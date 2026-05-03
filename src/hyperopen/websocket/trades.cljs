(ns hyperopen.websocket.trades
  (:require [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.utils.interval :as interval]
            [hyperopen.websocket.trades-policy :as policy]))

(def ^:private max-recent-trades 100)
(def ^:private trade-ring-kind ::trade-ring)
(def ^:private empty-trade-ring-items (vec (repeat max-recent-trades nil)))

;; Trades state
(defonce trades-state (atom {:subscriptions #{}
                             :trades []
                             :trades-by-coin {}}))

(defonce trades-buffer (atom {:pending [] :timer nil}))

(defn- trade-time-ms [trade]
  (or (:time-ms trade) 0))

(defn- trade-ring? [value]
  (= trade-ring-kind (:kind value)))

(defn- empty-trade-ring []
  {:kind trade-ring-kind
   :capacity max-recent-trades
   :start 0
   :count 0
   :items empty-trade-ring-items})

(defn- trade-ring-append [ring trade]
  (let [{:keys [capacity start count items] :as current}
        (if (trade-ring? ring) ring (empty-trade-ring))]
    (if (< count capacity)
      (let [write-idx (mod (+ start count) capacity)]
        (assoc current
               :count (inc count)
               :items (assoc items write-idx trade)))
      (let [write-idx start
            next-start (mod (inc start) capacity)]
        (assoc current
               :start next-start
               :items (assoc items write-idx trade))))))

(defn- trade-ring->ascending-trades [ring]
  (cond
    (trade-ring? ring)
    (let [{:keys [capacity start count items]} ring]
      (loop [idx 0
             acc (transient [])]
        (if (= idx count)
          (persistent! acc)
          (let [trade (nth items (mod (+ start idx) capacity))]
            (recur (inc idx) (conj! acc trade))))))

    (vector? ring)
    (if (seq ring)
      (vec (rseq ring))
      [])

    :else
    []))

(defn- trade-ring->descending-trades [ring]
  (cond
    (trade-ring? ring)
    (let [ascending (trade-ring->ascending-trades ring)]
      (if (seq ascending)
        (vec (rseq ascending))
        []))

    (vector? ring)
    ring

    :else
    []))

(defn- trade-times-nondecreasing? [trades]
  (loop [remaining (next trades)
         prev-time (trade-time-ms (first trades))]
    (if (seq remaining)
      (let [curr-time (trade-time-ms (first remaining))]
        (if (>= curr-time prev-time)
          (recur (next remaining) curr-time)
          false))
      true)))

(defn- trade-times-nonincreasing? [trades]
  (loop [remaining (next trades)
         prev-time (trade-time-ms (first trades))]
    (if (seq remaining)
      (let [curr-time (trade-time-ms (first remaining))]
        (if (<= curr-time prev-time)
          (recur (next remaining) curr-time)
          false))
      true)))

(defn- ensure-trades-ascending [trades]
  (let [ordered (if (vector? trades) trades (vec trades))]
    (cond
      (<= (count ordered) 1) ordered
      (trade-times-nondecreasing? ordered) ordered
      (trade-times-nonincreasing? ordered) (vec (rseq ordered))
      :else (->> ordered (sort-by trade-time-ms) vec))))

(defn- append-remaining-trades! [acc trades idx]
  (loop [i idx
         out acc]
    (if (< i (count trades))
      (recur (inc i) (conj! out (nth trades i)))
      out)))

(defn- merge-ascending-trades [existing incoming]
  (let [left (or existing [])
        right (or incoming [])
        left-count (count left)
        right-count (count right)]
    (loop [left-idx 0
           right-idx 0
           acc (transient [])]
      (cond
        (= left-idx left-count)
        (persistent! (append-remaining-trades! acc right right-idx))

        (= right-idx right-count)
        (persistent! (append-remaining-trades! acc left left-idx))

        :else
        (let [left-trade (nth left left-idx)
              right-trade (nth right right-idx)]
          (if (<= (trade-time-ms left-trade) (trade-time-ms right-trade))
            (recur (inc left-idx) right-idx (conj! acc left-trade))
            (recur left-idx (inc right-idx) (conj! acc right-trade))))))))

