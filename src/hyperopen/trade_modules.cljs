(ns hyperopen.trade-modules
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [hyperopen.trading-indicators-modules :as trading-indicators-modules]
            [shadow.loader :as loader]))

(def ^:private trade-chart-module-name
  "trade_chart")

(def ^:private exported-trade-chart-view-path
  ["hyperopen" "views" "trading_chart" "module" "trade_chart_view"])

(defonce ^:private resolved-trade-chart-view* (atom nil))

(defn default-state
  []
  {:chart {:loaded? false
           :loading? false
           :error nil}
   :indicators (trading-indicators-modules/default-state)})

(defn resolved-trade-chart-view
  []
  @resolved-trade-chart-view*)

(defn- resolve-exported-view
  []
  (let [root (or (some-> js/goog .-global)
                 js/globalThis)]
    (reduce (fn [acc segment]
              (when acc
                (gobj/get acc segment)))
            root
            exported-trade-chart-view-path)))

(defn- cached-or-exported-view
  []
  (let [cached-view (resolved-trade-chart-view)]
    (cond
      (fn? cached-view)
      cached-view

      (some? cached-view)
      (do
        (reset! resolved-trade-chart-view* nil)
        nil)

      :else
      (when-let [resolved-view (resolve-exported-view)]
        (when (fn? resolved-view)
          (reset! resolved-trade-chart-view* resolved-view)
          resolved-view)))))

(defn trade-chart-ready?
  [_state]
  (some? (cached-or-exported-view)))

(defn trade-chart-loading?
  [state]
  (true? (get-in state [:trade-modules :chart :loading?])))

(defn trade-chart-error
  [state]
  (get-in state [:trade-modules :chart :error]))

(defn render-trade-chart-view
  [state]
  (when-let [view (cached-or-exported-view)]
    (view state)))

(defn mark-trade-chart-loading
  [state]
  (-> state
      (assoc-in [:trade-modules :chart :loading?] true)
      (assoc-in [:trade-modules :chart :error] nil)))

(defn mark-trade-chart-loaded
  [state]
  (-> state
      (assoc-in [:trade-modules :chart :loaded?] true)
      (assoc-in [:trade-modules :chart :loading?] false)
      (assoc-in [:trade-modules :chart :error] nil)))

(defn mark-trade-chart-failed
  [state err]
  (let [message (or (some-> err .-message)
                    (some-> err str str/trim not-empty)
                    "Failed to load trade chart.")]
    (-> state
        (assoc-in [:trade-modules :chart :loaded?] false)
        (assoc-in [:trade-modules :chart :loading?] false)
        (assoc-in [:trade-modules :chart :error] message))))

(defn load-trade-chart-module!
  [store]
  (if-let [existing-view (cached-or-exported-view)]
    (do
      (swap! store mark-trade-chart-loaded)
      (js/Promise.resolve existing-view))
    (let [resolve-loaded-view!
          (fn []
            (let [resolved-view (resolve-exported-view)]
              (when-not (fn? resolved-view)
                (throw (js/Error.
                        "Loaded trade chart module without exported view.")))
              (reset! resolved-trade-chart-view* resolved-view)
              (swap! store mark-trade-chart-loaded)
              resolved-view))]
      (swap! store mark-trade-chart-loading)
      (try
        (if (loader/loaded? trade-chart-module-name)
          (js/Promise.resolve (resolve-loaded-view!))
          (-> (loader/load trade-chart-module-name)
              (.then (fn [_]
                       (resolve-loaded-view!)))
              (.catch (fn [err]
                        (swap! store mark-trade-chart-failed err)
                        (js/Promise.reject err)))))
        (catch :default err
          (swap! store mark-trade-chart-failed err)
          (js/Promise.reject err))))))
