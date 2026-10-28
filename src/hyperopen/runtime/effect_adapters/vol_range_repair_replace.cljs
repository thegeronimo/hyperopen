(ns hyperopen.runtime.effect-adapters.vol-range-repair-replace
  "Runtime operations behind the Vol Range repair review (HO-SPEC-014 v2.1
  sections 8.1, 8.6, 13.4).

  `repair-preview!` reads the ladder's book and account with awaited barrier
  reads, builds the pure proposal (`repair-plan/propose`) and freezes it at
  `[:vol-range :repair]` with a fresh proposal id. `repair!` sends exactly
  the frozen selected legs through `executor-place/repair!`, which runs the
  account admission section, journal-before-send, the final pre-HTTP guard
  and strict per-leg interpretation. Nothing here amends a leg, retries or
  resends.

  Both take the store and an already-built executor deps map (so this
  namespace never requires the execution adapter that dispatches to it) and
  resolve to the result map the adapter stores under
  `[:vol-range :execution :last]`. Each writes `[:vol-range :repair]` only
  while it still refers to the same ladder (and, for a send, the same
  proposal)."
  (:require [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.application.revalidation :as revalidation]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.executor-place :as executor-place]
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
  "Awaited, uncached account and book reads for the ladder's scope."
  [deps scope]
  (let [barrier-id ((:random-id! deps) "rb")]
    (-> (js/Promise.all
         #js [(reads/read-account-snapshot! (:post-info! deps)
                                            {:account-address (:account-address scope) :barrier-id barrier-id
                                             :now-ms! (:now-ms! deps)})
              (reads/read-book! (:post-info! deps) {:coin (:coin scope) :barrier-id barrier-id
                                                    :now-ms! (:now-ms! deps)})])
        (.then (fn [arr] (let [[read book] (array-seq arr)] {:read read :book book}))))))

(defn repair-preview!
  "Op `:repair-preview {:grid-id}`: freeze a fresh repair proposal for the
  ladder. Refused unless the live scope (the ticket's market and account) is
  the ladder's scope. An incomplete book read leaves every rejected order
  not repairable (`:repair/book-unavailable`); an incomplete account read is
  an error. Failures are written to the review by `settle-preview!`."
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
           (fn [{:keys [read book]}]
             (if-not (:complete? read)
               {:status :error :errors [:guard/account-unavailable] :error (:error read)}
               (let [market (assoc (get-in live [:input :market]) :bbo
                                   (when (:complete? book) (select-keys book [:bid :ask :observed-at-ms])))
                     proposal (repair-plan/propose
                               {:grid grid :market market
                                :account (snapshots/account-snapshot-from-read (:scope grid) read live)
                                :fees (get-in live [:input :fees]) :now-ms (ex/now deps)})
                     proposal-id ((:random-id! deps) "rp")]
                 (write-repair! store grid-id
                                (fn [_] (assoc proposal :grid-id grid-id :status :ready :error nil :errors []
                                               :proposal-id proposal-id)))
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
