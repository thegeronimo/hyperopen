(ns hyperopen.api.info-client
  (:require [clojure.string :as str]
            [hyperopen.api.request-policy :as request-policy]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]))

(def default-config
  {:info-url "https://api.hyperliquid.xyz/info"
   :max-retries 4
   :base-retry-ms 400
   :max-retry-ms 5000
   :max-inflight 4
   :high-priority-burst 3
   :default-priority :high})

(defn- default-sleep-ms
  [ms]
  (js/Promise.
   (fn [resolve _]
     (platform/set-timeout! resolve ms))))

(def ^:private unknown-request-type
  "unknown")

(def ^:private unknown-request-source
  "unknown")

(defn- token-text
  [value]
  (let [token (some-> value str str/trim)]
    (when (seq token)
      token)))

(defn- request-type-token
  [body]
  (or (token-text (when (map? body)
                    (or (get body "type")
                        (:type body))))
      unknown-request-type))

(defn- request-source-token
  [opts]
  (let [opts* (or opts {})]
    (or (token-text (:request-source opts*))
        (token-text (:dedupe-key opts*))
        unknown-request-source)))

(defn- update-counter
  [m key]
  (assoc (or m {})
         key
         (inc (or (get m key) 0))))

(defn- update-nested-counter
  [m outer-key inner-key]
  (update-in (or m {})
             [outer-key inner-key]
             (fnil inc 0)))

(defn- update-latency-aggregate
  [aggregate duration-ms]
  (let [duration* (max 0 (or duration-ms 0))
        aggregate* (or aggregate {})
        count* (inc (or (:count aggregate*) 0))
        total-ms* (+ (or (:total-ms aggregate*) 0) duration*)
        max-ms* (max (or (:max-ms aggregate*) 0) duration*)]
    {:count count*
     :total-ms total-ms*
     :max-ms max-ms*}))

