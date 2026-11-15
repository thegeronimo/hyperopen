(ns hyperopen.workbench.scenes.optimize.recommendation-scenes
  "Workbench scenes for the scenario Recommendation tab composition (verdict bar +
  results grid). Exists to eyeball the review-and-act hierarchy: primary CTA in the
  verdict bar above the fold, compact refinement card with a closed disclosure,
  review-first rail with the return-views editor collapsed — especially under a
  large universe, which is exactly the case that used to bury the CTA."
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.results-summary :as results-summary]
            [hyperopen.views.portfolio.optimize.scenario-kpi-strip :as kpi-strip]))

(portfolio/configure-scenes
  {:title "Recommendation tab"
   :collection :optimize})

(def ^:private many-symbols
  ["BTC" "ETH" "SOL" "AAPL" "TSM" "SP500" "GOLD" "SILVER"
   "DOGE" "AVAX" "LINK" "ARB" "OP" "EWZ" "NOW" "DKNG"])

(def ^:private many-target-weights
  [0.22 0.14 0.09 0.25 0.11 0.08 0.05 0.04
   -0.06 -0.04 0.03 -0.05 0.02 -0.03 0.06 -0.02])

(def ^:private many-current-weights
  [0.30 0.20 0.05 0.05 0.02 0.0 0.0 0.0
   0.0 0.02 0.08 0.0 0.04 0.05 0.0 0.06])

(defn- instrument-id
  [symbol]
  (str "perp:" symbol))

(def ^:private many-instrument-ids
  (mapv instrument-id many-symbols))

(defn- weights-by-id
  [weights]
  (zipmap many-instrument-ids weights))

(def ^:private many-universe
  (mapv (fn [symbol]
          {:instrument-id (instrument-id symbol)
           :market-type :perp
           :coin symbol})
        many-symbols))

(def ^:private many-frontier
  [{:id 0 :expected-return 0.08 :volatility 0.18 :sharpe 0.44
    :weights many-target-weights}
   {:id 1 :expected-return 0.14 :volatility 0.29 :sharpe 0.48
    :weights many-target-weights}
   {:id 2 :expected-return 0.21 :volatility 0.46 :sharpe 0.46
    :weights many-target-weights}])

(def ^:private many-assets-result
  {:status :solved
   :as-of-ms 1751400000000
   :instrument-ids many-instrument-ids
   :labels-by-instrument (zipmap many-instrument-ids many-symbols)
   :current-weights many-current-weights
   :target-weights many-target-weights
   :current-weights-by-instrument (weights-by-id many-current-weights)
   :target-weights-by-instrument (weights-by-id many-target-weights)
   :current-expected-return 0.06
   :expected-return 0.14
   :current-volatility 0.34
   :volatility 0.29
   :current-performance {:in-sample-sharpe 0.18}
   :performance {:in-sample-sharpe 0.48 :shrunk-sharpe 0.24}
   :history-summary {:return-observations 260 :return-days 373 :stale? false}
   :return-model :black-litterman
   :risk-model :diagonal-shrink
   :frontier many-frontier
   :frontier-summary {:source :display-sweep :point-count 40}
   :diagnostics {:gross-exposure 1.29
                 :net-exposure 0.89
                 :effective-n 6.4
                 :turnover 0.52
                 :covariance-conditioning {:status :ok
                                           :condition-number 840
                                           :min-eigenvalue 0.004}
                 :weight-sensitivity-by-instrument
                 {"perp:AAPL" {:base-expected-return 0.16
                               :down-expected-return 0.13
                               :up-expected-return 0.19}}
                 :binding-constraints [{:instrument-id "perp:AAPL"
                                        :constraint :upper-bound
                                        :weight 0.25
                                        :bound 0.25}
                                       {:instrument-id "perp:DOGE"
                                        :constraint :lower-bound
                                        :weight -0.06
                                        :bound -0.06}]}
   :warnings [{:code :short-history
               :instrument-id "perp:SP500"
               :message "perp:SP500 has fewer usable observations than the rest of the universe."}
              {:code :proxy-history
               :instrument-id "perp:GOLD"
               :message "perp:GOLD uses proxy history for part of the window."}
              {:code :concentration
               :message "Target concentrates more than half of gross exposure in three names."}]
   :rebalance-preview {:status :ready
                       :capital-usd 250000
                       :summary {:ready-count 12 :blocked-count 1}
                       :rows []}})

