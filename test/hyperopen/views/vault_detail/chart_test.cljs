(ns hyperopen.views.vault-detail.chart-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.vault-detail.chart :as chart]))

(deftest chart-private-formatters-handle-signs-and-fallbacks-test
  (let [format-axis @#'chart/format-chart-axis-value
        format-tooltip @#'chart/format-chart-tooltip-value]
    (is (= "+1.50%" (format-axis :returns 1.5)))
    (is (= "-3.40%" (format-axis :returns -3.4)))
    (is (= "0.00%" (format-axis :returns -0.000001)))
    (with-redefs [fmt/format-large-currency (fn [_] nil)]
      (is (= "$0" (format-axis :pnl js/NaN))))
    (is (= "$0.00" (format-tooltip :pnl js/NaN)))
    (is (= "$0.00" (format-tooltip :account-value nil)))))

(deftest chart-tooltip-models-and-benchmark-values-test
  (let [tooltip-model @#'chart/chart-tooltip-model
        tooltip-benchmark-values @#'chart/chart-tooltip-benchmark-values
        day-model (tooltip-model :day :returns {:time-ms 1700000000000
                                                :value 0.25})
        month-model (tooltip-model :month :returns {:time-ms 1700000000000
                                                    :value -0.5})
        benchmark-values (tooltip-benchmark-values :returns
                                                   1
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
                                                              {:value 0.4}]}])]
    (is (re-find #":" (:timestamp day-model)))
    (is (= "Returns" (:metric-label day-model)))
    (is (= "+0.25%" (:metric-value day-model)))
    (is (not (str/includes? (:timestamp month-model) ":")))
    (is (= "-0.50%" (:metric-value month-model)))
    (is (= 2 (count benchmark-values)))
    (is (= "Bitcoin" (get-in benchmark-values [0 :label])))
    (is (= "#e6edf2" (get-in benchmark-values [0 :stroke])))
    (is (= "#33d1b7" (get-in benchmark-values [1 :stroke])))
    (is (= [] (tooltip-benchmark-values :pnl 1 [])))))

(deftest chart-timeframe-menu-resolves-token-and-actions-test
  (let [menu (chart/chart-timeframe-menu {:timeframe-options [{:value :week
                                                                :label "7D"}
                                                               {:value "month"
                                                                :label "30D"}]
                                          :selected-timeframe nil})
        select (hiccup/find-first-node menu #(= :select (first %)))
        selected-option (hiccup/find-first-node menu
                                                #(and (= :option (first %))
                                                      (true? (get-in % [1 :selected]))))
        fallback-menu (chart/chart-timeframe-menu {:timeframe-options []
                                                   :selected-timeframe nil})
        fallback-select (hiccup/find-first-node fallback-menu #(= :select (first %)))]
    (is (= "week" (get-in select [1 :value])))
    (is (= [[:actions/set-vaults-snapshot-range [:event.target/value]]]
           (get-in select [1 :on :change])))
    (is (= "week" (get-in selected-option [1 :value])))
    (is (= "day" (get-in fallback-select [1 :value])))))

(deftest chart-section-renders-returns-benchmark-hover-and-split-area-test
  (let [view (chart/chart-section {:axis-kind :returns
                                   :y-ticks [{:value 5 :y-ratio 0}
                                             {:value 0 :y-ratio 0.5}
                                             {:value -5 :y-ratio 1}]
                                   :selected-series :returns
                                   :series-tabs [{:value :returns :label "Returns"}
                                                 {:value :pnl :label "PNL"}]
                                   :series [{:id :strategy
                                             :label "Vault"
                                             :stroke "#16d6a1"
                                             :has-data? true
                                             :path "M 0 90 L 100 10"
                                             :area-path "M 0 90 L 100 10 L 100 100 Z"
                                             :area-positive-fill "rgba(22, 214, 161, 0.24)"
                                             :area-negative-fill "rgba(237, 112, 136, 0.24)"
                                             :zero-y-ratio 1.2
                                             :points [{:time-ms 1700000000000 :value 0.2}
                                                      {:time-ms 1700003600000 :value -0.1}]}
                                            {:id :btc
                                             :coin "BTC"
                                             :label "Bitcoin"
                                             :stroke "#f7931a"
                                             :has-data? true
                                             :path "M 0 85 L 100 15"
                                             :points [{:time-ms 1700000000000 :value 0.4}
                                                      {:time-ms 1700003600000 :value 0.3}]}]
                                   :points [{:time-ms 1700000000000 :value 0.2}
                                            {:time-ms 1700003600000 :value -0.1}]
                                   :hover {:active? true
                                           :index 1
                                           :point {:time-ms 1700003600000
                                                   :value -0.1
                                                   :x-ratio 0.8
                                                   :y-ratio 0.2}}
                                   :returns-benchmark {:coin-search "BT"
                                                       :suggestions-open? true
                                                       :candidates [{:value "BTC" :label "Bitcoin"}]
                                                       :top-coin "BTC"
                                                       :selected-options [{:value "BTC" :label "Bitcoin"}]
                                                       :empty-message "No symbols."}
                                   :timeframe-options [{:value :day :label "24H"}
                                                       {:value :month :label "30D"}]
                                   :selected-timeframe :day})
        selector (hiccup/find-first-node view
                                         #(= "vault-detail-returns-benchmark-selector"
                                             (get-in % [1 :data-role])))
        suggestion-row (hiccup/find-first-node view
                                               #(= "vault-detail-returns-benchmark-suggestion-BTC"
                                                   (get-in % [1 :data-role])))
        chip-rail (hiccup/find-first-node view
                                          #(= "vault-detail-returns-benchmark-chip-rail"
                                              (get-in % [1 :data-role])))
        remove-chip-button (hiccup/find-first-node view
                                                   #(= "Remove benchmark Bitcoin"
                                                       (get-in % [1 :aria-label])))
        hover-tooltip (hiccup/find-first-node view
                                              #(= "vault-detail-chart-hover-tooltip"
                                                  (get-in % [1 :data-role])))
        hover-benchmark-value (hiccup/find-first-node view
                                                      #(= "vault-detail-chart-hover-tooltip-benchmark-value-BTC"
                                                          (get-in % [1 :data-role])))
        area-positive (hiccup/find-first-node view
                                              #(= "vault-detail-chart-area-positive"
                                                  (get-in % [1 :data-role])))
        area-negative (hiccup/find-first-node view
                                              #(= "vault-detail-chart-area-negative"
                                                  (get-in % [1 :data-role])))
        secondary-path (hiccup/find-first-node view
                                               #(= "vault-detail-chart-path-btc"
                                                   (get-in % [1 :data-role])))]
    (is (some? selector))
    (is (some? suggestion-row))
    (is (some? chip-rail))
    (is (some? remove-chip-button))
    (is (= "#f7931a" (get-in remove-chip-button [1 :style :color])))
    (is (some? hover-tooltip))
    (is (= "translate(calc(-100% - 8px), -50%)"
           (get-in hover-tooltip [1 :style :transform])))
    (is (some? hover-benchmark-value))
    (is (= "#f7931a" (get-in hover-benchmark-value [1 :style :color])))
    (is (some? area-positive))
    (is (some? area-negative))
    (is (some? secondary-path))))

