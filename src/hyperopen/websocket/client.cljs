(ns hyperopen.websocket.client
  (:require [clojure.string :as str]
            [hyperopen.websocket.acl.hyperliquid :as acl]
            [hyperopen.websocket.application.runtime :as app-runtime]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.domain.policy :as policy]
            [hyperopen.websocket.infrastructure.transport :as infra]))

(def ^:private default-config
  (merge {:base-delay-ms 500
          :backoff-multiplier 2
          :jitter-ratio 0.2
          :max-visible-delay-ms 15000
          :max-hidden-delay-ms 60000
          :max-queue-size 1000
          :watchdog-interval-ms 10000
          :stale-visible-ms 45000
          :stale-hidden-ms 180000
          :channel-tier-policy policy/default-channel-tier-policy}
         policy/default-backpressure-policy))

(defonce connection-config (atom default-config))

;; WebSocket connection state
(defonce connection-state (atom {:status :disconnected
                                 :attempt 0
                                 :next-retry-at-ms nil
                                 :last-close nil
                                 :last-activity-at-ms nil
                                 :queue-size 0
                                 :ws nil}))

;; Message handlers registry
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
         :transport nil
         :scheduler nil
         :clock nil
         :router nil
         :lifecycle-hooks-installed? false
         :lifecycle-handlers nil}))

(defonce outbound-queue (atom []))
(defonce desired-subscriptions (atom {}))
(defonce stream-runtime (atom {:tier-depth {:market 0 :lossless 0}
                               :metrics {:market-coalesced 0
                                         :market-dispatched 0
                                         :lossless-dispatched 0
                                         :ingress-parse-errors 0}
                               :market-coalesce {:pending {}
                                                 :timer nil}}))

(declare force-reconnect!)
(declare attempt-connect!)
(declare schedule-reconnect!)
(declare handle-message!)
(declare reconnect-if-needed!)

;; Wrappers retained as stable seam for tests and adapters.
(defn now-ms []
  (.now js/Date))

(defn random-value []
  (js/Math.random))

(defn schedule-timeout! [f ms]
  (js/setTimeout f ms))

(defn clear-timeout! [timer-id]
  (js/clearTimeout timer-id))

(defn schedule-interval! [f ms]
  (js/setInterval f ms))

(defn clear-interval! [timer-id]
  (js/clearInterval timer-id))

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

