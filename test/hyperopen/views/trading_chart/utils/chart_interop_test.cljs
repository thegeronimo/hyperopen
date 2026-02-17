(ns hyperopen.views.trading-chart.utils.chart-interop-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.schema.chart-interop-contracts :as chart-contracts]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.baseline :as baseline]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.legend :as legend]
            [hyperopen.views.trading-chart.utils.chart-interop.markers :as markers]
            [hyperopen.views.trading-chart.utils.chart-interop.price-format :as price-format]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]
            [hyperopen.views.trading-chart.utils.chart-interop.transforms :as transforms]))

(defn- make-fake-element [tag]
  (let [children (array)
        listeners (js-obj)
        element #js {:tagName tag
                     :style #js {}
                     :children children
                     :listeners listeners
                     :parentNode nil
                     :firstChild nil
                     :className ""
                     :innerHTML ""
                     :textContent ""}]
    (letfn [(refresh-first-child! []
              (set! (.-firstChild element)
                    (when (pos? (alength children))
                      (aget children 0))))]
      (set! (.-appendChild element)
            (fn [child]
              (.push children child)
              (set! (.-parentNode child) element)
              (refresh-first-child!)
              child))
      (set! (.-removeChild element)
            (fn [child]
              (let [idx (.indexOf children child)]
                (when (>= idx 0)
                  (.splice children idx 1)))
              (set! (.-parentNode child) nil)
              (refresh-first-child!)
              child))
      (set! (.-setAttribute element)
            (fn [attr value]
              (aset element attr value)))
      (set! (.-addEventListener element)
            (fn [event-name handler]
              (aset listeners event-name handler)))
      (set! (.-removeEventListener element)
            (fn [event-name _handler]
              (js-delete listeners event-name)))
      (set! (.-dispatchEvent element)
            (fn [event-name payload]
              (when-let [handler (aget listeners event-name)]
                (handler payload)))))
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

(defn- find-dom-node [node pred]
  (when node
    (let [children (or (some-> node .-children array-seq) [])]
      (or (when (pred node) node)
          (some #(find-dom-node % pred) children)))))

(defn- click-dom-node! [node]
  (when node
    (let [listeners (.-listeners ^js node)
          handler (when listeners
                    (aget listeners "click"))]
      (when (fn? handler)
        (handler #js {:preventDefault (fn [] nil)
                      :stopPropagation (fn [] nil)})))))

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

(deftest series-module-add-functions-call-chart-add-series-test
  (let [calls (atom [])
        chart #js {:addSeries (fn [kind options & [pane-index]]
                                (swap! calls conj {:kind kind
                                                   :pane-index pane-index
                                                   :options (js->clj options :keywordize-keys true)})
                                #js {:kind kind :paneIndex pane-index})}]
    (series/add-area-series! chart)
    (series/add-bar-series! chart)
    (series/add-high-low-series! chart)
    (series/add-baseline-series! chart)
    (series/add-candlestick-series! chart)
    (series/add-hollow-candles-series! chart)
    (series/add-heikin-ashi-series! chart)
    (series/add-histogram-series! chart)
    (series/add-columns-series! chart)
    (series/add-line-series! chart)
    (series/add-line-with-markers-series! chart)
    (series/add-step-line-series! chart)
    (series/add-hlc-area-series! chart)
    (series/add-volume-series! chart)
    (is (= 14 (count @calls)))
    (is (= "#2962FF" (get-in (nth @calls 0) [:options :lineColor])))
    (is (= "#ef5350" (get-in (nth @calls 1) [:options :downColor])))
    (is (= false (get-in (nth @calls 2) [:options :wickVisible])))
    (is (= "rgba(239, 83, 80, 0.28)" (get-in (nth @calls 3) [:options :bottomFillColor2])))
    (is (= false (get-in (nth @calls 4) [:options :borderVisible])))
    (is (= "rgba(0, 0, 0, 0)" (get-in (nth @calls 5) [:options :upColor])))
    (is (= "#26a69a" (get-in (nth @calls 6) [:options :wickUpColor])))
    (is (= "#26a69a" (get-in (nth @calls 7) [:options :color])))
    (is (= "#26a69a" (get-in (nth @calls 8) [:options :color])))
    (is (= "#2962FF" (get-in (nth @calls 9) [:options :color])))
    (is (= true (get-in (nth @calls 10) [:options :pointMarkersVisible])))
    (is (= 1 (get-in (nth @calls 11) [:options :lineType])))
    (is (= "rgba(41, 98, 255, 0.28)" (get-in (nth @calls 12) [:options :bottomColor])))
    (is (= "volume" (get-in (nth @calls 13) [:options :priceFormat :type])))
    (is (= "" (get-in (nth @calls 13) [:options :priceScaleId])))))

(deftest series-module-resolve-transform-and-extract-cover-registry-branches-test
  (let [candles [{:time 1 :open 10 :high 12 :low 9 :close 11}
                 {:time 2 :open 11 :high 13 :low 10 :close 12}]
        add-calls (atom [])
        chart #js {:addSeries (fn [_kind options & _]
                                (swap! add-calls conj (js->clj options :keywordize-keys true))
                                #js {})}
        columns (vec (series/transform-main-series-data candles :histogram))
        columns-direct (vec (series/transform-main-series-data candles :columns))
        fallback (vec (series/transform-main-series-data candles :unknown))
        line-prices (vec (series/extract-series-prices [{:time 1 :value 1.5}
                                                        {:time 2 :value 2.5}]
                                                       :line))
        ohlc-prices (vec (series/extract-series-prices candles :unknown))]
    (is (map? (series/resolve-chart-type :line)))
    (is (map? (series/resolve-chart-type :unknown)))
    (is (= columns-direct columns))
    (is (= [{:time 1 :value 11 :color "#26a69a"}
            {:time 2 :value 12 :color "#26a69a"}]
           columns))
    (is (= candles fallback))
    (is (= [1.5 2.5] line-prices))
    (is (= [10 12 9 11 11 13 10 12] ohlc-prices))
    (series/add-series! chart :unknown)
    (let [opts (first @add-calls)]
      (is (= false (:borderVisible opts)))
      (is (= "#26a69a" (:wickUpColor opts))))))

(deftest indicator-interop-add-series-set-data-and-pane-allocation-test
  (let [calls (atom [])
        chart #js {:addSeries (fn [kind options pane-index]
                                (swap! calls conj {:kind kind
                                                   :pane-index pane-index
                                                   :options (js->clj options :keywordize-keys true)})
                                #js {:kind kind :paneIndex pane-index})}
        set-data* (atom nil)
        indicator-series #js {:setData (fn [data]
                                         (reset! set-data* (js->clj data :keywordize-keys true)))}
        allocation (indicator-interop/indicator-pane-allocation
                    [{:id :overlay :pane :overlay :series [{:id :ov-1} {:id :ov-2}]}
                     {:id :separate-a :pane :separate :series [{:id :sep-a-1}]}
                     {:id :separate-b :pane :separate :series [{:id :sep-b-1} {:id :sep-b-2}]}
                     {:id :overlay-empty :pane :overlay :series []}])
        empty-allocation (indicator-interop/indicator-pane-allocation [])]
    (indicator-interop/add-indicator-series! chart {:series-type :line
                                                    :color "#334155"
                                                    :line-width 4}
                                   0)
    (indicator-interop/add-indicator-series! chart {:series-type :histogram} 2)
    (indicator-interop/add-indicator-series! chart {:series-type :unknown} 3)
    (indicator-interop/set-indicator-data! indicator-series [{:time 1 :value 10}
                                                              {:time 2}])
    (is (= 3 (count @calls)))
    (is (= {:color "#334155" :lineWidth 4}
           (:options (first @calls))))
    (is (= {:color "#10b981" :priceFormat {:type "price"} :base 0}
           (:options (second @calls))))
    (is (= {:color "#38bdf8" :lineWidth 2}
           (:options (nth @calls 2))))
    (is (= [0 2 3] (mapv :pane-index @calls)))
    (is (= [{:time 1 :value 10}
            {:time 2}]
           @set-data*))
    (is (= 3 (:next-pane-index allocation)))
    (is (= [0 0 1 2 2]
           (mapv :pane-index (:assignments allocation))))
    (is (= {:next-pane-index 1 :assignments []} empty-allocation))))

(deftest infer-series-price-format-prefers-metadata-decimals-and-clamps-range-test
  (let [extract-called? (atom false)
        from-string (js->clj (price-format/infer-series-price-format
                              [{:time 1 :value 1}]
                              (fn [_]
                                (reset! extract-called? true)
                                [1 2 3])
                              {:price-decimals "5.9"})
                             :keywordize-keys true)
        from-negative (js->clj (price-format/infer-series-price-format
                                [{:time 1 :value 1}]
                                (fn [_] [1 2 3])
                                {:price-decimals -3})
                               :keywordize-keys true)
        from-large (js->clj (price-format/infer-series-price-format
                             [{:time 1 :value 1}]
                             (fn [_] [1 2 3])
                             {:price-decimals "99"})
                            :keywordize-keys true)]
    (is (false? @extract-called?))
    (is (= {:type "price" :precision 5 :minMove 0.00001}
           from-string))
    (is (= 0 (:precision from-negative)))
    (is (== 1 (:minMove from-negative)))
    (is (= 12 (:precision from-large)))
    (is (== 1e-12 (:minMove from-large)))))

(deftest infer-series-price-format-infers-from-prices-and-falls-back-to-default-test
  (let [captured (atom [])
        from-positive (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                4)]
                        (js->clj (price-format/infer-series-price-format
                                  [{:time 1}]
                                  (fn [_] [0 -5 "2.5" "bad" 10]))
                                 :keywordize-keys true))
        from-absolute (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                3)]
                        (js->clj (price-format/infer-series-price-format
                                  [{:time 1}]
                                  (fn [_] [-8 "-2.5"]))
                                 :keywordize-keys true))
        from-fallback (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                nil)]
                        (js->clj (price-format/infer-series-price-format
                                  []
                                  (fn [_] ["bad" nil]))
                                 :keywordize-keys true))]
    (is (= [2.5 2.5 nil] @captured))
    (is (= 4 (:precision from-positive)))
    (is (== 0.0001 (:minMove from-positive)))
    (is (= 3 (:precision from-absolute)))
    (is (== 0.001 (:minMove from-absolute)))
    (is (= 2 (:precision from-fallback)))
    (is (== 0.01 (:minMove from-fallback)))))

