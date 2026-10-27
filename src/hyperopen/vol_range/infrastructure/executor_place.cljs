(ns hyperopen.vol-range.infrastructure.executor-place
  "Placement and repair of a Vol Range grid (HO-SPEC-014 v2.1 sections 8.1-8.4,
  8.6; VR-06, VR-09, VR-13, VR-16).

  Sequence (spec 8.1): verify scope, capability, permission and signer ->
  account admission section [load journal, refuse when anything in the market
  is unresolved or active, awaited barrier read, revalidate the FIXED vector
  against other pending reservations, assign CLOIDs, COMMIT intent + grid] ->
  sign and send through the delivery path, which commits the send attempt
  before HTTP and runs the final guard (scope, permission, cancel request,
  fresh barrier read and revalidation with the own reservation excluded)
  immediately before HTTP -> strict per-leg interpretation -> persist.
  Nothing here retries or resends."
  (:require [hyperopen.api.trading.order-result :as order-result]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.lifecycle-recovery :as recovery]
            [hyperopen.vol-range.application.revalidation :as revalidation]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.application.wire :as wire]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.domain.cloid :as cloid]
            [hyperopen.vol-range.infrastructure.executor :as ex]
            [hyperopen.vol-range.infrastructure.reads :as reads]))

(defn- same-scope? [a b]
  (= (select-keys a revalidation/identity-keys) (select-keys b revalidation/identity-keys)))

(defn- refusal
  "Spec 8.1 step 1, re-checked before HTTP."
  [live scope]
  (cond
    (not (:execution-enabled? live)) :capability/execution-disabled
    (:read-only? live) :capability/read-only
    (not (:agent-ready? live)) :signer/agent-not-ready
    (not (same-scope? scope (get-in live [:input :scope]))) :guard/scope-changed
    :else nil))

(defn- barrier-account!
  "Awaited raw account read: {:read raw :account ::account-snapshot}."
  [deps scope live]
  (-> (reads/read-account-snapshot! (:post-info! deps)
                                    {:account-address (:account-address scope)
                                     :barrier-id ((:random-id! deps) "rb")
                                     :now-ms! (:now-ms! deps)})
      (.then (fn [read] {:read read :account (snapshots/account-snapshot-from-read scope read live)}))))

(defn- admission-status [{:keys [status value error]}]
  (case status
    :ok value
    :busy {:status :busy}
    :timeout {:status :reconciliation-required :errors [:admission/timeout]}
    :unsafe-coordination {:status :coordination-unavailable}
    {:status :error :error (ex/error-text error)}))

