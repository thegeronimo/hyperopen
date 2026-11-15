(ns hyperopen.views.portfolio.optimize.results-panel
  (:require [hyperopen.portfolio.optimizer.application.view-model.results :as results-model]
            [hyperopen.portfolio.optimizer.application.view-model.setup-summary :as setup-summary]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.views.portfolio.optimize.equal-risk-confidence-rail
             :as equal-risk-confidence-rail]
            [hyperopen.views.portfolio.optimize.equal-risk-impact-strip
             :as equal-risk-impact-strip]
            [hyperopen.views.portfolio.optimize.frontier-chart :as frontier-chart]
            [hyperopen.views.portfolio.optimize.leverage-impact-panel
             :as leverage-impact-panel]
            [hyperopen.views.portfolio.optimize.refinement-status-card :as refinement-card]
            [hyperopen.views.portfolio.optimize.results-diagnostics-rail :as diagnostics-rail]
            [hyperopen.views.portfolio.optimize.results-summary :as summary]
            [hyperopen.views.portfolio.optimize.risk-contributions-card :as risk-contributions-card]
            [hyperopen.views.portfolio.optimize.risk-weighted-sizing-card
             :as risk-weighted-sizing-card]
            [hyperopen.views.portfolio.optimize.scenario-objective-menu :as objective-menu]
            [hyperopen.views.portfolio.optimize.target-exposure-table :as target-exposure-table]
            [hyperopen.views.portfolio.optimize.volatility-intuition-card
             :as volatility-intuition-card]))

(defn- active-views-editor
  [state draft result readiness]
  (when (and (= :black-litterman (get-in draft [:return-model :kind]))
             (not (contains? setup-summary/return-free-objective-kinds
                             (get-in draft [:objective :kind]))))
    (objective-menu/views-editor-section
     draft
     state
     result
     readiness
     {:container-role "portfolio-optimizer-results-your-views-editor"
      :title "Return views"
      :description "Annualized. Your views tilt the forecast; implied rows use the baseline estimate. Edits save and rerun automatically."
      :extra-class "optimizer-results-your-views-editor"
      :collapsible? true
      :include-apply? true
      :apply-role "portfolio-optimizer-results-your-views-apply"})))

(defn results-panel
  ([last-successful-run]
   (results-panel last-successful-run nil))
  ([last-successful-run draft]
   (results-panel last-successful-run draft nil))
  ([last-successful-run draft {:keys [state stale? frontier-overlay-mode
                                      readiness
                                      constrain-frontier?
                                      refinement]
                               :or {frontier-overlay-mode :standalone}}]
   (let [result (results-model/enrich-result-labels (:result last-successful-run) draft)
         selected-risk-instrument (get-in state
                                          contracts/ui-selected-risk-instrument-path)
         equal-risk? (= :equal-risk (get-in result [:solver :objective-kind]))
         inverse-volatility? (= :inverse-volatility
                                (get-in result [:solver :objective-kind]))
         frontier? (not (contains? contracts-constants/covariance-only-objective-kinds
                                   (get-in result [:solver :objective-kind])))]
     (when (= :solved (:status result))
       [:section {:class ["optimizer-results-surface" "space-y-0" "leading-4"]
                  :replicant/key "optimizer-results-surface"
                  :data-role "portfolio-optimizer-results-surface"}
        (summary/stale-result-banner stale?)
        [:div {:class ["optimizer-results-grid" "grid" "grid-cols-1"
                       "xl:grid-cols-[420px_minmax(0,1fr)]"
                       "2xl:grid-cols-[500px_minmax(0,1fr)_320px]"]
               :replicant/key "optimizer-results-grid"
               :data-role "portfolio-optimizer-results-grid"}
         [:div {:class ["optimizer-results-left-panel" "min-h-0" "space-y-0"]
                :data-role "portfolio-optimizer-results-left-panel"}
          (target-exposure-table/target-exposure-table result
                                                        {:state state
                                                         :draft draft
                                                         :selected-risk-instrument
                                                         selected-risk-instrument})]
         [:div {:class ["optimizer-results-center-panel" "min-h-0" "bg-base-100" "p-6"
                        "space-y-4"]
                :data-role "portfolio-optimizer-results-center-panel"}
          [:div {:class ["space-y-4"]
                 :replicant/key (cond
                                  equal-risk? "equal-risk-center"
                                  inverse-volatility? "inverse-volatility-center"
                                  :else "frontier-center")}
           (when equal-risk?
             (equal-risk-impact-strip/equal-risk-impact-strip result))
           (when equal-risk?
             (risk-contributions-card/risk-contributions-card
              result
              {:selected-risk-instrument selected-risk-instrument}))
           (when inverse-volatility?
             (risk-weighted-sizing-card/risk-weighted-sizing-card
              result
              {:selected-risk-instrument selected-risk-instrument}))
           (when frontier?
             (frontier-chart/frontier-chart
              draft
              result
              frontier-overlay-mode
              constrain-frontier?))
           [:div {:replicant/key "optimizer-leverage-impact-slot"
                  :data-role "portfolio-optimizer-leverage-impact-slot"}
            (leverage-impact-panel/leverage-impact-panel result)]
           (when equal-risk?
             (summary/equal-risk-context-card result))
           (when-not equal-risk?
             (summary/target-context-card result))
           (when frontier?
             (refinement-card/refinement-status-card refinement))]]
         [:div {:class ["optimizer-results-right-panel" "min-h-0"
                        "xl:col-span-2" "2xl:col-span-1"]
                :data-role "portfolio-optimizer-results-right-panel"}
          (or (equal-risk-confidence-rail/equal-risk-confidence-rail result)
              (when frontier?
                (diagnostics-rail/result-confidence-rail refinement)))
          [:div {:replicant/key "optimizer-volatility-risk-cards"
                 :data-role "portfolio-optimizer-volatility-risk-cards"}
           (volatility-intuition-card/volatility-intuition-card result)]
          (cond
            equal-risk?
            (equal-risk-confidence-rail/equal-risk-confidence-quality-rail result)

            frontier?
            (diagnostics-rail/result-confidence-quality-rail refinement))
          (diagnostics-rail/trust-diagnostics-rail result)
          (active-views-editor state draft result readiness)]]]))))
