(ns hyperopen.views.trading-chart.utils.chart-interop-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.chart-interop-contracts :as chart-contracts]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.chart-navigation-overlay :as chart-navigation-overlay]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.position-overlays :as position-overlays]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]
            [hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay :as volume-indicator-overlay]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(deftest series-wrapper-functions-delegate-to-series-module-test
  (let [chart #js {:id "chart"}
        calls (atom [])
        unary (fn [id]
                (fn [chart*]
                  (swap! calls conj [id chart*])
                  id))]
    (with-redefs [series/add-area-series! (unary :area)
                  series/add-bar-series! (unary :bar)
                  series/add-high-low-series! (unary :high-low)
                  series/add-baseline-series! (unary :baseline)
                  series/add-candlestick-series! (unary :candlestick)
                  series/add-hollow-candles-series! (unary :hollow-candles)
                  series/add-heikin-ashi-series! (unary :heikin-ashi)
                  series/add-histogram-series! (unary :histogram)
                  series/add-columns-series! (unary :columns)
                  series/add-line-series! (unary :line)
                  series/add-line-with-markers-series! (unary :line-with-markers)
                  series/add-step-line-series! (unary :step-line)
                  series/add-hlc-area-series! (unary :hlc-area)
                  series/add-volume-series! (unary :volume)
                  series/add-series! (fn [chart* chart-type]
                                       (swap! calls conj [:series chart* chart-type])
                                       [:series chart-type])]
      (is (= :area (chart-interop/add-area-series! chart)))
      (is (= :bar (chart-interop/add-bar-series! chart)))
      (is (= :high-low (chart-interop/add-high-low-series! chart)))
      (is (= :baseline (chart-interop/add-baseline-series! chart)))
      (is (= :candlestick (chart-interop/add-candlestick-series! chart)))
      (is (= :hollow-candles (chart-interop/add-hollow-candles-series! chart)))
      (is (= :heikin-ashi (chart-interop/add-heikin-ashi-series! chart)))
      (is (= :histogram (chart-interop/add-histogram-series! chart)))
      (is (= :columns (chart-interop/add-columns-series! chart)))
      (is (= :line (chart-interop/add-line-series! chart)))
      (is (= :line-with-markers (chart-interop/add-line-with-markers-series! chart)))
      (is (= :step-line (chart-interop/add-step-line-series! chart)))
      (is (= :hlc-area (chart-interop/add-hlc-area-series! chart)))
      (is (= :volume (chart-interop/add-volume-series! chart)))
      (is (= [:series :line] (chart-interop/add-series! chart :line))))
    (is (= [[:area chart]
            [:bar chart]
            [:high-low chart]
            [:baseline chart]
            [:candlestick chart]
            [:hollow-candles chart]
            [:heikin-ashi chart]
            [:histogram chart]
            [:columns chart]
            [:line chart]
            [:line-with-markers chart]
            [:step-line chart]
            [:hlc-area chart]
            [:volume chart]
            [:series chart :line]]
           @calls))))

(deftest transform-data-for-single-value-delegates-to-transform-collaborator-test
  (let [calls (atom [])
        candles [{:time 1 :close 10}
                 {:time 2 :close 15}]
        out [{:time 1 :value 20}
             {:time 2 :value 30}]
        value-fn (fn [candle] (* 2 (:close candle)))]
    (with-redefs [transforms/transform-data-for-single-value (fn [data value-fn*]
                                                                (swap! calls conj [data value-fn*])
                                                                out)]
      (is (= out (chart-interop/transform-data-for-single-value candles value-fn))))
    (is (= [[candles value-fn]] @calls))))

(deftest fit-content-calls-time-scale-fit-content-test
  (let [fit-content-calls (atom 0)
        chart #js {:timeScale (fn []
                                #js {:fitContent (fn []
                                                   (swap! fit-content-calls inc))})}]
    (chart-interop/fit-content! chart)
    (is (= 1 @fit-content-calls))))

