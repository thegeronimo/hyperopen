(ns hyperopen.views.trading-chart.utils.chart-interop-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.schema.chart-interop-contracts :as chart-contracts]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]))

(defn- make-fake-element [tag]
  (let [children (array)
        element #js {:tagName tag
                     :style #js {}
                     :children children
                     :parentNode nil
                     :textContent ""}]
    (set! (.-appendChild element)
          (fn [child]
            (.push children child)
            (set! (.-parentNode child) element)
            child))
    (set! (.-removeChild element)
          (fn [child]
            (set! (.-parentNode child) nil)
            child))
    element))

(defn- make-fake-document []
  #js {:createElement (fn [tag]
                        (make-fake-element tag))})

(defn- collect-text-content [node]
  (let [own (when-let [text (.-textContent node)]
              (when (and (string? text) (seq text))
                [text]))
        children (or (some-> node .-children array-seq) [])]
    (into (vec own)
          (mapcat collect-text-content children))))

(deftest apply-persisted-visible-range-applies-stored-time-range-test
  (let [requested-key (atom nil)
        applied-range (atom nil)
        time-scale #js {:setVisibleRange (fn [range]
                                           (reset! applied-range (js->clj range :keywordize-keys true)))}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (reset! requested-key key)
                                               "{\"kind\":\"time\",\"from\":10,\"to\":20}")]
      (is (= true (chart-interop/apply-persisted-visible-range! chart :1d)))
      (is (= "chart-visible-time-range:1d" @requested-key))
      (is (= {:from 10 :to 20} @applied-range)))))

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

(deftest apply-persisted-visible-range-ignores-invalid-storage-test
  (let [applied? (atom false)
        time-scale #js {:setVisibleRange (fn [_] (reset! applied? true))}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-get (fn [_] "not-json")]
      (is (= false (chart-interop/apply-persisted-visible-range! chart :1d)))
      (is (false? @applied?)))))

(deftest subscribe-visible-range-persistence-persists-time-range-and-cleans-up-test
  (let [handler* (atom nil)
        unsubscribe-called? (atom false)
        writes (atom [])
        time-scale #js {:getVisibleRange (fn [] #js {:from 31 :to 45})
                        :subscribeVisibleTimeRangeChange (fn [handler]
                                                           (reset! handler* handler))
                        :unsubscribeVisibleTimeRangeChange (fn [handler]
                                                             (when (identical? handler @handler*)
                                                               (reset! unsubscribe-called? true)))}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-set! (fn [key value]
                                                (swap! writes conj [key value]))
                  platform/set-timeout! (fn [f _ms]
                                          (f)
                                          :timeout-id)
                  platform/clear-timeout! (fn [_] nil)]
      (let [cleanup (chart-interop/subscribe-visible-range-persistence! chart :1h)]
        (is (fn? @handler*))
        (@handler* #js {})
        (is (= 1 (count @writes)))
        (let [[key raw] (first @writes)
              payload (js->clj (js/JSON.parse raw) :keywordize-keys true)]
          (is (= "chart-visible-time-range:1h" key))
          (is (= {:kind "time" :from 31 :to 45} payload)))
        (cleanup)
        (is @unsubscribe-called?)))))

