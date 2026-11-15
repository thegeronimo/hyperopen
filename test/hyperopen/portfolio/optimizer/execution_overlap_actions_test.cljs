(ns hyperopen.portfolio.optimizer.execution-overlap-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(def ^:private ready-plan
  {:scenario-id "draft-1"
   :status :ready
   :execution-disabled? false
   :summary {:ready-count 1}
   :rows [{:row-id "perp:ZETA"
           :status :ready
           :side :buy
           :instrument-type :perp
           :coin "ZETA"
           :delta-notional-usd 25
           :intent {:kind :perp-order :side :buy :quantity 5 :order-type :market}}]})

(defn- state-with-snapshot
  [snapshot-rows & {:keys [selections]}]
  {:wallet {:agent {:status :ready}}
   :orders {:open-orders-snapshot snapshot-rows}
   :portfolio {:optimizer
               {:execution-modal (cond-> {:open? true :plan ready-plan}
                                   selections (assoc :overlap-cancels selections))}}})

(deftest confirm-auto-cancels-cloid-recognized-orders-across-reload-test
  (let [state (state-with-snapshot
               [{:oid 900 :coin "ZETA" :side "B" :sz "5"
                 :cloid "0x0770c0deaaaaaaaaaaaaaaaaaaaaaaaa"}])
        [_ dispatched] (nth (actions/confirm-portfolio-optimizer-execution state) 2)]
    (is (= [900] (mapv :oid (:cancel-orders dispatched)))
        "our cloid-tagged resting order is auto-cancelled with no in-memory state")))

(deftest confirm-does-not-auto-cancel-untagged-overlap-until-user-selects-test
  (let [snapshot [{:oid 901 :coin "ZETA" :side "A" :sz "3" :cloid "0xmanual00000000000000000000000000"}]]
    (let [[_ d] (nth (actions/confirm-portfolio-optimizer-execution
                      (state-with-snapshot snapshot)) 2)]
      (is (not (contains? d :cancel-orders))
          "untagged overlap not auto-cancelled"))
    (let [[_ d] (nth (actions/confirm-portfolio-optimizer-execution
                      (state-with-snapshot snapshot :selections {"901" true})) 2)]
      (is (= [901] (mapv :oid (:cancel-orders d)))
          "once the user selects it, the untagged overlap joins :cancel-orders"))))

(deftest set-overlap-cancel-records-and-clears-choice-test
  (let [checked (actions/set-portfolio-optimizer-execution-overlap-cancel {} 555 true)
        [[_ path value]] checked]
    (is (= [:portfolio :optimizer :execution-modal :overlap-cancels] path))
    (is (= {"555" true} value) "oid stored as a string key")
    (let [cleared (actions/set-portfolio-optimizer-execution-overlap-cancel
                   {:portfolio {:optimizer {:execution-modal {:overlap-cancels {"555" true}}}}}
                   555 false)]
      (is (= {} (get-in (first cleared) [2])) "unchecking removes the oid"))))
