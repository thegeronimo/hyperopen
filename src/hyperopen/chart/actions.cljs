(ns hyperopen.chart.actions
  (:require [hyperopen.websocket.migration-flags :as migration-flags]))

(defn- chart-dropdown-visibility-path-values
  [open-dropdown]
  [[[:chart-options :timeframes-dropdown-visible] (= open-dropdown :timeframes)]
   [[:chart-options :chart-type-dropdown-visible] (= open-dropdown :chart-type)]
   [[:chart-options :indicators-dropdown-visible] (= open-dropdown :indicators)]])

(defn- chart-dropdown-projection-effect
  ([open-dropdown]
   (chart-dropdown-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (chart-dropdown-visibility-path-values open-dropdown))]))

(defn toggle-timeframes-dropdown
  [state]
  (let [current-visible (boolean (get-in state [:chart-options :timeframes-dropdown-visible]))
        open-dropdown (when-not current-visible :timeframes)]
    [(chart-dropdown-projection-effect open-dropdown)]))

(defn select-chart-timeframe
  [state timeframe]
  (cond-> [(chart-dropdown-projection-effect nil [[[:chart-options :selected-timeframe] timeframe]])
           [:effects/local-storage-set "chart-timeframe" (name timeframe)]
           [:effects/sync-active-candle-subscription :interval timeframe]]
    (migration-flags/should-fetch-candle-snapshot? state
                                                   (:active-asset state)
                                                   timeframe)
    (conj [:effects/fetch-candle-snapshot :interval timeframe])))

(defn toggle-chart-type-dropdown
  [state]
  (let [current-visible (boolean (get-in state [:chart-options :chart-type-dropdown-visible]))
        open-dropdown (when-not current-visible :chart-type)]
    [(chart-dropdown-projection-effect open-dropdown)]))

(defn select-chart-type
  [state chart-type]
  [(chart-dropdown-projection-effect nil [[[:chart-options :selected-chart-type] chart-type]])
   [:effects/local-storage-set "chart-type" (name chart-type)]])

(defn toggle-indicators-dropdown
  [state]
  (let [current-visible (boolean (get-in state [:chart-options :indicators-dropdown-visible]))
        open-dropdown (when-not current-visible :indicators)]
    [(chart-dropdown-projection-effect open-dropdown
                                       [[[:chart-options :indicators-search-term] ""]])]))

(defn update-indicators-search
  [_state value]
  [[:effects/save
    [:chart-options :indicators-search-term]
    (if (string? value)
      value
      (str (or value "")))]])
