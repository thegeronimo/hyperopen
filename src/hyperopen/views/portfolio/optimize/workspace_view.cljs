(ns hyperopen.views.portfolio.optimize.workspace-view
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.routes :as portfolio-routes]))

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

(defn- constraint-input
  [label constraint-key value data-role]
  [:label {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
   [:span {:class ["block"
                   "text-[0.65rem]"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.18em]"
                   "text-trading-muted"]}
    label]
   [:input {:type "text"
            :inputmode "decimal"
            :class ["mt-2"
                    "w-full"
                    "rounded-md"
                    "border"
                    "border-base-300"
                    "bg-base-100"
                    "px-2"
                    "py-1.5"
                    "text-sm"
                    "font-semibold"
                    "tabular-nums"
                    "outline-none"
                    "focus:border-primary/70"]
            :data-role data-role
            :value (str value)
            :on {:input [[:actions/set-portfolio-optimizer-constraint
                          constraint-key
                          [:event.target/value]]]}}]])

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
  [draft]
  (let [objective-kind (get-in draft [:objective :kind])
        return-kind (get-in draft [:return-model :kind])
        risk-kind (get-in draft [:risk-model :kind])
        constraints (:constraints draft)]
    [:div {:class ["grid" "grid-cols-1" "gap-4"]
           :data-role "portfolio-optimizer-setup-surface"}
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
                   [:actions/set-portfolio-optimizer-objective-kind :target-return]))
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
     (panel-shell
      "portfolio-optimizer-risk-model-panel"
      "Risk Model"
      "Covariance estimation is configured independently from expected returns."
      (option-chip "Ledoit-Wolf" (= :ledoit-wolf risk-kind)
                   "portfolio-optimizer-risk-model-ledoit-wolf"
                   [:actions/set-portfolio-optimizer-risk-model-kind :ledoit-wolf])
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
                         "portfolio-optimizer-constraint-max-asset-weight-input")
       (constraint-input "Gross Leverage"
                         :gross-max
                         (:gross-max constraints)
                         "portfolio-optimizer-constraint-gross-max-input")
       (constraint-input "Net Min"
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
                       (str (count (:perp-leverage constraints)) " overrides"))]]]))

(defn workspace-view
  [state route]
  (let [snapshot (current-portfolio/current-portfolio-snapshot state)
        draft (optimizer-draft state)
        scenario-id (:scenario-id route)]
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
        "Diagnostics"]]]
     [:main {:class ["space-y-4"]}
      (setup-panels draft)
      [:div {:class ["grid" "grid-cols-1" "gap-3" "lg:grid-cols-3"]
             :data-role "portfolio-optimizer-current-summary"}
       (metric-card "NAV" (format-usdc (get-in snapshot [:capital :nav-usdc])))
       (metric-card "Gross Exposure" (format-usdc (get-in snapshot [:capital :gross-exposure-usdc])))
       (metric-card "Net Exposure" (format-usdc (get-in snapshot [:capital :net-exposure-usdc])))]
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role "portfolio-optimizer-signed-exposure-table"}
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
        "Current Signed Exposure"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        (str (count (:exposures snapshot)) " current exposure rows available for optimizer request assembly.")]]]
     [:aside {:class ["rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100/95"
                      "p-4"]
              :data-role "portfolio-optimizer-right-rail"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
       "Trust & Freshness"]
      [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
       (if (:loaded? snapshot)
         "Current portfolio snapshot is available."
         "Current portfolio snapshot is not loaded yet.")]
      (when-let [message (get-in snapshot [:account :read-only-message])]
        [:p {:class ["mt-3" "rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]}
         message])]]))
