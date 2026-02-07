(ns hyperopen.websocket.trades
  (:require [hyperopen.websocket.client :as ws-client]
            [hyperopen.utils.interval :as interval]
            [hyperopen.websocket.trades-policy :as policy]))

;; Trades state
(defonce trades-state (atom {:subscriptions #{}
                             :trades []}))

(defonce trades-buffer (atom {:pending [] :timer nil}))

;; Subscribe to trades for a symbol
(defn subscribe-trades! [symbol]
  (when symbol
    (let [subscription-msg {:method "subscribe"
                            :subscription {:type "trades"
                                           :coin symbol}}]
      (swap! trades-state update :subscriptions conj symbol)
      (ws-client/send-message! subscription-msg)
      (println "Subscribed to trades for:" symbol))))

;; Unsubscribe from trades for a symbol
(defn unsubscribe-trades! [symbol]
  (when symbol
    (let [unsubscription-msg {:method "unsubscribe"
                              :subscription {:type "trades"
                                             :coin symbol}}]
      (swap! trades-state update :subscriptions disj symbol)
      (ws-client/send-message! unsubscription-msg)
      (println "Unsubscribed from trades for:" symbol))))

(defn- update-candles-from-trades! [store trades]
  (let [state @store
        active-asset (:active-asset state)
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        interval-ms (interval/interval-to-milliseconds selected-timeframe)]
    (when (and active-asset (seq trades))
      (let [normalized (->> trades
                            (map policy/normalize-trade)
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
                                                (policy/upsert-candle acc interval-ms trade max-count))
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
