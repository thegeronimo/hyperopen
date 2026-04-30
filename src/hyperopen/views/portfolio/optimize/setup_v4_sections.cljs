(ns hyperopen.views.portfolio.optimize.setup-v4-sections
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.instrument-overrides-panel :as instrument-overrides-panel]
            [hyperopen.views.portfolio.optimize.setup-v4-summary :as setup-v4-summary]
            [hyperopen.views.portfolio.optimize.setup-v4-universe :as setup-v4-universe]))
(def ^:private eyebrow-class
  ["font-mono" "text-[0.625rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"])

(def ^:private section-title-class
  ["text-[0.6875rem]" "font-semibold" "uppercase" "tracking-[0.08em]" "text-trading-text"])

(def ^:private input-class
  ["w-full" "border" "border-base-300" "bg-base-100/80" "px-2" "py-1.5"
   "font-mono" "text-[0.6875rem]" "font-medium" "outline-none"
   "transition-shadow" "focus:border-warning/70"
   "focus:shadow-[0_0_0_1px_rgba(212,181,88,0.75)]"])

(def ^:private constraint-help
  {:long-only? "Restricts target weights to zero or positive values. Turn this off when short or hedged perp exposure is allowed."
   :max-asset-weight "Maximum target portfolio weight any single asset can receive. 0.5 means no asset can exceed 50%."
   :gross-max "Maximum total absolute exposure across all legs. 1 means long exposure plus short exposure can total up to 100% of capital."
   :net-min "Minimum signed net exposure allowed after optimization. Leave blank when only the maximum net exposure matters."
   :net-max "Maximum signed net exposure allowed after optimization. 1 means the portfolio can be net long up to 100% of capital."
   :dust-usdc "Small rebalance trades below this USDC notional are ignored so the output avoids noisy dust orders."
   :max-turnover "Maximum total portfolio turnover allowed for the rebalance. 1 means trades can sum to 100% of capital."
   :rebalance-tolerance "Minimum target-vs-current weight difference before a rebalance row is considered actionable. 0.03 means 3 percentage points."})

(def ^:private model-help
  {:historical-mean "Uses the arithmetic mean of historical returns for each selected asset."
   :ew-mean "Uses exponentially weighted historical returns so recent observations count more."
   :black-litterman "Combines market-implied returns with your Black-Litterman views and confidence inputs."
   :diagonal-shrink "Shrinks the covariance estimate toward a diagonal model to reduce noisy cross-asset correlations."
   :sample-covariance "Uses the raw historical covariance matrix from the selected asset return history."})

(defn- active-preset
  [draft]
  (let [objective-kind (get-in draft [:objective :kind])
        return-kind (get-in draft [:return-model :kind])]
    (cond
      (= :black-litterman return-kind) :use-my-views
      (= :max-sharpe objective-kind) :risk-adjusted
      :else :conservative)))

(defn- labelize
  [value]
  (-> (name (or value :unknown))
      (str/replace "-" " ")
      (str/capitalize)))

(defn- percent-label
  [value]
  (if (number? value)
    (str (.toFixed (* value 100) 0) "%")
    "--"))

(defn- panel
  [role & children]
  (into [:section {:class ["border" "border-base-300" "bg-base-100/90" "p-3"]
                   :data-role role}]
        children))

(defn- disclosure-panel
  [role & children]
  (into [:details {:class ["border" "border-base-300" "bg-base-100/90" "p-3"]
                   :data-role role}]
        children))

(defn- section-heading
  [idx title trailing]
  [:div {:class ["flex" "items-center" "justify-between" "gap-3" "border-b" "border-base-300" "pb-2"]}
   [:p {:class section-title-class}
    [:span {:class ["mr-2" "font-mono" "text-trading-muted/70"]} idx]
    title]
   (when trailing
     [:span {:class ["font-mono" "text-[0.65625rem]" "uppercase" "tracking-[0.08em]"
                      "text-trading-muted/70"]}
      trailing])])

(defn- disclosure-heading
  [idx title trailing]
  [:summary {:class ["cursor-pointer" "select-none" "focus:outline-none" "focus:text-warning"]}
   (section-heading idx title trailing)])

