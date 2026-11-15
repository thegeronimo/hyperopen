(ns hyperopen.views.portfolio.optimize.scenario-detail-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.common :as action-common]
            [hyperopen.portfolio.optimizer.application.return-inputs :as return-inputs]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [click-actions collect-nodes collect-strings input-actions node-attr node-by-role]]))

(defn- ready-scenario-state
  [scenario-id return-model]
  {:router {:path (str "/portfolio/optimize/" scenario-id)}
   :portfolio {:optimizer
               {:active-scenario {:loaded-id scenario-id
                                  :name "BTC View"
                                  :status :computed
                                  :read-only? false}
                :draft {:id scenario-id
                        :name "BTC View"
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
                          :stale-after-ms 60000}
                :run-state {:status :succeeded
                            :run-id "run-1"
                            :completed-at-ms 2600}}}
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
   {:computed-at-ms 2600
    :request-signature (request-signature-for-state state)
    :result {:status :solved
             :as-of-ms 2600
             :instrument-ids ["perp:BTC"]
             :target-weights [1.0]
             :current-weights [0.0]
             :target-weights-by-instrument {"perp:BTC" 1.0}
             :current-weights-by-instrument {"perp:BTC" 0.0}
             :expected-returns-by-instrument {"perp:BTC" 0.2}
             :expected-return 0.2
             :volatility 1.0
             :performance {:shrunk-sharpe 0.2}
             :history-summary {:return-observations 2 :stale? false}
             :return-model :historical-mean
             :risk-model :sample-covariance
             :diagnostics {:turnover 1.0}
             :rebalance-preview {:status :ready
                                 :capital-usd 1000
                                 :summary {:ready-count 1 :blocked-count 0}
                                 :rows []}}}))

(defn- solved-run-with-baseline
  "A solved run carrying a current baseline (so vol/return deltas are non-nil) and a
  configurable rebalance trade count, for exercising the recommendation verdict and
  the rebalance CTA hierarchy."
  [state {:keys [current-vol target-vol current-return target-return ready-count
                 blocked-count]}]
  (fixtures/sample-last-successful-run
   {:computed-at-ms 2600
    :request-signature (request-signature-for-state state)
    :result {:status :solved
             :as-of-ms 2600
             :instrument-ids ["perp:BTC"]
             :target-weights [1.0]
             :current-weights [0.0]
             :target-weights-by-instrument {"perp:BTC" 1.0}
             :current-weights-by-instrument {"perp:BTC" 0.0}
             :expected-returns-by-instrument {"perp:BTC" target-return}
             :current-expected-return current-return
             :expected-return target-return
             :current-volatility current-vol
             :volatility target-vol
             :current-performance {:in-sample-sharpe 0.8}
             :performance {:in-sample-sharpe 1.0 :shrunk-sharpe 0.5}
             :history-summary {:return-observations 2 :stale? false}
             :return-model :historical-mean
             :risk-model :sample-covariance
             :diagnostics {:turnover 1.0 :gross-exposure 1.0 :net-exposure 1.0}
             :rebalance-preview {:status :ready
                                 :capital-usd 1000
                                 :summary {:ready-count ready-count
                                           :blocked-count (or blocked-count 0)}
                                 :rows []}}}))

(defn- solved-baseline-view
  [opts]
  (let [scenario-id "draft"
        state (ready-scenario-state scenario-id {:kind :historical-mean})
        solved-run (solved-run-with-baseline state opts)]
    (portfolio-view/portfolio-view
     (-> state
         (assoc-in [:portfolio :optimizer :run-state :request-signature]
                   (:request-signature solved-run))
         (assoc-in [:portfolio :optimizer :last-successful-run]
                   solved-run)))))

(deftest portfolio-optimizer-recommendation-leads-with-plain-language-verdict-test
  (let [view-node (solved-baseline-view {:current-vol 0.30 :target-vol 0.24
                                         :current-return 0.08 :target-return 0.10
                                         :ready-count 3})
        recommendation-tab (node-by-role view-node "portfolio-optimizer-recommendation-tab")
        verdict (node-by-role view-node "portfolio-optimizer-recommendation-verdict")
        verdict-text (str/join " " (collect-strings verdict))]
    (is (some? verdict) "The recommendation tab leads with a plain-language verdict.")
    (is (= "portfolio-optimizer-recommendation-verdict"
           (get-in (nth recommendation-tab 2) [1 :data-role])))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (str/includes? verdict-text "estimated volatility 24.00%"))
    (is (str/includes? verdict-text "-6.00 pts vs current"))
    (is (str/includes? verdict-text "+2.00 pts vs current"))
    (is (str/includes? verdict-text "3 trades get you there"))))

(deftest portfolio-optimizer-all-blocked-plan-is-not-already-at-target-test
  (let [view-node (solved-baseline-view {:current-vol 0.30 :target-vol 0.24
                                         :current-return 0.08 :target-return 0.10
                                         :ready-count 0 :blocked-count 2})
        verdict-text (str/join " " (collect-strings
                                    (node-by-role view-node
                                                  "portfolio-optimizer-recommendation-verdict")))]
    (is (str/includes? verdict-text "No trades can be sent — 2 legs are blocked."))
    (is (not (str/includes? verdict-text "Already at target")))
    (is (str/includes? verdict-text "Review trades"))))

(deftest portfolio-optimizer-provenance-strip-drops-invariant-horizon-field-test
  (let [view-node (solved-baseline-view {:current-vol 0.30 :target-vol 0.24
                                         :current-return 0.08 :target-return 0.10
                                         :ready-count 1})
        provenance (node-by-role view-node "portfolio-optimizer-provenance-strip")
        strings (set (collect-strings provenance))]
    (is (not (contains? strings "Horizon")))
    (is (not (contains? strings "Annualized")))))

