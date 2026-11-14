(ns hyperopen.views.portfolio.vaults-summary
  "Summary-card rows for the Vaults scope: the selected range's vault PNL and
  drawdown, then the lifetime vault figures. Unknown is `--`, never zero."
  (:require [hyperopen.views.portfolio.format :as portfolio-format]
            [hyperopen.views.portfolio.low-confidence :as low-confidence]))

(defn- row
  [label value value-class data-role]
  [:div {:class ["summary-kv-row" "grid" "grid-cols-[1fr_auto]" "items-center" "gap-3"]
         :data-role data-role}
   (if (vector? label)
     label
     [:span {:class ["text-sm" "text-trading-text-secondary"]} label])
   [:span {:class (into ["num" "text-sm" "text-trading-text"] value-class)}
    value]])

(defn- known-currency
  [value]
  (if (number? value)
    (portfolio-format/format-currency value)
    "--"))

(defn- signed-currency
  "[text classes]; `--` for an unknown value."
  [value colored?]
  (if (number? value)
    [(str (cond (pos? value) "+" (neg? value) "-" :else "")
          (portfolio-format/format-currency (js/Math.abs value)))
     (if colored?
       [(cond (pos? value) "text-success"
              (neg? value) "text-error"
              :else "text-trading-text")]
       [])]
    ["--" []]))

(defn- money-weighted-return
  [value]
  (if (and (number? value) (js/isFinite value))
    (str (cond (pos? value) "+" (neg? value) "-" :else "")
         (.toFixed (* 100 (js/Math.abs value)) 1)
         "% / yr")
    "--"))

(defn- vaults-held
  [{:keys [open-count closed-count]}]
  (if (and (number? open-count) (number? closed-count))
    (str open-count " open · " closed-count " closed")
    "--"))

(defn- muted-line
  [data-role text]
  [:div {:class ["text-xs" "text-trading-text-secondary"]
         :data-role data-role}
   text])

(defn- excluded-text
  [{:keys [incomplete-count]}]
  (when (and (number? incomplete-count) (pos? incomplete-count))
    (str incomplete-count (if (= 1 incomplete-count) " vault" " vaults")
         " excluded: incomplete history")))

(defn- status-lines
  [{:keys [status partial? ledger-partial? refresh-error? totals]}]
  (case status
    :loading [(muted-line "portfolio-vaults-status" "Loading vault history…")]
    :error [(muted-line "portfolio-vaults-status" "Vault history could not be loaded.")]
    :empty [(muted-line "portfolio-vaults-status" "No vault deposits found for this account.")]
    :ready (cond-> []
             refresh-error?
             (conj (muted-line "portfolio-vaults-refresh-error"
                               "Couldn't refresh vault history. Showing the last loaded figures."))
             ledger-partial?
             (conj (muted-line "portfolio-vaults-partial"
                               "Vault history may be incomplete: the ledger is too long to load in full."))
             (and partial? (not ledger-partial?))
             (conj (muted-line "portfolio-vaults-partial"
                               "Some vault histories failed to load; figures may be incomplete."))
             (excluded-text totals)
             (conj (muted-line "portfolio-vaults-excluded" (excluded-text totals))))
    []))

(defn- lifetime-rows
  [{:keys [totals]}]
  (let [[vault-pnl vault-pnl-class] (signed-currency (:pnl totals) true)
        [realized _] (signed-currency (:realized totals) false)
        [unrealized _] (signed-currency (:unrealized totals) false)]
    [[:div {:class ["border-t" "border-base-300"]
            :data-role "portfolio-vaults-divider"}]
     [:div {:class ["text-xs" "text-trading-text-secondary"]
            :data-role "portfolio-vaults-all-time-heading"}
      "All-time"]
     (row "Vault PNL" vault-pnl vault-pnl-class "portfolio-vaults-pnl")
     (row "Realized" realized [] "portfolio-vaults-realized")
     (row "Unrealized" unrealized [] "portfolio-vaults-unrealized")
     (row "Money-weighted Return"
          (money-weighted-return (:money-weighted-return totals))
          []
          "portfolio-vaults-mwr")
     (row "Vault Equity" (known-currency (:value totals)) [] "portfolio-vaults-equity")
     (row "Net Deposited" (known-currency (:net-deposited totals)) [] "portfolio-vaults-net-deposited")
     (row "Vaults Held" (vaults-held totals) [] "portfolio-vaults-held")]))

(defn- drawdown-label
  [estimated?]
  [:span {:class ["inline-flex" "items-center" "gap-1" "text-sm"
                  (if estimated? "text-ho-text-muted" "text-trading-text-secondary")]}
   "Max Drawdown"
   (when estimated?
     (low-confidence/estimated-mark "portfolio-vaults-drawdown-estimated-mark"))])

(defn rows
  "Card body children for the Vaults scope. `vaults` is the view model's
  `:vaults-scope` map."
  [{:keys [status pnl max-drawdown-pct estimated?] :as vaults}]
  (let [[pnl-text pnl-class] (signed-currency pnl true)]
    (if (= :empty status)
      (status-lines vaults)
      (into [(row "PNL" pnl-text pnl-class "portfolio-vaults-range-pnl")
             (row (drawdown-label estimated?)
                  (if (number? max-drawdown-pct)
                    (portfolio-format/format-drawdown max-drawdown-pct)
                    "--")
                  []
                  "portfolio-vaults-drawdown")]
            (concat (lifetime-rows vaults)
                    (status-lines vaults))))))
