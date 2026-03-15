(ns hyperopen.views.portfolio.vm.chart-helpers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.portfolio.vm.chart :as vm-chart]))

(deftest chart-history-and-point-normalization-follow-root-vm-contract-test
  (let [summary {:accountValueHistory [{:time-ms 1 :value 10.8}
                                       {:value 0}]
                 :pnlHistory [[1 5.2] [2 6.7]]}]
    (with-redefs [portfolio-metrics/returns-history-rows (fn [_state _summary _scope]
                                                           [[1 1.234]
                                                            [2 -0.0001]])]
      (is (= [[1 1.234] [2 -0.0001]]
             (vm-chart/chart-history-rows {} summary :returns :all)))
      (is (= [{:index 0 :time-ms 1 :value 1.23}
              {:index 1 :time-ms 2 :value 0}]
             (vm-chart/chart-data-points {} summary :returns :all)))
      (is (= [{:index 0 :time-ms 1 :value 11}
              {:index 1 :time-ms 1 :value 0}]
             (vm-chart/chart-data-points {} summary :account-value :all)))
      (is (= [{:index 0 :time-ms 1 :value 5}
              {:index 1 :time-ms 2 :value 7}]
             (vm-chart/chart-data-points {} summary :pnl :all)))
      (is (= [{:time-ms 1 :value 10.8}
              {:value 0}]
             (vm-chart/chart-history-rows {} summary :unknown :all))))))

(deftest build-chart-model-assembles-series-and-hover-from-canonical-modules-test
  (let [state {:portfolio-ui {:chart-tab :returns
                              :chart-hover-index 1}}
        benchmark-context {:strategy-cumulative-rows [[1 0.123]
                                                      [2 5.555]]
                           :benchmark-cumulative-rows-by-coin {}}
        chart (vm-chart/build-chart-model state
                                          {}
                                          :all
                                          :day
                                          {:selected-coins []
                                           :label-by-coin {}}
                                          benchmark-context)]
    (is (= :returns (:selected-tab chart)))
    (is (= :percent (:axis-kind chart)))
    (is (= [0.12 5.56]
           (mapv :value (:points chart))))
    (is (= 1 (get-in chart [:hover :index])))
    (is (= 5.56 (get-in chart [:hover :point :value])))
    (is (= "Returns" (get-in chart [:hover-tooltip :metric-label])))
    (is (= "+5.56%" (get-in chart [:hover-tooltip :metric-value])))
    (is (seq (:path chart)))
    (is (= 4 (count (:y-ticks chart))))))

(deftest build-chart-model-skips-svg-paths-when-explicitly-disabled-test
  (let [state {:portfolio-ui {:chart-tab :returns
                              :chart-hover-index 1}}
        benchmark-context {:strategy-cumulative-rows [[1 0.123]
                                                      [2 5.555]]
                           :benchmark-cumulative-rows-by-coin {}}
        chart (vm-chart/build-chart-model state
                                          {}
                                          :all
                                          :day
                                          {:selected-coins []
                                           :label-by-coin {}}
                                          benchmark-context
                                          {:include-svg-paths? false})]
    (is (nil? (:path chart)))
    (is (every? nil? (map :path (:series chart))))
    (is (= [0.12 5.56]
           (mapv :value (:points chart))))))
