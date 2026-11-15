(ns hyperopen.portfolio.optimizer.application.view-model.execution-reconcile-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.view-model.execution :as vm]
            [hyperopen.portfolio.optimizer.application.view-model.execution-reconcile :as reconcile]))

(defn- resting-row
  [oid]
  {:row-id "perp:BTC"
   :instrument-id "perp:BTC"
   :coin "BTC"
   :status :resting
   :side :buy
   :price 50000
   :quantity 1
   :delta-notional-usd 50000
   :response {:status "ok"
              :response {:data {:statuses [{:resting {:oid oid}}]}}}})

(defn- orders-state
  [{:keys [open-orders fills hydrated?]}]
  {:orders {:open-orders (vec open-orders)
            :open-orders-hydrated? hydrated?
            :fills (vec fills)}})

(deftest resting-oid-still-on-book-stays-resting-test
  (let [state (orders-state {:open-orders [{:oid 111 :coin "BTC"}]
                             :fills []
                             :hydrated? true})
        [row] (reconcile/reconcile-rows state [(resting-row 111)])]
    (is (= :resting (:status row)))
    (is (nil? (:reconciled row)))))

(deftest resting-oid-gone-and-in-fills-promotes-to-filled-with-realized-test
  (let [state (orders-state {:open-orders []
                             :fills [{:oid 111 :coin "BTC" :px "50050" :sz "1.0"}]
                             :hydrated? true})
        [row] (reconcile/reconcile-rows state [(resting-row 111)])]
    (is (= :submitted (:status row)))
    (is (= :filled (:reconciled row)))
    (is (= 50050 (get-in row [:realized :avg-px])))
    (is (some? (get-in row [:realized :slippage-bps])))))

(deftest resting-oid-still-on-book-with-fill-is-partial-test
  (let [state (orders-state {:open-orders [{:oid 111 :coin "BTC"}]
                             :fills [{:oid 111 :coin "BTC" :px "50050" :sz "0.4"}]
                             :hydrated? true})
        [row] (reconcile/reconcile-rows state [(resting-row 111)])]
    (is (= :resting (:status row)))
    (is (= :partial (:reconciled row)))
    (is (= 50050 (get-in row [:realized :avg-px])))))

(deftest no-fill-evidence-leaves-row-unchanged-test
  (let [state (orders-state {:open-orders []
                             :fills []
                             :hydrated? true})
        [row] (reconcile/reconcile-rows state [(resting-row 111)])]
    (is (= :resting (:status row)))
    (is (nil? (:reconciled row)))))

(deftest not-hydrated-without-fill-stays-resting-test
  (let [state (orders-state {:open-orders []
                             :fills []
                             :hydrated? false})
        [row] (reconcile/reconcile-rows state [(resting-row 111)])]
    (is (= :resting (:status row)))))

(deftest not-hydrated-with-fill-promotes-to-filled-test
  (let [state (orders-state {:open-orders []
                             :fills [{:oid 111 :coin "BTC" :px "50050" :sz "1.0"}]
                             :hydrated? false})
        [row] (reconcile/reconcile-rows state [(resting-row 111)])]
    (is (= :submitted (:status row)))
    (is (= :filled (:reconciled row)))))

(deftest non-resting-row-is-untouched-test
  (let [state (orders-state {:open-orders []
                             :fills [{:oid 111 :coin "BTC" :px "50050" :sz "1.0"}]
                             :hydrated? true})
        submitted (assoc (resting-row 111) :status :submitted)
        [row] (reconcile/reconcile-rows state [submitted])]
    (is (= :submitted (:status row)))
    (is (nil? (:reconciled row)))))

(deftest multi-fill-volume-weighted-avg-test
  (let [state (orders-state {:open-orders []
                             :fills [{:oid 111 :coin "BTC" :px "50000" :sz "1.0"}
                                     {:oid 111 :coin "BTC" :px "50100" :sz "1.0"}]
                             :hydrated? true})
        [row] (reconcile/reconcile-rows state [(resting-row 111)])]
    (is (= :submitted (:status row)))
    (is (= 50050 (get-in row [:realized :avg-px])))))

(defn- execution-state
  [orders]
  (let [row (resting-row 111)]
    (merge orders
           {:portfolio
            {:optimizer
             {:execution {:status :resting
                          :history [{:rows [row] :status :resting}]}
              :execution-modal {:open? true
                                :phase :staged
                                :plan {:scenario-id "draft-1"
                                       :status :resting
                                       :summary {:ready-count 0}
                                       :rows [row]}}}}})))

(deftest execution-tab-model-reflects-fill-and-advances-phase-to-done-test
  (let [resting-model (vm/execution-tab-model
                       (execution-state (orders-state {:open-orders [{:oid 111 :coin "BTC"}]
                                                       :fills []
                                                       :hydrated? true})))
        filled-model (vm/execution-tab-model
                      (execution-state (orders-state {:open-orders []
                                                      :fills [{:oid 111 :coin "BTC"
                                                               :px "50050" :sz "1.0"}]
                                                      :hydrated? true})))]
    (is (= :resting (:phase resting-model)))
    (is (= :resting (:status (first (:rows resting-model)))))
    (is (= :done (:phase filled-model)))
    (is (= :submitted (:status (first (:rows filled-model)))))
    (is (= :executed (:status (:latest-attempt filled-model))))))