(deftest portfolio-optimizer-review-rebalance-is-the-single-primary-cta-test
  (let [view-node (solved-baseline-view {:current-vol 0.30 :target-vol 0.24
                                         :current-return 0.08 :target-return 0.10
                                         :ready-count 3})
        cta (node-by-role view-node "portfolio-optimizer-recommendation-rebalance-cta")
        rerun (node-by-role view-node "portfolio-optimizer-scenario-rerun")
        rerun-classes (set (node-attr rerun :class))]
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-review-rebalance"))
        "The header must not duplicate the rebalance CTA.")
    (is (some? cta))
    (is (some #{"Review 3 trades"} (collect-strings cta)))
    (is (contains? rerun-classes "bg-base-200/40"))
    (is (not (contains? rerun-classes "bg-primary/10")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-save")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-refine")))
    (is (= [[:actions/open-portfolio-optimizer-execution]]
           (click-actions cta)))))

(deftest portfolio-optimizer-recommendation-cta-prominence-test
  (let [view-node (solved-baseline-view {:current-vol 0.30 :target-vol 0.24
                                         :current-return 0.08 :target-return 0.10
                                         :ready-count 3})
        cta (node-by-role view-node "portfolio-optimizer-recommendation-rebalance-cta")
        cta-classes (set (node-attr cta :class))
        tip (first (collect-nodes cta
                                  #(some #{"optimizer-verdict-cta-tip"}
                                         (node-attr % :class))))]
    (is (contains? cta-classes "optimizer-verdict-cta--live")
        "Actionable CTA carries the sweeping-border modifier.")
    (is (some? tip) "Guidance moved into the tooltip node.")
    (is (str/includes? (str/join " " (collect-strings tip))
                       "nothing is sent until you arm and confirm"))))

(deftest portfolio-optimizer-zero-trade-result-mutes-rebalance-affordances-test
  (let [view-node (solved-baseline-view {:current-vol 0.24 :target-vol 0.24
                                         :current-return 0.10 :target-return 0.10
                                         :ready-count 0})
        verdict-text (str/join " " (collect-strings
                                    (node-by-role view-node "portfolio-optimizer-recommendation-verdict")))
        cta (node-by-role view-node "portfolio-optimizer-recommendation-rebalance-cta")
        cta-text (str/join " " (collect-strings cta))]
    (is (str/includes? verdict-text "No trades needed"))
    (is (contains? (set (node-attr cta :class)) "text-trading-muted"))
    (is (str/includes? cta-text "Already at target"))
    (is (not (contains? (set (node-attr
                              (node-by-role view-node
                                            "portfolio-optimizer-recommendation-rebalance-cta")
                              :class))
                        "optimizer-verdict-cta--live")))))

(defn- scenario-kpi-delta-classes
  ([current-return target-return current-vol target-vol]
   (scenario-kpi-delta-classes current-return target-return current-vol target-vol 0.7 1.2))
  ([current-return target-return current-vol target-vol current-sharpe target-sharpe]
   (let [view-node (portfolio-view/portfolio-view
                    {:router {:path "/portfolio/optimize/scn_tone"}
                     :portfolio {:optimizer
                                 {:active-scenario {:loaded-id "scn_tone"
                                                    :name "Tone Check"
                                                    :status :computed}
                                  :draft {:id "scn_tone"
                                          :name "Tone Check"
                                          :universe [{:instrument-id "perp:BTC"
                                                      :market-type :perp
                                                      :coin "BTC"}]
                                          :objective {:kind :max-sharpe}
                                          :return-model {:kind :historical-mean}
                                          :risk-model {:kind :sample-covariance}
                                          :constraints {:max-asset-weight 1.0
                                                        :gross-max 1.0}
                                          :metadata {:dirty? false}}
                                  :last-successful-run
                                  (fixtures/sample-last-successful-run
                                   {:computed-at-ms 1714137600000
                                    :request-signature {:seed 1}
                                    :result {:as-of-ms 1714137600000
                                             :instrument-ids ["perp:BTC"]
                                             :current-weights [1.0]
                                             :target-weights [1.0]
                                             :target-weights-by-instrument {"perp:BTC" 1.0}
                                             :current-weights-by-instrument {"perp:BTC" 1.0}
                                             :current-expected-return current-return
                                             :expected-return target-return
                                             :current-volatility current-vol
                                             :volatility target-vol
                                             :current-performance {:in-sample-sharpe current-sharpe}
                                             :performance {:in-sample-sharpe target-sharpe
                                                           :shrunk-sharpe (* 0.5 target-sharpe)}
                                             :history-summary {:return-observations 12 :stale? false}
                                             :return-model :historical-mean
                                             :risk-model :sample-covariance
                                             :diagnostics {:turnover 0.0
                                                           :gross-exposure 1.0
                                                           :net-exposure 1.0}
                                             :rebalance-preview {:status :ready
                                                                 :capital-usd 100000
                                                                 :summary {:ready-count 1 :blocked-count 0}
                                                                 :rows []}}})}}})]
     {:volatility (set (get-in (node-by-role view-node
                                              "portfolio-optimizer-scenario-kpi-volatility")
                               [4 1 :class]))
      :expected-return (set (get-in (node-by-role view-node
                                                  "portfolio-optimizer-scenario-kpi-expected-return")
                                    [4 1 :class]))
      :sharpe (set (get-in (node-by-role view-node
                                          "portfolio-optimizer-scenario-kpi-sharpe")
                           [4 1 :class]))})))

