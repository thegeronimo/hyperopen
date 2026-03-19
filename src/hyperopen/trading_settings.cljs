(ns hyperopen.trading-settings
  (:require [hyperopen.platform :as platform]))

(def storage-key
  "hyperopen:trading-settings:v1")

(def default-state
  {:fill-alerts-enabled? true})

(defn normalize-state
  [value]
  (let [settings (if (map? value) value {})]
    {:fill-alerts-enabled? (not (false? (:fill-alerts-enabled? settings)))}))

(defn restore-state
  []
  (try
    (let [raw (platform/local-storage-get storage-key)]
      (if (seq raw)
        (normalize-state (js->clj (js/JSON.parse raw) :keywordize-keys true))
        default-state))
    (catch :default _
      default-state)))

(defn fill-alerts-enabled?
  [state]
  (not (false? (get-in state [:trading-settings :fill-alerts-enabled?]))))
