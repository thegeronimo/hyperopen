(ns hyperopen.portfolio.optimizer.application.execution-carryover-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.execution-carryover :as carryover]))

(defn- resting-ledger-row
  [oid asset-idx]
  {:row-id "perp:ZETA"
   :instrument-id "perp:ZETA"
   :instrument-type :perp
   :coin "ZETA"
   :side :buy
   :quantity 681.7
   :status :resting
   :request {:action {:type "order" :orders [{:a asset-idx :b true :s "681.7"}]}}
   :response {:status "ok"
              :response {:data {:statuses [{:resting {:oid oid}}]}}}})

(deftest carryover-entry-captures-oid-and-asset-index-test
  (let [entry (carryover/carryover-entry "exec_1" (resting-ledger-row 777 42))]
    (is (= 777 (:oid entry)))
    (is (= 42 (:asset-id entry)))
    (is (= "ZETA" (:coin entry)))
    (is (= "exec_1" (:attempt-id entry))))
  (is (nil? (carryover/carryover-entry "exec_1" {:status :submitted}))
      "a filled row leaves nothing on the book — no carryover entry"))

(deftest record-resting-carryover-adds-resting-and-prunes-cancelled-test
  (let [existing [{:oid 111 :asset-id 7} {:oid 222 :asset-id 8}]
        ledger {:attempt-id "exec_2"
                :cancellations {:status :ok :oids [111 222]}
                :rows [(resting-ledger-row 333 42)]}
        next-carryover (carryover/record-resting-carryover existing ledger)]
    (is (= [333] (mapv :oid next-carryover))
        "cancelled oids pruned, the new run's resting order recorded"))
  (let [ledger {:attempt-id "exec_3"
                :cancellations {:status :failed :oids [111]}
                :rows []}]
    (is (= [111] (mapv :oid (carryover/record-resting-carryover
                             [{:oid 111 :asset-id 7}] ledger)))
        "a FAILED cancellation must not prune — the order may still be live")))

(deftest live-resting-carryover-filters-by-hydrated-book-only-test
  (let [entries [{:oid 111 :asset-id 7} {:oid 222 :asset-id 8}]]
    (is (= [111 222]
           (mapv :oid (carryover/live-resting-carryover
                       {:orders {:open-orders-hydrated? false}} entries)))
        "book not hydrated -> keep everything (cancel of a gone order is tolerated; skipping a live one is the over-allocation bug)")
    (is (= [222]
           (mapv :oid (carryover/live-resting-carryover
                       {:orders {:open-orders-hydrated? true
                                 :open-orders [{:oid 222}]}}
                       entries)))
        "hydrated book -> only oids still on the book survive")))

(deftest carryover-cancels-splits-wire-ready-from-unresolved-test
  (let [{:keys [cancels unresolved]}
        (carryover/carryover-cancels [{:oid 111 :asset-id 7}
                                      {:oid "222" :asset-id "8"}
                                      {:oid 333}])]
    (is (= [{:a 7 :o 111} {:a 8 :o 222}] cancels)
        "string shapes are parsed onto the wire form")
    (is (= [333] (mapv :oid unresolved))
        "an entry without an asset index cannot be cancelled and must halt the run")))

(deftest open-order-rows-merges-all-sources-and-shapes-test
  (let [state {:orders {:open-orders {:dex "" :user "0xabc"
                                      :orders [{:oid 1 :coin "BTC"}]}
                        :open-orders-snapshot [{:oid 1 :coin "BTC"}
                                               {:oid 2 :coin "ETH"}]
                        :open-orders-snapshot-by-dex
                        {"xyz" [{:oid 3 :coin "ORCL"}]}}
               :webdata2 {:openOrders [{:oid 4 :coin "SOL"}]}}
        rows (carryover/open-order-rows state)]
    (is (= #{"1" "2" "3" "4"} (into #{} (map #(str (:oid %))) rows))
        "all four sources surface, oid 1 deduped across ws + snapshot")
    (is (= "xyz:ORCL" (:coin (first (filter #(= 3 (:oid %)) rows))))
        "per-dex bare coin namespaced with its dex")))

(deftest live-resting-carryover-handles-ws-payload-map-shape-test
  (let [carryover-entries [{:oid 777 :asset-id 42 :coin "xyz:ORCL"}]
        state {:orders {:open-orders-hydrated? true
                        :open-orders {:dex "" :user "0xabc"
                                      :orders [{:oid 777 :coin "xyz:ORCL"}]}}}]
    (is (= [777] (mapv :oid (carryover/live-resting-carryover state carryover-entries)))
        "the resting order inside the payload map is recognized as still live")))