(deftest portfolio-view-delegates-optimizer-scenario-route-to-detail-surface-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_01"}})
        detail-surface (node-by-role view-node "portfolio-optimizer-scenario-detail-surface")
        strings (set (collect-strings view-node))]
    (is (some? detail-surface))
    (is (= "scn_01" (get-in detail-surface [1 :data-scenario-id])))
    (is (some? (node-by-role view-node "portfolio-optimizer-provenance-strip")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tabs")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-recommendation")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-tab-rebalance")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-execution")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-tracking")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-tab-inputs")))
    (is (= :button
           (first (node-by-role view-node
                                "portfolio-optimizer-scenario-tab-tracking")))
        "Switching tabs must not perform browser navigation that loses unsaved run state.")
    (is (nil? (get-in (node-by-role view-node
                                     "portfolio-optimizer-scenario-tab-tracking")
                      [1 :href])))
    (is (contains? (set (get-in (node-by-role view-node
                                              "portfolio-optimizer-scenario-tab-tracking")
                                [1 :class]))
                   "text-trading-text/60")
        "Inactive tabs read as clickable affordances (raised contrast), not disabled chrome.")
    (is (some? (node-by-role view-node "portfolio-optimizer-recommendation-tab")))
    (is (= [[:actions/set-portfolio-optimizer-results-tab :tracking]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-tab-tracking"))))
    (is (= [[:actions/open-portfolio-optimizer-execution]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-tab-execution"))))
    (is (nil? (node-by-role view-node "portfolio-optimizer-setup-surface")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-workspace")))
    (is (contains? strings "Scenario scn_01"))
    (is (contains? strings "Recommendation"))
    (is (not (contains? strings "Rebalance preview")))
    (is (contains? strings "Execution"))
    (is (contains? strings "Tracking"))
    (is (contains? strings "Inputs"))))

(deftest portfolio-optimizer-scenario-detail-exposes-rebalance-paths-for-solved-result-test
  (let [scenario-id "draft"
        state (ready-scenario-state scenario-id {:kind :historical-mean})
        solved-run (solved-run-for-state state)
        view-node (portfolio-view/portfolio-view
                   (-> state
                       (assoc-in [:portfolio :optimizer :run-state :request-signature]
                                 (:request-signature solved-run))
                       (assoc-in [:portfolio :optimizer :last-successful-run]
                                 solved-run)))
        recommendation-cta (node-by-role view-node "portfolio-optimizer-recommendation-rebalance-cta")
        confidence-cta (node-by-role view-node "portfolio-optimizer-result-confidence-rebalance")
        expected [[:actions/open-portfolio-optimizer-execution]]
        strings (set (collect-strings view-node))]
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-review-rebalance"))
        "The header must not duplicate the rebalance CTA (removed 2026-07-13).")
    (is (some? recommendation-cta)
        "The recommendation read-flow must end with a discoverable path to stage the rebalance.")
    (is (some? confidence-cta)
        "The result-confidence rail must lead with rebalance as a clickable next step, not a refine imperative.")
    (is (= expected (click-actions recommendation-cta)))
    (is (= expected (click-actions confidence-cta)))
    (is (contains? strings "Review 1 trade"))
    (is (contains? strings "From here"))
    (is (contains? strings "Draft is solved and usable. Refining only adds frontier density; selection rarely moves."))
    (is (nil? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-recommendation-stale-blocked")))))

(deftest portfolio-optimizer-scenario-detail-hides-rebalance-paths-without-solved-result-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_no_result"}})]
    (is (some? (node-by-role view-node "portfolio-optimizer-recommendation-tab"))
        "Sanity: the recommendation tab still renders so the gate is genuinely exercised.")
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-review-rebalance"))
        "No solved result ⇒ no header rebalance CTA.")
    (is (nil? (node-by-role view-node "portfolio-optimizer-recommendation-rebalance-cta"))
        "No solved result ⇒ no recommendation rebalance CTA.")))

(deftest portfolio-optimizer-scenario-detail-renders-header-kpis-and-provenance-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_01"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_01"
                                                   :name "Capital Rotation"
                                                   :status :computed}
                                 :draft {:name "Capital Rotation"
                                         :metadata {:dirty? true}
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :max-sharpe}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:max-asset-weight 0.4
                                                       :gross-max 1.5}}
                                 :last-successful-run
                                 (fixtures/sample-last-successful-run
                                  {:computed-at-ms 1714137600000
                                   :request-signature {:seed 1}
                                   :result {:as-of-ms 1714137600000
                                            :instrument-ids ["perp:BTC" "perp:ETH"]
                                            :current-weights [0.1 0.2]
                                            :target-weights [0.35 0.15]
                                            :target-weights-by-instrument {"perp:BTC" 0.35 "perp:ETH" 0.15}
                                            :current-weights-by-instrument {"perp:BTC" 0.1 "perp:ETH" 0.2}
                                            :expected-return 0.14
                                            :volatility 0.32
                                            :performance {:shrunk-sharpe 0.44}
                                            :history-summary {:return-observations 12 :stale? false}
                                            :return-model :historical-mean
                                            :risk-model :diagonal-shrink
                                            :return-decomposition-by-instrument
                                            {"perp:BTC" {:return-component 0.1 :funding-component 0.02}
                                             "perp:ETH" {:return-component 0.08 :funding-component 0.01}}
                                            :diagnostics {:turnover 0.2 :gross-exposure 1.5 :net-exposure 0.5}
                                            :rebalance-preview {:status :ready
                                                                :capital-usd 100000
                                                                :summary {:ready-count 2 :blocked-count 0}
                                                                :rows []}}})}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-header")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-status-tag")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-strip")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-expected-return")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-volatility")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-sharpe")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-turnover")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-kpi-rebalance")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-stale-result-banner")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-table")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-recommendation-stale-blocked")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-rebalance-preview")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-tracking-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-stale-banner")))
    (is (fn? (node-attr
              (node-by-role view-node "portfolio-optimizer-scenario-stale-banner")
              :replicant/on-render)))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-rerun-stale")))
    (is (= false
           (boolean
            (get-in (node-by-role view-node "portfolio-optimizer-scenario-save")
                    [1 :disabled]))))
    (is (= [[:actions/open-portfolio-optimizer-scenario-save-modal]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-save"))))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-scenario-rerun"))))
    (is (not (contains? strings "Recompute")))
    (is (contains? strings "Capital Rotation"))
    (doseq [value ["14.00%" "32.00%" "0.571" "20.00%" "1.50x / 0.50x"]]
      (is (contains? strings value)))
    (is (contains? strings "data as of "))
    (is (contains? strings "gross ≤ 1.5 · cap 40.00%"))
    (is (contains? strings "Draft inputs differ from the last successful run. Refreshing automatically while previous output stays visible."))
    (is (contains? strings "Stale Output"))
    (is (contains? strings "These allocation weights come from the last successful run. The app is refreshing them automatically; save and execute unlock when the refreshed run completes."))))

