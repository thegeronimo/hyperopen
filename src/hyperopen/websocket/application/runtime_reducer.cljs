(ns hyperopen.websocket.application.runtime-reducer
  (:require [hyperopen.websocket.application.runtime.connection :as connection]
            [hyperopen.websocket.application.runtime.health-projection :as health-projection]
            [hyperopen.websocket.application.runtime.market :as market]
            [hyperopen.websocket.application.runtime.projections :as projections]
            [hyperopen.websocket.application.runtime.subscriptions :as subscriptions]
            [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.health :as health]))

(def ^:private default-runtime-config
  {:max-queue-size 1000
   :watchdog-interval-ms 10000
   :health-tick-interval-ms 1000
   :transport-live-threshold-ms health/default-transport-live-threshold-ms
   :freshness-hysteresis-consecutive health/default-freshness-hysteresis-consecutive
   :stale-threshold-ms health/default-stream-stale-threshold-ms
   :stale-visible-ms 45000
   :stale-hidden-ms 180000
   :market-coalesce-window-ms 16})

(defn- normalize-runtime-config
  [config]
  (let [config* (merge default-runtime-config (or config {}))]
    ;; Reducer defaults keep direct reducer usage safe in tests/replay tooling.
    (assert (pos-int? (:max-queue-size config*)) "runtime config :max-queue-size must be a positive integer")
    (assert (pos-int? (:watchdog-interval-ms config*)) "runtime config :watchdog-interval-ms must be a positive integer")
    (assert (pos-int? (:health-tick-interval-ms config*)) "runtime config :health-tick-interval-ms must be a positive integer")
    (assert (pos-int? (:stale-visible-ms config*)) "runtime config :stale-visible-ms must be a positive integer")
    (assert (pos-int? (:stale-hidden-ms config*)) "runtime config :stale-hidden-ms must be a positive integer")
    (assert (pos-int? (:market-coalesce-window-ms config*)) "runtime config :market-coalesce-window-ms must be a positive integer")
    config*))

(defn initial-runtime-state [config]
  ;; Runtime message time semantics:
  ;; - :ts is message creation time.
  ;; - :at-ms / :recv-at-ms are transport-originated timestamps when present.
  ;; - :now-ms in state is reducer's latest known runtime time.
  ;;
  ;; Runtime states:
  ;; - :disconnected, :connecting, :connected, :reconnecting.
  ;;
  ;; Tier meanings:
  ;; - :market streams are coalesced briefly before dispatch.
  ;; - :lossless streams dispatch immediately in order.
  ;;
  ;; Expected traffic:
  ;; - true when subscribed streams have stale-threshold policy and transport freshness
  ;;   should degrade when payloads stop arriving.
  (let [config* (normalize-runtime-config config)]
    {:config config*
   :ws-url nil
   :status :disconnected
   :attempt 0
   :next-retry-at-ms nil
   :last-close nil
   :last-activity-at-ms nil
   :now-ms nil
   :queue []
   :desired-subscriptions {}
   :streams {}
   :transport {:last-recv-at-ms nil
               :connected-at-ms nil
               :expected-traffic? false
               :freshness :offline
               :freshness-pending-status nil
               :freshness-pending-count 0}
   :intentional-close? false
   :socket-id 0
   :active-socket-id nil
   :online? true
   :hidden? false
   :lifecycle-installed? false
   :retry-timer-active? false
   :watchdog-active? false
   :health-tick-active? false
   :market-flush-active? false
   :market-coalesce {:pending {}}
   :tier-depth {:market 0 :lossless 0}
   :metrics {:market-coalesced 0
             :market-dispatched 0
             :lossless-dispatched 0
             :ingress-parse-errors 0}
   :health-projection-fingerprint nil
   :health-projection-last-refresh-at-ms nil}))

(defn- result [state effects msg-type force-health-refresh?]
  (let [state* (health-projection/maybe-refresh-health-hysteresis state msg-type force-health-refresh?)]
    {:state state*
     :effects (projections/append-runtime-view-projection state* (vec effects))}))

(defn- emit-runtime-result
  [msg-type state effects]
  (result state effects msg-type false))

(defn- emit-runtime-result-force-health
  [msg-type state effects]
  (result state effects msg-type true))

(defmulti ^:private handle-runtime-msg
  (fn [_deps _state msg]
    (:msg/type msg)))

