(ns hyperopen.views.portfolio.optimize.results-panel
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.instrument-labels :as instrument-labels]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.frontier-chart :as frontier-chart]
            [hyperopen.views.portfolio.optimize.target-exposure-table :as target-exposure-table]))

(def ^:private vault-instrument-prefix
  "vault:")

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- vault-address-from-instrument-id
  [instrument-id]
  (let [text (non-blank-text instrument-id)
        lower (some-> text str/lower-case)]
    (when (and lower
               (str/starts-with? lower vault-instrument-prefix))
      (subs lower (count vault-instrument-prefix)))))

(defn- raw-vault-label?
  [instrument-id label]
  (let [address (vault-address-from-instrument-id instrument-id)
        label* (some-> (non-blank-text label) str/lower-case)]
    (boolean
     (and address
          (or (nil? label*)
              (= label* address)
              (= label* (str vault-instrument-prefix address)))))))

(defn- display-label
  [instrument-id label]
  (when-not (raw-vault-label? instrument-id label)
    (non-blank-text label)))

(defn- instrument-label
  [labels-by-instrument instrument-id]
  (if (vault-address-from-instrument-id instrument-id)
    (or (display-label instrument-id (get labels-by-instrument instrument-id))
        (str instrument-id))
    (str instrument-id)))

(defn- enrich-result-labels
  [result draft]
  (if (map? result)
    (let [instrument-ids (vec (:instrument-ids result))
          result-labels (or (:labels-by-instrument result) {})
          draft-labels (when (seq (:universe draft))
                         (instrument-labels/labels-by-instrument (:universe draft)
                                                                 instrument-ids))
          merged-labels (into result-labels
                              (keep (fn [instrument-id]
                                      (let [existing-label (get result-labels instrument-id)
                                            draft-label (display-label
                                                         instrument-id
                                                         (get draft-labels instrument-id))]
                                        (when (and draft-label
                                                   (or (nil? (non-blank-text existing-label))
                                                       (raw-vault-label? instrument-id existing-label)))
                                          [instrument-id draft-label]))))
                              instrument-ids)]
      (assoc result :labels-by-instrument merged-labels))
    result))

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

(defn- binding-constraint-row
  [labels-by-instrument binding]
  [:div {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]}
   [:span {:class ["font-semibold"]}
    (instrument-label labels-by-instrument (:instrument-id binding))]
   [:span {:class ["ml-2"]} (opt-format/keyword-label (:constraint binding))]])

(defn- sensitivity-row
  [labels-by-instrument [instrument-id row]]
  [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-200/40" "p-2" "text-xs"]
         :data-role (str "portfolio-optimizer-sensitivity-row-" instrument-id)}
   [:span {:class ["font-semibold"]} (instrument-label labels-by-instrument instrument-id)]
   [:span {:class ["ml-2" "text-trading-muted"]}
    (str "Base " (opt-format/format-pct (:base-expected-return row))
         " / Down " (opt-format/format-pct (:down-expected-return row))
         " / Up " (opt-format/format-pct (:up-expected-return row)))]])

(defn- warning-row
  [warning]
  [:p {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]
       :data-role "portfolio-optimizer-result-warning"}
   [:span {:class ["font-semibold"]} (opt-format/keyword-label (:code warning))]
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
        labels-by-instrument (or (:labels-by-instrument result) {})
        bindings (:binding-constraints diagnostics)
        conditioning (:covariance-conditioning diagnostics)
        sensitivity (:weight-sensitivity-by-instrument diagnostics)]
    (panel-shell
     "portfolio-optimizer-diagnostics-panel"
     "Diagnostics"
     "Engine diagnostics are rendered from the run result, not recomputed in the view."
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary-card "Gross" (opt-format/format-pct (:gross-exposure diagnostics)))
      (summary-card "Net" (opt-format/format-pct (:net-exposure diagnostics)))
      (summary-card "Effective N" (opt-format/format-decimal (:effective-n diagnostics)))
      (summary-card "Turnover" (opt-format/format-pct (:turnover diagnostics)))]
     [:div {:class ["grid" "grid-cols-1" "gap-2" "lg:grid-cols-3"]}
      (summary-card "Condition" (opt-format/keyword-label (:status conditioning)))
      (summary-card "Condition #"
                    (opt-format/format-decimal (:condition-number conditioning)))
      (summary-card "Min Eigen"
                    (opt-format/format-decimal (:min-eigenvalue conditioning)))]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Binding Constraints"]
      (if (seq bindings)
        (into [:div {:class ["mt-2" "space-y-2"]}]
              (map (partial binding-constraint-row labels-by-instrument) bindings))
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         "No binding constraints reported."])]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
            :data-role "portfolio-optimizer-sensitivity-panel"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Weight Sensitivity"]
      (if (seq sensitivity)
        (into [:div {:class ["mt-2" "space-y-2"]}]
              (map (partial sensitivity-row labels-by-instrument) sensitivity))
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         "No sensitivity diagnostics reported."])])))

