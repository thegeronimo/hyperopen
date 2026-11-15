(ns hyperopen.websocket.diagnostics.catalog
  (:require [clojure.string :as str]))

(def group-order
  [:orders_oms :market_data :account])

(def group-catalog
  {:orders_oms {:id :orders_oms
                :title "Orders/OMS"
                :source-label "orders/oms"
                :weight 1.0
                :rank 0}
   :market_data {:id :market_data
                 :title "Market Data"
                 :source-label "market data"
                 :weight 0.85
                 :rank 1}
   :account {:id :account
             :title "Account"
             :source-label "account"
             :weight 0.65
             :rank 2}
   :transport {:id :transport
               :title "Transport"
               :source-label "transport"
               :weight 0.0
               :rank 3}
   :unknown {:id :unknown
             :title "Other"
             :source-label "unknown"
             :weight 0.5
             :rank 99}})

(def status-catalog
  {:idle {:id :idle
          :label "IDLE"
          :meter-label "Idle"
          :tone :neutral
          :neutral? true}
   :event-driven {:id :event-driven
                  :label "EVENT-DRIVEN"
                  :meter-label "Online"
                  :tone :neutral
                  :neutral? true}
   :live {:id :live
          :label "LIVE"
          :meter-label "Online"
          :tone :success
          :neutral? false}
   :delayed {:id :delayed
             :label "DELAYED"
             :meter-label "Delayed"
             :tone :warning
             :neutral? false}
   :reconnecting {:id :reconnecting
                  :label "RECONNECTING"
                  :meter-label "Reconnecting"
                  :tone :warning
                  :neutral? false}
   :offline {:id :offline
             :label "OFFLINE"
             :meter-label "Offline"
             :tone :error
             :neutral? false}
   :unknown {:id :unknown
             :label "UNKNOWN"
             :meter-label "Unknown"
             :tone :error
             :neutral? false}})

(def timeline-event-catalog
  {:connected "connected"
   :reconnecting "reconnecting"
   :offline "offline"
   :reset-market "reset-market"
   :reset-oms "reset-oms"
   :reset-all "reset-all"
   :auto-recover-market "auto-recover-market"
   :gap-detected "gap-detected"})

(def ^:private legacy-status-map
  {:n-a :event-driven})

(defn known-status?
  [status]
  (contains? status-catalog status))

(defn known-group?
  [group]
  (contains? group-catalog group))

(defn normalize-status
  [status]
  (cond
    (contains? legacy-status-map status)
    (get legacy-status-map status)

    (known-status? status)
    status

    :else
    :unknown))

(defn normalize-group
  [group]
  (if (known-group? group)
    group
    :unknown))

(defn status-meta
  [status]
  (get status-catalog (normalize-status status) (:unknown status-catalog)))

(defn group-meta
  [group]
  (get group-catalog (normalize-group group) (:unknown group-catalog)))

(defn status-label
  [status]
  (:label (status-meta status)))

(defn meter-status-label
  [status]
  (:meter-label (status-meta status)))

(defn neutral-status?
  [status]
  (true? (:neutral? (status-meta status))))

(defn non-neutral-status?
  [status]
  (not (neutral-status? status)))

(defn group-rank
  [group]
  (:rank (group-meta group)))

(defn group-weight
  [group]
  (:weight (group-meta group)))

(defn group-title
  [group]
  (:title (group-meta group)))

(defn source-label
  [source]
  (:source-label (group-meta source)))

(defn timeline-event-label
  [event]
  (get timeline-event-catalog event "unknown"))

(defn status-token-label
  [token]
  (cond
    (keyword? token) (-> token name str/upper-case)
    (string? token) (str/upper-case token)
    (nil? token) "UNKNOWN"
    :else (-> token str str/upper-case)))
