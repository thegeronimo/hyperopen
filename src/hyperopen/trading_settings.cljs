(ns hyperopen.trading-settings
  (:require [hyperopen.platform :as platform]))

(def storage-key
  "hyperopen:trading-settings:v1")

(def default-state
  {:fill-alerts-enabled? true
   :animate-orderbook? true
   :show-fill-markers? false
   :confirm-open-orders? false
   :confirm-close-position? false})

(defn normalize-state
  [value]
  (let [settings (if (map? value) value {})]
    {:fill-alerts-enabled? (not (false? (:fill-alerts-enabled? settings)))
     :animate-orderbook? (not (false? (:animate-orderbook? settings)))
     :show-fill-markers? (true? (:show-fill-markers? settings))
     :confirm-open-orders? (true? (:confirm-open-orders? settings))
     :confirm-close-position? (true? (:confirm-close-position? settings))}))

(defn restore-state
  []
  (try
    (let [raw (platform/local-storage-get storage-key)]
      (if (seq raw)
        (normalize-state (js->clj (js/JSON.parse raw) :keywordize-keys true))
        default-state))
    (catch :default _
      default-state)))

(defn- state-settings
  [state]
  (normalize-state (:trading-settings state)))

(defn fill-alerts-enabled?
  [state]
  (:fill-alerts-enabled? (state-settings state)))

(defn animate-orderbook?
  [state]
  (:animate-orderbook? (state-settings state)))

(defn show-fill-markers?
  [state]
  (:show-fill-markers? (state-settings state)))

(defn confirm-open-orders?
  [state]
  (:confirm-open-orders? (state-settings state)))

(defn confirm-close-position?
  [state]
  (:confirm-close-position? (state-settings state)))
