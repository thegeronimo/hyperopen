(ns hyperopen.websocket.health-projection
  (:require [clojure.string :as str]
            [hyperopen.websocket.domain.model :as model]))

(def ^:private fingerprint-time-bucket-ms
  1000)

(defn- fingerprint-time-bucket
  [at-ms]
  (when (number? at-ms)
    (quot at-ms fingerprint-time-bucket-ms)))

(defn websocket-health-fingerprint
  [health]
  {:clock/second (fingerprint-time-bucket (:generated-at-ms health))
   :transport/state (get-in health [:transport :state])
   :transport/freshness (get-in health [:transport :freshness])
   :groups/orders_oms (get-in health [:groups :orders_oms :worst-status])
   :groups/market_data (get-in health [:groups :market_data :worst-status])
   :groups/account (get-in health [:groups :account :worst-status])
   :gap/orders_oms (boolean (get-in health [:groups :orders_oms :gap-detected?]))
   :gap/market_data (boolean (get-in health [:groups :market_data :gap-detected?]))
   :gap/account (boolean (get-in health [:groups :account :gap-detected?]))
   :market-projection/latest-flush-event-seq
   (get-in health [:market-projection :latest-flush-event-seq])
   :market-projection/flush-event-count
   (get-in health [:market-projection :flush-event-count])})

(defn append-diagnostics-event
  [state event at-ms details timeline-limit]
  (let [entry (cond-> {:event event
                       :at-ms at-ms}
                (map? details) (assoc :details details))
        timeline (conj (vec (get-in state [:websocket-ui :diagnostics-timeline] [])) entry)
        max-start (max 0 (- (count timeline) timeline-limit))
        bounded (subvec timeline max-start)]
    (assoc-in state [:websocket-ui :diagnostics-timeline] bounded)))

(defn stream-age-ms
  [generated-at-ms last-payload-at-ms]
  (when (and (number? generated-at-ms)
             (number? last-payload-at-ms))
    (max 0 (- generated-at-ms last-payload-at-ms))))

(defn delayed-market-stream-severe?
  [health severe-threshold-ms]
  (let [generated-at-ms (:generated-at-ms health)]
    (boolean
     (some (fn [[_ stream]]
             (let [group (:group stream)
                   status (:status stream)
                   stale-threshold-ms (:stale-threshold-ms stream)
                   age-ms (stream-age-ms generated-at-ms (:last-payload-at-ms stream))]
               (and (= :market_data group)
                    (= :delayed status)
                    (number? stale-threshold-ms)
                    (number? age-ms)
                    (> age-ms severe-threshold-ms))))
           (get health :streams {})))))

(defn- address-like?
  [value]
  (and (string? value)
       (re-matches #"0x[0-9a-fA-F]+" value)))

(defn- selector-value=
  [a b]
  (cond
    (and (address-like? a) (address-like? b))
    (= (str/lower-case a) (str/lower-case b))

    :else
    (= a b)))

(defn- descriptor-matches-selector?
  [descriptor selector]
  (every? (fn [[k v]]
            (if (nil? v)
              true
              (selector-value= v (get descriptor k))))
          (or selector {})))

(defn- stream-topic
  [stream]
  (or (:topic stream)
      (get-in stream [:descriptor :type])))

(defn- stream-live?
  [stream]
  (and (:subscribed? stream)
       (= :live (:status stream))))

(defn- stream-usable?
  [stream]
  (and (:subscribed? stream)
       (contains? #{:live :n-a} (:status stream))))

(defn- transport-live?
  [health]
  (and (= :connected (get-in health [:transport :state]))
       (= :live (get-in health [:transport :freshness]))))

(defn- exact-stream-entry
  [streams topic selector]
  (let [sub-key (model/subscription-key (merge {:type topic}
                                               (or selector {})))
        stream (get streams sub-key)]
    (when (and (map? stream)
               (= topic (stream-topic stream)))
      [sub-key stream])))

(defn- active-topic-entries
  [streams topic]
  (->> (or streams {})
       (filter (fn [[_ stream]]
                 (and (map? stream)
                      (:subscribed? stream)
                      (= topic (stream-topic stream)))))
       vec))

(defn- selector-matching-entries
  [streams topic selector]
  (let [selector* (or selector {})]
    (->> (active-topic-entries streams topic)
         (filter (fn [[_ stream]]
                   (descriptor-matches-selector?
                    (or (:descriptor stream) {})
                    selector*)))
         vec)))

(defn- find-topic-stream
  [health {:keys [topic selector stream-ready?]}]
  (let [streams (or (:streams health) {})
        selector* (or selector {})]
    (when (and (string? topic)
               (fn? stream-ready?)
               (transport-live? health))
      (let [exact (exact-stream-entry streams topic selector*)
            selected (cond
                       (and exact (stream-ready? (second exact)))
                       exact

                       (seq selector*)
                       (let [matches (->> (selector-matching-entries streams topic selector*)
                                          (filter (fn [[_ stream]]
                                                    (stream-ready? stream)))
                                          vec)]
                         (when (= 1 (count matches))
                           (first matches)))

                       :else
                       (let [active (->> (active-topic-entries streams topic)
                                         (filter (fn [[_ stream]]
                                                   (stream-ready? stream)))
                                         vec)]
                         (when (= 1 (count active))
                           (first active))))]
        selected))))

(defn find-live-topic-stream
  [health {:keys [topic selector]}]
  (find-topic-stream health
                     {:topic topic
                      :selector selector
                      :stream-ready? stream-live?}))

(defn find-usable-topic-stream
  [health {:keys [topic selector]}]
  (find-topic-stream health
                     {:topic topic
                      :selector selector
                      :stream-ready? stream-usable?}))

(defn topic-stream-live?
  [health topic selector]
  (boolean
   (find-live-topic-stream health
                           {:topic topic
                            :selector selector})))

(defn topic-stream-usable?
  [health topic selector]
  (boolean
   (find-usable-topic-stream health
                             {:topic topic
                              :selector selector})))

(defn auto-recover-eligible?
  [state health {:keys [enabled? severe-threshold-ms]}]
  (let [transport-state (get-in health [:transport :state])
        transport-freshness (get-in health [:transport :freshness])
        generated-at-ms (or (:generated-at-ms health) 0)
        cooldown-until-ms (get-in state [:websocket-ui :auto-recover-cooldown-until-ms])]
    (and enabled?
         (= :connected transport-state)
         (= :live transport-freshness)
         (not (contains? #{:connecting :reconnecting} transport-state))
         (not (true? (get-in state [:websocket-ui :reset-in-progress?])))
         (or (not (number? cooldown-until-ms))
             (<= cooldown-until-ms generated-at-ms))
         (delayed-market-stream-severe? health severe-threshold-ms))))

(defn gap-detected-transition?
  [prior-fingerprint fingerprint]
  (and (not (some true? (vals (select-keys prior-fingerprint [:gap/orders_oms
                                                              :gap/market_data
                                                              :gap/account]))))
       (some true? (vals (select-keys fingerprint [:gap/orders_oms
                                                   :gap/market_data
                                                   :gap/account])))))
