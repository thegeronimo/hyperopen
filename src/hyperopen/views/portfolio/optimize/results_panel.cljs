(ns hyperopen.views.portfolio.optimize.results-panel
  (:require [hyperopen.views.portfolio.optimize.frontier-chart :as frontier-chart]))

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
                              #js {:maximumFractionDigits 2}))
    "N/A"))

(defn- keyword-label
  [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)
    :else "N/A"))

(defn- signed-label
  [value]
  (cond
    (and (finite-number? value) (neg? value)) "short"
    (and (finite-number? value) (pos? value)) "long"
    :else "flat"))

(defn- summary-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
    value]])

(defn- compact-fact
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "px-3" "py-2"]}
   [:p {:class ["text-[0.6rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-1" "text-sm" "font-semibold" "tabular-nums"]}
    value]])

(defn- panel-shell
  [data-role title subtitle & children]
  [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role data-role}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
    title]
   [:p {:class ["mt-2" "text-sm" "text-trading-muted"]} subtitle]
   (into [:div {:class ["mt-4" "space-y-2"]}]
         children)])

(defn- row-shell
  [& children]
  (into [:div {:class ["grid"
                       "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                       "gap-3"
                       "rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-200/40"
                       "p-3"
                       "text-xs"
                       "tabular-nums"]}]
        children))

(defn- row-shell-with-attrs
  [attrs & children]
  (let [base-class ["grid"
                    "grid-cols-[minmax(8rem,1.1fr)_repeat(4,minmax(5rem,0.8fr))]"
                    "gap-3"
                    "rounded-lg"
                    "border"
                    "border-base-300"
                    "bg-base-200/40"
                    "p-3"
                    "text-xs"
                    "tabular-nums"]
        attrs* (-> attrs
                   (dissoc :extra-class)
                   (assoc :class (into base-class (:extra-class attrs))))]
    (into [:div attrs*] children)))

(defn- signed-weight-cell
  [value]
  (let [sign (signed-label value)
        width (if (finite-number? value)
                (min 100 (* 100 (js/Math.abs value)))
                0)]
    [:span {:class ["space-y-1"]
            :data-sign sign}
     [:span {:class ["block"]} (format-pct value)]
     [:span {:class ["block" "h-1.5" "overflow-hidden" "rounded-full" "bg-base-300/50"]}
      [:span {:class (cond-> ["block" "h-full" "rounded-full"]
                       (= "long" sign) (conj "bg-primary/70")
                       (= "short" sign) (conj "bg-error/70")
                       (= "flat" sign) (conj "bg-trading-muted/40"))
              :style {:width (str width "%")}}]]]))

(defn- exposure-row
  [idx binding-instrument-ids instrument-id capital-usd current-weight target-weight]
  (let [current-notional (* (or capital-usd 0) (or current-weight 0))
        target-notional (* (or capital-usd 0) (or target-weight 0))
        delta (- (or target-weight 0) (or current-weight 0))
        binding? (contains? binding-instrument-ids instrument-id)]
    (row-shell-with-attrs
     {:data-role (str "portfolio-optimizer-target-exposure-row-" idx)
      :data-binding (when binding? "true")
      :data-current-sign (signed-label current-weight)
      :data-target-sign (signed-label target-weight)
      :extra-class (when binding?
                     ["border-warning/60" "bg-warning/10"])}
     [:span {:class ["font-semibold" "text-trading-text"]} instrument-id]
     (signed-weight-cell current-weight)
     (signed-weight-cell target-weight)
     [:span (format-pct delta)]
     [:span (format-usdc (- target-notional current-notional))])))

(defn- target-exposure-table
  [result]
  (let [capital-usd (get-in result [:rebalance-preview :capital-usd])
        ids (:instrument-ids result)
        current (:current-weights result)
        target (:target-weights result)
        binding-instrument-ids (set (keep :instrument-id
                                          (get-in result [:diagnostics :binding-constraints])))]
    (panel-shell
     "portfolio-optimizer-target-exposure-table"
     "Target Exposure"
     "Signed current-vs-target weights and notional deltas remain visible for long-only and signed portfolios."
     (row-shell
      [:span {:class ["font-semibold" "text-trading-muted"]} "Instrument"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Current"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Target"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Delta"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Notional"])
     (map-indexed (fn [idx [instrument-id current-weight target-weight]]
                    (exposure-row idx
                                  binding-instrument-ids
                                  instrument-id
                                  capital-usd
                                  current-weight
                                  target-weight))
                  (map vector ids current target)))))

