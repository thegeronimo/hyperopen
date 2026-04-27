(ns hyperopen.views.portfolio.optimize.setup-v4-sections
  (:require [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.instrument-overrides-panel :as instrument-overrides-panel]
            [hyperopen.views.portfolio.optimize.setup-v4-universe :as setup-v4-universe]))
(def ^:private eyebrow-class
  ["font-mono" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"])

(def ^:private input-class
  ["w-full" "border" "border-base-300" "bg-base-100/80" "px-2" "py-1.5"
   "font-mono" "text-xs" "font-semibold" "outline-none" "focus:border-warning/70"])

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

(defn- section-heading
  [idx title trailing]
  [:div {:class ["flex" "items-center" "justify-between" "gap-3" "border-b" "border-base-300" "pb-2"]}
   [:p {:class eyebrow-class}
    [:span {:class ["mr-2" "text-trading-muted/70"]} idx]
    title]
   (when trailing
     [:span {:class ["font-mono" "text-[0.65rem]" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
      trailing])])

(defn- segmented-button
  ([label selected? role action]
   (segmented-button label nil selected? role action))
  ([label hidden-label selected? role action]
  [:button {:type "button"
            :class (cond-> ["border-r" "border-base-300" "bg-transparent" "px-2"
                            "py-1.5" "text-center" "font-mono" "text-[0.6rem]"
                            "font-semibold" "uppercase" "tracking-[0.08em]"
                            "text-trading-muted" "transition-colors"
                            "last:border-r-0" "hover:text-warning"]
                     selected? (conj "bg-base-200/40" "text-trading-text"))
            :aria-pressed (str selected?)
            :data-role role
            :on {:click [action]}}
   label
   (when hidden-label
     [:span {:class ["sr-only"]} hidden-label])]))

(defn- model-section
  [draft]
  (let [return-kind (get-in draft [:return-model :kind])
        risk-kind (get-in draft [:risk-model :kind])]
    (panel
     "portfolio-optimizer-return-risk-panel"
     (section-heading "02" "Return / Risk Model" (labelize return-kind))
     [:div {:class ["mt-3" "space-y-3"] :data-role "portfolio-optimizer-setup-model-grid"}
      [:div {:data-role "portfolio-optimizer-return-model-panel"}
       [:p {:class eyebrow-class} "Expected returns"]
       [:div {:class ["mt-2" "grid" "grid-cols-3" "overflow-hidden" "border"
                      "border-base-300"]}
        (segmented-button "Historical" "Historical Mean" (= :historical-mean return-kind)
                          "portfolio-optimizer-return-model-historical-mean"
                          [:actions/set-portfolio-optimizer-return-model-kind :historical-mean])
        (segmented-button "EW Mean" (= :ew-mean return-kind)
                          "portfolio-optimizer-return-model-ew-mean"
                          [:actions/set-portfolio-optimizer-return-model-kind :ew-mean])
        (segmented-button "Use my views" (= :black-litterman return-kind)
                          "portfolio-optimizer-return-model-black-litterman"
                          [:actions/set-portfolio-optimizer-return-model-kind :black-litterman])
        [:span {:class ["sr-only"]} "Black-Litterman"]]
       [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
        (case return-kind
          :black-litterman "Black-Litterman stays here as a return-model mode, not an objective."
          :ew-mean "Exponentially weighted returns emphasize recent history."
          "Average of past returns. Simple and auditable for first runs.")]]
      [:div {:data-role "portfolio-optimizer-setup-model-column"}
       [:div {:data-role "portfolio-optimizer-risk-model-panel"}
        [:p {:class eyebrow-class} "Risk model"]
        [:div {:class ["mt-2" "grid" "grid-cols-2" "overflow-hidden" "border"
                       "border-base-300"]}
         (segmented-button "Stabilized Covariance" "Diagonal Shrink" (= :diagonal-shrink risk-kind)
                           "portfolio-optimizer-risk-model-diagonal-shrink"
                           [:actions/set-portfolio-optimizer-risk-model-kind :diagonal-shrink])
         (segmented-button "Sample Covariance" (= :sample-covariance risk-kind)
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
     (section-heading "03" "Objective" (labelize objective-kind))
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
     [:div {:class ["mt-2" "grid" "grid-cols-1" "gap-2" "sm:grid-cols-2"]}
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
                     [:event.target/value]]
                    false)])))

(defn- objective-card
  [title subtitle selected? role action]
  [:button {:type "button"
            :class (cond-> ["border" "border-base-300" "bg-base-200/20" "p-2"
                            "text-left" "transition-colors" "hover:border-warning/50"]
                     selected? (conj "border-warning/60" "bg-warning/10"))
            :aria-pressed (str selected?)
            :data-role role
            :on {:click [action]}}
   [:p {:class ["text-xs" "font-semibold" (if selected? "text-warning" "text-trading-text")]}
    (str (if selected? "◉ " "○ ") title)
    [:span {:class ["sr-only"]} title]]
   [:p {:class ["mt-1" "text-[0.65rem]" "text-trading-muted"]} subtitle]])

(defn- constraint-row
  ([label constraint-key value role highlighted?]
   (constraint-row label nil constraint-key value role highlighted?))
  ([label hidden-label constraint-key value role highlighted?]
  [:label {:class (cond-> ["grid" "grid-cols-[minmax(0,1fr)_92px]" "items-center"
                           "gap-2" "border" "border-base-300" "bg-base-200/20"
                           "px-2" "py-1.5"]
                    highlighted? (conj "border-warning/70" "bg-warning/10"))}
   [:span {:class ["min-w-0"]}
    [:span {:class eyebrow-class} label]
    (when hidden-label
      [:span {:class ["sr-only"]} hidden-label])
    [:span {:class ["ml-2" "font-mono" "text-[0.6rem]" "uppercase"
                    "tracking-[0.08em]" "text-trading-muted"]}
     "edit"]]
   [:input {:type "text"
            :inputmode "decimal"
            :class input-class
            :data-role role
            :data-infeasible (when highlighted? "true")
            :aria-invalid (when highlighted? "true")
            :value (str value)
            :on {:input [[:actions/set-portfolio-optimizer-constraint
                          constraint-key
                          [:event.target/value]]]}}]]))

(defn- constraints-section
  [draft highlighted-controls]
  (let [constraints (:constraints draft)]
    (panel
     "portfolio-optimizer-constraints-panel"
     (section-heading "04" "Constraints" "mandatory")
     [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2"]}
      [:label {:class ["flex" "items-center" "justify-between" "gap-3" "border"
                       "border-base-300" "bg-base-200/20" "p-2"]}
       [:span {:class eyebrow-class} "Long Only"]
       [:input {:type "checkbox"
                :class ["h-4" "w-4" "accent-warning"]
                :data-role "portfolio-optimizer-constraint-long-only-input"
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
   (model-section draft)
   (objective-section draft highlighted-controls)
   (constraints-section draft highlighted-controls)
   [:details {:class ["border" "border-base-300" "bg-base-100/90" "p-3"]
              :data-role "portfolio-optimizer-advanced-overrides-shell"}
    [:summary {:class ["cursor-pointer" "select-none" "font-mono" "text-[0.65rem]"
                       "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
     "Advanced Overrides"]
    [:div {:class ["mt-3"]}
     (instrument-overrides-panel/instrument-overrides-panel draft)]]])

(defn- summary-row
  [label title copy]
  [:div {:class ["grid" "grid-cols-[132px_minmax(0,1fr)]" "gap-4" "border-b"
                 "border-base-300" "px-4" "py-3"]}
   [:p {:class eyebrow-class} label]
   [:div
    [:p {:class ["text-sm" "font-semibold" "text-trading-text"]} title]
    [:p {:class ["mt-1" "text-xs" "text-trading-muted"]} copy]]])

(defn- universe-summary
  [draft]
  (let [universe (vec (:universe draft))
        coins (->> universe (keep :coin) (take 5) (str/join ", "))]
    (str (count universe) " assets"
         (when (seq coins) (str " - " coins)))))

(defn summary-pane
  [{:keys [draft]}]
  (let [preset (active-preset draft)
        objective-kind (get-in draft [:objective :kind])
        return-kind (get-in draft [:return-model :kind])
        constraints (:constraints draft)
        bl? (= :black-litterman return-kind)]
    [:main {:class ["space-y-3"] :data-role "portfolio-optimizer-setup-summary-pane"}
     [:section {:class ["border" "border-base-300" "bg-base-100/90"]
                :data-role "portfolio-optimizer-setup-summary-panel"}
      [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
       [:p {:class eyebrow-class} "Summary"]
       [:h2 {:class ["mt-2" "text-lg" "font-semibold" "tracking-tight"]}
        (if bl?
          "What your views will change"
          "What this scenario will solve for")]]
      (summary-row "Preset" (labelize preset)
                   "You can deviate from the preset below without changing the universe.")
      (summary-row "Universe" (universe-summary draft)
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
        [:h3 {:class ["mt-2" "text-base" "font-semibold"]}
         "What the model assumes and what your views change"]
        [:div {:class ["mt-4" "grid" "grid-cols-1" "gap-3" "lg:grid-cols-3"]}
         [:div [:p {:class eyebrow-class} "1 - Market reference"]
          [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
           "Prior weights come from market-cap proxy or current portfolio fallback."]]
         [:div [:p {:class eyebrow-class} "2 - Your views"]
          [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
           "Absolute or relative beliefs tilt expected returns with explicit confidence."]]
         [:div [:p {:class eyebrow-class} "3 - Combined output"]
          [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
           "The posterior return estimate feeds the selected optimizer objective."]]]])
     [:section {:class ["border" "border-base-300" "bg-base-100/90" "p-4"]
                :data-v4-note "true"}
      [:p {:class eyebrow-class} "What this model assumes"]
      [:ul {:class ["mt-3" "space-y-2" "text-xs" "text-trading-muted"]}
       [:li "- Returns are roughly normal at the chosen horizon."]
       [:li "- Past covariance is informative about future covariance."]
       [:li "- Cross-margin is treated as one book."]
       [:li "- Tail risk and drawdown are not modeled in this setup pass."]]]]))