(deftest portfolio-optimizer-allocation-table-renders-position-side-controls-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_sides"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_sides"
                                                   :name "Side Check"
                                                   :status :computed}
                                 :draft {:id "scn_sides"
                                         :name "Side Check"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"
                                                     :shortable? true
                                                     :position-side :long}
                                                    {:instrument-id "perp:ARB"
                                                     :market-type :perp
                                                     :coin "ARB"
                                                     :shortable? true
                                                     :position-side :short}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :sample-covariance}
                                         :constraints {:long-only? false
                                                       :max-asset-weight 0.5
                                                       :gross-max 1.0}
                                         :metadata {:dirty? false}}
                                 :last-successful-run
                                 (fixtures/sample-last-successful-run
                                  {:computed-at-ms 1714137600000
                                   :request-signature {:seed 1}
                                   :result {:as-of-ms 1714137600000
                                            :instrument-ids ["perp:BTC" "perp:ARB"]
                                            :current-weights [0.1 0.0]
                                            :target-weights [0.3 -0.1]
                                            :target-weights-by-instrument {"perp:BTC" 0.3
                                                                           "perp:ARB" -0.1}
                                            :current-weights-by-instrument {"perp:BTC" 0.1
                                                                            "perp:ARB" 0.0}
                                            :labels-by-instrument {"perp:BTC" "BTC"
                                                                   "perp:ARB" "ARB"}
                                            :expected-return 0.08
                                            :volatility 0.22
                                            :performance {:in-sample-sharpe 0.36}
                                            :history-summary {:return-observations 12
                                                              :stale? false}
                                            :return-model :historical-mean
                                            :risk-model :sample-covariance
                                            :diagnostics {:turnover 0.3
                                                          :gross-exposure 0.4
                                                          :net-exposure 0.2}
                                            :rebalance-preview {:status :ready
                                                                :capital-usd 100000
                                                                :summary {:ready-count 2
                                                                          :blocked-count 0}
                                                                :rows []}}})}}})
        btc-long (node-by-role view-node
                               "portfolio-optimizer-target-exposure-side-long-perp-BTC")
        arb-short (node-by-role view-node
                                "portfolio-optimizer-target-exposure-side-short-perp-ARB")]
    (is (some? btc-long))
    (is (some? arb-short))
    (is (= "true" (node-attr btc-long :data-selected)))
    (is (= "true" (node-attr arb-short :data-selected)))
    (is (contains? (set (node-attr btc-long :class)) "bg-success/70"))
    (is (contains? (set (node-attr arb-short :class)) "bg-error/70"))
    (is (= [[:actions/set-portfolio-optimizer-universe-instrument-side-and-run
             "perp:BTC"
             :short]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-target-exposure-side-short-perp-BTC"))))
    (is (= [[:actions/set-portfolio-optimizer-universe-instrument-side-and-run
             "perp:ARB"
             :long]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-target-exposure-side-long-perp-ARB"))))))

(deftest portfolio-optimizer-scenario-sharpe-kpi-renders-current-to-target-raw-sharpe-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_sharpe"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_sharpe"
                                                   :name "Sharpe Check"
                                                   :status :computed}
                                 :draft {:id "scn_sharpe"
                                         :name "Sharpe Check"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :max-sharpe}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :sample-covariance}
                                         :constraints {:max-asset-weight 1.0
                                                       :gross-max 1.0}
                                         :metadata {:dirty? false}}
                                 :last-successful-run
                                 (fixtures/sample-last-successful-run
                                  {:computed-at-ms 1714137600000
                                   :request-signature {:seed 1}
                                   :result {:as-of-ms 1714137600000
                                            :instrument-ids ["perp:BTC"]
                                            :current-weights [1.0]
                                            :target-weights [1.0]
                                            :target-weights-by-instrument {"perp:BTC" 1.0}
                                            :current-weights-by-instrument {"perp:BTC" 1.0}
                                            :current-expected-return 0.35
                                            :expected-return 0.6
                                            :current-volatility 0.5
                                            :volatility 0.5
                                            :current-performance {:in-sample-sharpe 0.7}
                                            :performance {:in-sample-sharpe 1.2
                                                          :shrunk-sharpe 0.6}
                                            :history-summary {:return-observations 12 :stale? false}
                                            :return-model :historical-mean
                                            :risk-model :sample-covariance
                                            :diagnostics {:turnover 0.0
                                                          :gross-exposure 1.0
                                                          :net-exposure 1.0}
                                            :rebalance-preview {:status :ready
                                                                :capital-usd 100000
                                                                :summary {:ready-count 1 :blocked-count 0}
                                                                :rows []}}})}}})
        sharpe-kpi (node-by-role view-node "portfolio-optimizer-scenario-kpi-sharpe")
        strings (set (collect-strings sharpe-kpi))]
    (is (contains? strings "Sharpe · current → target"))
    (is (contains? strings "0.7"))
    (is (contains? strings "1.2"))
    (is (contains? strings "+0.5 · raw Sharpe change"))
    (is (not (contains? strings "0.6"))
        "The headline target Sharpe should use raw in-sample Sharpe, not the shrunk Sharpe field.")))

