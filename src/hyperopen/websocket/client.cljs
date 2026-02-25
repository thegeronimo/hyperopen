(ns hyperopen.websocket.client
  (:require [hyperopen.platform :as platform]
            [hyperopen.websocket.acl.hyperliquid :as acl]
            [hyperopen.websocket.application.runtime :as app-runtime]
            [hyperopen.websocket.application.runtime-reducer :as runtime-reducer]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.domain.policy :as policy]
            [hyperopen.websocket.flight-recorder :as flight-recorder]
            [hyperopen.websocket.health :as health]
            [hyperopen.websocket.infrastructure.transport :as infra]))

(def ^:private default-flight-recorder-config
  {:enabled? ^boolean goog.DEBUG
   :capacity 4000})

(def ^:private default-config
  (merge {:base-delay-ms 500
          :backoff-multiplier 2
          :jitter-ratio 0.2
          :max-visible-delay-ms 15000
          :max-hidden-delay-ms 60000
          :max-queue-size 1000
          :watchdog-interval-ms 10000
          :health-tick-interval-ms 1000
          :transport-live-threshold-ms health/default-transport-live-threshold-ms
          :freshness-hysteresis-consecutive health/default-freshness-hysteresis-consecutive
          :stale-threshold-ms health/default-stream-stale-threshold-ms
          :stale-visible-ms 45000
          :stale-hidden-ms 180000
          :flight-recorder default-flight-recorder-config
          :channel-tier-policy policy/default-channel-tier-policy}
         policy/default-backpressure-policy))

(defonce connection-config (atom default-config))

(defn- default-connection-projection
  []
  {:status :disconnected
   :attempt 0
   :next-retry-at-ms nil
   :last-close nil
   :last-activity-at-ms nil
   :now-ms nil
   :online? true
   :transport/state :disconnected
   :transport/last-recv-at-ms nil
   :transport/connected-at-ms nil
   :transport/expected-traffic? false
   :transport/freshness :offline
   :queue-size 0
   :ws nil})

(defn- default-stream-projection
  []
  {:tier-depth {:market 0 :lossless 0}
   :metrics {:market-coalesced 0
             :market-dispatched 0
             :lossless-dispatched 0
             :ingress-parse-errors 0}
   :now-ms nil
   :health-fingerprint nil
   :streams {}
   :transport {:state :disconnected
               :online? true
               :last-recv-at-ms nil
               :connected-at-ms nil
               :expected-traffic? false
               :freshness :offline
               :attempt 0
               :last-close nil}
   :market-coalesce {:pending {}
                     :timer nil}})

(defn- default-runtime-view
  []
  {:active-socket-id nil
   :connection (default-connection-projection)
   :stream (default-stream-projection)})

(defonce runtime-view
  (atom (default-runtime-view)))

(defonce message-handlers (atom {}))

(defonce runtime-state
  (atom {:ws-url nil
         :socket nil
         :socket-id 0
         :active-socket-id nil
         :intentional-close? false
         :retry-timer nil
         :watchdog-timer nil
         :channel-runtime nil
         :runtime nil
         :transport nil
         :scheduler nil
         :clock nil
         :router nil
         :flight-recorder nil}))

(defonce websocket-health-projection-state
  (atom {:fingerprint nil
         :writes 0}))

;; Wrappers retained as stable seams for tests and adapters.
(defn now-ms []
  (platform/now-ms))

(defn random-value []
  (platform/random-value))

(defn schedule-timeout! [f ms]
  (platform/set-timeout! f ms))

(defn clear-timeout! [timer-id]
  (platform/clear-timeout! timer-id))

(defn schedule-interval! [f ms]
  (platform/set-interval! f ms))

(defn clear-interval! [timer-id]
  (platform/clear-interval! timer-id))

(defn create-websocket [ws-url]
  (js/WebSocket. ws-url))

(defn window-object []
  (when (exists? js/window)
    js/window))

(defn document-object []
  (when (exists? js/document)
    js/document))

(defn navigator-object []
  (when (exists? js/navigator)
    js/navigator))

(defn add-event-listener! [target event-name handler]
  (when target
    (try
      (.addEventListener target event-name handler)
      (catch :default _ nil))))

(defn- build-clock []
  (infra/make-function-clock now-ms random-value))

