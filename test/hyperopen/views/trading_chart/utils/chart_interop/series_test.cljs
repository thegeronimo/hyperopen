(ns hyperopen.views.trading-chart.utils.chart-interop.series-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.series :as series]))

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

(deftest set-series-data-identity-gates-repeated-candle-reference-test
  (let [set-calls* (atom 0)
        update-calls* (atom 0)
        candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5}
                 {:time 2 :open 10.5 :high 12 :low 10 :close 11.5}]
        series #js {:applyOptions (fn [_] nil)
                    :setData (fn [_]
                               (swap! set-calls* inc))
                    :update (fn [_]
                              (swap! update-calls* inc))}]
    (chart-interop/set-series-data! series candles :candlestick)
    (chart-interop/set-series-data! series candles :candlestick)
    (is (= 1 @set-calls*))
    (is (zero? @update-calls*))))

(deftest set-series-data-uses-incremental-update-for-tail-edit-and-append-test
  (let [calls* (atom [])
        candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5}
                 {:time 2 :open 10.5 :high 12 :low 10 :close 11.5}]
        updated-last [{:time 1 :open 10 :high 11 :low 9 :close 10.5}
                      {:time 2 :open 10.5 :high 12.5 :low 10 :close 12.0}]
        appended (conj updated-last {:time 3 :open 12.0 :high 12.2 :low 11.6 :close 11.8})
        series #js {:applyOptions (fn [_] nil)
                    :setData (fn [data]
                               (swap! calls* conj [:set (js->clj data :keywordize-keys true)]))
                    :update (fn [point]
                              (swap! calls* conj [:update (js->clj point :keywordize-keys true)]))}]
    (chart-interop/set-series-data! series candles :candlestick)
    (chart-interop/set-series-data! series updated-last :candlestick)
    (chart-interop/set-series-data! series appended :candlestick)
    (is (= :set (ffirst @calls*)))
    (is (= [:update {:time 2 :open 10.5 :high 12.5 :low 10 :close 12.0}]
           (second @calls*)))
    (is (= [:update {:time 3 :open 12.0 :high 12.2 :low 11.6 :close 11.8}]
           (nth @calls* 2)))))

(deftest set-series-data-non-tail-mutation-falls-back-to-full-reset-test
  (let [calls* (atom [])
        candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5}
                 {:time 2 :open 10.5 :high 12 :low 10 :close 11.5}]
        mutated-prefix [{:time 1 :open 10 :high 13 :low 9 :close 12.5}
                        {:time 2 :open 10.5 :high 12 :low 10 :close 11.5}]
        series #js {:applyOptions (fn [_] nil)
                    :setData (fn [_]
                               (swap! calls* conj :set))
                    :update (fn [_]
                              (swap! calls* conj :update))}]
    (chart-interop/set-series-data! series candles :candlestick)
    (chart-interop/set-series-data! series mutated-prefix :candlestick)
    (is (= [:set :set] @calls*))))

(deftest set-volume-data-identity-gates-and-incrementally-updates-tail-test
  (let [calls* (atom [])
        candles [{:time 1 :open 10 :high 11 :low 9 :close 10.5 :volume 100}
                 {:time 2 :open 10.5 :high 12 :low 10 :close 11.5 :volume 120}]
        updated-last [{:time 1 :open 10 :high 11 :low 9 :close 10.5 :volume 100}
                      {:time 2 :open 10.5 :high 12 :low 10 :close 11.0 :volume 140}]
        appended (conj updated-last {:time 3 :open 11.0 :high 11.4 :low 10.9 :close 11.2 :volume 90})
        volume-series #js {:setData (fn [data]
                                      (swap! calls* conj [:set (js->clj data :keywordize-keys true)]))
                           :update (fn [point]
                                     (swap! calls* conj [:update (js->clj point :keywordize-keys true)]))}]
    (chart-interop/set-volume-data! volume-series candles)
    (chart-interop/set-volume-data! volume-series candles)
    (chart-interop/set-volume-data! volume-series updated-last)
    (chart-interop/set-volume-data! volume-series appended)
    (is (= :set (ffirst @calls*)))
    (is (= 3 (count @calls*)))
    (is (= [:update {:value 140 :time 2 :color "rgba(34, 171, 148, 0.5)"}]
           (second @calls*)))
    (is (= [:update {:value 90 :time 3 :color "rgba(34, 171, 148, 0.5)"}]
           (nth @calls* 2)))))

(deftest set-volume-data-applies-transformed-volume-points-test
  (let [applied-data (atom nil)
        volume-series #js {:setData (fn [data]
                                      (reset! applied-data (js->clj data :keywordize-keys true)))}
        candles [{:time 1 :open 10 :high 12 :low 9 :close 11 :volume 50}
                 {:time 2 :open 11 :high 12 :low 8 :close 9 :volume 35}]]
    (chart-interop/set-volume-data! volume-series candles)
    (is (= [{:time 1 :value 50 :color "rgba(34, 171, 148, 0.5)"}
            {:time 2 :value 35 :color "rgba(247, 82, 95, 0.5)"}]
           @applied-data))))

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

