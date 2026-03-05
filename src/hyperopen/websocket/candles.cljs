(ns hyperopen.websocket.candles
  (:require [clojure.string :as str]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client :as ws-client]))

(def ^:private default-owner
  :active-chart)

(def ^:private max-candle-count
  5000)

(def ^:private default-candle-state
  {:subscriptions #{}
   :owners-by-sub {}
   :sub-by-owner {}})

(defonce candle-state
  (atom default-candle-state))

(defn- normalize-owner
  [owner]
  (if (keyword? owner)
    owner
    default-owner))

(defn- normalized-state
  [state]
  (let [state* (merge default-candle-state (or state {}))]
    (assoc state*
           :subscriptions (set (or (:subscriptions state*) #{}))
           :owners-by-sub (or (:owners-by-sub state*) {})
           :sub-by-owner (or (:sub-by-owner state*) {}))))

(defn- normalize-coin
  [coin]
  (let [coin* (some-> coin str str/trim)]
    (when (seq coin*)
      coin*)))

(defn- normalize-interval
  [interval]
  (cond
    (keyword? interval)
    (let [token (-> interval name str/trim)]
      (when (seq token)
        (keyword token)))

    (string? interval)
    (let [token (str/trim interval)]
      (when (seq token)
        (keyword token)))

    :else
    nil))

(defn- parse-number
  [value]
  (cond
    (number? value)
    (when-not (js/isNaN value)
      value)

    (string? value)
    (let [parsed (js/parseFloat value)]
      (when-not (js/isNaN parsed)
        parsed))

    :else
    nil))

(defn- parse-ms
  [value]
  (when-let [n (parse-number value)]
    (js/Math.floor n)))

(defn- subscription->payload
  [[coin interval]]
  {:type "candle"
   :coin coin
   :interval (name interval)})

(defn- send-subscribe!
  [subscription]
  (ws-client/send-message! {:method "subscribe"
                            :subscription (subscription->payload subscription)}))

(defn- send-unsubscribe!
  [subscription]
  (ws-client/send-message! {:method "unsubscribe"
                            :subscription (subscription->payload subscription)}))

(defn sync-candle-subscription!
  ([coin interval]
   (sync-candle-subscription! coin interval default-owner))
  ([coin interval owner]
   (let [owner* (normalize-owner owner)
         next-sub (when-let [coin* (normalize-coin coin)]
                    (when-let [interval* (normalize-interval interval)]
                      [coin* interval*]))
         unsubscribe-sub (atom nil)
         subscribe-sub (atom nil)]
     (swap! candle-state
            (fn [state]
              (let [{:keys [subscriptions owners-by-sub sub-by-owner] :as state*} (normalized-state state)
                    prev-sub (get sub-by-owner owner*)]
                (if (= prev-sub next-sub)
                  state*
                  (let [[subscriptions1 owners-by-sub1 sub-by-owner1]
                        (if prev-sub
                          (let [prev-owners (disj (get owners-by-sub prev-sub #{}) owner*)
                                owners-by-sub* (if (seq prev-owners)
                                                 (assoc owners-by-sub prev-sub prev-owners)
                                                 (dissoc owners-by-sub prev-sub))
                                subscriptions* (if (seq prev-owners)
                                                 subscriptions
                                                 (disj subscriptions prev-sub))
                                sub-by-owner* (dissoc sub-by-owner owner*)]
                            (when-not (seq prev-owners)
                              (reset! unsubscribe-sub prev-sub))
                            [subscriptions* owners-by-sub* sub-by-owner*])
                          [subscriptions owners-by-sub sub-by-owner])
                        [subscriptions2 owners-by-sub2 sub-by-owner2]
                        (if next-sub
                          (let [next-owners (get owners-by-sub1 next-sub #{})
                                first-owner? (empty? next-owners)
                                owners-by-sub* (assoc owners-by-sub1 next-sub (conj next-owners owner*))
                                subscriptions* (conj subscriptions1 next-sub)
                                sub-by-owner* (assoc sub-by-owner1 owner* next-sub)]
                            (when first-owner?
                              (reset! subscribe-sub next-sub))
                            [subscriptions* owners-by-sub* sub-by-owner*])
                          [subscriptions1 owners-by-sub1 sub-by-owner1])]
                    (assoc state*
                           :subscriptions subscriptions2
                           :owners-by-sub owners-by-sub2
                           :sub-by-owner sub-by-owner2))))))
     (when-let [subscription @unsubscribe-sub]
       (send-unsubscribe! subscription))
     (when-let [subscription @subscribe-sub]
       (send-subscribe! subscription)))))

(defn clear-owner-subscription!
  ([] (clear-owner-subscription! default-owner))
  ([owner]
   (sync-candle-subscription! nil nil owner)))

(defn get-subscriptions
  []
  (:subscriptions (normalized-state @candle-state)))

(defn- payload-candle-rows
  [payload]
  (let [data (:data payload)]
    (cond
      (sequential? data)
      data

      (map? data)
      [data]

      :else
      [])))

(defn- normalize-candle-entry
  [payload row]
  (let [coin (or (normalize-coin (:s row))
                 (normalize-coin (:coin row))
                 (normalize-coin (get-in payload [:data :s]))
                 (normalize-coin (get-in payload [:data :coin]))
                 (normalize-coin (:coin payload)))
        interval (or (normalize-interval (:i row))
                     (normalize-interval (:interval row))
                     (normalize-interval (get-in payload [:data :i]))
                     (normalize-interval (get-in payload [:data :interval]))
                     (normalize-interval (:interval payload)))
        t (or (parse-ms (:t row))
              (parse-ms (:time row))
              (parse-ms (:T row)))
        open (parse-number (or (:o row) (:open row)))
        high (parse-number (or (:h row) (:high row)))
        low (parse-number (or (:l row) (:low row)))
        close (parse-number (or (:c row) (:close row)))
        volume (parse-number (or (:v row) (:volume row)))
        close-time (parse-ms (:T row))
        trades (parse-ms (:n row))]
    (when (and coin
               interval
               (number? t)
               (number? open)
               (number? high)
               (number? low)
               (number? close))
      {:coin coin
       :interval interval
       :row (cond-> {:t t
                     :o open
                     :h high
                     :l low
                     :c close}
              (number? volume) (assoc :v volume)
              (number? close-time) (assoc :T close-time)
              (number? trades) (assoc :n trades))})))

(defn- normalize-payload-candle-rows
  [payload]
  (->> (payload-candle-rows payload)
       (keep #(normalize-candle-entry payload %))
       (reduce (fn [acc {:keys [coin interval row]}]
                 (update acc [coin interval] (fnil conj []) row))
               {})))

(defn- extract-existing-candle-rows
  [entry]
  (cond
    (sequential? entry)
    (vec entry)

    (map? entry)
    (let [rows (or (:candles entry)
                   (:rows entry)
                   (:data entry))]
      (if (sequential? rows)
        (vec rows)
        []))

    :else
    []))

(defn- bounded-dedupe-sorted-rows
  [rows]
  (let [deduped (->> rows
                     (reduce (fn [acc row]
                               (if (number? (:t row))
                                 (assoc acc (:t row) row)
                                 acc))
                             {})
                     vals
                     (sort-by :t)
                     vec)
        count* (count deduped)]
    (if (> count* max-candle-count)
      (subvec deduped (- count* max-candle-count))
      deduped)))

(defn- merge-candle-rows
  [entry incoming]
  (bounded-dedupe-sorted-rows
   (into (extract-existing-candle-rows entry)
         (vec incoming))))

(defn- write-candle-entry
  [entry rows]
  (cond
    (and (map? entry) (contains? entry :candles))
    (-> entry
        (assoc :candles rows)
        (dissoc :error :error-category))

    (and (map? entry) (contains? entry :rows))
    (-> entry
        (assoc :rows rows)
        (dissoc :error :error-category))

    (and (map? entry) (contains? entry :data))
    (-> entry
        (assoc :data rows)
        (dissoc :error :error-category))

    :else
    rows))

(defn create-candles-handler
  [store]
  (fn [payload]
    (when (and (map? payload)
               (= "candle" (:channel payload)))
      (let [rows-by-sub (normalize-payload-candle-rows payload)]
        (when (seq rows-by-sub)
          (swap! store
                 (fn [state]
                   (reduce-kv (fn [acc [coin interval] rows]
                                (update-in acc
                                           [:candles coin interval]
                                           (fn [entry]
                                             (let [merged (merge-candle-rows entry rows)]
                                               (write-candle-entry entry merged)))))
                              state
                              rows-by-sub))))))))

(defn init!
  [store]
  (telemetry/log! "Candle subscription module initialized")
  (ws-client/register-handler! "candle" (create-candles-handler store)))
