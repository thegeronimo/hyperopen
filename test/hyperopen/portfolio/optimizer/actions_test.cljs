(ns hyperopen.portfolio.optimizer.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(deftest run-portfolio-optimizer-emits-registered-worker-effect-test
  (let [request {:scenario-id "scenario-1"
                 :objective {:kind :minimum-variance}}
        signature {:scenario-id "scenario-1"
                   :revision 4}]
    (is (= [[:effects/run-portfolio-optimizer request signature]]
           (actions/run-portfolio-optimizer {} request signature)))))

(deftest run-portfolio-optimizer-from-draft-builds-request-and-signature-test
  (let [state {:portfolio {:optimizer {:draft {:id "draft-1"
                                               :universe [{:instrument-id "perp:BTC"
                                                           :market-type :perp
                                                           :coin "BTC"}]
                                               :objective {:kind :minimum-variance}
                                               :return-model {:kind :historical-mean}
                                               :risk-model {:kind :sample-covariance}
                                               :constraints {:long-only? true
                                                             :max-asset-weight 1.0}
                                               :execution-assumptions {:fallback-slippage-bps 25}}
                                      :history-data {:candle-history-by-coin
                                                     {"BTC" [{:time 1000 :close "100"}
                                                             {:time 2000 :close "110"}]}
                                                     :funding-history-by-coin {}}
                                      :market-cap-by-coin {}
                                      :runtime {:as-of-ms 2500
                                                :stale-after-ms 60000}}}
               :webdata2 {:clearinghouseState
                           {:marginSummary {:accountValue "1000"}
                            :assetPositions
                            [{:position {:coin "BTC"
                                         :szi "0.5"
                                         :positionValue "500"}}]}}}
        [[effect-id request signature]]
        (actions/run-portfolio-optimizer-from-draft state)]
    (is (= :effects/run-portfolio-optimizer effect-id))
    (is (= "draft-1" (:scenario-id request)))
    (is (= ["perp:BTC"] (mapv :instrument-id (:universe request))))
    (is (= :historical-mean (get-in request [:return-model :kind])))
    (is (= :sample-covariance (get-in request [:risk-model :kind])))
    (is (= 2500 (:as-of-ms request)))
    (is (= false (get-in request [:history :freshness :stale?])))
    (is (= "draft-1" (:scenario-id signature)))
    (is (= request (:request signature)))))

(deftest run-portfolio-optimizer-from-draft-requires-universe-test
  (is (= []
         (actions/run-portfolio-optimizer-from-draft
          {:portfolio {:optimizer {:draft {:universe []}}}}))))

(deftest set-draft-model-layer-actions-update-draft-and-mark-dirty-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :objective]
                                {:kind :max-sharpe}]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-objective-kind
          {}
          "maxSharpe")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :return-model]
                                {:kind :black-litterman
                                 :views []}]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-return-model-kind
          {}
          :black-litterman)))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :risk-model]
                                {:kind :sample-covariance}]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-risk-model-kind
          {}
          "sampleCovariance"))))

(deftest set-draft-model-layer-actions-ignore-invalid-kinds-test
  (is (= []
         (actions/set-portfolio-optimizer-objective-kind {} "not-real")))
  (is (= []
         (actions/set-portfolio-optimizer-return-model-kind {} "not-real")))
  (is (= []
         (actions/set-portfolio-optimizer-risk-model-kind {} "not-real"))))

(deftest set-draft-constraint-normalizes-supported-values-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :max-asset-weight]
                                0.42]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :max-asset-weight
          "0.42")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :long-only?]
                                true]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :long-only?
          true)))
  (is (= []
         (actions/set-portfolio-optimizer-constraint
          {}
          :gross-max
          "not-a-number"))))

(deftest set-draft-universe-from-current-holdings-test
  (let [state {:webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "1000"}
                           :assetPositions
                           [{:position {:coin "BTC"
                                        :szi "0.5"
                                        :positionValue "500"
                                        :leverage {:type "cross"
                                                   :value "5"}}}]}}
               :spot {:balances [{:coin "PURR"
                                  :total "10"}]}
               :asset-selector {:market-by-key
                                {"spot:PURR" {:key "spot:PURR"
                                              :market-type :spot
                                              :coin "PURR/USDC"
                                              :base "PURR"
                                              :quote "USDC"
                                              :mark "2"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"
                 :shortable? true}
                {:instrument-id "spot:PURR"
                 :market-type :spot
                 :coin "PURR"
                 :shortable? false}]]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/set-portfolio-optimizer-universe-from-current state)))))

(deftest set-draft-universe-from-current-holdings-ignores-empty-snapshot-test
  (is (= []
         (actions/set-portfolio-optimizer-universe-from-current {}))))
