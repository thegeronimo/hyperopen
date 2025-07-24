(ns hyperopen.views.trading-chart.utils.data-processing)

(defn process-candle-data [raw-data]
  "Transform raw API candle data to Lightweight.Charts format"
  (if (seq raw-data)
    (->> raw-data
         (keep (fn [c]
                 (when (and (map? c)
                            (:t c) (:o c) (:h c) (:l c) (:c c) (:v c))
                   (try
                     {:time   (/ (:t c) 1000)
                      :open   (js/parseFloat (:o c))
                      :high   (js/parseFloat (:h c))
                      :low    (js/parseFloat (:l c))
                      :close  (js/parseFloat (:c c))
                      :volume (js/parseFloat (:v c))}
                     (catch :default e
                       (js/console.warn "Error processing candle:" (pr-str c) e)
                       nil)))))
         (sort-by :time))
    []))

(defn process-volume-data [candle-data]
  "Extract volume data from candle data for volume chart"
  (->> candle-data
       (map #(hash-map :time (:time %)
                       :value (:volume %)
                       :color (if (>= (:close %) (:open %)) "#10b981" "#ef4444")))))

(defn generate-mock-candles []
  "Generate mock candle data for development"
  (let [base-time (- (js/Math.floor (/ (js/Date.now) 1000)) (* 100 60))  ; 100 minutes ago
        base-price 46000]
    (->> (range 100)
         (map (fn [i]
                (let [time (+ base-time (* i 60))  ; 1-minute intervals
                      open (+ base-price (* i 10) (- (rand 200) 100))
                      high (+ open (rand 150))
                      low (- open (rand 100))
                      close (+ open (- (rand 100) 50))
                      volume (* (+ 50000 (rand 100000)) (+ 0.5 (rand 1)))]
                  {:time time
                   :open open
                   :high (max high (max open close))
                   :low (min low (min open close))
                   :close close
                   :volume volume}))))))

(defn update-last-candle [candles current-time new-price volume]
  "Update the last candle with new price data"
  (if (empty? candles)
    [{:time current-time :open new-price :high new-price :low new-price :close new-price :volume volume}]
    (let [last-candle (last candles)
          is-same-minute (= (:time last-candle) current-time)]
      (if is-same-minute
        ;; Update existing candle
        (conj (vec (butlast candles))
              (-> last-candle
                  (assoc :close new-price)
                  (update :high max new-price)
                  (update :low min new-price)
                  (update :volume + volume)))
        ;; Create new candle
        (conj candles {:time current-time
                       :open new-price
                       :high new-price
                       :low new-price
                       :close new-price
                       :volume volume})))))

(defn format-price [price]
  "Format price with appropriate decimal places"
  (if (>= price 1)
    (.toFixed price 2)
    (.toFixed price 6)))

(defn format-volume [volume]
  "Format volume with K/M/B suffixes"
  (cond
    (>= volume 1000000000) (str (.toFixed (/ volume 1000000000) 1) "B")
    (>= volume 1000000) (str (.toFixed (/ volume 1000000) 1) "M")
    (>= volume 1000) (str (.toFixed (/ volume 1000) 1) "K")
    :else (.toFixed volume 0))) 