(defn- retain-most-recent-trades [ascending-trades]
  (let [trade-count (count ascending-trades)]
    (if (> trade-count max-recent-trades)
      (subvec ascending-trades (- trade-count max-recent-trades))
      ascending-trades)))

(defn- trade-ring-from-ascending-trades [ascending-trades]
  (reduce trade-ring-append (empty-trade-ring) ascending-trades))

(defn- trade-alias-value
  [trade aliases]
  (some #(get trade %) aliases))

(defn- trade-coin
  [trade]
  (trade-alias-value trade [:coin :symbol :asset]))

(defn- trade-price-raw
  [trade]
  (trade-alias-value trade [:px :price :p]))

(defn- trade-size-raw
  [trade]
  (trade-alias-value trade [:sz :size :s]))

(defn- trade-time-raw
  [trade]
  (trade-alias-value trade [:time :t :ts :timestamp]))

(defn- trade-side
  [trade]
  (trade-alias-value trade [:side :dir]))

(defn- trade-id
  [trade]
  (trade-alias-value trade [:tid :id]))

(defn- normalize-trade-for-view [trade]
  (let [price-raw (trade-price-raw trade)
        size-raw (trade-size-raw trade)
        time-raw (trade-time-raw trade)]
    {:coin (trade-coin trade)
     :price (policy/parse-number price-raw)
     :price-raw price-raw
     :size (or (policy/parse-number size-raw) 0)
     :size-raw size-raw
     :side (trade-side trade)
     :time-ms (policy/time->ms time-raw)
     :tid (trade-id trade)}))

(defn- normalize-trade-for-candle [trade]
  (let [normalized (policy/normalize-trade trade)
        time-ms (or (:time-ms normalized)
                    (policy/time->ms (:time-ms trade)))
        price (or (:price normalized)
                  (policy/parse-number (:price trade))
                  (policy/parse-number (:px trade))
                  (policy/parse-number (:p trade)))
        size (or (:size normalized)
                 (policy/parse-number (:size trade))
                 (policy/parse-number (:sz trade))
                 (policy/parse-number (:s trade))
                 0)
        coin (or (:coin normalized)
                 (trade-coin trade))]
    {:time-ms time-ms
     :price price
     :size (or size 0)
     :coin coin}))

(defn- normalize-candle-trades [trades]
  (into []
        (comp (map normalize-trade-for-candle)
              (filter #(and (some? (:time-ms %))
                            (some? (:price %)))))
        trades))

(defn- upsert-trades-by-coin [trades-by-coin normalized-trades]
  (let [incoming-by-coin (reduce (fn [acc trade]
                                   (let [coin (:coin trade)]
                                     (if (and (string? coin)
                                              (not= "" coin))
                                       (update acc coin (fnil conj []) trade)
                                       acc)))
                                 {}
                                 normalized-trades)]
    (reduce-kv (fn [acc coin incoming]
                 (let [existing-ascending (trade-ring->ascending-trades (get acc coin))
                       incoming-ascending (ensure-trades-ascending incoming)
                       merged-ascending (-> (merge-ascending-trades existing-ascending incoming-ascending)
                                            retain-most-recent-trades)]
                   (assoc acc coin (trade-ring-from-ascending-trades merged-ascending))))
               (or trades-by-coin {})
               incoming-by-coin)))

(defn- ingest-trades! [incoming-trades]
  (let [incoming (vec incoming-trades)
        normalized (mapv normalize-trade-for-view incoming)]
    (swap! trades-state
           (fn [state]
             (-> state
                 (update :trades #(->> (concat incoming (or % []))
                                       (take max-recent-trades)
                                       vec))
                 (update :trades-by-coin upsert-trades-by-coin normalized))))))

