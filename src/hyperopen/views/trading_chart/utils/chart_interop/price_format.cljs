(ns hyperopen.views.trading-chart.utils.chart-interop.price-format
  (:require [hyperopen.utils.formatting :as fmt]))

(defn infer-series-price-format
  "Infer Lightweight Charts price format options from transformed price values."
  [transformed-data extract-prices]
  (let [prices (->> (extract-prices transformed-data)
                    (map (fn [v]
                           (if (number? v) v (js/parseFloat v))))
                    (filter (fn [v]
                              (and (number? v) (not (js/isNaN v)))))
                    vec)
        positive-prices (filter pos? prices)
        reference-price (or (when (seq positive-prices)
                              (apply min positive-prices))
                            (when (seq prices)
                              (apply min (map js/Math.abs prices))))
        decimals (or (fmt/infer-price-decimals reference-price) 2)
        min-move (js/Math.pow 10 (- decimals))]
    #js {:type "price"
         :precision decimals
         :minMove min-move}))
