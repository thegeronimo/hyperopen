(ns hyperopen.portfolio.optimizer.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(deftest run-portfolio-optimizer-emits-registered-worker-effect-test
  (let [request {:scenario-id "scenario-1"
                 :objective {:kind :minimum-variance}}
        signature {:scenario-id "scenario-1"
                   :revision 4}]
    (is (= [[:effects/run-portfolio-optimizer request signature]]
           (actions/run-portfolio-optimizer {} request signature)))))

(deftest run-portfolio-optimizer-from-ready-draft-builds-request-and-signature-test
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
        (actions/run-portfolio-optimizer-from-ready-draft state)]
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

(deftest run-portfolio-optimizer-from-draft-starts-pipeline-without-history-test
  (is (= [[:effects/run-portfolio-optimizer-pipeline]]
         (actions/run-portfolio-optimizer-from-draft
          {:portfolio {:optimizer {:draft {:id "draft-missing-history"
                                           :universe [{:instrument-id "perp:BTC"
                                                       :market-type :perp
                                                       :coin "BTC"}]
                                           :objective {:kind :minimum-variance}
                                           :return-model {:kind :historical-mean}
                                           :risk-model {:kind :diagonal-shrink}
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

(deftest save-portfolio-optimizer-scenario-from-current-requires-solved-run-test
  (is (= [[:effects/save-portfolio-optimizer-scenario]]
         (actions/save-portfolio-optimizer-scenario-from-current
          {:portfolio {:optimizer {:last-successful-run
                                    (fixtures/sample-last-successful-run)}}})))
  (is (= []
         (actions/save-portfolio-optimizer-scenario-from-current
          {:portfolio {:optimizer {:last-successful-run
                                    (fixtures/sample-last-successful-run
                                     {:result {:status :infeasible}})}}})))
  (is (= []
         (actions/save-portfolio-optimizer-scenario-from-current
          {:portfolio {:optimizer {}}}))))

(deftest load-portfolio-optimizer-route-emits-scenario-read-effects-test
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]]
         (actions/load-portfolio-optimizer-route
          {:vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize")))
  (is (= [[:effects/load-portfolio-optimizer-scenario "scn_01"]]
         (actions/load-portfolio-optimizer-route
          {:vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/scn_01")))
  (is (= []
         (actions/load-portfolio-optimizer-route
          {:vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/new")))
  (is (= []
         (actions/load-portfolio-optimizer-route
          {}
          "/trade"))))

(deftest load-portfolio-optimizer-route-fetches-vault-metadata-for-universe-search-test
  (is (= [[:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/load-portfolio-optimizer-route
          {}
          "/portfolio/optimize/new")))
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/load-portfolio-optimizer-route
          {:vaults {}}
          "/portfolio/optimize")))
  (is (= [[:effects/load-portfolio-optimizer-scenario "scn_01"]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/load-portfolio-optimizer-route
          {:vaults {}}
          "/portfolio/optimize/scn_01")))
  (is (= []
         (actions/load-portfolio-optimizer-route
          {:vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/new"))))

(deftest set-portfolio-optimizer-results-tab-updates-shareable-tab-state-test
  (is (= [[:effects/save [:portfolio-ui :optimizer :results-tab] :tracking]
          [:effects/replace-shareable-route-query]]
         (actions/set-portfolio-optimizer-results-tab {} :tracking)))
  (is (= [[:effects/save [:portfolio-ui :optimizer :results-tab] :recommendation]
          [:effects/replace-shareable-route-query]]
         (actions/set-portfolio-optimizer-results-tab {} :frontier)))
  (is (= [[:effects/save [:portfolio-ui :optimizer :results-tab] :recommendation]
          [:effects/replace-shareable-route-query]]
         (actions/set-portfolio-optimizer-results-tab {} :wat))))

(deftest apply-portfolio-optimizer-setup-preset-updates-only-model-layer-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}]
                                               :objective {:kind :target-return
                                                           :target-return 0.2}
                                               :return-model {:kind :ew-mean}
                                               :risk-model {:kind :sample-covariance}
                                               :constraints {:max-asset-weight 0.4
                                                             :gross-max 2.0}}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :objective] {:kind :minimum-variance}]
              [[:portfolio :optimizer :draft :return-model] {:kind :historical-mean}]
              [[:portfolio :optimizer :draft :metadata :dirty?] true]]]]
           (actions/apply-portfolio-optimizer-setup-preset state :conservative)))
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :objective] {:kind :max-sharpe}]
              [[:portfolio :optimizer :draft :return-model] {:kind :historical-mean}]
              [[:portfolio :optimizer :draft :metadata :dirty?] true]]]]
           (actions/apply-portfolio-optimizer-setup-preset state :risk-adjusted)))
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :objective] {:kind :max-sharpe}]
              [[:portfolio :optimizer :draft :return-model] {:kind :black-litterman
                                                             :views []}]
              [[:portfolio :optimizer :draft :metadata :dirty?] true]]]]
           (actions/apply-portfolio-optimizer-setup-preset state :use-my-views)))
    (is (= []
           (actions/apply-portfolio-optimizer-setup-preset state :unknown)))))

