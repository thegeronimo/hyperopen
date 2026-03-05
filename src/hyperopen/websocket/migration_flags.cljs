(ns hyperopen.websocket.migration-flags
  (:require [hyperopen.config :as app-config]))

(def ^:private fallback-defaults
  {:order-fill-ws-first? true
   :startup-bootstrap-ws-first? true
   :candle-subscriptions? false
   :auto-fallback-on-health-degrade? true})

(def ^:private degraded-group-statuses
  #{:degraded
    :delayed
    :down
    :stale
    :unknown})

(defn- bool-or-default
  [value default]
  (if (boolean? value)
    value
    default))

(defn- normalize-flags
  [flags defaults]
  (let [flags* (if (map? flags) flags {})]
    {:order-fill-ws-first? (bool-or-default (:order-fill-ws-first? flags*)
                                            (:order-fill-ws-first? defaults))
     :startup-bootstrap-ws-first? (bool-or-default (:startup-bootstrap-ws-first? flags*)
                                                   (:startup-bootstrap-ws-first? defaults))
     :candle-subscriptions? (bool-or-default (:candle-subscriptions? flags*)
                                             (:candle-subscriptions? defaults))
     :auto-fallback-on-health-degrade? (bool-or-default (:auto-fallback-on-health-degrade? flags*)
                                                        (:auto-fallback-on-health-degrade? defaults))}))

(defn configured-default-flags
  []
  (normalize-flags (get-in app-config/config [:ws-migration])
                   fallback-defaults))

(defn effective-flags
  [state]
  (let [defaults (configured-default-flags)]
    (normalize-flags (get-in state [:websocket :migration-flags])
                     defaults)))

(defn- transport-degraded?
  [health]
  (let [transport (:transport health)]
    (when (map? transport)
      (or (and (contains? transport :state)
               (not= :connected (:state transport)))
          (and (contains? transport :freshness)
               (not= :live (:freshness transport)))))))

(defn- group-degraded?
  [health group]
  (let [group-health (get-in health [:groups group])]
    (when (map? group-health)
      (or (true? (:gap-detected? group-health))
          (contains? degraded-group-statuses
                     (:worst-status group-health))))))

(defn- flow-degraded?
  [health flow]
  (when (map? health)
    (or (true? (transport-degraded? health))
        (case flow
          :order-fill
          (or (true? (group-degraded? health :orders_oms))
              (true? (group-degraded? health :account)))

          :startup-bootstrap
          (or (true? (group-degraded? health :orders_oms))
              (true? (group-degraded? health :account)))

          :candle-subscriptions
          (true? (group-degraded? health :market_data))

          false))))

(defn- flow-enabled?
  [state flag-key flow]
  (let [flags (effective-flags state)
        enabled? (true? (get flags flag-key))
        guardrails? (true? (:auto-fallback-on-health-degrade? flags))
        degraded? (and guardrails?
                       (flow-degraded? (get-in state [:websocket :health])
                                       flow))]
    (and enabled?
         (not degraded?))))

(defn order-fill-ws-first-enabled?
  [state]
  (flow-enabled? state :order-fill-ws-first? :order-fill))

(defn startup-bootstrap-ws-first-enabled?
  [state]
  (flow-enabled? state :startup-bootstrap-ws-first? :startup-bootstrap))

(defn candle-subscriptions-enabled?
  [state]
  (flow-enabled? state :candle-subscriptions? :candle-subscriptions))

(defn- candle-rows-present?
  [state coin interval]
  (let [entry (get-in state [:candles coin interval])]
    (cond
      (sequential? entry)
      (seq entry)

      (map? entry)
      (let [rows (or (:rows entry)
                     (:data entry)
                     (:candles entry))]
        (and (sequential? rows)
             (seq rows)))

      :else
      false)))

(defn should-fetch-candle-snapshot?
  [state coin interval]
  (if-not (candle-subscriptions-enabled? state)
    true
    (or (not (string? coin))
        (empty? coin)
        (not (candle-rows-present? state coin interval)))))