(deftest chart-section-renders-single-area-fill-and-hides-returns-controls-test
  (let [view (chart/chart-section {:axis-kind :account-value
                                   :y-ticks [{:value 100 :y-ratio 0}
                                             {:value 50 :y-ratio 0.5}
                                             {:value 0 :y-ratio 1}]
                                   :selected-series :account-value
                                   :series-tabs [{:value :account-value :label "Account Value"}]
                                   :series [{:id :strategy
                                             :label "Vault"
                                             :stroke "#f7931a"
                                             :has-data? true
                                             :path "M 0 90 L 100 10"
                                             :area-path "M 0 90 L 100 10 L 100 100 Z"
                                             :area-fill "rgba(247, 147, 26, 0.24)"
                                             :points [{:time-ms 1700000000000 :value 100}
                                                      {:time-ms 1700003600000 :value 120}]}]
                                   :points [{:time-ms 1700000000000 :value 100}
                                            {:time-ms 1700003600000 :value 120}]
                                   :hover {:active? false}
                                   :returns-benchmark {:selected-options [{:value "BTC"
                                                                           :label "Bitcoin"}]}
                                   :timeframe-options [{:value :day :label "24H"}]
                                   :selected-timeframe :month})
        area-node (hiccup/find-first-node view
                                          #(= "vault-detail-chart-area"
                                              (get-in % [1 :data-role])))
        split-area-node (hiccup/find-first-node view
                                                #(= "vault-detail-chart-area-split"
                                                    (get-in % [1 :data-role])))
        selector (hiccup/find-first-node view
                                         #(= "vault-detail-returns-benchmark-selector"
                                             (get-in % [1 :data-role])))
        chip-rail (hiccup/find-first-node view
                                          #(= "vault-detail-returns-benchmark-chip-rail"
                                              (get-in % [1 :data-role])))
        hover-tooltip (hiccup/find-first-node view
                                              #(= "vault-detail-chart-hover-tooltip"
                                                  (get-in % [1 :data-role])))]
    (is (some? area-node))
    (is (nil? split-area-node))
    (is (nil? selector))
    (is (nil? chip-rail))
    (is (nil? hover-tooltip))))