(deftest subscribe-visible-range-persistence-falls-back-to-logical-range-test
  (let [handler* (atom nil)
        writes (atom [])
        time-scale #js {:getVisibleLogicalRange (fn [] #js {:from 5 :to 8})
                        :subscribeVisibleLogicalRangeChange (fn [handler]
                                                              (reset! handler* handler))
                        :unsubscribeVisibleLogicalRangeChange (fn [_] nil)}
        chart #js {:timeScale (fn [] time-scale)}]
    (with-redefs [platform/local-storage-set! (fn [key value]
                                                (swap! writes conj [key value]))
                  platform/set-timeout! (fn [f _ms]
                                          (f)
                                          :timeout-id)
                  platform/clear-timeout! (fn [_] nil)]
      (let [cleanup (chart-interop/subscribe-visible-range-persistence! chart :5m)]
        (is (fn? @handler*))
        (@handler* #js {})
        (let [[key raw] (first @writes)
              payload (js->clj (js/JSON.parse raw) :keywordize-keys true)]
          (is (= "chart-visible-time-range:5m" key))
          (is (= {:kind "logical" :from 5 :to 8} payload)))
        (cleanup)))))

(deftest transform-data-for-heikin-ashi-computes-deterministic-candles-test
  (let [raw-candles [{:time 1 :open 10 :high 15 :low 8 :close 12}
                     {:time 2 :open 12 :high 16 :low 11 :close 14}]
        transformed (chart-interop/transform-data-for-heikin-ashi raw-candles)]
    (is (= 2 (count transformed)))
    (let [first-candle (first transformed)
          second-candle (second transformed)]
      (is (= {:time 1
              :open 11
              :high 15
              :low 8
              :close 11.25}
             first-candle))
      (is (= 11.125 (:open second-candle)))
      (is (= 13.25 (:close second-candle)))
      (is (= 16 (:high second-candle)))
      (is (= 11 (:low second-candle))))))

(deftest transform-data-for-columns-adds-directional-color-test
  (let [raw-candles [{:time 1 :open 10 :high 11 :low 9 :close 12}
                     {:time 2 :open 12 :high 13 :low 11 :close 10}]
        transformed (vec (chart-interop/transform-data-for-columns raw-candles))]
    (is (= [{:time 1 :value 12 :color "#26a69a"}
            {:time 2 :value 10 :color "#ef5350"}]
           transformed))))

(deftest transform-data-for-high-low-builds-floating-range-bars-test
  (let [raw-candles [{:time 1 :open 10 :high 16 :low 8 :close 12}
                     {:time 2 :open 12 :high 18 :low 10 :close 16}]
        transformed (vec (chart-interop/transform-data-for-high-low raw-candles))]
    (is (= [{:time 1 :open 8 :high 16 :low 8 :close 16}
            {:time 2 :open 10 :high 18 :low 10 :close 18}]
           transformed))))

(deftest set-series-data-applies-hlc-area-transform-test
  (let [applied-options (atom nil)
        applied-data (atom nil)
        series #js {:applyOptions (fn [opts]
                                    (reset! applied-options (js->clj opts :keywordize-keys true)))
                    :setData (fn [data]
                               (reset! applied-data (js->clj data :keywordize-keys true)))}
        raw-candles [{:time 1 :open 10 :high 16 :low 8 :close 12}
                     {:time 2 :open 12 :high 18 :low 10 :close 16}]]
    (chart-interop/set-series-data! series raw-candles :hlc-area)
    (is (= [{:time 1 :value 12}
            {:time 2 :value 14.666666666666666}]
           @applied-data))
    (is (= "price" (get-in @applied-options [:priceFormat :type])))))

(deftest set-series-data-baseline-applies-midpoint-base-level-test
  (let [applied-options (atom nil)
        applied-data (atom nil)
        series #js {:applyOptions (fn [opts]
                                    (reset! applied-options (js->clj opts :keywordize-keys true)))
                    :setData (fn [data]
                               (reset! applied-data (js->clj data :keywordize-keys true)))}
        raw-candles [{:time 1 :open 10 :high 14 :low 9 :close 12}
                     {:time 2 :open 12 :high 16 :low 11 :close 15}
                     {:time 3 :open 15 :high 18 :low 14 :close 18}]]
    (chart-interop/set-series-data! series raw-candles :baseline)
    (is (= [{:time 1 :value 12}
            {:time 2 :value 15}
            {:time 3 :value 18}]
           @applied-data))
    (is (= "price" (get-in @applied-options [:priceFormat :type])))
    (is (= "price" (get-in @applied-options [:baseValue :type])))
    (is (= 15 (get-in @applied-options [:baseValue :price])))))

