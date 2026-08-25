(ns hyperopen.views.portfolio.optimize.setup-context
  (:require ["lucide/dist/esm/icons/circle-alert.js" :default lucide-circle-alert]
            ["lucide/dist/esm/icons/circle-check.js" :default lucide-circle-check]
            ["lucide/dist/esm/icons/clock.js" :default lucide-clock]
            ["lucide/dist/esm/icons/history.js" :default lucide-history]
            ["lucide/dist/esm/icons/loader-circle.js" :default lucide-loader-circle]
            ["lucide/dist/esm/icons/scaling.js" :default lucide-scaling]
            ["lucide/dist/esm/icons/target.js" :default lucide-target]
            ["lucide/dist/esm/icons/waypoints.js" :default lucide-waypoints]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.portfolio.optimizer.application.view-model.setup-summary :as setup-summary]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.optimization-progress-panel :as optimization-progress-panel]
            [hyperopen.views.portfolio.optimize.run-status-panel :as run-status-panel]
            [hyperopen.views.portfolio.optimize.scenario-objective-menu :as scenario-objective-menu]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]
            [hyperopen.views.portfolio.optimize.setup-readiness-panel :as setup-readiness-panel]))

(def ^:private eyebrow-class
  ["font-mono" "text-[0.6875rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"])

(defn- contract-row
  [label value]
  ;; Row labels are plain sentence-case text — mono/uppercase is reserved for
  ;; VALUES and section eyebrows; a rail full of uppercase-mono labels is what
  ;; made the contract read like telemetry. 110px label track keeps
  ;; "Exposure policy" on one line.
  [:div {:class ["grid" "grid-cols-[110px_minmax(0,1fr)]" "items-baseline" "gap-2"]}
   [:span {:class ["text-[0.6875rem]" "font-medium" "text-trading-muted"]}
    label]
   [:span {:class ["min-w-0" "text-[0.75rem]" "font-medium" "leading-[1.4]"
                   "text-trading-text"]}
    value]])

(def ^:private lucide-icon
  ;; Shared helper (setup-controls) — icons themselves stay per-ns requires.
  controls/lucide-icon)

(defn- verdict-value
  "Status value for the Run summary: the same run-verdict the footer pill
  renders, so the rail and the run bar can never disagree about \"ready\".
  Runnable states carry a green check and plain text; blocked gets the red
  alert, loading a spinner. :refreshing is RUNNABLE — a background reload over a
  bundle that already landed — so it spins in a dimmer tone than :loading and
  must never render the blocked treatment."
  [{:keys [level label]}]
  [:span {:class ["inline-flex" "min-w-0" "items-center" "justify-end" "gap-1.5"]
          :data-role "portfolio-optimizer-run-summary-status"
          :data-verdict (some-> level name)}
   (case level
     :blocked (lucide-icon lucide-circle-alert ["text-error"])
     :loading (lucide-icon lucide-loader-circle ["animate-spin" "text-trading-muted"])
     :refreshing (lucide-icon lucide-loader-circle ["animate-spin" "text-trading-muted/50"])
     (lucide-icon lucide-circle-check ["text-success"]))
   [:span {:class ["truncate"]} label]])

(defn- exposure-target-value
  "One-line target-exposure echo (\"19.50× gross · +10.48× net long\") with the
  net side tinted by direction, matching the exposure pad's readout."
  [{:keys [gross-label net-label direction net-output-only?]}]
  [:span {:class ["font-mono"]}
   (or gross-label "--")
   [:span {:class ["text-trading-muted"]} " gross"]
   [:span {:class ["text-trading-muted/60"]} " · "]
   [:span {:class [(case direction
                     :long "text-success"
                     :short "text-error"
                     "text-trading-text")]}
    (or net-label "--")]
   (when-not net-output-only?
     [:span {:class ["text-trading-muted"]}
      (case direction
        :long " net long"
        :short " net short"
        " net")])])

(defn- summary-row
  "Run-summary line: muted icon + label on the left, right-aligned value."
  [icon-node label value]
  [:div {:class ["flex" "min-h-[1.75rem]" "items-center" "gap-2.5"]}
   (lucide-icon icon-node ["text-trading-muted/60"])
   [:span {:class ["shrink-0" "text-[0.75rem]" "text-trading-muted"]} label]
   [:span {:class ["ml-auto" "min-w-0" "text-right" "text-[0.75rem]" "font-medium"
                   "leading-[1.4]" "text-trading-text"]}
    value]])

