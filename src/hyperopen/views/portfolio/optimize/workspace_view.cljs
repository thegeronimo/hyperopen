(ns hyperopen.views.portfolio.optimize.workspace-view
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.black-litterman-views-panel :as black-litterman-views-panel]
            [hyperopen.views.portfolio.optimize.execution-modal :as execution-modal]
            [hyperopen.views.portfolio.optimize.infeasible-panel :as infeasible-panel]
            [hyperopen.views.portfolio.optimize.instrument-overrides-panel :as instrument-overrides-panel]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]
            [hyperopen.views.portfolio.optimize.run-status-panel :as run-status-panel]
            [hyperopen.views.portfolio.optimize.setup-readiness-panel :as setup-readiness-panel]
            [hyperopen.views.portfolio.optimize.tracking-panel :as tracking-panel]
            [hyperopen.views.portfolio.optimize.universe-panel :as universe-panel]))

(defn- metric-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
    value]])

(defn- format-usdc
  [value]
  (if (number? value)
    (str "$" (.toLocaleString value "en-US" #js {:maximumFractionDigits 2}))
    "N/A"))

(defn- route-title
  [route]
  (case (:kind route)
    :optimize-new "New Scenario"
    :optimize-scenario (str "Scenario " (:scenario-id route))
    "Optimizer Workspace"))

(defn- optimizer-draft
  [state]
  (or (get-in state [:portfolio :optimizer :draft])
      (optimizer-defaults/default-draft)))

(defn- comparable-request
  [request]
  (some-> request
          (dissoc :as-of-ms)
          (update-in [:history :freshness] dissoc :as-of-ms :age-ms)
          (update :execution-assumptions dissoc :cost-contexts-by-id)))

(defn- stale-result?
  [last-successful-run readiness]
  (let [previous-request (get-in last-successful-run [:request-signature :request])
        current-request (:request readiness)]
    (and (some? previous-request)
         (some? current-request)
         (not= (comparable-request previous-request)
               (comparable-request current-request)))))

(defn- panel-shell
  [data-role title subtitle & children]
  [:section {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100/95"
                     "p-4"]
             :data-role data-role}
   [:p {:class ["text-[0.65rem]"
                "font-semibold"
                "uppercase"
                "tracking-[0.24em]"
                "text-trading-muted"]}
    title]
   [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
    subtitle]
   (into [:div {:class ["mt-4" "grid" "grid-cols-1" "gap-2" "md:grid-cols-2"]}]
         children)])

(defn- option-chip
  [label selected? data-role action]
  [:button {:type "button"
            :class (cond-> ["rounded-lg"
                            "border"
                            "px-3"
                            "py-2"
                            "text-left"
                            "text-sm"]
                     selected? (conj "border-primary/60" "bg-primary/10")
                     (not selected?) (conj "border-base-300" "bg-base-200/40"))
            :aria-pressed (str selected?)
            :data-role data-role
            :on {:click [action]}}
   [:span {:class ["font-medium"]} label]
   (when selected?
     [:span {:class ["ml-2" "text-xs" "uppercase" "tracking-[0.16em]" "text-primary"]}
      "Active"])])

(defn- constraint-row
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-sm" "font-semibold" "tabular-nums"]}
    value]])

