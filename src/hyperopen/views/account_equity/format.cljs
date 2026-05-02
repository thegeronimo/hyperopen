(ns hyperopen.views.account-equity.format
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]))

(defn parse-num [value]
  (cond
    (number? value) value
    (string? value) (let [s (str/trim value)
                          n (js/parseFloat s)]
                      (when (and (not (str/blank? s)) (not (js/isNaN n))) n))
    :else nil))

(defn safe-div [num denom]
  (when (and (number? num) (number? denom) (not (zero? denom)))
    (/ num denom)))

(defn display-currency [value]
  (if (number? value)
    (fmt/format-currency value)
    "--"))

(defn display-percent [ratio]
  (if (number? ratio)
    (str (fmt/safe-to-fixed (* ratio 100) 2) "%")
    "--"))

(defn display-leverage [ratio]
  (if (number? ratio)
    (str (fmt/safe-to-fixed ratio 2) "x")
    "--"))

(def ^:private unified-account-ratio-tooltip
  "Represents the risk of portfolio liquidation. When the value is greater than 95%, your portfolio may be liquidated.")

(def ^:private unified-account-leverage-tooltip
  "Unified Account Leverage = Total Cross Positions Value / Total Collateral Balance.")

(def ^:private tooltip-panel-position-classes
  {"top" ["bottom-full" "left-0" "mb-2"]
   "bottom" ["top-full" "left-0" "mt-2"]
   "left" ["right-full" "top-1/2" "-translate-y-1/2" "mr-2"]
   "right" ["left-full" "top-1/2" "-translate-y-1/2" "ml-2"]})

(def ^:private tooltip-arrow-position-classes
  {"top" ["top-full" "left-3" "border-t-gray-800"]
   "bottom" ["bottom-full" "left-3" "border-b-gray-800"]
   "left" ["left-full" "top-1/2" "-translate-y-1/2" "border-l-gray-800"]
   "right" ["right-full" "top-1/2" "-translate-y-1/2" "border-r-gray-800"]})

(defn- tooltip-position-classes
  [position]
  (or (get tooltip-panel-position-classes position)
      (throw (js/Error. (str "Unsupported tooltip position: " position)))))

(defn- tooltip-arrow-classes
  [position]
  (or (get tooltip-arrow-position-classes position)
      (throw (js/Error. (str "Unsupported tooltip position: " position)))))

(defn pnl-display [value]
  (if (number? value)
    (let [formatted (fmt/format-currency (js/Math.abs value))
          sign (cond
                 (pos? value) "+"
                 (neg? value) "-"
                 :else "")]
      {:text (str sign formatted)
       :class (cond
                (pos? value) "text-success"
                (neg? value) "text-error"
                :else "text-trading-text")})
    {:text "--" :class "text-trading-text-secondary"}))

(defn tooltip [trigger text & [position]]
  (let [pos (or position "top")]
    [:div.relative.inline-flex.group
     trigger
     [:div {:class (into ["absolute" "opacity-0" "group-hover:opacity-100" "transition-opacity" "duration-200"
                          "pointer-events-none" "z-50"]
                         (tooltip-position-classes pos))
            :style {:max-width "520px" :min-width "300px"}}
      [:div.bg-gray-800.text-gray-100.text-xs.rounded-md.px-3.py-2.spectate-lg.leading-snug.whitespace-normal
       text
       [:div {:class (into ["absolute" "w-0" "h-0" "border-4" "border-transparent"]
                           (tooltip-arrow-classes pos))}]]]]))

(defn label-with-tooltip [label tooltip-text]
  (tooltip
    [:span.text-sm.text-trading-text-secondary.border-b.border-dashed.border-gray-600.cursor-help
     label]
    tooltip-text
    "top"))

(defn default-metric-value-class [value]
  (if (= value "--")
    "text-trading-text-secondary"
    "text-trading-text"))

(defn metric-row [label value & {:keys [tooltip value-class]}]
  [:div.flex.items-center.justify-between.text-sm
   (if tooltip
     (label-with-tooltip label tooltip)
     [:span.text-sm.text-trading-text-secondary label])
   [:span {:class ["num" (or value-class (default-metric-value-class value))]}
    value]])
