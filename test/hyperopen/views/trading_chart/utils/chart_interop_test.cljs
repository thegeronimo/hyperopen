(ns hyperopen.views.trading-chart.utils.chart-interop-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.chart-interop-contracts :as chart-contracts]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]
            [hyperopen.views.trading-chart.utils.chart-interop.volume-indicator-overlay :as volume-indicator-overlay]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(deftest apply-persisted-visible-range-supports-injected-storage-get-test
  (let [requested-key (atom nil)
        applied-range (atom nil)
        time-scale #js {:setVisibleRange (fn [range]
                                           (reset! applied-range (js->clj range :keywordize-keys true)))}
        chart #js {:timeScale (fn [] time-scale)}]
    (is (= true (chart-interop/apply-persisted-visible-range!
                 chart
                 :4h
                 {:storage-get (fn [key]
                                 (reset! requested-key key)
                                 "{\"kind\":\"time\",\"from\":3,\"to\":9}")})))
    (is (= "chart-visible-time-range:4h" @requested-key))
    (is (= {:from 3 :to 9} @applied-range))))

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

(deftest subscribe-visible-range-persistence-supports-injected-storage-set-test
  (let [handler* (atom nil)
        writes (atom [])
        storage-set! (fn [key value]
                       (swap! writes conj [key value]))
        time-scale #js {:subscribeVisibleTimeRangeChange (fn [handler]
                                                           (reset! handler* handler))
                        :unsubscribeVisibleTimeRangeChange (fn [_] nil)}
        chart #js {:timeScale (fn [] time-scale)}
        cleanup (chart-interop/subscribe-visible-range-persistence! chart :15m
                                                                    {:storage-set! storage-set!})]
    (@handler* #js {:from 11 :to 19})
    (let [[key raw] (first @writes)
          payload (js->clj (js/JSON.parse raw) :keywordize-keys true)]
      (is (= "chart-visible-time-range:15m" key))
      (is (= {:kind "time" :from 11 :to 19} payload)))
    (cleanup)))

(deftest create-legend-three-arity-uses-global-document-test
  (let [original-document (aget js/globalThis "document")
        document (fake-dom/make-fake-document)
        container (fake-dom/make-fake-element "div")
        crosshair-handler* (atom nil)
        chart #js {:subscribeCrosshairMove (fn [handler]
                                             (reset! crosshair-handler* handler))
                   :unsubscribeCrosshairMove (fn [_] nil)}
        legend-meta {:candle-data [{:time 1 :open 10 :high 12 :low 9 :close 11}]}]
    (aset js/globalThis "document" document)
    (try
      (let [legend-control (chart-interop/create-legend! container chart legend-meta)
            text (str/join " " (fake-dom/collect-text-content (aget (.-children container) 0)))]
        (is (fn? @crosshair-handler*))
        (is (str/includes? text "— · — · —"))
        (.destroy ^js legend-control))
      (finally
        (aset js/globalThis "document" original-document)))))

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

(deftest create-chart-with-volume-and-series-skips-volume-pane-when-hidden-test
  (let [chart #js {:addSeries (fn [& _]
                                (throw (js/Error. "volume series should not be created when hidden")))}
        set-main-data-calls (atom 0)
        main-series #js {:id "main"
                         :applyOptions (fn [_] nil)
                         :setData (fn [_]
                                    (swap! set-main-data-calls inc))}
        set-volume-data-calls (atom 0)
        fit-content-calls (atom 0)
        candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5 :volume 100}]
        chart-obj (with-redefs [chart-contracts/assert-candles! (fn
                                                                   ([value _context]
                                                                    value)
                                                                   ([value _context _opts]
                                                                    value))
                                chart-interop/create-chart! (fn [_]
                                                              chart)
                                chart-interop/add-series! (fn [_ _]
                                                            main-series)
                                chart-interop/set-volume-data! (fn [_ _]
                                                                 (swap! set-volume-data-calls inc))
                                chart-interop/fit-content! (fn [_]
                                                             (swap! fit-content-calls inc))]
                    (chart-interop/create-chart-with-volume-and-series!
                     (fake-dom/make-fake-element "div")
                     :candlestick
                     candles
                     {:series-options {:price-decimals 2}
                      :volume-visible? false}))]
    (is (identical? main-series (.-mainSeries ^js chart-obj)))
    (is (nil? (.-volumeSeries ^js chart-obj)))
    (is (nil? (.-volumePaneIndex ^js chart-obj)))
    (is (= 1 @set-main-data-calls))
    (is (zero? @set-volume-data-calls))
    (is (= 1 @fit-content-calls))))

(deftest create-chart-with-indicators-skips-volume-pane-when-hidden-test
  (let [chart #js {:addSeries (fn [& _]
                                (throw (js/Error. "volume series should not be created when hidden")))}
        set-main-data-calls (atom 0)
        main-series #js {:id "main"
                         :applyOptions (fn [_] nil)
                         :setData (fn [_]
                                    (swap! set-main-data-calls inc))}
        set-volume-data-calls (atom 0)
        fit-content-calls (atom 0)
        candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5 :volume 100}]
        indicators []
        chart-obj (with-redefs [chart-contracts/assert-candles! (fn
                                                                   ([value _context]
                                                                    value)
                                                                   ([value _context _opts]
                                                                    value))
                                chart-contracts/assert-indicators! (fn [value _context]
                                                                      value)
                                chart-interop/create-chart! (fn [_]
                                                              chart)
                                chart-interop/add-series! (fn [_ _]
                                                            main-series)
                                chart-interop/set-volume-data! (fn [_ _]
                                                                 (swap! set-volume-data-calls inc))
                                chart-interop/fit-content! (fn [_]
                                                             (swap! fit-content-calls inc))]
                    (chart-interop/create-chart-with-indicators!
                     (fake-dom/make-fake-element "div")
                     :candlestick
                     candles
                     indicators
                     {:series-options {:price-decimals 2}
                      :volume-visible? false}))]
    (is (identical? main-series (.-mainSeries ^js chart-obj)))
    (is (nil? (.-volumeSeries ^js chart-obj)))
    (is (nil? (.-volumePaneIndex ^js chart-obj)))
    (is (zero? (alength (.-indicatorSeries ^js chart-obj))))
    (is (= 1 @set-main-data-calls))
    (is (zero? @set-volume-data-calls))
    (is (= 1 @fit-content-calls))))

(deftest set-main-series-markers-two-arity-allows-nil-chart-test
  (is (nil? (chart-interop/set-main-series-markers! nil [{:time 1 :position "aboveBar"}]))))

(deftest legacy-candlestick-wrappers-delegate-test
  (let [created-chart #js {:id "chart"}
        applied-data (atom nil)
        series* #js {:setData (fn [data]
                                (reset! applied-data (js->clj data :keywordize-keys true)))}
        candles [{:time 1 :open 10 :high 12 :low 9 :close 11}]]
    (with-redefs [chart-interop/create-chart! (fn [_] created-chart)]
      (is (identical? created-chart (chart-interop/create-candlestick-chart! #js {:id "container"}))))
    (chart-interop/set-candlestick-data! series* candles)
    (is (= candles @applied-data))))