(deftest portfolio-optimizer-scenario-kpi-delta-classes-reflect-metric-semantics-test
  (let [improved-risk (scenario-kpi-delta-classes 0.1 0.14 0.32 0.24)
        worse-risk (scenario-kpi-delta-classes 0.1 0.14 0.24 0.32 1.2 0.7)
        lower-return (scenario-kpi-delta-classes 0.14 0.1 0.32 0.24)]
    (is (contains? (:volatility improved-risk) "text-trading-green"))
    (is (contains? (:volatility worse-risk) "text-warning"))
    (is (contains? (:expected-return improved-risk) "text-trading-green"))
    (is (contains? (:expected-return lower-return) "text-warning"))
    (is (contains? (:sharpe improved-risk) "text-trading-green"))
    (is (contains? (:sharpe worse-risk) "text-warning"))))

(deftest portfolio-optimizer-scenario-detail-marks-clean-mismatched-result-stale-test
  (let [scenario-id "scn_bl"
        black-litterman-state
        (ready-scenario-state
         scenario-id
         {:kind :black-litterman
          :views [{:kind :absolute
                   :instrument-id "perp:BTC"
                   :return 0.2
                   :confidence 0.75
                   :weights {"perp:BTC" 1}}]})
        historical-state
        (assoc-in black-litterman-state
                  [:portfolio :optimizer :draft :return-model]
                  {:kind :historical-mean})
        view-node (portfolio-view/portfolio-view
                   (assoc-in black-litterman-state
                             [:portfolio :optimizer :last-successful-run]
                             (solved-run-for-state historical-state)))
        save-button (node-by-role view-node "portfolio-optimizer-scenario-save")]
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-stale-banner"))
        "A clean draft is still stale when the retained solved run was produced from different optimizer inputs.")
    (is (= false (boolean (get-in save-button [1 :disabled])))
        "Save stays available while stale — the workflow refuses to attach the mismatched run and persists setup-only.")
    (is (= [[:actions/open-portfolio-optimizer-scenario-save-modal]]
           (click-actions save-button)))
    (is (nil? (node-by-role view-node
                             "portfolio-optimizer-recommendation-stale-blocked"))
        "The recommendation tab should not replace retained output with a rerun-only blocker.")
    (is (some? (node-by-role view-node "portfolio-optimizer-stale-result-banner"))
        "Retained previous output should be clearly labeled as stale.")
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-panel"))
        "A stale retained result should remain visible as previous frontier output.")
    (is (some? (node-by-role view-node "portfolio-optimizer-target-exposure-table"))
        "A stale retained result should keep previous allocation weights visible.")
    (is (nil? (node-by-role view-node
                             "portfolio-optimizer-rerun-stale-result")))
    (is (fn? (node-attr
              (node-by-role view-node "portfolio-optimizer-scenario-stale-banner")
              :replicant/on-render)))))

(deftest portfolio-optimizer-scenario-detail-keeps-completed-result-after-snapshot-drift-test
  (let [scenario-id "draft"
        state (ready-scenario-state scenario-id {:kind :historical-mean})
        solved-run (solved-run-for-state state)
        view-node (portfolio-view/portfolio-view
                   (-> state
                       (assoc-in [:portfolio :optimizer :run-state :request-signature]
                                 (:request-signature solved-run))
                       (assoc-in [:portfolio :optimizer :last-successful-run]
                                 solved-run)
                       (assoc-in [:webdata2 :clearinghouseState :marginSummary :accountValue]
                                 "2000")))
        save-button (node-by-role view-node "portfolio-optimizer-scenario-save")]
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-stale-banner")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-recommendation-stale-blocked")))
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-frontier-panel")))
    (is (= false (get-in save-button [1 :disabled])))
    (is (= [[:actions/open-portfolio-optimizer-scenario-save-modal]]
           (click-actions save-button)))))

(deftest portfolio-optimizer-scenario-save-modal-collects-name-before-save-test
  (let [scenario-id "draft"
        state (ready-scenario-state scenario-id {:kind :historical-mean})
        solved-run (solved-run-for-state state)
        view-node (portfolio-view/portfolio-view
                   (-> state
                       (assoc-in [:portfolio :optimizer :run-state :request-signature]
                                 (:request-signature solved-run))
                       (assoc-in [:portfolio :optimizer :last-successful-run]
                                 solved-run)
                       (assoc-in [:portfolio :optimizer :scenario-save-modal]
                                 {:open? true
                                  :name "May Rotation"
                                  :error nil})))
        modal (node-by-role view-node "portfolio-optimizer-scenario-save-modal")
        input (node-by-role view-node "portfolio-optimizer-scenario-save-name")
        confirm (node-by-role view-node "portfolio-optimizer-scenario-save-confirm")
        cancel (node-by-role view-node "portfolio-optimizer-scenario-save-cancel")
        strings (set (collect-strings modal))]
    (is (some? modal))
    (is (contains? strings "Save scenario as"))
    (is (= "May Rotation" (node-attr input :value)))
    (is (= [[:actions/set-portfolio-optimizer-scenario-save-name
             [:event.target/value]]]
           (input-actions input)))
    (is (= [[:actions/confirm-portfolio-optimizer-scenario-save]]
           (click-actions confirm)))
    (is (= [[:actions/close-portfolio-optimizer-scenario-save-modal]]
           (click-actions cancel)))))

