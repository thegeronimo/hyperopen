(ns hyperopen.views.portfolio.optimize.tear-sheet-view
  "The hypothetical tear sheet page (/portfolio/optimize/<id>/tear-sheet): the
  optimizer's target weights backtested over each asset's own history, laid
  out like a vault's performance page but marked hypothetical throughout."
  (:require [hyperopen.portfolio.optimizer.application.tear-sheet.model :as tear-sheet]
            [hyperopen.portfolio.optimizer.application.view-model :as optimizer-view-model]
            [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.views.portfolio.optimize.tear-sheet-card :as card]
            [hyperopen.views.portfolio.optimize.tear-sheet-parts :as parts]
            [hyperopen.views.vaults.detail.activity.performance-metrics :as metrics-view]
            [hyperopen.views.vaults.detail.chart :as chart-model]
            [hyperopen.views.vaults.detail.chart-view :as chart-view]))

(defn- back-actions
  [scenario-id]
  [[:actions/navigate (portfolio-routes/portfolio-optimize-scenario-path scenario-id)]])

(defn- message
  [scenario-id title body]
  [:section {:class ["m-4" "rounded-xl" "border" "border-base-300" "p-5"]
             :data-role "portfolio-optimizer-tear-sheet-empty"}
   [:p {:class ["text-sm" "font-medium" "text-trading-text"]} title]
   [:p {:class ["mt-1.5" "text-sm" "text-trading-muted"]} body]
   [:button {:type "button"
             :class ["mt-3" "min-h-9" "rounded-md" "border" "border-base-300" "px-3" "text-sm" "text-trading-text"]
             :on {:click (back-actions scenario-id)}}
    "Back to recommendation"]])