(deftest legend-create-throws-when-document-is-missing-test
  (let [original-document (aget js/globalThis "document")
        container (make-fake-element "div")
        chart #js {:subscribeCrosshairMove (fn [_] nil)
                   :unsubscribeCrosshairMove (fn [_] nil)}]
    (aset js/globalThis "document" nil)
    (try
      (let [error (try
                    (legend/create-legend! container chart {})
                    nil
                    (catch :default e e))]
        (is (some? error))
        (is (str/includes? (.-message error) "Legend rendering requires a DOM document.")))
      (finally
        (aset js/globalThis "document" original-document)))))

(deftest legend-supports-time-lookups-update-and-destroy-cleanup-test
  (let [document (make-fake-document)
        container (make-fake-element "div")
        _ (set! (.-position (.-style container)) "static")
        crosshair-handler* (atom nil)
        unsubscribed-handler* (atom nil)
        chart #js {:subscribeCrosshairMove (fn [handler]
                                             (reset! crosshair-handler* handler))
                   :unsubscribeCrosshairMove (fn [handler]
                                               (reset! unsubscribed-handler* handler))}
        legend-control (legend/create-legend!
                        container
                        chart
                        {:symbol "ETH"
                         :timeframe-label "4H"
                         :venue "Demo"
                         :candle-data [{:time "2026-02-15"
                                        :open 100 :high 105 :low 99 :close 100}
                                       {:time 1700000100
                                        :open 100 :high 102 :low 90 :close 95}]}
                        {:document document
                         :format-price (fn [price]
                                         (when (number? price)
                                           (str "$" price)))
                         :format-delta (fn [delta]
                                         (when (number? delta)
                                           (str "d" delta)))
                         :format-pct (fn [pct]
                                       (when (number? pct)
                                         (str "p" (.toFixed pct 1))))})]
    (is (fn? @crosshair-handler*))
    (is (= "relative" (.-position (.-style container))))
    (@crosshair-handler* #js {:time 1700000100})
    (let [legend-node (aget (.-children container) 0)
          values-row (aget (.-children legend-node) 1)
          delta-node (aget (.-children values-row) (dec (.-length (.-children values-row))))
          text (str/join " " (collect-text-content legend-node))]
      (is (str/includes? text "ETH · 4H · Demo"))
      (is (str/includes? text "$95"))
      (is (str/includes? text "d-5"))
      (is (str/includes? text "p-5.0"))
      (is (= "#ef4444" (.-color (.-style delta-node)))))
    (@crosshair-handler* #js {:time :no-match})
    (let [text (str/join " " (collect-text-content (aget (.-children container) 0)))]
      (is (str/includes? text "$95")))
    (.update ^js legend-control {:symbol "SOL"
                                 :timeframe-label "1H"
                                 :venue "Alt"})
    (let [text (str/join " " (collect-text-content (aget (.-children container) 0)))]
      (is (str/includes? text "SOL · 1H · Alt"))
      (is (str/includes? text "-- (--)")))
    (.destroy ^js legend-control)
    (is (identical? @crosshair-handler* @unsubscribed-handler*))
    (is (zero? (alength (.-children container))))))

