(ns hyperopen.trading-settings
  (:require [hyperopen.platform :as platform]))

(def storage-key
  "hyperopen:trading-settings:v1")

(def open-order-safety-modes
  #{:strict :extended :off})

(defn normalize-open-order-safety-mode
  [mode]
  (let [mode* (cond
                (keyword? mode) mode
                (string? mode) (keyword mode)
                :else nil)]
    (if (contains? open-order-safety-modes mode*)
      mode*
      :strict)))

(def margin-rec-risk-modes
  #{:conservative :balanced :capital-efficient})

(defn normalize-margin-rec-risk-mode
  [mode]
  (let [mode* (cond
                (keyword? mode) mode
                (string? mode) (keyword mode)
                :else nil)]
    (if (contains? margin-rec-risk-modes mode*)
      mode*
      :balanced)))

(def default-state
  {:fill-alerts-enabled? true
   :sound-on-fill? false
   :animate-orderbook? true
   :show-chart-trades? true
   :confirm-open-orders? false
   :confirm-close-position? false
   :confirm-market-orders? true
   :open-order-safety-mode :strict
   :margin-rec-risk-mode :balanced
   :margin-rec-auto-topup? false
   ;; Optimizer execution: stage closing orders (sell longs / buy back shorts) for
   ;; held PERP positions the trader removed from the allocation, by default.
   ;; Spot holdings and assets the trader requested but the engine dropped (missing
   ;; history, ...) are never auto-closed. Default-on: an allocation the trader
   ;; edited an asset out of reads as "I want out of it".
   :optimizer-auto-exit-excluded? true})

(defn normalize-state
  [value]
  (let [settings (if (map? value) value {})]
    {:fill-alerts-enabled? (not (false? (:fill-alerts-enabled? settings)))
     :sound-on-fill? (true? (:sound-on-fill? settings))
     :animate-orderbook? (not (false? (:animate-orderbook? settings)))
     :show-chart-trades? (not (false? (:show-chart-trades? settings)))
     :confirm-open-orders? (true? (:confirm-open-orders? settings))
     :confirm-close-position? (true? (:confirm-close-position? settings))
     :confirm-market-orders? (not (false? (:confirm-market-orders? settings)))
     :open-order-safety-mode (normalize-open-order-safety-mode
                              (:open-order-safety-mode settings))
     :margin-rec-risk-mode (normalize-margin-rec-risk-mode
                            (:margin-rec-risk-mode settings))
     :margin-rec-auto-topup? (true? (:margin-rec-auto-topup? settings))
     :optimizer-auto-exit-excluded? (not (false? (:optimizer-auto-exit-excluded?
                                                  settings)))}))

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

(defn sound-on-fill?
  [state]
  (:sound-on-fill? (state-settings state)))

(defn animate-orderbook?
  [state]
  (:animate-orderbook? (state-settings state)))

(defn show-chart-trades?
  [state]
  (:show-chart-trades? (state-settings state)))

(defn confirm-open-orders?
  [state]
  (:confirm-open-orders? (state-settings state)))

(defn confirm-close-position?
  [state]
  (:confirm-close-position? (state-settings state)))

(defn confirm-market-orders?
  [state]
  (:confirm-market-orders? (state-settings state)))

(defn open-order-safety-mode
  [state]
  (:open-order-safety-mode (state-settings state)))

(defn margin-rec-risk-mode
  [state]
  (:margin-rec-risk-mode (state-settings state)))

(defn margin-rec-auto-topup?
  [state]
  (:margin-rec-auto-topup? (state-settings state)))

(defn optimizer-auto-exit-excluded?
  [state]
  (:optimizer-auto-exit-excluded? (state-settings state)))
