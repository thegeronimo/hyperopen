(ns hyperopen.websocket.infrastructure.transport)

(def ws-ready-state-connecting 0)
(def ws-ready-state-open 1)

(defprotocol ITransport
  (connect-websocket! [this ws-url handlers])
  (send-json! [this socket data])
  (close-socket! [this socket code reason])
  (ready-state [this socket])
  (detach-handlers! [this socket]))

(defprotocol IScheduler
  (schedule-timeout* [this f ms])
  (clear-timeout* [this timer-id])
  (schedule-interval* [this f ms])
  (clear-interval* [this timer-id])
  (window-object* [this])
  (document-object* [this])
  (navigator-object* [this])
  (add-event-listener* [this target event-name handler])
  (online?* [this])
  (hidden-tab?* [this]))

(defprotocol IClock
  (now-ms* [this])
  (random-value* [this]))

(defrecord FunctionTransport [create-websocket-fn]
  ITransport
  (connect-websocket! [_ ws-url handlers]
    (let [socket (create-websocket-fn ws-url)]
      (set! (.-onopen socket) (:on-open handlers))
      (set! (.-onmessage socket) (:on-message handlers))
      (set! (.-onclose socket) (:on-close handlers))
      (set! (.-onerror socket) (:on-error handlers))
      socket))
  (send-json! [_ socket data]
    (.send socket (js/JSON.stringify (clj->js data))))
  (close-socket! [_ socket code reason]
    (.close socket code reason))
  (ready-state [_ socket]
    (when socket
      (.-readyState socket)))
  (detach-handlers! [_ socket]
    (when socket
      (set! (.-onopen socket) nil)
      (set! (.-onmessage socket) nil)
      (set! (.-onclose socket) nil)
      (set! (.-onerror socket) nil))))

(defrecord FunctionScheduler [schedule-timeout-fn
                              clear-timeout-fn
                              schedule-interval-fn
                              clear-interval-fn
                              window-object-fn
                              document-object-fn
                              navigator-object-fn
                              add-event-listener-fn]
  IScheduler
  (schedule-timeout* [_ f ms]
    (schedule-timeout-fn f ms))
  (clear-timeout* [_ timer-id]
    (clear-timeout-fn timer-id))
  (schedule-interval* [_ f ms]
    (schedule-interval-fn f ms))
  (clear-interval* [_ timer-id]
    (clear-interval-fn timer-id))
  (window-object* [_]
    (window-object-fn))
  (document-object* [_]
    (document-object-fn))
  (navigator-object* [_]
    (navigator-object-fn))
  (add-event-listener* [_ target event-name handler]
    (add-event-listener-fn target event-name handler))
  (online?* [this]
    (if-let [nav (navigator-object* this)]
      (if (nil? (.-onLine nav))
        true
        (boolean (.-onLine nav)))
      true))
  (hidden-tab?* [this]
    (if-let [doc (document-object* this)]
      (= "hidden" (.-visibilityState doc))
      false)))

(defrecord FunctionClock [now-ms-fn random-value-fn]
  IClock
  (now-ms* [_]
    (now-ms-fn))
  (random-value* [_]
    (random-value-fn)))

(defn make-function-transport [create-websocket-fn]
  (->FunctionTransport create-websocket-fn))

(defn make-function-scheduler
  [{:keys [schedule-timeout-fn
           clear-timeout-fn
           schedule-interval-fn
           clear-interval-fn
           window-object-fn
           document-object-fn
           navigator-object-fn
           add-event-listener-fn]}]
  (->FunctionScheduler schedule-timeout-fn
                       clear-timeout-fn
                       schedule-interval-fn
                       clear-interval-fn
                       window-object-fn
                       document-object-fn
                       navigator-object-fn
                       add-event-listener-fn))

(defn make-function-clock [now-ms-fn random-value-fn]
  (->FunctionClock now-ms-fn random-value-fn))

(defn socket-open? [transport socket]
  (= ws-ready-state-open (ready-state transport socket)))

(defn socket-connecting? [transport socket]
  (= ws-ready-state-connecting (ready-state transport socket)))

(defn socket-active? [transport socket]
  (or (socket-open? transport socket)
      (socket-connecting? transport socket)))

