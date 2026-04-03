(ns hyperopen.trading-indicators-modules
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [shadow.loader :as loader]))

(def ^:private trading-indicators-module-name
  "trading_indicators")

(def ^:private exported-function-paths
  {:calculate-indicator ["hyperopen" "views" "trading_chart" "indicators_module" "calculateIndicator"]})

(defonce ^:private resolved-trading-indicators* (atom nil))
(defonce ^:private inflight-trading-indicators-load* (atom nil))

(defn default-state
  []
  {:loaded? false
   :loading? false
   :error nil})

(defn reset-trading-indicators-module-state!
  []
  (reset! resolved-trading-indicators* nil)
  (reset! inflight-trading-indicators-load* nil))

(defn- resolve-exported-function
  [path-segments]
  (let [root (or (some-> js/goog .-global)
                 js/globalThis)]
    (reduce (fn [acc segment]
              (when acc
                (gobj/get acc segment)))
            root
            path-segments)))

(defn- trading-indicators-ready-map?
  [resolved]
  (and (map? resolved)
       (seq resolved)
       (every? fn? (vals resolved))))

(defn- resolve-exported-trading-indicators
  []
  (reduce-kv (fn [resolved key path-segments]
               (assoc resolved key (resolve-exported-function path-segments)))
             {}
             exported-function-paths))

(defn resolved-trading-indicators
  []
  (let [cached @resolved-trading-indicators*]
    (cond
      (trading-indicators-ready-map? cached)
      cached

      (some? cached)
      (do
        (reset! resolved-trading-indicators* nil)
        nil)

      :else
      (let [resolved (resolve-exported-trading-indicators)]
        (when (trading-indicators-ready-map? resolved)
          (reset! resolved-trading-indicators* resolved)
          resolved)))))

(defn trading-indicators-ready?
  [state]
  (or (some? (resolved-trading-indicators))
      (true? (get-in state [:trade-modules :indicators :loaded?]))))

(defn trading-indicators-loading?
  [state]
  (or (some? @inflight-trading-indicators-load*)
      (true? (get-in state [:trade-modules :indicators :loading?]))))

(defn trading-indicators-error
  [state]
  (get-in state [:trade-modules :indicators :error]))

(defn mark-trading-indicators-loading
  [state]
  (-> state
      (assoc-in [:trade-modules :indicators :loading?] true)
      (assoc-in [:trade-modules :indicators :error] nil)))

(defn mark-trading-indicators-loaded
  [state]
  (-> state
      (assoc-in [:trade-modules :indicators :loaded?] true)
      (assoc-in [:trade-modules :indicators :loading?] false)
      (assoc-in [:trade-modules :indicators :error] nil)))

(defn mark-trading-indicators-failed
  [state err]
  (let [message (or (some-> err .-message)
                    (some-> err str str/trim not-empty)
                    "Failed to load trading indicators.")]
    (-> state
        (assoc-in [:trade-modules :indicators :loaded?] false)
        (assoc-in [:trade-modules :indicators :loading?] false)
        (assoc-in [:trade-modules :indicators :error] message))))

(defn calculate-indicator
  [indicator-type data params]
  (when-let [runtime (resolved-trading-indicators)]
    ((:calculate-indicator runtime) indicator-type data params)))

(defn load-trading-indicators-module!
  [store]
  (if-let [existing (resolved-trading-indicators)]
    (do
      (swap! store mark-trading-indicators-loaded)
      (js/Promise.resolve existing))
    (do
      (swap! store mark-trading-indicators-loading)
      (if-let [existing-load @inflight-trading-indicators-load*]
        existing-load
        (let [resolve-loaded-trading-indicators!
              (fn []
                (let [resolved (resolve-exported-trading-indicators)]
                  (when-not (trading-indicators-ready-map? resolved)
                    (throw (js/Error.
                            "Loaded trading indicators module without exported helpers.")))
                  (reset! resolved-trading-indicators* resolved)
                  (swap! store mark-trading-indicators-loaded)
                  resolved))]
          (try
            (let [load-promise
                  (-> (js/Promise.resolve
                       (when-not (loader/loaded? trading-indicators-module-name)
                         (loader/load trading-indicators-module-name)))
                      (.then (fn [_]
                               (resolve-loaded-trading-indicators!)))
                      (.catch (fn [err]
                                (swap! store mark-trading-indicators-failed err)
                                (js/Promise.reject err)))
                      (.finally (fn []
                                  (reset! inflight-trading-indicators-load* nil))))]
              (reset! inflight-trading-indicators-load* load-promise)
              load-promise)
            (catch :default err
              (swap! store mark-trading-indicators-failed err)
              (js/Promise.reject err))))))))