(defn- decomposition-row
  [instrument-id decomposition]
  (row-shell
   [:span {:class ["font-semibold" "text-trading-text"]} instrument-id]
   [:span (format-pct (:return-component decomposition))]
   [:span (format-pct (:funding-component decomposition))]
   [:span (keyword-label (:funding-source decomposition))]
   [:span (format-pct (+ (or (:return-component decomposition) 0)
                         (or (:funding-component decomposition) 0)))]))

(defn- return-decomposition
  [result]
  (let [by-id (:return-decomposition-by-instrument result)]
    (panel-shell
     "portfolio-optimizer-return-decomposition"
     "Funding Decomposition"
     "Perp funding is shown separately from price-return estimates so expected returns are auditable."
     (row-shell
      [:span {:class ["font-semibold" "text-trading-muted"]} "Instrument"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Return"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Funding"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Source"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Total"])
     (map (fn [instrument-id]
            (decomposition-row instrument-id (get by-id instrument-id)))
          (:instrument-ids result)))))

(defn- binding-constraint-row
  [binding]
  [:div {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]}
   [:span {:class ["font-semibold"]} (:instrument-id binding)]
   [:span {:class ["ml-2"]} (keyword-label (:constraint binding))]])

(defn- sensitivity-row
  [[instrument-id row]]
  [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-200/40" "p-2" "text-xs"]
         :data-role (str "portfolio-optimizer-sensitivity-row-" instrument-id)}
   [:span {:class ["font-semibold"]} instrument-id]
   [:span {:class ["ml-2" "text-trading-muted"]}
    (str "Base " (format-pct (:base-expected-return row))
         " / Down " (format-pct (:down-expected-return row))
         " / Up " (format-pct (:up-expected-return row)))]])

(defn- warning-row
  [warning]
  [:p {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]
       :data-role "portfolio-optimizer-result-warning"}
   [:span {:class ["font-semibold"]} (keyword-label (:code warning))]
   (when-let [message (:message warning)]
     [:span {:class ["ml-2"]} message])])

(defn- warnings-panel
  [result]
  (when (seq (:warnings result))
    (panel-shell
     "portfolio-optimizer-result-warnings"
     "Result Warnings"
     "Warnings explain assumptions or mathematically valid outcomes that may require a rerun with different controls."
     (map warning-row (:warnings result)))))

(defn- diagnostics-panel
  [result]
  (let [diagnostics (:diagnostics result)
        bindings (:binding-constraints diagnostics)
        conditioning (:covariance-conditioning diagnostics)
        sensitivity (:weight-sensitivity-by-instrument diagnostics)]
    (panel-shell
     "portfolio-optimizer-diagnostics-panel"
     "Diagnostics"
     "Engine diagnostics are rendered from the run result, not recomputed in the view."
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary-card "Gross" (format-pct (:gross-exposure diagnostics)))
      (summary-card "Net" (format-pct (:net-exposure diagnostics)))
      (summary-card "Effective N" (format-decimal (:effective-n diagnostics)))
      (summary-card "Turnover" (format-pct (:turnover diagnostics)))]
     [:div {:class ["grid" "grid-cols-1" "gap-2" "lg:grid-cols-3"]}
      (summary-card "Condition" (keyword-label (:status conditioning)))
      (summary-card "Condition #"
                    (format-decimal (:condition-number conditioning)))
      (summary-card "Min Eigen"
                    (format-decimal (:min-eigenvalue conditioning)))]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Binding Constraints"]
      (if (seq bindings)
        (into [:div {:class ["mt-2" "space-y-2"]}]
              (map binding-constraint-row bindings))
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         "No binding constraints reported."])]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
            :data-role "portfolio-optimizer-sensitivity-panel"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Weight Sensitivity"]
      (if (seq sensitivity)
        (into [:div {:class ["mt-2" "space-y-2"]}]
              (map sensitivity-row sensitivity))
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         "No sensitivity diagnostics reported."])])))