(defn- default-request-stats
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

(defn- default-request-runtime
  []
  {:inflight 0
   :queues {:high []
            :low []}
   :high-burst 0
   :stats (default-request-stats)})

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

(defn make-info-client
  [{:keys [config fetch-fn now-ms-fn sleep-ms-fn log-fn]
    :or {config default-config
         fetch-fn js/fetch
         now-ms-fn platform/now-ms
         sleep-ms-fn default-sleep-ms
         log-fn telemetry/log!}}]
  (let [{:keys [info-url
                max-retries
                base-retry-ms
                max-retry-ms
                max-inflight
                high-priority-burst
                default-priority]} (merge default-config (or config {}))
        cooldown-until-ms (atom 0)
        single-flight-promises (atom {})
        response-cache (atom {})
        request-runtime (atom (default-request-runtime))]
    (letfn [(normalize-priority [priority]
              (if (= priority :low) :low :high))
            (cache-key-token [opts]
              (let [opts* (or opts {})]
                (or (:cache-key opts*)
                    (:dedupe-key opts*))))
            (request-flow-opts [opts]
              (let [opts* (merge {:priority default-priority} (or opts {}))
                    cache-key (cache-key-token opts*)
                    dedupe-key (:dedupe-key opts*)
                    flight-key (or dedupe-key cache-key)
                    cache-ttl-ms (request-policy/normalize-ttl-ms (:cache-ttl-ms opts*))
                    force-refresh? (true? (:force-refresh? opts*))
                    request-source (or (:request-source opts*)
                                       flight-key)
                    request-opts (cond-> (dissoc opts*
                                                :dedupe-key
                                                :cache-key
                                                :cache-ttl-ms
                                                :force-refresh?)
                                   (some? request-source)
                                   (assoc :request-source request-source))]
                {:request-opts request-opts
                 :cache-key cache-key
                 :cache-ttl-ms cache-ttl-ms
                 :force-refresh? force-refresh?
                 :flight-key flight-key}))
            (read-cached-response [cache-key]
              (when cache-key
                (let [entry (get @response-cache cache-key)
                      expires-at-ms (:expires-at-ms entry)]
                  (cond
                    (and (map? entry)
                         (number? expires-at-ms)
                         (> expires-at-ms (now-ms-fn)))
                    (:value entry)

                    (some? entry)
                    (do
                      (swap! response-cache dissoc cache-key)
                      nil)

                    :else
                    nil))))
            (write-cached-response! [cache-key cache-ttl-ms value]
              (when (and cache-key
                         (number? cache-ttl-ms)
                         (pos? cache-ttl-ms))
                (swap! response-cache assoc
                       cache-key
                       {:value value
                        :expires-at-ms (+ (now-ms-fn) cache-ttl-ms)})))
            (retryable-status? [status]
              (contains? #{429 500 502 503 504} status))
            (retry-delay-ms [attempt]
              (let [exp-delay (* base-retry-ms (js/Math.pow 2 attempt))
                    capped-delay (min exp-delay max-retry-ms)]
                (js/Math.round capped-delay)))
            (make-http-error [status]
              (let [err (js/Error. (str "Hyperliquid /info request failed with HTTP " status))]
                (aset err "status" status)
                err))
            (next-request-priority [{:keys [queues high-burst]}]
              (let [has-high? (seq (get queues :high))
                    has-low? (seq (get queues :low))]
                (cond
                  (and has-high?
                       (or (not has-low?)
                           (< high-burst high-priority-burst)))
                  :high

                  has-low?
                  :low

                  has-high?
                  :high

                  :else
                  nil)))
            (dequeue-request-task! []
              (let [selected (atom nil)]
                (swap! request-runtime
                       (fn [state]
                         (let [priority (next-request-priority state)
                               inflight (:inflight state)]
                           (if (or (nil? priority)
                                   (>= inflight max-inflight))
                             state
                             (let [queue (get-in state [:queues priority])
                                   task (first queue)
                                   started-at-ms (now-ms-fn)
                                   task* (assoc task :started-at-ms started-at-ms)
                                   request-type (:request-type task*)
                                   request-source (:request-source task*)
                                   next-inflight (inc inflight)]
                               (reset! selected task*)
                               (-> state
                                   (assoc :inflight next-inflight)
                                   (assoc :high-burst (if (= priority :high)
                                                        (inc (:high-burst state))
                                                        0))
                                   (assoc-in [:queues priority] (vec (rest queue)))
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
                                   (update-in [:stats :max-inflight-observed] (fnil max 0) next-inflight)))))))
                @selected))
            (pump-request-queue! []
              (loop []
                (when (< (:inflight @request-runtime) max-inflight)
                  (when-let [task (dequeue-request-task!)]
                    (start-request-task! task)
                    (recur)))))
            (mark-request-complete! [{:keys [priority
                                             request-type
                                             request-source
                                             started-at-ms]}]
              (let [priority* (normalize-priority priority)
                    completed-at-ms (now-ms-fn)
                    duration-ms (- completed-at-ms (or started-at-ms completed-at-ms))]
                (swap! request-runtime
                       (fn [state]
                         (-> state
                             (update :inflight #(max 0 (dec %)))
                             (update-in [:stats :completed priority*] (fnil inc 0))
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
                                        duration-ms)))))
              (pump-request-queue!))
            (start-request-task! [task]
              (let [{:keys [request-fn resolve reject]} task]
                (try
                  (-> (js/Promise.resolve (request-fn))
                      (.then (fn [result]
                               (resolve result)
                               result))
                      (.catch (fn [err]
                                (reject err)
                                (js/Promise.reject err)))
                      (.finally (fn []
                                  (mark-request-complete! task))))
                  (catch :default e
                    (reject e)
                    (mark-request-complete! task)))))
            (enqueue-request!
              ([priority request-fn]
               (enqueue-request! priority request-fn {}))
              ([priority request-fn request-meta]
               (let [priority* (normalize-priority priority)
                     request-meta* (or request-meta {})
                     request-type (or (:request-type request-meta*)
                                      unknown-request-type)
                     request-source (or (:request-source request-meta*)
                                        unknown-request-source)]
                 (js/Promise.
                  (fn [resolve reject]
                    (swap! request-runtime update-in [:queues priority*]
                           (fnil conj [])
                           {:priority priority*
                            :request-fn request-fn
                            :resolve resolve
                            :reject reject
                            :request-type request-type
                            :request-source request-source})
                    (pump-request-queue!))))))
            (maybe-wait-for-cooldown! []
              (let [remaining-ms (- @cooldown-until-ms (now-ms-fn))]
                (if (pos? remaining-ms)
                  (sleep-ms-fn remaining-ms)
                  (js/Promise.resolve nil))))
            (enqueue-info-request! [priority request-fn request-meta]
              (enqueue-request!
               priority
               (fn []
                 (-> (maybe-wait-for-cooldown!)
                     (.then (fn []
                              (request-fn)))))
               request-meta))
            (track-rate-limit! [request-type request-source]
              (swap! request-runtime
                     (fn [state]
                       (-> state
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
                                      request-source)))))
            (mark-rate-limit-cooldown! [delay-ms]
              (swap! cooldown-until-ms max (+ (now-ms-fn) delay-ms)))
            (with-single-flight! [dedupe-key promise-fn]
              (if (nil? dedupe-key)
                (promise-fn)
                (if-let [existing (get @single-flight-promises dedupe-key)]
                  existing
                  (let [tracked-ref (atom nil)
                        tracked
                        (-> (promise-fn)
                            (.finally
                             (fn []
                               (let [tracked* @tracked-ref]
                                 (swap! single-flight-promises
                                        (fn [state]
                                          (if (identical? (get state dedupe-key) tracked*)
                                            (dissoc state dedupe-key)
                                            state)))))))]
                    (reset! tracked-ref tracked)
                    (swap! single-flight-promises assoc dedupe-key tracked)
                    tracked))))
            (parse-json! [resp]
              (-> (.json resp)
                  (.then #(js->clj % :keywordize-keys true))))
            (request-attempt! [body opts attempt]
              (let [opts* (or opts {})
                    priority (normalize-priority (:priority opts*))
                    request-type (request-type-token body)
                    request-source (request-source-token opts*)
                    request-meta {:request-type request-type
                                  :request-source request-source}]
                (-> (enqueue-info-request!
                     priority
                     (fn []
                       (fetch-fn
                        info-url
                        (clj->js {:method "POST"
                                  :headers {"Content-Type" "application/json"}
                                  :body (js/JSON.stringify (clj->js body))})))
                     request-meta
                    )
                    (.then
                     (fn [resp]
                       (let [status (.-status resp)]
                         (cond
                           (.-ok resp)
                           (parse-json! resp)

                           (and (retryable-status? status)
                                (< attempt max-retries))
                           (let [delay-ms (retry-delay-ms attempt)]
                             (when (= status 429)
                               (track-rate-limit! request-type request-source)
                               (mark-rate-limit-cooldown! delay-ms))
                             (log-fn "Rate-limited /info request, retrying in" delay-ms "ms. status:" status "attempt:" (inc attempt))
                             (-> (sleep-ms-fn delay-ms)
                                 (.then (fn []
                                          (request-attempt! body opts* (inc attempt))))))

                           :else
                           (throw (make-http-error status))))))
                    (.catch
                     (fn [err]
                       (let [status (aget err "status")]
                         (if (and (< attempt max-retries)
                                  (or (nil? status)
                                      (retryable-status? status)))
                           (let [delay-ms (retry-delay-ms attempt)]
                             (when (= status 429)
                               (track-rate-limit! request-type request-source)
                               (mark-rate-limit-cooldown! delay-ms))
                             (log-fn "Error during /info request, retrying in" delay-ms "ms. attempt:" (inc attempt) "error:" err)
                             (-> (sleep-ms-fn delay-ms)
                                 (.then (fn []
                                          (request-attempt! body opts* (inc attempt))))))
                           (js/Promise.reject err))))))))
            (request-info!
              ([body]
               (request-info! body {}))
              ([body opts]
               (let [{:keys [request-opts
                             cache-key
                             cache-ttl-ms
                             force-refresh?
                             flight-key]} (request-flow-opts opts)]
                 (if (and (not force-refresh?)
                          cache-key
                          cache-ttl-ms)
                   (if-let [cached (read-cached-response cache-key)]
                     (js/Promise.resolve cached)
                     (with-single-flight!
                      flight-key
                      (fn []
                        (-> (request-attempt! body request-opts 0)
                            (.then (fn [value]
                                     (write-cached-response! cache-key cache-ttl-ms value)
                                     value))))))
                   (with-single-flight!
                    flight-key
                    (fn []
                      (request-attempt! body request-opts 0))))))
              ([body opts attempt]
               (let [{:keys [request-opts]} (request-flow-opts opts)]
                 (request-attempt! body request-opts attempt))))
            (get-request-stats []
              (:stats @request-runtime))
            (reset-client! []
              (reset! cooldown-until-ms 0)
              (reset! single-flight-promises {})
              (reset! response-cache {})
              (reset! request-runtime (default-request-runtime)))]
      {:request-info! request-info!
       :enqueue-request! enqueue-request!
       :enqueue-info-request! enqueue-info-request!
       :wait-ms sleep-ms-fn
       :normalize-priority normalize-priority
       :maybe-wait-for-cooldown! maybe-wait-for-cooldown!
       :track-rate-limit! track-rate-limit!
       :mark-rate-limit-cooldown! mark-rate-limit-cooldown!
       :with-single-flight! with-single-flight!
       :get-request-stats get-request-stats
       :reset! reset-client!})))
