(ns hyperopen.api.info-client.stats
  (:require [clojure.string :as str]))

(def ^:private unknown-request-type
  "unknown")

(def ^:private unknown-request-source
  "unknown")

(defn token-text
  [value]
  (let [token (some-> value str str/trim)]
    (when (seq token)
      token)))

(defn request-type-token
  [body]
  (or (token-text (when (map? body)
                    (or (get body "type")
                        (:type body))))
      unknown-request-type))

(defn request-source-token
  [opts]
  (let [opts* (or opts {})]
    (or (token-text (:request-source opts*))
        (token-text (:dedupe-key opts*))
        unknown-request-source)))

(defn inactive-request-error
  [request-type request-source]
  (doto (js/Error. (str "Skipped inactive /info request for " request-type))
    (aset "inactiveRequest" true)
    (aset "requestType" request-type)
    (aset "requestSource" request-source)))

(defn request-active?
  [opts]
  (let [active?-fn (:active?-fn (or opts {}))]
    (if (fn? active?-fn)
      (try
        (not (false? (active?-fn)))
        (catch :default _
          false))
      true)))

(defn update-counter
  [m key]
  (assoc (or m {})
         key
         (inc (or (get m key) 0))))

(defn update-nested-counter
  [m outer-key inner-key]
  (update-in (or m {})
             [outer-key inner-key]
             (fnil inc 0)))

(defn update-latency-aggregate
  [aggregate duration-ms]
  (let [duration* (max 0 (or duration-ms 0))
        aggregate* (or aggregate {})
        count* (inc (or (:count aggregate*) 0))
        total-ms* (+ (or (:total-ms aggregate*) 0) duration*)
        max-ms* (max (or (:max-ms aggregate*) 0) duration*)]
    {:count count*
     :total-ms total-ms*
     :max-ms max-ms*}))

(defn default-request-stats
  []
  {:started {:high 0 :low 0}
   :completed {:high 0 :low 0}
   :started-by-type {}
   :completed-by-type {}
   :started-by-source {}
   :completed-by-source {}
   :started-by-type-source {}
   :completed-by-type-source {}
   :latency-ms-by-type {}
   :latency-ms-by-source {}
   :latency-ms-by-type-source {}
   :rate-limited 0
   :rate-limited-by-type {}
   :rate-limited-by-source {}
   :rate-limited-by-type-source {}
   :max-inflight-observed 0})

(defn default-request-runtime
  []
  {:inflight 0
   :queues {:high []
            :low []}
   :high-burst 0
   :stats (default-request-stats)})

(defn normalize-priority
  [priority]
  (if (= priority :low) :low :high))

(defn mark-request-started-state
  [state priority request-type request-source next-inflight]
  (let [state* (or state (default-request-runtime))]
    (-> state*
        (assoc :inflight next-inflight)
        (assoc :high-burst (if (= priority :high)
                             (inc (:high-burst state*))
                             0))
        (update-in [:stats :started priority] (fnil inc 0))
        (update-in [:stats :started-by-type]
                   update-counter
                   request-type)
        (update-in [:stats :started-by-source]
                   update-counter
                   request-source)
        (update-in [:stats :started-by-type-source]
                   update-nested-counter
                   request-type
                   request-source)
        (update-in [:stats :max-inflight-observed] (fnil max 0) next-inflight))))

(defn mark-request-complete-state
  [state priority request-type request-source duration-ms]
  (-> (or state (default-request-runtime))
      (update :inflight #(max 0 (dec %)))
      (update-in [:stats :completed priority] (fnil inc 0))
      (update-in [:stats :completed-by-type]
                 update-counter
                 request-type)
      (update-in [:stats :completed-by-source]
                 update-counter
                 request-source)
      (update-in [:stats :completed-by-type-source]
                 update-nested-counter
                 request-type
                 request-source)
      (update-in [:stats :latency-ms-by-type request-type]
                 update-latency-aggregate
                 duration-ms)
      (update-in [:stats :latency-ms-by-source request-source]
                 update-latency-aggregate
                 duration-ms)
      (update-in [:stats :latency-ms-by-type-source request-type request-source]
                 update-latency-aggregate
                 duration-ms)))

(defn track-rate-limited-state
  [state request-type request-source]
  (-> (or state (default-request-runtime))
      (update-in [:stats :rate-limited] (fnil inc 0))
      (update-in [:stats :rate-limited-by-type]
                 update-counter
                 request-type)
      (update-in [:stats :rate-limited-by-source]
                 update-counter
                 request-source)
      (update-in [:stats :rate-limited-by-type-source]
                 update-nested-counter
                 request-type
                 request-source)))

(defn top-request-hotspots
  ([stats]
   (top-request-hotspots stats {}))
  ([stats {:keys [limit min-started]
           :or {limit 5
                min-started 1}}]
   (let [limit* (max 0 (or limit 0))
         min-started* (max 0 (or min-started 0))
         started-map (or (:started-by-type-source stats) {})
         completed-map (or (:completed-by-type-source stats) {})
         rate-limited-map (or (:rate-limited-by-type-source stats) {})
         latency-map (or (:latency-ms-by-type-source stats) {})]
     (->> (for [[request-type source-counts] started-map
                :when (map? source-counts)
                [request-source started-count] source-counts
                :let [started-count* (if (number? started-count)
                                       started-count
                                       0)]
                :when (>= started-count* min-started*)]
            (let [completed-count (or (get-in completed-map [request-type request-source]) 0)
                  rate-limited-count (or (get-in rate-limited-map [request-type request-source]) 0)
                  latency-aggregate (or (get-in latency-map [request-type request-source]) {})
                  latency-count (or (:count latency-aggregate) 0)
                  total-ms (or (:total-ms latency-aggregate) 0)
                  avg-latency-ms (when (pos? latency-count)
                                   (/ total-ms latency-count))]
              {:request-type request-type
               :request-source request-source
               :started started-count*
               :completed completed-count
               :rate-limited rate-limited-count
               :latency-ms latency-aggregate
               :avg-latency-ms avg-latency-ms}))
          (sort-by (juxt (comp - :started)
                         (comp - :rate-limited)
                         (comp - (fn [row]
                                   (or (:avg-latency-ms row) 0)))
                         :request-type
                         :request-source))
          (take limit*)
          vec))))
