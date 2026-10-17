(ns hyperopen.portfolio.optimizer.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions.common :as action-common]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]))

(def ^:private bl-editor-path
  [:portfolio-ui :optimizer :black-litterman-editor])

(def ^:private bl-views-path
  [:portfolio :optimizer :draft :return-model :views])

(def ^:private bl-errors-path
  [:portfolio-ui :optimizer :black-litterman-editor :errors])

(def ^:private dirty-path
  [:portfolio :optimizer :draft :metadata :dirty?])

(def ^:private scenario-save-modal-path
  [:portfolio :optimizer :scenario-save-modal])

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left)
                    (map? right))
             (deep-merge left right)
             right))
         maps))

(defn- effect-values-by-path
  [effects]
  (reduce (fn [acc effect]
            (case (first effect)
              :effects/save
              (assoc acc (second effect) (nth effect 2))

              :effects/save-many
              (reduce (fn [acc [path value]]
                        (assoc acc path value))
                      acc
                      (second effect))

              acc))
          {}
          (or effects [])))

(defn- black-litterman-draft-state
  [& overrides]
  (apply deep-merge
         {:portfolio
          {:optimizer
           {:draft {:id "draft-bl"
                    :universe [{:instrument-id "perp:BTC"
                                :market-type :perp
                                :coin "BTC"}
                               {:instrument-id "perp:ETH"
                                :market-type :perp
                                :coin "ETH"}]
                    :objective {:kind :minimum-variance}
                    :return-model {:kind :black-litterman
                                   :views []}
                    :risk-model {:kind :sample-covariance}
                    :constraints {:long-only? true}
                    :metadata {:dirty? false}}
            :history-data {:candle-history-by-coin {}
                           :funding-history-by-coin {}}
            :runtime {:as-of-ms 2500}}}
          :portfolio-ui
          {:optimizer
           {:black-litterman-editor
            {:selected-kind :absolute
             :drafts {:absolute {:instrument-id "perp:BTC"
                                 :return-text ""
                                 :return-text-touched? false
                                 :confidence :medium
                                 :horizon :3m
                                 :notes ""}}
             :errors {}}}}}
         overrides))

(defn- ready-optimizer-state
  [return-model]
  {:portfolio
   {:optimizer
    {:draft {:id "draft-current"
             :universe [{:instrument-id "perp:BTC"
                         :market-type :perp
                         :coin "BTC"}]
             :objective {:kind :max-sharpe}
             :return-model return-model
             :risk-model {:kind :sample-covariance}
             :constraints {:long-only? true
                           :max-asset-weight 1.0}
             :metadata {:dirty? false}}
     :history-data {:candle-history-by-coin
                    {"BTC" [{:time 1000 :close "100"}
                            {:time 2000 :close "110"}]}
                    :funding-history-by-coin {}}
     :market-cap-by-coin {}
     :runtime {:as-of-ms 2500
               :stale-after-ms 60000}}}
   :webdata2 {:clearinghouseState
              {:marginSummary {:accountValue "1000"}
               :assetPositions []}}})

(defn- request-signature-for-state
  [state]
  (let [{:keys [request runnable?]} (setup-readiness/build-readiness state)]
    (is runnable?)
    (action-common/build-request-signature request)))

(defn- solved-run-for-state
  [state]
  (fixtures/sample-last-successful-run
   {:request-signature (request-signature-for-state state)
    :result {:status :solved}}))

(deftest run-portfolio-optimizer-emits-registered-worker-effect-test
  (let [request {:scenario-id "scenario-1"
                 :objective {:kind :minimum-variance}}
        signature {:scenario-id "scenario-1"
                   :revision 4}]
    (is (= [[:effects/run-portfolio-optimizer request signature]]
           (actions/run-portfolio-optimizer {} request signature)))))

(deftest universe-instrument-builders-preserve-optimizer-history-metadata-test
  (let [history-metadata {:optimizer-history/instrument-id "hl:perp:BTC"
                          :optimizer-history/display-symbol "BTC"
                          :optimizer-history/instrument-kind :hl-perp
                          :optimizer-history/history-status :available
                          :optimizer-history/proxy {:available true}}
        market (merge {:key "perp:BTC"
                       :market-type :perp
                       :coin "BTC"
                       :symbol "BTC-USDC"
                       :name "Bitcoin"}
                      history-metadata)
        exposure (merge {:instrument-id "perp:BTC"
                         :market-type :perp
                         :coin "BTC"
                         :symbol "BTC-USDC"}
                        history-metadata)]
    (is (= (merge {:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :shortable? true
                   :position-side :long
                   :symbol "BTC-USDC"
                   :name "Bitcoin"}
                  history-metadata)
           (action-common/market->universe-instrument market)))
    (is (= (merge {:instrument-id "perp:BTC"
                   :market-type :perp
                   :coin "BTC"
                   :shortable? true
                   :position-side :long
                   :symbol "BTC-USDC"}
                  history-metadata)
           (action-common/exposure->universe-instrument exposure)))))

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