(defn- stale-result-banner
  [stale?]
  (when stale?
    [:div {:class ["rounded-xl" "border" "border-warning/50" "bg-warning/10" "p-4"]
           :data-role "portfolio-optimizer-stale-result-banner"}
     [:div {:class ["flex" "flex-col" "gap-3" "md:flex-row" "md:items-center" "md:justify-between"]}
      [:div
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-warning"]}
        "Stale Result"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        "The setup draft has changed since the retained allocation was computed. Rerun before using these weights for execution."]]
      [:button {:type "button"
                :class ["rounded-lg" "border" "border-warning/60" "bg-warning/10" "px-3" "py-2"
                        "text-sm" "font-semibold" "text-warning" "hover:bg-warning/20"]
                :data-role "portfolio-optimizer-rerun-stale-result"
                :on {:click [[:actions/run-portfolio-optimizer-from-draft]]}}
       "Run Again"]]]))

(defn- history-lookback-label
  [result]
  (let [summary (:history-summary result)
        observations (:return-observations summary)]
    (if (finite-number? observations)
      (str observations " returns")
      "Loaded history")))

(defn- funding-assumption-label
  [result]
  (let [sources (->> (:return-decomposition-by-instrument result)
                     vals
                     (keep :funding-source)
                     set)]
    (cond
      (empty? sources) "No funding data"
      (= #{:not-applicable} sources) "Spot only"
      (contains? sources :market-funding-history) "Market funding"
      :else (keyword-label (first sources)))))

(defn- assumptions-strip
  [draft result]
  (let [objective-kind (or (get-in draft [:objective :kind])
                           (get-in result [:solver :objective-kind]))]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-assumptions-strip"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Run Assumptions"]
     [:div {:class ["mt-3" "grid" "grid-cols-2" "gap-2" "xl:grid-cols-5"]}
      (compact-fact "Objective" (keyword-label objective-kind))
      (compact-fact "Return Model" (keyword-label (:return-model result)))
      (compact-fact "Risk Model" (keyword-label (:risk-model result)))
      (compact-fact "Lookback" (history-lookback-label result))
      (compact-fact "Funding" (funding-assumption-label result))]]))

(defn- condition-caution
  [conditioning]
  (let [status (:status conditioning)]
    (when (and status
               (not= :ok status))
      {:code status
       :message (str "Covariance conditioning is " (keyword-label status) ".")})))

(defn- preview-caution
  [preview]
  (when (contains? #{:blocked :partially-blocked} (:status preview))
    {:code (:status preview)
     :message "Some rebalance rows are blocked or require manual handling."}))

(defn- trust-caution-panel
  [result]
  (let [cautions (vec (concat (take 3 (:warnings result))
                              (keep identity
                                    [(condition-caution
                                      (get-in result [:diagnostics :covariance-conditioning]))
                                     (preview-caution (:rebalance-preview result))])))]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-trust-caution-panel"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Trust & Caution"]
     [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
      "Use this run when assumptions are current, diagnostics are stable, and blocked execution rows are understood."]
     (if (seq cautions)
       (into [:div {:class ["mt-4" "space-y-2"]}]
             (map warning-row cautions))
       [:p {:class ["mt-4" "rounded-md" "border" "border-primary/30" "bg-primary/10" "p-2" "text-xs" "text-primary"]}
        "No caution flags reported for this run."])]))

(defn- performance-summary
  [result]
  (let [performance (:performance result)]
    [:div {:class ["grid" "grid-cols-2" "gap-2"]}
     (summary-card "Expected Return" (format-pct (:expected-return result)))
     (summary-card "Volatility" (format-pct (:volatility result)))
     (summary-card "In-sample Sharpe" (format-decimal (:in-sample-sharpe performance)))
     (summary-card "Shrunk Sharpe" (format-decimal (:shrunk-sharpe performance)))]))

