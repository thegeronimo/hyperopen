(ns hyperopen.vol-range.application.plan-state-memo-test
  "Memoization must never re-plan on provenance alone, and a cached offer must
  never be applied once the live book makes it unready (review findings)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.actions :as actions]
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
