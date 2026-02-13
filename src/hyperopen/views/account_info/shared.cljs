(ns hyperopen.views.account-info.shared
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.projections :as projections]))

(def ^:private fallback-decimals
  2)

(def ^:private max-decimals
  8)

(defn parse-num [value]
  (fmt/safe-number value))

(defn parse-optional-num [value]
  (projections/parse-optional-num value))

(defn parse-optional-int [value]
  (projections/parse-optional-int value))

(defn title-case-label [value]
  (projections/title-case-label value))

(defn non-blank-text [value]
  (projections/non-blank-text value))

(defn parse-coin-namespace [coin]
  (projections/parse-coin-namespace coin))

(defn resolve-coin-display [coin market-by-key]
  (projections/resolve-coin-display coin market-by-key))

(defn format-currency [value]
  (fmt/format-fixed-number value fallback-decimals))

(defn format-trade-price [value]
  (if (or (nil? value) (= value "N/A"))
    "0.00"
    (let [num-val (js/parseFloat value)]
      (if (js/isNaN num-val)
        "0.00"
        (or (fmt/format-trade-price num-val value) "0.00")))))

(defn format-amount [value decimals]
  (let [safe-decimals (-> (or decimals fallback-decimals)
                          (max 0)
                          (min max-decimals))]
    (fmt/format-fixed-number value safe-decimals)))

(defn format-balance-amount [value decimals]
  (if decimals
    (format-amount value decimals)
    (format-currency value)))

(defn format-open-orders-time [ms]
  (fmt/format-local-date-time ms))

(defn format-funding-history-time [time-ms]
  (fmt/format-local-date-time time-ms))

(defn format-pnl [pnl-value pnl-pct]
  (if (and (some? pnl-value) (some? pnl-pct))
    (let [pnl-num (parse-num pnl-value)
          pct-num (parse-num pnl-pct)
          color-class (cond
                        (pos? pnl-num) "text-success"
                        (neg? pnl-num) "text-error"
                        :else "text-trading-text")]
      [:span {:class [color-class "num"]}
       (str (if (pos? pnl-num) "+" "")
            "$" (format-currency pnl-num)
            " (" (if (pos? pct-num) "+" "") (.toFixed pct-num 2) "%)")])
    [:span.text-trading-text "--"]))

(def position-chip-classes
  ["px-1.5"
   "py-0.5"
   "text-xs"
   "leading-none"
   "font-medium"
   "rounded"
   "border"
   "bg-emerald-500/20"
   "text-emerald-300"
   "border-emerald-500/30"])

(def position-coin-cell-style
  {:background "linear-gradient(90deg, rgb(31, 166, 125) 0px, rgb(31, 166, 125) 4px, rgb(11, 50, 38) 4px, transparent 100%) transparent"
   :padding-left "12px"})

(def positions-grid-template-class
  "grid-cols-[minmax(170px,1.9fr)_minmax(130px,1.2fr)_minmax(110px,1fr)_minmax(110px,1fr)_minmax(110px,1fr)_minmax(130px,1.3fr)_minmax(110px,1fr)_minmax(100px,1fr)_minmax(100px,1fr)_minmax(80px,0.8fr)]")

(def positions-grid-min-width-class
  "min-w-[1140px]")
