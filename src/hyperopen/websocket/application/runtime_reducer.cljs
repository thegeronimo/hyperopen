(ns hyperopen.websocket.application.runtime-reducer
  (:require [hyperopen.websocket.domain.model :as model]
            [hyperopen.websocket.health :as health]))

(defn initial-runtime-state [config]
  {:config config
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
             :ingress-parse-errors 0}})

(defn- connection-projection [state]
  {:status (:status state)
   :attempt (:attempt state)
   :next-retry-at-ms (:next-retry-at-ms state)
   :last-close (:last-close state)
   :last-activity-at-ms (:last-activity-at-ms state)
   :now-ms (:now-ms state)
   :online? (:online? state)
   :transport/state (:status state)
   :transport/last-recv-at-ms (get-in state [:transport :last-recv-at-ms])
   :transport/connected-at-ms (get-in state [:transport :connected-at-ms])
   :transport/expected-traffic? (boolean (get-in state [:transport :expected-traffic?]))
   :transport/freshness (get-in state [:transport :freshness])
   :queue-size (count (:queue state))
   :ws nil})

(defn- stream-projection [state]
  {:metrics (:metrics state)
   :tier-depth (:tier-depth state)
   :market-coalesce (:market-coalesce state)
   :now-ms (:now-ms state)
   :streams (:streams state)
   :transport {:state (:status state)
               :online? (:online? state)
               :last-recv-at-ms (get-in state [:transport :last-recv-at-ms])
               :connected-at-ms (get-in state [:transport :connected-at-ms])
               :expected-traffic? (boolean (get-in state [:transport :expected-traffic?]))
               :freshness (get-in state [:transport :freshness])
               :attempt (:attempt state)
               :last-close (:last-close state)}})

(defn- refresh-health-hysteresis [state]
  (let [now-ms (:now-ms state)]
    (if (number? now-ms)
      (let [transport-candidate (health/derive-transport-freshness
                                  {:state (:status state)
                                   :online? (:online? state)
                                   :expected-traffic? (boolean (get-in state [:transport :expected-traffic?]))
                                   :last-recv-at-ms (get-in state [:transport :last-recv-at-ms])
                                   :connected-at-ms (get-in state [:transport :connected-at-ms])
                                   :transport-live-threshold-ms (get-in state [:config :transport-live-threshold-ms])
                                   :now-ms now-ms})
            transport* (health/advance-transport-freshness
                         (:config state)
                         (:transport state)
                         transport-candidate)
            streams* (into {}
                           (map (fn [[sub-key stream]]
                                  (let [candidate-status (health/derive-stream-status now-ms stream)]
                                    [sub-key
                                     (health/advance-stream-status
                                       (:config state)
                                       stream
                                       candidate-status)])))
                           (:streams state))]
        (assoc state
               :transport transport*
               :streams streams*))
      state)))

(defn- append-projections [state effects]
  (-> effects
      (conj (model/make-runtime-effect :fx/project-connection-state
                                       {:connection (connection-projection state)
                                        :active-socket-id (:active-socket-id state)}))
      (conj (model/make-runtime-effect :fx/project-stream-metrics
                                       (stream-projection state)))))

(defn- result [state effects]
  (let [state* (refresh-health-hysteresis state)]
    {:state state*
     :effects (append-projections state* (vec effects))}))

(defn- can-connect? [state]
  (and (:ws-url state)
       (:online? state)
       (not (:intentional-close? state))
       (nil? (:active-socket-id state))))

(defn- ensure-connect [state effects]
  (if (can-connect? state)
    (let [socket-id (inc (:socket-id state))
          status (if (pos? (:attempt state)) :reconnecting :connecting)]
      [(assoc state
              :socket-id socket-id
              :active-socket-id socket-id
              :status status
              :next-retry-at-ms nil)
       (conj effects
             (model/make-runtime-effect :fx/socket-connect
                                        {:ws-url (:ws-url state)
                                         :socket-id socket-id}))])
    [state effects]))

(defn- with-now [state now-ms]
  (if (number? now-ms)
    (assoc state :now-ms now-ms)
    state))

(defn- with-overflow-bound [queue max-size data]
  (let [next-queue (conj (vec queue) data)]
    (if (> (count next-queue) max-size)
      [(vec (rest next-queue)) true]
      [next-queue false])))

