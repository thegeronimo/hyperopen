(ns hyperopen.views.portfolio.chart-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio.chart-view :as chart-view]))

(def ^:private returns-chart
  {:chart {:selected-tab :returns
           :axis-kind :percent
           :tabs [{:value :account-value :label "Account Value"}
                  {:value :pnl :label "PNL"}
                  {:value :returns :label "Returns"}]
           :points [{:time-ms 1 :value 0.1 :x-ratio 0 :y-ratio 1}
                    {:time-ms 2 :value 0.2 :x-ratio 1 :y-ratio 0}]
           :series [{:id :strategy
                     :label "Portfolio"
                     :stroke "#f5f7f8"
                     :has-data? true
                     :path "M 0 100 L 100 0"}
                    {:id :btc-usdc
                     :coin "BTC-USDC"
                     :label "BTC-USDC (PERP)"
                     :stroke "#f7931a"
                     :has-data? true
                     :path "M 0 60 L 100 40"}]
           :y-ticks [{:value 5 :y-ratio 0}
                     {:value 0 :y-ratio 0.5}
                     {:value -5 :y-ratio 1}]
           :hover {:active? false}}
   :selectors {:summary-time-range {:value :month}
               :returns-benchmark {:coin-search "BT"
                                   :suggestions-open? true
                                   :candidates [{:value "BTC-USDC"
                                                 :label "BTC-USDC (PERP)"}]
                                   :top-coin "BTC-USDC"
                                   :selected-options [{:value "BTC-USDC"
                                                       :label "BTC-USDC (PERP)"}]}}})

(deftest chart-card-renders-returns-benchmark-controls-and-d3-host-test
  (let [view (chart-view/chart-card returns-chart)
        selector (hiccup/find-by-data-role view "portfolio-returns-benchmark-selector")
        suggestion-row (hiccup/find-by-data-role view "portfolio-returns-benchmark-suggestion-BTC-USDC")
        chip-rail (hiccup/find-by-data-role view "portfolio-returns-benchmark-chip-rail")
        chip (hiccup/find-by-data-role view "portfolio-returns-benchmark-chip-BTC-USDC")
        legend (hiccup/find-by-data-role view "portfolio-chart-legend")
        plot-area (hiccup/find-by-data-role view "portfolio-chart-plot-area")
        host (hiccup/find-by-data-role view "portfolio-chart-d3-host")
        chip-text (set (hiccup/collect-strings chip))]
    (is (some? selector))
    (is (some? suggestion-row))
    (is (= [[:actions/select-portfolio-returns-benchmark "BTC-USDC"]]
           (get-in suggestion-row [1 :on :mousedown])))
    (is (some? chip-rail))
    (is (contains? chip-text "BTC"))
    (is (not (contains? chip-text "BTC-USDC (PERP)")))
    (is (= "rgba(247, 147, 26, 0.58)"
           (get-in chip [1 :style :border-color])))
    (is (some? legend))
    (is (some? plot-area))
    (is (nil? (get-in plot-area [1 :on])))
    (is (some? host))
    (is (fn? (get-in host [1 :replicant/on-render])))))

(deftest chart-card-estimate-note-is-hidden-unless-present-test
  (let [hidden (hiccup/find-by-data-role (chart-view/chart-card returns-chart)
                                         "portfolio-chart-estimate-note")
        shown-view (chart-view/chart-card
                    (assoc-in returns-chart [:chart :estimate-note]
                              "Estimated before Jul 01, 2026 from vault history sampled about every 12 days"))
        shown (hiccup/find-by-data-role shown-view "portfolio-chart-estimate-note")]
    (is (contains? (hiccup/node-class-set hidden) "hidden"))
    (is (not (contains? (hiccup/node-class-set shown) "hidden")))
    (is (some #(re-find #"^Estimated before Jul 01, 2026 from vault history sampled about every 12 days$" %)
              (hiccup/collect-strings shown)))
    (is (some #{"~"} (hiccup/collect-strings shown)))))