(deftest markers-module-normalizes-marker-input-and-reuses-plugin-test
  (let [create-calls (atom 0)
        plugin-sets (atom [])
        main-series #js {:id "main"}
        chart-obj #js {:mainSeries main-series}
        create-markers (fn [series initial]
                         (swap! create-calls inc)
                         (is (= [] (js->clj initial)))
                         #js {:setMarkers (fn [markers]
                                            (swap! plugin-sets conj {:series-id (.-id series)
                                                                     :markers (js->clj markers :keywordize-keys true)}))})]
    (is (nil? (markers/set-main-series-markers! nil [{:time 1}] {:create-markers create-markers})))
    (markers/set-main-series-markers! chart-obj {:not "sequential"} {:create-markers create-markers})
    (markers/set-main-series-markers! chart-obj [{:time 1 :position "aboveBar"}] {:create-markers create-markers})
    (is (= 1 @create-calls))
    (is (= [] (:markers (first @plugin-sets))))
    (is (= [{:time 1 :position "aboveBar"}]
           (:markers (second @plugin-sets))))))

(deftest baseline-module-infer-base-value-handles-numeric-like-and-invalid-values-test
  (is (= 15 (baseline/infer-baseline-base-value [{:value "10"}
                                                 {:value 20}])))
  (is (nil? (baseline/infer-baseline-base-value [{:value "bad"}
                                                 {:value nil}]))))

