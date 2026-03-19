(ns hyperopen.views.trading-chart.utils.position-overlay-model
  (:require [clojure.string :as str]
            [hyperopen.utils.interval :as interval]
            [hyperopen.views.account-info.projections :as projections]))

(def ^:private long-marker-color "#26a69a")
(def ^:private short-marker-color "#ef5350")

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))))

(defn- parse-num
  [value]
  (projections/parse-optional-num value))

(defn- parse-time-ms
  [value]
  (projections/parse-time-ms value))

(defn- non-blank-text
  [value]
  (projections/non-blank-text value))

(defn- normalize-token
  [value]
  (some-> value non-blank-text str/upper-case))

(defn- spot-like-coin?
  [coin]
  (let [coin* (some-> coin non-blank-text)]
    (boolean
     (and coin*
          (or (str/includes? coin* "/")
              (str/starts-with? coin* "@"))))))

(defn- fill-coin
  [fill]
  (or (:coin fill)
      (:symbol fill)
      (:asset fill)))

(defn- fill-direction-text
  [fill]
  (some-> (or (:dir fill) (:direction fill))
          str
          str/trim
          str/lower-case))

(defn- resolve-base-token
  [coin market-by-key]
  (let [{:keys [base-label]} (projections/resolve-coin-display coin (or market-by-key {}))]
    (normalize-token base-label)))

(defn- asset-fill-match?
  [active-asset fill market-by-key]
  (let [active-asset* (non-blank-text active-asset)
        fill-coin* (non-blank-text (fill-coin fill))
        active-base (resolve-base-token active-asset* market-by-key)
        fill-base (resolve-base-token fill-coin* market-by-key)
        raw-match? (= active-asset* fill-coin*)
        active-spot? (spot-like-coin? active-asset*)
        fill-spot? (spot-like-coin? fill-coin*)
        same-market-kind? (= active-spot? fill-spot?)]
    (boolean
     (and (seq active-base)
          (seq fill-base)
          (or raw-match?
              (and same-market-kind?
                   (= active-base fill-base)))))))

(defn- fill-side-sign
  [fill]
  (let [side (some-> (:side fill) str str/trim str/upper-case)
        direction (fill-direction-text fill)]
    (cond
      (contains? #{"B" "BUY" "BID" "LONG"} side) 1
      (contains? #{"A" "S" "SELL" "ASK" "SHORT"} side) -1
      (and (seq direction)
           (or (str/includes? direction "sell")
               (str/includes? direction "open short")
               (str/includes? direction "close long")))
      -1
      (and (seq direction)
           (or (str/includes? direction "buy")
               (str/includes? direction "open long")
               (str/includes? direction "close short")))
      1
      :else nil)))

(defn- fill-time
  [fill]
  (or (parse-time-ms (:time fill))
      (parse-time-ms (:timestamp fill))
      (parse-time-ms (:ts fill))
      (parse-time-ms (:t fill))))

(defn- open-direction-from-dir-text
  [fill]
  (let [dir* (fill-direction-text fill)]
    (cond
      (and dir* (str/includes? dir* "open long")) :long
      (and dir* (str/includes? dir* "open short")) :short
      :else nil)))

(declare fill-size)

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

(defn- marker-position
  [side]
  (if (= side :long) "belowBar" "aboveBar"))

(defn- marker-color
  [side]
  (if (= side :long) long-marker-color short-marker-color))

(defn- marker-label
  [side]
  (if (= side :long) "L" "S"))

(defn- fill-marker-shape
  [side]
  (if (= side :long) "arrowUp" "arrowDown"))

(defn- fill-marker-label
  [side]
  (if (= side :long) "B" "S"))

(defn- fill-side
  [fill]
  (case (fill-side-sign fill)
    1 :long
    -1 :short
    nil))

(defn- fill-price
  [fill]
  (or (parse-num (:price fill))
      (parse-num (:px fill))
      (parse-num (:p fill))
      (parse-num (:fillPx fill))
      (parse-num (:avgPx fill))))

(defn- fill-size
  [fill]
  (or (parse-num (:sz fill))
      (parse-num (:size fill))
      (parse-num (:s fill))
      (parse-num (:filledSz fill))
      (parse-num (:filled fill))))

(defn- fill-identity
  [fill]
  (or (non-blank-text (:tid fill))
      (non-blank-text (:fill-id fill))
      (non-blank-text (:id fill))
      (non-blank-text (:fillId fill))
      (non-blank-text (:oid fill))
      [(fill-coin fill)
       (fill-time fill)
       (fill-side-sign fill)
       (fill-price fill)
       (fill-size fill)
       (parse-num (:startPosition fill))
       (non-blank-text (:dir fill))
       (non-blank-text (:direction fill))]))

(defn build-fill-markers
  [{:keys [active-asset
           fills
           market-by-key
           selected-timeframe
           show-fill-markers?]}]
  (if-not show-fill-markers?
    []
    (->> (or fills [])
         (keep-indexed
          (fn [idx fill]
            (when (and (map? fill)
                       (asset-fill-match? active-asset fill market-by-key))
              (let [side (fill-side fill)
                    time-ms (fill-time fill)
                    aligned-time (align-time-to-timeframe time-ms selected-timeframe)]
                (when (and (#{:long :short} side)
                           (finite-number? aligned-time))
                  {:identity (fill-identity fill)
                   :sort-time (or time-ms 0)
                   :sort-index idx
                   :marker {:coin (fill-coin fill)
                            :time aligned-time
                            :position (marker-position side)
                            :shape (fill-marker-shape side)
                            :color (marker-color side)
                            :text (fill-marker-label side)}})))))
         (reduce (fn [{:keys [seen markers]} {:keys [identity] :as candidate}]
                   (if (contains? seen identity)
                     {:seen seen
                      :markers markers}
                     {:seen (conj seen identity)
                      :markers (conj markers candidate)}))
                 {:seen #{}
                  :markers []})
         :markers
         (sort-by (juxt :sort-time :sort-index))
         (mapv :marker))))

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
           candle-data
           show-fill-markers?]}]
  (let [position* (or position {})
        side (open-position-side position*)
        size (parse-num (:szi position*))
        abs-size (when (finite-number? size)
                   (js/Math.abs size))
        fill-markers (build-fill-markers {:active-asset active-asset
                                          :fills fills
                                          :market-by-key market-by-key
                                          :selected-timeframe selected-timeframe
                                          :show-fill-markers? show-fill-markers?})]
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
           :fill-markers fill-markers
           :entry-time entry-time
           :entry-time-ms entry-time-ms
           :latest-time latest-time
           :entry-marker (when (finite-number? entry-time)
                           {:time entry-time
                            :position (marker-position side)
                            :shape "circle"
                            :color (marker-color side)
                            :text (marker-label side)})})))))
