(ns hyperopen.websocket.application.runtime-reducer
  (:require [hyperopen.websocket.domain.model :as model]))

(defn initial-runtime-state [config]
  {:config config
   :ws-url nil
   :status :disconnected
   :attempt 0
   :next-retry-at-ms nil
   :last-close nil
   :last-activity-at-ms nil
   :queue []
   :desired-subscriptions {}
   :intentional-close? false
   :socket-id 0
   :active-socket-id nil
   :online? true
   :hidden? false
   :lifecycle-installed? false
   :retry-timer-active? false
   :watchdog-active? false
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
   :queue-size (count (:queue state))
   :ws nil})

(defn- stream-projection [state]
  {:metrics (:metrics state)
   :tier-depth (:tier-depth state)
   :market-coalesce (:market-coalesce state)})

(defn- append-projections [state effects]
  (-> effects
      (conj (model/make-runtime-effect :fx/project-connection-state
                                       {:connection (connection-projection state)
                                        :active-socket-id (:active-socket-id state)}))
      (conj (model/make-runtime-effect :fx/project-stream-metrics
                                       (stream-projection state)))))

(defn- result [state effects]
  {:state state
   :effects (append-projections state (vec effects))})

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
      (let [state* (assoc state :ws-url (:ws-url msg) :intentional-close? false)
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
                                                                   :ms (:watchdog-interval-ms config)
                                                                   :msg {:msg/type :evt/timer-watchdog-fired}}))])
            [state3 effects3] (ensure-connect state2 effects2)]
        (result state3 effects3))

      :cmd/disconnect
      (let [socket-id (:active-socket-id state)
            [state1 effects1] (maybe-clear-retry state [])
            [state2 effects2] (maybe-clear-watchdog state1 effects1)
            [state3 effects3] (maybe-clear-market-flush state2 effects2)
            effects4 (cond-> effects3
                       socket-id (conj (model/make-runtime-effect :fx/socket-detach-handlers {:socket-id socket-id})
                                       (model/make-runtime-effect :fx/socket-close {:socket-id socket-id
                                                                                    :code 1000
                                                                                    :reason "Intentional disconnect"})))
            state4 (assoc state3
                          :intentional-close? true
                          :status :disconnected
                          :active-socket-id nil
                          :next-retry-at-ms nil)]
        (result state4 effects4))

      :cmd/force-reconnect
      (let [socket-id (:active-socket-id state)
            [state1 effects1] (maybe-clear-retry (assoc state :intentional-close? false) [])
            effects2 (cond-> effects1
                       socket-id (conj (model/make-runtime-effect :fx/socket-detach-handlers {:socket-id socket-id})
                                       (model/make-runtime-effect :fx/socket-close {:socket-id socket-id
                                                                                    :code 4000
                                                                                    :reason "Force reconnect"})))
            state2 (assoc state1 :active-socket-id nil)
            [state3 effects3] (ensure-connect state2 effects2)]
        (result state3 effects3))

      :cmd/send-message
      (let [data (:data msg)
            desired* (model/apply-subscription-intent (:desired-subscriptions state) data)
            state* (assoc state :desired-subscriptions desired*)]
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
      (result state
              [(model/make-runtime-effect :fx/router-register-handler
                                          {:topic (:topic msg)
                                           :handler-fn (:handler-fn msg)})])

      :evt/socket-open
      (if (= (:socket-id msg) (:active-socket-id state))
        (let [[state1 effects1] (maybe-clear-retry state [])
              replay-msgs (->> (vals (:desired-subscriptions state1))
                               (sort-by pr-str)
                               (mapv (fn [subscription]
                                       {:method "subscribe"
                                        :subscription subscription})))
              queue-msgs (:queue state1)
              effects2 (into effects1
                            (map (fn [payload]
                                   (model/make-runtime-effect :fx/socket-send
                                                              {:socket-id (:socket-id msg)
                                                               :data payload}))
                                 (concat replay-msgs queue-msgs)))
              state2 (assoc state1
                            :status :connected
                            :attempt 0
                            :queue []
                            :last-activity-at-ms ts)]
          (result state2 effects2))
        (result state []))

      :evt/socket-close
      (if (= (:socket-id msg) (:active-socket-id state))
        (let [close-info {:code (or (:code msg) 0)
                          :reason (or (:reason msg) "")
                          :was-clean? (boolean (:was-clean? msg))
                          :at-ms ts}
              state1 (assoc state
                            :active-socket-id nil
                            :last-close close-info)]
          (if (:intentional-close? state1)
            (let [[state2 effects2] (maybe-clear-retry (assoc state1 :status :disconnected) [])]
              (result state2 effects2))
            (let [state2 (-> state1
                             (update :attempt (fnil inc 0))
                             (assoc :status :reconnecting))
                  [state3 effects3] (schedule-retry ctx state2 [] ts)]
              (result state3 effects3))))
        (result state []))

      :evt/socket-error
      (result state
              [(model/make-runtime-effect :fx/log
                                          {:level :warn
                                           :message "WebSocket error"
                                           :error (:error msg)})])

      :evt/lifecycle-online
      (let [state1 (assoc state :online? true)
            [state2 effects2] (ensure-connect state1 [])]
        (result state2 effects2))

      :evt/lifecycle-offline
      (let [socket-id (:active-socket-id state)
            [state1 effects1] (maybe-clear-retry (assoc state :online? false
                                                        :status :disconnected
                                                        :active-socket-id nil)
                                                 [])
            effects2 (cond-> effects1
                       socket-id (conj (model/make-runtime-effect :fx/socket-close
                                                                  {:socket-id socket-id
                                                                   :code 4001
                                                                   :reason "Offline"})))]
        (result state1 effects2))

      :evt/lifecycle-focus
      (let [[state1 effects1] (ensure-connect state [])]
        (result state1 effects1))

      :evt/lifecycle-visible
      (let [[state1 effects1] (ensure-connect (assoc state :hidden? false) [])]
        (result state1 effects1))

      :evt/timer-retry-fired
      (let [state1 (assoc state :retry-timer-active? false :next-retry-at-ms nil)
            [state2 effects2] (ensure-connect state1 [])]
        (result state2 effects2))

      :evt/timer-watchdog-fired
      (let [threshold-ms (if (:hidden? state)
                           (:stale-hidden-ms config)
                           (:stale-visible-ms config))
            stale? (and (= :connected (:status state))
                        (:active-socket-id state)
                        (number? (:last-activity-at-ms state))
                        (> (- ts (:last-activity-at-ms state)) threshold-ms))]
        (if stale?
          (result state
                  [(model/make-runtime-effect :fx/log
                                              {:level :warn
                                               :message "WebSocket watchdog detected stale connection, forcing reconnect"})
                   (model/make-runtime-effect :fx/socket-close
                                              {:socket-id (:active-socket-id state)
                                               :code 4002
                                               :reason "Stale websocket connection"})])
          (result state [])))

      :evt/socket-message
      (if (= (:socket-id msg) (:active-socket-id state))
        (result (assoc state :last-activity-at-ms ts)
                [(model/make-runtime-effect :fx/parse-raw-message
                                            {:raw (:raw msg)
                                             :socket-id (:socket-id msg)})])
        (result state []))

      :evt/decoded-envelope
      (let [envelope (:envelope msg)]
        (if (= :market (:tier envelope))
          (let [key (model/market-coalesce-key envelope)
                replacing? (contains? (get-in state [:market-coalesce :pending] {}) key)
                state1 (-> state
                           (assoc-in [:market-coalesce :pending key] envelope)
                           (cond-> replacing? (update-in [:metrics :market-coalesced] (fnil inc 0))))
                [state2 effects2] (if (:market-flush-active? state1)
                                    [state1 []]
                                    [(assoc state1 :market-flush-active? true)
                                     [(model/make-runtime-effect :fx/timer-set-timeout
                                                                 {:timer-key :market-flush
                                                                  :ms (:market-coalesce-window-ms config)
                                                                  :msg {:msg/type :evt/timer-market-flush-fired}})]])]
            (result state2 effects2))
          (let [state1 (update-in state [:metrics :lossless-dispatched] (fnil inc 0))]
            (result state1
                    [(model/make-runtime-effect :fx/router-dispatch-envelope
                                                {:envelope envelope})]))))

      :evt/timer-market-flush-fired
      (let [[state1 effects1] (flush-market-pending state [])]
        (result state1 effects1))

      :evt/parse-error
      (let [state1 (update-in state [:metrics :ingress-parse-errors] (fnil inc 0))]
        (result state1
                [(model/make-runtime-effect :fx/dead-letter
                                            {:reason :parse-error
                                             :error (:error msg)
                                             :raw (:raw msg)})]))

      (result state
              [(model/make-runtime-effect :fx/dead-letter
                                          {:reason :unknown-message
                                           :message msg})]))))
