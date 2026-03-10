(ns hyperopen.workbench.scenes.markets.chart-controls-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.trading-chart.chart-type-dropdown :as chart-type-dropdown]
            [hyperopen.views.trading-chart.indicators-dropdown :as indicators-dropdown]
            [hyperopen.views.trading-chart.timeframe-dropdown :as timeframe-dropdown]))

(portfolio/configure-scenes
  {:title "Chart Controls"
   :collection :markets})

(defn- controls-state
  [overrides]
  (merge {:selected-timeframe :1d
          :timeframes-dropdown-visible false
          :selected-chart-type :candlestick
          :chart-type-dropdown-visible false
          :indicators-dropdown-visible false
          :volume-visible? true
          :active-indicators {:ema {:period 20}
                              :rsi {:period 14}}
          :search-term ""}
         overrides))

(defn- remove-indicator
  [state indicator-id]
  (update state :active-indicators dissoc indicator-id))

(defn- chart-control-reducers
  []
  {:actions/toggle-timeframes-dropdown
   (fn [state _dispatch-data]
     (update state :timeframes-dropdown-visible not))

   :actions/select-chart-timeframe
   (fn [state _dispatch-data timeframe]
     (assoc state
            :selected-timeframe timeframe
            :timeframes-dropdown-visible false))

   :actions/toggle-chart-type-dropdown
   (fn [state _dispatch-data]
     (update state :chart-type-dropdown-visible not))

   :actions/select-chart-type
   (fn [state _dispatch-data chart-type]
     (assoc state
            :selected-chart-type chart-type
            :chart-type-dropdown-visible false))

   :actions/toggle-indicators-dropdown
   (fn [state _dispatch-data]
     (update state :indicators-dropdown-visible not))

   :actions/update-indicators-search
   (fn [state _dispatch-data value]
     (assoc state :search-term value))

   :actions/show-volume-indicator
   (fn [state _dispatch-data]
     (assoc state :volume-visible? true))

   :actions/hide-volume-indicator
   (fn [state _dispatch-data]
     (assoc state :volume-visible? false))

   :actions/add-indicator
   (fn [state _dispatch-data indicator-id config]
     (assoc-in state [:active-indicators indicator-id] config))

   :actions/remove-indicator
   (fn [state _dispatch-data indicator-id]
     (remove-indicator state indicator-id))

   :actions/update-indicator-period
   (fn [state _dispatch-data indicator-id value]
     (assoc-in state [:active-indicators indicator-id :period]
               (js/parseInt (or value "0") 10)))})

(defonce default-controls-store
  (ws/create-store ::default-controls (controls-state {})))

(defonce chart-type-store
  (ws/create-store ::chart-type-controls
                   (controls-state {:chart-type-dropdown-visible true
                                    :selected-chart-type :baseline})))

(defonce indicators-store
  (ws/create-store ::indicators-controls
                   (controls-state {:indicators-dropdown-visible true
                                    :search-term "vol"})))

(defn- controls-shell
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (chart-control-reducers)
    (layout/desktop-shell
     (layout/panel-shell
      [:div {:class ["flex" "items-start" "gap-3" "p-4"]}
       (timeframe-dropdown/timeframe-dropdown @store)
       (chart-type-dropdown/chart-type-dropdown @store)
       (indicators-dropdown/indicators-dropdown @store)])))))

(portfolio/defscene default-toolbar
  :params default-controls-store
  [store]
  (controls-shell store))

(portfolio/defscene chart-type-open
  :params chart-type-store
  [store]
  (controls-shell store))

(portfolio/defscene indicators-open
  :params indicators-store
  [store]
  (controls-shell store))