(defn- summary-card
  "The Run summary: what the solver will receive plus the global verdict, as an
  icon-labeled stack (designer parity, 2026-07-10) the user can verify at a
  glance instead of inferring it from scattered controls. Derived output, not
  primary input. While the holdings auto-seed is pending the Universe row says
  so — the rail is where the user looks to confirm readiness, so it must
  reflect the wait. The exposure line reads the policy TARGET; the band
  numbers live in the exposure section itself."
  [draft readiness {:keys [history-data-label verdict]}]
  (let [{:keys [asset-count objective-label exposure-target]}
        (optimizer-view-model/setup-summary-card-model draft {:labelize controls/labelize})
        holdings-loading? (= :holdings-loading (:reason readiness))]
    [:section {:class ["optimizer-setup-panel" "border" "border-base-300" "bg-base-100/90" "p-3"]
               :data-role "portfolio-optimizer-setup-summary-card"}
     [:p {:class ["text-[0.875rem]" "font-semibold" "text-trading-text"]}
      "Run summary"]
     [:div {:class ["mt-1.5" "space-y-0.5"]}
      (summary-row lucide-waypoints "Universe"
                   (if holdings-loading?
                     "Loading holdings…"
                     (str asset-count " assets")))
      (summary-row lucide-target "Goal" objective-label)
      (summary-row lucide-scaling "Exposure" (exposure-target-value exposure-target))
      ;; One compact provenance line ("70 native · 8 modeled") instead of
      ;; restating each assumption — the assumptions panel below carries the
      ;; per-asset detail.
      (when history-data-label
        (summary-row lucide-history "History data"
                     [:span {:data-role "portfolio-optimizer-run-summary-history-data"}
                      history-data-label]))
      (when verdict
        (summary-row lucide-clock "Status" (verdict-value verdict)))]]))

