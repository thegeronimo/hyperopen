(ns hyperopen.websocket.health
  (:require [clojure.string :as str]
            [hyperopen.websocket.domain.model :as model]))

(def default-stream-stale-threshold-ms
  {"l2Book" 5000
   "trades" 10000})

(def default-transport-live-threshold-ms
  10000)

(def default-freshness-hysteresis-consecutive
  2)

(def ^:private status-rank
  {:idle 0
   :n-a 1
   :live 2
   :delayed 3
   :reconnecting 4
   :offline 5})

(defn stream-stale-threshold-ms
  [config topic]
  (let [configured (or (:stale-threshold-ms config) {})]
    (if (contains? configured topic)
      (get configured topic)
      (get default-stream-stale-threshold-ms topic))))

(defn transport-live-threshold-ms
  [config]
  (or (:transport-live-threshold-ms config)
      default-transport-live-threshold-ms))

(defn freshness-hysteresis-consecutive
  [config]
  (max 1 (or (:freshness-hysteresis-consecutive config)
             default-freshness-hysteresis-consecutive)))

(defn transport-expected-traffic?
  [streams]
  (boolean
    (some (fn [[_ {:keys [subscribed? stale-threshold-ms]}]]
            (and subscribed? (number? stale-threshold-ms)))
          (or streams {}))))

(defn- age-ms [now-ms at-ms]
  (when (and (number? now-ms) (number? at-ms))
    (max 0 (- now-ms at-ms))))

