(ns hyperopen.views.portfolio.optimize.setup-v4-sections
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.query :as asset-query]
            [hyperopen.views.portfolio.optimize.instrument-overrides-panel :as instrument-overrides-panel]
            [hyperopen.views.portfolio.optimize.run-status-panel :as run-status-panel]))
(def ^:private eyebrow-class
  ["font-mono" "text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"])

(def ^:private input-class
  ["w-full" "border" "border-base-300" "bg-base-100/80" "px-2" "py-1.5"
   "font-mono" "text-xs" "font-semibold" "outline-none" "focus:border-warning/70"])

(defn- normalized-text
  [value]
  (some-> value str str/trim))

(defn- route-title
  [route]
  (case (:kind route)
    :optimize-new "Untitled scenario"
    :optimize-scenario (str "Scenario " (:scenario-id route))
    "Optimizer scenario"))

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

(defn- selected-instrument-ids
  [universe]
  (into #{} (keep :instrument-id) universe))

(defn- market-label
  [market]
  (or (normalized-text (:symbol market))
      (normalized-text (:coin market))
      (normalized-text (:key market))
      "Unknown Market"))

(defn- candidate-markets
  [state universe query]
  (let [selected-ids (selected-instrument-ids universe)
        query* (or (normalized-text query) "")]
    (->> (asset-query/filter-and-sort-assets
          (get-in state [:asset-selector :markets])
          query*
          :volume
          :desc
          #{}
          false
          false
          :all)
         (filter #(and (normalized-text (:key %))
                       (normalized-text (:coin %))
                       (:market-type %)
                       (not (contains? selected-ids (:key %)))))
         (take 6)
         vec)))

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

(defn- v4-button
  [label selected? role action]
  [:button {:type "button"
            :class (cond-> ["border" "border-base-300" "bg-base-200/20" "px-2.5" "py-2"
                            "text-left" "text-xs" "font-semibold" "text-trading-text"
                            "transition-colors" "hover:border-warning/50" "hover:text-warning"]
                     selected? (conj "border-warning/60" "bg-warning/10" "text-warning"))
            :aria-pressed (str selected?)
            :data-role role
            :on {:click [action]}}
   label
   (when selected?
     [:span {:class ["ml-2" "font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.14em]" "text-primary"]}
      "Active"])])

(defn setup-header
  [{:keys [draft route running? run-triggerable? saving-scenario? solved-run? result-path]}]
  [:header {:class ["border" "border-base-300" "bg-base-100/90" "px-3" "py-2.5"]
            :data-role "portfolio-optimizer-setup-header"}
   [:div {:class ["flex" "items-center" "justify-between" "gap-4"]}
    [:div {:class ["min-w-0"]}
     [:p {:class eyebrow-class} "Optimizer - portfolio / optimize / new"]
     [:div {:class ["mt-1" "flex" "flex-wrap" "items-center" "gap-2"]}
      [:h1 {:class ["text-lg" "font-semibold" "tracking-tight" "text-trading-text"]}
       (route-title route)]
      [:span {:class ["text-sm" "text-trading-muted"]}
       "- configure your target portfolio"]
      [:span {:class ["border" "border-base-300" "bg-base-200/40" "px-2" "py-0.5"
                      "font-mono" "text-[0.6rem]" "font-semibold" "uppercase"
                      "tracking-[0.12em]" "text-trading-muted"]
              :data-role "portfolio-optimizer-setup-status-tag"}
       (if (= :computed (:status draft)) "computed" "draft")]]
     (run-status-panel/draft-state-badge draft)]
    [:div {:class ["flex" "shrink-0" "items-center" "gap-2"]}
     [:button {:type "button"
               :class ["border" "border-base-300" "bg-base-200/20" "px-3" "py-1.5"
                       "font-mono" "text-xs" "font-semibold" "text-trading-muted"]
               :aria-label "More setup actions"
               :data-role "portfolio-optimizer-setup-overflow"}
      "..."]
     [:button {:type "button"
               :class ["border" "border-base-300" "bg-base-200/30" "px-3" "py-1.5"
                       "text-xs" "font-semibold" "text-trading-text"
                       "disabled:cursor-not-allowed" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-save-scenario"
               :disabled (or (not solved-run?) saving-scenario?)
               :on {:click [[:actions/save-portfolio-optimizer-scenario-from-current]]}}
      (if saving-scenario? "Saving" "Save draft")]
     [:button {:type "button"
               :class ["border" "border-warning/60" "bg-warning/10" "px-3" "py-1.5"
                       "text-xs" "font-semibold" "text-warning"
                       "disabled:cursor-not-allowed" "disabled:border-base-300"
                       "disabled:bg-base-200/30" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-run-draft"
               :disabled (not run-triggerable?)
               :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
      (if running? "Running Optimization" "Run optimization")]
     (when solved-run?
       [:button {:type "button"
                 :class ["border" "border-warning/60" "bg-warning/10" "px-3" "py-1.5"
                         "text-xs" "font-semibold" "text-warning"]
                 :data-role "portfolio-optimizer-view-weights"
                 :on {:click [[:actions/navigate result-path]]}}
        "View weights"])]]])

(defn- preset-card
  [draft preset title subtitle kicker]
  (let [selected? (= preset (active-preset draft))]
    [:button {:type "button"
              :class (cond-> ["border" "border-base-300" "bg-base-100/70" "p-3" "text-left"
                              "transition-colors" "hover:border-warning/50"]
                       selected? (conj "border-warning/70" "bg-warning/10"))
              :aria-pressed (str selected?)
              :data-role (str "portfolio-optimizer-setup-preset-" (name preset))
              :on {:click [[:actions/apply-portfolio-optimizer-setup-preset preset]]}}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div
       [:p {:class ["text-sm" "font-semibold" (if selected? "text-warning" "text-trading-text")]}
        (str (if selected? "(*) " "( ) ") title)]
       [:p {:class ["mt-2" "text-xs" "text-trading-muted"]} subtitle]
       [:p {:class ["mt-2" "font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.16em]"
                    "text-trading-muted"]}
        kicker]]
      (when selected?
        [:span {:class ["border" "border-base-300" "px-1.5" "py-0.5" "font-mono"
                        "text-[0.55rem]" "uppercase" "tracking-[0.12em]" "text-trading-muted"]}
         "default"])]]))

(defn preset-row
  [draft]
  [:section {:class ["border" "border-base-300" "bg-base-100/80" "p-3"]
             :data-role "portfolio-optimizer-setup-preset-row"}
   [:div {:class ["grid" "grid-cols-1" "gap-2" "xl:grid-cols-[90px_minmax(0,1fr)]"]}
    [:p {:class (conj eyebrow-class "pt-2")} "Start with"]
    [:div {:class ["grid" "grid-cols-1" "gap-2" "lg:grid-cols-3"]}
     (preset-card draft :conservative "Conservative"
                  "Minimum variance - stabilized historical returns"
                  "Recommended for first runs")
     (preset-card draft :risk-adjusted "Risk-adjusted"
                  "Maximum Sharpe - stabilized historical returns"
                  "Best risk-adjusted return")
     (preset-card draft :use-my-views "Use my views"
                  "Combine the market reference with your absolute / relative beliefs"
                  "For experienced users")]]])

(defn- history-label
  [state coin]
  (if (seq (get-in state [:portfolio :optimizer :history-data :candle-history-by-coin coin]))
    "sufficient"
    "missing"))

(defn- selected-row
  [state instrument]
  (let [instrument-id (:instrument-id instrument)
        coin (:coin instrument)]
    [:div {:class ["grid" "grid-cols-[minmax(0,1fr)_76px_80px_26px]" "items-center"
                   "gap-2" "border-b" "border-base-300/70" "px-2" "py-2" "text-xs"]
           :data-role (str "portfolio-optimizer-universe-selected-row-" instrument-id)}
     [:div {:class ["min-w-0"]}
      [:p {:class ["truncate" "font-semibold"]} (or coin instrument-id)]
      [:p {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
       (str (name (:market-type instrument)) " / " instrument-id)]]
     [:span {:class ["font-mono" "text-warning"]} (history-label state coin)]
     [:span {:class ["font-mono" "text-trading-muted"]} "medium"]
     [:button {:type "button"
               :class ["text-trading-muted" "hover:text-warning"]
               :aria-label (str "Remove " instrument-id)
               :data-role (str "portfolio-optimizer-universe-remove-" instrument-id)
               :on {:click [[:actions/remove-portfolio-optimizer-universe-instrument
                              instrument-id]]}}
      "x"]]))

(defn- market-row
  [market]
  (let [market-key (:key market)]
    [:div {:class ["grid" "grid-cols-[minmax(0,1fr)_56px]" "items-center" "gap-2"
                   "border" "border-base-300" "bg-base-200/20" "px-2" "py-2"]}
     [:div {:class ["min-w-0"]}
      [:p {:class ["truncate" "text-xs" "font-semibold"]} (market-label market)]
      [:p {:class ["font-mono" "text-[0.6rem]" "uppercase" "tracking-[0.14em]" "text-trading-muted"]}
       (str market-key " / " (name (:market-type market)))]]
     [:button {:type "button"
               :class ["border" "border-primary/50" "bg-primary/10" "px-2" "py-1"
                       "font-mono" "text-[0.6rem]" "font-semibold" "uppercase"
                       "tracking-[0.14em]" "text-primary"]
               :data-role (str "portfolio-optimizer-universe-add-" market-key)
               :on {:click [[:actions/add-portfolio-optimizer-universe-instrument market-key]]}}
      "Add"]]))

(defn- universe-section
  [state draft]
  (let [universe (vec (or (:universe draft) []))
        search-query (or (get-in state [:portfolio-ui :optimizer :universe-search-query]) "")
        markets (candidate-markets state universe search-query)]
    (panel
     "portfolio-optimizer-universe-panel"
     (section-heading "01" "Universe" (str (count universe) " included"))
     [:div {:class ["mt-3" "grid" "grid-cols-3" "border" "border-base-300" "text-center"
                    "font-mono" "text-[0.6rem]" "font-semibold" "uppercase"
                    "tracking-[0.12em]" "text-trading-muted"]}
      [:button {:type "button"
                :class ["border-r" "border-base-300" "px-2" "py-2" "hover:text-warning"]
                :data-role "portfolio-optimizer-universe-use-current"
                :on {:click [[:actions/set-portfolio-optimizer-universe-from-current]]}}
       "From holdings"
       [:span {:class ["sr-only"]} "Use Current Holdings"]]
      [:span {:class ["border-r" "border-warning/60" "bg-warning/10" "px-2" "py-2" "text-warning"]}
       "Custom"]
      [:span {:class ["px-2" "py-2" "text-trading-muted/60"]} "Index"]]
     [:div {:class ["mt-3" "border" "border-base-300" "bg-base-200/15"]}
      [:div {:class ["grid" "grid-cols-[minmax(0,1fr)_76px_80px_26px]" "gap-2" "px-2"
                     "py-1.5" "font-mono" "text-[0.58rem]" "uppercase"
                     "tracking-[0.12em]" "text-trading-muted"]}
       [:span "Asset"] [:span "History"] [:span "Liquidity"] [:span ""]]
      (if (seq universe)
        (into [:div] (map #(selected-row state %) universe))
        [:p {:class ["border-t" "border-base-300" "px-2" "py-3" "text-xs" "text-trading-muted"]}
         "No instruments selected yet."])]
     [:div {:class ["mt-3"]}
      [:p {:class eyebrow-class} "Manual Add"]
      [:input {:type "search"
               :class (conj input-class "mt-2")
               :placeholder "Search ticker or name (e.g. BTC, ETH, Solana...)"
               :data-role "portfolio-optimizer-universe-search-input"
               :value search-query
               :on {:input [[:actions/set-portfolio-optimizer-universe-search-query
                             [:event.target/value]]]}}]
      [:p {:class ["mt-1.5" "text-xs" "text-trading-muted"]}
       "Requires history reload after adding new assets."]
      (if (seq markets)
        (into [:div {:class ["mt-2" "space-y-2"]}] (map market-row markets))
        [:p {:class ["mt-2" "border" "border-base-300" "bg-base-200/20" "p-2"
                     "text-xs" "text-trading-muted"]}
         "No matching unused markets found."])])))

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
       [:div {:class ["mt-2" "grid" "grid-cols-1" "gap-1.5"]}
        (v4-button "Historical Mean" (= :historical-mean return-kind)
                   "portfolio-optimizer-return-model-historical-mean"
                   [:actions/set-portfolio-optimizer-return-model-kind :historical-mean])
        (v4-button "EW Mean" (= :ew-mean return-kind)
                   "portfolio-optimizer-return-model-ew-mean"
                   [:actions/set-portfolio-optimizer-return-model-kind :ew-mean])
        [:div {:class ["relative"]}
         (v4-button "Use my views" (= :black-litterman return-kind)
                    "portfolio-optimizer-return-model-black-litterman"
                    [:actions/set-portfolio-optimizer-return-model-kind :black-litterman])
         [:span {:class ["sr-only"]} "Black-Litterman"]]]
       [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
        (case return-kind
          :black-litterman "Black-Litterman stays here as a return-model mode, not an objective."
          :ew-mean "Exponentially weighted returns emphasize recent history."
          "Average of past returns. Simple and auditable for first runs.")]]
      [:div {:data-role "portfolio-optimizer-setup-model-column"}
       [:div {:data-role "portfolio-optimizer-risk-model-panel"}
        [:p {:class eyebrow-class} "Risk model"]
        [:div {:class ["mt-2" "grid" "grid-cols-1" "gap-1.5"]}
         (v4-button "Diagonal Shrink" (= :diagonal-shrink risk-kind)
                    "portfolio-optimizer-risk-model-diagonal-shrink"
                    [:actions/set-portfolio-optimizer-risk-model-kind :diagonal-shrink])
         (v4-button "Sample Covariance" (= :sample-covariance risk-kind)
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

(defn- objective-section
  [draft highlighted-controls]
  (let [objective-kind (get-in draft [:objective :kind])]
    (panel
     "portfolio-optimizer-objective-panel"
     (section-heading "03" "Objective" (labelize objective-kind))
     [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-1.5" "sm:grid-cols-2"]}
      (v4-button "Minimum Variance" (= :minimum-variance objective-kind)
                 "portfolio-optimizer-objective-minimum-variance"
                 [:actions/set-portfolio-optimizer-objective-kind :minimum-variance])
      (v4-button "Maximum Sharpe" (= :max-sharpe objective-kind)
                 "portfolio-optimizer-objective-max-sharpe"
                 [:actions/set-portfolio-optimizer-objective-kind :max-sharpe])
      (v4-button "Target Volatility" (= :target-volatility objective-kind)
                 "portfolio-optimizer-objective-target-volatility"
                 [:actions/set-portfolio-optimizer-objective-kind :target-volatility])
      (v4-button "Target Return" (= :target-return objective-kind)
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

(defn- constraint-input
  [label constraint-key value role highlighted?]
  (number-input label value role
                [:actions/set-portfolio-optimizer-constraint
                 constraint-key
                 [:event.target/value]]
                highlighted?))

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
      (constraint-input "Max Asset Weight" :max-asset-weight (:max-asset-weight constraints)
                        "portfolio-optimizer-constraint-max-asset-weight-input"
                        (contains? highlighted-controls :max-asset-weight))
      (constraint-input "Gross Leverage" :gross-max (:gross-max constraints)
                        "portfolio-optimizer-constraint-gross-max-input" false)
      (constraint-input "Net Min" :net-min (:net-min constraints)
                        "portfolio-optimizer-constraint-net-min-input" false)
      (constraint-input "Net Max" :net-max (:net-max constraints)
                        "portfolio-optimizer-constraint-net-max-input" false)
      (constraint-input "Dust Threshold" :dust-usdc (:dust-usdc constraints)
                        "portfolio-optimizer-constraint-dust-usdc-input" false)
      (constraint-input "Max Turnover" :max-turnover (:max-turnover constraints)
                        "portfolio-optimizer-constraint-max-turnover-input" false)
      (constraint-input "Rebalance Tolerance" :rebalance-tolerance (:rebalance-tolerance constraints)
                        "portfolio-optimizer-constraint-rebalance-tolerance-input" false)])))

(defn control-rail
  [{:keys [state draft highlighted-controls]}]
  [:aside {:class ["space-y-3"] :data-role "portfolio-optimizer-setup-control-rail"}
   (universe-section state draft)
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
     [:section {:class ["border" "border-base-300" "bg-base-100/90" "p-4"]}
      [:p {:class eyebrow-class} "What this model assumes"]
      [:ul {:class ["mt-3" "space-y-2" "text-xs" "text-trading-muted"]}
       [:li "- Returns are roughly normal at the chosen horizon."]
       [:li "- Past covariance is informative about future covariance."]
       [:li "- Cross-margin is treated as one book."]
       [:li "- Tail risk and drawdown are not modeled in this setup pass."]]]]))