(def ^:private setup-field-label-class
  ["block" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"])

(def ^:private setup-number-input-class
  ["mt-2" "w-full" "rounded-md" "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
   "text-sm" "font-semibold" "tabular-nums" "outline-none" "focus:border-primary/70"])

(defn- constraint-input
  ([label constraint-key value data-role]
   (constraint-input label constraint-key value data-role false))
  ([label constraint-key value data-role highlighted?]
  [:label {:class (cond-> ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
                    highlighted? (conj "border-warning/60" "bg-warning/10"))}
   [:span {:class setup-field-label-class} label]
   [:input {:type "text"
            :inputmode "decimal"
            :class setup-number-input-class
            :data-role data-role
            :data-infeasible (when highlighted? "true")
            :aria-invalid (when highlighted? "true")
            :value (str value)
            :on {:input [[:actions/set-portfolio-optimizer-constraint
                          constraint-key
                          [:event.target/value]]]}}]]))

(defn- number-input
  ([label value data-role action]
   (number-input label value data-role action false))
  ([label value data-role action highlighted?]
  [:label {:class (cond-> ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
                    highlighted? (conj "border-warning/60" "bg-warning/10"))}
   [:span {:class setup-field-label-class} label]
   [:input {:type "text"
            :inputmode "decimal"
            :class setup-number-input-class
            :data-role data-role
            :data-infeasible (when highlighted? "true")
            :aria-invalid (when highlighted? "true")
            :value (str value)
            :on {:input [action]}}]]))

(defn- constraint-toggle
  [label constraint-key checked? data-role]
  [:label {:class ["flex"
                   "items-center"
                   "justify-between"
                   "gap-3"
                   "rounded-lg"
                   "border"
                   "border-base-300"
                   "bg-base-200/40"
                   "p-3"]}
   [:span {:class ["text-[0.65rem]"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.18em]"
                   "text-trading-muted"]}
    label]
   [:input {:type "checkbox"
            :class ["h-4" "w-4" "accent-primary"]
            :data-role data-role
            :checked (true? checked?)
            :on {:change [[:actions/set-portfolio-optimizer-constraint
                           constraint-key
                           :event.target/checked]]}}]])

(defn- setup-panels
  [state draft readiness highlighted-controls]
  (let [objective-kind (get-in draft [:objective :kind])
        return-kind (get-in draft [:return-model :kind])
        risk-kind (get-in draft [:risk-model :kind])
        constraints (:constraints draft)
        execution-assumptions (:execution-assumptions draft)]
    [:div {:class ["grid" "grid-cols-1" "gap-4"]
           :data-role "portfolio-optimizer-setup-surface"}
     (universe-panel/universe-panel state draft)
     (panel-shell
      "portfolio-optimizer-objective-panel"
      "Objective"
      "Choose the optimizer objective separately from the return model."
      (option-chip "Minimum Variance" (= :minimum-variance objective-kind)
                   "portfolio-optimizer-objective-minimum-variance"
                   [:actions/set-portfolio-optimizer-objective-kind :minimum-variance])
      (option-chip "Max Sharpe" (= :max-sharpe objective-kind)
                   "portfolio-optimizer-objective-max-sharpe"
                   [:actions/set-portfolio-optimizer-objective-kind :max-sharpe])
      (option-chip "Target Volatility" (= :target-volatility objective-kind)
                   "portfolio-optimizer-objective-target-volatility"
                   [:actions/set-portfolio-optimizer-objective-kind :target-volatility])
      (option-chip "Target Return" (= :target-return objective-kind)
                   "portfolio-optimizer-objective-target-return"
                   [:actions/set-portfolio-optimizer-objective-kind :target-return])
      (number-input "Target Return"
                    (or (get-in draft [:objective :target-return]) 0.15)
                    "portfolio-optimizer-objective-target-return-input"
                    [:actions/set-portfolio-optimizer-objective-parameter
                     :target-return
                     [:event.target/value]]
                    (contains? highlighted-controls :target-return))
      (number-input "Target Volatility"
                    (or (get-in draft [:objective :target-volatility]) 0.2)
                    "portfolio-optimizer-objective-target-volatility-input"
                    [:actions/set-portfolio-optimizer-objective-parameter
                     :target-volatility
                     [:event.target/value]]))
     (panel-shell
      "portfolio-optimizer-return-model-panel"
      "Return Model"
      "Black-Litterman stays here as a return-model mode, not an objective."
      (option-chip "Historical Mean" (= :historical-mean return-kind)
                   "portfolio-optimizer-return-model-historical-mean"
                   [:actions/set-portfolio-optimizer-return-model-kind :historical-mean])
      (option-chip "EW Mean" (= :ew-mean return-kind)
                   "portfolio-optimizer-return-model-ew-mean"
                   [:actions/set-portfolio-optimizer-return-model-kind :ew-mean])
      (option-chip "Black-Litterman" (= :black-litterman return-kind)
                   "portfolio-optimizer-return-model-black-litterman"
                   [:actions/set-portfolio-optimizer-return-model-kind :black-litterman]))
     (black-litterman-views-panel/black-litterman-views-panel
      draft
      (get-in readiness [:request :black-litterman-prior]))
     (panel-shell
      "portfolio-optimizer-risk-model-panel"
      "Risk Model"
      "Covariance estimation is configured independently from expected returns."
      (option-chip "Diagonal Shrink" (= :diagonal-shrink risk-kind)
                   "portfolio-optimizer-risk-model-diagonal-shrink"
                   [:actions/set-portfolio-optimizer-risk-model-kind :diagonal-shrink])
      (option-chip "Sample Covariance" (= :sample-covariance risk-kind)
                   "portfolio-optimizer-risk-model-sample-covariance"
                   [:actions/set-portfolio-optimizer-risk-model-kind :sample-covariance]))
     [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                :data-role "portfolio-optimizer-constraints-panel"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
       "Constraints"]
      [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
       "Mandatory V1 controls stay visible, including global max asset weight and turnover."]
      [:div {:class ["mt-4" "grid" "grid-cols-1" "gap-2" "md:grid-cols-2" "xl:grid-cols-3"]}
       (constraint-toggle "Long Only"
                          :long-only?
                          (:long-only? constraints)
                          "portfolio-optimizer-constraint-long-only-input")
       (constraint-input "Max Asset Weight"
                         :max-asset-weight
                         (:max-asset-weight constraints)
                         "portfolio-optimizer-constraint-max-asset-weight-input"
                         (contains? highlighted-controls :max-asset-weight))
       (constraint-input "Gross Leverage"
                         :gross-max
                         (:gross-max constraints)
                         "portfolio-optimizer-constraint-gross-max-input")
       (constraint-input "Net Min (optional floor)"
                         :net-min
                         (:net-min constraints)
                         "portfolio-optimizer-constraint-net-min-input")
       (constraint-input "Net Max"
                         :net-max
                         (:net-max constraints)
                         "portfolio-optimizer-constraint-net-max-input")
       (constraint-input "Dust Threshold"
                         :dust-usdc
                         (:dust-usdc constraints)
                         "portfolio-optimizer-constraint-dust-usdc-input")
       (constraint-input "Max Turnover"
                         :max-turnover
                         (:max-turnover constraints)
                         "portfolio-optimizer-constraint-max-turnover-input")
       (constraint-input "Rebalance Tolerance"
                         :rebalance-tolerance
                         (:rebalance-tolerance constraints)
                         "portfolio-optimizer-constraint-rebalance-tolerance-input")
       (constraint-row "Allowlist / Blocklist"
                       (str (count (:allowlist constraints)) " / " (count (:blocklist constraints))))
       (constraint-row "Perp Leverage"
                       (str (count (:perp-leverage constraints)) " overrides"))]]
     (instrument-overrides-panel/instrument-overrides-panel draft)
     (panel-shell
      "portfolio-optimizer-execution-assumptions-panel"
      "Execution Assumptions"
      "Preview costs use live market context where available, with explicit fallbacks and optional manual capital sizing."
      (number-input "Fallback Slippage"
                    (or (:fallback-slippage-bps execution-assumptions)
                        (:slippage-fallback-bps execution-assumptions)
                        25)
                    "portfolio-optimizer-execution-fallback-slippage-bps-input"
                    [:actions/set-portfolio-optimizer-execution-assumption
                     :fallback-slippage-bps
                     [:event.target/value]])
      (number-input "Manual Capital Base"
                    (:manual-capital-usdc execution-assumptions)
                    "portfolio-optimizer-execution-manual-capital-usdc-input"
                    [:actions/set-portfolio-optimizer-execution-assumption
                     :manual-capital-usdc
                     [:event.target/value]])
      (option-chip "Default Order: Market"
                   (= :market (or (:default-order-type execution-assumptions) :market))
                   "portfolio-optimizer-execution-default-order-type-input"
                   [:actions/set-portfolio-optimizer-execution-assumption
                    :default-order-type
                    :market])
      (option-chip "Fee Mode: Taker"
                   (= :taker (or (:fee-mode execution-assumptions) :taker))
                   "portfolio-optimizer-execution-fee-mode-input"
                   [:actions/set-portfolio-optimizer-execution-assumption
                    :fee-mode
                    :taker]))]))