(deftest set-series-data-prefers-metadata-price-decimals-test
  (let [applied-options (atom nil)
        series #js {:applyOptions (fn [opts]
                                    (reset! applied-options (js->clj opts :keywordize-keys true)))
                    :setData (fn [_] nil)}
        raw-candles [{:time 1 :open 0.01 :high 0.02 :low 0.009 :close 0.015}
                     {:time 2 :open 0.015 :high 0.025 :low 0.014 :close 0.02}]]
    (chart-interop/set-series-data! series raw-candles :line {:price-decimals 5})
    (is (= 5 (get-in @applied-options [:priceFormat :precision])))
    (is (= 0.00001 (get-in @applied-options [:priceFormat :minMove])))))

(deftest baseline-subscription-refreshes-base-value-on-visible-range-change-test
  (let [visible-range* (atom {:from 10 :to 30})
        subscribed-handler* (atom nil)
        unsubscribed?* (atom false)
        applied-options* (atom [])
        time-scale #js {:subscribeVisibleLogicalRangeChange (fn [handler]
                                                              (reset! subscribed-handler* handler))
                        :unsubscribeVisibleLogicalRangeChange (fn [handler]
                                                                (when (identical? handler @subscribed-handler*)
                                                                  (reset! unsubscribed?* true)))}
        price-scale #js {:getVisibleRange (fn []
                                            (clj->js @visible-range*))}
        main-series #js {:priceScale (fn [] price-scale)
                         :applyOptions (fn [opts]
                                         (swap! applied-options* conj (js->clj opts :keywordize-keys true)))}
        chart #js {:timeScale (fn [] time-scale)}
        chart-obj #js {:chart chart
                       :mainSeries main-series}]
    (chart-interop/sync-baseline-base-value-subscription! chart-obj :baseline)
    (is (= 20 (get-in (first @applied-options*) [:baseValue :price])))
    (reset! visible-range* {:from 30 :to 50})
    (@subscribed-handler* #js {})
    (is (= 40 (get-in (last @applied-options*) [:baseValue :price])))
    (chart-interop/sync-baseline-base-value-subscription! chart-obj :line)
    (is @unsubscribed?*)))

(deftest set-series-data-unknown-chart-type-falls-back-to-candlestick-test
  (let [applied-data (atom nil)
        series #js {:applyOptions (fn [_] nil)
                    :setData (fn [data]
                               (reset! applied-data (js->clj data :keywordize-keys true)))}
        raw-candles [{:time 1 :open 10 :high 12 :low 9 :close 11}
                     {:time 2 :open 11 :high 13 :low 10 :close 12}]]
    (chart-interop/set-series-data! series raw-candles :unknown-chart-type)
    (is (= raw-candles @applied-data))))

(deftest set-series-data-accepts-sequential-candle-collections-test
  (let [applied-data (atom nil)
        series #js {:applyOptions (fn [_] nil)
                    :setData (fn [data]
                               (reset! applied-data (js->clj data :keywordize-keys true)))}
        vector-candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5}
                        {:time 2 :open 10.5 :high 12 :low 10 :close 11.5}]
        list-candles (apply list vector-candles)
        lazy-seq-candles (map identity vector-candles)]
    (chart-interop/set-series-data! series list-candles :candlestick)
    (is (= vector-candles @applied-data))
    (chart-interop/set-series-data! series lazy-seq-candles :candlestick)
    (is (= vector-candles @applied-data))))