(defn- rebalance-row
  [row]
  [:div {:class ["grid"
                 "grid-cols-[minmax(8rem,1.1fr)_repeat(8,minmax(5rem,0.75fr))]"
                 "gap-3"
                 "rounded-lg"
                 "border"
                 "border-base-300"
                 "bg-base-200/40"
                 "p-3"
                 "text-xs"
                 "tabular-nums"]
         :data-role (str "portfolio-optimizer-rebalance-row-" (:instrument-id row))}
   [:span {:class ["font-semibold" "text-trading-text"]} (:instrument-id row)]
   [:span (keyword-label (:status row))]
   [:span (keyword-label (:side row))]
   [:span (format-decimal (:quantity row))]
   [:span (format-usdc (:price row))]
   [:span (keyword-label (get-in row [:cost :source]))]
   [:span (format-usdc (get-in row [:cost :estimated-slippage-usd]))]
   [:span (format-usdc (:delta-notional-usd row))]
   [:span (keyword-label (:reason row))]])

(defn- rebalance-preview
  [result]
  (let [preview (:rebalance-preview result)
        summary (:summary preview)]
    (panel-shell
     "portfolio-optimizer-rebalance-preview"
     "Rebalance Preview"
     "Rows that cannot execute through the current trading stack remain visible instead of being dropped."
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary-card "Status" (keyword-label (:status preview)))
      (summary-card "Ready" (str (or (:ready-count summary) 0)))
      (summary-card "Blocked" (str (or (:blocked-count summary) 0)))
      (summary-card "Gross Trade" (format-usdc (:gross-trade-notional-usd summary)))]
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary-card "Fees" (format-usdc (:estimated-fees-usd summary)))
      (summary-card "Slippage" (format-usdc (:estimated-slippage-usd summary)))
      (summary-card "Margin After"
                    (format-pct (get-in summary [:margin :after-utilization])))
      (summary-card "Margin Warning"
                    (keyword-label (get-in summary [:margin :warning])))]
     [:button {:type "button"
               :class ["rounded-lg" "border" "border-primary/50" "bg-primary/10" "px-3" "py-2"
                       "text-left" "text-sm" "font-semibold" "text-primary"
                       "disabled:cursor-not-allowed" "disabled:border-base-300"
                       "disabled:bg-base-200/40" "disabled:text-trading-muted"]
               :data-role "portfolio-optimizer-open-execution-modal"
               :disabled (not (pos? (or (:ready-count summary) 0)))
               :on {:click [[:actions/open-portfolio-optimizer-execution-modal]]}}
      "Review Execution"]
     [:div {:class ["grid"
                    "grid-cols-[minmax(8rem,1.1fr)_repeat(8,minmax(5rem,0.75fr))]"
                    "gap-3"
                    "rounded-lg"
                    "border"
                    "border-base-300"
                    "bg-base-200/40"
                    "p-3"
                    "text-xs"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.14em]"
                    "text-trading-muted"]}
      [:span "Instrument"]
      [:span "Status"]
      [:span "Side"]
      [:span "Size"]
      [:span "Price"]
      [:span "Cost Source"]
      [:span "Slippage"]
      [:span "Delta"]
      [:span "Reason"]]
     (map rebalance-row (:rows preview)))))

(defn results-panel
  ([last-successful-run]
   (results-panel last-successful-run nil))
  ([last-successful-run draft]
   (results-panel last-successful-run draft nil))
  ([last-successful-run draft {:keys [stale?]}]
   (let [result (:result last-successful-run)]
     (when (= :solved (:status result))
       [:section {:class ["space-y-4"]
                  :data-role "portfolio-optimizer-results-surface"}
        (stale-result-banner stale?)
        (assumptions-strip draft result)
        [:div {:class ["grid" "grid-cols-1" "gap-4" "2xl:grid-cols-[minmax(22rem,1.1fr)_minmax(22rem,1fr)_minmax(18rem,0.9fr)]"]
               :data-role "portfolio-optimizer-results-grid"}
         [:div {:class ["space-y-4"]
                :data-role "portfolio-optimizer-results-left-panel"}
          (target-exposure-table result)
          (return-decomposition result)]
         [:div {:class ["space-y-4"]
                :data-role "portfolio-optimizer-results-center-panel"}
          (performance-summary result)
          (frontier-chart/frontier-chart draft result)]
         [:div {:class ["space-y-4"]
                :data-role "portfolio-optimizer-results-right-panel"}
          (trust-caution-panel result)
          (warnings-panel result)
          (diagnostics-panel result)]]
        (rebalance-preview result)]))))
