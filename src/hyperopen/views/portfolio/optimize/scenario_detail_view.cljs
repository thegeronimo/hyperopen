(ns hyperopen.views.portfolio.optimize.scenario-detail-view
  (:require [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.query-state :as optimizer-query-state]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.execution-modal :as execution-modal]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.inputs-tab :as inputs-tab-view]
            [hyperopen.views.portfolio.optimize.rebalance-tab :as rebalance-tab-view]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.tracking-panel :as tracking-panel]))

(def ^:private tabs
  [{:key :recommendation :label "Recommendation" :data-role "portfolio-optimizer-scenario-tab-recommendation"}
   {:key :rebalance :label "Rebalance preview" :data-role "portfolio-optimizer-scenario-tab-rebalance"}
   {:key :tracking :label "Tracking" :data-role "portfolio-optimizer-scenario-tab-tracking"}
   {:key :inputs :label "Inputs" :data-role "portfolio-optimizer-scenario-tab-inputs"}])

(defn- active-tab
  [state]
  (optimizer-query-state/normalize-results-tab
   (get-in state [:portfolio-ui :optimizer :results-tab])))

(defn- loaded-scenario-matches-route?
  [state scenario-id]
  (= scenario-id
     (get-in state [:portfolio :optimizer :active-scenario :loaded-id])))

(defn- pending-route-load?
  [state scenario-id]
  (let [load-state (get-in state [:portfolio :optimizer :scenario-load-state])]
    (and (= scenario-id (:scenario-id load-state))
         (= :loading (:status load-state)))))

(defn- retained-unsaved-run?
  [state]
  (and (nil? (get-in state [:portfolio :optimizer :active-scenario :loaded-id]))
       (some? (get-in state [:portfolio :optimizer :last-successful-run]))))

(defn- retained-unsaved-route?
  [state scenario-id]
  (and (retained-unsaved-run? state)
       (contains? #{"draft"}
                  scenario-id)))

(defn- route-mismatched?
  [state scenario-id]
  (let [loaded-id (get-in state [:portfolio :optimizer :active-scenario :loaded-id])]
    (and (not (retained-unsaved-route? state scenario-id))
         (or (and (some? loaded-id)
                  (not= loaded-id scenario-id))
             (and (nil? loaded-id)
                  (or (pending-route-load? state scenario-id)
                      (retained-unsaved-run? state)))))))

(defn- scenario-scoped-state
  [state scenario-id]
  (if (route-mismatched? state scenario-id)
    (-> state
        (assoc-in [:portfolio :optimizer :draft] (optimizer-defaults/default-draft))
        (assoc-in [:portfolio :optimizer :last-successful-run] nil)
        (assoc-in [:portfolio :optimizer :tracking] (optimizer-defaults/default-tracking-state))
        (assoc-in [:portfolio :optimizer :active-scenario]
                  {:loaded-id nil
                   :status :loading
                   :read-only? true}))
    state))

(defn- scenario-name
  [state scenario-id]
  (or (when (loaded-scenario-matches-route? state scenario-id)
        (or (get-in state [:portfolio :optimizer :active-scenario :name])
            (get-in state [:portfolio :optimizer :draft :name])))
      (when (retained-unsaved-route? state scenario-id)
        (or (get-in state [:portfolio :optimizer :draft :name])
            "Unsaved Optimization"))
      (when (= scenario-id (get-in state [:portfolio :optimizer :draft :id]))
        (get-in state [:portfolio :optimizer :draft :name]))
      (str "Scenario " scenario-id)))

(defn- result
  [state]
  (get-in state [:portfolio :optimizer :last-successful-run :result]))

(defn- solved-result?
  [state]
  (= :solved (:status (result state))))

(defn- scenario-stale?
  [state]
  (and (some? (get-in state [:portfolio :optimizer :last-successful-run]))
       (true? (get-in state [:portfolio :optimizer :draft :metadata :dirty?]))))

(defn- tab-path
  [scenario-id tab-key]
  (str (portfolio-routes/portfolio-optimize-scenario-path scenario-id)
       "?otab="
       (name tab-key)))

(defn- copy-scenario-link!
  [scenario-id]
  (fn [_event]
    (let [clipboard (some-> js/globalThis .-navigator .-clipboard)]
      (when (some-> clipboard .-writeText)
        (.writeText clipboard
                    (str (.-origin js/location)
                         (portfolio-routes/portfolio-optimize-scenario-path scenario-id)))))))

