(ns hyperopen.views.trading-chart.utils.chart-interop.indicators-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.utils.chart-interop.indicators :as indicator-interop]))

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


(defn- recording-series
  [calls id]
  #js {:applyOptions (fn [options]
                       (swap! calls conj [id (aget options "priceFormat")]))})

(deftest price-pane-indicators-follow-the-main-series-format-test
  (let [calls (atom [])
        overlay (recording-series calls :overlay)
        separate (recording-series calls :separate)
        entries #js [#js {:series overlay :paneIndex 0}
                     #js {:series separate :paneIndex 1}]
        format-a #js {:type "custom" :minMove 0.00001}
        format-b #js {:type "custom" :minMove 0.001}]
    (indicator-interop/sync-price-pane-formats! entries format-a)
    (is (= [[:overlay format-a]] @calls) "only the price pane is relabelled")
    (indicator-interop/sync-price-pane-formats! entries format-a)
    (is (= 1 (count @calls)) "an unchanged format is not re-applied")
    (indicator-interop/sync-price-pane-formats! entries format-b)
    (is (= [:overlay format-b] (last @calls))
        "new price decimals (a new format object) reach the overlay without a remount")
    (indicator-interop/sync-price-pane-formats! entries nil)
    (is (= 2 (count @calls)) "no unit format: overlays keep their own")
    (let [map-calls (atom [])
          map-overlay (recording-series map-calls :map)]
      (indicator-interop/sync-price-pane-formats! [{:series map-overlay :paneIndex 0}] format-a)
      (is (= [[:map format-a]] @map-calls) "mount-time vector entries work too"))))
