(ns hyperopen.views.trading-chart.runtime-edge-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.runtime :as runtime]
            [hyperopen.views.trading-chart.runtime-state :as chart-runtime]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]))

(def candle-data
  [{:time 1700000000 :open 100 :high 101 :low 99 :close 100 :volume 10}])

(defn- expose-arity!
  [f arity]
  (aset f (str "cljs$core$IFn$_invoke$arity$" arity) f)
  f)

(def noop-2
  (expose-arity! (fn [_ _] nil) 2))

(def noop-3
  (expose-arity! (fn [_ _ _] nil) 3))

(def noop-4
  (expose-arity! (fn [_ _ _ _] nil) 4))

(defn- fake-chart-obj
  []
  (doto #js {}
    (aset "chart" #js {})
    (aset "mainSeries" #js {:id "main"})
    (aset "volumeSeries" #js {:id "volume"})
    (aset "indicatorSeries" #js [])))

(defn- base-context
  [overrides]
  (merge {:candle-data candle-data
          :chart-type :candlestick
          :indicators-data []
          :indicator-series-data []
          :legend-meta {:symbol "BTC"
                        :timeframe-label "1D"
                        :venue "Hyperopen"
                        :candle-data candle-data}
          :legend-deps {}
          :series-options {:price-decimals 2}
          :selected-timeframe :1d
          :persistence-deps {:asset "BTC"
                             :candles candle-data}
          :volume-visible? true
          :main-series-markers []
          :position-overlay nil
          :position-overlay-deps {}
          :open-order-overlays []
          :overlay-deps {}
          :volume-indicator-deps {}
          :context-menu-deps {}}
         overrides))

(defn- render!
  [context lifecycle node]
  ((runtime/chart-canvas-on-render context)
   {:replicant/life-cycle lifecycle
    :replicant/node node}))

(deftest chart-runtime-coalesces-latest-decoration-context-test
  (let [node #js {}
        chart-obj (fake-chart-obj)
        scheduled-callbacks (atom [])
        marker-args (atom [])
        schedule! (fn [callback]
                    (swap! scheduled-callbacks conj callback)
                    :frame-1)
        context-base {:schedule-decoration-frame! schedule!
                      :cancel-decoration-frame! (fn [_] nil)}]
    (with-redefs [chart-interop/create-chart-with-volume-and-series!
                  (expose-arity! (fn [_ _ _ _] chart-obj) 4)
                  chart-interop/create-legend!
                  (expose-arity! (fn [_ _ _ _]
                                   #js {:update (fn [_] nil)
                                        :destroy (fn [] nil)})
                                 4)
                  chart-interop/sync-baseline-base-value-subscription! noop-2
                  chart-interop/set-series-data! (expose-arity! (fn [_ _ _ _] nil) 4)
                  chart-interop/set-volume-data! noop-2
                  chart-interop/set-indicator-data! noop-2
                  chart-interop/set-main-series-markers!
                  (expose-arity! (fn [_ markers]
                                   (swap! marker-args conj markers))
                                 2)
                  chart-interop/sync-position-overlays! noop-4
                  chart-interop/sync-open-order-overlays! noop-4
                  chart-interop/sync-volume-indicator-overlay! noop-4
                  chart-interop/sync-chart-context-menu-overlay! noop-4
                  chart-interop/sync-chart-navigation-overlay! noop-4
                  chart-interop/apply-default-visible-range! noop-2
                  chart-interop/apply-persisted-visible-range!
                  (expose-arity! (fn [_ _ _] (js/Promise.resolve nil)) 3)
                  chart-interop/subscribe-visible-range-persistence! noop-3]
      (render! (base-context (assoc context-base :main-series-markers [:mount-marker]))
               :replicant.life-cycle/mount
               node)
      (render! (base-context (assoc context-base :main-series-markers [:update-marker]))
               :replicant.life-cycle/update
               node)
      (is (= 1 (count @scheduled-callbacks)))
      (is (= :frame-1 (:decoration-frame-id (chart-runtime/get-state node))))
      ((first @scheduled-callbacks) 16)
      (is (= [[:update-marker]] @marker-args))
      (is (nil? (:decoration-frame-id (chart-runtime/get-state node))))
      (is (nil? (:pending-decoration-context (chart-runtime/get-state node))))
      (chart-runtime/clear-state! node))))