(deftest baseline-wrapper-guards-contract-checks-for-nil-and-non-nil-chart-test
  (let [assert-calls (atom [])
        sync-calls (atom [])
        clear-calls (atom [])
        chart-obj #js {:chart #js {}
                       :mainSeries #js {}}]
    (with-redefs [chart-contracts/assert-chart-handle! (fn [value context]
                                                         (swap! assert-calls conj {:value value :context context})
                                                         value)
                  baseline/sync-baseline-base-value-subscription! (fn [value chart-type]
                                                                    (swap! sync-calls conj [value chart-type])
                                                                    :synced)
                  baseline/clear-baseline-base-value-subscription! (fn [value]
                                                                     (swap! clear-calls conj value)
                                                                     :cleared)]
      (is (= :synced (chart-interop/sync-baseline-base-value-subscription! nil :line)))
      (is (= :cleared (chart-interop/clear-baseline-base-value-subscription! nil)))
      (is (= :synced (chart-interop/sync-baseline-base-value-subscription! chart-obj :baseline)))
      (is (= :cleared (chart-interop/clear-baseline-base-value-subscription! chart-obj))))
    (is (= 2 (count @sync-calls)))
    (is (= nil (ffirst @sync-calls)))
    (is (= 2 (count @clear-calls)))
    (is (= #{:chart-interop/sync-baseline :chart-interop/clear-baseline}
           (set (map (comp :boundary :context) @assert-calls))))))

(deftest indicator-wrapper-functions-delegate-to-indicator-interop-test
  (let [add-calls (atom [])
        set-calls (atom [])
        chart #js {:id "chart"}
        series* #js {:id "series"}
        series-def {:series-type :line :data [{:time 1 :value 10}]}
        data [{:time 1 :value 10}
              {:time 2}]]
    (with-redefs [indicator-interop/add-indicator-series! (fn [chart* series-def* pane-index]
                                                             (swap! add-calls conj [chart* series-def* pane-index])
                                                             series*)
                  indicator-interop/set-indicator-data! (fn [series value]
                                                          (swap! set-calls conj [series value])
                                                          :ok)]
      (is (identical? series* (chart-interop/add-indicator-series! chart series-def 2)))
      (is (= :ok (chart-interop/set-indicator-data! series* data))))
    (is (= [[chart series-def 2]] @add-calls))
    (is (= [[series* data]] @set-calls))))

(deftest volume-indicator-overlay-wrapper-delegates-and-guards-volume-contract-test
  (let [assert-calls (atom [])
        sync-calls (atom [])
        clear-calls (atom 0)
        chart-with-volume #js {:chart #js {}
                               :mainSeries #js {}
                               :volumeSeries #js {}}
        chart-without-volume #js {:chart #js {}
                                  :mainSeries #js {}}
        container (fake-dom/make-fake-element "div")
        candles [{:time 1 :open 10 :high 12 :low 9 :close 11 :volume 55}]]
    (with-redefs [chart-contracts/assert-candles! (fn
                                                    ([value context]
                                                     (swap! assert-calls conj {:value value
                                                                               :context context
                                                                               :opts nil})
                                                     value)
                                                    ([value context opts]
                                                     (swap! assert-calls conj {:value value
                                                                               :context context
                                                                               :opts opts})
                                                     value))
                  volume-indicator-overlay/sync-volume-indicator-overlay! (fn
                                                                            ([chart* container* candles*]
                                                                             (swap! sync-calls conj [chart* container* candles* nil])
                                                                             :synced)
                                                                            ([chart* container* candles* opts]
                                                                             (swap! sync-calls conj [chart* container* candles* opts])
                                                                             :synced))
                  volume-indicator-overlay/clear-volume-indicator-overlay! (fn [_]
                                                                              (swap! clear-calls inc)
                                                                              :cleared)]
      (is (= :synced
             (chart-interop/sync-volume-indicator-overlay! chart-with-volume
                                                           container
                                                           candles
                                                           {:on-remove (fn [] nil)})))
      (is (= :synced
             (chart-interop/sync-volume-indicator-overlay! chart-without-volume
                                                           container
                                                           candles)))
      (is (= :cleared
             (chart-interop/clear-volume-indicator-overlay! chart-with-volume))))
    (is (= 1 (count @assert-calls)))
    (is (= {:require-volume? true}
           (:opts (first @assert-calls))))
    (is (= 2 (count @sync-calls)))
    (is (= 1 @clear-calls))))

(deftest chart-navigation-overlay-wrapper-delegates-to-overlay-module-test
  (let [sync-calls (atom [])
        clear-calls (atom [])
        chart-obj #js {:chart #js {}
                       :mainSeries #js {}}
        container (fake-dom/make-fake-element "div")
        candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5 :volume 42}]
        opts {:on-interaction (fn [] nil)}]
    (with-redefs [chart-navigation-overlay/sync-chart-navigation-overlay! (fn
                                                                            ([chart* container* candles*]
                                                                             (swap! sync-calls conj [chart* container* candles* nil])
                                                                             :synced)
                                                                            ([chart* container* candles* opts*]
                                                                             (swap! sync-calls conj [chart* container* candles* opts*])
                                                                             :synced))
                  chart-navigation-overlay/clear-chart-navigation-overlay! (fn [chart*]
                                                                             (swap! clear-calls conj chart*)
                                                                             :cleared)]
      (is (= :synced
             (chart-interop/sync-chart-navigation-overlay! chart-obj container candles opts)))
      (is (= :synced
             (chart-interop/sync-chart-navigation-overlay! chart-obj container candles)))
      (is (= :cleared
             (chart-interop/clear-chart-navigation-overlay! chart-obj))))
    (is (= 2 (count @sync-calls)))
    (is (= [chart-obj] @clear-calls))))

(deftest position-overlay-wrapper-delegates-and-guards-chart-contract-test
  (let [assert-calls (atom [])
        sync-calls (atom [])
        clear-calls (atom [])
        chart-handle #js {:chart #js {}
                          :mainSeries #js {}}
        container (fake-dom/make-fake-element "div")
        overlay {:entry-price 100
                 :unrealized-pnl 12.5}]
    (with-redefs [chart-contracts/assert-chart-handle! (fn [value context]
                                                         (swap! assert-calls conj {:value value
                                                                                   :context context})
                                                         value)
                  position-overlays/sync-position-overlays! (fn
                                                              ([chart-obj* container* overlay*]
                                                               (swap! sync-calls conj [chart-obj* container* overlay* nil])
                                                               :synced)
                                                              ([chart-obj* container* overlay* opts]
                                                               (swap! sync-calls conj [chart-obj* container* overlay* opts])
                                                               :synced))
                  position-overlays/clear-position-overlays! (fn [chart-obj*]
                                                               (swap! clear-calls conj chart-obj*)
                                                               :cleared)]
      (is (= :synced
             (chart-interop/sync-position-overlays! chart-handle
                                                    container
                                                    overlay
                                                    {:format-price str})))
      (is (= :synced
             (chart-interop/sync-position-overlays! nil container overlay)))
      (is (= :cleared
             (chart-interop/clear-position-overlays! chart-handle)))
      (is (= :cleared
             (chart-interop/clear-position-overlays! nil))))
    (is (= 2 (count @sync-calls)))
    (is (= 2 (count @clear-calls)))
    (is (= #{:chart-interop/sync-position-overlays
             :chart-interop/clear-position-overlays}
           (set (map (comp :boundary :context) @assert-calls))))))