(defn- final-guard
  "Spec 8.1 step 8: true to send, else the reasons not to. The grid is
  re-read so a cancel requested meanwhile refuses the send."
  [deps {:keys [scope grid-id operation-id check]}]
  (fn [_ctx]
    (let [live ((:live! deps))]
      (if-let [r (refusal live scope)]
        (js/Promise.resolve [r])
        (-> (js/Promise.all #js [(ex/load! deps) (barrier-account! deps scope live)])
            (.then (fn [arr]
                     (let [[journal {:keys [account]}] (array-seq arr)
                           stored (when (:ok? journal) (ex/stored-grid (:records journal) grid-id))]
                       (cond
                         (not (:ok? journal)) [:journal/unavailable]
                         (:cancel-requested-at-ms stored) [:grid/cancel-requested]
                         :else
                         (let [pending (jr/pending-reservations (:records journal) (:account-address scope) operation-id)
                               {:keys [ok? errors]} (check {:input (:input live) :account account
                                                            :pending pending :now-ms (ex/now deps)})]
                           (if ok? true errors)))))))))))

(defn- on-send-attempt
  "Commit the send attempt and the grid marked sending, merged under the grid
  lock. Rejects on failure, so nothing is sent (spec 8.1 step 7)."
  [deps grid-ref attempt-ref {:keys [operation-id attempt-id digest]}]
  (fn [{:keys [nonce expires-after]}]
    (let [at (ex/now deps)
          grid @grid-ref
          attempt (jr/attempt-record {:operation-id operation-id :attempt-id attempt-id
                                      :scope-key (:scope-key grid) :nonce nonce
                                      :expires-after-ms expires-after :action-digest digest :now-ms at})]
      (-> (ex/persist-grid! deps (lifecycle/mark-sending grid operation-id attempt-id at) [attempt])
          (.then (fn [{:keys [ok? grid error]}]
                   (when-not ok? (throw (js/Error. (str "Send attempt not recorded: " error))))
                   (reset! attempt-ref attempt)
                   (reset! grid-ref grid)
                   true))))))

(defn- send-and-record!
  "Send `action` for an admitted operation of `grid` and record everything."
  [deps {:keys [grid operation-id legs action scope expires-after check]}]
  (let [grid-ref (atom grid)
        attempt-ref (atom nil)
        ids {:operation-id operation-id :attempt-id ((:random-id! deps) "attempt")
             :digest (wire/action-digest action)}]
    (-> ((:send! deps)
         action
         {:expires-after expires-after
          :vault-address (:vault-address scope)
          :on-send-attempt! (on-send-attempt deps grid-ref attempt-ref ids)
          :before-send! (final-guard deps {:scope scope :grid-id (:grid-id grid) :operation-id operation-id
                                           :check check})})
        (.then
         (fn [{:keys [delivery body error phase nonce]}]
           (let [at (ex/now deps)
                 normalized (when (= :response delivery) (order-result/normalize-order-response legs body))
                 ;; A late attempt commit (pre-send timeout) may still land; the
                 ;; outcome is written under the same record id either way.
                 base-attempt (or @attempt-ref
                                  (jr/attempt-record {:operation-id operation-id :attempt-id (:attempt-id ids)
                                                      :scope-key (:scope-key grid) :nonce nonce
                                                      :expires-after-ms expires-after
                                                      :action-digest (:digest ids) :now-ms at}))
                 grid (evidence/apply-placement-delivery @grid-ref operation-id
                                                         {:delivery delivery
                                                          :leg-results (:leg-results normalized)
                                                          :error (or error (some-> phase name))}
                                                         at)
                 attempt (some-> base-attempt
                                 (jr/with-outcome {:delivery delivery
                                                   :response-category (:response-category normalized)
                                                   :leg-results (:leg-results normalized)}
                                                  at))]
             (-> (ex/persist-grid! deps grid (if attempt [attempt] []))
                 (.then (fn [{:keys [ok? grid] persist-error :error}]
                          {:status (lifecycle/status grid)
                           :delivery delivery
                           :phase phase
                           :error error
                           :response-category (:response-category normalized)
                           :grid grid
                           :counts (lifecycle/counts grid)
                           :persisted? ok?
                           :persist-error persist-error})))))))))

(defn- admit-and-send!
  "Admission section around `admit!` (-> Promise of {:status :admitted ...}
  or a refusal), then the send."
  [deps scope operation-id check admit!]
  (-> ((:admission! deps) {:network (:network scope) :account-address (:account-address scope)} admit!)
      (.then admission-status)
      (.then (fn [{:keys [status] :as admitted}]
               (if (= :admitted status)
                 (send-and-record! deps (assoc admitted :operation-id operation-id :scope scope :check check))
                 admitted)))
      (.catch (fn [e] {:status :error :error (ex/error-text e)}))))

(defn- commit-admitted!
  "Commit intent + grid together (one transaction, merged under the grid
  lock), inside the admission section."
  [deps intent grid admitted]
  (-> (ex/persist-grid! deps grid [intent])
      (.then (fn [{:keys [ok? grid]}]
               (if ok? (assoc admitted :grid grid) {:status :journal-unavailable})))))

;; ---------------------------------------------------------------------------
;; Placement
;; ---------------------------------------------------------------------------