(defn- range-label
  [value]
  (:label (some #(when (= value (:value %)) %) tear-sheet/ranges)))

(defn- chart-section
  [model opts]
  (let [{:keys [series y-ticks points]} (chart-model/build-chart-model
                                         {:selected-series :returns
                                          :raw-series (:raw-series model)})
        chart {:axis-kind :returns
               :selected-series :returns
               :selected-timeframe (:range opts)
               :tooltip-timeframe (:range opts)
               :series series
               :y-ticks y-ticks
               :points points}]
    [:section {:class ["min-w-0" "flex-[999_1_640px]" "rounded-xl" "border" "border-base-300" "bg-base-100/60" "p-3"]
               :data-role "portfolio-optimizer-tear-sheet-chart"}
     [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2" "border-b" "border-base-300" "pb-2"]}
      [:div {:role "group" :aria-label "Chart" :class ["flex" "gap-1"]}
       (for [[value label] [[:returns "Returns"] [:drawdown "Drawdown"]]]
         ^{:key (str "mode-" (name value))}
         (parts/pill {:label label
                      :active? (= value (:mode opts))
                      :on (parts/set-option :mode value)
                      :data-role (str "portfolio-optimizer-tear-sheet-mode-" (name value))}))]
      [:div {:role "group" :aria-label "Range" :class ["flex" "flex-wrap" "gap-0.5" "rounded-lg" "border" "border-base-300" "p-0.5"]}
       (for [{:keys [value label]} tear-sheet/ranges]
         ^{:key (str "range-" (name value))}
         (parts/pill {:label label
                      :active? (= value (:range opts))
                      :on (parts/set-option :range value)
                      :data-role (str "portfolio-optimizer-tear-sheet-range-" (name value))}))]]
     (chart-view/chart-plot chart)
     [:div {:class ["mt-2"]} (chart-view/chart-legend series)]
     (parts/live-strip model)
     [:div {:class ["mt-3" "flex" "flex-wrap" "items-center" "gap-2" "border-t" "border-base-300" "pt-3"]}
      [:span {:class ["mr-1" "text-xs" "text-trading-muted"]} "Compare"]
      (for [{:keys [key label stroke on? available?]} (:benchmarks model)]
        ^{:key (str "benchmark-" (name key))}
        [:button {:type "button"
                  :aria-pressed (boolean on?)
                  :disabled (and on? (not available?))
                  :class (into ["inline-flex" "min-h-8" "items-center" "gap-1.5" "rounded-full" "border" "px-3" "text-xs"]
                               (if on?
                                 ["border-base-content/30" "text-trading-text"]
                                 ["border-dashed" "border-base-300" "text-trading-muted"]))
                  :data-role (str "portfolio-optimizer-tear-sheet-benchmark-" (name key))
                  :on {:click (parts/set-option :toggle-benchmark key)}}
         [:span {:class ["h-0.5" "w-3"] :style {:background-color stroke}}]
         (if (and on? (not available?)) (str label " · loading") label)])]]))

(defn- composition-aside
  [model opts]
  [:aside {:class ["flex" "min-w-0" "flex-[1_1_280px]" "flex-col" "rounded-xl" "border" "border-base-300" "bg-base-100/60"]
           :data-role "portfolio-optimizer-tear-sheet-composition"}
   [:div {:class ["flex" "items-baseline" "justify-between" "border-b" "border-base-300" "px-4" "py-3"]}
    [:span {:class ["text-sm" "font-medium" "text-trading-text"]} "Composition"]
    [:span {:class ["text-xs" "text-trading-muted"]} "target weights"]]
   [:div {:class ["flex" "flex-col" "gap-2" "px-4" "py-3"]}
    (for [{:keys [id label weight]} (take 8 (:legs model))]
      ^{:key (str "weight-" id)}
      [:div {:class ["flex" "flex-col" "gap-1"]}
       [:div {:class ["flex" "justify-between" "gap-2" "text-xs"]}
        [:span {:class ["truncate" "text-trading-text"]} label]
        [:span {:class ["num" "text-trading-text"]} (str (.toFixed (* 100 weight) 1) "%")]]
       [:div {:class ["h-1" "rounded-full" "bg-base-300/60"]}
        [:div {:class ["h-1" "rounded-full" "bg-[#a493ff]"]
               :style {:width (str (min 100 (* 100 (/ (js/Math.abs weight)
                                                       (max 1e-9 (js/Math.abs (:weight (first (:legs model))))))))
                                   "%")}}]]])
    (let [rest-legs (drop 8 (:legs model))]
      [:span {:class ["text-xs" "text-trading-muted"]}
       (if (seq rest-legs)
         (str "+ " (count rest-legs) " smaller positions · "
              (.toFixed (* 100 (reduce + 0 (map :weight rest-legs))) 1) "%")
         "")])]
   [:div {:class ["mt-auto" "flex" "flex-col" "gap-1.5" "border-t" "border-base-300" "px-4" "py-3" "text-xs"]}
    [:div {:class ["flex" "justify-between" "gap-2"]}
     [:span {:class ["text-trading-muted"]} "Rebalancing"]
     [:span {:class ["text-trading-text"]} (case (:rebalance opts) :monthly "Monthly" :hold "Deposit and hold" "Daily, to target")]]
    [:div {:class ["flex" "justify-between" "gap-2"]}
     [:span {:class ["text-trading-muted"]} "Before an asset existed"]
     [:span {:class ["text-trading-text"]} (case (:missing opts) :cash "Held as cash" :common "Start when all exist" "Spread over live assets")]]
    [:div {:class ["flex" "justify-between" "gap-2"]}
     [:span {:class ["text-trading-muted"]} "Leader profit share"]
     [:span {:class ["text-trading-text"]} (if (:profit-share? opts) "Deducted" "Not deducted")]]
    [:div {:class ["flex" "justify-between" "gap-2"]}
     [:span {:class ["text-trading-muted"]} "Coarsest sampling"]
     [:span {:class ["num" "text-trading-text"]} (parts/spacing-label (:max-gap-days model))]]
    [:button {:type "button"
              :class ["mt-2" "min-h-9" "rounded-md" "border" "border-base-300" "text-sm" "text-trading-text" "hover:bg-base-200/40"]
              :data-role "portfolio-optimizer-tear-sheet-change-method"
              :on {:click (parts/set-option :tab :method)}}
     "Change method"]]])

(defn- tabs
  [model opts]
  [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/60"]}
   [:div {:role "tablist" :class ["flex" "flex-wrap" "gap-1" "border-b" "border-base-300" "px-3"]}
    (for [[value label] [[:metrics "Performance metrics"] [:holdings "Holdings"] [:method "Method & data"]]]
      ^{:key (str "tab-" (name value))}
      [:button {:type "button"
                :role "tab"
                :aria-selected (= value (:tab opts))
                :class (into ["min-h-11" "border-b-2" "px-3" "text-sm"]
                             (if (= value (:tab opts))
                               ["border-[#a493ff]" "text-trading-text"]
                               ["border-transparent" "text-trading-muted" "hover:text-trading-text"]))
                :data-role (str "portfolio-optimizer-tear-sheet-tab-" (name value))
                :on {:click (parts/set-option :tab value)}}
       label])]
   (case (:tab opts)
     :holdings (parts/holdings-panel model)
     :method [:div {:class ["divide-y" "divide-base-300"]}
              (parts/method-panel opts)
              (parts/coverage-panel model)]
     [:div {:class ["flex" "min-h-[420px]" "flex-col"] :data-role "portfolio-optimizer-tear-sheet-metrics"}
      (metrics-view/performance-metrics-card
       (assoc (:metrics model) :vault-label "This target"))])])

(defn tear-sheet-view
  [state route]
  (let [{:keys [scenario-id loading? result scenario-name] :as detail}
        (optimizer-view-model/scenario-detail-model state route)
        state* (:state detail)
        opts (tear-sheet/options state)]
    [:section {:class ["portfolio-optimizer" "space-y-0" "leading-4" "text-trading-text"]
               :data-role "portfolio-optimizer-tear-sheet"}
     (cond
       loading?
       (message scenario-id "Loading scenario" "The tear sheet appears once this scenario's run is loaded.")

       (not (tear-sheet/vault-run? result))
       (message scenario-id "No hypothetical tear sheet for this run"
                "The tear sheet is available for solved runs that include at least one vault.")

       :else
       (if-let [model (tear-sheet/backtest-model state* result opts)]
         (let [label (range-label (:range opts))]
           [:div {:class ["mx-auto" "flex" "max-w-[1392px]" "flex-col" "gap-4" "px-4" "py-4"]}
            [:section {:class ["rounded-2xl" "border" "border-[#a493ff]/30" "bg-base-100/60" "px-5" "py-4"]}
             [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-4"]}
              [:div {:class ["min-w-0"]}
               [:p {:class ["text-xs" "text-trading-muted"]} "Optimizer › " scenario-name " › Hypothetical tear sheet"]
               [:div {:class ["mt-2" "flex" "flex-wrap" "items-center" "gap-3"]}
                [:h1 {:class ["text-3xl" "font-semibold" "tracking-[-0.02em]"]} scenario-name]
                [:span {:class card/hypothetical-badge-classes
                        :data-role "portfolio-optimizer-tear-sheet-badge"}
                 "Hypothetical"]]
               [:p {:class ["mt-2" "max-w-3xl" "text-sm" "leading-6" "text-trading-muted"]}
                (str (count (:legs model)) " assets held at the optimizer's target weights. "
                     "A backtest built from each asset's published history — not a vault, and not something you can deposit into.")]]
              [:button {:type "button"
                        :class ["min-h-10" "rounded-md" "border" "border-base-300" "px-4" "text-sm" "text-trading-text" "hover:bg-base-200/40"]
                        :data-role "portfolio-optimizer-tear-sheet-back"
                        :on {:click (back-actions scenario-id)}}
               "Back to recommendation"]]
             (parts/hero-stats model (tear-sheet/hero-stats model) label (:sparse? model))]
            [:div {:role "note"
                   :class ["flex" "items-start" "gap-3" "rounded-lg" "border" "border-[#e2b545]/40" "bg-[#e2b545]/10"
                           "px-4" "py-3" "text-sm" "leading-6" "text-[#efe1b5]"]
                   :data-role "portfolio-optimizer-tear-sheet-in-sample"}
             [:span
              "In-sample: the optimizer chose these weights using this same history, so the past will look better than the future is likely to. "
              (let [joined (count (filter :joins-in-window? (:legs model)))]
                (if (pos? joined)
                  (str joined " of " (count (:legs model)) " assets start inside this range.")
                  "Every asset has history across this range."))]]
            [:div {:class ["flex" "flex-wrap" "items-stretch" "gap-4"]}
             (composition-aside model opts)
             (chart-section model opts)]
            (tabs model opts)])
         (message scenario-id "Not enough history"
                  "None of this run's assets has history in the selected range yet. Try a longer range.")))]))
