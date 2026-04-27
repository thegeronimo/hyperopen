(ns hyperopen.views.portfolio.optimize.scenario-detail-view
  (:require [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.query-state :as optimizer-query-state]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.execution-modal :as execution-modal]
            [hyperopen.views.portfolio.optimize.inputs-tab :as inputs-tab-view]
            [hyperopen.views.portfolio.optimize.rebalance-tab :as rebalance-tab-view]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.tracking-panel :as tracking-panel]))

(def ^:private tabs
  [{:key :recommendation
    :label "Recommendation"
    :data-role "portfolio-optimizer-scenario-tab-recommendation"}
   {:key :rebalance
    :label "Rebalance preview"
    :data-role "portfolio-optimizer-scenario-tab-rebalance"}
   {:key :tracking
    :label "Tracking"
    :data-role "portfolio-optimizer-scenario-tab-tracking"}
   {:key :inputs
    :label "Inputs"
    :data-role "portfolio-optimizer-scenario-tab-inputs"}])

(defn- keyword-label
  [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)
    :else "N/A"))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- format-pct
  [value]
  (if (finite-number? value)
    (str (.toLocaleString (* 100 value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         "%")
    "N/A"))

(defn- format-decimal
  [value]
  (if (finite-number? value)
    (.toLocaleString value "en-US" #js {:maximumFractionDigits 3})
    "N/A"))

(defn- format-usdc
  [value]
  (if (finite-number? value)
    (str "$" (.toLocaleString value
                              "en-US"
                              #js {:maximumFractionDigits 0}))
    "N/A"))

(defn- format-time
  [ms]
  (if (number? ms)
    (.toLocaleString (js/Date. ms)
                     "en-US"
                     #js {:month "short"
                          :day "numeric"
                          :hour "2-digit"
                          :minute "2-digit"})
    "N/A"))

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

(defn- route-mismatched?
  [state scenario-id]
  (let [loaded-id (get-in state [:portfolio :optimizer :active-scenario :loaded-id])]
    (or (and (some? loaded-id)
             (not= loaded-id scenario-id))
        (and (nil? loaded-id)
             (or (pending-route-load? state scenario-id)
                 (retained-unsaved-run? state))))))

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

(defn- scenario-header
  [state scenario-id]
  (let [status (get-in state [:portfolio :optimizer :active-scenario :status])
        read-only? (true? (get-in state [:portfolio :optimizer :active-scenario :read-only?]))
        running? (= :running (get-in state [:portfolio :optimizer :run-state :status]))
        save-state (get-in state [:portfolio :optimizer :scenario-save-state :status])
        saving? (= :saving save-state)]
    [:header {:class ["rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100/95"
                      "p-4"]
              :data-role "portfolio-optimizer-scenario-header"}
     [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-4"]}
      [:div
       [:p {:class ["text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.24em]"
                    "text-trading-muted"]}
        "Scenario"]
       [:div {:class ["mt-2" "flex" "flex-wrap" "items-center" "gap-3"]}
        [:h1 {:class ["text-2xl" "font-semibold" "tracking-tight"]}
         (scenario-name state scenario-id)]
        [:span {:class ["rounded-full"
                        "border"
                        "border-base-300"
                        "bg-base-200/60"
                        "px-2.5"
                        "py-1"
                        "text-[0.65rem]"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.14em]"
                        "text-trading-muted"]
                :data-role "portfolio-optimizer-scenario-status-tag"}
         (keyword-label status)]]
       [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
        (str "Scenario id " scenario-id
             (when read-only? " · read-only"))]]
      [:div {:class ["flex" "flex-wrap" "gap-2"]}
       [:button {:type "button"
                 :class ["rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200/40"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "font-semibold"
                         "text-trading-text"
                         "disabled:cursor-not-allowed"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-scenario-save"
                 :disabled saving?
                 :on {:click [[:actions/save-portfolio-optimizer-scenario-from-current]]}}
        (if saving? "Saving" "Save Scenario")]
       [:button {:type "button"
                 :class ["rounded-lg"
                         "border"
                         "border-primary/50"
                         "bg-primary/10"
                         "px-3"
                         "py-2"
                         "text-sm"
                         "font-semibold"
                         "text-primary"
                         "disabled:cursor-not-allowed"
                         "disabled:border-base-300"
                         "disabled:bg-base-200/40"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-scenario-rerun"
                 :disabled running?
                 :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
        (if running? "Running" "Rerun Scenario")]]]]))

(defn- kpi-card
  [data-role label value]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100/95"
                 "p-4"]
         :data-role data-role}
   [:p {:class ["text-[0.65rem]"
                "font-semibold"
                "uppercase"
                "tracking-[0.2em]"
                "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-xl" "font-semibold" "tabular-nums"]}
    value]])

(defn- kpi-strip
  [state]
  (let [result* (result state)
        preview (:rebalance-preview result*)
        performance (:performance result*)]
    [:section {:class ["grid"
                       "grid-cols-2"
                       "gap-3"
                       "lg:grid-cols-5"]
               :data-role "portfolio-optimizer-scenario-kpi-strip"}
     (kpi-card "portfolio-optimizer-scenario-kpi-expected-return"
               "Expected Return"
               (format-pct (:expected-return result*)))
     (kpi-card "portfolio-optimizer-scenario-kpi-volatility"
               "Volatility"
               (format-pct (:volatility result*)))
     (kpi-card "portfolio-optimizer-scenario-kpi-sharpe"
               "Sharpe"
               (format-decimal (or (:shrunk-sharpe performance)
                                   (:in-sample-sharpe performance))))
     (kpi-card "portfolio-optimizer-scenario-kpi-turnover"
               "Turnover"
               (format-pct (get-in result* [:diagnostics :turnover])))
     (kpi-card "portfolio-optimizer-scenario-kpi-rebalance"
               "Rebalance"
               (keyword-label (:status preview)))]))

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
    [:section {:class ["rounded-xl"
                       "border"
                       "border-base-300"
                       "bg-base-100/95"
                       "p-4"]
               :data-role "portfolio-optimizer-provenance-strip"}
     [:p {:class ["text-[0.65rem]"
                  "font-semibold"
                  "uppercase"
                  "tracking-[0.24em]"
                  "text-trading-muted"]}
      "Provenance"]
     [:div {:class ["mt-3"
                    "grid"
                    "grid-cols-2"
                    "gap-2"
                    "text-xs"
                    "md:grid-cols-4"
                    "xl:grid-cols-6"]}
      [:div [:span {:class ["text-trading-muted"]} "Objective"] [:p (keyword-label (or (get-in draft [:objective :kind])
                                                                                       (get-in result* [:solver :objective-kind])))]]
      [:div [:span {:class ["text-trading-muted"]} "Returns"] [:p (keyword-label (or (:return-model result*)
                                                                                     (get-in draft [:return-model :kind])))]]
      [:div [:span {:class ["text-trading-muted"]} "Risk"] [:p (keyword-label (or (:risk-model result*)
                                                                                  (get-in draft [:risk-model :kind])))]]
      [:div [:span {:class ["text-trading-muted"]} "Funding"] [:p (if (seq (:return-decomposition-by-instrument result*))
                                                                    "Included"
                                                                    "Pending run")]]
      [:div [:span {:class ["text-trading-muted"]} "History"] [:p (if-let [observations (:return-observations history-summary)]
                                                                    (str observations " returns")
                                                                    "N/A")]]
      [:div [:span {:class ["text-trading-muted"]} "As Of"] [:p (format-time (:as-of-ms result*))]]
      [:div [:span {:class ["text-trading-muted"]} "Max Weight"] [:p (format-pct (:max-asset-weight constraints))]]
      [:div [:span {:class ["text-trading-muted"]} "Gross"] [:p (format-decimal (:gross-max constraints))]]
      [:div [:span {:class ["text-trading-muted"]} "Status"] [:p (keyword-label (get-in state [:portfolio :optimizer :active-scenario :status]))]]
      [:div [:span {:class ["text-trading-muted"]} "Data"] [:p (if result*
                                                                 "Last successful run"
                                                                 "Awaiting run")]]
      [:div [:span {:class ["text-trading-muted"]} "Capital"] [:p (format-usdc (get-in result* [:rebalance-preview :capital-usd]))]]
      [:div [:span {:class ["text-trading-muted"]} "Link"] [:a {:class ["text-primary"]
                                                                 :href (portfolio-routes/portfolio-optimize-scenario-path scenario-id)}
                                                            scenario-id]]]]))