(defn- admit-placement!
  [deps {:keys [frozen-plan scope scope-key live operation-id check]}]
  (-> (ex/load! deps)
      (.then
       (fn [{:keys [ok? records] :as journal}]
         (let [blockers (when ok? (ex/placement-blockers journal scope-key))]
           (cond
             (not ok?) {:status :journal-unavailable}
             (seq blockers) {:status :blocked :errors blockers}
             :else
             (.then
              (barrier-account! deps scope live)
              (fn [{:keys [read account]}]
                (let [found (ex/discovered-orders (:open-orders read) scope (jr/grids records nil))
                      {:keys [ok? errors]} (check {:input (:input live) :account account
                                                   :pending (jr/pending-reservations records (:account-address scope))
                                                   :now-ms (ex/now deps)})
                      plan-legs (wire/ordered-legs (:legs frozen-plan))
                      assigned (when ok? (cloid/assign-cloids plan-legs ((:cloid-suffixes! deps) (count plan-legs))))
                      action (some->> (:legs assigned) (wire/order-action (:asset-id scope)))]
                  (cond
                    (not (:complete? read)) {:status :admission-failed :errors [:guard/account-unavailable]}
                    (seq found) {:status :blocked :errors [:grid/recovered-orders]}
                    (not ok?) {:status :admission-failed :errors errors}
                    (:error assigned) {:status :admission-failed :errors [(:error assigned)]}
                    (nil? action) {:status :admission-failed :errors [:wire/invalid-action]}
                    :else
                    (let [at (ex/now deps)
                          expires (+ at ex/action-deadline-ms)
                          legs (:legs assigned)
                          totals (:totals frozen-plan)
                          grid (lifecycle/new-grid {:grid-id ((:random-id! deps) "grid") :scope scope
                                                    :scope-key scope-key :operation-id operation-id
                                                    :plan-id (:plan-id frozen-plan) :legs legs
                                                    :expires-after-ms expires :now-ms at})
                          intent (jr/intent-record
                                  {:operation-id operation-id :scope scope :scope-key scope-key
                                   :plan-id (:plan-id frozen-plan) :input-fingerprint (:input-fingerprint frozen-plan)
                                   :action-digest (wire/action-digest action) :legs legs
                                   :reservations {:usdc (or (get-in totals [:buy :max-debit]) "0")
                                                  :base (or (get-in totals [:sell :base-committed]) "0")}
                                   :now-ms at})]
                      (commit-admitted! deps intent grid {:status :admitted :grid grid :legs legs
                                                          :action action :expires-after expires}))))))))))))

(defn place!
  "Place a reviewed :ready plan. `frozen-plan` and `frozen-scope` come from
  the review; nothing in them is amended. Resolves to a typed result:
  {:status :refused|:blocked|:busy|:admission-failed|:journal-unavailable|...}
  before anything is sent, else the grid status after the send."
  [deps {:keys [frozen-plan frozen-scope]}]
  (let [live ((:live! deps))
        scope frozen-scope
        r (or (when (not= :ready (:status frozen-plan)) :plan/not-ready) (refusal live scope))]
    (if r
      (js/Promise.resolve {:status :refused :errors [r]})
      (let [operation-id ((:random-id! deps) "op")
            check (fn [ctx] (revalidation/revalidate (assoc ctx :frozen-plan frozen-plan :frozen-scope scope)))]
        (admit-and-send! deps scope operation-id check
              #(admit-placement! deps {:frozen-plan frozen-plan :scope scope :scope-key (contracts/scope-key scope)
                                       :live live :operation-id operation-id :check check}))))))

;; ---------------------------------------------------------------------------
;; Repair (spec 8.6, 13.4)
;; ---------------------------------------------------------------------------

(defn- repair-context
  "What a repair check needs from the CURRENT grid."
  [grid]
  {:candidates (into {} (map (juxt :logical-id identity)) (lifecycle/repair-candidates grid))
   :survivor-prices (->> (lifecycle/current-legs grid)
                         (remove lifecycle/definitively-not-placed?)
                         (map :price))})

