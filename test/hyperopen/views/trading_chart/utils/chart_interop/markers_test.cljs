(ns hyperopen.views.trading-chart.utils.chart-interop.markers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop :as chart-interop]
            [hyperopen.views.trading-chart.utils.chart-interop.markers :as markers]))

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

(deftest markers-module-normalizes-marker-input-and-reuses-plugin-test
  (let [create-calls (atom 0)
        plugin-sets (atom [])
        main-series #js {:id "main"}
        chart-obj #js {:mainSeries main-series}
        stable-markers [{:time 1 :position "aboveBar"}]
        create-markers (fn [series initial]
                         (swap! create-calls inc)
                         (is (= [] (js->clj initial)))
                         #js {:setMarkers (fn [markers]
                                            (swap! plugin-sets conj {:series-id (.-id series)
                                                                     :markers (js->clj markers :keywordize-keys true)}))})]
    (is (nil? (markers/set-main-series-markers! nil [{:time 1}] {:create-markers create-markers})))
    (markers/set-main-series-markers! chart-obj {:not "sequential"} {:create-markers create-markers})
    (markers/set-main-series-markers! chart-obj stable-markers {:create-markers create-markers})
    (markers/set-main-series-markers! chart-obj stable-markers {:create-markers create-markers})
    (is (= 1 @create-calls))
    (is (= [] (:markers (first @plugin-sets))))
    (is (= [{:time 1 :position "aboveBar"}]
           (:markers (second @plugin-sets))))))