(defn- schedule-retry
  [{:keys [calculate-retry-delay-ms]} state effects ts]
  (cond
    (:intentional-close? state)
    [(assoc state :status :disconnected :next-retry-at-ms nil :retry-timer-active? false)
     effects]

    (not (:ws-url state))
    [(assoc state :status :disconnected :next-retry-at-ms nil :retry-timer-active? false)
     effects]

    (not (:online? state))
    [(assoc state :status :disconnected :next-retry-at-ms nil :retry-timer-active? false)
     effects]

    :else
    (let [attempt (max 1 (:attempt state))
          delay-ms (calculate-retry-delay-ms attempt (:hidden? state) (:config state) 0.5)
          retry-at (+ ts delay-ms)
          effects* (cond-> effects
                     (:retry-timer-active? state)
                     (conj (model/make-runtime-effect :fx/timer-clear-timeout {:timer-key :retry}))

                     true
                     (conj (model/make-runtime-effect :fx/timer-set-timeout
                                                     {:timer-key :retry
                                                      :ms delay-ms
                                                      :msg {:msg/type :evt/timer-retry-fired}})))]
      [(assoc state
              :status :reconnecting
              :next-retry-at-ms retry-at
              :retry-timer-active? true)
       effects*])))

(defn- maybe-start-health-tick [state effects]
  (if (:health-tick-active? state)
    [state effects]
    [(assoc state :health-tick-active? true)
     (conj effects
           (model/make-runtime-effect :fx/timer-set-interval
                                      {:timer-key :health-tick
                                       :ms (or (get-in state [:config :health-tick-interval-ms]) 1000)
                                       :msg {:msg/type :evt/timer-health-tick}}))]))

(defn- maybe-clear-watchdog [state effects]
  (if (:watchdog-active? state)
    [(assoc state :watchdog-active? false)
     (conj effects (model/make-runtime-effect :fx/timer-clear-interval {:timer-key :watchdog}))]
    [state effects]))

(defn- maybe-clear-retry [state effects]
  (if (:retry-timer-active? state)
    [(assoc state :retry-timer-active? false :next-retry-at-ms nil)
     (conj effects (model/make-runtime-effect :fx/timer-clear-timeout {:timer-key :retry}))]
    [state effects]))

(defn- maybe-clear-market-flush [state effects]
  (if (:market-flush-active? state)
    [(assoc state :market-flush-active? false)
     (conj effects (model/make-runtime-effect :fx/timer-clear-timeout {:timer-key :market-flush}))]
    [state effects]))

(defn- maybe-clear-health-tick [state effects]
  (if (:health-tick-active? state)
    [(assoc state :health-tick-active? false)
     (conj effects (model/make-runtime-effect :fx/timer-clear-interval {:timer-key :health-tick}))]
    [state effects]))

(defn- flush-market-pending [state effects]
  (let [pending (vals (get-in state [:market-coalesce :pending] {}))
        sorted (sort-by :ts pending)
        effects* (into effects
                       (map (fn [envelope]
                              (model/make-runtime-effect :fx/router-dispatch-envelope {:envelope envelope})))
                       sorted)
        dispatched (count sorted)]
    [(-> state
         (assoc-in [:market-coalesce :pending] {})
         (assoc :market-flush-active? false)
         (update-in [:metrics :market-dispatched] (fnil + 0) dispatched))
     effects*]))

(defn- refresh-expected-traffic [state]
  (assoc-in state
            [:transport :expected-traffic?]
            (health/transport-expected-traffic? (:streams state))))

(defn- baseline-stream-entry [state subscription]
  (let [topic (:type subscription)]
    {:descriptor subscription
     :topic topic
     :group (model/topic->group topic)
     :stale-threshold-ms (health/stream-stale-threshold-ms (:config state) topic)
     :status :idle
     :status-pending-status nil
     :status-pending-count 0
     :last-seq nil
     :seq-gap-detected? false
     :seq-gap-count 0
     :last-gap nil}))

