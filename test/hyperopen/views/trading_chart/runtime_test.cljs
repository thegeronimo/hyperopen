(ns hyperopen.views.trading-chart.runtime-test
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

(defn- fake-time-scale
  [visible-range set-visible-range-calls]
  (doto #js {}
    (aset "getVisibleLogicalRange" (fn [] visible-range))
    (aset "setVisibleLogicalRange" (fn [range]
                                     (swap! set-visible-range-calls conj range)))))

(defn- fake-chart
  [time-scale remove-series-calls remove-chart-calls]
  (doto #js {}
    (aset "timeScale" (fn [] time-scale))
    (aset "removeSeries" (fn [series]
                           (swap! remove-series-calls conj series)))
    (aset "remove" (fn []
                     (swap! remove-chart-calls inc)))))

(defn- fake-chart-obj
  [{:keys [chart main-series volume-series indicator-series]}]
  (doto #js {}
    (aset "chart" chart)
    (aset "mainSeries" main-series)
    (aset "volumeSeries" volume-series)
    (aset "indicatorSeries" indicator-series)))

(defn- fake-accessibility-node
  [accessibility-attrs]
  (let [table (doto #js {:tagName "TABLE"}
                (aset "setAttribute" (fn [attr value]
                                       (swap! accessibility-attrs conj ["TABLE" attr value]))))
        row (doto #js {:tagName "TR"}
              (aset "setAttribute" (fn [attr value]
                                     (swap! accessibility-attrs conj ["TR" attr value]))))
        cell (doto #js {:tagName "TD"}
               (aset "setAttribute" (fn [attr value]
                                      (swap! accessibility-attrs conj ["TD" attr value]))))]
    (doto #js {}
      (aset "querySelectorAll" (fn [_selector]
                                 #js [table row cell])))))

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

(deftest chart-runtime-owner-preserves-lifecycle-contract-test
  (let [accessibility-attrs (atom [])
        node (fake-accessibility-node accessibility-attrs)
        remove-series-calls (atom [])
        remove-chart-calls (atom 0)
        set-visible-range-calls (atom [])
        visible-range #js {:from 2 :to 8}
        chart (fake-chart (fake-time-scale visible-range set-visible-range-calls)
                          remove-series-calls
                          remove-chart-calls)
        original-main-series #js {:id "candlestick"}
        new-main-series #js {:id "area"}
        volume-series #js {:id "volume"}
        chart-obj (fake-chart-obj {:chart chart
                                   :main-series original-main-series
                                   :volume-series volume-series
                                   :indicator-series #js []})
        volume-creations (atom [])
        indicator-creations (atom [])
        legend-creations (atom [])
        legend-updates (atom [])
        order-calls (atom [])
        baseline-sync-calls (atom [])
        add-series-calls (atom [])
        series-data-calls (atom [])
        volume-data-calls (atom [])
        scheduled-callbacks (atom [])
        canceled-frame-ids (atom [])
        frame-counter (atom 0)
        schedule! (fn [callback]
                    (let [frame-id (keyword (str "frame-" (swap! frame-counter inc)))]
                      (swap! scheduled-callbacks conj [frame-id callback])
                      frame-id))
        cancel! (fn [frame-id]
                  (swap! canceled-frame-ids conj frame-id))
        legend-control (doto #js {}
                         (aset "update" (fn [legend-meta]
                                          (swap! legend-updates conj legend-meta)))
                         (aset "destroy" (fn []
                                           (swap! order-calls conj :legend-destroy))))
        record-markers! (expose-arity! (fn [_ _]
                                         (swap! order-calls conj :markers))
                                       2)
        record-position! (expose-arity! (fn [_ _ _ _]
                                          (swap! order-calls conj :position))
                                        4)
        record-open-orders! (expose-arity! (fn [_ _ _ _]
                                             (swap! order-calls conj :open-orders))
                                           4)
        record-volume! (expose-arity! (fn [_ _ _ _]
                                        (swap! order-calls conj :volume))
                                      4)
        record-context-menu! (expose-arity! (fn [_ _ _ _]
                                             (swap! order-calls conj :context-menu))
                                           4)
        record-navigation! (expose-arity! (fn [_ _ _ _]
                                            (swap! order-calls conj :navigation))
                                          4)
        cleanup! (fn []
                   (swap! order-calls conj :visible-range-cleanup))]
    (with-redefs [chart-interop/create-chart-with-volume-and-series!
                  (expose-arity! (fn [container chart-type data opts]
                                   (reset! volume-creations [container chart-type data opts])
                                   chart-obj)
                                 4)
                  chart-interop/create-chart-with-indicators!
                  (expose-arity! (fn [container chart-type data indicators opts]
                                   (reset! indicator-creations [container chart-type data indicators opts])
                                   chart-obj)
                                 5)
                  chart-interop/create-legend!
                  (expose-arity! (fn [container chart* legend-meta legend-deps]
                                   (reset! legend-creations [container chart* legend-meta legend-deps])
                                   legend-control)
                                 4)
                  chart-interop/sync-baseline-base-value-subscription!
                  (expose-arity! (fn [chart-obj* chart-type]
                                   (swap! baseline-sync-calls conj [chart-obj* chart-type]))
                                 2)
                  chart-interop/add-series!
                  (expose-arity! (fn [chart* next-type]
                                   (swap! add-series-calls conj [chart* next-type])
                                   new-main-series)
                                 2)
                  chart-interop/set-series-data!
                  (expose-arity! (fn [series data type options]
                                   (swap! series-data-calls conj [series data type options]))
                                 4)
                  chart-interop/set-volume-data!
                  (expose-arity! (fn [series data]
                                   (swap! volume-data-calls conj [series data]))
                                 2)
                  chart-interop/set-indicator-data! noop-2
                  chart-interop/set-main-series-markers! record-markers!
                  chart-interop/sync-position-overlays! record-position!
                  chart-interop/sync-open-order-overlays! record-open-orders!
                  chart-interop/sync-volume-indicator-overlay! record-volume!
                  chart-interop/sync-chart-context-menu-overlay! record-context-menu!
                  chart-interop/sync-chart-navigation-overlay! record-navigation!
                  chart-interop/apply-default-visible-range! noop-2
                  chart-interop/apply-persisted-visible-range!
                  (expose-arity! (fn [_ _ _] (js/Promise.resolve nil)) 3)
                  chart-interop/subscribe-visible-range-persistence!
                  (expose-arity! (fn [_ _ _] cleanup!) 3)
                  chart-interop/clear-open-order-overlays!
                  (fn [_] (swap! order-calls conj :clear-open-orders))
                  chart-interop/clear-position-overlays!
                  (fn [_] (swap! order-calls conj :clear-position-overlays))
                  chart-interop/clear-volume-indicator-overlay!
                  (fn [_] (swap! order-calls conj :clear-volume-overlay))
                  chart-interop/clear-chart-context-menu-overlay!
                  (fn [_] (swap! order-calls conj :clear-context-menu-overlay))
                  chart-interop/clear-chart-navigation-overlay!
                  (fn [_] (swap! order-calls conj :clear-navigation-overlay))
                  chart-interop/clear-baseline-base-value-subscription!
                  (fn [_] (swap! order-calls conj :clear-baseline))]
      (render! (base-context {:schedule-decoration-frame! schedule!
                              :cancel-decoration-frame! cancel!})
               :replicant.life-cycle/mount
               node)
      (is (= [node :candlestick candle-data {:series-options {:price-decimals 2}
                                             :volume-visible? true}]
             @volume-creations))
      (is (empty? @indicator-creations))
      (is (= [node chart (:legend-meta (base-context {})) {}] @legend-creations))
      (is (= :frame-1 (:decoration-frame-id (chart-runtime/get-state node))))
      (is (empty? @order-calls))
      (is (false? (:chart-accessibility-applied? (chart-runtime/get-state node))))
      ((second (first @scheduled-callbacks)) 16)
      (is (= [:markers :position :open-orders :volume :context-menu :navigation]
             @order-calls))
      (is (= [["TABLE" "aria-hidden" "true"]
              ["TABLE" "role" "presentation"]
              ["TR" "aria-hidden" "true"]
              ["TD" "aria-hidden" "true"]]
             @accessibility-attrs))
      (is (nil? (:decoration-frame-id (chart-runtime/get-state node))))
      (is (true? (:chart-accessibility-applied? (chart-runtime/get-state node))))

      (render! (base-context {:chart-type :area
                              :schedule-decoration-frame! schedule!
                              :cancel-decoration-frame! cancel!})
               :replicant.life-cycle/update
               node)
      (is (= [[chart :area]] @add-series-calls))
      (is (= [original-main-series] @remove-series-calls))
      (is (= [[new-main-series candle-data :area {:price-decimals 2}]]
             @series-data-calls))
      (is (= [[volume-series candle-data]] @volume-data-calls))
      (is (= [visible-range] @set-visible-range-calls))
      (is (= :area (:chart-type (chart-runtime/get-state node))))
      (is (= new-main-series (.-mainSeries ^js (:chart-obj (chart-runtime/get-state node)))))
      (is (= [(:legend-meta (base-context {:chart-type :area
                                            :schedule-decoration-frame! schedule!
                                            :cancel-decoration-frame! cancel!}))]
             @legend-updates))
      (is (= [[chart-obj :candlestick] [chart-obj :area]]
             @baseline-sync-calls))
      (is (= :frame-2 (:decoration-frame-id (chart-runtime/get-state node))))

      (render! (base-context {:schedule-decoration-frame! schedule!
                              :cancel-decoration-frame! cancel!})
               :replicant.life-cycle/unmount
               node)
      (is (= [:frame-2] @canceled-frame-ids))
      (is (= [:markers
              :position
              :open-orders
              :volume
              :context-menu
              :navigation
              :legend-destroy
              :clear-open-orders
              :clear-position-overlays
              :clear-volume-overlay
              :clear-context-menu-overlay
              :clear-navigation-overlay
              :clear-baseline
              :visible-range-cleanup]
             @order-calls))
      (is (= 1 @remove-chart-calls))
      (is (= {} (chart-runtime/get-state node)))))

  (let [node #js {}
        indicator-creations (atom [])
        chart (fake-chart (fake-time-scale nil (atom [])) (atom []) (atom 0))
        chart-obj (fake-chart-obj {:chart chart
                                   :main-series #js {:id "main"}
                                   :volume-series #js {:id "volume"}
                                   :indicator-series #js []})]
    (with-redefs [chart-interop/create-chart-with-volume-and-series!
                  (expose-arity! (fn [_ _ _ _]
                                   (throw (js/Error. "unexpected volume chart path")))
                                 4)
                  chart-interop/create-chart-with-indicators!
                  (expose-arity! (fn [container chart-type data indicators opts]
                                   (reset! indicator-creations [container chart-type data indicators opts])
                                   chart-obj)
                                 5)
                  chart-interop/create-legend!
                  (expose-arity! (fn [_ _ _ _]
                                   #js {:update (fn [_] nil)
                                        :destroy (fn [] nil)})
                                 4)
                  chart-interop/sync-baseline-base-value-subscription! noop-2
                  chart-interop/set-main-series-markers! noop-2
                  chart-interop/sync-position-overlays! noop-4
                  chart-interop/sync-open-order-overlays! noop-4
                  chart-interop/sync-volume-indicator-overlay! noop-4
                  chart-interop/sync-chart-context-menu-overlay! noop-4
                  chart-interop/sync-chart-navigation-overlay! noop-4
                  chart-interop/apply-default-visible-range! noop-2
                  chart-interop/apply-persisted-visible-range!
                  (expose-arity! (fn [_ _ _] (js/Promise.resolve nil)) 3)
                  chart-interop/subscribe-visible-range-persistence! noop-3]
      (render! (base-context {:indicators-data [{:id :sma}]
                              :schedule-decoration-frame! (fn [_] nil)
                              :cancel-decoration-frame! (fn [_] nil)})
               :replicant.life-cycle/mount
               node)
      (is (= [node :candlestick candle-data [{:id :sma}] {:series-options {:price-decimals 2}
                                                          :volume-visible? true}]
             @indicator-creations))
      (chart-runtime/clear-state! node))))