(def ^:private many-assets-draft
  {:id "draft"
   :universe many-universe
   :objective {:kind :max-sharpe}
   :return-model {:kind :black-litterman
                  :views [{:id "bl_view_aapl"
                           :kind :absolute
                           :instrument-id "perp:AAPL"
                           :return 0.18
                           :confidence-level :medium
                           :confidence 0.5
                           :weights {"perp:AAPL" 1}}
                          {:id "bl_view_btc"
                           :kind :absolute
                           :instrument-id "perp:BTC"
                           :return 0.22
                           :confidence-level :high
                           :confidence 0.75
                           :weights {"perp:BTC" 1}}]}
   :risk-model {:kind :diagonal-shrink}
   :constraints {:max-asset-weight 0.25 :gross-max 1.5}
   :metadata {:dirty? false}})

(defn- depth-options
  [selected]
  [{:key :quick :points 56 :label "Quick" :hint "Light pass · ~a few seconds"
    :selected? (= selected :quick)}
   {:key :thorough :points 72 :label "Thorough" :hint "Balanced · ~10–20s est."
    :selected? (= selected :thorough)}
   {:key :maximum :points 80 :label "Maximum" :hint "Densest · ~20–40s est."
    :selected? (= selected :maximum)}])

(def ^:private draft-refinement
  {:solved? true
   :in-flight? false
   :can-refine? true
   :depth :thorough
   :depth-options (depth-options :thorough)
   :assessment {:tier :draft
                :point-count 40
                :frontier-quality :medium
                :selection-stability :provisional
                :exact-selection? false
                :next-step :refine-optimization
                :stop-reason :draft-budget-reached}
   :runtime-ms 7350
   :progress {:overall-percent nil :active-step nil}})

(defn- recommendation-shell
  "Mirror the recommendation-tab composition (verdict bar, then results grid) inside
  the .portfolio-optimizer surface scope at a desktop-ish width."
  [result draft refinement]
  (layout/page-shell
   (layout/desktop-shell
    [:div {:class ["portfolio-optimizer" "mx-auto" "w-full"]
           :style {:max-width "1440px"}}
     [:div {:class ["space-y-0"]}
      (results-summary/verdict-headline (kpi-strip/recommendation-deltas result))
      (results-panel/results-panel
       {:result result
        :computed-at-ms 1751400000000}
       draft
       {:frontier-overlay-mode :standalone
        :refinement refinement})]])))

(portfolio/defscene many-assets-black-litterman
  []
  (recommendation-shell many-assets-result many-assets-draft draft-refinement))

(def ^:private few-symbols ["BTC" "ETH" "SOL" "AVAX"])

(def ^:private few-ids (mapv instrument-id few-symbols))

(def ^:private few-target [0.45 0.3 0.15 0.1])

(def ^:private few-current [0.6 0.25 0.1 0.05])

(def ^:private few-assets-result
  (merge many-assets-result
         {:instrument-ids few-ids
          :labels-by-instrument (zipmap few-ids few-symbols)
          :current-weights few-current
          :target-weights few-target
          :current-weights-by-instrument (zipmap few-ids few-current)
          :target-weights-by-instrument (zipmap few-ids few-target)
          :return-model :historical-mean
          :warnings []
          :diagnostics (assoc (:diagnostics many-assets-result)
                              :binding-constraints [])
          :rebalance-preview {:status :ready
                              :capital-usd 50000
                              :summary {:ready-count 4 :blocked-count 0}
                              :rows []}}))

(def ^:private few-assets-draft
  {:id "draft"
   :universe (mapv (fn [symbol]
                     {:instrument-id (instrument-id symbol)
                      :market-type :perp
                      :coin symbol})
                   few-symbols)
   :objective {:kind :minimum-variance}
   :return-model {:kind :historical-mean}
   :risk-model {:kind :diagonal-shrink}
   :constraints {:max-asset-weight 0.6 :gross-max 1.0}
   :metadata {:dirty? false}})

(portfolio/defscene few-assets-historical
  []
  (recommendation-shell few-assets-result few-assets-draft draft-refinement))
