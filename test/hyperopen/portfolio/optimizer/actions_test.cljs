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
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize")))
  (is (= [[:effects/load-portfolio-optimizer-scenario "scn_01"]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/scn_01")))
  (is (= []
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/new")))
  (is (= []
         (actions/load-portfolio-optimizer-route
          {}
          "/trade"))))

(deftest load-portfolio-optimizer-route-fetches-vault-metadata-for-universe-search-test
  (is (= [[:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}}
          "/portfolio/optimize/new")))
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {}}
          "/portfolio/optimize")))
  (is (= [[:effects/load-portfolio-optimizer-scenario "scn_01"]
          [:effects/api-fetch-vault-index]
          [:effects/api-fetch-vault-summaries]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {}}
          "/portfolio/optimize/scn_01")))
  (is (= []
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/new"))))

(deftest load-portfolio-optimizer-route-refreshes-cache-only-selector-markets-test
  (is (= [[:effects/fetch-asset-selector-markets {:phase :full}]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:cache-hydrated? true
                            :phase :bootstrap
                            :markets [{:key "perp:BTC"
                                       :coin "BTC"
                                       :symbol "BTC-USDC"
                                       :market-type :perp}]}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
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
