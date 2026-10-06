(ns hyperopen.api.info-client.flow
  (:require [hyperopen.api.info-client.stats :as stats]
            [hyperopen.api.request-policy :as request-policy]))

(defn cache-key-token
  [opts]
  (let [opts* (or opts {})]
    (or (:cache-key opts*)
        (:dedupe-key opts*))))

(defn request-flow-opts
  [default-priority opts]
  (let [opts* (merge {:priority default-priority} (or opts {}))
        cache-key (cache-key-token opts*)
        dedupe-key (:dedupe-key opts*)
        flight-key (or dedupe-key cache-key)
        cache-ttl-ms (request-policy/normalize-ttl-ms (:cache-ttl-ms opts*))
        force-refresh? (true? (:force-refresh? opts*))
        replace-response-cache-on-force? (true? (:replace-response-cache-on-force? opts*))
        request-source (or (:request-source opts*)
                           flight-key)
        request-opts (cond-> (dissoc opts*
                                    :dedupe-key
                                    :cache-key
                                    :cache-ttl-ms
                                    :force-refresh?
                                    :replace-response-cache-on-force?)
                       (some? request-source)
                       (assoc :request-source request-source))]
    {:request-opts request-opts
     :cache-key cache-key
     :cache-ttl-ms cache-ttl-ms
     :force-refresh? force-refresh?
     :replace-response-cache-on-force? replace-response-cache-on-force?
     :flight-key flight-key}))

(defn- cache-generation
  [entry]
  (or (:generation entry) 0))

(defn- cache-generation-marker
  [entry]
  {:generation (cache-generation entry)})

(defn cached-response-result
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
      {:cache (if (map? entry)
                (assoc cache cache-key (cache-generation-marker entry))
                (dissoc cache cache-key))}

      :else
      {:cache cache})))

