(ns hyperopen.websocket.infrastructure.runtime-effects
  (:require [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.infrastructure.transport :as infra]))

(defn- now-ms [clock]
  (infra/now-ms* clock))

(defn- clear-timer! [scheduler io-state timer-key clear-fn]
  (when-let [timer-id (get-in @io-state [:timers timer-key])]
    (clear-fn scheduler timer-id)
    (swap! io-state update :timers dissoc timer-key)))

(defn- socket-for-id [io-state socket-id]
  (get-in @io-state [:sockets socket-id]))

(def ^:private projection-fingerprint-path
  [:projection-fingerprints])

(defn- applied-projection-fingerprint
  [io-state projection-key]
  (get-in @io-state (conj projection-fingerprint-path projection-key)))

(defn- store-projection-fingerprint!
  [io-state projection-key projection-fingerprint]
  (swap! io-state assoc-in (conj projection-fingerprint-path projection-key) projection-fingerprint))

(defn- maybe-update-projection!
  [io-state projection-key projection-fingerprint update-projection-fn]
  (when (not= (applied-projection-fingerprint io-state projection-key)
              projection-fingerprint)
    (update-projection-fn)
    (store-projection-fingerprint! io-state projection-key projection-fingerprint)))

(defn- attach-active-socket
  [io-state runtime-view]
  (let [active-socket-id (:active-socket-id runtime-view)
        socket (socket-for-id io-state active-socket-id)]
    (assoc-in runtime-view [:connection :ws] socket)))

(defn- update-runtime-view-projection!
  [runtime-view-atom io-state runtime-view projection-fingerprint]
  (let [runtime-view* (or runtime-view {})
        fingerprint (or projection-fingerprint runtime-view*)]
    (maybe-update-projection! io-state :runtime-view fingerprint
                              (fn []
                                (reset! runtime-view-atom
                                        (attach-active-socket io-state runtime-view*))))))

(defmulti ^:private interpret-effect-by-type!
  (fn [_ctx effect]
    (:fx/type effect)))

(defmethod interpret-effect-by-type! :fx/socket-connect
  [{:keys [transport clock io-state dispatch!]} effect]
  (let [{:keys [ws-url socket-id]} effect
        socket (infra/connect-websocket!
                transport
                ws-url
                {:on-open (fn [_]
                            (let [at-ms (now-ms clock)]
                              (dispatch! {:msg/type :evt/socket-open
                                          :socket-id socket-id
                                          :at-ms at-ms
                                          :ts at-ms})))
                 :on-message (fn [event]
                               (let [recv-at-ms (now-ms clock)]
                                 (dispatch! {:msg/type :evt/socket-message
                                             :socket-id socket-id
                                             :raw (.-data event)
                                             :recv-at-ms recv-at-ms
                                             :ts recv-at-ms})))
                 :on-close (fn [event]
                             (let [at-ms (now-ms clock)]
                               (dispatch! {:msg/type :evt/socket-close
                                           :socket-id socket-id
                                           :code (or (.-code event) 0)
                                           :reason (or (.-reason event) "")
                                           :was-clean? (boolean (.-wasClean event))
                                           :at-ms at-ms
                                           :ts at-ms})))
                 :on-error (fn [event]
                             (let [at-ms (now-ms clock)]
                               (dispatch! {:msg/type :evt/socket-error
                                           :socket-id socket-id
                                           :error event
                                           :ts at-ms})))})]
    (swap! io-state assoc-in [:sockets socket-id] socket)
    (swap! io-state assoc :active-socket-id socket-id)))

(defmethod interpret-effect-by-type! :fx/socket-send
  [{:keys [transport clock io-state dispatch!]} effect]
  (let [{:keys [socket-id data]} effect
        socket (socket-for-id io-state socket-id)]
    (when socket
      (try
        (when (infra/socket-open? transport socket)
          (infra/send-json! transport socket data))
        (catch :default e
          (dispatch! {:msg/type :evt/socket-error
                      :socket-id socket-id
                      :error e
                      :ts (now-ms clock)}))))))

(defmethod interpret-effect-by-type! :fx/socket-close
  [{:keys [transport io-state]} effect]
  (let [{:keys [socket-id code reason]} effect
        socket (socket-for-id io-state socket-id)]
    (when socket
      (try
        (infra/close-socket! transport socket (or code 1000) (or reason ""))
        (catch :default _ nil)))))

(defmethod interpret-effect-by-type! :fx/socket-detach-handlers
  [{:keys [transport io-state]} effect]
  (let [socket (socket-for-id io-state (:socket-id effect))]
    (when socket
      (infra/detach-handlers! transport socket))))

(defmethod interpret-effect-by-type! :fx/timer-set-timeout
  [{:keys [scheduler clock io-state dispatch!]} effect]
  (let [{:keys [timer-key ms msg]} effect]
    (clear-timer! scheduler io-state timer-key infra/clear-timeout*)
    (let [timer-id (infra/schedule-timeout*
                    scheduler
                    (fn []
                      (swap! io-state update :timers dissoc timer-key)
                      (let [fired-at-ms (now-ms clock)]
                        (dispatch! (assoc msg :ts fired-at-ms :now-ms fired-at-ms))))
                    ms)]
      (swap! io-state assoc-in [:timers timer-key] timer-id))))

