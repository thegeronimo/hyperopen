(ns hyperopen.websocket.application.runtime.connection
  (:require [hyperopen.websocket.domain.model :as model]))

(defn with-now
  [state now-ms]
  (if (number? now-ms)
    (assoc state :now-ms now-ms)
    state))

(defn with-overflow-bound
  [queue max-size data]
  (let [next-queue (conj (vec queue) data)]
    (if (> (count next-queue) max-size)
      [(vec (rest next-queue)) true]
      [next-queue false])))

(defn msg-from-active-socket?
  [state msg]
  (let [socket-id (:socket-id msg)]
    (or (nil? socket-id)
        (= socket-id (:active-socket-id state)))))

(defn add-socket-teardown-effects
  [effects socket-id code reason]
  (cond-> effects
    socket-id (conj (model/make-runtime-effect :fx/socket-detach-handlers {:socket-id socket-id})
                    (model/make-runtime-effect :fx/socket-close {:socket-id socket-id
                                                                 :code code
                                                                 :reason reason}))))

(defn can-connect?
  [state]
  (and (:ws-url state)
       (:online? state)
       (not (:intentional-close? state))
       (nil? (:active-socket-id state))))

(defn ensure-connect
  [state effects]
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

(defn schedule-retry
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
    (let [attempt (+ (max 1 (:attempt state))
                     (or (:short-lived-streak state) 0))
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

(defn maybe-start-health-tick
  [state effects]
  (if (:health-tick-active? state)
    [state effects]
    [(assoc state :health-tick-active? true)
     (conj effects
           (model/make-runtime-effect :fx/timer-set-interval
                                      {:timer-key :health-tick
                                       :ms (or (get-in state [:config :health-tick-interval-ms]) 1000)
                                       :msg {:msg/type :evt/timer-health-tick}}))]))

(defn maybe-clear-watchdog
  [state effects]
  (if (:watchdog-active? state)
    [(assoc state :watchdog-active? false)
     (conj effects (model/make-runtime-effect :fx/timer-clear-interval {:timer-key :watchdog}))]
    [state effects]))

(defn maybe-clear-retry
  [state effects]
  (if (:retry-timer-active? state)
    [(assoc state :retry-timer-active? false :next-retry-at-ms nil)
     (conj effects (model/make-runtime-effect :fx/timer-clear-timeout {:timer-key :retry}))]
    [state effects]))

(defn maybe-clear-health-tick
  [state effects]
  (if (:health-tick-active? state)
    [(assoc state :health-tick-active? false)
     (conj effects (model/make-runtime-effect :fx/timer-clear-interval {:timer-key :health-tick}))]
    [state effects]))