(defn read-cached-response!
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

(defn write-cached-response!
  ([response-cache now-ms-fn cache-key cache-ttl-ms value]
   (write-cached-response! response-cache
                           now-ms-fn
                           cache-key
                           cache-ttl-ms
                           value
                           nil))
  ([response-cache now-ms-fn cache-key cache-ttl-ms value expected-generation]
   (when (and cache-key
              (number? cache-ttl-ms)
              (pos? cache-ttl-ms))
     (swap! response-cache
            (fn [cache]
              (let [entry (get cache cache-key)
                    generation (cache-generation entry)]
                (if (or (nil? expected-generation)
                        (= expected-generation generation))
                  (assoc cache
                         cache-key
                         {:value value
                          :expires-at-ms (+ (now-ms-fn) cache-ttl-ms)
                          :generation generation})
                  cache)))))))

(defn- advance-cache-generation!
  [response-cache cache-key]
  (let [next-generation (atom nil)]
    (swap! response-cache
           (fn [cache]
             (let [generation (inc (cache-generation (get cache cache-key)))]
               (reset! next-generation generation)
               (assoc cache cache-key {:generation generation}))))
    @next-generation))

(defn retryable-status?
  [status]
  (contains? #{429 500 502 503 504} status))

(defn retry-delay-ms
  [base-retry-ms max-retry-ms attempt]
  (let [exp-delay (* base-retry-ms (js/Math.pow 2 attempt))
        capped-delay (min exp-delay max-retry-ms)]
    (js/Math.round capped-delay)))

(defn make-http-error
  [status]
  (let [err (js/Error. (str "Hyperliquid /info request failed with HTTP " status))]
    (aset err "status" status)
    err))

(defn make-timeout-error
  [timeout-ms]
  (let [err (js/Error. (str "Hyperliquid /info request timed out after " timeout-ms "ms"))]
    (aset err "timeout" true)
    err))

(defn fetch-with-timeout!
  "Performs the /info fetch with an abort-on-timeout guard. A stalled
   connection rejects (so the caller can retry and ultimately surface an
   error) instead of hanging forever and permanently poisoning the
   single-flight cache for that dedupe key. Falls back to a plain fetch when
   no positive timeout or timer is configured."
  [{:keys [fetch-fn set-timeout-fn clear-timeout-fn make-abort-controller
           request-timeout-ms]}
   url
   request-init]
  (let [timeout-ms (request-policy/normalize-ttl-ms request-timeout-ms)]
    (if-not (and timeout-ms (fn? set-timeout-fn))
      (fetch-fn url request-init)
      (let [controller (when (fn? make-abort-controller)
                         (make-abort-controller))
            _ (when controller
                (aset request-init "signal" (.-signal controller)))
            timer (atom nil)
            clear-timer! (fn []
                           (when-let [timer-id @timer]
                             (reset! timer nil)
                             (when (fn? clear-timeout-fn)
                               (clear-timeout-fn timer-id))))]
        (js/Promise.
         (fn [resolve reject]
           (reset! timer
                   (set-timeout-fn
                    (fn []
                      (reset! timer nil)
                      (when controller
                        (try
                          (.abort controller)
                          (catch :default _ nil)))
                      (reject (make-timeout-error timeout-ms)))
                    timeout-ms))
           (-> (js/Promise.resolve (fetch-fn url request-init))
               (.then (fn [resp]
                        (clear-timer!)
                        (resolve resp)))
               (.catch (fn [err]
                         (clear-timer!)
                         (reject err))))))))))

(defn with-single-flight!
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

(defn- replace-single-flight!
  [single-flight-promises dedupe-key promise-fn]
  (if (nil? dedupe-key)
    (promise-fn)
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
      tracked)))

(defn parse-json!
  [resp]
  (-> (.json resp)
      (.then #(js->clj % :keywordize-keys true))))

(defn request-attempt!
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
        priority (stats/normalize-priority (:priority opts*))
        request-type (stats/request-type-token body)
        request-source (stats/request-source-token opts*)
        request-meta {:request-type request-type
                      :request-source request-source}]
    (if-not (stats/request-active? opts*)
      (js/Promise.reject (stats/inactive-request-error request-type request-source))
      (-> (enqueue-info-request!
           priority
           (fn []
             (if (stats/request-active? opts*)
               (fetch-with-timeout!
                env
                info-url
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"}
                          :body (js/JSON.stringify (clj->js body))}))
               (js/Promise.reject
                (stats/inactive-request-error request-type request-source))))
           request-meta)
        (.then
         (fn [resp]
           (let [status (.-status resp)]
             (cond
               (.-ok resp)
               (parse-json! resp)

               (and (retryable-status? status)
                    (< attempt max-retries))
               (if (stats/request-active? opts*)
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
                 (js/Promise.reject
                  (stats/inactive-request-error request-type request-source)))

               :else
               (throw (make-http-error status))))))
        (.catch
         (fn [err]
           (let [status (aget err "status")]
             (if (and (< attempt max-retries)
                      (or (nil? status)
                           (retryable-status? status))
                      (stats/request-active? opts*))
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
               (js/Promise.reject err)))))))))

(defn request-info-with-flow!
  [default-priority response-cache now-ms-fn single-flight-promises request-attempt-fn body opts]
  (let [{:keys [request-opts
                cache-key
                cache-ttl-ms
                force-refresh?
                replace-response-cache-on-force?
                flight-key]} (request-flow-opts default-priority opts)]
    (cond
      (and force-refresh?
           replace-response-cache-on-force?
           cache-key
           cache-ttl-ms)
      (let [generation (advance-cache-generation! response-cache cache-key)]
        (replace-single-flight!
         single-flight-promises
         flight-key
         (fn []
           (-> (request-attempt-fn body request-opts 0)
               (.then (fn [value]
                        (write-cached-response! response-cache
                                                now-ms-fn
                                                cache-key
                                                cache-ttl-ms
                                                value
                                                generation)
                        value))))))

      (and (not force-refresh?)
           cache-key
           cache-ttl-ms)
      (if-let [cached (read-cached-response! response-cache now-ms-fn cache-key)]
        (js/Promise.resolve cached)
        (let [generation (cache-generation (get @response-cache cache-key))]
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
                                                  value
                                                  generation)
                          value)))))))
      force-refresh?
      (request-attempt-fn body request-opts 0)

      :else
      (with-single-flight!
        single-flight-promises
        flight-key
        (fn []
          (request-attempt-fn body request-opts 0))))))

(defn request-info-at-attempt!
  [default-priority request-attempt-fn body opts attempt]
  (let [{:keys [request-opts]} (request-flow-opts default-priority opts)]
    (request-attempt-fn body request-opts attempt)))
