(ns hyperopen.portfolio.optimizer.execution-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(deftest execution-modal-actions-save-plan-from-last-successful-run-test
  (let [state {:portfolio {:optimizer
                           {:draft {:id "draft-1"
                                    :execution-assumptions {:default-order-type :market}}
                            :last-successful-run
                            (fixtures/sample-last-successful-run
                             {:result {:rebalance-preview
                                       {:summary {:estimated-fees-usd nil
                                                  :estimated-slippage-usd nil}
                                        :rows [{:instrument-id "perp:BTC"
                                                :instrument-type :perp
                                                :status :ready
                                                :side :buy
                                                :quantity 0.25
                                                :delta-notional-usd 1000}]}}})}}}]
    (is (= [[:effects/save
             [:portfolio :optimizer :execution-modal]
             {:open? true
              :plan {:scenario-id "draft-1"
                     :status :ready
                     :execution-disabled? false
                     :disabled-reason nil
                     :disabled-message nil
                     :summary {:ready-count 1
                               :blocked-count 0
                               :skipped-count 0
                               :gross-ready-notional-usd 1000
                               :estimated-fees-usd nil
                               :estimated-slippage-usd nil
                               :margin nil}
                     :rows [{:row-id "perp:BTC"
                             :instrument-id "perp:BTC"
                             :instrument-type :perp
                             :status :ready
                             :side :buy
                             :quantity 0.25
                             :order-type :market
                             :delta-notional-usd 1000
                             :cost nil
                             :intent {:kind :perp-order
                                      :instrument-id "perp:BTC"
                                      :side :buy
                                      :quantity 0.25
                                      :order-type :market
                                      :reduce-only? false}}]}}]]
           (actions/open-portfolio-optimizer-execution-modal state))))
  (is (= [[:effects/save
           [:portfolio :optimizer :execution-modal]
           {:open? false
            :plan nil
            :submitting? false
            :error nil}]]
         (actions/close-portfolio-optimizer-execution-modal {}))))

(deftest open-execution-modal-requires-solved-run-test
  (is (= []
         (actions/open-portfolio-optimizer-execution-modal
          {:portfolio {:optimizer {:last-successful-run
                                    (fixtures/sample-last-successful-run
                                     {:result {:status :infeasible}})}}}))))

(deftest confirm-execution-modal-dispatches-execution-effect-test
  (let [plan {:scenario-id "draft-1"
              :status :ready
              :execution-disabled? false
              :summary {:ready-count 1}
              :rows [{:row-id "perp:BTC"
                      :status :ready
                      :intent {:kind :perp-order}}]}
        state {:portfolio {:optimizer {:execution-modal {:open? true
                                                         :plan plan}}}}]
    (is (= [[:effects/save [:portfolio :optimizer :execution-modal :submitting?] true]
            [:effects/save [:portfolio :optimizer :execution-modal :error] nil]
            [:effects/execute-portfolio-optimizer-plan plan]]
           (actions/confirm-portfolio-optimizer-execution state)))))

(deftest confirm-execution-modal-blocks-read-only-plan-test
  (let [state {:portfolio {:optimizer {:execution-modal
                                       {:open? true
                                        :plan {:scenario-id "draft-1"
                                               :status :ready
                                               :execution-disabled? true
                                               :disabled-message "Spectate Mode is read-only."}}}}}]
    (is (= [[:effects/save
             [:portfolio :optimizer :execution-modal :error]
             "Spectate Mode is read-only."]]
           (actions/confirm-portfolio-optimizer-execution state)))))
