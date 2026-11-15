(ns hyperopen.portfolio.optimizer.execution-carryover-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(def ^:private ready-plan
  {:scenario-id "draft-1"
   :status :ready
   :execution-disabled? false
   :summary {:ready-count 1}
   :rows [{:row-id "perp:BTC"
           :status :ready
           :side :buy
           :instrument-type :perp
           :delta-notional-usd 25
           :intent {:kind :perp-order :side :buy :quantity 0.25
                    :order-type :market}}]})

(deftest confirm-execution-attaches-live-resting-carryover-as-cancel-orders-test
  (let [state {:wallet {:agent {:status :ready}}
               :orders {:open-orders-hydrated? true
                        :open-orders [{:oid 111}]}
               :portfolio {:optimizer
                           {:execution-resting-carryover
                            [{:oid 111 :asset-id 7 :coin "ZETA"}
                             {:oid 222 :asset-id 8 :coin "ZEN"}]
                            :execution-modal {:open? true :plan ready-plan}}}}
        effects (actions/confirm-portfolio-optimizer-execution state)
        [effect-key dispatched] (nth effects 2)]
    (is (= :effects/execute-portfolio-optimizer-plan effect-key))
    (is (= [111] (mapv :oid (:cancel-orders dispatched)))
        "only the still-live oid rides along for pre-run cancellation")))

(deftest confirm-execution-without-carryover-omits-cancel-orders-test
  (let [state {:wallet {:agent {:status :ready}}
               :portfolio {:optimizer {:execution-modal {:open? true
                                                         :plan ready-plan}}}}
        [_ dispatched] (nth (actions/confirm-portfolio-optimizer-execution state) 2)]
    (is (not (contains? dispatched :cancel-orders)))))
