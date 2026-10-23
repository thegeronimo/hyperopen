(ns hyperopen.views.trading-chart.utils.position-overlay-model
  (:require [clojure.string :as str]
            [hyperopen.utils.interval :as interval]
            [hyperopen.views.trading-chart.utils.fill-fields
             :refer [asset-fill-match? fill-direction-text fill-side-sign fill-size fill-time
                     finite-number? parse-num parse-time-ms]]))

(defn- open-direction-from-dir-text
  [fill]
  (let [dir* (fill-direction-text fill)]
    (cond
      (and dir* (str/includes? dir* "open long")) :long
      (and dir* (str/includes? dir* "open short")) :short
      :else nil)))

(defn- entry-transition-direction
  [fill]
  (let [start-position (parse-num (:startPosition fill))
        fill-size (fill-size fill)
        side-sign (fill-side-sign fill)
        open-direction (open-direction-from-dir-text fill)]
    (cond
      (#{:long :short} open-direction)
      open-direction

      (and (finite-number? start-position)
           (finite-number? fill-size)
           (finite-number? side-sign))
      (let [end-position (+ start-position (* side-sign fill-size))]
        (cond
          (and (<= start-position 0)
               (> end-position 0))
          :long
          (and (>= start-position 0)
               (< end-position 0))
          :short
          :else nil))

      :else nil)))

(defn- prefer-later-fill
  [current fill]
  (let [current-time (fill-time current)
        fill-time* (fill-time fill)]
    (if (and (finite-number? fill-time*)
             (or (not (finite-number? current-time))
                 (>= fill-time* current-time)))
      fill
      current)))

(defn- latest-entry-fills-for-asset
  [active-asset fills market-by-key]
  (reduce (fn [latest-by-side fill]
            (if-not (and (map? fill)
                         (asset-fill-match? active-asset fill market-by-key))
              latest-by-side
              (let [direction (entry-transition-direction fill)]
                (if (#{:long :short} direction)
                  (update latest-by-side direction prefer-later-fill fill)
                  latest-by-side))))
          {:long nil
           :short nil}
          (or fills [])))

(defn- timeframe-bucket-seconds
  [timeframe]
  (let [interval-ms (interval/interval-to-milliseconds timeframe)]
    (if (and (finite-number? interval-ms) (pos? interval-ms))
      (max 1 (js/Math.floor (/ interval-ms 1000)))
      1)))

(defn- align-time-to-timeframe
  [time-ms timeframe]
  (let [time-ms* (parse-time-ms time-ms)]
    (when (finite-number? time-ms*)
      (let [time-sec (js/Math.floor (/ time-ms* 1000))
            bucket-sec (timeframe-bucket-seconds timeframe)]
        (* bucket-sec
           (js/Math.floor (/ time-sec bucket-sec)))))))

(defn- open-position-side
  [position]
  (let [size (parse-num (:szi position))]
    (cond
      (and (finite-number? size) (pos? size)) :long
      (and (finite-number? size) (neg? size)) :short
      :else nil)))

(defn build-position-overlay
  [{:keys [active-asset
           position
           fills
           market-by-key
           selected-timeframe
           candle-data]}]
  (let [position* (or position {})
        side (open-position-side position*)
        size (parse-num (:szi position*))
        abs-size (when (finite-number? size)
                   (js/Math.abs size))]
    (when (and (#{:long :short} side)
               (finite-number? abs-size)
               (pos? abs-size))
      (let [entry-price (parse-num (:entryPx position*))
            unrealized-pnl (or (parse-num (:unrealizedPnl position*)) 0)
            liquidation-price (parse-num (:liquidationPx position*))
            latest-entry-fills (latest-entry-fills-for-asset active-asset fills market-by-key)
            entry-fill (get latest-entry-fills side)
            entry-time-ms (fill-time entry-fill)
            entry-time (align-time-to-timeframe entry-time-ms selected-timeframe)
            latest-time (when (seq candle-data)
                          (:time (last candle-data)))]
        (when (and (finite-number? entry-price)
                   (pos? entry-price))
          {:side side
           :size size
           :abs-size abs-size
           :entry-price entry-price
           :unrealized-pnl unrealized-pnl
           :liquidation-price (when (and (finite-number? liquidation-price)
                                         (pos? liquidation-price))
                                liquidation-price)
           :entry-time entry-time
           :entry-time-ms entry-time-ms
           :latest-time latest-time})))))
