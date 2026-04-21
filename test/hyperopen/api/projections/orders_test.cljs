(ns hyperopen.api.projections.orders-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.projections.orders :as orders]))

(deftest order-projections-target-expected-state-paths-test
  (let [state {:orders {}}
        open-orders (orders/apply-open-orders-success state nil [{:oid 1}])
        open-orders-by-dex (orders/apply-open-orders-success state "vault" [{:oid 2}])
        open-orders-error (orders/apply-open-orders-error state (js/Error. "open-orders"))
        fills (orders/apply-user-fills-success state [{:tid 1}])
        fills-error (orders/apply-user-fills-error state (js/Error. "fills"))]
    (is (= true (get-in open-orders [:orders :open-orders-hydrated?])))
    (is (= true (get-in open-orders-by-dex [:orders :open-orders-hydrated?])))
    (is (= [{:oid 1}] (get-in open-orders [:orders :open-orders-snapshot])))
    (is (= [{:oid 2}] (get-in open-orders-by-dex [:orders :open-orders-snapshot-by-dex "vault"])))
    (is (= "Error: open-orders" (get-in open-orders-error [:orders :open-error])))
    (is (= :unexpected (get-in open-orders-error [:orders :open-error-category])))
    (is (= [{:tid 1}] (get-in fills [:orders :fills])))
    (is (= "Error: fills" (get-in fills-error [:orders :fills-error])))
    (is (= :unexpected (get-in fills-error [:orders :fills-error-category])))))

(deftest apply-open-orders-success-filters-recently-canceled-oids-for-base-and-dex-snapshots-test
  (let [state {:orders {:recently-canceled-oids #{22}}}
        base (orders/apply-open-orders-success state
                                               nil
                                               [{:coin "BTC" :oid 22}
                                                {:coin "ETH" :oid 23}])
        per-dex (orders/apply-open-orders-success state
                                                  "dex-a"
                                                  [{:order {:coin "BTC" :oid 22}}
                                                   {:order {:coin "SOL" :oid 24}}])]
    (is (= [{:coin "ETH" :oid 23}]
           (get-in base [:orders :open-orders-snapshot])))
    (is (= [{:order {:coin "SOL" :oid 24}}]
           (get-in per-dex [:orders :open-orders-snapshot-by-dex "dex-a"])))
    (is (= #{22}
           (get-in base [:orders :recently-canceled-oids])))
    (is (= true
           (get-in base [:orders :open-orders-hydrated?])))
    (is (= true
           (get-in per-dex [:orders :open-orders-hydrated?])))))
