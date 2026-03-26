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

(defn- normalize-client-config
  [config]
  (merge default-config (or config {})))

(defn- normalize-priority
  [priority]
  (if (= priority :low) :low :high))

(defn- cache-key-token
  [opts]
  (let [opts* (or opts {})]
    (or (:cache-key opts*)
        (:dedupe-key opts*))))

(defn- request-flow-opts
  [default-priority opts]
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

(defn- cached-response-result
  [cache now-ms cache-key]
  (let [entry (get cache cache-key)
        expires-at-ms (:expires-at-ms entry)]
    (cond
      (and (map? entry)
           (number? expires-at-ms)
           (> expires-at-ms now-ms))
      {:cache cache
       :value (:value entry)}

      (some? entry)
      {:cache (dissoc cache cache-key)}

      :else
      {:cache cache})))

(defn- read-cached-response!
  [response-cache now-ms-fn cache-key]
  (when cache-key
    (let [cached-value (atom nil)]
      (swap! response-cache
             (fn [cache]
               (let [{:keys [cache value]} (cached-response-result cache
                                                                  (now-ms-fn)
                                                                  cache-key)]
                 (reset! cached-value value)
                 cache)))
      @cached-value)))

(defn- write-cached-response!
  [response-cache now-ms-fn cache-key cache-ttl-ms value]
  (when (and cache-key
             (number? cache-ttl-ms)
             (pos? cache-ttl-ms))
    (swap! response-cache assoc
           cache-key
           {:value value
            :expires-at-ms (+ (now-ms-fn) cache-ttl-ms)})))

(defn- retryable-status?
  [status]
  (contains? #{429 500 502 503 504} status))

(defn- retry-delay-ms
  [base-retry-ms max-retry-ms attempt]
  (let [exp-delay (* base-retry-ms (js/Math.pow 2 attempt))
        capped-delay (min exp-delay max-retry-ms)]
    (js/Math.round capped-delay)))

(defn- make-http-error
  [status]
  (let [err (js/Error. (str "Hyperliquid /info request failed with HTTP " status))]
    (aset err "status" status)
    err))

(defn- next-request-priority
  [high-priority-burst {:keys [queues high-burst]}]
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

(defn- mark-request-started-state
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

(defn- mark-request-complete-state
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

(defn- track-rate-limited-state
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

(defn- dequeue-request-state
  [state now-ms-fn max-inflight high-priority-burst]
  (let [state* (or state (default-request-runtime))
        priority (next-request-priority high-priority-burst state*)
        inflight (:inflight state*)]
    (if (or (nil? priority)
            (>= inflight max-inflight))
      {:state state*}
      (let [queue (get-in state* [:queues priority])
            task (first queue)
            started-at-ms (now-ms-fn)
            task* (assoc task :started-at-ms started-at-ms)
            next-inflight (inc inflight)]
        {:task task*
         :state (-> state*
                    (assoc-in [:queues priority] (vec (rest queue)))
                    (mark-request-started-state priority
                                                (:request-type task*)
                                                (:request-source task*)
                                                next-inflight))}))))

(defn- dequeue-request-task!
  [request-runtime now-ms-fn max-inflight high-priority-burst]
  (let [selected (atom nil)]
    (swap! request-runtime
           (fn [state]
             (let [{:keys [state task]} (dequeue-request-state state
                                                               now-ms-fn
                                                               max-inflight
                                                               high-priority-burst)]
               (reset! selected task)
               state)))
    @selected))

(defn- start-request-task!
  [mark-request-complete! task]
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

(defn- pump-request-queue!
  [request-runtime max-inflight dequeue-request-task-fn start-request-task-fn]
  (loop []
    (when (< (:inflight @request-runtime) max-inflight)
      (when-let [task (dequeue-request-task-fn)]
        (start-request-task-fn task)
        (recur)))))

(defn- enqueue-request!
  ([request-runtime pump-request-queue-fn priority request-fn]
   (enqueue-request! request-runtime pump-request-queue-fn priority request-fn {}))
  ([request-runtime pump-request-queue-fn priority request-fn request-meta]
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
        (pump-request-queue-fn))))))

(defn- maybe-wait-for-cooldown!
  [cooldown-until-ms now-ms-fn sleep-ms-fn]
  (let [remaining-ms (- @cooldown-until-ms (now-ms-fn))]
    (if (pos? remaining-ms)
      (sleep-ms-fn remaining-ms)
      (js/Promise.resolve nil))))

(defn- enqueue-info-request!
  [enqueue-request-fn cooldown-until-ms now-ms-fn sleep-ms-fn priority request-fn request-meta]
  (enqueue-request-fn
   priority
   (fn []
     (-> (maybe-wait-for-cooldown! cooldown-until-ms now-ms-fn sleep-ms-fn)
         (.then (fn []
                  (request-fn)))))
   request-meta))

(defn- track-rate-limit!
  [request-runtime request-type request-source]
  (swap! request-runtime
         track-rate-limited-state
         request-type
         request-source))

(defn- mark-rate-limit-cooldown!
  [cooldown-until-ms now-ms-fn delay-ms]
  (swap! cooldown-until-ms max (+ (now-ms-fn) delay-ms)))

(defn- with-single-flight!
  [single-flight-promises dedupe-key promise-fn]
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

(defn- parse-json!
  [resp]
  (-> (.json resp)
      (.then #(js->clj % :keywordize-keys true))))

(defn- request-attempt!
  [{:keys [enqueue-info-request!
           fetch-fn
           info-url
           max-retries
           base-retry-ms
           max-retry-ms
           sleep-ms-fn
           log-fn
           track-rate-limit!
           mark-rate-limit-cooldown!]
    :as env}
   body
   opts
   attempt]
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
         request-meta)
        (.then
         (fn [resp]
           (let [status (.-status resp)]
             (cond
               (.-ok resp)
               (parse-json! resp)

               (and (retryable-status? status)
                    (< attempt max-retries))
               (let [delay-ms (retry-delay-ms base-retry-ms max-retry-ms attempt)]
                 (when (= status 429)
                   (track-rate-limit! request-type request-source)
                   (mark-rate-limit-cooldown! delay-ms))
                 (log-fn "Rate-limited /info request, retrying in" delay-ms "ms. status:" status "attempt:" (inc attempt))
                 (-> (sleep-ms-fn delay-ms)
                     (.then (fn []
                              (request-attempt! env
                                                body
                                                opts*
                                                (inc attempt))))))

               :else
               (throw (make-http-error status))))))
        (.catch
         (fn [err]
           (let [status (aget err "status")]
             (if (and (< attempt max-retries)
                      (or (nil? status)
                           (retryable-status? status)))
               (let [delay-ms (retry-delay-ms base-retry-ms max-retry-ms attempt)]
                 (when (= status 429)
                   (track-rate-limit! request-type request-source)
                   (mark-rate-limit-cooldown! delay-ms))
                 (log-fn "Error during /info request, retrying in" delay-ms "ms. attempt:" (inc attempt) "error:" err)
                 (-> (sleep-ms-fn delay-ms)
                     (.then (fn []
                              (request-attempt! env
                                                body
                                                opts*
                                                (inc attempt))))))
               (js/Promise.reject err))))))))

(defn- request-info-with-flow!
  [default-priority response-cache now-ms-fn single-flight-promises request-attempt-fn body opts]
  (let [{:keys [request-opts
                cache-key
                cache-ttl-ms
                force-refresh?
                flight-key]} (request-flow-opts default-priority opts)]
    (if (and (not force-refresh?)
             cache-key
             cache-ttl-ms)
      (if-let [cached (read-cached-response! response-cache now-ms-fn cache-key)]
        (js/Promise.resolve cached)
        (with-single-flight!
          single-flight-promises
          flight-key
          (fn []
            (-> (request-attempt-fn body request-opts 0)
                (.then (fn [value]
                         (write-cached-response! response-cache
                                                 now-ms-fn
                                                 cache-key
                                                 cache-ttl-ms
                                                 value)
                         value))))))
      (with-single-flight!
        single-flight-promises
        flight-key
        (fn []
          (request-attempt-fn body request-opts 0))))))

(defn- request-info-at-attempt!
  [default-priority request-attempt-fn body opts attempt]
  (let [{:keys [request-opts]} (request-flow-opts default-priority opts)]
    (request-attempt-fn body request-opts attempt)))

(defn- get-request-stats
  [request-runtime]
  (:stats @request-runtime))

(defn- reset-client!
  [cooldown-until-ms single-flight-promises response-cache request-runtime]
  (reset! cooldown-until-ms 0)
  (reset! single-flight-promises {})
  (reset! response-cache {})
  (reset! request-runtime (default-request-runtime)))

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
                default-priority]} (normalize-client-config config)
        cooldown-until-ms (atom 0)
        single-flight-promises (atom {})
        response-cache (atom {})
        request-runtime (atom (default-request-runtime))]
    (letfn [(dequeue-request-task-fn []
              (dequeue-request-task! request-runtime
                                     now-ms-fn
                                     max-inflight
                                     high-priority-burst))
            (mark-request-complete-fn [{:keys [priority
                                               request-type
                                               request-source
                                               started-at-ms]}]
              (let [priority* (normalize-priority priority)
                    completed-at-ms (now-ms-fn)
                    duration-ms (- completed-at-ms
                                   (or started-at-ms completed-at-ms))]
                (swap! request-runtime
                       mark-request-complete-state
                       priority*
                       request-type
                       request-source
                       duration-ms)
                (pump-request-queue-fn)))
            (start-request-task-fn [task]
              (start-request-task! mark-request-complete-fn task))
            (pump-request-queue-fn []
              (pump-request-queue! request-runtime
                                   max-inflight
                                   dequeue-request-task-fn
                                   start-request-task-fn))
            (enqueue-request-fn
              ([priority request-fn]
               (enqueue-request! request-runtime
                                 pump-request-queue-fn
                                 priority
                                 request-fn))
              ([priority request-fn request-meta]
               (enqueue-request! request-runtime
                                 pump-request-queue-fn
                                 priority
                                 request-fn
                                 request-meta)))
            (maybe-wait-for-cooldown-fn []
              (maybe-wait-for-cooldown! cooldown-until-ms
                                        now-ms-fn
                                        sleep-ms-fn))
            (enqueue-info-request-fn [priority request-fn request-meta]
              (enqueue-info-request! enqueue-request-fn
                                     cooldown-until-ms
                                     now-ms-fn
                                     sleep-ms-fn
                                     priority
                                     request-fn
                                     request-meta))
            (track-rate-limit-fn [request-type request-source]
              (track-rate-limit! request-runtime
                                 request-type
                                 request-source))
            (mark-rate-limit-cooldown-fn [delay-ms]
              (mark-rate-limit-cooldown! cooldown-until-ms
                                         now-ms-fn
                                         delay-ms))
            (with-single-flight-fn [dedupe-key promise-fn]
              (with-single-flight! single-flight-promises
                                   dedupe-key
                                   promise-fn))
            (request-attempt-fn [body opts attempt]
              (request-attempt! {:enqueue-info-request! enqueue-info-request-fn
                                 :fetch-fn fetch-fn
                                 :info-url info-url
                                 :max-retries max-retries
                                 :base-retry-ms base-retry-ms
                                 :max-retry-ms max-retry-ms
                                 :sleep-ms-fn sleep-ms-fn
                                 :log-fn log-fn
                                 :track-rate-limit! track-rate-limit-fn
                                 :mark-rate-limit-cooldown! mark-rate-limit-cooldown-fn}
                                body
                                opts
                                attempt))
            (request-info-fn
              ([body]
               (request-info-fn body {}))
              ([body opts]
               (request-info-with-flow! default-priority
                                        response-cache
                                        now-ms-fn
                                        single-flight-promises
                                        request-attempt-fn
                                        body
                                        opts))
              ([body opts attempt]
               (request-info-at-attempt! default-priority
                                         request-attempt-fn
                                         body
                                         opts
                                         attempt)))
            (get-request-stats-fn []
              (get-request-stats request-runtime))
            (reset-client-fn []
              (reset-client! cooldown-until-ms
                             single-flight-promises
                             response-cache
                             request-runtime))]
      {:request-info! request-info-fn
       :enqueue-request! enqueue-request-fn
       :enqueue-info-request! enqueue-info-request-fn
       :wait-ms sleep-ms-fn
       :normalize-priority normalize-priority
       :maybe-wait-for-cooldown! maybe-wait-for-cooldown-fn
       :track-rate-limit! track-rate-limit-fn
       :mark-rate-limit-cooldown! mark-rate-limit-cooldown-fn
       :with-single-flight! with-single-flight-fn
       :get-request-stats get-request-stats-fn
       :reset! reset-client-fn})))
