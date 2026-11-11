(ns hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.presentation
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.shared :as account-shared]
            [hyperopen.views.trading-chart.utils.chart-interop.open-order-overlays.support :as support]))

(def ^:private buy-line-color "rgba(34, 201, 151, 0.85)")
(def ^:private sell-line-color "rgba(227, 95, 120, 0.85)")
(def ^:private buy-badge-color "rgba(34, 201, 151, 0.14)")
(def ^:private sell-badge-color "rgba(227, 95, 120, 0.14)")
(def ^:private buy-text-color "rgb(151, 252, 228)")
(def ^:private sell-text-color "rgb(244, 187, 198)")
(def ^:private tp-line-color "rgba(45, 212, 191, 0.9)")
(def ^:private sl-line-color "rgba(251, 146, 60, 0.9)")
(def ^:private tp-badge-color "rgba(45, 212, 191, 0.16)")
(def ^:private sl-badge-color "rgba(251, 146, 60, 0.16)")
(def ^:private tp-text-color "rgb(153, 246, 228)")
(def ^:private sl-text-color "rgb(254, 215, 170)")
(def ^:private tp-chip-bg "rgba(45, 212, 191, 0.28)")
(def ^:private sl-chip-bg "rgba(251, 146, 60, 0.28)")
(def ^:private neutral-chip-bg "rgba(148, 163, 184, 0.24)")
(def ^:private neutral-chip-text "rgb(203, 213, 225)")
(def ^:private badge-stack-gap-px 24)
(def ^:private badge-overlap-threshold-px 18)
(def ^:private badge-horizontal-step-px 18)
(def ^:private badge-horizontal-max-offset-px 36)
(def ^:private badge-edge-padding-px 14)
(def ^:private tp-side-markers
  #{"tp" "takeprofit" "take-profit" "take profit"})
(def ^:private sl-side-markers
  #{"sl" "stoploss" "stop-loss" "stop loss"})

(defn- buy-side?
  [side]
  (= "B" (some-> side str str/trim str/upper-case)))

(defn- side-label
  [side]
  (if (buy-side? side) "Buy" "Sell"))

(defn- normalize-order-text
  [value]
  (some-> value str str/trim str/lower-case))

(defn- includes-any-fragment?
  [text fragments]
  (boolean
   (some #(str/includes? text %)
         fragments)))

(defn order-intent
  [order]
  (let [tpsl-text (normalize-order-text (:tpsl order))
        order-type-text (normalize-order-text (:type order))]
    (cond
      (contains? tp-side-markers tpsl-text) :tp
      (contains? sl-side-markers tpsl-text) :sl
      (and (seq order-type-text)
           (includes-any-fragment? order-type-text
                                   #{"takeprofit"
                                     "take-profit"
                                     "take profit"
                                     "take market"
                                     "take limit"}))
      :tp
      (and (seq order-type-text)
           (includes-any-fragment? order-type-text
                                   #{"stoploss"
                                     "stop-loss"
                                     "stop loss"
                                     "stop market"
                                     "stop limit"}))
      :sl
      :else :standard)))

(defn- intent-priority
  [intent]
  (case intent
    :tp 0
    :sl 1
    2))

(defn- intent-chip-label
  [intent]
  (case intent
    :tp "TP"
    :sl "SL"
    "ORD"))

(defn- intent-display-label
  [intent]
  (case intent
    :tp "take profit"
    :sl "stop loss"
    "order"))

(defn- intent-chip-bg
  [intent]
  (case intent
    :tp tp-chip-bg
    :sl sl-chip-bg
    neutral-chip-bg))

(defn- intent-chip-text-color
  [intent]
  (case intent
    :tp tp-text-color
    :sl sl-text-color
    neutral-chip-text))

(defn- order-execution-label
  [order]
  (let [order-type-text (normalize-order-text (:type order))]
    (cond
      (and (seq order-type-text)
           (str/includes? order-type-text "market"))
      "MKT"

      (and (seq order-type-text)
           (str/includes? order-type-text "limit"))
      "LMT"

      :else nil)))

(defn- order-type-label
  [order]
  (let [order-type (some-> (:type order) str str/trim)]
    (if (seq order-type)
      (account-shared/title-case-label order-type)
      "Order")))

(defn- order-line-color
  [side intent]
  (case intent
    :tp tp-line-color
    :sl sl-line-color
    (if (buy-side? side) buy-line-color sell-line-color)))

(defn- order-badge-color
  [side intent]
  (case intent
    :tp tp-badge-color
    :sl sl-badge-color
    (if (buy-side? side) buy-badge-color sell-badge-color)))

(defn- order-text-color
  [side intent]
  (case intent
    :tp tp-text-color
    :sl sl-text-color
    (if (buy-side? side) buy-text-color sell-text-color)))

