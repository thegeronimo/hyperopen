(ns hyperopen.views.vaults.detail.chart-tooltip-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vaults.detail.chart-tooltip :as chart-tooltip]))

(deftest format-chart-tooltip-value-handles-signs-and-fallbacks-test
  (let [format-tooltip @#'chart-tooltip/format-chart-tooltip-value]
    (is (= "+1.50%" (format-tooltip :returns 1.5)))
    (is (= "-3.40%" (format-tooltip :returns -3.4)))
    (is (= "0.00%" (format-tooltip :returns -0.000001)))
    (is (= "$0.00" (format-tooltip :pnl js/NaN)))
    (is (= "$0.00" (format-tooltip :account-value nil)))))

(deftest build-chart-hover-tooltip-builds-vault-tooltip-and-benchmark-rows-test
  (let [day-tooltip (chart-tooltip/build-chart-hover-tooltip :day
                                                             :returns
                                                             {:point {:time-ms 1700000000000
                                                                      :value 0.25}
                                                              :index 1}
                                                             [{:id :strategy
                                                               :points [{:value 0.1}
                                                                        {:value 0.2}]}
                                                              {:id :btc
                                                               :coin "BTC"
                                                               :label "Bitcoin"
                                                               :stroke ""
                                                               :points [{:value 0.1}
                                                                        {:value -0.2}]}
                                                              {:id :eth
                                                               :coin "ETH"
                                                               :label ""
                                                               :stroke "#33d1b7"
                                                               :points [{:value 0.3}
                                                                        {:value 0.4}]}])
        month-tooltip (chart-tooltip/build-chart-hover-tooltip :month
                                                               :returns
                                                               {:point {:time-ms 1700000000000
                                                                        :value -0.5}
                                                                :index 1}
                                                               [])]
    (is (re-find #":" (:timestamp day-tooltip)))
    (is (= "Returns" (:metric-label day-tooltip)))
    (is (= "+0.25%" (:metric-value day-tooltip)))
    (is (not (str/includes? (:timestamp month-tooltip) ":")))
    (is (= "-0.50%" (:metric-value month-tooltip)))
    (is (= 2 (count (:benchmark-values day-tooltip))))
    (is (= "Bitcoin" (get-in day-tooltip [:benchmark-values 0 :label])))
    (is (= "#e6edf2" (get-in day-tooltip [:benchmark-values 0 :stroke])))
    (is (= "#33d1b7" (get-in day-tooltip [:benchmark-values 1 :stroke])))
    (is (= [] (:benchmark-values (chart-tooltip/build-chart-hover-tooltip :day
                                                                          :pnl
                                                                          {:point {:time-ms 1
                                                                                   :value 2}
                                                                           :index 0}
                                                                          []))))))

(deftest build-chart-hover-tooltip-labels-primary-row-with-selected-vault-test
  (let [tooltip (chart-tooltip/build-chart-hover-tooltip :day
                                                         :returns
                                                         {:point {:time-ms 1700000000000
                                                                  :value -2.0}
                                                          :index 0}
                                                         [{:id :strategy
                                                           :label "Growi HF"
                                                           :points [{:value -2.0}]}
                                                          {:id :benchmark-0
                                                           :coin "vault:0xabc"
                                                           :label "Peer Vault (VAULT)"
                                                           :stroke "#f2cf66"
                                                           :points [{:value 53.82}]}])]
    (is (= "Growi HF Returns" (:metric-label tooltip)))
    (is (= "-2.00%" (:metric-value tooltip)))
    (is (= "Peer Vault (VAULT)"
           (get-in tooltip [:benchmark-values 0 :label])))))

(deftest build-chart-hover-tooltip-uses-latest-prior-benchmark-point-by-time-test
  (let [tooltip (chart-tooltip/build-chart-hover-tooltip :month
                                                         :returns
                                                         {:point {:time-ms 25
                                                                  :value 1.25}
                                                          :index 1}
                                                         [{:id :strategy
                                                           :points [{:time-ms 10 :value 0.5}
                                                                    {:time-ms 25 :value 1.25}]}
                                                          {:id :btc
                                                           :coin "BTC"
                                                           :label "Bitcoin"
                                                           :stroke "#f2cf66"
                                                           :points [{:time-ms 10 :value -6}
                                                                    {:time-ms 40 :value -12}]}])]
    (is (= [{:coin "BTC"
             :label "Bitcoin"
             :value "-6.00%"
             :stroke "#f2cf66"}]
           (:benchmark-values tooltip)))))