(defn- segmented-button
  ([label selected? role action]
   (segmented-button label nil nil :center selected? role action))
  ([label hidden-label selected? role action]
   (segmented-button label hidden-label nil :center selected? role action))
  ([label hidden-label help-copy tooltip-position selected? role action]
   (let [tooltip-id (str role "-tooltip")
         tooltip-position-classes (case tooltip-position
                                    :start ["left-0"]
                                    :end ["right-0"]
                                    ["left-1/2" "-translate-x-1/2"])]
     [:button {:type "button"
               :class (cond-> ["group" "relative" "border-r" "border-base-300"
                               "bg-transparent" "px-2" "py-1.5" "text-center"
                               "text-[0.65625rem]" "font-medium" "uppercase"
                               "tracking-[0.04em]" "text-trading-muted"
                               "transition-colors" "last:border-r-0"
                               "hover:text-warning" "focus:outline-none"
                               "focus:text-warning"
                               "focus:shadow-[inset_0_0_0_1px_rgba(212,181,88,0.75)]"]
                        selected? (conj "bg-base-200/40" "text-trading-text"))
               :aria-pressed (str selected?)
               :aria-describedby (when help-copy tooltip-id)
               :data-role role
               :on {:click [action]}}
      label
      (when hidden-label
        [:span {:class ["sr-only"]} hidden-label])
      (when help-copy
        [:span {:class (into ["pointer-events-none" "absolute" "top-[calc(100%+6px)]"
                              "z-30" "w-72" "max-w-[calc(100vw-2rem)]"
                              "border" "border-base-300" "bg-base-100" "px-2" "py-1.5"
                              "font-sans" "text-[0.65625rem]" "font-normal"
                              "normal-case" "leading-[1.45]" "tracking-normal"
                              "text-trading-muted" "opacity-0"
                              "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"
                              "transition-opacity" "duration-150"
                              "group-hover:opacity-100" "group-focus:opacity-100"]
                             tooltip-position-classes)
                :id tooltip-id
                :role "tooltip"
                :data-role tooltip-id}
         help-copy])])))

(defn- model-section
  [draft]
  (let [return-kind (get-in draft [:return-model :kind])
        risk-kind (get-in draft [:risk-model :kind])]
    (disclosure-panel
     "portfolio-optimizer-return-risk-panel"
     (disclosure-heading "03" "Return / Risk Model" (labelize return-kind))
     [:div {:class ["mt-3" "space-y-3"] :data-role "portfolio-optimizer-setup-model-grid"}
      [:div {:data-role "portfolio-optimizer-return-model-panel"}
       [:p {:class eyebrow-class} "Expected returns"]
       [:div {:class ["mt-2" "grid" "grid-cols-3" "border"
                      "border-base-300"]}
        (segmented-button "Historical" "Historical Mean" (:historical-mean model-help) :start (= :historical-mean return-kind)
                          "portfolio-optimizer-return-model-historical-mean"
                          [:actions/set-portfolio-optimizer-return-model-kind :historical-mean])
        (segmented-button "EW Mean" nil (:ew-mean model-help) :center (= :ew-mean return-kind)
                          "portfolio-optimizer-return-model-ew-mean"
                          [:actions/set-portfolio-optimizer-return-model-kind :ew-mean])
        (segmented-button "Use my views" nil (:black-litterman model-help) :end (= :black-litterman return-kind)
                          "portfolio-optimizer-return-model-black-litterman"
                          [:actions/set-portfolio-optimizer-return-model-kind :black-litterman])
        [:span {:class ["sr-only"]} "Black-Litterman"]]
       [:p {:class ["mt-2" "text-[0.6875rem]" "text-trading-muted"]}
        (case return-kind
          :black-litterman "Black-Litterman stays here as a return-model mode, not an objective."
          :ew-mean "Exponentially weighted returns emphasize recent history."
          "Average of past returns. Simple and auditable for first runs.")]]
      [:div {:data-role "portfolio-optimizer-setup-model-column"}
       [:div {:data-role "portfolio-optimizer-risk-model-panel"}
        [:p {:class eyebrow-class} "Risk model"]
        [:div {:class ["mt-2" "grid" "grid-cols-2" "border"
                       "border-base-300"]}
         (segmented-button "Stabilized Covariance" "Diagonal Shrink" (:diagonal-shrink model-help) :start (= :diagonal-shrink risk-kind)
                           "portfolio-optimizer-risk-model-diagonal-shrink"
                           [:actions/set-portfolio-optimizer-risk-model-kind :diagonal-shrink])
         (segmented-button "Sample Covariance" nil (:sample-covariance model-help) :end (= :sample-covariance risk-kind)
                           "portfolio-optimizer-risk-model-sample-covariance"
                           [:actions/set-portfolio-optimizer-risk-model-kind :sample-covariance])]]]])))