(defn workspace-view
  [state route]
  (let [snapshot (current-portfolio/current-portfolio-snapshot state)
        draft (optimizer-draft state)
        readiness (setup-readiness/build-readiness state)
        preview-snapshot (or (get-in readiness [:request :current-portfolio])
                             snapshot)
        run-state (or (get-in state [:portfolio :optimizer :run-state])
                      (optimizer-defaults/default-run-state))
        running? (= :running (:status run-state))
        runnable? (and (:runnable? readiness)
                       (not running?))
        last-successful-run (get-in state [:portfolio :optimizer :last-successful-run])
        solved-run? (= :solved (get-in last-successful-run [:result :status]))
        scenario-save-state (or (get-in state [:portfolio :optimizer :scenario-save-state])
                                (optimizer-defaults/default-scenario-save-state))
        saving-scenario? (= :saving (:status scenario-save-state))
        history-load-state (or (get-in state [:portfolio :optimizer :history-load-state])
                               (optimizer-defaults/default-history-load-state))
        scenario-id (:scenario-id route)
        infeasible-result (infeasible-panel/infeasible-result run-state)
        highlighted-controls (infeasible-panel/highlighted-control-keys infeasible-result)]
    [:section {:class ["grid"
                       "grid-cols-1"
                       "gap-4"
                       "text-trading-text"
                       "xl:grid-cols-[260px_minmax(0,1fr)_280px]"]
               :data-role "portfolio-optimizer-workspace"
               :data-scenario-id scenario-id}
     [:aside {:class ["rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100/95"
                      "p-4"]
              :data-role "portfolio-optimizer-left-rail"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
       "Scenario"]
      [:h1 {:class ["mt-2" "text-xl" "font-semibold" "tracking-tight"]}
       (route-title route)]
      (run-status-panel/draft-state-badge draft)
      [:nav {:class ["mt-4" "space-y-2" "text-sm"]}
       [:a {:class ["block" "rounded-md" "bg-base-200/70" "px-3" "py-2"]
            :href (portfolio-routes/portfolio-optimize-new-path)
            :on {:click [[:actions/navigate
                          (portfolio-routes/portfolio-optimize-new-path)]]}}
        "Setup"]
       [:a {:class ["block" "rounded-md" "px-3" "py-2" "text-trading-muted"]
            :href (or (when scenario-id
                        (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                      (portfolio-routes/portfolio-optimize-index-path))
            :on {:click [[:actions/navigate
                          (or (when scenario-id
                                (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                              (portfolio-routes/portfolio-optimize-index-path))]]}}
        "Results"]
       [:a {:class ["block" "rounded-md" "px-3" "py-2" "text-trading-muted"]
            :href (or (when scenario-id
                        (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                      (portfolio-routes/portfolio-optimize-index-path))
            :on {:click [[:actions/navigate
                          (or (when scenario-id
                                (portfolio-routes/portfolio-optimize-scenario-path scenario-id))
                              (portfolio-routes/portfolio-optimize-index-path))]]}}
        "Diagnostics"]]
      [:div {:class ["mt-5" "border-t" "border-base-300" "pt-4"]}
       [:p {:class ["text-[0.65rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.18em]"
                    "text-trading-muted"]}
        "Actions"]
       [:button {:type "button"
                 :class ["mt-3"
                         "w-full"
                         "rounded-lg"
                         "border"
                         "border-primary/50"
                         "bg-primary/10"
                         "px-3"
                         "py-2"
                         "text-left"
                         "text-sm"
                         "font-semibold"
                         "text-primary"
                         "disabled:cursor-not-allowed"
                         "disabled:border-base-300"
                         "disabled:bg-base-200/40"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-run-draft"
                 :disabled (not runnable?)
                 :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
        (if running?
          "Running Optimization"
          "Run Optimization")]
       [:button {:type "button"
                 :class ["mt-2"
                         "w-full"
                         "rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200/40"
                         "px-3"
                         "py-2"
                         "text-left"
                         "text-sm"
                         "font-semibold"
                         "text-trading-text"
                         "disabled:cursor-not-allowed"
                         "disabled:text-trading-muted"]
                 :data-role "portfolio-optimizer-save-scenario"
                 :disabled (or (not solved-run?) saving-scenario?)
                 :on {:click [[:actions/save-portfolio-optimizer-scenario-from-current]]}}
        (if saving-scenario?
          "Saving Scenario"
          "Save Scenario")]]]
     [:main {:class ["space-y-4"]}
      (infeasible-panel/infeasible-banner infeasible-result highlighted-controls)
      (setup-panels state draft readiness highlighted-controls)
      (results-panel/results-panel last-successful-run
                                   draft
                                   {:stale? (stale-result? last-successful-run
                                                           readiness)})
      (tracking-panel/tracking-panel state)
     [:div {:class ["grid" "grid-cols-1" "gap-3" "lg:grid-cols-3"]
             :data-role "portfolio-optimizer-current-summary"}
       (metric-card "NAV" (format-usdc (get-in preview-snapshot [:capital :nav-usdc])))
       (metric-card "Gross Exposure" (format-usdc (get-in preview-snapshot [:capital :gross-exposure-usdc])))
       (metric-card "Net Exposure" (format-usdc (get-in preview-snapshot [:capital :net-exposure-usdc])))]
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role "portfolio-optimizer-signed-exposure-table"}
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
        "Current Signed Exposure"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        (str (count (:exposures preview-snapshot)) " current exposure rows available for optimizer request assembly.")]]]
     [:aside {:class ["rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100/95"
                      "p-4"]
              :data-role "portfolio-optimizer-right-rail"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
       "Trust & Freshness"]
      [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
       (cond
         (not (:snapshot-loaded? snapshot))
         (if (= :manual (get-in preview-snapshot [:capital :source]))
           "Manual capital base is being used for preview sizing. Execution still depends on connected account readiness."
           "Current portfolio snapshot is not loaded yet.")

         (not (:capital-ready? preview-snapshot))
         "Current portfolio snapshot is available, but no positive capital base is available for execution preview."

         (not (:execution-ready? preview-snapshot))
         "Current portfolio snapshot is available in read-only mode. Optimization can run, but execution is blocked."

         :else
         "Current portfolio snapshot is available.")]
      (setup-readiness-panel/readiness-panel readiness history-load-state)
      (run-status-panel/run-status-panel run-state)
      (run-status-panel/last-successful-run-panel run-state last-successful-run)
      (when-let [message (get-in preview-snapshot [:account :read-only-message])]
        [:p {:class ["mt-3" "rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]}
         message])]
     (execution-modal/execution-modal state)]))
