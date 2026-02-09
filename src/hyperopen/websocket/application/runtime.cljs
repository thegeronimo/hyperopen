(ns hyperopen.websocket.application.runtime
  (:require [cljs.core.async :as async :refer [<! chan close! put!]]
            [hyperopen.websocket.application.runtime-engine :as engine]
            [hyperopen.websocket.application.runtime-reducer :as reducer]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.infrastructure.runtime-effects :as runtime-effects]
            [hyperopen.websocket.infrastructure.transport :as infra])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defprotocol IMessageRouter
  (route-domain-message! [this envelope])
  (register-topic-handler! [this topic handler-fn])
  (stop-router! [this]))

(defn safe-put! [channel value]
  (when channel
    (try
      (boolean (put! channel value))
      (catch :default _
        false))))

(defrecord AsyncTopicRouter [bus-ch topic-pub handlers topic->tier config]
  IMessageRouter
  (route-domain-message! [_ envelope]
    (safe-put! bus-ch envelope))
  (register-topic-handler! [_ topic handler-fn]
    (when-let [{:keys [ch]} (get @handlers topic)]
      (async/unsub topic-pub topic ch)
      (close! ch))
    (let [buffer-size (or (:handler-buffer-size config) 64)
          tier (topic->tier topic)
          ch (chan (if (= tier :market)
                     (async/sliding-buffer buffer-size)
                     (async/buffer buffer-size)))]
      (swap! handlers assoc topic {:ch ch :handler handler-fn})
      (async/sub topic-pub topic ch)
      (go-loop []
        (when-let [envelope (<! ch)]
          (let [payload (:payload envelope)
                payload* (if (contains? payload :channel)
                           payload
                           (assoc payload :channel (:topic envelope)))]
            (try
              (handler-fn payload*)
              (catch :default e
                (println "WebSocket topic handler failed for" topic e))))
          (recur)))))
  (stop-router! [_]
    (doseq [[topic {:keys [ch]}] @handlers]
      (async/unsub topic-pub topic ch)
      (close! ch))
    (reset! handlers {})
    (close! bus-ch)))

(defn make-handler-router
  [{:keys [topic->tier config]
    :or {topic->tier (constantly :lossless)
         config {}}}]
  (let [bus-ch (chan (async/buffer (or (:lossless-buffer-size config) 4096)))
        topic-pub (async/pub bus-ch :topic)
        handlers (atom {})]
    (->AsyncTopicRouter bus-ch topic-pub handlers topic->tier config)))

(defn create-runtime-channels
  [{:keys [mailbox-buffer-size effects-buffer-size metrics-buffer-size dead-letter-buffer-size]}]
  {:mailbox-ch (chan (async/buffer (or mailbox-buffer-size 4096)))
   :effects-ch (chan (async/buffer (or effects-buffer-size 4096)))
   :metrics-ch (chan (async/dropping-buffer (or metrics-buffer-size 1024)))
   :dead-letter-ch (chan (async/dropping-buffer (or dead-letter-buffer-size 512)))})

(defn- command->runtime-msg [command now-ms]
  (cond
    (model/runtime-msg? command)
    command

    (model/connection-command? command)
    (case (:op command)
      :runtime/stop (model/make-runtime-msg :cmd/disconnect (:ts command))
      :outbound/intent (model/make-runtime-msg :cmd/send-message (:ts command) {:data (:data command)})
      :connection/connect (if (:force? command)
                            (model/make-runtime-msg :cmd/force-reconnect (:ts command))
                            (model/make-runtime-msg :cmd/init-connection (:ts command)
                                                    {:ws-url (:ws-url command)}))
      (model/make-runtime-msg :evt/parse-error
                              (:ts command)
                              {:error (js/Error. (str "Unsupported connection command: " (:op command)))
                               :raw command}))

    (and (map? command) (keyword? (:op command)))
    (command->runtime-msg (model/make-connection-command (:op command)
                                                         (or (:ts command) (now-ms))
                                                         (dissoc command :op :ts))
                          now-ms)

    :else
    (model/make-runtime-msg :evt/parse-error
                            (now-ms)
                            {:error (js/Error. "Unsupported command payload")
                             :raw command})))

