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

(defn- summary-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
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

(defn- exposure-row
  [idx binding-instrument-ids instrument-id capital-usd current-weight target-weight]
  (let [current-notional (* (or capital-usd 0) (or current-weight 0))
        target-notional (* (or capital-usd 0) (or target-weight 0))
        delta (- (or target-weight 0) (or current-weight 0))
        binding? (contains? binding-instrument-ids instrument-id)]
    (row-shell-with-attrs
     {:data-role (str "portfolio-optimizer-target-exposure-row-" idx)
      :data-binding (when binding? "true")
      :extra-class (when binding?
                     ["border-warning/60" "bg-warning/10"])}
     [:span {:class ["font-semibold" "text-trading-text"]} instrument-id]
     [:span (format-pct current-weight)]
     [:span (format-pct target-weight)]
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

(defn- diagnostics-panel
  [result]
  (let [diagnostics (:diagnostics result)
        bindings (:binding-constraints diagnostics)]
    (panel-shell
     "portfolio-optimizer-diagnostics-panel"
     "Diagnostics"
     "Engine diagnostics are rendered from the run result, not recomputed in the view."
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary-card "Gross" (format-pct (:gross-exposure diagnostics)))
      (summary-card "Net" (format-pct (:net-exposure diagnostics)))
      (summary-card "Effective N" (format-decimal (:effective-n diagnostics)))
      (summary-card "Turnover" (format-pct (:turnover diagnostics)))]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Binding Constraints"]
      (if (seq bindings)
        (into [:div {:class ["mt-2" "space-y-2"]}]
              (map binding-constraint-row bindings))
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         "No binding constraints reported."])])))

(defn- rebalance-row
  [row]
  (row-shell
   [:span {:class ["font-semibold" "text-trading-text"]} (:instrument-id row)]
   [:span (keyword-label (:status row))]
   [:span (keyword-label (:side row))]
   [:span (format-usdc (:delta-notional-usd row))]
   [:span (keyword-label (:reason row))]))

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
     (row-shell
      [:span {:class ["font-semibold" "text-trading-muted"]} "Instrument"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Status"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Side"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Delta"]
      [:span {:class ["font-semibold" "text-trading-muted"]} "Reason"])
     (map rebalance-row (:rows preview)))))

(defn results-panel
  ([last-successful-run]
   (results-panel last-successful-run nil))
  ([last-successful-run draft]
   (let [result (:result last-successful-run)]
     (when (= :solved (:status result))
       [:section {:class ["space-y-4"]
                  :data-role "portfolio-optimizer-results-surface"}
        [:div {:class ["grid" "grid-cols-2" "gap-3" "lg:grid-cols-4"]}
         (summary-card "Expected Return" (format-pct (:expected-return result)))
         (summary-card "Volatility" (format-pct (:volatility result)))
         (summary-card "Return Model" (keyword-label (:return-model result)))
         (summary-card "Risk Model" (keyword-label (:risk-model result)))]
        (target-exposure-table result)
        (frontier-chart/frontier-chart draft result)
        (return-decomposition result)
        (diagnostics-panel result)
        (rebalance-preview result)]))))