(defmethod handle-runtime-msg :cmd/init-connection
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        config (:config state)
        state* (-> state
                   (assoc :ws-url (:ws-url msg)
                          :intentional-close? false)
                   (connection/with-now ts))
        [state1 effects1] (if (:lifecycle-installed? state*)
                            [state* []]
                            [(assoc state* :lifecycle-installed? true)
                             [(model/make-runtime-effect :fx/lifecycle-install-listeners {})]])
        [state2 effects2] (if (:watchdog-active? state1)
                            [state1 effects1]
                            [(assoc state1 :watchdog-active? true)
                             (conj effects1
                                   (model/make-runtime-effect :fx/timer-set-interval
                                                              {:timer-key :watchdog
                                                               :ms (or (:watchdog-interval-ms config) 10000)
                                                               :msg {:msg/type :evt/timer-watchdog-fired}}))])
        [state3 effects3] (connection/maybe-start-health-tick state2 effects2)
        [state4 effects4] (connection/ensure-connect state3 effects3)]
    (emit-runtime-result msg-type state4 effects4)))

(defmethod handle-runtime-msg :cmd/disconnect
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        socket-id (:active-socket-id state)
        [state1 effects1] (connection/maybe-clear-retry state [])
        [state2 effects2] (connection/maybe-clear-watchdog state1 effects1)
        [state3 effects3] (market/maybe-clear-market-flush state2 effects2)
        [state4 effects4] (connection/maybe-clear-health-tick state3 effects3)
        effects5 (connection/add-socket-teardown-effects effects4 socket-id 1000 "Intentional disconnect")
        state5 (-> state4
                   (connection/with-now ts)
                   (assoc :intentional-close? true
                          :status :disconnected
                          :active-socket-id nil
                          :next-retry-at-ms nil)
                   (assoc-in [:transport :connected-at-ms] nil))]
    (emit-runtime-result msg-type state5 effects5)))

(defmethod handle-runtime-msg :cmd/force-reconnect
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        socket-id (:active-socket-id state)
        [state0 effects0] (connection/maybe-clear-retry (-> state
                                                (assoc :intentional-close? false)
                                                (connection/with-now ts))
                                             [])
        [state1 effects1] (market/maybe-clear-market-flush state0 effects0)
        effects2 (connection/add-socket-teardown-effects effects1 socket-id 4000 "Force reconnect")
        state2 (assoc state1 :active-socket-id nil)
        [state3 effects3] (connection/maybe-start-health-tick state2 effects2)
        [state4 effects4] (connection/ensure-connect state3 effects3)]
    (emit-runtime-result msg-type state4 effects4)))

(defmethod handle-runtime-msg :cmd/send-message
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        config (:config state)
        data (:data msg)
        at-ms (or (:at-ms msg) ts)
        desired* (model/apply-subscription-intent (:desired-subscriptions state) data)
        state* (-> state
                   (connection/with-now at-ms)
                   (assoc :desired-subscriptions desired*)
                   (subscriptions/apply-stream-intent data at-ms)
                   (subscriptions/refresh-expected-traffic))]
    (if (and (= :connected (:status state*))
             (:active-socket-id state*))
      (emit-runtime-result msg-type state*
                           [(model/make-runtime-effect :fx/socket-send
                                                       {:socket-id (:active-socket-id state*)
                                                        :data data})])
      (let [[queue* dropped?] (connection/with-overflow-bound (:queue state*)
                                                   (:max-queue-size config)
                                                   data)
            state1 (assoc state* :queue queue*)
            effects1 (cond-> []
                       dropped? (conj (model/make-runtime-effect :fx/log
                                                                 {:level :warn
                                                                  :message "WebSocket queue overflow, dropping oldest queued message"})))
            [state2 effects2] (connection/ensure-connect state1 effects1)]
        (emit-runtime-result msg-type state2 effects2)))))

(defmethod handle-runtime-msg :cmd/register-handler
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)]
    (emit-runtime-result msg-type
                         (connection/with-now state ts)
                         [(model/make-runtime-effect :fx/router-register-handler
                                                     {:topic (:topic msg)
                                                      :handler-fn (:handler-fn msg)})])))

(defmethod handle-runtime-msg :evt/socket-open
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)]
    (if (= (:socket-id msg) (:active-socket-id state))
      (let [open-at-ms (or (:at-ms msg) ts)
            [state1 effects1] (connection/maybe-clear-retry state [])
            state2 (-> state1
                       (connection/with-now open-at-ms)
                       (subscriptions/replay-subscriptions-as-active open-at-ms)
                       (subscriptions/refresh-expected-traffic))
            replay-msgs (->> (vals (:desired-subscriptions state2))
                             (sort-by model/subscription-key)
                             (mapv (fn [subscription]
                                     {:method "subscribe"
                                      :subscription subscription})))
            queue-msgs (:queue state2)
            effects2 (into effects1
                           (map (fn [payload]
                                  (model/make-runtime-effect :fx/socket-send
                                                             {:socket-id (:socket-id msg)
                                                              :data payload}))
                                (concat replay-msgs queue-msgs)))
            state3 (-> state2
                       (assoc :status :connected
                              :attempt 0
                              :queue []
                              :last-activity-at-ms open-at-ms)
                       (assoc-in [:transport :connected-at-ms] open-at-ms)
                       (assoc-in [:transport :last-recv-at-ms] nil))]
        (emit-runtime-result msg-type state3 effects2))
      (emit-runtime-result msg-type state []))))