(defn- transport-event->runtime-msg [event now-ms]
  (cond
    (model/runtime-msg? event)
    event

    (model/transport-event? event)
    (case (:event/type event)
      :socket/open (model/make-runtime-msg :evt/socket-open (:ts event) {:socket-id (:socket-id event)})
      :socket/message (model/make-runtime-msg :evt/socket-message (:ts event)
                                              {:socket-id (:socket-id event)
                                               :recv-at-ms (or (:recv-at-ms event) (:ts event))
                                               :raw (:raw event)})
      :socket/close (model/make-runtime-msg :evt/socket-close (:ts event)
                                            {:socket-id (:socket-id event)
                                             :code (:code event)
                                             :reason (:reason event)
                                             :at-ms (or (:at-ms event) (:ts event))
                                             :was-clean? (:was-clean? event)})
      :socket/error (model/make-runtime-msg :evt/socket-error (:ts event)
                                            {:socket-id (:socket-id event)
                                             :error (:error event)})
      :lifecycle/focus (model/make-runtime-msg :evt/lifecycle-focus (:ts event))
      :lifecycle/online (model/make-runtime-msg :evt/lifecycle-online (:ts event))
      :lifecycle/offline (model/make-runtime-msg :evt/lifecycle-offline (:ts event))
      :lifecycle/visible (model/make-runtime-msg :evt/lifecycle-visible (:ts event))
      :timer/retry (model/make-runtime-msg :evt/timer-retry-fired (:ts event))
      :timer/watchdog (model/make-runtime-msg :evt/timer-watchdog-fired (:ts event))
      :timer/health (model/make-runtime-msg :evt/timer-health-tick (:ts event)
                                            {:now-ms (or (:now-ms event) (:ts event))})
      :timer/market-flush (model/make-runtime-msg :evt/timer-market-flush-fired (:ts event))
      (model/make-runtime-msg :evt/parse-error (:ts event)
                              {:error (js/Error. (str "Unsupported transport event: " (:event/type event)))
                               :raw event}))

    (and (map? event) (keyword? (:event/type event)))
    (transport-event->runtime-msg (model/make-transport-event (:event/type event)
                                                              (or (:ts event) (now-ms))
                                                              (dissoc event :event/type :ts))
                                  now-ms)

    :else
    (model/make-runtime-msg :evt/parse-error
                            (now-ms)
                            {:error (js/Error. "Unsupported transport payload")
                             :raw event})))

(defn start-runtime!
  [{:keys [config
           parse-raw-envelope
           topic->tier
           router
           connection-state
           stream-runtime
           transport
           scheduler
           clock
           calculate-retry-delay-ms]}]
  (let [io-state (atom {:sockets {}
                        :timers {}
                        :active-socket-id nil
                        :lifecycle-installed? false
                        :lifecycle-handlers nil})
        step-fn (fn [state msg]
                  (reducer/step {:calculate-retry-delay-ms calculate-retry-delay-ms}
                                state
                                msg))
        engine-instance (engine/start-engine!
                          {:initial-state (reducer/initial-runtime-state config)
                           :reducer step-fn
                           :now-ms #(infra/now-ms* clock)
                           :interpret-effect! runtime-effects/interpret-effect!
                           :context {:transport transport
                                     :scheduler scheduler
                                     :clock clock
                                     :io-state io-state
                                     :parse-raw-envelope parse-raw-envelope
                                     :register-router-handler! #(register-topic-handler! router %1 %2)
                                     :dispatch-envelope! #(route-domain-message! router %)
                                     :connection-state-atom connection-state
                                     :stream-runtime-atom stream-runtime}})]
    {:engine engine-instance
     :io-state io-state
     :router router
     :transport transport
     :scheduler scheduler
     :clock clock
     :dispatch! (fn [msg]
                  (engine/dispatch! engine-instance msg))}))

(defn publish-command! [runtime command]
  (let [now-ms #(infra/now-ms* (:clock runtime))
        msg (command->runtime-msg command now-ms)]
    ((:dispatch! runtime) msg)))

(defn publish-transport-event! [runtime event]
  (let [now-ms #(infra/now-ms* (:clock runtime))
        msg (transport-event->runtime-msg event now-ms)]
    ((:dispatch! runtime) msg)))

(defn stop-runtime! [runtime]
  (when runtime
    (let [io-state (:io-state runtime)
          scheduler (:scheduler runtime)
          transport (:transport runtime)]
      (doseq [timer-id (vals (get @io-state :timers {}))]
        (infra/clear-timeout* scheduler timer-id)
        (infra/clear-interval* scheduler timer-id))
      (swap! io-state assoc :timers {})
      (doseq [socket (vals (get @io-state :sockets {}))]
        (try
          (infra/detach-handlers! transport socket)
          (infra/close-socket! transport socket 1000 "Runtime stop")
          (catch :default _ nil)))
      (swap! io-state assoc :sockets {} :active-socket-id nil)
      (when-let [router (:router runtime)]
        (stop-router! router))
      (engine/stop-engine! (:engine runtime)))))