(deftest baseline-module-sync-supports-time-range-subscriptions-and-cleanup-test
  (let [visible-range* (atom {:from 100 :to 120})
        subscribed-handler* (atom nil)
        unsubscribed?* (atom false)
        applied-options* (atom [])
        time-scale #js {:subscribeVisibleTimeRangeChange (fn [handler]
                                                           (reset! subscribed-handler* handler))
                        :unsubscribeVisibleTimeRangeChange (fn [handler]
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
    (baseline/sync-baseline-base-value-subscription! chart-obj :baseline)
    (is (= 110 (get-in (first @applied-options*) [:baseValue :price])))
    (reset! visible-range* {:from 120 :to 140})
    (@subscribed-handler* #js {})
    (is (= 130 (get-in (last @applied-options*) [:baseValue :price])))
    (baseline/clear-baseline-base-value-subscription! chart-obj)
    (is @unsubscribed?*)))

(deftest baseline-module-sync-without-chart-or-time-scale-is-safe-test
  (let [main-series #js {:priceScale (fn [] nil)
                         :applyOptions (fn [_] nil)}
        chart-obj #js {:chart #js {:timeScale (fn [] nil)}
                       :mainSeries main-series}
        baseline-result (baseline/sync-baseline-base-value-subscription! chart-obj :baseline)
        clear-result (baseline/sync-baseline-base-value-subscription! chart-obj :line)]
    (is (nil? (baseline/sync-baseline-base-value-subscription! nil :baseline)))
    (is (nil? (baseline/clear-baseline-base-value-subscription! nil)))
    (is (some? baseline-result))
    (is (true? clear-result))))

