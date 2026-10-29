(ns hyperopen.vol-range.replace-actions-test
  "Ladder update action handlers (HO-SPEC-014 sections 10.2, 11.2, 13.4;
  VR-15, VR-16, VR-18): exact effects, nothing for a stale or busy update,
  and Stop updating never reaches an order operation."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.ticket-model :as ticket-model]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.replace-actions :as a]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def now 1700000000000)

(def plan (planner/prepare-plan (fixtures/plan-input)))

(defn- cloid [i] (str "0x" (.padStart (str i) 32 "0")))

(defn- placed-grid
  "The 13.2 ladder in the ticket's scope with the `rejected` members
  definitively rejected and the others resting."
  ([] (placed-grid #{}))
  ([rejected] (placed-grid rejected {:settings (ladder-settings/settings-from-plan plan fixtures/draft-13-2)}))
  ([rejected extra]
   (let [legs (vec (map-indexed (fn [i l] (assoc l :cloid (cloid (inc i)))) (:legs plan)))
         g (-> (lifecycle/new-grid (merge {:grid-id "g1" :scope {:account-address sf/owner}
                                           :scope-key (draft-scope/current-scope-key (sf/base-state))
                                           :operation-id "op" :plan-id "plan-1" :legs legs
                                           :model (select-keys (:model plan) [:anchor :volatility :tilt :shifted-anchor])
                                           :expires-after-ms 31000 :now-ms 1000}
                                          extra))
               (lifecycle/mark-sending "op" "a1" 1500))]
     (evidence/apply-placement-delivery
      g "op"
      {:delivery :response
       :leg-results (map-indexed (fn [i {:keys [leg-id] :as l}]
                                   (if (contains? rejected leg-id)
                                     {:cloid (:cloid l) :placement-state :rejected :reason "Post only"}
                                     {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted
                                      :order-state :resting}))
                                 legs)}
      2000))))

(defn- state-with
  ([grid] (state-with grid nil))
  ([grid update]
   (-> (sf/base-state)
       (assoc-in [:vol-range :execution] {:enabled? true :grids {"g1" grid}})
       (assoc-in [:vol-range :replace]
                 (when update (merge {:grid-id "g1" :scope-key (:scope-key grid) :started-at-ms 5
                                      :dialog-open? true :include-rejected #{}}
                                     update))))))

(defn- draft [state] (draft-scope/effective-draft state))
(defn- apply-all [state effects] (sf/apply-effects state effects))

(def ready-basis
  {:ready? true :blocking [] :remaining {:buy "40" :sell "27"} :filled {:buy "0" :sell "3"}
   :filled-during-update {:buy "0" :sell "3"}
   :rejected [{:logical-id "buy-0" :side :buy :rung-index 0 :price "76.4" :original-base "7" :reason "Post only"}]})

(def canceled-update
  {:phase :canceled :frozen-cloids [(cloid 2) (cloid 3)] :filled-at-freeze {} :basis ready-basis})

;; ---------------------------------------------------------------------------
;; Preview update
;; ---------------------------------------------------------------------------

(deftest preview-loads-the-live-remainder-into-the-ticket-test
  (let [s (assoc-in (state-with (placed-grid #{"buy-0"})) [:vol-range :ui :review-open?] true)
        effects (a/preview-vol-ladder-update s "g1" now)
        after (apply-all s effects)]
    (is (= [:effects/save-many] (mapv first effects)) "one write, no order operation")
    (is (= {:grid-id "g1" :scope-key (draft-scope/current-scope-key s) :phase :preview :dialog-open? true
            :started-at-ms now :settings-recorded? true :frozen-cloids nil :filled-at-freeze nil :basis nil
            :include-rejected #{} :refreshed nil :confirmed-at-ms nil}
           (get-in after [:vol-range :replace])))
    (testing "quantities are the live remainder, in base units, with the deployed settings"
      (is (= {:buy "33" :sell "30"} (lifecycle/live-remaining (placed-grid #{"buy-0"}))))
      (is (= ["33" :base true] ((juxt :quantity :amount-unit :enabled?) (:buy (draft after)))))
      (is (= ["30" :base true] ((juxt :quantity :amount-unit :enabled?) (:sell (draft after)))))
      (is (= "2900" (get-in (draft after) [:buy :max-usdc-debit])) "the deployed buy cap is kept")
      (is (= "190" (get-in (draft after) [:inventory :maximum]))))
    (testing "a frozen review is discarded"
      (is (false? (get-in after [:vol-range :ui :review-open?])))
      (is (nil? (get-in after [:vol-range :frozen-review]))))))

(deftest preview-shows-the-ticket-in-the-same-write-test
  ;; One dispatch expands every action against the same state, so the
  ;; preview switches the ticket to Scale / Volatility range itself.
  (let [s (-> (state-with (placed-grid))
              (assoc-in [:order-form :type] :limit)
              (assoc-in [:order-form :scale :mode] :price-range))
        effects (a/preview-vol-ladder-update s "g1" now)
        after (apply-all s effects)]
    (is (false? (ticket-model/visible? s)))
    (is (= [:effects/save-many] (mapv first effects)) "no candle fetch here; the freshness watcher fetches")
    (is (true? (ticket-model/visible? after)))
    (is (= :scale (get-in after [:order-form :type])))
    (is (= ["40" "30"] [(get-in (draft after) [:buy :quantity]) (get-in (draft after) [:sell :quantity])]))
    (is (= :preview (get-in after [:vol-range :replace :phase])))))

(deftest preview-without-recorded-settings-keeps-the-ticket-draft-test
  (let [g (placed-grid #{"buy-0"} {})
        s (-> (state-with g)
              (assoc-in [:order-form :scale :vol-range :buy :count] 6))
        after (apply-all s (a/preview-vol-ladder-update s "g1" now))]
    (is (nil? (:settings g)))
    (is (false? (get-in after [:vol-range :replace :settings-recorded?])))
    (is (= 6 (get-in (draft after) [:buy :count])) "the ticket's own settings stay")
    (is (= ["33" "30"] [(get-in (draft after) [:buy :quantity]) (get-in (draft after) [:sell :quantity])]))))

(deftest preview-is-refused-test
  (let [s (state-with (placed-grid))]
    (is (seq (a/preview-vol-ladder-update s "g1" now)))
    (doseq [[label state] [["unknown ladder" (assoc-in s [:vol-range :execution :grids] {})]
                           ["placement off" (assoc-in s [:vol-range :execution :enabled?] false)]
                           ["recovered" (state-with (assoc (placed-grid) :recovered? true))]
                           ["being canceled" (state-with (assoc (placed-grid) :cancel-requested-at-ms 3000))]
                           ["another market or account" (state-with (assoc (placed-grid) :scope-key "elsewhere"))]
                           ["unresolved" (state-with (assoc-in (placed-grid) [:legs (cloid 2) :placement-state] :unknown))]
                           ["nothing placed" (state-with (placed-grid (set (map :leg-id (:legs plan)))))]
                           ["repair review open" (assoc-in s [:vol-range :repair] {:grid-id "g1" :status :ready})]
                           ["repair in flight" (assoc-in s [:vol-range :execution :busy [:repair "g1"]] true)]
                           ["update already canceling" (state-with (placed-grid) {:phase :canceling})]
                           ["update canceled" (state-with (placed-grid) canceled-update)]
                           ["update op in flight" (-> (state-with (placed-grid) {:phase :preview})
                                                      (assoc-in [:vol-range :execution :busy [:replace-confirm "g1"]] true))]
                           ;; Stop updating while the cancel runs: the store's
                           ;; copy has no :cancel-requested-at-ms until it ends.
                           ["a stopped update's cancel still runs"
                            (assoc-in s [:vol-range :execution :busy [:replace-cancel "g1"]] true)]
                           ["a stopped update's check still runs"
                            (assoc-in s [:vol-range :execution :busy [:replace-confirm "g1"]] true)]]]
      (is (= [] (a/preview-vol-ladder-update state "g1" now)) label))
    (testing "an update still in preview may be previewed again"
      (is (seq (a/preview-vol-ladder-update (state-with (placed-grid) {:phase :preview}) "g1" now))))))

;; ---------------------------------------------------------------------------
;; Dialog
;; ---------------------------------------------------------------------------

(deftest dialog-open-close-and-keyboard-test
  (let [s (state-with (placed-grid) {:phase :preview :dialog-open? false})]
    (is (= [[:effects/save-many [[[:vol-range :replace :dialog-open?] true]]]] (a/open-vol-ladder-update s)))
    (is (= [[:effects/save-many [[[:vol-range :replace :dialog-open?] false]]]] (a/close-vol-ladder-update s)))
    (is (= (a/close-vol-ladder-update s) (a/handle-vol-ladder-update-keydown s "Escape")))
    (is (= [] (a/handle-vol-ladder-update-keydown s "Enter")) "Enter never cancels or places")
    (is (= [] (a/open-vol-ladder-update (state-with (assoc (placed-grid) :scope-key "elsewhere") {:phase :preview})))
        "a stale update does not reopen")
    (is (= [] (a/open-vol-ladder-update (state-with (placed-grid)))))
    (is (= [] (a/close-vol-ladder-update (state-with (placed-grid)))))))

;; ---------------------------------------------------------------------------
;; Cancel and check
;; ---------------------------------------------------------------------------

(deftest cancel-for-update-test
  (let [s (state-with (placed-grid) {:phase :preview})
        u (get-in s [:vol-range :replace])]
    (is (= [[:effects/save-many [[[:vol-range :replace] (assoc u :phase :canceling)]]]
            [:effects/vol-range-execute :replace-cancel {:grid-id "g1"}]]
           (a/cancel-vol-ladder-for-update s)))
    (testing "Cancel remaining orders while old orders are still open"
      (let [b (state-with (placed-grid) {:phase :blocked :frozen-cloids [(cloid 1)]
                                         :basis {:ready? false :blocking [:replace/legs-still-open]}})]
        (is (= [:effects/save-many :effects/vol-range-execute] (mapv first (a/cancel-vol-ladder-for-update b))))))
    (testing "never for other blocks, other phases, stale or busy updates"
      (doseq [[label state]
              [["unresolved" (state-with (placed-grid) {:phase :blocked :basis {:blocking [:replace/unresolved-outcomes]}})]
               ["check failed" (state-with (placed-grid) {:phase :blocked :basis {:blocking [:replace/check-failed]}})]
               ["canceling" (state-with (placed-grid) {:phase :canceling})]
               ["canceled" (state-with (placed-grid) canceled-update)]
               ["stale" (state-with (assoc (placed-grid) :scope-key "elsewhere") {:phase :preview})]
               ["no update" (state-with (placed-grid))]
               ["cancel in flight" (assoc-in s [:vol-range :execution :busy [:replace-cancel "g1"]] true)]
               ["confirm in flight" (assoc-in s [:vol-range :execution :busy [:replace-confirm "g1"]] true)]
               ["dock cancel in flight" (assoc-in s [:vol-range :execution :busy [:cancel "g1"]] true)]]]
        (is (= [] (a/cancel-vol-ladder-for-update state)) label)))
    (testing "the gate is not consulted: cancellation never depends on it"
      (is (seq (a/cancel-vol-ladder-for-update (assoc-in s [:vol-range :execution :enabled?] false)))))))

(deftest check-again-and-refresh-balances-test
  (let [confirm [[:effects/vol-range-execute :replace-confirm {:grid-id "g1"}]]]
    (doseq [phase [:blocked :canceled :ready]]
      (is (= confirm (a/check-vol-ladder-update (state-with (placed-grid) (assoc canceled-update :phase phase))))
          (str phase)))
    (is (= [] (a/check-vol-ladder-update (state-with (placed-grid) {:phase :preview}))))
    (is (= [] (a/check-vol-ladder-update (state-with (placed-grid) {:phase :canceling :frozen-cloids [(cloid 1)]}))))
    (is (= [] (a/check-vol-ladder-update (state-with (placed-grid) (dissoc canceled-update :frozen-cloids)))))
    (is (= [] (a/check-vol-ladder-update (-> (state-with (placed-grid) canceled-update)
                                             (assoc-in [:vol-range :execution :busy [:replace-confirm "g1"]] true)))))
    (is (= [] (a/check-vol-ladder-update (state-with (assoc (placed-grid) :scope-key "elsewhere") canceled-update))))))

;; ---------------------------------------------------------------------------
;; Replacement
;; ---------------------------------------------------------------------------

(deftest toggle-rejected-members-test
  (let [s (state-with (placed-grid) canceled-update)]
    (is (= [[:effects/save-many [[[:vol-range :replace :include-rejected] #{"buy-0"}]]]]
           (a/toggle-vol-ladder-update-rejected s "buy-0")))
    (is (= [[:effects/save-many [[[:vol-range :replace :include-rejected] #{}]]]]
           (a/toggle-vol-ladder-update-rejected (assoc-in s [:vol-range :replace :include-rejected] #{"buy-0"}) "buy-0")))
    (is (= [] (a/toggle-vol-ladder-update-rejected s "sell-1")) "not a listed rejected member")
    (is (= [] (a/toggle-vol-ladder-update-rejected (state-with (placed-grid) (assoc canceled-update :phase :ready)) "buy-0")))))

(deftest review-replacement-writes-the-frozen-remainder-test
  (let [s (state-with (placed-grid) canceled-update)
        effects (a/review-vol-ladder-replacement s)
        after (apply-all s effects)]
    (is (= [:effects/save-many] (mapv first effects)) "never places anything")
    (is (= ["40" "27"] [(get-in (draft after) [:buy :quantity]) (get-in (draft after) [:sell :quantity])]))
    (is (= {:phase :ready :dialog-open? false}
           (select-keys (get-in after [:vol-range :replace]) [:phase :dialog-open?])))
    (testing "a selected rejected member adds its full quantity to its side"
      (let [after (apply-all s (a/review-vol-ladder-replacement
                                (assoc-in s [:vol-range :replace :include-rejected] #{"buy-0"})))]
        (is (= "47" (get-in (draft after) [:buy :quantity])))))
    (testing "only from a canceled update whose basis is ready"
      (is (= [] (a/review-vol-ladder-replacement (assoc-in s [:vol-range :replace :basis :ready?] false))))
      (is (= [] (a/review-vol-ladder-replacement (state-with (placed-grid) (assoc canceled-update :phase :blocked)))))
      (is (= [] (a/review-vol-ladder-replacement (state-with (assoc (placed-grid) :scope-key "elsewhere") canceled-update)))))))

;; ---------------------------------------------------------------------------
;; Stop updating
;; ---------------------------------------------------------------------------

(defn- order-operation? [effects]
  (some #(= :effects/vol-range-execute (first %)) effects))

(deftest stop-updating-never-touches-orders-test
  (testing "before the cancel"
    (let [effects (a/stop-vol-ladder-update (state-with (placed-grid) {:phase :preview}) now)]
      (is (= [[:effects/save-many [[[:vol-range :replace] nil]
                                   [[:vol-range :execution :last]
                                    {:op :replace-stop :status :stopped :grid-id "g1" :old-ladder :unchanged
                                     :update-started-at-ms 5 :at-ms now}]]]]
             effects))))
  (doseq [phase [:canceling :blocked :canceled :ready]]
    (let [s (state-with (placed-grid) (assoc canceled-update :phase phase))
          effects (a/stop-vol-ladder-update s now)
          after (apply-all s effects)]
      (is (not (order-operation? effects)) (str phase))
      (is (nil? (get-in after [:vol-range :replace])))))
  (testing "a review frozen with this update's lineage closes too"
    (let [s (-> (state-with (placed-grid) (assoc canceled-update :phase :ready))
                (assoc-in [:vol-range :frozen-review] {:status :ready})
                (assoc-in [:vol-range :frozen-context] {:replace {:predecessor {:grid-id "g1"}}})
                (assoc-in [:vol-range :ui :review-open?] true))
          after (apply-all s (a/stop-vol-ladder-update s now))]
      (is (false? (get-in after [:vol-range :ui :review-open?])))
      (is (nil? (get-in after [:vol-range :frozen-review])))
      (is (nil? (get-in after [:vol-range :frozen-context])))))
  (testing "the stop note never calls old orders canceled that may still be open"
    (let [old-ladder (fn [update]
                       (let [s (state-with (placed-grid) update)]
                         (get-in (apply-all s (a/stop-vol-ladder-update s now)) [:vol-range :execution :last :old-ladder])))
          blocked (fn [codes] (assoc canceled-update :phase :blocked :basis {:ready? false :blocking codes}))]
      (is (= :unsettled (old-ladder {:phase :canceling})) "the cancel is still running")
      (is (= :unsettled (old-ladder (blocked [:replace/unresolved-outcomes :replace/legs-still-open])))
          "the cancel delivery was lost and the venue still lists the orders")
      (is (= :unsettled (old-ladder (blocked [:replace/legs-still-open]))) "the cancel was refused")
      (is (= :unsettled (old-ladder (blocked [:replace/check-failed]))) "the check failed or timed out")
      (is (= :unsettled (old-ladder (blocked []))))
      (is (= :canceled (old-ladder (blocked [:replace/fills-incomplete]))) "every order canceled; fills pending")
      (is (= :canceled (old-ladder canceled-update)))
      (is (= :canceled (old-ladder (assoc canceled-update :phase :ready))))))
  (testing "a stale update can always be stopped; nothing to stop is a no-op"
    (is (seq (a/stop-vol-ladder-update (state-with (assoc (placed-grid) :scope-key "elsewhere") {:phase :canceled}) now)))
    (is (= [] (a/stop-vol-ladder-update (state-with (placed-grid)) now)))))