(defmethod interpret-effect-by-type! :fx/timer-clear-timeout
  [{:keys [scheduler io-state]} effect]
  (clear-timer! scheduler io-state (:timer-key effect) infra/clear-timeout*))

(defmethod interpret-effect-by-type! :fx/timer-set-interval
  [{:keys [scheduler clock io-state dispatch!]} effect]
  (let [{:keys [timer-key ms msg]} effect]
    (clear-timer! scheduler io-state timer-key infra/clear-interval*)
    (let [timer-id (infra/schedule-interval*
                    scheduler
                    (fn []
                      (let [fired-at-ms (now-ms clock)]
                        (dispatch! (assoc msg :ts fired-at-ms :now-ms fired-at-ms))))
                    ms)]
      (swap! io-state assoc-in [:timers timer-key] timer-id))))

(defmethod interpret-effect-by-type! :fx/timer-clear-interval
  [{:keys [scheduler io-state]} effect]
  (clear-timer! scheduler io-state (:timer-key effect) infra/clear-interval*))

(defmethod interpret-effect-by-type! :fx/lifecycle-install-listeners
  [{:keys [scheduler clock io-state dispatch!]} _effect]
  (when-not (:lifecycle-installed? @io-state)
    (let [focus-handler (fn [_]
                          (dispatch! {:msg/type :evt/lifecycle-focus
                                      :ts (now-ms clock)}))
          online-handler (fn [_]
                           (dispatch! {:msg/type :evt/lifecycle-online
                                       :ts (now-ms clock)}))
          offline-handler (fn [_]
                            (dispatch! {:msg/type :evt/lifecycle-offline
                                        :ts (now-ms clock)}))
          visibility-handler (fn [_]
                               (dispatch! {:msg/type (if (infra/hidden-tab?* scheduler)
                                                       :evt/lifecycle-hidden
                                                       :evt/lifecycle-visible)
                                           :ts (now-ms clock)}))]
      (infra/add-event-listener* scheduler (infra/window-object* scheduler) "focus" focus-handler)
      (infra/add-event-listener* scheduler (infra/window-object* scheduler) "online" online-handler)
      (infra/add-event-listener* scheduler (infra/window-object* scheduler) "offline" offline-handler)
      (infra/add-event-listener* scheduler (infra/document-object* scheduler) "visibilitychange" visibility-handler)
      (swap! io-state assoc
             :lifecycle-installed? true
             :lifecycle-handlers {:focus focus-handler
                                  :online online-handler
                                  :offline offline-handler
                                  :visibility visibility-handler}))))

(defmethod interpret-effect-by-type! :fx/router-register-handler
  [{:keys [register-router-handler!]} effect]
  (register-router-handler! (:topic effect) (:handler-fn effect)))

(defmethod interpret-effect-by-type! :fx/router-dispatch-envelope
  [{:keys [hydrate-envelope dispatch-envelope!]} effect]
  (let [envelope (:envelope effect)
        envelope* (if hydrate-envelope
                    (hydrate-envelope envelope)
                    envelope)]
    (dispatch-envelope! envelope*)))

(defmethod interpret-effect-by-type! :fx/parse-raw-message
  [{:keys [clock parse-raw-envelope dispatch!]} effect]
  (let [{:keys [raw socket-id recv-at-ms]} effect
        decode-at-ms (or recv-at-ms (now-ms clock))
        {:keys [ok error]} (parse-raw-envelope {:raw raw
                                                :socket-id socket-id})]
    (if ok
      (dispatch! {:msg/type :evt/decoded-envelope
                  :envelope ok
                  :socket-id socket-id
                  :recv-at-ms decode-at-ms
                  :ts decode-at-ms})
      (dispatch! {:msg/type :evt/parse-error
                  :error error
                  :raw raw
                  :socket-id socket-id
                  :recv-at-ms decode-at-ms
                  :ts decode-at-ms}))))

(defmethod interpret-effect-by-type! :fx/project-runtime-view
  [{:keys [runtime-view-atom io-state]} effect]
  (update-runtime-view-projection! runtime-view-atom
                                   io-state
                                   (:runtime-view effect)
                                   (:projection-fingerprint effect)))

(defmethod interpret-effect-by-type! :fx/log
  [_ effect]
  (let [{:keys [level message error]} effect]
    (telemetry/emit! :websocket/runtime-log
                     {:level (or level :info)
                      :message message
                      :error (when error (str error))})))

(defmethod interpret-effect-by-type! :fx/dead-letter
  [_ effect]
  (telemetry/emit! :websocket/dead-letter effect))

(defmethod interpret-effect-by-type! :default
  [_ _]
  nil)

(defn interpret-effect!
  [ctx effect]
  (interpret-effect-by-type! ctx effect))