(deftest portfolio-optimizer-scenario-detail-retains-recommendation-while-recomputing-test
  (let [scenario-id "scn_recompute"
        base-state (ready-scenario-state scenario-id {:kind :historical-mean})
        solved-run (solved-run-for-state base-state)
        state (-> base-state
                  (assoc-in [:portfolio :optimizer :last-successful-run] solved-run)
                  (assoc-in [:portfolio :optimizer :draft :universe]
                            [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "perp:ETH"
                              :market-type :perp
                              :coin "ETH"}])
                  (assoc-in [:portfolio :optimizer :draft :metadata :dirty?] true)
                  (assoc-in [:portfolio :optimizer :optimization-progress]
                            {:status :running
                             :run-id "run-recompute"
                             :started-at-ms 2600
                             :overall-percent 35
                             :steps [{:id :fetch-returns
                                      :label "Fetch returns"
                                      :status :running
                                      :percent 35}]}))
        view-node (portfolio-view/portfolio-view state)
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-results-surface")))
    (is (some? (node-by-role view-node "portfolio-optimizer-recompute-banner")))
    (is (some? (node-by-role view-node "portfolio-optimizer-progress-panel")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-recommendation-stale-blocked")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-scenario-stale-banner")))
    (is (contains? strings "Recomputing recommendation"))
    (is (contains? strings "Keeping the previous allocation visible until the new run finishes."))))