(defn- mark-subscribe [state subscription subscribed-at-ms]
  (let [sub-key (model/subscription-key subscription)
        existing (get-in state [:streams sub-key] {})
        next-stream (merge existing
                           (baseline-stream-entry state subscription)
                           {:subscribed? true
                            :subscribed-at-ms subscribed-at-ms
                            :first-payload-at-ms nil
                            :last-payload-at-ms nil
                            :message-count 0
                            :status :idle
                            :status-pending-status nil
                            :status-pending-count 0
                            :last-seq nil
                            :seq-gap-detected? false
                            :seq-gap-count 0
                            :last-gap nil})]
    (assoc-in state [:streams sub-key] next-stream)))

(defn- mark-unsubscribe [state subscription]
  (let [sub-key (model/subscription-key subscription)
        existing (get-in state [:streams sub-key] {})
        next-stream (merge existing
                           (baseline-stream-entry state subscription)
                           {:subscribed? false
                            :subscribed-at-ms nil
                            :first-payload-at-ms nil
                            :status :idle
                            :status-pending-status nil
                            :status-pending-count 0
                            :last-seq nil
                            :seq-gap-detected? false
                            :seq-gap-count 0
                            :last-gap nil})]
    (assoc-in state [:streams sub-key] next-stream)))

(defn- apply-stream-intent [state data at-ms]
  (let [method (model/normalize-method (:method data))
        subscription (:subscription data)]
    (if (map? subscription)
      (case method
        "subscribe" (mark-subscribe state subscription at-ms)
        "unsubscribe" (mark-unsubscribe state subscription)
        state)
      state)))

(defn- replay-subscriptions-as-active [state at-ms]
  (reduce (fn [acc subscription]
            (mark-subscribe acc subscription at-ms))
          state
          (vals (:desired-subscriptions state))))

(defn- update-stream-payload [stream recv-at-ms]
  (if (and (map? stream) (:subscribed? stream))
    (-> stream
        (assoc :first-payload-at-ms (or (:first-payload-at-ms stream) recv-at-ms)
               :last-payload-at-ms recv-at-ms)
        (update :message-count (fnil inc 0)))
    stream))

(defn- update-stream-seq
  [stream seq-value recv-at-ms]
  (if (and (map? stream)
           (:subscribed? stream)
           (number? seq-value))
    (let [last-seq (:last-seq stream)
          gap? (and (number? last-seq)
                    (> seq-value (inc last-seq)))
          next-last-seq (if (number? last-seq)
                          (max last-seq seq-value)
                          seq-value)]
      (cond-> (assoc stream :last-seq next-last-seq)
        gap?
        (-> (assoc :seq-gap-detected? true
                   :last-gap {:expected (inc last-seq)
                              :actual seq-value
                              :at-ms recv-at-ms})
            (update :seq-gap-count (fnil inc 0)))))
    stream))

(defn- record-stream-payload [state envelope recv-at-ms]
  (let [stream-keys (health/match-stream-keys (:streams state) envelope)
        seq-value (get-in envelope [:payload :seq])]
    (reduce (fn [acc sub-key]
              (if (contains? (:streams acc) sub-key)
                (update-in acc
                           [:streams sub-key]
                           (fn [stream]
                             (-> stream
                                 (update-stream-payload recv-at-ms)
                                 (update-stream-seq seq-value recv-at-ms))))
                acc))
            state
            stream-keys)))