(defn- scenario-header
  [state scenario-id]
  (let [status (get-in state [:portfolio :optimizer :active-scenario :status])
        read-only? (true? (get-in state [:portfolio :optimizer :active-scenario :read-only?]))
        running? (= :running (get-in state [:portfolio :optimizer :run-state :status]))
        save-state (get-in state [:portfolio :optimizer :scenario-save-state :status])
        saving? (= :saving save-state)]
    [:header {:class ["border-b"
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
         (scenario-name state scenario-id)]
        [:span {:class ["text-[0.8125rem]" "text-trading-muted"]}
         (str "/ scenario id " scenario-id
              (when read-only? " · read-only"))]
        [:span {:class ["rounded-full"
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
       [:button {:type "button"
                 :class ["border"
                         "border-base-300"
                         "bg-base-200/30"
                         "px-2"
                         "py-1"
                         "font-mono"
                         "text-[0.65625rem]"
                         "font-semibold"
                         "text-trading-muted"]
                 :aria-label "More scenario actions"}
        "..."]
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
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-scenario-save"
                 :disabled saving?
                 :on {:click [[:actions/save-portfolio-optimizer-scenario-from-current]]}}
        (if saving? "Saving" "Save scenario")]
       [:button {:type "button"
                 :class ["rounded-lg"
                         "border"
                         "border-primary/50"
                         "bg-primary/10"
                         "px-2.5"
                         "py-1"
                         "text-[0.65625rem]"
                         "font-semibold"
                         "text-primary"
                         "disabled:cursor-not-allowed"
                         "disabled:border-base-300"
                         "disabled:bg-base-200/40"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-scenario-rerun"
                 :disabled running?
                 :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
        (if running? "Running" "Rerun")]]]]))

(defn- kpi-card
  [data-role label value delta]
  [:div {:class ["border-r" "border-base-300" "px-3" "py-2.5" "last:border-r-0"]
         :data-role data-role}
   [:p {:class ["font-mono"
                "text-[0.6rem]"
                "uppercase"
                "tracking-[0.08em]"
                "text-trading-muted/70"]}
    label]
   [:p {:class ["mt-1" "font-mono" "text-sm" "font-semibold" "tabular-nums" "text-trading-text"]}
    value]
   [:p {:class ["mt-0.5" "font-mono" "text-[0.65rem]" "tabular-nums" "text-trading-green"]}
    delta]])

(defn- kpi-strip
  [state]
  (let [result* (result state)
        preview (:rebalance-preview result*)
        performance (:performance result*)
        diagnostics (:diagnostics result*)
        current-return (:current-expected-return result*)
        current-vol (:current-volatility result*)
        target-return (:expected-return result*)
        target-vol (:volatility result*)
        gross (:gross-exposure diagnostics)
        net (:net-exposure diagnostics)]
    [:section {:class ["grid" "grid-cols-2" "border-y" "border-base-300" "bg-base-100/95" "lg:grid-cols-5"]
               :data-role "portfolio-optimizer-scenario-kpi-strip"}
     (kpi-card "portfolio-optimizer-scenario-kpi-volatility"
               "Volatility · current → target"
               (if (opt-format/finite-number? current-vol)
                 [:span [:span {:class ["text-trading-muted"]} (opt-format/format-pct current-vol)]
                  " → "
                  (opt-format/format-pct target-vol)]
                 (opt-format/format-pct target-vol))
               (if (opt-format/finite-number? current-vol)
                 (str (opt-format/format-pct-delta (- (or target-vol 0) current-vol)) " · annualized")
                 "annualized"))
     (kpi-card "portfolio-optimizer-scenario-kpi-expected-return"
               "Expected Return · current → target"
               (if (opt-format/finite-number? current-return)
                 [:span [:span {:class ["text-trading-muted"]} (opt-format/format-pct current-return)]
                  " → "
                  (opt-format/format-pct target-return)]
                 (opt-format/format-pct target-return))
               (if (opt-format/finite-number? current-return)
                 (str (opt-format/format-pct-delta (- (or target-return 0) current-return)) " · annualized")
                 "annualized"))
     (kpi-card "portfolio-optimizer-scenario-kpi-sharpe"
               "Sharpe"
               (opt-format/format-decimal (or (:shrunk-sharpe performance)
                                               (:in-sample-sharpe performance)))
               "optimized run")
     (kpi-card "portfolio-optimizer-scenario-kpi-turnover"
               "Turnover Required"
               (opt-format/format-pct (:turnover diagnostics))
               (str "rebalance " (opt-format/keyword-label (:status preview))))
     (kpi-card "portfolio-optimizer-scenario-kpi-rebalance"
               "Gross / Net"
               (str (opt-format/format-pct gross) " / " (opt-format/format-pct net))
               "constraint utilization")]))

(defn- stale-banner
  [state]
  (when (scenario-stale? state)
    [:section {:class ["rounded-xl"
                       "border"
                       "border-warning/50"
                       "bg-warning/10"
                       "p-3"
                       "text-sm"
                       "text-warning"]
               :data-role "portfolio-optimizer-scenario-stale-banner"}
     [:span {:class ["font-semibold"]} "Stale"]
     [:span {:class ["ml-2"]}
      "Draft inputs changed after the last successful run. Rerun before using recommendation or rebalance output."]
     [:button {:type "button"
               :class ["ml-3"
                       "rounded-md"
                       "border"
                       "border-warning/50"
                       "px-2"
                       "py-1"
                       "text-xs"
                       "font-semibold"]
               :data-role "portfolio-optimizer-scenario-rerun-stale"
               :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
      "Rerun"]]))

(defn- provenance-strip
  [state scenario-id]
  (let [draft (or (get-in state [:portfolio :optimizer :draft])
                  (optimizer-defaults/default-draft))
        result* (result state)
        history-summary (:history-summary result*)
        constraints (:constraints draft)]
    (let [field (fn [label value]
                  [:div {:class ["border-r" "border-base-300" "px-3" "py-2"]}
                   [:span {:class ["block" "font-mono" "text-[0.56rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
                    label]
                   [:span {:class ["mt-0.5" "block" "text-[0.7rem]" "font-medium" "text-trading-text"]}
                    value]])
          fields [(field "Objective"
                         (opt-format/display-label (or (get-in draft [:objective :kind])
                                                       (get-in result* [:solver :objective-kind]))))
                  (field "Returns"
                         (opt-format/display-label (or (:return-model result*)
                                                       (get-in draft [:return-model :kind]))))
                  (field "Risk"
                         (opt-format/display-label (or (:risk-model result*)
                                                       (get-in draft [:risk-model :kind]))))
                  (field "Horizon" "Annualized")
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
       [:section {:class ["flex" "flex-wrap" "items-stretch" "border-y" "border-base-300" "bg-base-200/40"]
                  :data-role "portfolio-optimizer-provenance-strip"}]
       fields))))

(defn- scenario-tabs
  [scenario-id selected-tab]
  (into
   [:nav {:class ["flex" "h-8" "items-stretch" "border-b" "border-base-300" "bg-base-100/95" "pl-4"]
          :data-role "portfolio-optimizer-scenario-tabs"}]
   (map (fn [{:keys [key label data-role]}]
          [:a {:class (cond-> ["flex" "items-center" "border-b" "px-4" "text-[0.7rem]" "font-medium"]
                        (= key selected-tab) (conj "border-primary" "text-trading-text")
                        (not= key selected-tab) (conj "border-transparent" "text-trading-muted"))
               :href (tab-path scenario-id key)
               :data-role data-role
               :aria-current (when (= key selected-tab) "page")
               :on {:click [[:actions/set-portfolio-optimizer-results-tab key]]}}
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

(defn- recommendation-tab
  [state]
  [:section {:class ["space-y-0"]
             :data-role "portfolio-optimizer-recommendation-tab"}
   (if (solved-result? state)
     (results-panel/results-panel
      (get-in state [:portfolio :optimizer :last-successful-run])
      (get-in state [:portfolio :optimizer :draft])
      {:stale? (scenario-stale? state)
       :include-rebalance? false})
     (empty-tab "portfolio-optimizer-recommendation-empty"
                "Recommendation"
                "Run or load this scenario to review target allocation, frontier, diagnostics, and rebalance context."))])

(defn- rebalance-tab
  [state]
  [:section {:class ["space-y-4"]
             :data-role "portfolio-optimizer-rebalance-tab"}
   (if (solved-result? state)
     (rebalance-tab-view/rebalance-tab
      (get-in state [:portfolio :optimizer :last-successful-run]))
     (empty-tab "portfolio-optimizer-rebalance-empty"
                "Rebalance Preview"
                "A rebalance preview is available after a successful optimization run."))])

(defn- tab-body
  [state selected-tab]
  (case selected-tab
    :rebalance (rebalance-tab state)
    :tracking [:section {:class ["space-y-4"]
                         :data-role "portfolio-optimizer-tracking-tab"}
               (tracking-panel/tracking-panel state)]
    :inputs (inputs-tab-view/inputs-tab state)
    (recommendation-tab state)))

(defn- scenario-loading-state
  [scenario-id]
  (empty-tab "portfolio-optimizer-scenario-loading-state"
             "Loading Scenario"
             (str "Scenario " scenario-id " is loading. Retained data from a previous scenario is hidden until the routed scenario is available.")))

(defn scenario-detail-view
  [state route]
  (let [scenario-id (:scenario-id route)
        loading? (route-mismatched? state scenario-id)
        state* (scenario-scoped-state state scenario-id)
        selected-tab (active-tab state)]
    [:section {:class ["portfolio-optimizer-v4" "space-y-0" "text-trading-text"]
               :data-role "portfolio-optimizer-scenario-detail-surface"
               :data-scenario-id scenario-id}
     (scenario-header state* scenario-id)
     (provenance-strip state* scenario-id)
     (scenario-tabs scenario-id selected-tab)
     (kpi-strip state*)
     (stale-banner state*)
     (if loading?
       (scenario-loading-state scenario-id)
       (tab-body state* selected-tab))
     (execution-modal/execution-modal state*)]))
