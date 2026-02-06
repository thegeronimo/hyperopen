(ns hyperopen.websocket.application.runtime
  (:require [cljs.core.async :as async :refer [<! >! chan close! put!]]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.infrastructure.transport :as infra])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defprotocol IMessageRouter
  (route-domain-message! [this envelope]))

(defrecord HandlerRouter [handlers]
  IMessageRouter
  (route-domain-message! [_ envelope]
    (let [payload (:payload envelope)
          topic (:topic envelope)
          payload* (if (contains? payload :channel)
                     payload
                     (assoc payload :channel topic))]
      (when-let [handler (get @handlers topic)]
        (handler payload*)))))

(defn make-handler-router [handlers]
  (->HandlerRouter handlers))

(defn create-runtime-channels
  [{:keys [control-buffer-size
           outbound-buffer-size
           ingress-raw-buffer-size
           ingress-decoded-buffer-size
           market-buffer-size
           lossless-buffer-size]}]
  {:control-ch (chan (async/buffer control-buffer-size))
   :outbound-ch (chan (async/buffer outbound-buffer-size))
   :ingress-raw-ch (chan (async/sliding-buffer ingress-raw-buffer-size))
   :ingress-decoded-ch (chan (async/sliding-buffer ingress-decoded-buffer-size))
   :market-tier-ch (chan (async/sliding-buffer market-buffer-size))
   :lossless-tier-ch (chan (async/buffer lossless-buffer-size))})

(defn safe-put! [channel value]
  (when channel
    (try
      (boolean (put! channel value))
      (catch :default _
        false))))

(defn- update-tier-depth! [stream-runtime tier f]
  (swap! stream-runtime update-in [:tier-depth tier] (fnil f 0)))

(defn- increment-metric! [stream-runtime metric-key]
  (swap! stream-runtime update-in [:metrics metric-key] (fnil inc 0)))

(defn- queue-market-envelope! [{:keys [stream-runtime config scheduler publish-control! make-command]} envelope]
  (let [key (model/market-coalesce-key envelope)
        coalesce-window-ms (:market-coalesce-window-ms config)
        replacing? (contains? (get-in @stream-runtime [:market-coalesce :pending] {}) key)
        needs-timer? (nil? (get-in @stream-runtime [:market-coalesce :timer]))]
    (swap! stream-runtime assoc-in [:market-coalesce :pending key] envelope)
    (when replacing?
      (increment-metric! stream-runtime :market-coalesced))
    (when needs-timer?
      (let [timer-id (infra/schedule-timeout* scheduler
                                        (fn []
                                          (swap! stream-runtime assoc-in [:market-coalesce :timer] nil)
                                          (publish-control! (make-command :flush-market-coalesced)))
                                        coalesce-window-ms)]
        (swap! stream-runtime assoc-in [:market-coalesce :timer] timer-id)))))

(defn- flush-market-coalesced! [{:keys [stream-runtime router]}]
  (let [pending (vals (get-in @stream-runtime [:market-coalesce :pending] {}))]
    (swap! stream-runtime assoc-in [:market-coalesce :pending] {})
    (doseq [envelope (sort-by :ts pending)]
      (increment-metric! stream-runtime :market-dispatched)
      (route-domain-message! router envelope))))

(defn- dispatch-lossless-envelope! [{:keys [stream-runtime router]} envelope]
  (increment-metric! stream-runtime :lossless-dispatched)
  (route-domain-message! router envelope))

(defn default-command-handlers
  [{:keys [desired-subscriptions
           drain-queued-messages!
           reconnect-if-needed!
           stream-runtime
           router
           dispatch-outbound-message!]}]
  {:send-outbound
   (fn [{:keys [data]} channels _]
     (safe-put! (:outbound-ch channels) data))

   :replay-subscriptions
   (fn [_ channels _]
     (doseq [subscription (->> @desired-subscriptions vals (sort-by pr-str))]
       (safe-put! (:outbound-ch channels)
                  {:method "subscribe"
                   :subscription subscription})))

   :flush-outbound-queue
   (fn [_ channels _]
     (doseq [queued (drain-queued-messages!)]
       (safe-put! (:outbound-ch channels) queued)))

   :flush-market-coalesced
   (fn [_ _ _]
     (flush-market-coalesced! {:stream-runtime stream-runtime
                               :router router}))

   :reconnect-if-needed
   (fn [_ _ _]
     (reconnect-if-needed!))

   :dispatch-outbound
   (fn [{:keys [data]} _ _]
     (dispatch-outbound-message! data))})

