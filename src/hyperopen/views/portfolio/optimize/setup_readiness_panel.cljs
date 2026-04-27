(ns hyperopen.views.portfolio.optimize.setup-readiness-panel)

(defn- warning-code-label
  [warning]
  (some-> (:code warning) name))

(defn- readiness-copy
  [readiness]
  (case (:reason readiness)
    :missing-universe "Select a universe before running."
    :no-eligible-history "Run Optimization will fetch history before computing."
    :incomplete-history "Run Optimization will refresh history for this changed universe."
    :history-loading "History reload is in flight as part of the optimization run."
    "Optimizer inputs are ready to run."))

(defn- history-load-copy
  [history-load-state readiness]
  (case (:status history-load-state)
    :loading "Loading optimizer history for the selected universe."
    :succeeded "Optimizer history is loaded. Run Optimization refreshes this when the universe changes."
    :failed "History load failed. Existing history, if any, is retained."
    (readiness-copy readiness)))

(defn readiness-panel
  [readiness history-load-state]
  (let [warnings (vec (:warnings readiness))]
    [:div {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
           :data-role "portfolio-optimizer-readiness-panel"}
     [:p {:class ["text-[0.65rem]"
                  "font-semibold"
                  "uppercase"
                  "tracking-[0.18em]"
                  "text-trading-muted"]}
      "Readiness"]
     [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
      (history-load-copy history-load-state readiness)]
     (when-let [error-message (get-in history-load-state [:error :message])]
       [:p {:class ["mt-3"
                    "rounded-md"
                    "border"
                    "border-error/40"
                    "bg-error/10"
                    "px-2"
                    "py-1.5"
                    "text-xs"
                    "text-error"]}
        error-message])
     (when (seq warnings)
       (into
        [:div {:class ["mt-3" "space-y-2"]}]
        (map (fn [warning]
               [:p {:class ["rounded-md"
                            "border"
                            "border-warning/40"
                            "bg-warning/10"
                            "px-2"
                            "py-1.5"
                            "text-xs"
                            "font-semibold"
                            "text-warning"]
                    :data-role "portfolio-optimizer-readiness-warning"}
                (warning-code-label warning)])
             warnings)))]))