(deftest portfolio-optimizer-scenario-detail-objective-menu-renders-actions-test
  (let [scenario-id "draft"
        base-state (ready-scenario-state scenario-id {:kind :historical-mean})
        solved-run (solved-run-for-state base-state)
        solved-state (assoc-in base-state
                               [:portfolio :optimizer :last-successful-run]
                               solved-run)
        open-menu-state (fn [selection]
                          (cond-> (assoc-in solved-state
                                            [:portfolio-ui :optimizer :objective-menu-open?]
                                            true)
                            selection
                            (assoc-in [:portfolio-ui :optimizer :objective-menu-selection]
                                      selection)))
        closed-view (portfolio-view/portfolio-view solved-state)
        open-view (portfolio-view/portfolio-view (open-menu-state :max-sharpe))
        changed-view (portfolio-view/portfolio-view
                      (open-menu-state :minimum-volatility))
        views-view (portfolio-view/portfolio-view
                    (-> (open-menu-state :max-sharpe)
                        (assoc-in [:portfolio :optimizer :draft :universe]
                                  [{:instrument-id "perp:BTC"
                                    :market-type :perp
                                    :coin "BTC"}
                                   {:instrument-id "perp:DOGE"
                                    :market-type :perp
                                    :coin "DOGE"}])
                        (assoc-in [:portfolio :optimizer :draft :return-model]
                                  {:kind :black-litterman
                                   :views [{:id "bl_view_1"
                                            :kind :absolute
                                            :instrument-id "perp:BTC"
                                            :return 0.15
                                            :confidence-level :medium
                                            :confidence 0.5
                                            :weights {"perp:BTC" 1}}]})
                        (assoc-in [:portfolio-ui :optimizer :objective-menu-view-drafts]
                                  {:perp:BTC {:return-text "18"}})))
        implied-state (-> (open-menu-state nil)
                          (assoc-in [:portfolio :optimizer :draft :return-model]
                                    {:kind :black-litterman :views []}))
        implied-view (portfolio-view/portfolio-view implied-state)
        implied-btc-text (-> (setup-readiness/build-readiness implied-state)
                             return-inputs/readiness-inputs-by-instrument
                             (get "perp:BTC")
                             return-inputs/decimal->percent-text)
        trigger (node-by-role closed-view
                              "portfolio-optimizer-objective-menu-trigger")
        open-trigger (node-by-role open-view
                                   "portfolio-optimizer-objective-menu-trigger")
        menu (node-by-role open-view "portfolio-optimizer-objective-menu")
        menu-classes (set (get-in menu [1 :class]))
        apply-model-switch (node-by-role open-view
                                         "portfolio-optimizer-objective-menu-apply")
        apply-noop (node-by-role views-view
                                 "portfolio-optimizer-objective-menu-apply")
        apply-changed (node-by-role changed-view
                                    "portfolio-optimizer-objective-menu-apply")
        minimum-row (node-by-role open-view
                                  "portfolio-optimizer-objective-menu-option-minimum-volatility")
        max-sharpe-row (node-by-role open-view
                                     "portfolio-optimizer-objective-menu-option-max-sharpe")
        inline-editor (node-by-role views-view
                                    "portfolio-optimizer-objective-menu-use-my-views-editor")
        btc-row (node-by-role views-view
                              "portfolio-optimizer-objective-menu-view-row-perp:BTC")
        doge-row (node-by-role views-view
                               "portfolio-optimizer-objective-menu-view-row-perp:DOGE")
        btc-return (node-by-role views-view
                                 "portfolio-optimizer-objective-menu-view-perp:BTC-return")
        btc-step-up (node-by-role views-view
                                  "portfolio-optimizer-objective-menu-view-perp:BTC-step-up")
        btc-step-down (node-by-role views-view
                                    "portfolio-optimizer-objective-menu-view-perp:BTC-step-down")
        doge-return (node-by-role views-view
                                  "portfolio-optimizer-objective-menu-view-perp:DOGE-return")
        btc-implied-return (node-by-role implied-view
                                         "portfolio-optimizer-objective-menu-view-perp:BTC-return")
        btc-confidence-medium (node-by-role views-view
                                            "portfolio-optimizer-objective-menu-view-perp:BTC-confidence-medium")
        btc-confidence-high (node-by-role views-view
                                          "portfolio-optimizer-objective-menu-view-perp:BTC-confidence-high")
        doge-confidence-medium (node-by-role views-view
                                             "portfolio-optimizer-objective-menu-view-perp:DOGE-confidence-medium")
        btc-reset (node-by-role views-view
                                "portfolio-optimizer-objective-menu-view-perp:BTC-reset")
        option-roles (->> (collect-nodes menu vector?)
                          (keep #(get-in % [1 :data-role]))
                          (filter #(str/starts-with? % "portfolio-optimizer-objective-menu-option-"))
                          vec)
        strings (set (collect-strings open-view))]
    (is (= [[:actions/open-portfolio-optimizer-objective-menu]]
           (click-actions trigger)))
    (is (= "true" (get-in trigger [1 :aria-haspopup])))
    (is (= "false" (get-in trigger [1 :aria-expanded])))
    (is (= "true" (get-in open-trigger [1 :aria-expanded])))
    (is (nil? (node-by-role closed-view "portfolio-optimizer-objective-menu")))
    (is (nil? (node-by-role open-view "portfolio-optimizer-objective-menu-backdrop")))
    (is (every? menu-classes ["optimizer-objective-menu" "optimizer-objective-popover"
                              "focus:outline-none" "focus:ring-0"]))
    (is (= "region" (get-in menu [1 :role])))
    (is (nil? (get-in menu [1 :aria-modal])))
    (is (= [[:actions/handle-portfolio-optimizer-objective-menu-keydown [:event/key]]]
           (get-in menu [1 :on :keydown])))
    (doseq [label ["Change objective"
                   "Re-runs the solver with the same universe and constraints"
                   "Minimum risk" "Maximum Sharpe" "· 12%" "Maximum return"]]
      (is (contains? strings label)))
    (is (not (contains? strings "Use my views"))
        "Views are an input policy of Maximum Sharpe now, not an objective.")
    (is (= ["portfolio-optimizer-objective-menu-option-minimum-volatility"
            "portfolio-optimizer-objective-menu-option-max-sharpe"
            "portfolio-optimizer-objective-menu-option-equal-risk"
            "portfolio-optimizer-objective-menu-option-target-volatility"
            "portfolio-optimizer-objective-menu-option-maximum-return"]
           option-roles)
        "All five objective options always render; the use-my-views option is gone.")
    (is (= [[:actions/select-portfolio-optimizer-objective-menu-option :minimum-volatility]]
           (click-actions minimum-row)))
    (is (= [[:actions/select-portfolio-optimizer-objective-menu-option :max-sharpe]]
           (click-actions max-sharpe-row)))
    (is (nil? (node-by-role open-view
                            "portfolio-optimizer-objective-menu-use-my-views-editor-rows")))
    (is (= [[:actions/set-portfolio-optimizer-return-model-kind :black-litterman]]
           (click-actions (node-by-role open-view
                                        "portfolio-optimizer-objective-menu-use-my-views-editor-views-on"))))
    (is (nil? (node-by-role changed-view
                            "portfolio-optimizer-objective-menu-use-my-views-editor")))
    (is (contains? (set (collect-strings inline-editor)) "Return views"))
    (is (= "user" (node-attr btc-row :data-source)))
    (is (= "implied" (node-attr doge-row :data-source)))
    (is (= "https://app.hyperliquid.xyz/coins/BTC.svg"
           (get-in (node-by-role btc-row
                                 "portfolio-optimizer-objective-menu-view-perp:BTC-icon")
                   [2 1 :src])))
    (is (= "18" (get-in btc-return [1 :value]))
        "The typing buffer wins over the authored view's 15.")
    (is (= "" (get-in doge-return [1 :value]))
        "No readiness history for DOGE, so the implied row prefills nothing.")
    (is (= "—" (get-in doge-return [1 :placeholder])))
    (is (and (seq implied-btc-text) (not= "20" implied-btc-text))
        "Implied inputs come from the readiness baseline, never the run's posterior.")
    (is (= implied-btc-text (get-in btc-implied-return [1 :value])))
    (is (contains? (set (get-in btc-return [1 :class])) "pr-9"))
    (is (contains? (set (collect-strings btc-row)) "%"))
    (is (= [[:actions/set-portfolio-optimizer-objective-menu-view-return "perp:BTC"
             [:event.target/value]]]
           (get-in btc-return [1 :on :input])))
    (is (fn? (get-in btc-return [1 :on :keydown])))
    (is (= "Increase BTC return" (get-in btc-step-up [1 :aria-label])))
    (is (= "Decrease BTC return" (get-in btc-step-down [1 :aria-label])))
    (is (= [[:actions/step-portfolio-optimizer-objective-menu-view-return "perp:BTC" :up]]
           (click-actions btc-step-up)))
    (is (= [[:actions/step-portfolio-optimizer-objective-menu-view-return "perp:BTC" :down]]
           (click-actions btc-step-down)))
    (is (= "true" (get-in btc-confidence-medium [1 :data-selected]))
        "Selected confidence comes from the authored view.")
    (is (= "false" (get-in btc-confidence-high [1 :data-selected])))
    (is (= "false" (get-in doge-confidence-medium [1 :data-selected]))
        "Implied rows select no confidence; medium is no longer a default.")
    (is (= ["medium trust in this return" "save as your view · medium confidence"]
           [(get-in btc-confidence-medium [1 :data-tooltip])
            (get-in doge-confidence-medium [1 :data-tooltip])]))
    (is (= "Set medium confidence" (get-in btc-confidence-medium [1 :aria-label])))
    (is (= ["Medium"] (collect-strings btc-confidence-medium)))
    (is (= [[:actions/set-portfolio-optimizer-objective-menu-view-confidence "perp:BTC"
             :medium]]
           (click-actions btc-confidence-medium)))
    (is (= [[:actions/remove-portfolio-optimizer-objective-menu-view "perp:BTC"]]
           (click-actions btc-reset)))
    (is (nil? (node-by-role doge-row
                            "portfolio-optimizer-objective-menu-view-perp:DOGE-reset"))
        "Implied rows have nothing to reset.")
    (is (= false (get-in apply-model-switch [1 :disabled]))
        "Applying moves this historical-mean draft onto the views-aware model.")
    (is (= [[:actions/apply-portfolio-optimizer-objective-menu-selection-and-run]]
           (click-actions apply-model-switch)))
    (is (= true (get-in apply-noop [1 :disabled]))
        "Maximum Sharpe re-applied to an already views-aware draft is a no-op.")
    (is (nil? (click-actions apply-noop)))
    (is (= false (get-in apply-changed [1 :disabled])))
    (is (= [[:actions/apply-portfolio-optimizer-objective-menu-selection-and-run]]
           (click-actions apply-changed)))
    (is (= [[:actions/close-portfolio-optimizer-objective-menu]]
           (click-actions
            (node-by-role open-view "portfolio-optimizer-objective-menu-cancel"))))
    (is (= [[:actions/close-portfolio-optimizer-objective-menu]]
           (click-actions
            (node-by-role open-view "portfolio-optimizer-objective-menu-close"))))))

(deftest portfolio-optimizer-scenario-detail-objective-menu-rehydrates-loaded-views-test
  (let [scenario-id "draft"
        base-state (ready-scenario-state scenario-id {:kind :historical-mean})
        solved-run (solved-run-for-state base-state)
        view-node (portfolio-view/portfolio-view
                   (-> base-state
                       (assoc-in [:portfolio :optimizer :last-successful-run]
                                 solved-run)
                       (assoc-in [:portfolio :optimizer :draft :return-model]
                                 {:kind :black-litterman
                                  :views [{:id "bl_view_1"
                                           :kind "absolute"
                                           :instrument-id "perp:BTC"
                                           :return 0.18
                                           :confidence-level "high"
                                           :confidence 0.75
                                           :weights {"perp:BTC" 1}}]})
                       (assoc-in [:portfolio-ui :optimizer :objective-menu-open?]
                                 true)))
        btc-return (node-by-role view-node
                                 "portfolio-optimizer-objective-menu-view-perp:BTC-return")
        btc-confidence-high (node-by-role
                             view-node
                             "portfolio-optimizer-objective-menu-view-perp:BTC-confidence-high")]
    (is (= "18" (get-in btc-return [1 :value])))
    (is (= "true" (get-in btc-confidence-high [1 :data-selected])))))

(deftest portfolio-optimizer-inputs-tab-renders-read-only-audit-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_inputs"}
                    :portfolio-ui {:optimizer {:results-tab :inputs}}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_inputs"
                                                   :status :saved}
                                 :draft {:id "scn_inputs"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :target-volatility}
                                         :return-model {:kind :black-litterman
                                                        :views [{:id "view-1"}]}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true
                                                       :max-asset-weight 0.4
                                                       :gross-max 1.2
                                                       :max-turnover 0.5
                                                       :rebalance-tolerance 0.01
                                                       :dust-usdc 15}
                                         :execution-assumptions {:manual-capital-usdc 25000
                                                                 :fallback-slippage-bps 35
                                                                 :default-order-type :market
                                                                 :fee-mode :taker}}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-tab")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-audit-grid")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-universe")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-models")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-constraints")))
    (is (some? (node-by-role view-node "portfolio-optimizer-inputs-execution-assumptions")))
    (is (= [[:actions/duplicate-portfolio-optimizer-scenario "scn_inputs"]]
           (click-actions
            (node-by-role view-node "portfolio-optimizer-inputs-duplicate"))))
    (is (contains? strings "Read-only scenario input audit. Duplicate the scenario before editing inputs."))
    (is (contains? strings "perp:BTC"))
    (is (contains? strings "Black-Litterman views: 1"))
    (is (contains? strings "Manual capital: $25,000"))))