(deftest set-main-series-markers-reuses-plugin-for-same-series-and-recreates-for-new-series-test
  (let [create-calls (atom 0)
        plugin-updates (atom [])
        series-a #js {:id "a"}
        series-b #js {:id "b"}
        chart-obj #js {:chart #js {}
                       :mainSeries series-a}
        create-markers (fn [series _initial]
                         (swap! create-calls inc)
                         (let [plugin-id @create-calls]
                           #js {:setMarkers (fn [markers]
                                              (swap! plugin-updates conj {:plugin-id plugin-id
                                                                          :series-id (.-id series)
                                                                          :markers (js->clj markers :keywordize-keys true)}))}))]
    (chart-interop/set-main-series-markers! chart-obj [{:time 1 :position "aboveBar"}]
                                            {:create-markers create-markers})
    (chart-interop/set-main-series-markers! chart-obj [{:time 2 :position "belowBar"}]
                                            {:create-markers create-markers})
    (set! (.-mainSeries ^js chart-obj) series-b)
    (chart-interop/set-main-series-markers! chart-obj [{:time 3 :position "aboveBar"}]
                                            {:create-markers create-markers})
    (is (= 2 @create-calls))
    (is (= [1 1 2] (mapv :plugin-id @plugin-updates)))
    (is (= ["a" "a" "b"] (mapv :series-id @plugin-updates)))))

(deftest create-legend-supports-business-day-crosshair-time-with-injected-document-test
  (let [document (make-fake-document)
        container (make-fake-element "div")
        crosshair-handler* (atom nil)
        chart #js {:subscribeCrosshairMove (fn [handler]
                                             (reset! crosshair-handler* handler))
                   :unsubscribeCrosshairMove (fn [_] nil)}
        legend-meta {:symbol "BTC"
                     :timeframe-label "1D"
                     :venue "TestVenue"
                     :candle-data [{:time {:year 2026 :month 2 :day 15}
                                    :open 99 :high 101 :low 98 :close 100}
                                   {:time {:year 2026 :month 2 :day 16}
                                    :open 100 :high 103 :low 99 :close 102}]}
        legend-control (chart-interop/create-legend!
                        container
                        chart
                        legend-meta
                        {:document document
                         :format-price (fn [price] (str "P" price))
                         :format-delta (fn [delta] (str "D" delta))
                         :format-pct (fn [pct] (str "Q" (.toFixed pct 1)))})]
    (is (fn? @crosshair-handler*))
    (@crosshair-handler* #js {:time #js {:year 2026 :month 2 :day 16}})
    (let [text (str/join " " (collect-text-content (aget (.-children container) 0)))]
      (is (str/includes? text "BTC · 1D · TestVenue"))
      (is (str/includes? text "P100"))
      (is (str/includes? text "P103"))
      (is (str/includes? text "P99"))
      (is (str/includes? text "P102"))
      (is (str/includes? text "D2"))
      (is (str/includes? text "Q2.0")))
    (.destroy ^js legend-control)))

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

(deftest set-volume-data-applies-transformed-volume-points-test
  (let [applied-data (atom nil)
        volume-series #js {:setData (fn [data]
                                      (reset! applied-data (js->clj data :keywordize-keys true)))}
        candles [{:time 1 :open 10 :high 12 :low 9 :close 11 :volume 50}
                 {:time 2 :open 11 :high 12 :low 8 :close 9 :volume 35}]]
    (chart-interop/set-volume-data! volume-series candles)
    (is (= [{:time 1 :value 50 :color "#26a69a"}
            {:time 2 :value 35 :color "#ef5350"}]
           @applied-data))))

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
        document (make-fake-document)
        container (make-fake-element "div")
        crosshair-handler* (atom nil)
        chart #js {:subscribeCrosshairMove (fn [handler]
                                             (reset! crosshair-handler* handler))
                   :unsubscribeCrosshairMove (fn [_] nil)}
        legend-meta {:candle-data [{:time 1 :open 10 :high 12 :low 9 :close 11}]}]
    (aset js/globalThis "document" document)
    (try
      (let [legend-control (chart-interop/create-legend! container chart legend-meta)
            text (str/join " " (collect-text-content (aget (.-children container) 0)))]
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
