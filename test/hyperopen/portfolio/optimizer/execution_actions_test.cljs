(ns hyperopen.portfolio.optimizer.execution-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(deftest execution-modal-actions-save-plan-from-last-successful-run-test
  (let [state {:portfolio {:optimizer
                           {:draft {:id "draft-1"
                                    :execution-assumptions {:default-order-type :market}}
                            :last-successful-run
                            {:result {:status :solved
                                      :rebalance-preview
                                      {:rows [{:instrument-id "perp:BTC"
                                               :instrument-type :perp
                                               :status :ready
                                               :side :buy
                                               :quantity 0.25
                                               :delta-notional-usd 1000}]}}}}}}]
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
                               :gross-ready-notional-usd 1000}
                     :rows [{:row-id "perp:BTC"
                             :instrument-id "perp:BTC"
                             :instrument-type :perp
                             :status :ready
                             :side :buy
                             :quantity 0.25
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
          {:portfolio {:optimizer {:last-successful-run {:result {:status :infeasible}}}}}))))
