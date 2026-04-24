(ns hyperopen.views.portfolio.optimize.setup-readiness-panel)

(defn- warning-code-label
  [warning]
  (some-> (:code warning) name))

(defn- readiness-copy
  [readiness]
  (case (:reason readiness)
    :missing-universe "Select a universe before running."
    :no-eligible-history "History is required before this draft can run."
    :incomplete-history "Reload history before running this changed universe."
    :history-loading "History reload is still in flight."
    "Optimizer inputs are ready to run."))

(defn- history-load-copy
  [history-load-state readiness]
  (case (:status history-load-state)
    :loading "Loading optimizer history for the selected universe."
    :succeeded "Optimizer history is loaded. Rerun this if the universe changes."
    :failed "History load failed. Existing history, if any, is retained."
    (readiness-copy readiness)))

(defn readiness-panel
  [readiness history-load-state]
  (let [warnings (vec (:warnings readiness))
        history-loading? (= :loading (:status history-load-state))
        missing-universe? (= :missing-universe (:reason readiness))]
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
     [:button {:type "button"
               :class ["mt-3"
                       "w-full"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-100"
                       "px-3"
                       "py-2"
                       "text-left"
                       "text-sm"
                       "font-semibold"
                       "disabled:cursor-not-allowed"
                       "disabled:opacity-60"]
               :data-role "portfolio-optimizer-load-history"
               :disabled (or history-loading? missing-universe?)
               :on {:click [[:actions/load-portfolio-optimizer-history-from-draft]]}}
      (if history-loading?
        "Loading History"
        "Load History")]
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