(defn- ensure-runtime-dependencies! []
  (swap! runtime-state
         (fn [state]
           (-> state
               (update :clock #(or % (build-clock)))
               (update :scheduler #(or % (build-scheduler)))
               (update :transport #(or % (build-transport)))
               (update :router #(or % (app-runtime/make-handler-router message-handlers)))))))

(defn- current-clock []
  (:clock @runtime-state))

(defn- current-scheduler []
  (:scheduler @runtime-state))

(defn- current-transport []
  (:transport @runtime-state))

(defn- current-router []
  (:router @runtime-state))

(defn- online? []
  (infra/online?* (current-scheduler)))

(defn- hidden-tab? []
  (infra/hidden-tab?* (current-scheduler)))

(defn- socket-open? [socket]
  (infra/socket-open? (current-transport) socket))

(defn- socket-connecting? [socket]
  (infra/socket-connecting? (current-transport) socket))

(defn- socket-active? [socket]
  (infra/socket-active? (current-transport) socket))

(defn- active-socket-id? [socket-id]
  (= socket-id (:active-socket-id @runtime-state)))

(defn- update-queue-size! []
  (swap! connection-state assoc :queue-size (count @outbound-queue)))

(defn- runtime-channel [channel-key]
  (get-in @runtime-state [:channel-runtime channel-key]))

(defn- make-command
  ([op]
   (make-command op nil))
  ([op attrs]
   (model/make-connection-command op (infra/now-ms* (current-clock)) attrs)))

(defn publish-control! [command]
  (let [command* (if (model/connection-command? command)
                   command
                   (make-command (:op command) (dissoc command :op :ts)))]
    (app-runtime/safe-put! (runtime-channel :control-ch) command*)))

(defn- update-parse-error-metric! []
  (swap! stream-runtime update-in [:metrics :ingress-parse-errors] (fnil inc 0)))

(defn- parse-raw-envelope [{:keys [raw socket-id]}]
  (acl/parse-raw-envelope {:raw raw
                           :socket-id socket-id
                           :source :hyperliquid/ws
                           :now-ms #(infra/now-ms* (current-clock))
                           :topic->tier #(policy/topic->tier
                                           (:channel-tier-policy @connection-config)
                                           %)}))

(defn- track-subscription-intent! [data]
  (swap! desired-subscriptions model/apply-subscription-intent data))

(defn- send-json! [socket data]
  (when (socket-open? socket)
    (try
      (infra/send-json! (current-transport) socket data)
      (swap! connection-state assoc :last-activity-at-ms (infra/now-ms* (current-clock)))
      true
      (catch :default e
        (println "Error sending WebSocket message:" e)
        false))))

(defn- enqueue-message! [data]
  (let [max-queue-size (:max-queue-size @connection-config)]
    (swap! outbound-queue
           (fn [queue]
             (let [next-queue (conj queue data)]
               (if (> (count next-queue) max-queue-size)
                 (do
                   (println "WebSocket queue overflow, dropping oldest queued message")
                   (vec (rest next-queue)))
                 next-queue)))))
  (update-queue-size!))

(defn- drain-queued-messages! []
  (let [queued @outbound-queue]
    (reset! outbound-queue [])
    (update-queue-size!)
    queued))

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

(defn- clear-retry-timer! []
  (when-let [timer-id (:retry-timer @runtime-state)]
    (infra/clear-timeout* (current-scheduler) timer-id)
    (swap! runtime-state assoc :retry-timer nil))
  (swap! connection-state assoc :next-retry-at-ms nil))

(defn- dispatch-outbound-message! [data]
  (let [socket (:socket @runtime-state)]
    (if (and (= :connected (:status @connection-state))
             socket
             (send-json! socket data))
      true
      (do
        (enqueue-message! data)
        (when (and (not (:intentional-close? @runtime-state))
                   (:ws-url @runtime-state)
                   (nil? (:retry-timer @runtime-state))
                   (not (socket-connecting? (:socket @runtime-state))))
          (schedule-reconnect!))
        false))))

(defn- start-channel-runtime! []
  (when-not (:channel-runtime @runtime-state)
    (let [channels (app-runtime/create-runtime-channels @connection-config)]
      (swap! runtime-state assoc :channel-runtime channels)
      (app-runtime/start-runtime-loops!
        {:channels channels
         :parse-raw-envelope parse-raw-envelope
         :topic->tier #(policy/topic->tier (:channel-tier-policy @connection-config) %)
         :router (current-router)
         :config @connection-config
         :stream-runtime stream-runtime
         :scheduler (current-scheduler)
         :publish-control! publish-control!
         :make-command make-command
         :reconnect-if-needed! reconnect-if-needed!
         :drain-queued-messages! drain-queued-messages!
         :desired-subscriptions desired-subscriptions
         :dispatch-outbound-message! dispatch-outbound-message!
         :command-handlers {}}))))

(defn- stop-channel-runtime! []
  (when-let [channels (:channel-runtime @runtime-state)]
    (app-runtime/stop-runtime! channels)
    (swap! runtime-state assoc :channel-runtime nil))
  (when-let [timer-id (get-in @stream-runtime [:market-coalesce :timer])]
    (infra/clear-timeout* (current-scheduler) timer-id))
  (swap! stream-runtime assoc
         :tier-depth {:market 0 :lossless 0}
         :market-coalesce {:pending {}
                           :timer nil}))

(defn- flush-queued-messages! [socket]
  (when (socket-open? socket)
    (let [queued-messages @outbound-queue]
      (reset! outbound-queue [])
      (update-queue-size!)
      (loop [pending (seq queued-messages)]
        (when pending
          (if (send-json! socket (first pending))
            (recur (next pending))
            (do
              ;; Put unsent messages back at the front to preserve FIFO order.
              (swap! outbound-queue #(vec (concat pending %)))
              (update-queue-size!))))))))

(defn- replay-desired-subscriptions! [socket]
  (when (socket-open? socket)
    (doseq [subscription (->> @desired-subscriptions vals (sort-by pr-str))]
      (send-json! socket {:method "subscribe"
                          :subscription subscription}))))

(defn- reconnect-if-needed! []
  (let [{:keys [status]} @connection-state]
    (when (and (contains? #{:disconnected :reconnecting} status)
               (not (:intentional-close? @runtime-state))
               (:ws-url @runtime-state))
      (force-reconnect!))))

(defn- request-reconnect-if-needed! []
  (or (publish-control! (make-command :reconnect-if-needed))
      (reconnect-if-needed!)))

(defn- ensure-watchdog! []
  (when-not (:watchdog-timer @runtime-state)
    (let [timer-id (infra/schedule-interval*
                     (current-scheduler)
                     (fn []
                       (let [{:keys [status last-activity-at-ms]} @connection-state
                             socket (:socket @runtime-state)
                             threshold-ms (if (hidden-tab?)
                                            (:stale-hidden-ms @connection-config)
                                            (:stale-visible-ms @connection-config))]
                         (when (and (= status :connected)
                                    (socket-open? socket)
                                    (number? last-activity-at-ms)
                                    (> (- (infra/now-ms* (current-clock)) last-activity-at-ms) threshold-ms))
                           (println "WebSocket watchdog detected stale connection, forcing reconnect")
                           (swap! runtime-state assoc :intentional-close? false)
                           (try
                             (infra/close-socket! (current-transport) socket 4002 "Stale websocket connection")
                             (catch :default e
                               (println "Failed to close stale WebSocket:" e))))))
                     (:watchdog-interval-ms @connection-config))]
      (swap! runtime-state assoc :watchdog-timer timer-id))))

(defn- install-lifecycle-hooks! []
  (when-not (:lifecycle-hooks-installed? @runtime-state)
    (let [scheduler (current-scheduler)
          focus-handler (fn [_]
                          (request-reconnect-if-needed!))
          visibility-handler (fn [_]
                               (when-not (hidden-tab?)
                                 (request-reconnect-if-needed!)))
          online-handler (fn [_]
                           (println "Browser returned online, reconnecting WebSocket")
                           (request-reconnect-if-needed!))
          offline-handler (fn [_]
                            (println "Browser went offline, pausing WebSocket reconnect timer")
                            (clear-retry-timer!)
                            (swap! connection-state assoc :status :disconnected)
                            (when-let [socket (:socket @runtime-state)]
                              (swap! runtime-state assoc :intentional-close? false)
                              (try
                                (infra/close-socket! (current-transport) socket 4001 "Offline")
                                (catch :default e
                                  (println "Error closing WebSocket while offline:" e)))))]
      (infra/add-event-listener* scheduler (infra/window-object* scheduler) "focus" focus-handler)
      (infra/add-event-listener* scheduler (infra/window-object* scheduler) "online" online-handler)
      (infra/add-event-listener* scheduler (infra/window-object* scheduler) "offline" offline-handler)
      (infra/add-event-listener* scheduler (infra/document-object* scheduler) "visibilitychange" visibility-handler)
      (swap! runtime-state assoc
             :lifecycle-hooks-installed? true
             :lifecycle-handlers {:focus focus-handler
                                  :visibility visibility-handler
                                  :online online-handler
                                  :offline offline-handler}))))

(defn- on-socket-open! [socket socket-id]
  (when (active-socket-id? socket-id)
    (clear-retry-timer!)
    (swap! connection-state assoc
           :status :connected
           :attempt 0
           :ws socket
           :last-activity-at-ms (infra/now-ms* (current-clock)))
    (println "WebSocket connected")
    ;; Rebuild deterministic subscription state first, then replay queued intent.
    (replay-desired-subscriptions! socket)
    (flush-queued-messages! socket)))

(defn- on-socket-close! [event socket-id]
  (when (active-socket-id? socket-id)
    (let [close-info {:code (or (.-code event) 0)
                      :reason (or (.-reason event) "")
                      :was-clean? (boolean (.-wasClean event))
                      :at-ms (infra/now-ms* (current-clock))}
          intentional? (:intentional-close? @runtime-state)]
      (swap! runtime-state assoc :socket nil :active-socket-id nil)
      (swap! connection-state assoc
             :ws nil
             :last-close close-info)
      (println "WebSocket disconnected. Code:" (:code close-info) "Reason:" (:reason close-info))
      (if intentional?
        (do
          (clear-retry-timer!)
          (swap! connection-state assoc :status :disconnected))
        (do
          (swap! connection-state update :attempt (fnil inc 0))
          (swap! connection-state assoc :status :reconnecting)
          (schedule-reconnect!))))))

(defn- on-socket-error! [event socket-id]
  (when (active-socket-id? socket-id)
    (println "WebSocket error:" event)))

(defn- create-and-bind-socket! [ws-url]
  (try
    (let [socket-id (inc (:socket-id @runtime-state))
          reconnecting? (pos? (:attempt @connection-state))
          socket (infra/connect-websocket!
                   (current-transport)
                   ws-url
                   {:on-open (fn [_] (on-socket-open! (:socket @runtime-state) socket-id))
                    :on-message handle-message!
                    :on-close (fn [event] (on-socket-close! event socket-id))
                    :on-error (fn [event] (on-socket-error! event socket-id))})]
      (swap! runtime-state assoc
             :socket-id socket-id
             :active-socket-id socket-id
             :socket socket
             :intentional-close? false)
      (swap! connection-state assoc
             :status (if reconnecting? :reconnecting :connecting)
             :ws socket
             :next-retry-at-ms nil)
      (println "Connecting to WebSocket:" ws-url))
    (catch :default e
      (println "Failed to create WebSocket connection:" e)
      (swap! connection-state update :attempt (fnil inc 0))
      (swap! connection-state assoc :status :reconnecting)
      (schedule-reconnect!))))

(defn schedule-reconnect! []
  (clear-retry-timer!)
  (cond
    (:intentional-close? @runtime-state)
    (swap! connection-state assoc :status :disconnected :next-retry-at-ms nil)

    (not (:ws-url @runtime-state))
    (swap! connection-state assoc :status :disconnected :next-retry-at-ms nil)

    (not (online?))
    (do
      (println "Skipping WebSocket retry while offline; waiting for online event")
      (swap! connection-state assoc :status :disconnected :next-retry-at-ms nil))

    :else
    (let [attempt (max 1 (:attempt @connection-state))
          delay-ms (calculate-retry-delay-ms attempt (hidden-tab?))
          retry-at (+ (infra/now-ms* (current-clock)) delay-ms)
          timer-id (infra/schedule-timeout*
                     (current-scheduler)
                     (fn []
                       (swap! runtime-state assoc :retry-timer nil)
                       (swap! connection-state assoc :next-retry-at-ms nil)
                       (attempt-connect!))
                     delay-ms)]
      (swap! runtime-state assoc :retry-timer timer-id)
      (swap! connection-state assoc
             :status :reconnecting
             :next-retry-at-ms retry-at)
      (println "Scheduling WebSocket reconnect in" delay-ms "ms"))))

(defn attempt-connect! []
  (when-let [ws-url (:ws-url @runtime-state)]
    (if (not (online?))
      (do
        (swap! connection-state assoc :status :disconnected :next-retry-at-ms nil)
        (println "WebSocket connect skipped while offline"))
      (let [existing-socket (:socket @runtime-state)]
        (when-not (socket-active? existing-socket)
          (create-and-bind-socket! ws-url))))))

;; Register a message handler
(defn register-handler! [message-type handler-fn]
  (swap! message-handlers assoc message-type handler-fn))

(defn- handle-message-immediate! [event]
  (let [{:keys [ok error]} (parse-raw-envelope {:raw (.-data event)
                                                 :socket-id (:active-socket-id @runtime-state)})]
    (if ok
      (app-runtime/route-domain-message! (current-router) ok)
      (do
        (update-parse-error-metric!)
        (println "Error parsing WebSocket message:" error)))))

;; Handle incoming WebSocket messages
(defn handle-message! [event]
  (swap! connection-state assoc :last-activity-at-ms (infra/now-ms* (current-clock)))
  (let [raw-envelope {:raw (.-data event)
                      :received-at-ms (infra/now-ms* (current-clock))
                      :socket-id (:active-socket-id @runtime-state)}
        ingress-raw-ch (runtime-channel :ingress-raw-ch)]
    (if (app-runtime/safe-put! ingress-raw-ch raw-envelope)
      true
      (do
        ;; Fallback keeps backward compatibility if channel runtime is unavailable.
        (handle-message-immediate! event)
        false))))

;; Initialize WebSocket connection
(defn init-connection! [ws-url]
  (ensure-runtime-dependencies!)
  (swap! runtime-state assoc :ws-url ws-url :intentional-close? false)
  (start-channel-runtime!)
  (install-lifecycle-hooks!)
  (ensure-watchdog!)
  (if (socket-active? (:socket @runtime-state))
    (println "WebSocket connection already active, skipping duplicate init")
    (attempt-connect!)))

(defn disconnect! []
  (swap! runtime-state assoc :intentional-close? true)
  (clear-retry-timer!)
  (when-let [socket (:socket @runtime-state)]
    (try
      (infra/close-socket! (current-transport) socket 1000 "Intentional disconnect")
      (catch :default e
        (println "Error during WebSocket disconnect:" e))))
  (swap! runtime-state assoc :socket nil :active-socket-id nil)
  (swap! connection-state assoc :status :disconnected :ws nil :next-retry-at-ms nil))

(defn force-reconnect! []
  (swap! runtime-state assoc :intentional-close? false)
  (clear-retry-timer!)
  (when-let [socket (:socket @runtime-state)]
    ;; Disable handlers so replacing a live socket does not schedule duplicate retries.
    (infra/detach-handlers! (current-transport) socket)
    (try
      (infra/close-socket! (current-transport) socket 4000 "Force reconnect")
      (catch :default e
        (println "Error closing WebSocket during forced reconnect:" e)))
    (swap! runtime-state assoc :socket nil :active-socket-id nil))
  (attempt-connect!))

;; Send message function
(defn send-message! [data]
  (track-subscription-intent! data)
  ;; Keep synchronous send path for API compatibility while channel runtime
  ;; handles replay, ingress routing, and tiered consumers.
  (dispatch-outbound-message! data))

;; Connection status helpers
(defn connected? []
  (= (:status @connection-state) :connected))

(defn get-connection-status []
  (:status @connection-state))

(defn get-runtime-metrics []
  (:metrics @stream-runtime))

(defn get-tier-depths []
  (:tier-depth @stream-runtime))

(defn reset-manager-state! []
  ;; Primarily intended for tests.
  (disconnect!)
  (stop-channel-runtime!)
  (when-let [watchdog-id (:watchdog-timer @runtime-state)]
    (infra/clear-interval* (current-scheduler) watchdog-id))
  (reset! outbound-queue [])
  (reset! desired-subscriptions {})
  (reset! connection-config default-config)
  (reset! stream-runtime {:tier-depth {:market 0 :lossless 0}
                          :metrics {:market-coalesced 0
                                    :market-dispatched 0
                                    :lossless-dispatched 0
                                    :ingress-parse-errors 0}
                          :market-coalesce {:pending {}
                                            :timer nil}})
  (reset! connection-state {:status :disconnected
                            :attempt 0
                            :next-retry-at-ms nil
                            :last-close nil
                            :last-activity-at-ms nil
                            :queue-size 0
                            :ws nil})
  (swap! runtime-state assoc
         :socket nil
         :ws-url nil
         :retry-timer nil
         :watchdog-timer nil
         :channel-runtime nil
         :transport nil
         :scheduler nil
         :clock nil
         :router nil
         :socket-id 0
         :active-socket-id nil
         :intentional-close? false
         :lifecycle-hooks-installed? false
         :lifecycle-handlers nil))