(defn- status-token
  [status]
  (case status
    :ok {:label "ok" :class "text-trading-green"}
    :healthy {:label "ok" :class "text-trading-green"}
    :warning {:label "caution" :class "text-warning"}
    :caution {:label "caution" :class "text-warning"}
    :ill-conditioned {:label "caution" :class "text-warning"}
    :singular {:label "bad" :class "text-trading-red"}
    {:label (opt-format/keyword-label status) :class "text-trading-muted"}))

(defn- top-sensitivity
  [sensitivity]
  (when (seq sensitivity)
    (let [row-span (fn [row]
                     (js/Math.abs
                      (- (or (:up-weight row) (:up-expected-return row) 0)
                         (or (:down-weight row) (:down-expected-return row) 0))))
          [instrument-id row] (->> sensitivity
                                   (sort-by (fn [[_ row*]]
                                              (- (row-span row*))))
                                   first)]
      {:instrument-id instrument-id
       :span (row-span row)})))

(defn- trust-row
  [{:keys [label status value subtext]}]
  (let [{status-label :label status-class :class} (status-token status)]
    [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
      [:span {:class ["text-[0.62rem]" "font-semibold" "uppercase" "tracking-[0.06em]" "text-trading-muted"]}
       label]
      [:span {:class [status-class "text-[0.62rem]" "font-semibold" "uppercase"]}
       (str "● " status-label)]]
     [:p {:class ["mt-1" "font-mono" "text-base" "font-semibold" "tabular-nums" "text-trading-text"]}
      value]
     [:p {:class ["mt-0.5" "text-[0.64rem]" "text-trading-muted/70"]}
      subtext]]))

(defn- trust-diagnostics-rail
  [result]
  (let [diagnostics (:diagnostics result)
        conditioning (:covariance-conditioning diagnostics)
        sensitivity (:weight-sensitivity-by-instrument diagnostics)
        sensitivity-top (top-sensitivity sensitivity)
        effective-n (:effective-n diagnostics)
        universe-size (count (:instrument-ids result))
        conditioning-status (or (:status conditioning) :ok)
        weight-stability-status (if sensitivity-top :caution :ok)]
    [:aside {:class ["min-h-0" "border-l" "border-base-300" "bg-base-100/95"]
             :data-role "portfolio-optimizer-trust-caution-panel"}
     [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
      [:p {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
       "How much to trust this"]]
     [:div {:data-role "portfolio-optimizer-diagnostics-panel"}
      (trust-row {:label "Conditioning"
                  :status conditioning-status
                  :value (if (= :ok conditioning-status) "Healthy" (opt-format/keyword-label conditioning-status))
                  :subtext "Correlation matrix is checked before weights are accepted."})
      (trust-row {:label "Diversification"
                  :status :ok
                  :value (str "Effective N · " (opt-format/format-effective-n effective-n universe-size) " of " universe-size)
                  :subtext "Higher effective N means less concentration in one name."})
      (trust-row {:label "Weight Stability"
                  :status weight-stability-status
                  :value (if sensitivity-top "Moderate" "Stable")
                  :subtext (if sensitivity-top
                            (str (instrument-label (:labels-by-instrument result)
                                                   (:instrument-id sensitivity-top))
                                 " is most sensitive (±"
                                 (opt-format/format-pct (/ (:span sensitivity-top) 2))
                                 ").")
                            "No material sensitivity flags reported.")})
      (when (seq (:warnings result))
        [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]
               :data-role "portfolio-optimizer-result-warnings"}
         [:p {:class ["text-[0.62rem]" "font-semibold" "uppercase" "tracking-[0.06em]" "text-warning"]}
          "Warnings"]
         (into [:div {:class ["mt-2" "space-y-2"]}]
               (map warning-row (:warnings result)))])
      [:details {:class ["border-b" "border-base-300"]}
       [:summary {:class ["cursor-pointer" "px-4" "py-3" "font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
        "More Diagnostics"]
       [:div {:class ["space-y-2" "px-4" "pb-4"]}
        (summary-card "Gross" (opt-format/format-pct (:gross-exposure diagnostics)))
        (summary-card "Net" (opt-format/format-pct (:net-exposure diagnostics)))
        (summary-card "Turnover" (opt-format/format-pct (:turnover diagnostics)))
        (summary-card "Condition #" (opt-format/format-decimal (:condition-number conditioning)))]]]]))

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
    (if (opt-format/finite-number? observations)
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
      :else (opt-format/keyword-label (first sources)))))

(defn- assumptions-strip
  [draft result]
  (let [objective-kind (or (get-in draft [:objective :kind])
                           (get-in result [:solver :objective-kind]))]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-assumptions-strip"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Run Assumptions"]
     [:div {:class ["mt-3" "grid" "grid-cols-2" "gap-2" "xl:grid-cols-5"]}
      (compact-fact "Objective" (opt-format/keyword-label objective-kind))
      (compact-fact "Return Model" (opt-format/keyword-label (:return-model result)))
      (compact-fact "Risk Model" (opt-format/keyword-label (:risk-model result)))
      (compact-fact "Lookback" (history-lookback-label result))
      (compact-fact "Funding" (funding-assumption-label result))]]))

