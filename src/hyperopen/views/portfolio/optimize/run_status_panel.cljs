(ns hyperopen.views.portfolio.optimize.run-status-panel)

(defn- warning-code-label
  [warning]
  (some-> (:code warning) name))

(defn draft-state-badge
  [draft]
  [:p {:class ["mt-3"
               "rounded-md"
               "border"
               "border-base-300"
               "bg-base-200/40"
               "px-3"
               "py-2"
               "text-xs"
               "font-semibold"
               "text-trading-muted"]
       :data-role "portfolio-optimizer-draft-state"}
   (if (get-in draft [:metadata :dirty?])
     "Draft has unsaved changes"
     "Draft clean")])

(defn- run-status-label
  [status]
  (case status
    :running "Running"
    :succeeded "Succeeded"
    :failed "Failed"
    :infeasible "Infeasible"
    :unsupported "Unsupported"
    :idle "Idle"
    (name (or status :idle))))

(defn run-status-panel
  [run-state]
  (let [status (:status run-state)
        error (:error run-state)]
    [:div {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
           :data-role "portfolio-optimizer-run-status-panel"}
     [:p {:class ["text-[0.65rem]"
                  "font-semibold"
                  "uppercase"
                  "tracking-[0.18em]"
                  "text-trading-muted"]}
      "Run Status"]
     [:p {:class ["mt-2" "text-sm" "font-semibold"]}
      (run-status-label status)]
     (when (and (= :running status)
                (:started-at-ms run-state))
       [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
        "Worker run is in flight. Last successful result is retained until completion."])
     (when error
       [:div {:class ["mt-3" "rounded-md" "border" "border-error/40" "bg-error/10" "p-2"
                      "text-xs" "text-error"]}
        [:p {:class ["font-semibold"]} (warning-code-label error)]
        (when (:message error)
          [:p {:class ["mt-1"]} (:message error)])])]))

(defn last-successful-run-panel
  [run-state last-successful-run]
  (let [result (:result last-successful-run)
        asset-count (count (or (:instrument-ids result) []))]
    (when result
      [:div {:class ["mt-4" "rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
             :data-role "portfolio-optimizer-last-successful-run"}
       [:p {:class ["text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.18em]"
                    "text-trading-muted"]}
        "Last Successful Run"]
       [:p {:class ["mt-2" "text-sm" "font-semibold" "tabular-nums"]}
        (str asset-count " assets")]
       [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
        (if (= :running (:status run-state))
          "Retaining last successful result while rerunning."
          "Last successful result is available for comparison.")]])))
