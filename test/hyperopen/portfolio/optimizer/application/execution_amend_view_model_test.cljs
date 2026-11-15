(ns hyperopen.portfolio.optimizer.application.execution-amend-view-model-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.view-model.execution :as vm]))

(def ^:private resting-ledger-row
  {:row-id "perp:ZETA"
   :instrument-id "perp:ZETA"
   :instrument-type :perp
   :coin "ZETA"
   :side :buy
   :quantity 681.7
   :price 0.046
   :order-type :passive
   :status :resting
   :delta-notional-usd 31.36
   :request {:action {:type "order" :orders [{:a 42 :b true :s "681.7"}]}}
   :response {:status "ok"
              :response {:data {:statuses [{:resting {:oid 777}}]}}}})

(defn- state-with
  [{:keys [open-orders plan-extra modal-extra]
    :or {open-orders [{:oid 777 :coin "ZETA" :sz "300.5" :limitPx "0.045"}]}}]
  {:asset-selector {:market-by-key
                    {"perp:ZETA" {:szDecimals 1 :markRaw "0.0465" :asset-id 42}}}
   :orders {:open-orders-hydrated? true
            :open-orders open-orders}
   :portfolio {:optimizer
               {:execution-modal (merge {:plan (merge {:scenario-id "s1"
                                                       :summary {:ready-count 0}
                                                       :rows []}
                                                      plan-extra)}
                                        modal-extra)
                :execution {:status :resting
                            :history [{:attempt-id "exec_1"
                                       :status :resting
                                       :rows [resting-ledger-row]}]}}}})

(defn- zeta-row
  [model]
  (first (filter #(= "perp:ZETA" (:row-id %)) (:rows model))))

(deftest working-row-carries-live-amend-affordances-test
  (let [amend (:amend (zeta-row (vm/execution-tab-model (state-with {}))))]
    (is (true? (:amendable? amend)))
    (is (= "777" (:oid amend)))
    (is (= 300.5 (:remaining-size amend)) "live REMAINING size, not the original qty")
    (is (= 0.045 (:limit-px amend)))
    (is (= 0.0465 (:live-mark amend)))
    (is (= :passive (:order-type amend)) "defaults to the row's current type")
    (is (= 0 (:limit-bps amend)) "defaults to at-the-mark")))

(deftest amend-selection-reflects-the-modal-editor-state-test
  (let [amend (:amend (zeta-row
                       (vm/execution-tab-model
                        (state-with {:modal-extra
                                     {:overrides {"perp:ZETA" :limit}
                                      :params {"perp:ZETA" {:limit-bps -5}}}}))))]
    (is (= :limit (:order-type amend)))
    (is (= -5 (:limit-bps amend)))))

(deftest gone-from-book-row-is-not-amendable-test
  (is (nil? (:amend (zeta-row (vm/execution-tab-model
                               (state-with {:open-orders []})))))))

(deftest read-only-surface-is-not-amendable-test
  (is (nil? (:amend (zeta-row
                     (vm/execution-tab-model
                      (state-with {:plan-extra {:execution-disabled? true}})))))))