(defn- condition-caution
  [conditioning]
  (let [status (:status conditioning)]
    (when (and status
               (not= :ok status))
      {:code status
       :message (str "Covariance conditioning is " (opt-format/keyword-label status) ".")})))

(defn- blocked-reason-summary
  [preview]
  (let [counts (frequencies
                (keep (fn [row]
                        (when (= :blocked (:status row))
                          (:reason row)))
                      (:rows preview)))]
    (when (seq counts)
      (->> counts
           (sort-by (fn [[reason _count]]
                      (opt-format/keyword-label reason)))
           (map (fn [[reason count]]
                  (str (opt-format/keyword-label reason) " x" count)))
           (str/join ", ")))))

(defn- preview-caution
  [preview]
  (when (contains? #{:blocked :partially-blocked} (:status preview))
    (let [reason-summary (blocked-reason-summary preview)]
      {:code (:status preview)
       :message (if (seq reason-summary)
                  (str "Blocked reasons: " reason-summary ".")
                  "Some rebalance rows are blocked or require manual handling.")})))

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
     (summary-card "Expected Return" (opt-format/format-pct (:expected-return result)))
     (summary-card "Volatility" (opt-format/format-pct (:volatility result)))
     (summary-card "In-sample Sharpe" (opt-format/format-decimal (:in-sample-sharpe performance)))
     (summary-card "Shrunk Sharpe" (opt-format/format-decimal (:shrunk-sharpe performance)))]))

(defn- rebalance-row
  [labels-by-instrument row]
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
   [:span {:class ["font-semibold" "text-trading-text"]}
    (instrument-label labels-by-instrument (:instrument-id row))]
   [:span (opt-format/keyword-label (:status row))]
   [:span (opt-format/keyword-label (:side row))]
   [:span (opt-format/format-decimal (:quantity row))]
   [:span (opt-format/format-usdc (:price row))]
   [:span (opt-format/keyword-label (get-in row [:cost :source]))]
   [:span (opt-format/format-usdc (get-in row [:cost :estimated-slippage-usd]))]
   [:span (opt-format/format-usdc (:delta-notional-usd row))]
   [:span (opt-format/keyword-label (:reason row))]])

(defn- rebalance-preview
  [result]
  (let [preview (:rebalance-preview result)
        labels-by-instrument (or (:labels-by-instrument result) {})
        summary (:summary preview)]
    (panel-shell
     "portfolio-optimizer-rebalance-preview"
     "Rebalance Preview"
     "Rows that cannot execute through the current trading stack remain visible instead of being dropped."
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary-card "Status" (opt-format/keyword-label (:status preview)))
      (summary-card "Ready" (str (or (:ready-count summary) 0)))
      (summary-card "Blocked" (str (or (:blocked-count summary) 0)))
      (summary-card "Gross Trade" (opt-format/format-usdc (:gross-trade-notional-usd summary)))]
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary-card "Fees" (opt-format/format-usdc (:estimated-fees-usd summary)))
      (summary-card "Slippage" (opt-format/format-usdc (:estimated-slippage-usd summary)))
      (summary-card "Margin After"
                    (opt-format/format-pct (get-in summary [:margin :after-utilization])))
      (summary-card "Margin Warning"
                    (opt-format/keyword-label (get-in summary [:margin :warning])))]
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
     (map (partial rebalance-row labels-by-instrument) (:rows preview)))))

(defn results-panel
  ([last-successful-run]
   (results-panel last-successful-run nil))
  ([last-successful-run draft]
   (results-panel last-successful-run draft nil))
  ([last-successful-run draft {:keys [stale? include-rebalance? frontier-overlay-mode
                                      constrain-frontier?]
                               :or {include-rebalance? true
                                    frontier-overlay-mode :standalone}}]
   (let [result (enrich-result-labels (:result last-successful-run) draft)]
     (when (= :solved (:status result))
       [:section {:class ["space-y-0" "leading-4"]
                  :data-role "portfolio-optimizer-results-surface"}
        (stale-result-banner stale?)
        [:div {:class ["grid" "grid-cols-1" "xl:grid-cols-[500px_minmax(0,1fr)_320px]"]
               :data-role "portfolio-optimizer-results-grid"}
         [:div {:class ["min-h-0" "space-y-0"]
                :data-role "portfolio-optimizer-results-left-panel"}
         (target-exposure-table/target-exposure-table result)]
         [:div {:class ["min-h-0" "bg-base-100" "p-6"]
                :data-role "portfolio-optimizer-results-center-panel"}
          (frontier-chart/frontier-chart
           draft
           result
           frontier-overlay-mode
           constrain-frontier?)]
         [:div {:class ["min-h-0"]
                :data-role "portfolio-optimizer-results-right-panel"}
          (trust-diagnostics-rail result)]]
        (when include-rebalance?
          (rebalance-preview result))]))))
