(ns hyperopen.views.chart.tooltip-core-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.chart.tooltip-core :as tooltip-core]))

(deftest benchmark-rows-filter-series-and-apply-fallbacks-test
  (let [rows (tooltip-core/benchmark-rows {:metric-kind :returns
                                           :hovered-index 1
                                           :series [{:id :strategy
                                                     :points [{:value 1}
                                                              {:value 2}]}
                                                    {:id :btc
                                                     :coin " BTC "
                                                     :label "Bitcoin"
                                                     :stroke ""
                                                     :points [{:value 0.1}
                                                              {:value 0.2}]}
                                                    {:id :eth
                                                     :coin ""
                                                     :label "  "
                                                     :stroke "#33d1b7"
                                                     :points [{:value 0.3}
                                                              {:value js/NaN}]}
                                                    {:id :sol
                                                     :coin " "
                                                     :label ""
                                                     :stroke nil
                                                     :points [{:value 0.4}
                                                              {:value 0.5}]}
                                                    {:id "bad-id"
                                                     :points [{:value 1}
                                                              {:value 2}]}]}
                                          {:format-benchmark-value (fn [value]
                                                                     (str "v:" value))})]
    (is (= [{:coin "BTC"
             :label "Bitcoin"
             :value "v:0.2"
             :stroke "#e6edf2"}
            {:coin "sol"
             :label "sol"
             :value "v:0.5"
             :stroke "#e6edf2"}]
           rows))
    (is (= []
           (tooltip-core/benchmark-rows {:metric-kind :pnl
                                         :hovered-index 1
                                         :series [{:id :btc
                                                   :points [{:value 0.1}
                                                            {:value 0.2}]}]}
                                        {:format-benchmark-value str})))))

(deftest build-hover-tooltip-uses-shared-labels-classes-and-timestamp-policy-test
  (let [tooltip (tooltip-core/build-hover-tooltip {:time-range :day
                                                   :metric-kind :account-value
                                                   :hover-point {:time-ms 42
                                                                 :value 250}
                                                   :hovered-index 0
                                                   :series [{:id :benchmark-0
                                                             :coin "SPY"
                                                             :label "SPY (SPOT)"
                                                             :stroke "#f2cf66"
                                                             :points [{:value 12}]}]}
                                                  {:format-date (fn [time-ms]
                                                                  (str "date:" time-ms))
                                                   :format-time (fn [time-ms]
                                                                  (str "time:" time-ms))
                                                   :format-metric-value (fn [metric-kind value]
                                                                          (str (name metric-kind) ":" value))
                                                   :format-benchmark-value (fn [value]
                                                                             (str "benchmark:" value))})
        pnl-tooltip (tooltip-core/build-hover-tooltip {:time-range :month
                                                       :metric-kind :pnl
                                                       :hover-point {:time-ms 77
                                                                     :value -5}
                                                       :hovered-index 0
                                                       :series []}
                                                      {:format-date (fn [time-ms]
                                                                      (str "date:" time-ms))
                                                       :format-time (fn [time-ms]
                                                                      (str "time:" time-ms))
                                                       :format-metric-value (fn [metric-kind value]
                                                                              (str (name metric-kind) ":" value))
                                                       :format-benchmark-value str})]
    (is (= "time:42" (:timestamp tooltip)))
    (is (= "Account Value" (:metric-label tooltip)))
    (is (= "account-value:250" (:metric-value tooltip)))
    (is (= ["text-[#ff9f1a]"] (:value-classes tooltip)))
    (is (= [] (:benchmark-values tooltip)))
    (is (= "date:77" (:timestamp pnl-tooltip)))
    (is (= ["text-[#ff7b72]"] (:value-classes pnl-tooltip)))))

(deftest benchmark-rows-use-latest-prior-point-by-hover-time-instead-of-shared-index-test
  (let [rows (tooltip-core/benchmark-rows {:metric-kind :returns
                                           :hover-time-ms 25
                                           :hovered-index 1
                                           :series [{:id :strategy
                                                     :points [{:time-ms 10 :value 1}
                                                              {:time-ms 25 :value 2}]}
                                                    {:id :btc
                                                     :coin "BTC"
                                                     :label "Bitcoin"
                                                     :stroke "#f2cf66"
                                                     :points [{:time-ms 10 :value -6}
                                                              {:time-ms 40 :value -12}]}]}
                                          {:format-benchmark-value str})]
    (is (= [{:coin "BTC"
             :label "Bitcoin"
             :value "-6"
             :stroke "#f2cf66"}]
           rows))))