(defn- rail-assumption-row
  "One-line disclosure per workflow asset: label + the same one-line summary
  the center's collapsed card shows + the status chip; the full label/value
  pairs sit behind the toggle. A row that still needs input renders forced
  open so unfinished work stays visible (machine condition, so re-asserting
  :open against the user's toggle is the point); complete and loading rows
  rest closed — the detail is one click away, not deleted."
  [{:keys [instrument-id label configured? history-loading? summary summary-pairs]}]
  (let [needs-input? (and (not configured?) (not history-loading?))]
    [:details (cond-> {:class ["border" "border-base-300" "bg-base-200/20"]
                       :data-role (str "portfolio-optimizer-history-assumptions-rail-row-"
                                       instrument-id)
                       :replicant/key (str "history-assumptions-rail-row-" instrument-id)}
                needs-input? (assoc :open true))
     [:summary {:class ["flex" "cursor-pointer" "select-none" "items-center" "gap-2"
                        "p-2" "focus:outline-none" "focus:text-warning"]}
      [:span {:class ["shrink-0" "font-mono" "text-[0.75rem]" "font-semibold"]}
       label]
      [:span {:class ["min-w-0" "flex-1" "truncate" "text-[0.6875rem]"
                      "text-trading-muted"]
              :data-role (str "portfolio-optimizer-history-assumptions-rail-summary-"
                              instrument-id)}
       (or summary "Not configured yet")]
      ;; While this asset's proxy history is still fetching, both "Configured"
      ;; and "Needs input" are provisional verdicts - say loading until the
      ;; fetch settles. Configured is a glyph (the row already says everything
      ;; else); actionable states keep words.
      [:span (cond-> {:class ["ml-auto" "shrink-0" "font-mono" "text-[0.625rem]"
                              "uppercase" "tracking-[0.08em]"
                              (when history-loading? "animate-pulse")
                              (cond history-loading? "text-trading-muted"
                                    configured? "text-success"
                                    :else "text-warning")]
                      :data-role (str "portfolio-optimizer-history-assumptions-rail-status-"
                                      instrument-id)}
               configured? (assoc :aria-label "Configured")
               history-loading? (assoc :data-loading "true"))
       (cond history-loading? "Loading history…"
             configured? "✓"
             :else "Needs input")]]
     (into [:div {:class ["space-y-0.5" "border-t" "border-base-300" "p-2"]}]
           (map (fn [[pair-label pair-value]]
                  (contract-row pair-label pair-value)))
           summary-pairs)]))

(defn- history-assumptions-rail-panel
  "Per-asset modeling summary for the assumption workflow: one disclosure line
  per asset with the compact facts behind it, and a configured-count in the
  header. No aggregate ready banner — \"ready\" lives in the run verdict and
  Data health only. While any proxy history is still fetching the count reads
  loading (a configured-count judged against a not-yet-loaded usable set is
  the same mid-flight falsehood the old ready banner gated on)."
  [{:keys [applicable? rows configured-count any-loading?
           recommended-count recommended-actions]}]
  (when applicable?
    [:section {:class ["optimizer-setup-panel" "border" "border-base-300"
                       "bg-base-100/90" "p-3"]
               :data-role "portfolio-optimizer-history-assumptions-rail"
               :replicant/key "history-assumptions-rail"}
     [:div {:class ["flex" "items-baseline" "justify-between" "gap-2"]}
      [:p {:class eyebrow-class} "History assumptions"]
      [:span (cond-> {:class ["font-mono" "text-[0.6875rem]" "tracking-[0.08em]"
                              (cond any-loading? "text-trading-muted"
                                    (= configured-count (count rows)) "text-success"
                                    :else "text-warning")
                              (when any-loading? "animate-pulse")]
                      :data-role "portfolio-optimizer-history-assumptions-rail-count"}
               any-loading? (assoc :data-loading "true"))
       (if any-loading?
         "Loading history…"
         (str configured-count " of " (count rows) " configured"))]]
     ;; Rail-level shortcut for the center section's apply-all banner: the rail
     ;; is where the user reads "0 of 7 configured", so the one-click fix must
     ;; be reachable here too, not only after scrolling to the cards. Same
     ;; action id as the banner — one funnel, one set of guards.
     (when (pos? (or recommended-count 0))
       [:button {:type "button"
                 :class ["mt-2" "w-full" "border" "border-success/50" "bg-success/10"
                         "px-2" "py-1.5" "text-center" "font-mono" "text-[0.6875rem]"
                         "font-semibold" "uppercase" "tracking-[0.08em]" "text-success"]
                 :data-role "portfolio-optimizer-history-assumptions-rail-apply-all-recommended"
                 :on {:click [[(:apply-all recommended-actions)]]}}
        (str "Apply all recommended (" recommended-count ")")])
     ;; Height-capped and internally scrollable, same idiom as the Return
     ;; views editor's optimizer-objective-view-rows: a universe with many
     ;; thin-history assets otherwise grows this list without bound, which
     ;; stretches the whole 3-column setup grid row (default CSS Grid
     ;; align-items: stretch) and leaves the shorter columns trailing acres
     ;; of empty panel background below their actual content.
     (into [:div {:class ["optimizer-history-assumptions-rows" "min-h-0" "mt-2" "space-y-1"
                          "overflow-x-hidden" "overflow-y-auto"]
                  :data-role "portfolio-optimizer-history-assumptions-rail-rows"}]
           (map rail-assumption-row)
           rows)]))

(defn context-rail
  [{:keys [draft state readiness snapshot preview-snapshot run-state optimization-progress
           history-load-state last-successful-run current-result? result-path]}]
  (let [progress-visible? (contains? #{:running :succeeded :failed}
                                     (:status optimization-progress))
        readiness-visible? (or (contains? #{:loading :failed :succeeded}
                                          (:status history-load-state))
                               (contains? #{:no-eligible-history
                                            :incomplete-history
                                            :missing-history-assumptions
                                            :history-loading
                                            :holdings-loading}
                                          (:reason readiness))
                               (seq (:warnings readiness)))
        run-visible? (not= :idle (:status run-state))
        last-run-visible? (:result last-successful-run)
        read-only-message (get-in preview-snapshot [:account :read-only-message])
        status-visible? (or progress-visible?
                            readiness-visible?
                            run-visible?
                            last-run-visible?
                            read-only-message)
        views-active? (= :black-litterman (get-in draft [:return-model :kind]))
        objective-kind (get-in draft [:objective :kind])
        ;; Covariance-only goals: views never influence the weights, so the
        ;; rail note names the goal and the CTA switches goals.
        return-free? (contains? setup-summary/return-free-objective-kinds
                                objective-kind)
        ;; The editor needs BOTH: a views-aware return model AND an objective
        ;; that consumes returns. Equal Risk sets only the objective, so coming
        ;; from Maximum Sharpe it leaves Black-Litterman active — without the
        ;; return-free check the rail would keep soliciting per-asset return
        ;; inputs that cannot move Equal Risk weights.
        views-editor-active? (and views-active? (not return-free?))
        return-free-label (case objective-kind
                            :equal-risk "Not used by Equal Risk"
                            :inverse-volatility "Not used by Risk-weighted sizing"
                            "Not used by Minimum risk")
        readiness-model (optimizer-view-model/readiness-panel-model readiness history-load-state)
        ;; Computed once for the whole rail: the assumptions panel rows, the
        ;; Run-summary History-data line, and the unified verdict (the same
        ;; run-verdict the footer pill consumes, so rail and run bar can never
        ;; disagree about "ready").
        rail-model (optimizer-view-model/history-assumption-rail-model
                    state draft readiness history-load-state
                    {:labelize controls/labelize
                     :percent-label controls/percent-label})
        assumption-count (count (:rows rail-model))
        history-data-label (when (pos? assumption-count)
                             (str (max 0 (- (count (:universe draft)) assumption-count))
                                  " native · " assumption-count " modeled"))
        verdict (optimizer-view-model/run-verdict readiness history-load-state)
        snapshot-line (cond
                        (not (:snapshot-loaded? snapshot))
                        (if (= :manual (get-in preview-snapshot [:capital :source]))
                          "Manual capital base is being used for preview sizing."
                          "Current portfolio snapshot is not loaded yet.")
                        (not (:capital-ready? preview-snapshot))
                        "Current portfolio snapshot is available, but no positive capital base is available for preview sizing."
                        (not (:execution-ready? preview-snapshot))
                        "Current portfolio snapshot is available in read-only mode."
                        :else
                        "Current portfolio snapshot is available.")
        ;; Two generic healthy lines ("snapshot available" + "history loaded")
        ;; collapse into one status sentence; anything unhealthy keeps its own.
        combined-status-line (when (and (:snapshot-loaded? snapshot)
                                        (:capital-ready? preview-snapshot)
                                        (:execution-ready? preview-snapshot)
                                        (= :succeeded (:status history-load-state)))
                               "Portfolio snapshot and optimizer history loaded.")]
    [:aside {:class ["optimizer-context-rail" "min-h-0"]
             :data-role "portfolio-optimizer-right-rail"}
     (summary-card draft readiness {:history-data-label history-data-label
                                    :verdict verdict})
     ;; The full Return views editor renders only while views actually shape
     ;; the result (views-aware model AND a returns-consuming objective); when
     ;; views are inert the section is demoted to the one-line note below, so
     ;; inactive functionality never competes with live warnings.
     (when views-editor-active?
       [:section {:class ["optimizer-setup-panel" "border" "border-base-300" "bg-base-100/90" "p-3"]
                  :data-role "portfolio-optimizer-assumptions-rail"
                  :replicant/key "return-views-editor"}
        [:p {:class eyebrow-class} "Return views"]
        [:div {:class ["mt-3"]}
         (scenario-objective-menu/views-editor-section
          draft
          state
          (:result last-successful-run)
          readiness
          ;; OPEN by default: under Maximum Sharpe the per-asset return
          ;; forecasts are the core input driving the result — hiding them
          ;; behind a closed disclosure made the optimizer look more objective
          ;; than it is (owner + expert review, 2026-07-04). Still collapsible
          ;; so the user can tuck it away; the rows list is height-capped in
          ;; CSS so Data health stays reachable on large universes.
          {:container-role "portfolio-optimizer-setup-use-my-views-editor"
           :title "Used by Maximum Sharpe"
           :collapsible? true
           :open? true
           :description "Edit any return to save it as your view. Saved views override implied returns; the rest use the implied baseline."})]])
     ;; Demoted inactive note: a TRUE one-liner — eyebrow, status, and the
     ;; one-click way to make views matter, on a single row. An inactive
     ;; feature must not spend a titled section + explainer announcing that it
     ;; is not in use (comprehension pass 2026-07-10). The whole Return-views
     ;; slot (editor or note) sits ABOVE Data health so the rail order is
     ;; stable across goals (owner review 2026-07-04).
     (when-not views-editor-active?
       [:section {:class ["optimizer-setup-panel" "border" "border-base-300" "bg-base-100/90" "p-3"]
                  :data-role "portfolio-optimizer-assumptions-rail"
                  :replicant/key "return-views-inactive"}
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-3" "gap-y-1.5"]}
         [:p {:class eyebrow-class} "Return views"]
         [:span {:class ["min-w-0" "truncate" "text-[0.6875rem]" "text-trading-muted"]
                 :data-role "portfolio-optimizer-return-views-inactive"}
          (if return-free? return-free-label "Not used")]
         [:button {:type "button"
                   :class ["ml-auto" "shrink-0" "border" "border-base-300" "bg-base-200/40"
                           "px-2" "py-0.5" "text-[0.6875rem]" "font-semibold"
                           "text-trading-muted" "hover:bg-base-200/60"]
                   :data-role "portfolio-optimizer-return-views-activate"
                   :on {:click (if return-free?
                                 [[:actions/apply-portfolio-optimizer-setup-preset :max-sharpe]]
                                 [[:actions/set-portfolio-optimizer-return-model-kind
                                   :black-litterman]])}}
          (if return-free? "Switch to Maximum Sharpe" "Use my views")]]])
     (history-assumptions-rail-panel rail-model)
     (when status-visible?
       [:section {:class ["optimizer-setup-panel" "border-t" "border-base-300" "bg-base-100/90" "p-3"]
                  :data-role "portfolio-optimizer-trust-freshness-panel"
                  :replicant/key "data-health"}
        [:p {:class eyebrow-class} "Data health"]
        ;; The verdict is the section's headline — the first thing scanned —
        ;; not a small chip competing with the warning cards below it.
        (let [{:keys [level label issue-count]} (:status readiness-model)]
          [:p {:class ["mt-1" "text-[0.8125rem]" "font-semibold"
                       (case level
                         :blocked "text-error"
                         :caution "text-warning"
                         :ready "text-success"
                         "text-trading-muted")]
               :data-role "portfolio-optimizer-data-health-status"}
           ;; The count keeps the verdict meaningful even when the warning
           ;; cards sit below other panels ("Ready with cautions · 3 issues").
           (if (and (pos? (or issue-count 0))
                    (contains? #{:caution :blocked} level))
             (str label " · " issue-count
                  (if (= 1 issue-count) " issue" " issues"))
             label)])
        [:p {:class ["mt-1.5" "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]}
         (or combined-status-line snapshot-line)]
        (optimization-progress-panel/progress-panel optimization-progress)
        (when readiness-visible?
          (setup-readiness-panel/readiness-panel
           readiness history-load-state
           {:suppress-copy? (some? combined-status-line)}))
        (when run-visible?
          (run-status-panel/run-status-panel run-state))
        (run-status-panel/last-successful-run-panel run-state last-successful-run)
        (when (and current-result? (:result last-successful-run))
          (let [result-path* (or result-path
                                 (portfolio-routes/portfolio-optimize-scenario-path "draft"))]
            [:div {:class ["mt-3" "grid" "grid-cols-2" "gap-2"]
                   :data-role "portfolio-optimizer-results-links"}
             [:button {:type "button"
                       :class ["border" "border-warning/60" "bg-warning/10"
                               "px-3" "py-2" "text-center" "text-[0.8125rem]" "font-medium" "text-warning"]
                       :data-role "portfolio-optimizer-results-link"
                       :on {:click [[:actions/navigate result-path*]]}}
              "Results"]
             [:button {:type "button"
                       :class ["border" "border-primary/50" "bg-primary/10"
                               "px-3" "py-2" "text-center" "text-[0.8125rem]" "font-medium" "text-primary"]
                       :data-role "portfolio-optimizer-rebalance-link"
                       :on {:click [[:actions/navigate result-path*]
                                    [:actions/open-portfolio-optimizer-execution]]}}
              "Review trades"]]))
        (when read-only-message
          [:p {:class ["mt-3" "border" "border-warning/40" "bg-warning/10" "p-2"
                       "text-[0.8125rem]" "text-warning"]}
           read-only-message])])]))