(defn- format-order-price
  [format-price order]
  (or (when (fn? format-price)
        (or (try
              (format-price (:px order) (:px order))
              (catch :default _ nil))
            (try
              (format-price (:px order))
              (catch :default _ nil))))
      (account-shared/format-trade-price (:px order))
      "0.00"))

(defn- ensure-dollar-prefixed-price
  "Dollar prices read \"$1.23\". A price already in a unit (an outcome
   chart's \"20.9%\" or \"20.9¢\") is not dollars and keeps no `$`."
  [price-text]
  (let [text (some-> price-text str str/trim)]
    (cond
      (not (seq text)) "$0.00"
      (or (str/starts-with? text "$")
          (str/starts-with? text "<$"))
      text
      (or (str/ends-with? text "%") (str/ends-with? text "¢")) text
      :else (str "$" text))))

(defn- format-order-size
  [format-size order]
  (or (when (fn? format-size)
        (format-size (:sz order)))
      (account-shared/format-currency (:sz order))
      "0.00"))

(defn- overlay-label-text
  [order intent order-type side-text sz-text px-label]
  (if (= intent :standard)
    (str order-type " " sz-text " @ " px-label)
    (str side-text " " sz-text " @ " px-label
         (when-let [execution-label (order-execution-label order)]
           (str " | " execution-label)))))

(defn overlay-row-presentation
  [order intent format-price format-size]
  (let [side (:side order)
        side-text (side-label side)
        order-type (order-type-label order)
        px-text (format-order-price format-price order)
        px-label (ensure-dollar-prefixed-price px-text)
        sz-text (format-order-size format-size order)
        line-color (order-line-color side intent)
        badge-color (order-badge-color side intent)
        text-color (order-text-color side intent)
        kind-text (intent-display-label intent)
        chip-label (intent-chip-label intent)
        label-text (overlay-label-text order intent order-type side-text sz-text px-label)
        cancel-target (if (= intent :standard)
                        (str/lower-case side-text)
                        (str kind-text " " (str/lower-case side-text)))]
    {:chip-label chip-label
     :label-text label-text
     :line-color line-color
     :badge-color badge-color
     :text-color text-color
     :kind-attr (case intent
                  :tp "tp"
                  :sl "sl"
                  "order")
     :title-text (str chip-label " | " label-text)
     :chip-bg (intent-chip-bg intent)
     :chip-text-color (intent-chip-text-color intent)
     :cancel-aria-label (str "Cancel "
                             cancel-target
                             " order at "
                             px-label)}))

(defn- layout-sort-key
  [{:keys [line-y intent order]}]
  [line-y
   (intent-priority intent)
   (str (or (:oid order) ""))])

(defn- split-overlap-clusters
  [rows]
  (reduce (fn [clusters row]
            (if-let [last-cluster (peek clusters)]
              (let [last-row (peek last-cluster)
                    last-y (:line-y last-row)
                    row-y (:line-y row)]
                (if (<= (js/Math.abs (- row-y last-y))
                        badge-overlap-threshold-px)
                  (conj (pop clusters) (conj last-cluster row))
                  (conj clusters [row])))
              [[row]]))
          []
          rows))

(defn- cluster-horizontal-offset
  [idx cluster-size]
  (let [center (/ (dec cluster-size) 2)
        raw (* badge-horizontal-step-px
               (- idx center))]
    (support/clamp raw
                   (- badge-horizontal-max-offset-px)
                   badge-horizontal-max-offset-px)))

(defn- cluster-start-y
  [cluster height]
  (let [cluster-size (count cluster)
        center-y (/ (reduce + (map :line-y cluster))
                    (max cluster-size 1))
        span (* badge-stack-gap-px
                (max 0 (dec cluster-size)))
        raw-start (- center-y (/ span 2))]
    (if (pos? height)
      (let [top-bound badge-edge-padding-px
            bottom-bound (max top-bound
                              (- height badge-edge-padding-px))
            max-start (- bottom-bound span)]
        (if (< max-start top-bound)
          raw-start
          (support/clamp raw-start top-bound max-start)))
      raw-start)))

(defn layout-overlapping-badges
  [rows width height]
  (let [anchor-x (/ width 2)
        sorted-rows (sort-by layout-sort-key rows)
        clusters (split-overlap-clusters sorted-rows)]
    (->> clusters
         (mapcat (fn [cluster]
                   (let [cluster-size (count cluster)
                         start-y (cluster-start-y cluster height)]
                     (map-indexed (fn [idx row]
                                    (assoc row
                                           :badge-y (+ start-y
                                                       (* idx badge-stack-gap-px))
                                           :badge-x (+ anchor-x
                                                       (cluster-horizontal-offset idx cluster-size))))
                                  cluster))))
         vec)))
