(ns hyperopen.runtime.effect-adapters.vol-range-repair-replace
  "Runtime operations behind the Vol Range repair review and ladder update
  (HO-SPEC-014 v2.1 sections 8.1, 8.6, 10.2, 13.4).

  `repair-preview!` reads the ladder's book and account with awaited barrier
  reads, builds the pure proposal (`repair-plan/propose`) and freezes it at
  `[:vol-range :repair]` with a fresh proposal id. `repair!` sends exactly
  the frozen selected legs through `executor-place/repair!`, which runs the
  account admission section, journal-before-send, the final pre-HTTP guard
  and strict per-leg interpretation. Nothing here amends a leg, retries or
  resends.

  `replace-cancel!` freezes an update's membership from the
  journal-refreshed ladder, cancels that ladder and confirms the
  cancellation; `replace-confirm!` confirms again. Neither ever places an
  order: the replacement is placed only by the ordinary placement.

  All take the store and an already-built executor deps map (so this
  namespace never requires the execution adapter that dispatches to it) and
  resolve to the result map the adapter stores under
  `[:vol-range :execution :last]`. Each writes `[:vol-range :repair]` or
  `[:vol-range :replace]` only while it still refers to the same ladder
  (and, for a repair send, the same proposal; for an update, the same
  update)."
  (:require [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.application.replace-flow :as replace-flow]
            [hyperopen.vol-range.application.replace-state :as replace-state]
            [hyperopen.vol-range.application.revalidation :as revalidation]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-cancel :as executor-cancel]
            [hyperopen.vol-range.infrastructure.executor-place :as executor-place]
            [hyperopen.vol-range.infrastructure.executor-replace :as executor-replace]
            [hyperopen.vol-range.infrastructure.reads :as reads]))

(defn- grid-in-state [store grid-id] (get-in @store [:vol-range :execution :grids grid-id]))

(defn- same-scope? [a b]
  (= (select-keys a revalidation/identity-keys) (select-keys b revalidation/identity-keys)))

(defn- write-repair!
  "Apply `f` to the repair state only while it is still for `grid-id`."
  [store grid-id f]
  (swap! store (fn [state]
                 (if (= grid-id (get-in state [:vol-range :repair :grid-id]))
                   (update-in state [:vol-range :repair] f)
                   state))))

(defn settle-preview!
  "After `:repair-preview` finished, failed or timed out: a review still
  loading for `grid-id` shows the failure instead of loading forever."
  [store grid-id {:keys [status errors error]}]
  (when (not= :ok status)
    (write-repair! store grid-id #(if (= :loading (:status %))
                                    (assoc % :status :error :errors (vec errors) :error error)
                                    %))))

(defn- barrier-reads!
  "Awaited, uncached account and book reads for the ladder's scope, and the
  journal (for pending reservations, judged like admission)."
  [deps scope]
  (let [barrier-id ((:random-id! deps) "rb")]
    (-> (js/Promise.all
         #js [(reads/read-account-snapshot! (:post-info! deps)
                                            {:account-address (:account-address scope) :barrier-id barrier-id
                                             :now-ms! (:now-ms! deps)})
              (reads/read-book! (:post-info! deps) {:coin (:coin scope) :barrier-id barrier-id
                                                    :now-ms! (:now-ms! deps)})
              (ex/load! deps)])
        (.then (fn [arr] (let [[read book journal] (array-seq arr)] {:read read :book book :journal journal}))))))

