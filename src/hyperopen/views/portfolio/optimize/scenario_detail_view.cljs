(ns hyperopen.views.portfolio.optimize.scenario-detail-view
  (:require [hyperopen.portfolio.optimizer.application.tear-sheet.model :as tear-sheet-model]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
             :as equal-risk-results]
            [hyperopen.portfolio.optimizer.application.view-model.inverse-volatility-results
             :as inverse-volatility-results]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.system :as app-system]
            [hyperopen.views.portfolio.optimize.execution-tab :as execution-tab]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.inputs-tab :as inputs-tab-view]
            [hyperopen.views.portfolio.optimize.optimization-progress-panel :as optimization-progress-panel]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.results-summary :as results-summary]
            [hyperopen.views.portfolio.optimize.scenario-kpi-strip :as kpi-strip]
            [hyperopen.views.portfolio.optimize.scenario-objective-menu :as objective-menu]
            [hyperopen.views.portfolio.optimize.target-sigma :as target-sigma]
            [hyperopen.views.portfolio.optimize.tear-sheet-card :as tear-sheet-card]
            [hyperopen.views.portfolio.optimize.tracking-panel :as tracking-panel]
            [nexus.registry :as nxr]))

(def ^:private tabs
  [{:key :recommendation :label "Recommendation" :data-role "portfolio-optimizer-scenario-tab-recommendation"}
   {:key :execution :label "Execution" :data-role "portfolio-optimizer-scenario-tab-execution"
    ;; Entering Execution rebuilds the plan snapshot from the latest rebalance, so route
    ;; the tab click through the open action rather than the bare set-tab. There is no
    ;; separate Rebalance preview tab — the rebalance is staged straight into Execution.
    :on [[:actions/open-portfolio-optimizer-execution]]}
   {:key :tracking :label "Tracking" :data-role "portfolio-optimizer-scenario-tab-tracking"}
   {:key :inputs :label "Inputs" :data-role "portfolio-optimizer-scenario-tab-inputs"}])

(defn- copy-scenario-link!
  [scenario-id]
  (fn [_event]
    (let [clipboard (some-> js/globalThis .-navigator .-clipboard)]
      (when (some-> clipboard .-writeText)
        (.writeText clipboard
                    (str (.-origin js/location)
                         (portfolio-routes/portfolio-optimize-scenario-path scenario-id)))))))