(deftest run-portfolio-optimizer-from-draft-materializes-pending-black-litterman-view-test
  (let [effects (actions/run-portfolio-optimizer-from-draft
                 (black-litterman-draft-state
                  {:portfolio-ui
                   {:optimizer
                    {:black-litterman-editor
                     {:drafts {:absolute {:return-text "20"
                                          :return-text-touched? true
                                          :confidence :high
                                          :horizon :1y
                                          :notes "User view"}}}}}}))
        values (effect-values-by-path effects)
        [view] (get values bl-views-path)]
    (is (= [:effects/save-many :effects/run-portfolio-optimizer-pipeline]
           (mapv first effects)))
    (is (= {:kind :absolute
            :instrument-id "perp:BTC"
            :return 0.2
            :confidence 0.75
            :weights {"perp:BTC" 1}}
           (select-keys view [:kind :instrument-id :return :confidence :weights])))
    (is (= {} (get values bl-errors-path)))
    (is (= true (get values dirty-path)))))

(deftest run-portfolio-optimizer-from-draft-runs-with-zero-black-litterman-views-test
  ;; Zero authored views is a valid Maximum Sharpe run: the posterior equals the
  ;; baseline expected returns, so nothing blocks and no error is staged.
  (is (= [[:effects/run-portfolio-optimizer-pipeline]]
         (actions/run-portfolio-optimizer-from-draft
          (black-litterman-draft-state)))))

(deftest auto-recompute-stale-portfolio-optimizer-scenario-requests-background-run-once-test
  (let [state (ready-optimizer-state {:kind :historical-mean})
        stale-state (-> state
                        (assoc-in contracts/last-successful-run-path
                                  (solved-run-for-state state))
                        (assoc-in dirty-path true))
        request (-> (setup-readiness/build-readiness stale-state)
                    :request)
        request-signature (action-common/build-request-signature request)
        input-signature (contracts/optimizer-input-signature request)
        expected-auto-state {:request-signature request-signature
                             :input-signature input-signature
                             :scenario-id "draft-current"}]
    (is (= [[:effects/save
             contracts/ui-stale-auto-recompute-path
             expected-auto-state]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/auto-recompute-stale-portfolio-optimizer-scenario
            stale-state)))
    (is (= []
           (actions/auto-recompute-stale-portfolio-optimizer-scenario
            (assoc-in stale-state
                      contracts/ui-stale-auto-recompute-path
                      expected-auto-state)))
        "The render hook may fire repeatedly, so a stale signature is requested at most once.")
    (is (= []
           (actions/auto-recompute-stale-portfolio-optimizer-scenario
            (-> stale-state
                (assoc-in contracts/ui-stale-auto-recompute-path
                          expected-auto-state)
                (assoc-in [:portfolio :optimizer :runtime :as-of-ms]
                          9000))))
        "Render-time request metadata must not defeat the one-shot auto recompute guard.")
    (is (= []
           (actions/auto-recompute-stale-portfolio-optimizer-scenario
            (assoc-in stale-state
                      contracts/optimization-progress-status-path
                      :running)))
        "A visible recompute status means the background run is already in flight.")))

(deftest load-portfolio-optimizer-history-from-draft-requires-universe-test
  (is (= [[:effects/load-portfolio-optimizer-history]]
         (actions/load-portfolio-optimizer-history-from-draft
          {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                       :market-type :perp
                                                       :coin "BTC"}]}}}})))
  (is (= []
         (actions/load-portfolio-optimizer-history-from-draft
          {:portfolio {:optimizer {:draft {:universe []}}}}))))

(deftest save-portfolio-optimizer-scenario-from-current-always-opens-the-save-modal-test
  ;; Saving no longer requires a solved run: the modal opens at any point, and
  ;; the workflow decides whether the results snapshot is honest to attach
  ;; (stale or missing runs persist setup-only records).
  (let [state (ready-optimizer-state {:kind :historical-mean})]
    (is (= [[:effects/save
              scenario-save-modal-path
              {:open? true
               :name "Untitled Optimization"
               :error nil}]]
           (actions/save-portfolio-optimizer-scenario-from-current
            (assoc-in state
                      [:portfolio :optimizer :last-successful-run]
                      (solved-run-for-state state))))))
  (is (= [[:effects/save
            scenario-save-modal-path
            {:open? true
             :name "Untitled Optimization"
             :error nil}]]
         (actions/save-portfolio-optimizer-scenario-from-current
          {:portfolio {:optimizer {}}}))))

