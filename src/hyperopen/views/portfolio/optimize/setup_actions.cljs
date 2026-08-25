(ns hyperopen.views.portfolio.optimize.setup-actions
  (:require [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

(defn model-assumptions-panel
  "Collapsed by default: assumptions are explanation, not configuration, so they
  sit on the same tertiary tier as 'Why this preset is safe' instead of
  competing with the constraint numbers for attention."
  []
  [:details {:class ["optimizer-note" "optimizer-model-assumptions-note"
                     "border" "border-base-300" "bg-base-100/90"]
             :data-role "portfolio-optimizer-model-assumptions-panel"
             :data-optimizer-note "true"}
   (controls/disclosure-heading "What this model assumes" nil)
   [:ul {:class ["mt-2" "space-y-px" "text-[0.75rem]" "leading-[1.45]" "text-trading-muted"]}
    [:li "Returns are roughly normal at the chosen horizon."]
    [:li "Past covariance is informative about future covariance."]
    [:li "Cross-margin is treated as one book."]
    [:li "Tail risk and drawdown are not modeled in this setup pass."]]])

(defn- action-objective-label
  [objective-kind]
  (case objective-kind
    :max-sharpe "Maximum Sharpe"
    :equal-risk "Equal Risk"
    :target-volatility "Target volatility"
    :target-return "Target return"
    "Minimum risk"))

(defn- action-model-label
  [return-kind risk-kind]
  (cond
    (= :black-litterman return-kind) "your views + implied returns"
    (= :mixed-frequency risk-kind) "mixed-frequency covariance"
    (= :sample-covariance risk-kind) "sample historical returns"
    :else "stabilized historical returns"))

(defn- run-status-label
  [reason]
  (case reason
    :holdings-loading "Loading holdings"
    :history-loading "Loading history"
    :no-eligible-history "No usable history"
    :incomplete-history "History incomplete"
    :missing-history-assumptions "Needs assumptions"
    :missing-universe "Add assets to run"
    "Not ready to run"))

(defn run-status
  "Pure derivation of the Run button + status-pill state from the run gate and
  readiness. The pill never reads green \"Ready to run\" while readiness is not
  runnable; instead it names the blocking reason so the CTA cannot contradict the
  Readiness panel beside it. `:verdict` (view-model run-verdict) folds in the
  page-wide warning state: runnable-with-warnings reads as an amber
  \"Ready with N warning(s)\" so a green pill can never sit next to a visible
  warning (e.g. current exposure outside the configured policy)."
  [{:keys [run-triggerable? running? readiness asset-count verdict]}]
  (let [runnable? (if (some? readiness)
                    (boolean (:runnable? readiness))
                    true)
        ready? (boolean (and run-triggerable? runnable?))]
    (cond
      running?
      {:ready? false :tone :busy :label "Optimizing"}

      (and ready? (= :caution (:level verdict)))
      {:ready? true :tone :caution :label (:label verdict)}

      ;; A background reload over an already-loaded bundle. The run is allowed,
      ;; so this stays ready-toned; the label just tells the truth about the
      ;; fetch still in flight instead of claiming a settled "Ready to run".
      (and ready? (= :refreshing (:level verdict)))
      {:ready? true :tone :ready :label (:label verdict)}

      ready?
      {:ready? true :tone :ready :label "Ready to run"}

      ;; Waiting on the holdings snapshot is a machine state, not a user task:
      ;; it must win over the zero-asset "Add assets to run" instruction, which
      ;; would tell the user to do the work the seed is about to do.
      (= :holdings-loading (:reason readiness))
      {:ready? false :tone :busy :label "Loading holdings"}

      (zero? (or asset-count 0))
      {:ready? false :tone :empty :label "Add assets to run"}

      :else
      {:ready? false :tone :blocked :label (run-status-label (:reason readiness))})))

(defn results-tab-nav-actions
  "Click actions that open the results surface at `tab` from the setup route.

  `navigate` switches to the scenario route and
  `set-portfolio-optimizer-results-tab` selects the tab. nexus applies both
  `:effects/save` projections (router path, then the results tab) before the
  route loaders run, so the results surface opens on the requested tab instead of
  re-deriving it from the URL."
  [result-path tab]
  [[:actions/navigate result-path]
   [:actions/set-portfolio-optimizer-results-tab tab]])

(defn- result-nav-button
  "Opens the results surface from the setup route. By default it navigates and selects
  `tab`; pass `actions` to override the click vector (e.g. navigate + stage execution)."
  [{:keys [result-path tab label data-role accent? actions]}]
  [:button {:type "button"
            :class (into ["border" "px-3" "py-2" "whitespace-nowrap"
                          "text-[0.8125rem]" "font-semibold" "scroll-mb-12"]
                         (if accent?
                           ["border-primary/50" "bg-primary/10" "text-primary"]
                           ["border-base-300" "bg-base-200/30" "text-trading-text"]))
            :data-role data-role
            :on {:click (or actions (results-tab-nav-actions result-path tab))}}
   label])

(defn setup-bottom-actions
  [{:keys [draft readiness running? run-triggerable? saving-scenario? solved-run?
           result-path verdict]}]
  (let [asset-count (count (:universe draft))
        objective-copy (action-objective-label (get-in draft [:objective :kind]))
        model-copy (action-model-label (get-in draft [:return-model :kind])
                                       (get-in draft [:risk-model :kind]))
        status (run-status {:run-triggerable? run-triggerable?
                            :running? running?
                            :readiness readiness
                            :asset-count asset-count
                            :verdict verdict})
        ready? (:ready? status)
        holdings-loading? (= :holdings-loading (:reason readiness))]
    [:section {:class ["optimizer-setup-actions"
                       "relative" "z-[180]" "mt-2" "flex" "flex-col" "items-start" "gap-3"
                       "border" "border-base-300" "bg-[#101518]"
                       "px-7" "py-[14px]" "scroll-mb-12" "leading-4"
                       "sm:flex-row" "sm:flex-wrap" "sm:items-center" "sm:gap-4"]
               :data-role "portfolio-optimizer-setup-bottom-actions"}
     [:button {:type "button"
               :class ["optimizer-primary-action"
                       "border" "border-warning/70" "bg-warning/80" "px-6" "py-2.5"
                       "whitespace-nowrap" "text-[0.8125rem]" "font-semibold" "text-base-100"
                       "shadow-[0_0_0_1px_rgba(0,0,0,0.25)]"
                       "scroll-mb-12"
                       "disabled:cursor-not-allowed" "disabled:border-base-300"
                       "disabled:bg-base-200/30" "disabled:text-trading-muted"
                       "disabled:shadow-none"]
               :data-role "portfolio-optimizer-run-draft"
               ;; The button stays clickable while a universe is present even when
               ;; readiness is not runnable: clicking is the intentional
               ;; "run retries anything still missing" history-load affordance.
               ;; Honesty lives in the status pill below, not by disabling the CTA.
               ;; Row edits materialize views into the draft immediately, so BL no
               ;; longer needs the objective-menu apply path to bulk-collect them:
               ;; Run is the same plain action for every return model.
               :disabled (not run-triggerable?)
               :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
      ;; Goal language, not policy language: "safe defaults" described the app's
      ;; internal posture and turned misleading the moment the user customized
      ;; anything. The status-detail line below still names the exact
      ;; objective/model being solved.
      (cond
        running? "Optimizing…"
        holdings-loading? "Loading holdings…"
        :else "Run optimization")]
     [:button {:type "button"
               :class ["border" "border-base-300" "bg-base-200/30" "px-3" "py-2"
                       "whitespace-nowrap" "text-[0.8125rem]" "font-semibold" "text-trading-text"
                       "scroll-mb-12"
                       "disabled:cursor-not-allowed" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-save-scenario"
               :disabled saving-scenario?
               :on {:click [[:actions/open-portfolio-optimizer-scenario-save-modal]]}}
      ;; Save works at ANY point: it snapshots a NAMED scenario (results attach
      ;; only when a current solved run exists). The draft itself autosaves per
      ;; wallet on every edit — see the "Saved <time>" note in the setup header.
      (if saving-scenario? "Saving" "Save scenario")]
     (when (and solved-run? result-path)
       (result-nav-button {:result-path result-path
                           :tab :recommendation
                           :label "View results"
                           :data-role "portfolio-optimizer-view-results"}))
     (when (and solved-run? result-path)
       (result-nav-button {:result-path result-path
                           :label "Review trades"
                           :data-role "portfolio-optimizer-view-rebalance"
                           :accent? true
                           :actions [[:actions/navigate result-path]
                                     [:actions/open-portfolio-optimizer-execution]]}))
     [:div {:class ["flex" "max-w-full" "flex-col" "items-start" "gap-1.5" "font-mono"
                    "sm:ml-auto" "sm:min-w-[220px]" "sm:items-end" "sm:text-right"]}
      ;; Status tag stays a small uppercase-mono TAG (0.75rem), but in the
      ;; readable muted tier: when blocked it is the explanation of why Run is
      ;; gated, so it cannot whisper at ~2:1 contrast (the old #444951/#5a5f68
      ;; literals failed WCAG AA on the bar background).
      [:div {:class ["flex" "items-center" "gap-2" "text-[0.75rem]" "font-semibold"
                     "whitespace-nowrap" "uppercase" "tracking-[0.08em]"
                     (if ready? "text-trading-muted/80" "text-trading-muted")
                     "sm:justify-end"]
             :data-role "portfolio-optimizer-setup-bottom-actions-status-meta"
             :data-run-status (name (:tone status))}
       (when (= :ready (:tone status))
         [:span {:class ["h-2" "w-2" "rounded-full" "bg-success"]
                 :aria-hidden "true"}])
       (when (contains? #{:blocked :caution} (:tone status))
         [:span {:class ["h-2" "w-2" "rounded-full" "bg-warning"]
                 :aria-hidden "true"}])
       [:span (:label status)]
       [:span {:class ["text-trading-muted/50"]} "·"]
       [:span (str asset-count " assets")]]
      ;; The detail line names the exact objective/model being solved — the most
      ;; informative text in the bar, so it sits a tier brighter than the status
      ;; tag above it (portfolio-regressions pins that relationship).
      [:div {:class ["max-w-full" "text-[0.6875rem]" "font-semibold" "normal-case"
                     "tracking-normal" "text-trading-text/90" "sm:max-w-[260px]"]
             :data-role "portfolio-optimizer-setup-bottom-actions-status-detail"}
       (str "Solving " objective-copy " · " model-copy)]]]))