(deftest portfolio-optimizer-scenario-detail-does-not-render-stale-loaded-scenario-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_new"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_old"
                                                   :status :executed}
                                 :draft {:name "Old scenario"
                                         :universe [{:instrument-id "perp:OLD"
                                                     :market-type :perp
                                                     :coin "OLD"}]}
                                 :last-successful-run (fixtures/sample-last-successful-run
                                                       {:result {:instrument-ids ["perp:OLD"]}})
                                 :tracking {:scenario-id "scn_old"
                                            :snapshots
                                            [{:scenario-id "scn_old"
                                              :rows [{:instrument-id "perp:OLD"
                                                      :current-weight 1
                                                      :target-weight 1
                                                      :weight-drift 0
                                                      :signed-notional-usdc 1000}]}]}}}})
        detail-surface (node-by-role view-node "portfolio-optimizer-scenario-detail-surface")
        strings (set (collect-strings view-node))]
    (is (= "scn_new" (get-in detail-surface [1 :data-scenario-id])))
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-loading-state")))
    (is (contains? strings "Scenario scn_new"))
    (is (not (contains? strings "Old scenario")))
    (is (not (contains? strings "perp:OLD")))))

(deftest portfolio-optimizer-scenario-detail-hides-unsaved-run-while-route-load-pending-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/scn_loading"}
                    :portfolio {:optimizer
                                {:active-scenario {:loaded-id nil
                                                   :status :idle}
                                 :scenario-load-state {:status :loading
                                                       :scenario-id "scn_loading"}
                                 :draft {:name "Unsaved draft"
                                         :universe [{:instrument-id "perp:UNSAVED"
                                                     :market-type :perp
                                                     :coin "UNSAVED"}]}
                                 :last-successful-run (fixtures/sample-last-successful-run
                                                       {:result {:instrument-ids ["perp:UNSAVED"]}})}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-scenario-loading-state")))
    (is (contains? strings "Scenario scn_loading"))
    (is (not (contains? strings "Unsaved draft")))
    (is (not (contains? strings "perp:UNSAVED")))))