(defn- admit-repair!
  [deps {:keys [grid legs scope live operation-id check-ref]}]
  (-> (ex/load! deps)
      (.then
       (fn [{:keys [ok? records unreadable] :as journal}]
         (let [grid (recovery/merge-grids (when ok? (ex/stored-grid records (:grid-id grid))) grid)
               blockers (when ok? (ex/placement-blockers journal (:scope-key grid) (:grid-id grid)))]
           (cond
             (not ok?) {:status :journal-unavailable}
             (seq unreadable) {:status :blocked :errors [:journal/unreadable-records]}
             (:cancel-requested-at-ms grid) {:status :blocked :errors [:grid/cancel-requested]}
             (contains? #{:closed :not-placed} (lifecycle/status grid)) {:status :blocked :errors [:grid/closed]}
             (lifecycle/unresolved? grid) {:status :blocked :errors [:grid/unresolved]}
             (seq blockers) {:status :blocked :errors blockers}
             :else
             (.then
              (barrier-account! deps scope live)
              (fn [{:keys [read account]}]
                (let [check (fn [ctx] (revalidation/revalidate-repair
                                       (merge ctx (repair-context grid) {:grid-scope scope :legs legs})))
                      _ (reset! check-ref check)
                      found (ex/discovered-orders (:open-orders read) scope (jr/grids records nil))
                      {:keys [ok? errors reservations]}
                      (check {:input (:input live) :account account :now-ms (ex/now deps)
                              :pending (jr/pending-reservations records (:account-address scope))})
                      assigned (when ok? (cloid/assign-cloids legs ((:cloid-suffixes! deps) (count legs))))
                      at (ex/now deps)
                      expires (+ at ex/action-deadline-ms)
                      added (when (:legs assigned)
                              (lifecycle/add-repair grid {:operation-id operation-id :plan-id nil
                                                          :legs (:legs assigned) :expires-after-ms expires
                                                          :now-ms at}))
                      action (when (:grid added) (wire/order-action (:asset-id scope) (:legs assigned)))]
                  (cond
                    (not (:complete? read)) {:status :admission-failed :errors [:guard/account-unavailable]}
                    (seq found) {:status :blocked :errors [:grid/recovered-orders]}
                    (not ok?) {:status :admission-failed :errors errors}
                    (:error assigned) {:status :admission-failed :errors [(:error assigned)]}
                    (:error added) {:status :admission-failed :errors [(:error added)]}
                    (nil? action) {:status :admission-failed :errors [:wire/invalid-action]}
                    :else
                    (let [intent (jr/intent-record
                                  {:operation-id operation-id :scope scope :scope-key (:scope-key grid)
                                   :plan-id nil :input-fingerprint "repair"
                                   :action-digest (wire/action-digest action)
                                   :legs (mapv #(assoc % :leg-id (:logical-id %)) (:legs assigned))
                                   :reservations reservations :now-ms at})]
                      (commit-admitted! deps intent (:grid added)
                                        {:status :admitted :grid (:grid added)
                                         :legs (wire/ordered-legs (:legs assigned))
                                         :action action :expires-after expires}))))))))))))

(defn repair!
  "Place a reviewed repair for definitively rejected logical members of
  `grid` (spec 8.6, 13.4). `legs` are [{:logical-id :price :size}] with a
  newly reviewed price and at most the rejected quantity; each gets a NEW
  CLOID appended to its member. Only these legs are sent: surviving legs are
  never replayed. Refused once the ladder is canceled or closed."
  [deps {:keys [grid legs]}]
  (let [live ((:live! deps))
        scope (:scope grid)
        r (or (when (:recovered? grid) :repair/recovered-grid) (refusal live scope))
        members (:members grid)
        legs (mapv (fn [l] (assoc l :side (get-in members [(:logical-id l) :side])
                                  :rung-index (get-in members [(:logical-id l) :rung-index])))
                   legs)]
    (if r
      (js/Promise.resolve {:status :refused :errors [r]})
      (let [operation-id ((:random-id! deps) "op")
            ;; The check is built inside admission from the journal's newest
            ;; grid; the final guard reuses that same check.
            check-ref (atom nil)
            check (fn [ctx] (@check-ref ctx))]
        (admit-and-send! deps scope operation-id check
              #(admit-repair! deps {:grid grid :legs legs :scope scope :live live
                                    :operation-id operation-id :check-ref check-ref}))))))