(defn- scenario-header
  [{:keys [scenario-id
           scenario-name
           active-scenario
           run-state
           running?
           scenario-save-state
           refinement
           rerun-blocked-reason] :as model}]
  (let [status (:status active-scenario)
        read-only? (true? (:read-only? active-scenario))
        running? (or running?
                     (= :running (:status run-state)))
        save-state (:status scenario-save-state)
        saving? (= :saving save-state)
        ;; Save is available whenever a save isn't already in flight — a stale
        ;; or missing result saves a setup-only snapshot (the workflow attaches
        ;; results only when they still match the draft).
        save-disabled? saving?
        can-refine? (boolean (:can-refine? refinement))]
    [:header {:class ["optimizer-scenario-header"
                      "border-b"
                      "border-base-300"
                      "bg-base-100/95"
                      "px-5"
                      "py-3"]
              :data-role "portfolio-optimizer-scenario-header"}
     [:div {:class ["flex" "flex-wrap" "items-end" "justify-between" "gap-4"]}
      [:div
       [:p {:class ["text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.24em]"
                    "text-trading-muted"]}
        "Scenario"]
       [:div {:class ["mt-1" "flex" "flex-wrap" "items-center" "gap-2"]}
        [:h1 {:class ["text-lg" "font-medium" "tracking-[-0.01em]"]}
         scenario-name]
        [:span {:class ["text-[0.8125rem]" "text-trading-muted"]}
         (str "/ scenario id " scenario-id
              (when read-only? " · read-only"))]
        [:span {:class ["optimizer-status-tag"
                        "rounded-full"
                        "border"
                        "border-base-300"
                        "bg-base-200/60"
                        "px-2"
                        "py-0.5"
                        "text-[0.58rem]"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.14em]"
                        "text-trading-muted"]
                :data-role "portfolio-optimizer-scenario-status-tag"}
         (opt-format/keyword-label status)]]]
      [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
       ;; Back to the workspace: the scenario load already hydrated the draft
       ;; with this scenario's config, so the setup page opens editing it and
       ;; Save updates the same record.
       (tear-sheet-card/header-link model)
       [:button {:type "button"
                 :class ["rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200/40"
                         "px-2.5"
                         "py-1"
                         "text-[0.65625rem]"
                         "font-semibold"
                         "text-trading-text"]
                 :data-role "portfolio-optimizer-scenario-edit-setup"
                 :on {:click [[:actions/navigate
                               (portfolio-routes/portfolio-optimize-path)]]}}
        "Edit setup"]
       [:button {:type "button"
                 :class ["optimizer-refine-action"
                         "rounded-lg"
                         "border"
                         "border-primary/60"
                         "bg-primary/20"
                         "px-2.5"
                         "py-1"
                         "text-[0.65625rem]"
                         "font-semibold"
                         "text-primary"
                         "transition-colors"
                         "hover:bg-primary/30"
                         "disabled:cursor-not-allowed"
                         "disabled:border-base-300"
                         "disabled:bg-base-200/40"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-scenario-refine"
                 :disabled (not can-refine?)
                 :on (when can-refine?
                       {:click [[:actions/refine-portfolio-optimizer]]})}
        "Refine optimization"]
       [:button {:type "button"
                 :class ["optimizer-primary-action"
                         "rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200/40"
                         "px-2.5"
                         "py-1"
                         "text-[0.65625rem]"
                         "font-semibold"
                         "text-trading-text"
                         "disabled:cursor-not-allowed"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-scenario-save"
                 :disabled save-disabled?
                 :on (when-not save-disabled?
                       {:click [[:actions/open-portfolio-optimizer-scenario-save-modal]]})}
        (if saving? "Saving" "Save scenario")]
       ;; Rerun demoted to neutral so a single action reads as primary.
       [:button {:type "button"
                 :class ["rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200/40"
                         "px-2.5"
                         "py-1"
                         "text-[0.65625rem]"
                         "font-semibold"
                         "text-trading-text"
                         "disabled:cursor-not-allowed"
                         "disabled:border-base-300"
                         "disabled:bg-base-200/40"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-scenario-rerun"
                 ;; An enabled button that silently does nothing is a truthfulness
                 ;; violation — when the draft cannot run, disable and say why.
                 :disabled (boolean (or running? rerun-blocked-reason))
                 :title rerun-blocked-reason
                 :on (when-not (or running? rerun-blocked-reason)
                       {:click [[:actions/run-portfolio-optimizer-from-draft]]})}
        (cond
          running? "Running"
          :else "Rerun")]]]]))
;; NOTE: the header deliberately does not duplicate the rebalance CTA — the
;; verdict-bar "Review N trades" button (results-summary/verdict-cta) is the
;; page's single forward action; a second amber button up here diluted it.

(defn- auto-recompute-stale-scenario!
  [_node]
  (when app-system/store
    (nxr/dispatch app-system/store
                  nil
                  [[:actions/auto-recompute-stale-portfolio-optimizer-scenario]])))

(defn- stale-banner
  [stale?]
  (when stale?
    [:section {:class ["rounded-xl"
                       "border"
                       "border-warning/50"
                       "bg-warning/10"
                       "p-3"
                       "text-sm"
                       "text-warning"]
               :data-role "portfolio-optimizer-scenario-stale-banner"
               :replicant/on-render auto-recompute-stale-scenario!}
     [:span {:class ["font-semibold"]} "Stale"]
     [:span {:class ["ml-2"]}
      "Draft inputs differ from the last successful run. Refreshing automatically while previous output stays visible."]]))

(defn- provenance-strip
  [{:keys [state draft result readiness scenario-id]}]
  (let [result* result
        constraints (:constraints draft)
        objective-key (objective-menu/current-objective-menu-key draft result*)
        objective-label (objective-menu/objective-label objective-key draft)]
    (let [field (fn [label value]
                  [:div {:class ["border-r" "border-base-300" "px-3" "py-2"]}
                   [:span {:class ["block" "font-mono" "text-[0.56rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
                    label]
                   [:span {:class ["mt-0.5" "block" "text-[0.7rem]" "font-medium" "text-trading-text"]}
                    value]])
          fields [[:div {:class ["optimizer-provenance-objective"
                                  "border-r"
                                  "border-base-300"
                                  "px-3"
                                  "py-2"]}
                   [:span {:class ["block" "font-mono" "text-[0.56rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
                    "Objective"]
                   [:div {:class ["optimizer-objective-anchor"
                                   "relative"
                                   "inline-block"]}
                    (objective-menu/objective-trigger
                     objective-label
                     (objective-menu/objective-menu-open? state))
                    (objective-menu/objective-menu state draft result* readiness)]]
                  (field "Returns"
                         ;; Covariance-only objectives never size positions
                         ;; from returns: say so where the model is named or
                         ;; users will assume the historical mean drove the
                         ;; weights.
                         (let [label (opt-format/display-label
                                      (or (:return-model result*)
                                          (get-in draft [:return-model :kind])))
                               covariance-only? contracts-constants/covariance-only-objective-kinds]
                           (if (or (covariance-only? (get-in draft [:objective :kind]))
                                   (covariance-only? (get-in result* [:solver :objective-kind])))
                             (str label " · analytics only")
                             label)))
                  (field "Risk"
                         (opt-format/display-label (or (:risk-model result*)
                                                       (get-in draft [:risk-model :kind]))))
                  ;; "Horizon: Annualized" was a hard-coded invariant carrying zero
                  ;; information — dropped to reduce provenance-strip noise.
                  (field "Funding"
                         (if (seq (:return-decomposition-by-instrument result*))
                           "Included"
                           "Pending run"))
                  (field "Constraints"
                         (str "gross ≤ " (opt-format/format-decimal (:gross-max constraints))
                              " · cap " (opt-format/format-pct (:max-asset-weight constraints))))
                  [:div {:class ["ml-auto" "flex" "items-center" "gap-2" "px-3" "py-2" "font-mono" "text-[0.62rem]" "text-trading-muted"]}
                   [:span "data as of " [:span {:class ["text-trading-muted"]} (opt-format/format-time (:as-of-ms result*))]]
                   [:span "·"]
                   [:a {:class ["text-trading-muted"]
                        :href (portfolio-routes/portfolio-optimize-scenario-path scenario-id)}
                    scenario-id]
                   [:button {:type "button"
                             :class ["border" "border-base-300" "bg-base-200/40" "px-2" "py-1" "font-mono"
                                     "text-[0.58rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted"]
                             :data-role "portfolio-optimizer-copy-scenario-link"
                             :on {:click (copy-scenario-link! scenario-id)}}
                    "Copy link"]]]]
      (into
       [:section {:class ["optimizer-provenance-strip"
                          "flex" "flex-wrap" "items-stretch" "border-y" "border-base-300" "bg-base-200/40"]
                  :data-role "portfolio-optimizer-provenance-strip"}]
       fields))))

(defn- scenario-tabs
  [_scenario-id selected-tab]
  (into
   [:nav {:class ["optimizer-scenario-tabs"
                  "flex" "h-8" "items-stretch" "border-b" "border-base-300" "bg-base-100/95" "pl-4"]
          :data-role "portfolio-optimizer-scenario-tabs"}]
   (map (fn [{:keys [key label data-role on]}]
          [:button {:type "button"
                    :class (cond-> ["flex" "items-center" "border-b" "px-4" "text-[0.7rem]" "font-medium" "transition-colors"]
                             (= key selected-tab) (conj "border-primary" "text-trading-text")
                             (not= key selected-tab) (conj "border-transparent" "text-trading-text/60" "hover:text-trading-text"))
                    :data-role data-role
                    :aria-current (when (= key selected-tab) "page")
                    :on {:click (or on [[:actions/set-portfolio-optimizer-results-tab key]])}}
           label])
        tabs)))

(defn- empty-tab
  [data-role title body]
  [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role data-role}
   [:p {:class ["text-[0.65rem]"
                "font-semibold"
                "uppercase"
                "tracking-[0.24em]"
                "text-trading-muted"]}
    title]
   [:p {:class ["mt-2" "text-sm" "text-trading-muted"]} body]])

(defn- solved-result?
  [model]
  (= :solved (:status (:result model))))

(defn- recompute-banner
  [optimization-progress]
  [:section {:class ["border-y"
                     "border-primary/40"
                     "bg-primary/10"
                     "px-4"
                     "py-3"
                     "text-sm"
                     "text-primary"]
             ;; Keyed with its siblings so mounting/unmounting this banner cannot
             ;; recreate the results grid (which holds open-<details> DOM state).
             :replicant/key "optimizer-recompute-banner"
             :data-role "portfolio-optimizer-recompute-banner"}
   [:p {:class ["font-semibold"]} "Recomputing recommendation"]
   [:p {:class ["mt-1" "text-trading-muted"]}
    "Keeping the previous allocation visible until the new run finishes."]
   (optimization-progress-panel/progress-panel optimization-progress {:show-header? false})])

(defn- recommendation-tab
  [{:keys [last-successful-run
           draft
           result
           stale?
           running?
           optimization-progress
           frontier-overlay-mode
           state
           readiness
           constrain-frontier?
           refinement] :as model}]
  (into
   [:section {:class ["space-y-0"]
              :data-role "portfolio-optimizer-recommendation-tab"}]
   (cond
     (solved-result? model)
     (let [deltas* (kpi-strip/recommendation-deltas result)
           ;; The covariance-only objectives get objective-specific verdict
           ;; copy (incl. Equal Risk's constraint-determined case) instead of
           ;; vol/return framing that implies a frontier choice was made.
           deltas (if-let [body (or (equal-risk-results/verdict-body result)
                                    (inverse-volatility-results/verdict-body result))]
                    (assoc deltas* :objective-body body)
                    deltas*)
           tear-sheet-card* (when (tear-sheet-model/vault-run? result)
                              (tear-sheet-card/card state model))]
       (cond-> []
         ;; Lead with the plain-language verdict + the primary Review-trades CTA,
         ;; before the analyst diagnostics — the page's job is review-and-act, so the
         ;; action sits above the fold instead of after the results grid.
         true (conj (results-summary/verdict-headline deltas))
         running? (conj (recompute-banner optimization-progress))
         ;; Runs that hold vaults get a hypothetical-performance card that
         ;; opens the tear sheet; other runs are unchanged. Conj'd only when
         ;; present: a nil hole among keyed siblings freezes the renderer.
         tear-sheet-card* (conj tear-sheet-card*)
         true (conj (results-panel/results-panel
                     last-successful-run
                     draft
                     {:state state
                      :readiness readiness
                      :stale? (and stale? (not running?))
                      :frontier-overlay-mode frontier-overlay-mode
                      :constrain-frontier? constrain-frontier?
                      :refinement refinement}))))

     :else
     [(empty-tab "portfolio-optimizer-recommendation-empty"
                 "Recommendation"
                 "Run or load this scenario to review target allocation, frontier, diagnostics, and rebalance context.")])))

(defn- tab-body
  [{:keys [state selected-tab] :as model}]
  (case selected-tab
    :execution [:section {:class ["space-y-4"]
                          :data-role "portfolio-optimizer-execution-tab-shell"}
                (execution-tab/execution-tab state)]
    :tracking [:section {:class ["space-y-4"]
                         :data-role "portfolio-optimizer-tracking-tab"}
               (tracking-panel/tracking-panel state)]
    :inputs (inputs-tab-view/inputs-tab state)
    (recommendation-tab model)))

(defn- scenario-loading-state
  [scenario-id]
  (empty-tab "portfolio-optimizer-scenario-loading-state"
             "Loading Scenario"
             (str "Scenario " scenario-id " is loading. Retained data from a previous scenario is hidden until the routed scenario is available.")))

(defn scenario-detail-view
  [state route]
  (let [{:keys [scenario-id
                loading?
                state
                selected-tab
                result
                stale?
                running?] :as model} (optimizer-view-model/scenario-detail-model state route)]
    [:section {:class ["portfolio-optimizer" "optimizer-scenario-surface"
                       "space-y-0" "leading-4" "text-trading-text"]
               :data-role "portfolio-optimizer-scenario-detail-surface"
               :data-scenario-id scenario-id}
     (scenario-header model)
     (provenance-strip model)
     (scenario-tabs scenario-id selected-tab)
     (target-sigma/target-sigma-strip model)
     (kpi-strip/kpi-strip result)
     (stale-banner (and stale? (not running?)))
     (if loading?
       (scenario-loading-state scenario-id)
       (tab-body model))]))