(defn step
  "Pure runtime transition function.
   Returns {:state next-state :effects [RuntimeEffect ...]}."
  [{:keys [calculate-retry-delay-ms]} state msg]
  (let [ctx {:calculate-retry-delay-ms calculate-retry-delay-ms}
        msg-type (:msg/type msg)
        ts (:ts msg)
        config (:config state)]
    (case msg-type
      :cmd/init-connection
      (let [state* (-> state
                       (assoc :ws-url (:ws-url msg)
                              :intentional-close? false)
                       (with-now ts))
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
            [state3 effects3] (maybe-start-health-tick state2 effects2)
            [state4 effects4] (ensure-connect state3 effects3)]
        (result state4 effects4))

      :cmd/disconnect
      (let [socket-id (:active-socket-id state)
            [state1 effects1] (maybe-clear-retry state [])
            [state2 effects2] (maybe-clear-watchdog state1 effects1)
            [state3 effects3] (maybe-clear-market-flush state2 effects2)
            [state4 effects4] (maybe-clear-health-tick state3 effects3)
            effects5 (cond-> effects4
                       socket-id (conj (model/make-runtime-effect :fx/socket-detach-handlers {:socket-id socket-id})
                                       (model/make-runtime-effect :fx/socket-close {:socket-id socket-id
                                                                                    :code 1000
                                                                                    :reason "Intentional disconnect"})))
            state5 (-> state4
                       (with-now ts)
                       (assoc :intentional-close? true
                              :status :disconnected
                              :active-socket-id nil
                              :next-retry-at-ms nil)
                       (assoc-in [:transport :connected-at-ms] nil))]
        (result state5 effects5))

      :cmd/force-reconnect
      (let [socket-id (:active-socket-id state)
            [state1 effects1] (maybe-clear-retry (-> state
                                                    (assoc :intentional-close? false)
                                                    (with-now ts))
                                                 [])
            effects2 (cond-> effects1
                       socket-id (conj (model/make-runtime-effect :fx/socket-detach-handlers {:socket-id socket-id})
                                       (model/make-runtime-effect :fx/socket-close {:socket-id socket-id
                                                                                    :code 4000
                                                                                    :reason "Force reconnect"})))
            state2 (assoc state1 :active-socket-id nil)
            [state3 effects3] (maybe-start-health-tick state2 effects2)
            [state4 effects4] (ensure-connect state3 effects3)]
        (result state4 effects4))

      :cmd/send-message
      (let [data (:data msg)
            at-ms (or (:at-ms msg) ts)
            desired* (model/apply-subscription-intent (:desired-subscriptions state) data)
            state* (-> state
                       (with-now at-ms)
                       (assoc :desired-subscriptions desired*)
                       (apply-stream-intent data at-ms)
                       (refresh-expected-traffic))]
        (if (and (= :connected (:status state*))
                 (:active-socket-id state*))
          (result state*
                  [(model/make-runtime-effect :fx/socket-send
                                              {:socket-id (:active-socket-id state*)
                                               :data data})])
          (let [[queue* dropped?] (with-overflow-bound (:queue state*)
                                                       (:max-queue-size config)
                                                       data)
                state1 (assoc state* :queue queue*)
                effects1 (cond-> []
                           dropped? (conj (model/make-runtime-effect :fx/log
                                                                     {:level :warn
                                                                      :message "WebSocket queue overflow, dropping oldest queued message"})))
                [state2 effects2] (ensure-connect state1 effects1)]
            (result state2 effects2))))

      :cmd/register-handler
      (result (with-now state ts)
              [(model/make-runtime-effect :fx/router-register-handler
                                          {:topic (:topic msg)
                                           :handler-fn (:handler-fn msg)})])

      :evt/socket-open
      (if (= (:socket-id msg) (:active-socket-id state))
        (let [open-at-ms (or (:at-ms msg) ts)
              [state1 effects1] (maybe-clear-retry state [])
              state2 (-> state1
                         (with-now open-at-ms)
                         (replay-subscriptions-as-active open-at-ms)
                         (refresh-expected-traffic))
              replay-msgs (->> (vals (:desired-subscriptions state2))
                               (sort-by pr-str)
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
          (result state3 effects2))
        (result state []))

      :evt/socket-close
      (if (= (:socket-id msg) (:active-socket-id state))
        (let [close-at-ms (or (:at-ms msg) ts)
              close-info {:code (or (:code msg) 0)
                          :reason (or (:reason msg) "")
                          :was-clean? (boolean (:was-clean? msg))
                          :at-ms close-at-ms}
              state1 (-> state
                         (with-now close-at-ms)
                         (assoc :active-socket-id nil
                                :last-close close-info)
                         (assoc-in [:transport :connected-at-ms] nil))]
          (if (:intentional-close? state1)
            (let [[state2 effects2] (maybe-clear-retry (assoc state1 :status :disconnected) [])]
              (result state2 effects2))
            (let [state2 (-> state1
                             (update :attempt (fnil inc 0))
                             (assoc :status :reconnecting))
                  [state3 effects3] (schedule-retry ctx state2 [] close-at-ms)]
              (result state3 effects3))))
        (result state []))

      :evt/socket-error
      (result (with-now state ts)
              [(model/make-runtime-effect :fx/log
                                          {:level :warn
                                           :message "WebSocket error"
                                           :error (:error msg)})])

      :evt/lifecycle-online
      (let [state1 (-> state
                       (assoc :online? true)
                       (with-now ts))
            [state2 effects2] (ensure-connect state1 [])]
        (result state2 effects2))

      :evt/lifecycle-offline
      (let [socket-id (:active-socket-id state)
            [state1 effects1] (maybe-clear-retry (-> state
                                                    (assoc :online? false
                                                           :status :disconnected
                                                           :active-socket-id nil)
                                                    (with-now ts)
                                                    (assoc-in [:transport :connected-at-ms] nil))
                                                 [])
            effects2 (cond-> effects1
                       socket-id (conj (model/make-runtime-effect :fx/socket-close
                                                                  {:socket-id socket-id
                                                                   :code 4001
                                                                   :reason "Offline"})))]
        (result state1 effects2))

      :evt/lifecycle-focus
      (let [[state1 effects1] (ensure-connect (with-now state ts) [])]
        (result state1 effects1))

      :evt/lifecycle-visible
      (let [[state1 effects1] (ensure-connect (-> state
                                                 (assoc :hidden? false)
                                                 (with-now ts))
                                               [])]
        (result state1 effects1))

      :evt/timer-retry-fired
      (let [state1 (-> state
                       (assoc :retry-timer-active? false
                              :next-retry-at-ms nil)
                       (with-now ts))
            [state2 effects2] (ensure-connect state1 [])]
        (result state2 effects2))

      :evt/timer-health-tick
      (result (with-now state (or (:now-ms msg) ts)) [])

      :evt/timer-watchdog-fired
      (let [tick-at-ms (or (:now-ms msg) ts)
            threshold-ms (if (:hidden? state)
                           (:stale-hidden-ms config)
                           (:stale-visible-ms config))
            stale? (and (= :connected (:status state))
                        (:active-socket-id state)
                        (number? (:last-activity-at-ms state))
                        (> (- tick-at-ms (:last-activity-at-ms state)) threshold-ms))
            state1 (with-now state tick-at-ms)]
        (if stale?
          (result state1
                  [(model/make-runtime-effect :fx/log
                                              {:level :warn
                                               :message "WebSocket watchdog detected stale connection, forcing reconnect"})
                   (model/make-runtime-effect :fx/socket-close
                                              {:socket-id (:active-socket-id state)
                                               :code 4002
                                               :reason "Stale websocket connection"})])
          (result state1 [])))

      :evt/socket-message
      (if (= (:socket-id msg) (:active-socket-id state))
        (let [recv-at-ms (or (:recv-at-ms msg) ts)
              state1 (-> state
                         (with-now recv-at-ms)
                         (assoc :last-activity-at-ms recv-at-ms)
                         (assoc-in [:transport :last-recv-at-ms] recv-at-ms))]
          (result state1
                [(model/make-runtime-effect :fx/parse-raw-message
                                            {:raw (:raw msg)
                                             :socket-id (:socket-id msg)
                                             :recv-at-ms recv-at-ms})]))
        (result state []))

      :evt/decoded-envelope
      (let [envelope (:envelope msg)
            recv-at-ms (or (:recv-at-ms msg) ts)
            state0 (-> state
                       (with-now recv-at-ms)
                       (record-stream-payload envelope recv-at-ms)
                       (refresh-expected-traffic))]
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
            (result state2 effects2))
          (let [state1 (update-in state0 [:metrics :lossless-dispatched] (fnil inc 0))]
            (result state1
                    [(model/make-runtime-effect :fx/router-dispatch-envelope
                                                {:envelope envelope})]))))

      :evt/timer-market-flush-fired
      (let [[state1 effects1] (flush-market-pending (with-now state ts) [])]
        (result state1 effects1))

      :evt/parse-error
      (let [state1 (-> state
                       (with-now ts)
                       (update-in [:metrics :ingress-parse-errors] (fnil inc 0)))]
        (result state1
                [(model/make-runtime-effect :fx/dead-letter
                                            {:reason :parse-error
                                             :error (:error msg)
                                             :raw (:raw msg)})]))

      (result (with-now state ts)
              [(model/make-runtime-effect :fx/dead-letter
                                          {:reason :unknown-message
                                           :message msg})]))))