(defn start-runtime-loops!
  [{:keys [channels
           parse-raw-envelope
           topic->tier
           router
           config
           stream-runtime
           scheduler
           publish-control!
           make-command
           reconnect-if-needed!
           drain-queued-messages!
           desired-subscriptions
           dispatch-outbound-message!
           command-handlers]}]
  (let [{:keys [control-ch outbound-ch ingress-raw-ch ingress-decoded-ch market-tier-ch lossless-tier-ch]} channels
        control-handlers (merge (default-command-handlers
                                  {:desired-subscriptions desired-subscriptions
                                   :drain-queued-messages! drain-queued-messages!
                                   :reconnect-if-needed! reconnect-if-needed!
                                   :stream-runtime stream-runtime
                                   :router router
                                   :dispatch-outbound-message! dispatch-outbound-message!})
                                command-handlers)
        market-ctx {:stream-runtime stream-runtime
                    :config config
                    :scheduler scheduler
                    :publish-control! publish-control!
                    :make-command make-command}]
    ;; Ingress decode loop: raw websocket text -> domain envelope.
    (go-loop []
      (when-let [raw-envelope (<! ingress-raw-ch)]
        (let [{:keys [ok error]} (parse-raw-envelope raw-envelope)]
          (if ok
            (>! ingress-decoded-ch (update ok :tier #(or % (topic->tier (:topic ok)))))
            (do
              (increment-metric! stream-runtime :ingress-parse-errors)
              (println "Error parsing WebSocket message:" error))))
        (recur)))
    ;; Demux loop: decoded envelopes -> tier channels.
    (go-loop []
      (when-let [envelope (<! ingress-decoded-ch)]
        (case (:tier envelope)
          :market
          (do
            (update-tier-depth! stream-runtime :market inc)
            (>! market-tier-ch envelope))
          :lossless
          (do
            (update-tier-depth! stream-runtime :lossless inc)
            (>! lossless-tier-ch envelope))
          (dispatch-lossless-envelope! {:stream-runtime stream-runtime
                                        :router router}
                                      envelope))
        (recur)))
    ;; Market loop: coalesced by topic/coin before dispatch.
    (go-loop []
      (when-let [envelope (<! market-tier-ch)]
        (update-tier-depth! stream-runtime :market #(max 0 (dec %)))
        (queue-market-envelope! market-ctx envelope)
        (recur)))
    ;; Lossless loop: strict ordered dispatch.
    (go-loop []
      (when-let [envelope (<! lossless-tier-ch)]
        (update-tier-depth! stream-runtime :lossless #(max 0 (dec %)))
        (dispatch-lossless-envelope! {:stream-runtime stream-runtime
                                      :router router}
                                    envelope)
        (when (> (get-in @stream-runtime [:tier-depth :lossless] 0)
                 (:lossless-depth-alert-threshold config))
          (println "Lossless websocket queue depth is elevated:"
                   (get-in @stream-runtime [:tier-depth :lossless])))
        (recur)))
    ;; Outbound loop.
    (go-loop []
      (when-let [data (<! outbound-ch)]
        (dispatch-outbound-message! data)
        (recur)))
    ;; Control loop: handler registry replaces hardcoded case branching.
    (go-loop []
      (when-let [command (<! control-ch)]
        (try
          (when-let [handler (get control-handlers (:op command))]
            (handler command channels {:stream-runtime stream-runtime
                                       :router router}))
          (catch :default e
            (println "WebSocket control loop command failed:" e)))
        (recur)))))

(defn stop-runtime! [channels]
  (doseq [channel (vals (or channels {}))]
    (close! channel)))
