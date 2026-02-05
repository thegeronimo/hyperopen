(ns hyperopen.websocket.trades
  (:require [hyperopen.websocket.client :as ws-client]
            [hyperopen.utils.interval :as interval]))

;; Trades state
(defonce trades-state (atom {:subscriptions #{}
                             :trades []}))

(defonce trades-buffer (atom {:pending [] :timer nil}))

;; Subscribe to trades for a symbol
(defn subscribe-trades! [symbol]
  (when (ws-client/connected?)
    (let [subscription-msg {:method "subscribe"
                            :subscription {:type "trades"
                                           :coin symbol}}]
      (ws-client/send-message! subscription-msg)
      (swap! trades-state update :subscriptions conj symbol)
      (println "Subscribed to trades for:" symbol))))

;; Unsubscribe from trades for a symbol
(defn unsubscribe-trades! [symbol]
  (when (ws-client/connected?)
    (let [unsubscription-msg {:method "unsubscribe"
                              :subscription {:type "trades"
                                             :coin symbol}}]
      (ws-client/send-message! unsubscription-msg)
      (swap! trades-state update :subscriptions disj symbol)
      (println "Unsubscribed from trades for:" symbol))))

(defn- parse-number [value]
  (cond
    (number? value) value
    (string? value) (let [n (js/parseFloat value)]
                      (when-not (js/isNaN n) n))
    :else nil))

(defn- time->ms [value]
  (let [n (parse-number value)]
    (when n
      (if (< n 1000000000000) (* n 1000) n))))

(defn- normalize-trade [trade]
  (let [time-ms (time->ms (or (:time trade) (:t trade) (:ts trade) (:timestamp trade)))
        price (parse-number (or (:px trade) (:price trade) (:p trade)))
        size (parse-number (or (:sz trade) (:size trade) (:s trade)))
        coin (or (:coin trade) (:symbol trade) (:asset trade))]
    {:time-ms time-ms
     :price price
     :size (or size 0)
     :coin coin}))

(defn- update-candle [candle price size]
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

(defn- upsert-candle [candles interval-ms {:keys [time-ms price size]} max-count]
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

(defn- update-candles-from-trades! [store trades]
  (let [state @store
        active-asset (:active-asset state)
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        interval-ms (interval/interval-to-milliseconds selected-timeframe)]
    (when (and active-asset (seq trades))
      (let [normalized (->> trades
                            (map normalize-trade)
                            (filter #(and (:time-ms %) (:price %)))
                            (filter (fn [trade]
                                      (or (nil? (:coin trade))
                                          (= active-asset (:coin trade)))))
                            (sort-by :time-ms))
            update-fn (fn [entry]
                        (let [raw (cond
                                    (vector? entry) entry
                                    (and (map? entry) (vector? (:data entry))) (:data entry)
                                    :else [])
                              max-count (when (seq raw) (count raw))
                              updated (reduce (fn [acc trade]
                                                (upsert-candle acc interval-ms trade max-count))
                                              (vec raw)
                                              normalized)]
                          (cond
                            (vector? entry) updated
                            (map? entry) (assoc (or entry {}) :data updated)
                            :else updated)))]
        (when (seq normalized)
          (swap! store update-in [:candles active-asset selected-timeframe] update-fn))))))

(defn- schedule-candle-update! [store trades]
  (swap! trades-buffer update :pending into trades)
  (when-not (:timer @trades-buffer)
    (let [timeout-id (js/setTimeout
                       (fn []
                         (let [pending (:pending @trades-buffer)]
                           (swap! trades-buffer assoc :pending [] :timer nil)
                           (when (seq pending)
                             (update-candles-from-trades! store pending))))
                       500)]
      (swap! trades-buffer assoc :timer timeout-id))))

;; Handle incoming trade data
(defn handle-trade-data! [data]
  (println "Processing trade data:" data)
  (when (and (map? data) (= (:channel data) "trades"))
    (let [trades (:data data)]
      (when (seq trades)
        (swap! trades-state update :trades 
               #(take 100 (concat trades %))) ; Keep last 100 trades
        (println "Received" (count trades) "new trades")
        (println "Latest trade:" (first trades))))))

(defn create-trades-handler [store]
  (fn [data]
    (when (and (map? data) (= (:channel data) "trades"))
      (let [trades (:data data)]
        (when (seq trades)
          (swap! trades-state update :trades
                 #(take 100 (concat trades %)))
          (schedule-candle-update! store trades))))))

;; Get current subscriptions
(defn get-subscriptions []
  (:subscriptions @trades-state))

;; Get recent trades
(defn get-recent-trades []
  (:trades @trades-state))

;; Clear all trades data
(defn clear-trades! []
  (swap! trades-state assoc :trades []))

;; Initialize trades module
(defn init! [store]
  (println "Trades subscription module initialized")
  ;; Register handler for trades channel
  (ws-client/register-handler! "trades" (create-trades-handler store)))