;; Subscribe to trades for a symbol
(defn subscribe-trades! [symbol]
  (when symbol
    (if (contains? (:subscriptions @trades-state) symbol)
      (telemetry/log! "Trades subscription already active for:" symbol)
      (let [subscription-msg {:method "subscribe"
                              :subscription {:type "trades"
                                             :coin symbol}}]
        (swap! trades-state update :subscriptions conj symbol)
        (ws-client/send-message! subscription-msg)
        (telemetry/log! "Subscribed to trades for:" symbol)))))

;; Unsubscribe from trades for a symbol
(defn unsubscribe-trades! [symbol]
  (when symbol
    (let [unsubscription-msg {:method "unsubscribe"
                              :subscription {:type "trades"
                                             :coin symbol}}]
      (swap! trades-state update :subscriptions disj symbol)
      (ws-client/send-message! unsubscription-msg)
      (telemetry/log! "Unsubscribed from trades for:" symbol))))

(defn- update-candles-from-normalized-trades! [store normalized]
  (let [state @store
        active-asset (:active-asset state)
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        interval-ms (interval/interval-to-milliseconds selected-timeframe)]
    (when (and active-asset (seq normalized))
      (let [relevant (into []
                           (filter (fn [trade]
                                     (or (nil? (:coin trade))
                                         (= active-asset (:coin trade)))))
                           normalized)
            update-fn (fn [entry]
                        (let [raw (cond
                                    (vector? entry) entry
                                    (and (map? entry) (vector? (:data entry))) (:data entry)
                                    :else [])
                              max-count (when (seq raw) (count raw))
                              updated (reduce (fn [acc trade]
                                                (policy/upsert-candle acc interval-ms trade max-count))
                                              (vec raw)
                                              relevant)]
                          (cond
                            (vector? entry) updated
                            (map? entry) (assoc (or entry {}) :data updated)
                            :else updated)))]
        (when (seq relevant)
          (swap! store update-in [:candles active-asset selected-timeframe] update-fn))))))

(defn- update-candles-from-trades! [store trades]
  (let [ordered (-> trades
                    normalize-candle-trades
                    ensure-trades-ascending)]
    (update-candles-from-normalized-trades! store ordered)))

(defn- schedule-candle-update! [store trades]
  (let [incoming (-> trades
                     normalize-candle-trades
                     ensure-trades-ascending)]
    (when (seq incoming)
      (swap! trades-buffer update :pending #(merge-ascending-trades (or % []) incoming))
      (when-not (:timer @trades-buffer)
        (let [timeout-id (platform/set-timeout!
                           (fn []
                             (let [pending (:pending @trades-buffer)]
                               (swap! trades-buffer assoc :pending [] :timer nil)
                               (when (seq pending)
                                 (update-candles-from-normalized-trades! store pending))))
                           500)]
          (swap! trades-buffer assoc :timer timeout-id))))))

;; Handle incoming trade data
(defn handle-trade-data! [data]
  (telemetry/log! "Processing trade data:" data)
  (when (and (map? data) (= (:channel data) "trades"))
    (let [trades (:data data)]
      (when (seq trades)
        (ingest-trades! trades)
        (telemetry/log! "Received" (count trades) "new trades")
        (telemetry/log! "Latest trade:" (first trades))))))

(defn create-trades-handler [store]
  (fn [data]
    (when (and (map? data) (= (:channel data) "trades"))
      (let [trades (:data data)]
        (when (seq trades)
          (ingest-trades! trades)
          (schedule-candle-update! store trades))))))

;; Get current subscriptions
(defn get-subscriptions []
  (:subscriptions @trades-state))

;; Get recent trades
(defn get-recent-trades []
  (:trades @trades-state))

(defn get-recent-trades-for-coin [coin]
  (if (seq coin)
    (trade-ring->descending-trades (get-in @trades-state [:trades-by-coin coin]))
    []))

;; Clear all trades data
(defn clear-trades! []
  (swap! trades-state assoc :trades [] :trades-by-coin {}))

;; Initialize trades module
(defn init! [store]
  (telemetry/log! "Trades subscription module initialized")
  ;; Register handler for trades channel
  (ws-client/register-handler! "trades" (create-trades-handler store)))
