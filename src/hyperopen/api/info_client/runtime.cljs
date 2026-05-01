(ns hyperopen.api.info-client.runtime
  (:require [hyperopen.api.info-client.flow :as flow]
            [hyperopen.api.info-client.stats :as stats]
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

(defn default-sleep-ms
  [ms]
  (js/Promise.
   (fn [resolve _]
     (platform/set-timeout! resolve ms))))

(defn normalize-client-config
  [config]
  (merge default-config (or config {})))

(defn next-request-priority
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

(defn dequeue-request-state
  [state now-ms-fn max-inflight high-priority-burst]
  (let [state* (or state (stats/default-request-runtime))
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
                    (stats/mark-request-started-state priority
                                                (:request-type task*)
                                                (:request-source task*)
                                                next-inflight))}))))

(defn dequeue-request-task!
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

(defn start-request-task!
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

(defn pump-request-queue!
  [request-runtime max-inflight dequeue-request-task-fn start-request-task-fn]
  (loop []
    (when (< (:inflight @request-runtime) max-inflight)
      (when-let [task (dequeue-request-task-fn)]
        (start-request-task-fn task)
        (recur)))))

(defn enqueue-request!
  ([request-runtime pump-request-queue-fn priority request-fn]
   (enqueue-request! request-runtime pump-request-queue-fn priority request-fn {}))
  ([request-runtime pump-request-queue-fn priority request-fn request-meta]
   (let [priority* (stats/normalize-priority priority)
         request-meta* (or request-meta {})
         request-type (or (:request-type request-meta*)
                          "unknown")
         request-source (or (:request-source request-meta*)
                            "unknown")]
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

(defn maybe-wait-for-cooldown!
  [cooldown-until-ms now-ms-fn sleep-ms-fn]
  (let [remaining-ms (- @cooldown-until-ms (now-ms-fn))]
    (if (pos? remaining-ms)
      (sleep-ms-fn remaining-ms)
      (js/Promise.resolve nil))))

(defn enqueue-info-request!
  [enqueue-request-fn cooldown-until-ms now-ms-fn sleep-ms-fn priority request-fn request-meta]
  (enqueue-request-fn
   priority
   (fn []
     (-> (maybe-wait-for-cooldown! cooldown-until-ms now-ms-fn sleep-ms-fn)
         (.then (fn []
                  (request-fn)))))
   request-meta))

(defn track-rate-limit!
  [request-runtime request-type request-source]
  (swap! request-runtime
         stats/track-rate-limited-state
         request-type
         request-source))

(defn mark-rate-limit-cooldown!
  [cooldown-until-ms now-ms-fn delay-ms]
  (swap! cooldown-until-ms max (+ (now-ms-fn) delay-ms)))

(defn get-request-stats
  [request-runtime]
  (:stats @request-runtime))

(defn reset-client!
  [cooldown-until-ms single-flight-promises response-cache request-runtime]
  (reset! cooldown-until-ms 0)
  (reset! single-flight-promises {})
  (reset! response-cache {})
  (reset! request-runtime (stats/default-request-runtime)))

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
        request-runtime (atom (stats/default-request-runtime))]
    (letfn [(dequeue-request-task-fn []
              (dequeue-request-task! request-runtime
                                     now-ms-fn
                                     max-inflight
                                     high-priority-burst))
            (mark-request-complete-fn [{:keys [priority
                                               request-type
                                               request-source
                                               started-at-ms]}]
              (let [priority* (stats/normalize-priority priority)
                    completed-at-ms (now-ms-fn)
                    duration-ms (- completed-at-ms
                                   (or started-at-ms completed-at-ms))]
                (swap! request-runtime
                       stats/mark-request-complete-state
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
              (flow/with-single-flight! single-flight-promises
                                        dedupe-key
                                        promise-fn))
            (request-attempt-fn [body opts attempt]
              (flow/request-attempt! {:enqueue-info-request! enqueue-info-request-fn
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
               (flow/request-info-with-flow! default-priority
                                             response-cache
                                             now-ms-fn
                                             single-flight-promises
                                             request-attempt-fn
                                             body
                                             opts))
              ([body opts attempt]
               (flow/request-info-at-attempt! default-priority
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
       :normalize-priority stats/normalize-priority
       :maybe-wait-for-cooldown! maybe-wait-for-cooldown-fn
       :track-rate-limit! track-rate-limit-fn
       :mark-rate-limit-cooldown! mark-rate-limit-cooldown-fn
       :with-single-flight! with-single-flight-fn
       :get-request-stats get-request-stats-fn
       :reset! reset-client-fn})))