(defn repair-preview!
  "Op `:repair-preview {:grid-id}`: freeze a fresh repair proposal for the
  ladder. Refused unless the live scope (the ticket's market and account) is
  the ladder's scope. An incomplete book read leaves every rejected order
  not repairable (`:repair/book-unavailable`); an incomplete account read or
  an unreadable journal is an error. Funds are judged net of the account's
  pending reservations, exactly as admission judges them. Orders the user
  excluded before **Refresh prices** stay excluded. Failures are written to
  the review by `settle-preview!`."
  [store deps {:keys [grid-id]}]
  (let [grid (grid-in-state store grid-id)
        live ((:live! deps))]
    (cond
      (nil? grid)
      (js/Promise.resolve {:status :error :error "Unknown ladder"})

      (not (same-scope? (:scope grid) (get-in live [:input :scope])))
      (js/Promise.resolve {:status :refused :errors [:guard/scope-changed]})

      :else
      (-> (barrier-reads! deps (:scope grid))
          (.then
           (fn [{:keys [read book journal]}]
             (cond
               (not (:complete? read))
               {:status :error :errors [:guard/account-unavailable] :error (:error read)}

               (not (:ok? journal))
               {:status :error :errors [:journal/unavailable] :error (:error journal)}

               :else
               (let [market (assoc (get-in live [:input :market]) :bbo
                                   (when (:complete? book) (select-keys book [:bid :ask :observed-at-ms])))
                     proposal (repair-plan/propose
                               {:grid grid :market market
                                :account (snapshots/account-snapshot-from-read (:scope grid) read live)
                                :pending (jr/pending-reservations (:records journal)
                                                                  (get-in grid [:scope :account-address]))
                                :fees (get-in live [:input :fees]) :now-ms (ex/now deps)})
                     proposal-id ((:random-id! deps) "rp")]
                 (write-repair! store grid-id
                                (fn [prev] (-> proposal
                                                (repair-plan/keep-exclusions (:excluded prev))
                                                (assoc :grid-id grid-id :status :ready :error nil :errors []
                                                       :proposal-id proposal-id))))
                 {:status :ok :proposal-id proposal-id}))))))))

(defn- may-have-sent? [{:keys [delivery status]}]
  (or (contains? #{:response :unknown} delivery) (= :error status)))

(defn repair!
  "Op `:repair {:grid-id :proposal-id :legs}`: send the frozen selected legs.
  Refused with `:repair/proposal-mismatch`, sending nothing, unless the
  proposal id is still the frozen one and `legs` equal its selected legs.
  The review closes once anything may have been sent; the dock then owns the
  outcome."
  [store deps {:keys [grid-id proposal-id legs]}]
  (let [proposal (get-in @store [:vol-range :repair])
        grid (grid-in-state store grid-id)
        frozen (repair-plan/selected-legs proposal)]
    (-> (cond
          (not (and (= grid-id (:grid-id proposal))
                    (= proposal-id (:proposal-id proposal))
                    (= :ready (:status proposal))
                    (seq frozen)
                    (= frozen legs)))
          (js/Promise.resolve {:status :refused :errors [:repair/proposal-mismatch]})

          (nil? grid)
          (js/Promise.resolve {:status :error :error "Unknown ladder"})

          :else
          (-> (executor-place/repair! deps {:grid grid :legs frozen})
              (.catch (fn [e] {:status :error :error (ex/error-text e)}))
              (.then (fn [result]
                       (when (may-have-sent? result)
                         (swap! store (fn [state]
                                        (if (= proposal-id (get-in state [:vol-range :repair :proposal-id]))
                                          (assoc-in state [:vol-range :repair] nil)
                                          state))))
                       result))))
        ;; Every result names the proposal it answers (the review shows only its own).
        (.then #(assoc % :proposal-id proposal-id)))))

;; ---------------------------------------------------------------------------
;; Ladder update (spec 10.2 steps 2-4)
;; ---------------------------------------------------------------------------

(defn- update-in-state [store] (get-in @store [:vol-range :replace]))

(defn- same-update? [a b]
  (and (some? (:grid-id a)) (= (:grid-id a) (:grid-id b)) (= (:started-at-ms a) (:started-at-ms b))))

(defn- write-update!
  "Apply `(f update state)` to the update only while it is still update `u`
  (same ladder, same start): a stopped or restarted update is never
  written."
  [store u f]
  (swap! store (fn [state]
                 (let [current (get-in state [:vol-range :replace])]
                   (if (same-update? u current)
                     (assoc-in state [:vol-range :replace] (f current state))
                     state)))))

(defn- check-failed [basis]
  (assoc (or basis {}) :ready? false :blocking [:replace/check-failed]))

(defn settle-update!
  "After `:replace-cancel` or `:replace-confirm` finished, failed or timed
  out: an error (including the 60 s bound) leaves the update of `grid-id`
  :blocked with `[:replace/check-failed]`, so Check again and Stop updating
  are offered and it never stays :canceling. Only the update the operation
  ran for (same ladder, and the `:update-started-at-ms` the adapter tags
  every result with) is written; a stopped or restarted one never is."
  [store grid-id {:keys [status update-started-at-ms]}]
  (when (= :error status)
    (swap! store (fn [state]
                   (if (same-update? {:grid-id grid-id :started-at-ms update-started-at-ms}
                                     (get-in state [:vol-range :replace]))
                     (update-in state [:vol-range :replace]
                                #(assoc % :phase :blocked :basis (check-failed (:basis %))))
                     state)))))

(defn- refresh-for-replacement!
  "Once the replacement may be reviewed: refresh the fee quote (the final
  guard needs it fresh) and, best effort, the store's spot balances. The
  planner uses the confirmation's own account read until the store's
  balances change (`with-store-balances`)."
  [{:keys [refresh-fees! refresh-spot!]} grid]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] (when refresh-fees! (refresh-fees!))))
      (.then (fn [_] (when refresh-spot! (refresh-spot! (get-in grid [:scope :account-address])))))
      (.catch (fn [_] nil))))

(defn- next-phase
  "A confirmation keeps a :ready update :ready (Refresh balances); otherwise
  `replace-flow/phase-after-confirm`."
  [current basis]
  (if (and (= :ready current) (:ready? basis)) :ready (replace-flow/phase-after-confirm basis)))

(defn- with-store-balances
  "The confirmation's `refreshed` read plus the store's own balances at this
  moment (after the spot refresh), so the planner can tell once the store
  has newer balances than that read (`replace-state/account-for-plan`).
  Recorded only while the ticket shows the ladder's scope."
  [refreshed state current now-ms]
  (cond-> refreshed
    (and (map? refreshed) (= (:scope-key current) (draft-scope/current-scope-key state)))
    (assoc :store-balances (replace-state/store-balances (snapshots/account-snapshot state now-ms)))))

(defn- settle-confirm!
  "Write a confirmation's outcome to update `u`; resolve to the op result."
  [store deps opts u {:keys [status grid basis refreshed errors error]}]
  (-> (if (= :ready status) (refresh-for-replacement! opts grid) (js/Promise.resolve nil))
      (.then (fn [_]
               (write-update! store u
                              (fn [current state]
                                (case status
                                  :ready (assoc current :phase (next-phase (:phase current) basis) :basis basis
                                                :refreshed (with-store-balances refreshed state current (ex/now deps))
                                                :confirmed-at-ms (ex/now deps))
                                  :blocked (assoc current :phase :blocked :basis basis :refreshed nil)
                                  (assoc current :phase :blocked :basis (check-failed (:basis current))))))
               (cond-> {:status status :grid grid}
                 (= :blocked status) (assoc :errors (:blocking basis))
                 (seq errors) (assoc :errors errors)
                 error (assoc :error error))))))

(defn- no-update []
  (js/Promise.resolve {:status :error :error "No update of this ladder is in progress"}))

(defn replace-cancel!
  "Op `:replace-cancel {:grid-id}` (spec 10.2 steps 2-4): refresh the ladder
  from the journal, freeze its membership from THAT copy (`replace-flow/freeze`;
  a membership frozen earlier is kept, so Cancel remaining orders never
  shrinks it), cancel that same copy, then confirm. Never requires the
  placement gate: cancellation must not depend on it. `opts` carries the
  adapter's `:refresh-fees!` and `:refresh-spot!`. Resolves to the confirm
  result, or to the cancel's own `:refused`/`:busy` status (and errors) when
  nothing could be canceled."
  [store deps {:keys [grid-id]} opts]
  (let [u (update-in-state store)
        grid (grid-in-state store grid-id)]
    (cond
      (nil? grid) (js/Promise.resolve {:status :error :error "Unknown ladder"})
      (not= grid-id (:grid-id u)) (no-update)
      :else
      (-> (ex/refresh-grid! deps grid)
          (.then
           (fn [refreshed]
             (let [frozen (if (seq (:frozen-cloids u))
                            (select-keys u [:frozen-cloids :filled-at-freeze])
                            (replace-flow/freeze refreshed))]
               (write-update! store u (fn [current _] (merge current frozen)))
               (.then
                (executor-cancel/cancel! deps {:grid refreshed})
                (fn [{:keys [status errors] :as canceled}]
                  (if (= :error status)
                    canceled
                    (-> (executor-replace/confirm! deps (assoc frozen :grid (:grid canceled)))
                        (.then #(settle-confirm! store deps opts u %))
                        (.then (fn [r]
                                 (cond-> r
                                   (contains? #{:refused :busy} status)
                                   (assoc :status status :errors errors)))))))))))
          (.catch (fn [e] {:status :error :error (ex/error-text e)}))))))

(defn replace-confirm!
  "Op `:replace-confirm {:grid-id}`: confirm the canceled ladder again for
  its frozen membership (Check again, Refresh balances). Reads only."
  [store deps {:keys [grid-id]} opts]
  (let [u (update-in-state store)
        grid (grid-in-state store grid-id)]
    (cond
      (nil? grid) (js/Promise.resolve {:status :error :error "Unknown ladder"})
      (not (and (= grid-id (:grid-id u)) (seq (:frozen-cloids u)))) (no-update)
      :else
      (-> (executor-replace/confirm! deps (assoc (select-keys u [:frozen-cloids :filled-at-freeze]) :grid grid))
          (.then #(settle-confirm! store deps opts u %))
          (.catch (fn [e] {:status :error :error (ex/error-text e)}))))))
