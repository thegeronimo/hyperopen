(ns hyperopen.websocket.trades-policy)

(defn parse-number [value]
  (cond
    (number? value) value
    (string? value) (let [n (js/parseFloat value)]
                      (when-not (js/isNaN n) n))
    :else nil))

(defn time->ms [value]
  (let [n (parse-number value)]
    (when n
      (if (< n 1000000000000) (* n 1000) n))))

(defn normalize-trade [trade]
  (let [time-ms (time->ms (or (:time trade) (:t trade) (:ts trade) (:timestamp trade)))
        price (parse-number (or (:px trade) (:price trade) (:p trade)))
        size (parse-number (or (:sz trade) (:size trade) (:s trade)))
        coin (or (:coin trade) (:symbol trade) (:asset trade))]
    {:time-ms time-ms
     :price price
     :size (or size 0)
     :coin coin}))

(defn update-candle [candle price size]
  (let [prev-high (parse-number (:h candle))
        prev-low (parse-number (:l candle))
        prev-volume (parse-number (:v candle))
        next-high (if prev-high (max prev-high price) price)
        next-low (if prev-low (min prev-low price) price)
        next-volume (+ (or prev-volume 0) (or size 0))]
    (-> candle
        (assoc :c price)
        (assoc :h next-high)
        (assoc :l next-low)
        (assoc :v next-volume))))

(defn upsert-candle [candles interval-ms {:keys [time-ms price size]} max-count]
  (if (and time-ms price interval-ms)
    (let [bucket (-> time-ms (quot interval-ms) (* interval-ms))
          current (vec (or candles []))
          last-candle (last current)]
      (cond
        (and last-candle (= (:t last-candle) bucket))
        (conj (pop current) (update-candle last-candle price size))

        (and last-candle (< (:t last-candle) bucket))
        (let [new-candle {:t bucket
                          :o price
                          :h price
                          :l price
                          :c price
                          :v (or size 0)}
              extended (conj current new-candle)]
          (if (and max-count (> (count extended) max-count))
            (subvec extended (- (count extended) max-count))
            extended))

        :else
        current))
    (vec (or candles []))))
