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

