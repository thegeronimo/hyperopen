(ns hyperopen.views.trading-chart.utils.chart-interop.transforms)

(def hyperliquid-volume-up-color
  "Hyperliquid volume up-bar color from TradingView Volume study defaults."
  "rgba(34, 171, 148, 0.5)")

(def hyperliquid-volume-down-color
  "Hyperliquid volume down-bar color from TradingView Volume study defaults."
  "rgba(247, 82, 95, 0.5)")

(defn normalize-main-chart-type
  "Normalize aliases to canonical chart-type keywords."
  [chart-type]
  (if (= chart-type :histogram) :columns chart-type))

(defn- close-value
  [candle]
  (:close candle))

(defn- hlc3-value
  [candle]
  (/ (+ (:high candle) (:low candle) (:close candle)) 3))

(defn transform-data-for-single-value
  "Transform OHLC data to single-value data using `value-fn`."
  [data value-fn]
  (map (fn [candle]
         {:value (value-fn candle)
          :time (:time candle)})
       data))

(defn transform-data-for-close
  "Transform OHLC data to close-value points."
  [data]
  (transform-data-for-single-value data close-value))

(defn transform-data-for-hlc3
  "Transform OHLC data to HLC3-value points."
  [data]
  (transform-data-for-single-value data hlc3-value))

(defn transform-data-for-columns
  "Transform OHLC data to columns data with directional colors."
  [data]
  (map (fn [candle]
         {:value (:close candle)
          :time (:time candle)
          :color (if (>= (:close candle) (:open candle)) "#26a69a" "#ef5350")})
       data))

(defn transform-data-for-heikin-ashi
  "Transform raw candles into Heikin Ashi candles."
  [data]
  (loop [remaining data
         prev-ha-open nil
         prev-ha-close nil
         acc []]
    (if (empty? remaining)
      acc
      (let [candle (first remaining)
            ha-close (/ (+ (:open candle)
                           (:high candle)
                           (:low candle)
                           (:close candle))
                        4)
            ha-open (if (and (number? prev-ha-open)
                             (number? prev-ha-close))
                      (/ (+ prev-ha-open prev-ha-close) 2)
                      (/ (+ (:open candle) (:close candle)) 2))
            ha-high (apply max [(:high candle) ha-open ha-close])
            ha-low (apply min [(:low candle) ha-open ha-close])]
        (recur (rest remaining)
               ha-open
               ha-close
               (conj acc {:time (:time candle)
                          :open ha-open
                          :high ha-high
                          :low ha-low
                          :close ha-close}))))))

(defn transform-data-for-high-low
  "Transform candles into solid high-low range bars."
  [data]
  (map (fn [candle]
         {:time (:time candle)
          :open (:low candle)
          :high (:high candle)
          :low (:low candle)
          :close (:high candle)})
       data))

(defn transform-data-for-volume
  "Transform OHLC data to volume data with directional colors."
  [data]
  (map (fn [candle]
         {:value (:volume candle)
          :time (:time candle)
          :color (if (>= (:close candle) (:open candle))
                   hyperliquid-volume-up-color
                   hyperliquid-volume-down-color)})
       data))