(defn- scenario-tabs
  [scenario-id selected-tab]
  (into
   [:nav {:class ["flex"
                  "flex-wrap"
                  "gap-2"
                  "rounded-xl"
                  "border"
                  "border-base-300"
                  "bg-base-100/95"
                  "p-2"]
          :data-role "portfolio-optimizer-scenario-tabs"}]
   (map (fn [{:keys [key label data-role]}]
          [:a {:class (cond-> ["rounded-lg"
                               "border"
                               "px-3"
                               "py-2"
                               "text-sm"
                               "font-semibold"]
                        (= key selected-tab) (conj "border-primary/60" "bg-primary/10" "text-primary")
                        (not= key selected-tab) (conj "border-base-300" "bg-base-200/40" "text-trading-muted"))
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
  [:section {:class ["space-y-4"]
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
    [:section {:class ["portfolio-optimizer-v4" "space-y-4" "text-trading-text"]
               :data-role "portfolio-optimizer-scenario-detail-surface"
               :data-scenario-id scenario-id}
     (scenario-header state* scenario-id)
     (kpi-strip state*)
     (stale-banner state*)
     (provenance-strip state* scenario-id)
     (scenario-tabs scenario-id selected-tab)
     (if loading?
       (scenario-loading-state scenario-id)
       (tab-body state* selected-tab))
     (execution-modal/execution-modal state*)]))
