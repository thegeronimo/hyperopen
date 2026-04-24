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

(deftest run-portfolio-optimizer-from-draft-requires-eligible-history-test
  (is (= []
         (actions/run-portfolio-optimizer-from-draft
          {:portfolio {:optimizer {:draft {:id "draft-missing-history"
                                           :universe [{:instrument-id "perp:BTC"
                                                       :market-type :perp
                                                       :coin "BTC"}]
                                           :objective {:kind :minimum-variance}
                                           :return-model {:kind :historical-mean}
                                           :risk-model {:kind :ledoit-wolf}
                                           :constraints {:long-only? true}}
                                  :history-data {:candle-history-by-coin {}
                                                 :funding-history-by-coin {}}
                                  :runtime {:as-of-ms 2500}}}}))))

(deftest load-portfolio-optimizer-history-from-draft-requires-universe-test
  (is (= [[:effects/load-portfolio-optimizer-history]]
         (actions/load-portfolio-optimizer-history-from-draft
          {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                       :market-type :perp
                                                       :coin "BTC"}]}}}})))
  (is (= []
         (actions/load-portfolio-optimizer-history-from-draft
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

(deftest set-draft-objective-parameter-updates-supported-targets-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :objective :target-return]
                                0.18]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-objective-parameter
          {}
          :target-return
          "0.18")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :objective :target-volatility]
                                0.22]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-objective-parameter
          {}
          "targetVolatility"
          "0.22")))
  (is (= []
         (actions/set-portfolio-optimizer-objective-parameter
          {}
          :unknown
          "0.1"))))

(deftest set-draft-execution-assumption-normalizes-supported-values-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :fallback-slippage-bps]
                                35]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :fallback-slippage-bps
          "35")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :default-order-type]
                                :market]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :default-order-type
          "market")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :fee-mode]
                                :taker]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :fee-mode
          :taker)))
  (is (= []
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :fallback-slippage-bps
          "not-a-number"))))

(deftest set-draft-instrument-filter-updates-allowlist-and-blocklist-test
  (let [state {:portfolio {:optimizer {:draft {:constraints {:allowlist ["perp:BTC"]
                                                             :blocklist ["spot:PURR"]}}}}}]
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :allowlist]
                                  ["perp:BTC" "perp:ETH"]]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-instrument-filter
            state
            :allowlist
            "perp:ETH"
            true)))
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :blocklist]
                                  []]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-instrument-filter
            state
            :blocklist
            "spot:PURR"
            false)))
    (is (= []
           (actions/set-portfolio-optimizer-instrument-filter
            state
            :unknown
            "perp:BTC"
            true)))))

(deftest set-draft-asset-override-updates-row-level-constraints-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:ETH"
                                                            :market-type :perp}
                                                           {:instrument-id "spot:PURR"
                                                            :market-type :spot}]
                                           :constraints {:held-locks ["perp:BTC"]}}}}}]
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :asset-overrides "perp:ETH" :max-weight]
                                  0.28]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-asset-override
            state
            :max-weight
            "perp:ETH"
            "0.28")))
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :held-locks]
                                  ["perp:BTC" "perp:ETH"]]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-asset-override
            state
            :held-lock?
            "perp:ETH"
            true)))
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :perp-leverage "perp:ETH" :max-weight]
                                  0.5]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-asset-override
            state
            :perp-max-weight
            "perp:ETH"
            "0.5")))
    (is (= []
           (actions/set-portfolio-optimizer-asset-override
            state
            :max-weight
            "perp:ETH"
            "not-a-number")))
    (is (= []
           (actions/set-portfolio-optimizer-asset-override
            state
            :perp-max-weight
            "spot:PURR"
            "0.5")))))

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