(defn derive-transport-freshness
  [{:keys [state
           online?
           expected-traffic?
           last-recv-at-ms
           connected-at-ms
           now-ms
           transport-live-threshold-ms]}]
  (let [transport-age-ms (age-ms now-ms (or last-recv-at-ms connected-at-ms))
        threshold-ms (or transport-live-threshold-ms
                         default-transport-live-threshold-ms)]
    (cond
      (or (= false online?)
          (= :disconnected state))
      :offline

      (#{:connecting :reconnecting} state)
      :reconnecting

      (not= :connected state)
      :offline

      (not expected-traffic?)
      :live

      (and (number? transport-age-ms)
           (<= transport-age-ms threshold-ms))
      :live

      :else
      :delayed)))

(defn- apply-transition-hysteresis
  [{:keys [stable-status
           pending-status
           pending-count
           candidate-status
           immediate-statuses
           consecutive-required]}]
  (let [required (max 1 (or consecutive-required 1))
        pending-count* (or pending-count 0)]
    (cond
      (nil? candidate-status)
      {:stable-status stable-status
       :pending-status pending-status
       :pending-count pending-count*}

      (contains? immediate-statuses candidate-status)
      {:stable-status candidate-status
       :pending-status nil
       :pending-count 0}

      (nil? stable-status)
      {:stable-status candidate-status
       :pending-status nil
       :pending-count 0}

      (= candidate-status stable-status)
      {:stable-status stable-status
       :pending-status nil
       :pending-count 0}

      (= candidate-status pending-status)
      (let [next-count (inc pending-count*)]
        (if (>= next-count required)
          {:stable-status candidate-status
           :pending-status nil
           :pending-count 0}
          {:stable-status stable-status
           :pending-status candidate-status
           :pending-count next-count}))

      :else
      {:stable-status stable-status
       :pending-status candidate-status
       :pending-count 1})))

(defn advance-transport-freshness
  [config transport candidate-freshness]
  (let [{:keys [stable-status pending-status pending-count]}
        (apply-transition-hysteresis
          {:stable-status (:freshness transport)
           :pending-status (:freshness-pending-status transport)
           :pending-count (:freshness-pending-count transport)
           :candidate-status candidate-freshness
           :immediate-statuses #{:offline :reconnecting}
           :consecutive-required (freshness-hysteresis-consecutive config)})]
    (assoc (or transport {})
           :freshness stable-status
           :freshness-pending-status pending-status
           :freshness-pending-count pending-count)))

(defn derive-stream-status
  [now-ms {:keys [subscribed?
                  subscribed-at-ms
                  first-payload-at-ms
                  last-payload-at-ms
                  stale-threshold-ms]}]
  (let [valid-first-payload? (and (number? first-payload-at-ms)
                                  (or (nil? subscribed-at-ms)
                                      (>= first-payload-at-ms subscribed-at-ms)))
        payload-age-ms (age-ms now-ms last-payload-at-ms)]
    (cond
      (not subscribed?)
      :idle

      (not valid-first-payload?)
      :idle

      (not (number? stale-threshold-ms))
      :n-a

      (and (number? payload-age-ms)
           (<= payload-age-ms stale-threshold-ms))
      :live

      :else
      :delayed)))

(defn advance-stream-status
  [config stream candidate-status]
  (let [{:keys [stable-status pending-status pending-count]}
        (apply-transition-hysteresis
          {:stable-status (:status stream)
           :pending-status (:status-pending-status stream)
           :pending-count (:status-pending-count stream)
           :candidate-status candidate-status
           :immediate-statuses #{:idle :n-a}
           :consecutive-required (freshness-hysteresis-consecutive config)})]
    (assoc (or stream {})
           :status stable-status
           :status-pending-status pending-status
           :status-pending-count pending-count)))

(defn- normalized-string [value]
  (when (string? value)
    (let [trimmed (str/trim value)]
      (when-not (str/blank? trimmed)
        trimmed))))

(defn- extract-single-coin [payload]
  (or (normalized-string (:coin payload))
      (normalized-string (:symbol payload))
      (normalized-string (:asset payload))
      (normalized-string (get-in payload [:data :coin]))
      (normalized-string (get-in payload [:data :symbol]))
      (normalized-string (get-in payload [:data :asset]))))

(defn- extract-trades-coins [payload]
  (let [rows (:data payload)]
    (if (sequential? rows)
      (->> rows
           (map (fn [row]
                  (or (normalized-string (:coin row))
                      (normalized-string (:symbol row))
                      (normalized-string (:asset row)))))
           (keep identity)
           distinct
           vec)
      [])))

(defn- extract-user-candidates [payload]
  (->> [(:user payload)
        (:address payload)
        (:walletAddress payload)
        (get-in payload [:data :user])
        (get-in payload [:data :address])
        (get-in payload [:data :walletAddress])
        (get-in payload [:data :wallet])]
       (map normalized-string)
       (keep identity)
       distinct
       vec))

(def ^:private topic->matcher
  {"l2Book" (fn [payload]
               (if-let [coin (extract-single-coin payload)]
                 [{:coin coin}]
                 []))
   "trades" (fn [payload]
               (->> (extract-trades-coins payload)
                    (mapv (fn [coin] {:coin coin}))))
   "activeAssetCtx" (fn [payload]
                       (if-let [coin (extract-single-coin payload)]
                         [{:coin coin}]
                         []))
   "webData2" (fn [payload]
                 (->> (extract-user-candidates payload)
                      (mapv (fn [user] {:user user}))))
   "openOrders" (fn [payload]
                   (->> (extract-user-candidates payload)
                        (mapv (fn [user] {:user user}))))
   "userFills" (fn [payload]
                  (->> (extract-user-candidates payload)
                       (mapv (fn [user] {:user user}))))
   "userFundings" (fn [payload]
                     (->> (extract-user-candidates payload)
                          (mapv (fn [user] {:user user}))))
   "userNonFundingLedgerUpdates" (fn [payload]
                                    (->> (extract-user-candidates payload)
                                         (mapv (fn [user] {:user user}))))})

(defn descriptor-candidates
  [{:keys [topic payload]}]
  (let [matcher (get topic->matcher topic)]
    (if matcher
      (->> (matcher (or payload {}))
           (mapv #(merge {:type topic} %)))
      [])))

(defn match-stream-keys
  [streams envelope]
  (let [topic (:topic envelope)
        payload (:payload envelope)
        active-entries (->> (or streams {})
                            (filter (fn [[_ stream]]
                                      (let [stream-topic (or (:topic stream)
                                                             (get-in stream [:descriptor :type]))]
                                        (and (:subscribed? stream)
                                             (= topic stream-topic)))))
                            vec)
        active-keys (set (map first active-entries))
        descriptor-keys (->> (descriptor-candidates {:topic topic :payload payload})
                             (map model/subscription-key)
                             (filter active-keys)
                             distinct
                             vec)]
    (cond
      (seq descriptor-keys)
      descriptor-keys

      (= 1 (count active-entries))
      [(ffirst active-entries)]

      :else
      [])))

(defn worst-status
  [a b]
  (if (>= (get status-rank (or a :idle) 0)
          (get status-rank (or b :idle) 0))
    (or a :idle)
    (or b :idle)))

(defn derive-health-snapshot
  [{:keys [now-ms
           transport
           streams
           config]}]
  (let [streams* (or streams {})
        expected-traffic? (if (contains? (or transport {}) :expected-traffic?)
                            (boolean (:expected-traffic? transport))
                            (transport-expected-traffic? streams*))
        transport-threshold-ms (transport-live-threshold-ms config)
        transport-state (or (:state transport) :disconnected)
        transport-freshness-candidate (derive-transport-freshness
                                        {:state transport-state
                                         :online? (if (contains? transport :online?)
                                                    (boolean (:online? transport))
                                                    true)
                                         :expected-traffic? expected-traffic?
                                         :last-recv-at-ms (:last-recv-at-ms transport)
                                         :connected-at-ms (:connected-at-ms transport)
                                         :transport-live-threshold-ms transport-threshold-ms
                                         :now-ms now-ms})
        transport-freshness (or (:freshness transport)
                                transport-freshness-candidate)
        derived-streams (into {}
                             (map (fn [[sub-key stream]]
                                    (let [status-candidate (derive-stream-status now-ms stream)
                                          status (or (:status stream)
                                                     status-candidate)
                                          group (or (:group stream)
                                                    (model/topic->group (:topic stream)))
                                          last-payload-at-ms (:last-payload-at-ms stream)
                                          payload-age-ms (age-ms now-ms last-payload-at-ms)]
                                      [sub-key
                                       {:group group
                                        :topic (:topic stream)
                                        :status status
                                        :subscribed? (:subscribed? stream)
                                        :subscribed-at-ms (:subscribed-at-ms stream)
                                        :first-payload-at-ms (:first-payload-at-ms stream)
                                        :last-payload-at-ms last-payload-at-ms
                                        :age-ms payload-age-ms
                                        :message-count (or (:message-count stream) 0)
                                        :stale-threshold-ms (:stale-threshold-ms stream)
                                        :last-seq (:last-seq stream)
                                        :seq-gap-detected? (boolean (:seq-gap-detected? stream))
                                        :seq-gap-count (or (:seq-gap-count stream) 0)
                                        :last-gap (:last-gap stream)
                                        :descriptor (:descriptor stream)}]))
                             streams*))
        groups (reduce
                 (fn [acc [_ {:keys [group status seq-gap-detected?]}]]
                   (let [group* (or group :account)
                         current (get-in acc [group* :worst-status] :idle)]
                     (-> acc
                         (assoc-in [group* :worst-status] (worst-status current status))
                         (update-in [group* :gap-detected?]
                                    #(or (boolean %)
                                         (boolean seq-gap-detected?))))))
                 {:market_data {:worst-status :idle :gap-detected? false}
                  :orders_oms {:worst-status :idle :gap-detected? false}
                  :account {:worst-status :idle :gap-detected? false}}
                 derived-streams)]
    {:generated-at-ms now-ms
     :transport {:state transport-state
                 :freshness transport-freshness
                 :last-recv-at-ms (:last-recv-at-ms transport)
                 :expected-traffic? expected-traffic?
                 :attempt (:attempt transport)
                 :last-close (:last-close transport)}
     :streams derived-streams
     :groups groups}))