(defmethod handle-runtime-msg :evt/socket-close
  [deps state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)]
    (if (= (:socket-id msg) (:active-socket-id state))
      (let [close-at-ms (or (:at-ms msg) ts)
            close-info {:code (or (:code msg) 0)
                        :reason (or (:reason msg) "")
                        :was-clean? (boolean (:was-clean? msg))
                        :at-ms close-at-ms}
            state1 (-> state
                       (connection/with-now close-at-ms)
                       (assoc :active-socket-id nil
                              :last-close close-info)
                       (assoc-in [:transport :connected-at-ms] nil))
            [state1* effects1*] (market/maybe-clear-market-flush state1 [])]
        (if (:intentional-close? state1)
          (let [[state2 effects2] (connection/maybe-clear-retry (assoc state1* :status :disconnected) effects1*)]
            (emit-runtime-result msg-type state2 effects2))
          (let [state2 (-> state1*
                           (update :attempt (fnil inc 0))
                           (assoc :status :reconnecting))
                [state3 effects3] (connection/schedule-retry deps state2 effects1* close-at-ms)]
            (emit-runtime-result msg-type state3 effects3))))
      (emit-runtime-result msg-type state []))))

(defmethod handle-runtime-msg :evt/socket-error
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)]
    (emit-runtime-result msg-type
                         (connection/with-now state ts)
                         [(model/make-runtime-effect :fx/log
                                                     {:level :warn
                                                      :message "WebSocket error"
                                                      :error (:error msg)})])))

(defmethod handle-runtime-msg :evt/lifecycle-online
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        state1 (-> state
                   (assoc :online? true)
                   (connection/with-now ts))
        [state2 effects2] (connection/ensure-connect state1 [])]
    (emit-runtime-result msg-type state2 effects2)))

(defmethod handle-runtime-msg :evt/lifecycle-offline
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        socket-id (:active-socket-id state)
        [state0 effects0] (connection/maybe-clear-retry (-> state
                                                (assoc :online? false
                                                       :status :disconnected
                                                       :active-socket-id nil)
                                                (connection/with-now ts)
                                                (assoc-in [:transport :connected-at-ms] nil))
                                             [])
        [state1 effects1] (market/maybe-clear-market-flush state0 effects0)
        effects2 (connection/add-socket-teardown-effects effects1 socket-id 4001 "Offline")]
    (emit-runtime-result msg-type state1 effects2)))

(defmethod handle-runtime-msg :evt/lifecycle-focus
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        [state1 effects1] (connection/ensure-connect (connection/with-now state ts) [])]
    (emit-runtime-result msg-type state1 effects1)))

(defmethod handle-runtime-msg :evt/lifecycle-visible
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        [state1 effects1] (connection/ensure-connect (-> state
                                             (assoc :hidden? false)
                                             (connection/with-now ts))
                                          [])]
    (emit-runtime-result msg-type state1 effects1)))

(defmethod handle-runtime-msg :evt/lifecycle-hidden
  [_ state msg]
  (emit-runtime-result (:msg/type msg)
                       (-> state
                           (assoc :hidden? true)
                           (connection/with-now (:ts msg)))
                       []))

(defmethod handle-runtime-msg :evt/timer-retry-fired
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        state1 (-> state
                   (assoc :retry-timer-active? false
                          :next-retry-at-ms nil)
                   (connection/with-now ts))
        [state2 effects2] (connection/ensure-connect state1 [])]
    (emit-runtime-result msg-type state2 effects2)))

(defmethod handle-runtime-msg :evt/timer-health-tick
  [_ state msg]
  (emit-runtime-result (:msg/type msg)
                       (connection/with-now state (or (:now-ms msg) (:ts msg)))
                       []))

