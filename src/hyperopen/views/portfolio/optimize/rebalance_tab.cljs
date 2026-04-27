(ns hyperopen.views.portfolio.optimize.rebalance-tab
  (:require [clojure.string :as str]))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- format-usdc
  [value]
  (if (finite-number? value)
    (str "$" (.toLocaleString value
                              "en-US"
                              #js {:maximumFractionDigits 2}))
    "N/A"))

(defn- format-decimal
  [value]
  (if (finite-number? value)
    (.toLocaleString value "en-US" #js {:maximumFractionDigits 4})
    "N/A"))

(defn- format-pct
  [value]
  (if (finite-number? value)
    (str (.toLocaleString (* 100 value)
                          "en-US"
                          #js {:minimumFractionDigits 2
                               :maximumFractionDigits 2})
         "%")
    "N/A"))

(defn- keyword-label
  [value]
  (cond
    (keyword? value) (name value)
    (some? value) (str value)
    :else "N/A"))

(defn- instrument-group-key
  [instrument-id]
  (let [value (str instrument-id)
        unprefixed (last (str/split value #":"))
        base (first (str/split unprefixed #"[/-]"))]
    (if (seq base) base value)))

(defn- data-role-token
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- kpi-card
  [label value]
  [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]} value]])

(defn- blocked-reason-summary
  [preview]
  (let [reasons (->> (:rows preview)
                     (filter #(= :blocked (:status %)))
                     (keep :reason)
                     frequencies)]
    (when (seq reasons)
      (->> reasons
           (map (fn [[reason count]]
                  (str (keyword-label reason) " x" count)))
           (str/join ", ")))))

(defn- review-caution
  [preview]
  (let [reason-summary (blocked-reason-summary preview)]
    [:section {:class ["rounded-xl"
                       "border"
                       (if (seq reason-summary) "border-warning/50" "border-base-300")
                       (if (seq reason-summary) "bg-warning/10" "bg-base-100/95")
                       "p-4"]
               :data-role "portfolio-optimizer-rebalance-review-caution"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Review Posture"]
     [:p {:class ["mt-2" "text-sm" (if (seq reason-summary) "text-warning" "text-trading-muted")]}
      (if (seq reason-summary)
        (str "Blocked reasons: " reason-summary ". Ready rows can be reviewed separately from blocked/manual rows.")
        "Review only. No orders are sent until you open the execution review and explicitly confirm.")]]))

(defn- row-shell
  [attrs & cells]
  (into
   [:div (merge {:class ["grid"
                         "grid-cols-[minmax(8rem,1.1fr)_repeat(8,minmax(5rem,0.75fr))]"
                         "gap-3"
                         "rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200/40"
                         "p-3"
                         "text-xs"
                         "tabular-nums"]}
                attrs)]
   cells))

(defn- group-row
  [asset rows]
  (let [delta (reduce + 0 (map #(or (:delta-notional-usd %) 0) rows))
        ready (count (filter #(= :ready (:status %)) rows))
        blocked (count (filter #(= :blocked (:status %)) rows))]
    (row-shell
     {:class ["grid"
              "grid-cols-[minmax(8rem,1.1fr)_repeat(8,minmax(5rem,0.75fr))]"
              "gap-3"
              "rounded-lg"
              "border"
              "border-base-300"
              "bg-base-200/70"
              "p-3"
              "text-xs"
              "font-semibold"
              "tabular-nums"]
      :data-role (str "portfolio-optimizer-rebalance-group-" (data-role-token asset))}
     [:span asset]
     [:span (str ready " ready")]
     [:span (str blocked " blocked")]
     [:span "group"]
     [:span "N/A"]
     [:span "N/A"]
     [:span "N/A"]
     [:span (format-usdc delta)]
     [:span ""])) )

(defn- trade-row
  [row]
  (row-shell
   {:data-role (str "portfolio-optimizer-rebalance-row-"
                    (data-role-token (:instrument-id row)))}
   [:span {:class ["font-semibold" "text-trading-text"]} (:instrument-id row)]
   [:span (keyword-label (:status row))]
   [:span (keyword-label (:side row))]
   [:span (format-decimal (:quantity row))]
   [:span (format-usdc (:price row))]
   [:span (keyword-label (get-in row [:cost :source]))]
   [:span (format-usdc (get-in row [:cost :estimated-slippage-usd]))]
   [:span (format-usdc (:delta-notional-usd row))]
   [:span (keyword-label (:reason row))]))

(defn- trade-table
  [preview]
  (let [rows (vec (:rows preview))
        groups (group-by (comp instrument-group-key :instrument-id) rows)]
    [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-rebalance-preview"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Trade Review"]
     [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
      "Rows are grouped by asset. Blocked rows remain visible with their reason instead of being dropped."]
     [:div {:class ["mt-4"
                    "grid"
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
     (if (seq groups)
       (map (fn [[asset asset-rows]]
              [:details {:class ["mt-2" "space-y-2"]
                         :data-role (str "portfolio-optimizer-rebalance-asset-"
                                         (data-role-token asset))
                         :open true}
               [:summary {:class ["cursor-pointer" "list-none"]}
                (group-row asset asset-rows)]
               (map trade-row asset-rows)])
            groups)
       [:p {:class ["mt-4" "rounded-md" "border" "border-base-300" "bg-base-200/40" "p-3" "text-sm" "text-trading-muted"]}
        "No rebalance rows are available for this run."])]))

(defn rebalance-tab
  [last-successful-run]
  (let [result (:result last-successful-run)
        preview (:rebalance-preview result)
        summary (:summary preview)]
    (if (and (= :solved (:status result)) (map? preview))
      [:section {:class ["space-y-4"]
                 :data-role "portfolio-optimizer-rebalance-review-surface"}
       [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                  :data-role "portfolio-optimizer-rebalance-review-header"}
        [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
         [:div
          [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
           "Rebalance Preview"]
          [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
           "Review target deltas, blocked rows, cost assumptions, and cross-margin impact before staging execution."]]
         [:button {:type "button"
                   :class ["rounded-lg" "border" "border-primary/50" "bg-primary/10" "px-3" "py-2"
                           "text-left" "text-sm" "font-semibold" "text-primary"
                           "disabled:cursor-not-allowed" "disabled:border-base-300"
                           "disabled:bg-base-200/40" "disabled:text-trading-muted"]
                   :data-role "portfolio-optimizer-open-execution-modal"
                   :disabled (not (pos? (or (:ready-count summary) 0)))
                   :on {:click [[:actions/open-portfolio-optimizer-execution-modal]]}}
          "Review Execution"]]]
       [:section {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-6"]
                  :data-role "portfolio-optimizer-rebalance-summary-kpis"}
        (kpi-card "Status" (keyword-label (:status preview)))
        (kpi-card "Ready" (str (or (:ready-count summary) 0)))
        (kpi-card "Blocked" (str (or (:blocked-count summary) 0)))
        (kpi-card "Gross Trade" (format-usdc (:gross-trade-notional-usd summary)))
        (kpi-card "Fees" (format-usdc (:estimated-fees-usd summary)))
        (kpi-card "Slippage" (format-usdc (:estimated-slippage-usd summary)))]
       [:section {:class ["grid" "grid-cols-1" "gap-4" "xl:grid-cols-[minmax(0,1fr)_18rem]"]}
        (trade-table preview)
        [:aside {:class ["space-y-4"]}
         (review-caution preview)
         [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                    :data-role "portfolio-optimizer-rebalance-margin-context"}
          [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
           "Margin Context"]
          [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
           "Cross-margin impact is rendered when supplied by the preview builder."]
          [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2"]}
           (kpi-card "After Utilization" (format-pct (get-in summary [:margin :after-utilization])))
           (kpi-card "Warning" (keyword-label (get-in summary [:margin :warning])))]]]]]
      [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
                 :data-role "portfolio-optimizer-rebalance-empty"}
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
        "Rebalance Preview"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        "A rebalance preview is available after a successful optimization run."]])))