(deftest save-portfolio-optimizer-scenario-from-current-allows-completed-run-after-snapshot-drift-test
  (let [state (ready-optimizer-state {:kind :historical-mean})
        solved-run (solved-run-for-state state)
        state* (-> state
                   (assoc-in [:portfolio :optimizer :run-state]
                             {:status :succeeded
                              :request-signature (:request-signature solved-run)})
                   (assoc-in [:portfolio :optimizer :last-successful-run]
                             solved-run)
                   (assoc-in [:webdata2 :clearinghouseState :marginSummary :accountValue]
                             "2000"))]
    (is (= [[:effects/save
              scenario-save-modal-path
              {:open? true
               :name "Untitled Optimization"
               :error nil}]]
           (actions/save-portfolio-optimizer-scenario-from-current state*)))))

(deftest confirm-portfolio-optimizer-scenario-save-requires-name-test
  (is (= [[:effects/save
           (conj scenario-save-modal-path :error)
           "Enter a scenario name before saving."]]
         (actions/confirm-portfolio-optimizer-scenario-save
          (assoc-in (ready-optimizer-state {:kind :historical-mean})
                    scenario-save-modal-path
                    {:open? true
                     :name "  "
                     :error nil})))))

(deftest confirm-portfolio-optimizer-scenario-save-passes-trimmed-name-test
  (let [state (ready-optimizer-state {:kind :historical-mean})
        state* (-> state
                   (assoc-in [:portfolio :optimizer :last-successful-run]
                             (solved-run-for-state state))
                   (assoc-in scenario-save-modal-path
                             {:open? true
                              :name "  May Rotation  "
                              :error nil}))]
    (is (= [[:effects/save
             (conj scenario-save-modal-path :error)
             nil]
            [:effects/save-portfolio-optimizer-scenario
             {:name "May Rotation"}]]
           (actions/confirm-portfolio-optimizer-scenario-save state*)))))

(deftest confirm-portfolio-optimizer-scenario-save-works-without-solved-run-test
  ;; A named setup with no optimization run yet still saves (as a setup-only
  ;; scenario); the modal no longer demands a rerun.
  (is (= [[:effects/save
           (conj scenario-save-modal-path :error)
           nil]
          [:effects/save-portfolio-optimizer-scenario
           {:name "Pre-run Setup"}]]
         (actions/confirm-portfolio-optimizer-scenario-save
          (assoc-in (ready-optimizer-state {:kind :historical-mean})
                    scenario-save-modal-path
                    {:open? true
                     :name "Pre-run Setup"
                     :error nil})))))

(deftest load-portfolio-optimizer-route-emits-scenario-read-effects-test
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize")))
  ;; A scenario route also fetches HLP for the hypothetical-performance card.
  (is (= [[:effects/load-portfolio-optimizer-scenario "scn_01"]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]
          [:effects/api-fetch-vault-benchmark-details "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/scn_01")))
  ;; The legacy /new alias parses to the same workspace kind, so it loads the
  ;; scenario index for the Scenarios menu exactly like the bare path.
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/new")))
  (is (= []
         (actions/load-portfolio-optimizer-route
          {}
          "/trade"))))

(deftest load-portfolio-optimizer-route-fetches-vault-metadata-for-universe-search-test
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/api-fetch-vault-index-with-cache]
          [:effects/api-fetch-vault-summaries]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}}
          "/portfolio/optimize/new")))
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/api-fetch-vault-index-with-cache]
          [:effects/api-fetch-vault-summaries]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {}}
          "/portfolio/optimize")))
  (is (= [[:effects/load-portfolio-optimizer-scenario "scn_01"]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/api-fetch-vault-index-with-cache]
          [:effects/api-fetch-vault-summaries]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]
          [:effects/api-fetch-vault-benchmark-details "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303"]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {}}
          "/portfolio/optimize/scn_01")))
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]]
         (actions/load-portfolio-optimizer-route
          {:asset-selector {:phase :full}
           :vaults {:merged-index-rows [{:vault-address "0xloaded"}]}}
          "/portfolio/optimize/new"))))

(deftest load-portfolio-optimizer-route-refreshes-cache-only-selector-markets-test
  (is (= [[:effects/load-portfolio-optimizer-scenario-index]
          [:effects/load-portfolio-optimizer-history-discovery]
          [:effects/fetch-asset-selector-markets {:phase :full}]
          [:effects/load-portfolio-optimizer-constraint-profiles]
          [:effects/load-portfolio-optimizer-view-library]
          [:effects/load-portfolio-optimizer-assumption-library]]
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

(deftest set-portfolio-optimizer-selected-risk-instrument-saves-ui-state-test
  (is (= [[:effects/save [:portfolio-ui :optimizer :selected-risk-instrument]
           "perp:MSTR"]]
         (actions/set-portfolio-optimizer-selected-risk-instrument
          {}
          "perp:MSTR"))))

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
