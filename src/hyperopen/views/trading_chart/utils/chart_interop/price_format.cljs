(ns hyperopen.views.trading-chart.utils.chart-interop.price-format
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.utils.chart-interop.numeric :as numeric]))

(defn- normalize-decimals
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseFloat value)
                 :else js/NaN)]
    (when-not (js/isNaN parsed)
      (-> parsed
          js/Math.floor
          (max 0)
          (min 12)))))

(defn- min-move-for-decimals
  [decimals]
  ;; Normalize IEEE-754 drift from Math.pow for deterministic minMove values.
  (js/parseFloat (.toFixed (js/Math.pow 10 (- decimals)) decimals)))

(defn- commaized-price-formatter
  [decimals]
  (fn [value]
    (if-let [num (numeric/coerce-number value)]
      (or (fmt/format-intl-number num
                                  {:minimumFractionDigits decimals
                                   :maximumFractionDigits decimals})
          (str value))
      (str value))))

(defn infer-series-price-format
  "Infer Lightweight Charts price format options from metadata or transformed prices.
   When `:price-decimals` is provided, skip transformed-data scanning."
  ([transformed-data extract-prices]
   (infer-series-price-format transformed-data extract-prices nil))
  ([transformed-data extract-prices {:keys [price-decimals]}]
   (let [metadata-decimals (normalize-decimals price-decimals)
         prices (when (nil? metadata-decimals)
                  (->> (extract-prices transformed-data)
                       (numeric/coerce-number-values)))
         positive-prices (when (seq prices) (filter pos? prices))
         reference-price (when (seq prices)
                           (or (when (seq positive-prices)
                                 (apply min positive-prices))
                               (apply min (map js/Math.abs prices))))
         decimals (or metadata-decimals
                      (fmt/infer-price-decimals reference-price)
                      2)
         min-move (min-move-for-decimals decimals)]
    #js {:type "custom"
         :minMove min-move
         :formatter (commaized-price-formatter decimals)})))
