(ns hyperopen.vol-range.application.plan-state-memo-test
  "Memoization must never re-plan on provenance alone, and a cached offer must
  never be applied once the live book makes it unready (review findings)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.actions :as actions]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.plan-state :as plan-state]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.state-fixtures :as sf]))

(defn- with-fees [f]
  (with-redefs [snapshots/fee-snapshot sf/with-synthetic-fees] (f)))

(deftest provenance-alone-never-replans-test
  (with-fees
    (fn []
      (let [state (sf/base-state)
            a (plan-state/plan-for-state state sf/now-ms)
            b (plan-state/plan-for-state (-> state
                                             (assoc-in [:market-observations :active-asset-ctx "@107"] (+ sf/now-ms 5))
                                             (assoc-in [:orderbooks "@107" :timestamp] (+ sf/now-ms 7)))
                                         sf/now-ms)]
        (is (identical? a b) "a new receipt time alone hits the memo")
        (is (= (:plan-id a) (:plan-id b)))
        (testing "the receipt time no longer lives in the shared :active-assets data"
          (is (nil? (get-in state [:active-assets :contexts "@107" :receivedAtMs]))))))))

(deftest a-cached-offer-is-rechecked-against-the-live-book-test
  (with-fees
    (fn []
      (let [crossing (assoc-in (sf/base-state) [:orderbooks "@107"]
                               {:bids [{:px "75.89"}] :asks [{:px "75.91"}] :timestamp 1})
            offer (some #(when (= :start-buys-at (:id %)) %)
                        (:remediations (plan-state/plan-for-state crossing sf/now-ms)))]
        (is (= [[[:buy :start-sigma] "0.90"]] (:patch offer)))
        (is (true? (plan-state/offer-still-ready? crossing sf/now-ms offer)))
        (testing "the ask falls further: the same leg still crosses but 0.90σ no longer clears it"
          (let [moved (assoc-in crossing [:orderbooks "@107"] {:bids [{:px "74.4"}] :asks [{:px "74.5"}] :timestamp 2})]
            (is (false? (plan-state/offer-still-ready? moved sf/now-ms offer)))
            (with-redefs [plan-state/plan-for-state (fn [_ _] {:remediations [offer]})]
              (is (= [] (actions/apply-vol-range-remediation moved :start-buys-at sf/now-ms))
                  "a stale offer that is no longer ready is never applied"))
            (is (not= offer (some #(when (= :start-buys-at (:id %)) %)
                                  (:remediations (plan-state/plan-for-state moved sf/now-ms))))
                "crossing errors key the memo on the book, so the offer is recomputed")))))))

(deftest an-update-changes-the-planner-account-only-for-its-scope-test
  ;; Spec 10.2 step 4, 13.4: after the cancel is confirmed the replacement is
  ;; planned from the account read after the cancel, not the pre-fill store.
  (with-fees
    (fn []
      (let [state (-> (sf/base-state)
                      (assoc-in [:order-form :scale :vol-range :inventory :tilt-enabled?] true)
                      (assoc-in [:order-form :scale :vol-range :buy :max-usdc-debit] "2990"))
            key (draft-scope/current-scope-key state)
            refreshed (assoc (snapshots/account-snapshot state sf/now-ms)
                             :base {:total "147" :hold "0" :available "147"})
            with-update (fn [s scope-key]
                          (-> s
                              (assoc-in [:vol-range :execution :grids "old"] {:grid-id "old" :scope-key key :legs {}})
                              (assoc-in [:vol-range :replace] {:grid-id "old" :scope-key scope-key :phase :canceled
                                                               :refreshed {:requested-at-ms 1 :account refreshed}})))
            before (plan-state/context-for-state state sf/now-ms)
            after (plan-state/context-for-state (with-update state key) sf/now-ms)
            elsewhere (plan-state/context-for-state (with-update state "elsewhere") sf/now-ms)]
        (is (= "150" (get-in before [:account :base :total])))
        (is (= "147" (get-in after [:account :base :total])) "the refreshed account is the planner's")
        (is (not= (:plan-id (:plan before)) (:plan-id (:plan after))) "a different input re-plans")
        (is (not= (mapv :price (:legs (:plan before))) (mapv :price (:legs (:plan after))))
            "with tilt on the legs follow the refreshed inventory")
        (is (= (get-in before [:account :base]) (get-in elsewhere [:account :base]))
            "an update of another market or account changes nothing here")))))
