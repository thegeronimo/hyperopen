(ns hyperopen.api.info-runtime
  (:require [hyperopen.platform :as platform]))

(def ^:private info-max-inflight 4)
(def ^:private high-priority-burst 3)

(defonce ^:private info-cooldown-until-ms (atom 0))
(defonce ^:private single-flight-promises (atom {}))
(defonce ^:private request-runtime
  (atom {:inflight 0
         :queues {:high []
                  :low []}
         :high-burst 0
         :stats {:started {:high 0 :low 0}
                 :completed {:high 0 :low 0}
                 :rate-limited 0
                 :max-inflight-observed 0}}))

(declare pump-request-queue!)

(defn wait-ms
  [ms]
  (js/Promise.
   (fn [resolve _]
     (platform/set-timeout! resolve ms))))

(defn- now-ms []
  (platform/now-ms))

(defn normalize-priority
  [priority]
  (if (= priority :low) :low :high))

(defn maybe-wait-for-cooldown!
  [wait-ms-fn]
  (let [remaining-ms (- @info-cooldown-until-ms (now-ms))]
    (if (pos? remaining-ms)
      (wait-ms-fn remaining-ms)
      (js/Promise.resolve nil))))

(defn- next-request-priority
  [{:keys [queues high-burst]}]
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

(defn- dequeue-request-task!
  []
  (let [selected (atom nil)]
    (swap! request-runtime
           (fn [state]
             (let [priority (next-request-priority state)
                   inflight (:inflight state)]
               (if (or (nil? priority)
                       (>= inflight info-max-inflight))
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

(defn- mark-request-complete!
  [priority]
  (let [priority* (normalize-priority priority)]
    (swap! request-runtime
           (fn [state]
             (-> state
                 (update :inflight #(max 0 (dec %)))
                 (update-in [:stats :completed priority*] (fnil inc 0))))))
  (pump-request-queue!))

(defn- start-request-task!
  [{:keys [priority request-fn resolve reject]}]
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

(defn- pump-request-queue!
  []
  (loop []
    (when (< (:inflight @request-runtime) info-max-inflight)
      (when-let [task (dequeue-request-task!)]
        (start-request-task! task)
        (recur)))))

(defn enqueue-request!
  [priority request-fn]
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

(defn enqueue-info-request!
  [priority request-fn maybe-wait-for-cooldown-fn]
  (enqueue-request!
   priority
   (fn []
     (-> (maybe-wait-for-cooldown-fn)
         (.then (fn []
                  (request-fn)))))))

(defn track-rate-limit!
  []
  (swap! request-runtime update-in [:stats :rate-limited] (fnil inc 0)))

(defn bump-cooldown!
  [delay-ms]
  (swap! info-cooldown-until-ms max (+ (now-ms) delay-ms)))

(defn- clone-promise-result
  [value]
  ;; Single-flight can fan out one fetch response to multiple consumers.
  ;; Each consumer needs an independent body stream for `.json`.
  (if (and value (fn? (aget value "clone")))
    (try
      (.clone value)
      (catch :default _
        value))
    value))

(defn with-single-flight!
  [dedupe-key promise-fn]
  (if (nil? dedupe-key)
    (promise-fn)
    (if-let [existing (get @single-flight-promises dedupe-key)]
      (.then existing clone-promise-result)
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
        (.then tracked clone-promise-result)))))

(defn get-request-stats
  []
  (:stats @request-runtime))

(defn reset-request-runtime!
  []
  (reset! info-cooldown-until-ms 0)
  (reset! single-flight-promises {})
  (reset! request-runtime
          {:inflight 0
           :queues {:high []
                    :low []}
           :high-burst 0
           :stats {:started {:high 0 :low 0}
                   :completed {:high 0 :low 0}
                   :rate-limited 0
                   :max-inflight-observed 0}}))