(deftest scenario-board-row-actions-emit-persistence-effects-test
  (is (= [[:effects/archive-portfolio-optimizer-scenario "scn_01"]]
         (actions/archive-portfolio-optimizer-scenario
          {}
          "scn_01")))
  (is (= [[:effects/duplicate-portfolio-optimizer-scenario "scn_01"]]
         (actions/duplicate-portfolio-optimizer-scenario
          {}
          "scn_01")))
  (is (= []
         (actions/archive-portfolio-optimizer-scenario
          {}
          " ")))
  (is (= []
         (actions/duplicate-portfolio-optimizer-scenario
          {}
          nil))))

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
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :return-model]
                                {:kind :ew-mean
                                 :alpha 0.015159678336035098}]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-return-model-kind
          {}
          :ew-mean)))
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
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :manual-capital-usdc]
                                100000]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :manual-capital-usdc
          "100000")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :manual-capital-usdc]
                                nil]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :manual-capital-usdc
          "")))
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
                                              :symbol "PURR/USDC"
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
                 :shortable? false
                 :symbol "PURR/USDC"
                 :base "PURR"
                 :quote "USDC"}]]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/set-portfolio-optimizer-universe-from-current state)))))

(deftest set-draft-universe-from-current-holdings-ignores-empty-snapshot-test
  (is (= []
         (actions/set-portfolio-optimizer-universe-from-current {}))))

(deftest add-draft-universe-instrument-from-asset-selector-market-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"
                                                            :shortable? true}]}}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"
                                             :symbol "ETH-USDC"
                                             :base "ETH"
                                             :quote "USDC"
                                             :dex "hl"
                                             :maxLeverage 50}
                                 "spot:PURR/USDC" {:key "spot:PURR/USDC"
                                                   :market-type :spot
                                                   :coin "PURR/USDC"
                                                   :symbol "PURR/USDC"
                                                   :base "PURR"
                                                   :quote "USDC"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"
                 :shortable? true}
                {:instrument-id "perp:ETH"
                 :market-type :perp
                 :coin "ETH"
                 :shortable? true
                 :dex "hl"
                 :symbol "ETH-USDC"
                 :base "ETH"
                 :quote "USDC"}]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"
                 :shortable? true}
                {:instrument-id "spot:PURR/USDC"
                 :market-type :spot
                 :coin "PURR/USDC"
                 :shortable? false
                 :symbol "PURR/USDC"
                 :base "PURR"
                 :quote "USDC"}]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "spot:PURR/USDC")))))

(deftest add-draft-universe-instrument-from-vault-row-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"
                                                            :shortable? true}]}}}
               :vaults {:merged-index-rows [{:name "Alpha Yield"
                                             :vault-address "0x1111111111111111111111111111111111111111"
                                             :relationship {:type :normal}
                                             :tvl 500}]}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"
                 :shortable? true}
                {:instrument-id (str "vault:" vault-address)
                 :market-type :vault
                 :coin (str "vault:" vault-address)
                 :vault-address vault-address
                 :shortable? false
                 :name "Alpha Yield"
                 :symbol "Alpha Yield"
                 :tvl 500}]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            (str "vault:" vault-address))))))

(deftest add-draft-universe-instrument-rejects-missing-or-duplicate-market-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"}]}}}
               :asset-selector {:market-by-key {"perp:BTC" {:key "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"}}}}]
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:BTC")))
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            " ")))))

(deftest remove-draft-universe-instrument-cleans-dependent-constraints-test
  (let [state {:portfolio
               {:optimizer
                {:draft
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "perp:ETH"
                              :market-type :perp
                              :coin "ETH"}]
                  :constraints {:allowlist ["perp:BTC" "perp:ETH"]
                                :blocklist ["perp:ETH"]
                                :held-locks ["perp:ETH"]
                                :asset-overrides {"perp:ETH" {:max-weight 0.2}
                                                  "perp:BTC" {:max-weight 0.5}}
                                :perp-leverage {"perp:ETH" {:max-weight 0.4}}}}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"}]]
              [[:portfolio :optimizer :draft :constraints :allowlist]
               ["perp:BTC"]]
              [[:portfolio :optimizer :draft :constraints :blocklist]
               []]
              [[:portfolio :optimizer :draft :constraints :held-locks]
               []]
              [[:portfolio :optimizer :draft :constraints :asset-overrides]
               {"perp:BTC" {:max-weight 0.5}}]
              [[:portfolio :optimizer :draft :constraints :perp-leverage]
               {}]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/remove-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= []
           (actions/remove-portfolio-optimizer-universe-instrument
            state
            "perp:SOL")))))
