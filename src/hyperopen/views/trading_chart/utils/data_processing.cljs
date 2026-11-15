(ns hyperopen.views.trading-chart.utils.data-processing
  (:require [hyperopen.platform :as platform]
            [hyperopen.views.trading-chart.utils.theme-colors :as theme-colors]))

(defn- volume-up-color []
  (theme-colors/token "--ho-chart-up"))

(defn- volume-down-color []
  (theme-colors/token "--ho-chart-down"))

(defn process-candle-data [raw-data]
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
         (sort-by :time)
         vec)
    []))

(defn process-volume-data [candle-data]
  (let [up-color (volume-up-color)
        down-color (volume-down-color)]
    (->> candle-data
         (map #(hash-map :time (:time %)
                         :value (:volume %)
                         :color (if (>= (:close %) (:open %))
                                  up-color
                                  down-color))))))

(defn update-last-candle [candles current-time new-price volume]
  (if (empty? candles)
    [{:time current-time :open new-price :high new-price :low new-price :close new-price :volume volume}]
    (let [last-candle (last candles)
          is-same-minute (= (:time last-candle) current-time)]
      (if is-same-minute
        (conj (vec (butlast candles))
              (-> last-candle
                  (assoc :close new-price)
                  (update :high max new-price)
                  (update :low min new-price)
                  (update :volume + volume)))
        (conj candles {:time current-time
                       :open new-price
                       :high new-price
                       :low new-price
                       :close new-price
                       :volume volume})))))

(defn format-price [price]
  (if (>= price 1)
    (.toFixed price 2)
    (.toFixed price 6)))

(defn format-volume [volume]
  (cond
    (>= volume 1000000000) (str (.toFixed (/ volume 1000000000) 1) "B")
    (>= volume 1000000) (str (.toFixed (/ volume 1000000) 1) "M")
    (>= volume 1000) (str (.toFixed (/ volume 1000) 1) "K")
    :else (.toFixed volume 0))) 
