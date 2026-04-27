(ns hyperopen.views.portfolio.optimize.setup-v4-context
  (:require [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.black-litterman-views-panel :as black-litterman-views-panel]
            [hyperopen.views.portfolio.optimize.optimization-progress-panel :as optimization-progress-panel]
            [hyperopen.views.portfolio.optimize.run-status-panel :as run-status-panel]
            [hyperopen.views.portfolio.optimize.setup-readiness-panel :as setup-readiness-panel]))

(def ^:private eyebrow-class
  ["font-mono" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"])

(defn context-rail
  [{:keys [draft readiness snapshot preview-snapshot run-state optimization-progress
           history-load-state last-successful-run result-path]}]
  (let [bl? (= :black-litterman (get-in draft [:return-model :kind]))
        progress-visible? (contains? #{:running :succeeded :failed}
                                     (:status optimization-progress))
        readiness-visible? (or (contains? #{:loading :failed :succeeded}
                                          (:status history-load-state))
                               (contains? #{:no-eligible-history
                                            :incomplete-history
                                            :history-loading}
                                          (:reason readiness))
                               (seq (:warnings readiness)))
        run-visible? (not= :idle (:status run-state))
        last-run-visible? (:result last-successful-run)
        read-only-message (get-in preview-snapshot [:account :read-only-message])
        status-visible? (or progress-visible?
                            readiness-visible?
                            run-visible?
                            last-run-visible?
                            read-only-message)]
    [:aside {:class ["min-h-0"] :data-role "portfolio-optimizer-right-rail"}
     [:section {:class ["border" "border-base-300" "bg-base-100/90" "p-3"]
                :data-role "portfolio-optimizer-assumptions-rail"}
      [:p {:class eyebrow-class}
       (if bl? "Edit views" "Why this preset is safe")]
      (if bl?
        [:div {:class ["mt-3"]}
         (black-litterman-views-panel/black-litterman-views-panel
          draft
          (get-in readiness [:request :black-litterman-prior]))]
        [:div {:class ["mt-3" "space-y-3" "text-xs" "text-trading-muted"]}
         [:p "Stabilized inputs reduce dependence on a single historical window."]
         [:p "Minimum variance does not rely on return forecasts."]
         [:p "Cash floor and turnover caps protect against destructive rebalances."]
         [:p {:class ["font-semibold" "text-warning"]}
          "Switch to Use my views to add beliefs and compare posterior output."]])]
     (when status-visible?
       [:section {:class ["border-t" "border-base-300" "bg-base-100/90" "p-3"]
                  :data-role "portfolio-optimizer-trust-freshness-panel"}
        [:p {:class eyebrow-class} "Trust & Freshness"]
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         (cond
           (not (:snapshot-loaded? snapshot))
           (if (= :manual (get-in preview-snapshot [:capital :source]))
             "Manual capital base is being used for preview sizing."
             "Current portfolio snapshot is not loaded yet.")
           (not (:capital-ready? preview-snapshot))
           "Current portfolio snapshot is available, but no positive capital base is available for preview sizing."
           (not (:execution-ready? preview-snapshot))
           "Current portfolio snapshot is available in read-only mode."
           :else
           "Current portfolio snapshot is available.")]
        (optimization-progress-panel/progress-panel optimization-progress)
        (when readiness-visible?
          (setup-readiness-panel/readiness-panel readiness history-load-state))
        (when run-visible?
          (run-status-panel/run-status-panel run-state))
        (run-status-panel/last-successful-run-panel run-state last-successful-run)
        (when (:result last-successful-run)
          [:button {:type "button"
                    :class ["mt-3" "w-full" "border" "border-warning/60" "bg-warning/10"
                            "px-3" "py-2" "text-left" "text-xs" "font-semibold" "text-warning"]
                    :data-role "portfolio-optimizer-results-link"
                    :on {:click [[:actions/navigate
                                  (or result-path
                                      (portfolio-routes/portfolio-optimize-scenario-path "draft"))]]}}
           "Results"])
        (when read-only-message
          [:p {:class ["mt-3" "border" "border-warning/40" "bg-warning/10" "p-2"
                       "text-xs" "text-warning"]}
           read-only-message])])]))
