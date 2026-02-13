(ns hyperopen.api.info-client
  (:require [hyperopen.platform :as platform]))

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

(defn make-info-client
  [{:keys [config fetch-fn now-ms-fn sleep-ms-fn log-fn]
    :or {config default-config
         fetch-fn js/fetch
         now-ms-fn platform/now-ms
         sleep-ms-fn default-sleep-ms
         log-fn println}}]
  (let [{:keys [info-url
                max-retries
                base-retry-ms
                max-retry-ms
                max-inflight
                high-priority-burst
                default-priority]} (merge default-config (or config {}))
        cooldown-until-ms (atom 0)
        single-flight-promises (atom {})
        request-runtime (atom {:inflight 0
                               :queues {:high []
                                        :low []}
                               :high-burst 0
                               :stats {:started {:high 0 :low 0}
                                       :completed {:high 0 :low 0}
                                       :rate-limited 0
                                       :max-inflight-observed 0}})]
    (letfn [(normalize-priority [priority]
              (if (= priority :low) :low :high))
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
                                   next-inflight (inc inflight)]
                               (reset! selected task)
                               (-> state
                                   (assoc :inflight next-inflight)
                                   (assoc :high-burst (if (= priority :high)
                                                        (inc (:high-burst state))
                                                        0))
                                   (assoc-in [:queues priority] (vec (rest queue)))
                                   (update-in [:stats :started priority] (fnil inc 0))
                                   (update-in [:stats :max-inflight-observed] (fnil max 0) next-inflight)))))))
                @selected))
            (pump-request-queue! []
              (loop []
                (when (< (:inflight @request-runtime) max-inflight)
                  (when-let [task (dequeue-request-task!)]
                    (start-request-task! task)
                    (recur)))))
            (mark-request-complete! [priority]
              (let [priority* (normalize-priority priority)]
                (swap! request-runtime
                       (fn [state]
                         (-> state
                             (update :inflight #(max 0 (dec %)))
                             (update-in [:stats :completed priority*] (fnil inc 0))))))
              (pump-request-queue!))
            (start-request-task! [{:keys [priority request-fn resolve reject]}]
              (try
                (-> (js/Promise.resolve (request-fn))
                    (.then (fn [result]
                             (resolve result)
                             result))
                    (.catch (fn [err]
                              (reject err)
                              (js/Promise.reject err)))
                    (.finally (fn []
                                (mark-request-complete! priority))))
                (catch :default e
                  (reject e)
                  (mark-request-complete! priority))))
            (enqueue-request! [priority request-fn]
              (let [priority* (normalize-priority priority)]
                (js/Promise.
                 (fn [resolve reject]
                   (swap! request-runtime update-in [:queues priority*]
                          (fnil conj [])
                          {:priority priority*
                           :request-fn request-fn
                           :resolve resolve
                           :reject reject})
                   (pump-request-queue!)))))
            (maybe-wait-for-cooldown! []
              (let [remaining-ms (- @cooldown-until-ms (now-ms-fn))]
                (if (pos? remaining-ms)
                  (sleep-ms-fn remaining-ms)
                  (js/Promise.resolve nil))))
            (enqueue-info-request! [priority request-fn]
              (enqueue-request!
               priority
               (fn []
                 (-> (maybe-wait-for-cooldown!)
                     (.then (fn []
                              (request-fn)))))))
            (track-rate-limit! []
              (swap! request-runtime update-in [:stats :rate-limited] (fnil inc 0)))
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
              (let [priority (normalize-priority (:priority opts))]
                (-> (enqueue-info-request!
                     priority
                     (fn []
                       (fetch-fn
                        info-url
                        (clj->js {:method "POST"
                                  :headers {"Content-Type" "application/json"}
                                  :body (js/JSON.stringify (clj->js body))}))))
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
                               (track-rate-limit!)
                               (mark-rate-limit-cooldown! delay-ms))
                             (log-fn "Rate-limited /info request, retrying in" delay-ms "ms. status:" status "attempt:" (inc attempt))
                             (-> (sleep-ms-fn delay-ms)
                                 (.then (fn []
                                          (request-attempt! body opts (inc attempt))))))

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
                               (track-rate-limit!)
                               (mark-rate-limit-cooldown! delay-ms))
                             (log-fn "Error during /info request, retrying in" delay-ms "ms. attempt:" (inc attempt) "error:" err)
                             (-> (sleep-ms-fn delay-ms)
                                 (.then (fn []
                                          (request-attempt! body opts (inc attempt))))))
                           (js/Promise.reject err))))))))
            (request-info!
              ([body]
               (request-info! body {}))
              ([body opts]
               (let [opts* (merge {:priority default-priority} (or opts {}))
                     dedupe-key (:dedupe-key opts*)]
                 (with-single-flight!
                  dedupe-key
                  (fn []
                    (request-attempt! body (dissoc opts* :dedupe-key) 0)))))
              ([body opts attempt]
               (request-attempt! body
                                 (merge {:priority default-priority} (or opts {}))
                                 attempt)))
            (get-request-stats []
              (:stats @request-runtime))
            (reset-client! []
              (reset! cooldown-until-ms 0)
              (reset! single-flight-promises {})
              (reset! request-runtime
                      {:inflight 0
                       :queues {:high []
                                :low []}
                       :high-burst 0
                       :stats {:started {:high 0 :low 0}
                               :completed {:high 0 :low 0}
                               :rate-limited 0
                               :max-inflight-observed 0}}))]
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