(defmethod handle-runtime-msg :evt/timer-watchdog-fired
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        config (:config state)
        tick-at-ms (or (:now-ms msg) ts)
        threshold-ms (if (:hidden? state)
                       (:stale-hidden-ms config)
                       (:stale-visible-ms config))
        stale? (and (= :connected (:status state))
                    (:active-socket-id state)
                    (number? (:last-activity-at-ms state))
                    (> (- tick-at-ms (:last-activity-at-ms state)) threshold-ms))
        state1 (connection/with-now state tick-at-ms)]
    (if stale?
      (emit-runtime-result msg-type state1
                           [(model/make-runtime-effect :fx/log
                                                       {:level :warn
                                                        :message "WebSocket watchdog detected stale connection, forcing reconnect"})
                            (model/make-runtime-effect :fx/socket-close
                                                       {:socket-id (:active-socket-id state)
                                                        :code 4002
                                                        :reason "Stale websocket connection"})])
      (emit-runtime-result msg-type state1 []))))

(defmethod handle-runtime-msg :evt/socket-message
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)]
    (if (= (:socket-id msg) (:active-socket-id state))
      (let [recv-at-ms (or (:recv-at-ms msg) ts)
            state1 (-> state
                       (connection/with-now recv-at-ms)
                       (assoc :last-activity-at-ms recv-at-ms)
                       (assoc-in [:transport :last-recv-at-ms] recv-at-ms))]
        (emit-runtime-result msg-type state1
                             [(model/make-runtime-effect :fx/parse-raw-message
                                                         {:raw (:raw msg)
                                                          :socket-id (:socket-id msg)
                                                          :recv-at-ms recv-at-ms})]))
      (emit-runtime-result msg-type state []))))

(defmethod handle-runtime-msg :evt/decoded-envelope
  [_ state msg]
  (let [msg-type (:msg/type msg)]
    (if-not (connection/msg-from-active-socket? state msg)
      (emit-runtime-result msg-type state [])
      (let [ts (:ts msg)
            config (:config state)
            envelope (:envelope msg)
            recv-at-ms (or (:recv-at-ms msg) ts)
            state0 (-> state
                       (connection/with-now recv-at-ms)
                       (subscriptions/record-stream-payload envelope recv-at-ms)
                       (subscriptions/refresh-expected-traffic))]
        (if (= :market (:tier envelope))
          (let [key (model/market-coalesce-key envelope)
                replacing? (contains? (get-in state0 [:market-coalesce :pending] {}) key)
                state1 (-> state0
                           (assoc-in [:market-coalesce :pending key] envelope)
                           (cond-> replacing? (update-in [:metrics :market-coalesced] (fnil inc 0))))
                [state2 effects2] (if (:market-flush-active? state1)
                                    [state1 []]
                                    [(assoc state1 :market-flush-active? true)
                                     [(model/make-runtime-effect :fx/timer-set-timeout
                                                                 {:timer-key :market-flush
                                                                  :ms (or (:market-coalesce-window-ms config) 16)
                                                                  :msg {:msg/type :evt/timer-market-flush-fired}})]])]
            (emit-runtime-result msg-type state2 effects2))
          (let [state1 (update-in state0 [:metrics :lossless-dispatched] (fnil inc 0))]
            (emit-runtime-result-force-health msg-type state1
                                              [(model/make-runtime-effect :fx/router-dispatch-envelope
                                                                          {:envelope envelope})])))))))

(defmethod handle-runtime-msg :evt/timer-market-flush-fired
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)
        [state1 effects1] (market/flush-market-pending (connection/with-now state ts) [])]
    (emit-runtime-result msg-type state1 effects1)))

(defmethod handle-runtime-msg :evt/parse-error
  [_ state msg]
  (let [msg-type (:msg/type msg)]
    (if-not (connection/msg-from-active-socket? state msg)
      (emit-runtime-result msg-type state [])
      (let [ts (:ts msg)
            state1 (-> state
                       (connection/with-now ts)
                       (update-in [:metrics :ingress-parse-errors] (fnil inc 0)))]
        (emit-runtime-result msg-type state1
                             [(model/make-runtime-effect :fx/dead-letter
                                                         {:reason :parse-error
                                                          :error (:error msg)
                                                          :raw (:raw msg)})])))))

(defmethod handle-runtime-msg :default
  [_ state msg]
  (let [msg-type (:msg/type msg)
        ts (:ts msg)]
    (emit-runtime-result msg-type
                         (connection/with-now state ts)
                         [(model/make-runtime-effect :fx/dead-letter
                                                     {:reason :unknown-message
                                                      :message msg})])))

(defn step
  "Pure runtime transition function.
   Returns {:state next-state :effects [RuntimeEffect ...]}."
  [{:keys [calculate-retry-delay-ms]} state msg]
  (handle-runtime-msg {:calculate-retry-delay-ms calculate-retry-delay-ms}
                      state
                      msg))