(deftest open-order-overlays-render-lines-and-inline-cancel-test
  (let [document (make-fake-document)
        container (make-fake-element "div")
        unsubscribes* (atom 0)
        subscribe-fn (fn [_] nil)
        unsubscribe-fn (fn [_]
                         (swap! unsubscribes* inc))
        time-scale #js {:subscribeVisibleTimeRangeChange subscribe-fn
                        :unsubscribeVisibleTimeRangeChange unsubscribe-fn
                        :subscribeVisibleLogicalRangeChange subscribe-fn
                        :unsubscribeVisibleLogicalRangeChange unsubscribe-fn
                        :subscribeSizeChange subscribe-fn
                        :unsubscribeSizeChange unsubscribe-fn}
        chart #js {:timeScale (fn [] time-scale)
                   :subscribeCrosshairMove subscribe-fn
                   :unsubscribeCrosshairMove unsubscribe-fn
                   :subscribeClick subscribe-fn
                   :unsubscribeClick unsubscribe-fn}
        main-series #js {:priceToCoordinate (fn [price]
                                              (* 2 price))
                         :subscribeDataChanged subscribe-fn
                         :unsubscribeDataChanged unsubscribe-fn}
        chart-obj #js {:chart chart
                       :mainSeries main-series}
        canceled-oids* (atom [])
        orders [{:coin "SOL"
                 :oid 11
                 :side "B"
                 :type "limit"
                 :sz "1.00"
                 :px "60.0"}
                {:coin "SOL"
                 :oid 12
                 :side "A"
                 :type "limit"
                 :sz "2.25"
                 :px "61.5"}]]
    (chart-interop/sync-open-order-overlays!
     chart-obj
     container
     orders
     {:document document
      :on-cancel-order (fn [order]
                         (swap! canceled-oids* conj (:oid order)))
      :format-price (fn [price _raw]
                      (str "P" price))
      :format-size (fn [size]
                     (str "S" size))})
    (is (= 1 (alength (.-children container))))
    (let [overlay-root (aget (.-children container) 0)
          text (str/join " " (collect-text-content overlay-root))
          cancel-button (find-dom-node overlay-root
                                       #(= "button" (some-> (.-tagName %) str/lower-case)))]
      (is (str/includes? text "Limit S1.00 at $P60.0"))
      (is (str/includes? text "Limit S2.25 at $P61.5"))
      (is (some? cancel-button))
      (click-dom-node! cancel-button))
    (is (= [12] @canceled-oids*))
    (chart-interop/clear-open-order-overlays! chart-obj)
    (is (= 0 (alength (.-children container))))
    (is (= 6 @unsubscribes*))))