(defn- build-scheduler []
  (infra/make-function-scheduler {:schedule-timeout-fn schedule-timeout!
                                  :clear-timeout-fn clear-timeout!
                                  :schedule-interval-fn schedule-interval!
                                  :clear-interval-fn clear-interval!
                                  :window-object-fn window-object
                                  :document-object-fn document-object
                                  :navigator-object-fn navigator-object
                                  :add-event-listener-fn add-event-listener!}))

(defn- build-transport []
  (infra/make-function-transport create-websocket))

(defn- configured-topic->tier
  [topic]
  (policy/topic->tier (:channel-tier-policy @connection-config) topic))

(defn- make-router []
  (app-runtime/make-handler-router
    {:topic->tier configured-topic->tier
     :config @connection-config}))

(defn- ensure-runtime-dependencies! []
  (swap! runtime-state
         (fn [state]
           (-> state
               (update :clock #(or % (build-clock)))
               (update :scheduler #(or % (build-scheduler)))
               (update :transport #(or % (build-transport)))
               (update :router #(or % (make-router)))
               (update :flight-recorder
                       (fn [existing]
                         (let [{:keys [enabled? capacity]} (:flight-recorder @connection-config)]
                           (cond
                             (and enabled? existing)
                             existing

                             enabled?
                             (flight-recorder/create-recorder
                               {:capacity (or capacity (:capacity default-flight-recorder-config))
                                :now-ms now-ms})

                             :else
                             nil))))))))

(defn- current-clock []
  (:clock @runtime-state))

(defn- current-scheduler []
  (:scheduler @runtime-state))

(defn- current-transport []
  (:transport @runtime-state))

(defn- current-runtime []
  (:runtime @runtime-state))

(defn- current-router []
  (:router @runtime-state))

(defn- parse-raw-envelope [{:keys [raw socket-id]}]
  (acl/parse-raw-envelope {:raw raw
                           :socket-id socket-id
                           :source :hyperliquid/ws
                           :now-ms #(infra/now-ms* (current-clock))
                           :topic->tier configured-topic->tier}))

(defn calculate-retry-delay-ms
  ([attempt hidden?]
   (calculate-retry-delay-ms attempt hidden? @connection-config (infra/random-value* (current-clock))))
  ([attempt hidden? config sample]
   (let [{:keys [base-delay-ms
                 backoff-multiplier
                 jitter-ratio
                 max-visible-delay-ms
                 max-hidden-delay-ms]} config
         attempt* (max 1 (or attempt 1))
         exponential-delay (* base-delay-ms (js/Math.pow backoff-multiplier (dec attempt*)))
         capped-delay (min exponential-delay (if hidden? max-hidden-delay-ms max-visible-delay-ms))
         centered-sample (- (* 2 (or sample 0.5)) 1)
         jitter-factor (+ 1 (* centered-sample jitter-ratio))
         jittered-delay (* capped-delay jitter-factor)]
     (-> jittered-delay
         (max 0)
         js/Math.round))))

(defn- start-channel-runtime! []
  (when-not (current-runtime)
    (let [runtime (app-runtime/start-runtime!
                    {:config @connection-config
                     :parse-raw-envelope parse-raw-envelope
                     :hydrate-envelope acl/hydrate-envelope
                     :topic->tier configured-topic->tier
                     :router (current-router)
                     :runtime-view runtime-view
                     :flight-recorder (:flight-recorder @runtime-state)
                     :transport (current-transport)
                     :scheduler (current-scheduler)
                     :clock (current-clock)
                     :calculate-retry-delay-ms calculate-retry-delay-ms})]
      (swap! runtime-state assoc :runtime runtime)
      (doseq [[topic handler] @message-handlers]
        (app-runtime/publish-command! runtime
                                      (model/make-runtime-msg :cmd/register-handler
                                                              (infra/now-ms* (current-clock))
                                                              {:topic topic
                                                               :handler-fn handler}))))))

(defn- stop-channel-runtime! []
  (when-let [runtime (current-runtime)]
    (app-runtime/stop-runtime! runtime)
    (swap! runtime-state assoc :runtime nil))
  (reset! runtime-view (default-runtime-view)))

(defn publish-control! [command]
  (when-let [runtime (current-runtime)]
    (app-runtime/publish-command! runtime command)))

(defn publish-transport-event! [event]
  (when-let [runtime (current-runtime)]
    (app-runtime/publish-transport-event! runtime event)))

;; Legacy compatibility hook. Runtime transport bindings publish socket messages directly.
(defn handle-message! [event]
  (publish-transport-event! {:msg/type :evt/socket-message
                             :socket-id (:active-socket-id @runtime-state)
                             :raw (.-data event)
                             :ts (infra/now-ms* (current-clock))}))

(defn init-connection! [ws-url]
  (ensure-runtime-dependencies!)
  (swap! runtime-state assoc :ws-url ws-url :intentional-close? false)
  (start-channel-runtime!)
  (publish-control! {:msg/type :cmd/init-connection
                     :ws-url ws-url
                     :ts (infra/now-ms* (current-clock))}))

(defn disconnect! []
  (swap! runtime-state assoc :intentional-close? true)
  (publish-control! {:msg/type :cmd/disconnect
                     :ts (infra/now-ms* (current-clock))}))

(defn force-reconnect! []
  (swap! runtime-state assoc :intentional-close? false)
  (publish-control! {:msg/type :cmd/force-reconnect
                     :ts (infra/now-ms* (current-clock))}))

(defn register-handler! [message-type handler-fn]
  (swap! message-handlers assoc message-type handler-fn)
  (when-let [runtime (current-runtime)]
    (publish-control! {:msg/type :cmd/register-handler
                       :topic message-type
                       :handler-fn handler-fn
                       :ts (infra/now-ms* (current-clock))}))
  true)

(defn send-message! [data]
  (boolean
    (publish-control! {:msg/type :cmd/send-message
                       :data data
                       :ts (infra/now-ms* (current-clock))})))

(defn connected? []
  (= (get-in @runtime-view [:connection :status]) :connected))

(defn get-connection-status []
  (get-in @runtime-view [:connection :status]))

(defn get-runtime-metrics []
  (get-in @runtime-view [:stream :metrics]))

(defn get-tier-depths []
  (get-in @runtime-view [:stream :tier-depth]))

(defn get-flight-recording []
  (when-let [recorder (:flight-recorder @runtime-state)]
    (flight-recorder/snapshot recorder)))

(defn get-flight-recording-redacted []
  (when-let [recorder (:flight-recorder @runtime-state)]
    (flight-recorder/redacted-snapshot recorder)))

(defn clear-flight-recording! []
  (when-let [recorder (:flight-recorder @runtime-state)]
    (flight-recorder/clear-recorder! recorder)))

(defn replay-flight-recording []
  (when-let [recording (get-flight-recording)]
    (let [reducer (fn [state msg]
                    (runtime-reducer/step {:calculate-retry-delay-ms calculate-retry-delay-ms}
                                          state
                                          msg))
          replay (flight-recorder/replay-runtime-messages {:recording recording
                                                           :reducer reducer})]
      (assoc replay :recording-event-count (:event-count recording)))))

(defn get-health-snapshot []
  (let [view @runtime-view
        connection (:connection view)
        stream (:stream view)
        clock (current-clock)
        now-ms (or (:now-ms stream)
                   (:now-ms connection)
                   (when clock (infra/now-ms* clock))
                   0)
        transport {:state (:transport/state connection)
                   :online? (:online? connection)
                   :last-recv-at-ms (:transport/last-recv-at-ms connection)
                   :connected-at-ms (:transport/connected-at-ms connection)
                   :expected-traffic? (:transport/expected-traffic? connection)
                   :freshness (:transport/freshness connection)
                   :attempt (:attempt connection)
                   :last-close (:last-close connection)}
        streams (:streams stream)]
    (health/derive-health-snapshot {:now-ms now-ms
                                    :transport transport
                                    :streams streams
                                    :config @connection-config})))

(defn reset-manager-state! []
  ;; Primarily intended for tests.
  (when (current-runtime)
    (disconnect!))
  (stop-channel-runtime!)
  (reset! connection-config default-config)
  (reset! runtime-view (default-runtime-view))
  (reset! websocket-health-projection-state {:fingerprint nil
                                             :writes 0})
  (reset! message-handlers {})
  (swap! runtime-state assoc
         :ws-url nil
         :socket nil
         :socket-id 0
         :active-socket-id nil
         :intentional-close? false
         :retry-timer nil
         :watchdog-timer nil
         :channel-runtime nil
         :runtime nil
         :transport nil
         :scheduler nil
         :clock nil
         :router nil
         :flight-recorder nil))