(defn- number-input
  [label value role action highlighted?]
  [:label {:class (cond-> ["block" "border" "border-base-300" "bg-base-200/20" "p-2"]
                    highlighted? (conj "border-warning/70" "bg-warning/10"))}
   [:span {:class eyebrow-class} label]
   [:input {:type "text"
            :inputmode "decimal"
            :class (conj input-class "mt-2")
            :data-role role
            :data-infeasible (when highlighted? "true")
            :aria-invalid (when highlighted? "true")
            :value (str value)
            :on {:input [action]}}]])

(declare objective-card)

(defn- objective-section
  [draft highlighted-controls]
  (let [objective-kind (get-in draft [:objective :kind])]
    (panel
     "portfolio-optimizer-objective-panel"
     (section-heading "02" "Objective" (labelize objective-kind))
     [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-1.5" "sm:grid-cols-2"]}
      (objective-card "Minimum Variance" "Lowest risk - no return assumption"
                      (= :minimum-variance objective-kind)
                      "portfolio-optimizer-objective-minimum-variance"
                      [:actions/set-portfolio-optimizer-objective-kind :minimum-variance])
      (objective-card "Maximum Sharpe" "Best risk-adjusted return"
                      (= :max-sharpe objective-kind)
                      "portfolio-optimizer-objective-max-sharpe"
                      [:actions/set-portfolio-optimizer-objective-kind :max-sharpe])
      (objective-card "Target Volatility" "Cap how much risk you take"
                      (= :target-volatility objective-kind)
                      "portfolio-optimizer-objective-target-volatility"
                      [:actions/set-portfolio-optimizer-objective-kind :target-volatility])
      (objective-card "Target Return" "Aim for a specific return"
                      (= :target-return objective-kind)
                      "portfolio-optimizer-objective-target-return"
                      [:actions/set-portfolio-optimizer-objective-kind :target-return])]
     (when (#{:target-volatility :target-return} objective-kind)
       [:div {:class ["mt-2"]}
        (case objective-kind
          :target-volatility
          (number-input "Target Volatility"
                        (or (get-in draft [:objective :target-volatility]) 0.2) "portfolio-optimizer-objective-target-volatility-input"
                        [:actions/set-portfolio-optimizer-objective-parameter :target-volatility [:event.target/value]]
                        false)
          :target-return
          (number-input "Target Return"
                        (or (get-in draft [:objective :target-return]) 0.15) "portfolio-optimizer-objective-target-return-input"
                        [:actions/set-portfolio-optimizer-objective-parameter :target-return [:event.target/value]]
                        (contains? highlighted-controls :target-return)))]))))

(defn- objective-card
  [title subtitle selected? role action]
  [:button {:type "button"
            :class (cond-> ["border" "border-base-300" "bg-base-200/20" "p-2"
                            "text-left" "transition-colors" "hover:border-warning/50"]
                     selected? (conj "border-warning/60" "bg-warning/10"))
            :aria-pressed (str selected?)
            :data-role role
            :on {:click [action]}}
   [:p {:class ["text-[0.6875rem]" "font-medium" "text-trading-text"]}
    [:span {:class (if selected? "text-warning" "text-trading-muted")} (if selected? "◉ " "○ ")]
    title
    [:span {:class ["sr-only"]} title]]
   [:p {:class ["mt-1" "text-[0.65625rem]" "text-trading-muted"]} subtitle]])

(defn- constraint-tooltip
  [tooltip-id copy]
  [:span {:class ["pointer-events-none" "absolute" "left-0" "top-[calc(100%+6px)]"
                  "z-30" "w-[min(22rem,calc(100vw-2rem))]" "border"
                  "border-base-300" "bg-base-100" "px-2" "py-1.5"
                  "font-sans" "text-[0.65625rem]" "font-normal"
                  "normal-case" "leading-[1.45]" "tracking-normal"
                  "text-trading-muted" "opacity-0" "shadow-[0_12px_32px_rgba(0,0,0,0.45)]"
                  "transition-opacity" "duration-150" "group-hover:opacity-100"
                  "group-focus-within:opacity-100"]
          :id tooltip-id
          :role "tooltip"
          :data-role tooltip-id}
   copy])

(defn- constraint-label
  [label tooltip-id help-copy]
  [:span {:class ["relative" "inline-flex" "min-w-0" "items-center" "gap-1.5"]}
   [:span {:class eyebrow-class} label]
   [:span {:class ["font-mono" "text-[0.5625rem]" "text-trading-muted/70"]
           :aria-hidden "true"}
    "?"]
   (constraint-tooltip tooltip-id help-copy)])

(defn- constraint-row
  ([label constraint-key value role highlighted?]
   (constraint-row label nil constraint-key value role highlighted?))
  ([label hidden-label constraint-key value role highlighted?]
   (let [tooltip-id (str role "-tooltip")
         help-copy (get constraint-help constraint-key)]
  [:label {:class (cond-> ["group" "relative" "grid" "grid-cols-[minmax(0,1fr)_92px]" "items-center"
                           "gap-2" "border" "border-base-300" "bg-base-200/20"
                           "px-2" "py-1.5"]
                    highlighted? (conj "border-warning/70" "bg-warning/10"))}
   [:span {:class ["min-w-0"]}
    (if help-copy
      (constraint-label label tooltip-id help-copy)
      [:span {:class eyebrow-class} label])
    (when hidden-label
      [:span {:class ["sr-only"]} hidden-label])
    [:span {:class ["ml-2" "font-mono" "text-[0.59375rem]" "uppercase"
                    "tracking-[0.08em]" "text-trading-muted"]}
     "edit"]]
   [:input {:type "text"
            :inputmode "decimal"
            :class input-class
            :data-role role
            :data-infeasible (when highlighted? "true")
            :aria-invalid (when highlighted? "true")
            :aria-describedby (when help-copy tooltip-id)
            :value (str value)
            :on {:input [[:actions/set-portfolio-optimizer-constraint
                          constraint-key
                          [:event.target/value]]]}}]])))

(defn- constraints-section
  [draft highlighted-controls]
  (let [constraints (:constraints draft)]
    (disclosure-panel
     "portfolio-optimizer-constraints-panel"
     (disclosure-heading "04" "Constraints" "mandatory")
     [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2"]}
      [:label {:class ["group" "relative" "flex" "items-center" "justify-between" "gap-3" "border"
                       "border-base-300" "bg-base-200/20" "p-2"]}
       [:span {:class ["min-w-0"]}
        (constraint-label "Long Only"
                          "portfolio-optimizer-constraint-long-only-tooltip"
                          (:long-only? constraint-help))]
       [:input {:type "checkbox"
                :class ["h-4" "w-4" "accent-warning" "outline-none"
                        "transition-shadow"
                        "focus:shadow-[0_0_0_2px_rgba(212,181,88,0.75)]"]
                :data-role "portfolio-optimizer-constraint-long-only-input"
                :aria-describedby "portfolio-optimizer-constraint-long-only-tooltip"
                :checked (true? (:long-only? constraints))
                :on {:change [[:actions/set-portfolio-optimizer-constraint
                               :long-only?
                               :event.target/checked]]}}]]
      (constraint-row "Per-asset cap" "Max Asset Weight"
                      :max-asset-weight (:max-asset-weight constraints)
                      "portfolio-optimizer-constraint-max-asset-weight-input"
                      (contains? highlighted-controls :max-asset-weight))
      (constraint-row "Gross exposure" "Gross Leverage"
                      :gross-max (:gross-max constraints)
                      "portfolio-optimizer-constraint-gross-max-input" false)
      (constraint-row "Net exposure min" :net-min (:net-min constraints)
                      "portfolio-optimizer-constraint-net-min-input" false)
      (constraint-row "Net exposure max" :net-max (:net-max constraints)
                      "portfolio-optimizer-constraint-net-max-input" false)
      (constraint-row "Dust threshold" :dust-usdc (:dust-usdc constraints)
                      "portfolio-optimizer-constraint-dust-usdc-input" false)
      (constraint-row "Turnover cap" :max-turnover (:max-turnover constraints)
                      "portfolio-optimizer-constraint-max-turnover-input" false)
      (constraint-row "Rebalance tolerance" "Rebalance Tolerance"
                      :rebalance-tolerance (:rebalance-tolerance constraints)
                      "portfolio-optimizer-constraint-rebalance-tolerance-input" false)])))

(defn control-rail
  [{:keys [state draft highlighted-controls]}]
  [:aside {:class ["min-h-0" "overflow-hidden"] :data-role "portfolio-optimizer-setup-control-rail"}
   (setup-v4-universe/universe-section state draft)
   (objective-section draft highlighted-controls)
   (model-section draft)
   (constraints-section draft highlighted-controls)
   [:details {:class ["border" "border-base-300" "bg-base-100/90" "p-3"]
              :data-role "portfolio-optimizer-advanced-overrides-shell"}
    [:summary {:class (into ["cursor-pointer" "select-none"] section-title-class)}
     "Advanced Overrides"]
    [:div {:class ["mt-3"]}
     (instrument-overrides-panel/instrument-overrides-panel draft)]]])

(defn- summary-row
  [label title copy]
  [:div {:class ["grid" "grid-cols-[132px_minmax(0,1fr)]" "gap-4" "border-b"
                 "border-base-300" "px-4" "py-3"]}
   [:p {:class eyebrow-class} label]
   [:div
    [:p {:class ["text-[0.6875rem]" "font-medium" "text-trading-text"]} title]
    [:p {:class ["mt-1" "text-[0.6875rem]" "leading-[1.45]" "text-trading-muted"]} copy]]])

(defn- action-objective-label
  [objective-kind]
  (case objective-kind
    :max-sharpe "Maximum Sharpe"
    :target-volatility "Target volatility"
    :target-return "Target return"
    "Minimum variance"))

(defn- action-model-label
  [return-kind risk-kind]
  (cond
    (= :black-litterman return-kind) "posterior views"
    (= :sample-covariance risk-kind) "sample historical returns"
    :else "stabilized historical returns"))

(defn setup-bottom-actions
  [{:keys [draft running? run-triggerable? saving-scenario? solved-run? result-path]}]
  (let [asset-count (count (:universe draft))
        objective-copy (action-objective-label (get-in draft [:objective :kind]))
        model-copy (action-model-label (get-in draft [:return-model :kind])
                                       (get-in draft [:risk-model :kind]))]
  [:section {:class ["relative" "z-[180]" "mt-2" "flex" "flex-col" "items-start" "gap-3"
                     "border" "border-base-300" "bg-[#101518]"
                     "px-7" "py-[14px]" "scroll-mb-12" "leading-4"
                     "sm:flex-row" "sm:flex-wrap" "sm:items-center" "sm:gap-4"]
             :data-role "portfolio-optimizer-setup-bottom-actions"}
   [:button {:type "button"
             :class ["border" "border-warning/70" "bg-warning/80" "px-6" "py-2.5"
                     "whitespace-nowrap" "text-[0.71875rem]" "font-semibold" "text-base-100"
                     "shadow-[0_0_0_1px_rgba(0,0,0,0.25)]"
                     "scroll-mb-12"
                     "disabled:cursor-not-allowed" "disabled:border-base-300"
                     "disabled:bg-base-200/30" "disabled:text-trading-muted"
                     "disabled:shadow-none"]
             :data-role "portfolio-optimizer-run-draft"
             :disabled (not run-triggerable?)
             :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
    (if running? "Running Optimization" "Run optimization")]
   [:button {:type "button"
             :class ["border" "border-base-300" "bg-base-200/30" "px-3" "py-2"
                     "whitespace-nowrap" "text-[0.6875rem]" "font-semibold" "text-trading-text"
                     "scroll-mb-12"
                     "disabled:cursor-not-allowed" "disabled:text-trading-muted"]
             :data-role "portfolio-optimizer-save-scenario"
             :disabled (or (not solved-run?) saving-scenario?)
             :on {:click [[:actions/save-portfolio-optimizer-scenario-from-current]]}}
    (if saving-scenario? "Saving" "Save draft")]
   (when solved-run?
     [:button {:type "button"
               :class ["border" "border-warning/60" "bg-warning/10" "px-3" "py-1.5"
                       "text-[0.6875rem]" "font-medium" "text-warning"]
               :data-role "portfolio-optimizer-view-weights"
               :on {:click [[:actions/navigate result-path]]}}
      "View weights"])
   [:div {:class ["flex" "max-w-full" "flex-col" "items-start" "gap-1.5" "font-mono"
                  "sm:ml-auto" "sm:min-w-[220px]" "sm:items-end" "sm:text-right"]}
    [:div {:class ["flex" "items-center" "gap-2" "text-[0.6875rem]" "font-semibold"
                   "whitespace-nowrap" "uppercase" "tracking-[0.14em]"
                   (if run-triggerable? "text-[#5a5f68]" "text-[#444951]")
                   "sm:justify-end"]
           :data-role "portfolio-optimizer-setup-bottom-actions-status-meta"}
     (when run-triggerable?
       [:span {:class ["h-2" "w-2" "rounded-full" "bg-success"]
               :aria-hidden "true"}])
     [:span (if run-triggerable? "Ready to run" "Add assets to run")]
     [:span {:class ["text-trading-muted/50"]} "·"]
     [:span (str asset-count " assets")]]
    [:div {:class ["max-w-full" "text-[0.625rem]" "font-semibold" "normal-case"
                   "tracking-normal" "text-trading-muted" "sm:max-w-[260px]"]
           :data-role "portfolio-optimizer-setup-bottom-actions-status-detail"}
     (str "Solving " objective-copy " · " model-copy)]]]))

(defn summary-pane
  [{:keys [draft running? run-triggerable? saving-scenario? solved-run? result-path]}]
  (let [preset (active-preset draft)
        objective-kind (get-in draft [:objective :kind])
        return-kind (get-in draft [:return-model :kind])
        constraints (:constraints draft)
        bl? (= :black-litterman return-kind)]
    [:main {:class ["space-y-4" "leading-4"] :data-role "portfolio-optimizer-setup-summary-pane"}
     [:div {:class ["px-1" "pt-2" "pb-1"]
            :data-role "portfolio-optimizer-setup-summary-heading"}
       [:p {:class eyebrow-class} "Summary"]
       [:h2 {:class ["mt-2" "text-[0.875rem]" "font-medium" "tracking-[-0.01em]"]}
        (if bl?
          "What your views will change"
          "What this scenario will solve for")]]
     [:section {:class ["border" "border-base-300" "bg-base-100/90"]
                :data-role "portfolio-optimizer-setup-summary-panel"}
      (summary-row "Preset" (labelize preset)
                   "You can deviate from the preset below without changing the universe.")
      (summary-row "Universe" (setup-v4-summary/universe-summary draft)
                   "Selected instruments are optimized as one cross-margin book.")
      (summary-row "Expected Returns" (labelize return-kind)
                   (if bl?
                     "Market reference plus explicit views produces the posterior return estimate."
                     "Funding-adjusted return assumptions are kept separate from covariance."))
      (summary-row "Objective" (labelize objective-kind)
                   "Objective remains separate from return model selection.")
      (summary-row "Constraints"
                   (str "gross <= " (or (:gross-max constraints) "--")
                        " - cap <= " (percent-label (:max-asset-weight constraints)))
                   "Constraints are enforced before the recommendation is accepted.")
      (summary-row "Horizon" "Annualized"
                   "Displayed return and volatility metrics use the optimizer annualization convention.")]
     (when bl?
       [:section {:class ["border" "border-warning/50" "bg-warning/10" "p-4"]
                  :data-role "portfolio-optimizer-setup-use-my-views-context"}
        [:p {:class eyebrow-class} "Use my views"]
        [:h3 {:class ["mt-2" "text-[0.875rem]" "font-medium"]}
         "What the model assumes and what your views change"]
        [:div {:class ["mt-4" "grid" "grid-cols-1" "gap-3" "lg:grid-cols-3"]}
         [:div [:p {:class eyebrow-class} "1 - Market reference"]
          [:p {:class ["mt-2" "text-[0.6875rem]" "leading-[1.45]" "text-trading-muted"]}
           "Prior weights come from market-cap proxy or current portfolio fallback."]]
         [:div [:p {:class eyebrow-class} "2 - Your views"]
          [:p {:class ["mt-2" "text-[0.6875rem]" "leading-[1.45]" "text-trading-muted"]}
           "Absolute or relative beliefs tilt expected returns with explicit confidence."]]
         [:div [:p {:class eyebrow-class} "3 - Combined output"]
          [:p {:class ["mt-2" "text-[0.6875rem]" "leading-[1.45]" "text-trading-muted"]}
           "The posterior return estimate feeds the selected optimizer objective."]]]])
     [:div {:class ["space-y-2"]
            :data-role "portfolio-optimizer-model-assumptions-stack"}
      [:section {:class ["border" "border-base-300" "bg-base-100/90"]
                 :data-role "portfolio-optimizer-model-assumptions-panel"
                 :data-v4-note "true"}
       [:p {:class eyebrow-class} "What this model assumes"]
       [:ul {:class ["mt-1" "space-y-px" "text-[0.65625rem]" "leading-[1.32]" "text-trading-muted"]}
        [:li "Returns are roughly normal at the chosen horizon."]
        [:li "Past covariance is informative about future covariance."]
        [:li "Cross-margin is treated as one book."]
        [:li "Tail risk and drawdown are not modeled in this setup pass."]]]
      (setup-bottom-actions {:draft draft
                             :running? running?
                             :run-triggerable? run-triggerable?
                             :saving-scenario? saving-scenario?
                             :solved-run? solved-run?
                             :result-path result-path})]]))
