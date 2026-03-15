(ns hyperopen.views.vaults.detail.chart-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]
            [hyperopen.views.vaults.detail.chart-view :as chart]))

(defn- mount-d3-host!
  [on-render]
  (let [document (fake-dom/make-fake-document)
        host (fake-dom/make-fake-element "div")]
    (aset host "ownerDocument" document)
    (set! (.-clientWidth host) 400)
    (set! (.-clientHeight host) 260)
    (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                :replicant/node host
                :replicant/remember (fn [_memory] nil)})
    host))

(defn- find-dom-node-by-role
  [root data-role]
  (fake-dom/find-dom-node root #(and (= 1 (.-nodeType %))
                                     (= data-role (.getAttribute % "data-role")))))

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

(deftest chart-section-renders-returns-benchmark-controls-and-d3-tooltip-test
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
                                             :points [{:time-ms 1700000000000 :value 0.2 :x-ratio 0.0 :y-ratio 0.7}
                                                      {:time-ms 1700003600000 :value -0.1 :x-ratio 0.8 :y-ratio 0.2}]}
                                            {:id :btc
                                             :coin "BTC"
                                             :label "Bitcoin"
                                             :stroke "#f7931a"
                                             :has-data? true
                                             :points [{:time-ms 1700000000000 :value 0.4 :x-ratio 0.0 :y-ratio 0.5}
                                                      {:time-ms 1700003600000 :value 0.3 :x-ratio 0.8 :y-ratio 0.3}]}]
                                   :points [{:time-ms 1700000000000 :value 0.2 :x-ratio 0.0 :y-ratio 0.7}
                                            {:time-ms 1700003600000 :value -0.1 :x-ratio 0.8 :y-ratio 0.2}]
                                   :hover {:active? false}
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
        plot-area (hiccup/find-first-node view
                                          #(= "vault-detail-chart-plot-area"
                                              (get-in % [1 :data-role])))
        host-node (hiccup/find-first-node view
                                          #(= "vault-detail-chart-d3-host"
                                              (get-in % [1 :data-role])))
        host (mount-d3-host! (get-in host-node [1 :replicant/on-render]))]
    (is (some? selector))
    (is (some? suggestion-row))
    (is (some? chip-rail))
    (is (some? remove-chip-button))
    (is (= "#f7931a" (get-in remove-chip-button [1 :style :color])))
    (is (some? plot-area))
    (is (nil? (get-in plot-area [1 :on])))
    (is (some? host-node))
    (is (fn? (get-in host-node [1 :replicant/on-render])))
    (fake-dom/dispatch-dom-event-with-payload! host "pointermove" #js {:clientX 390})
    (let [hover-tooltip (find-dom-node-by-role host "vault-detail-chart-hover-tooltip")
          hover-benchmark-value (find-dom-node-by-role host "vault-detail-chart-hover-tooltip-benchmark-value-BTC")
          secondary-path (find-dom-node-by-role host "vault-detail-chart-path-btc")
          tooltip-strings (set (fake-dom/collect-text-content hover-tooltip))]
      (is (some? hover-tooltip))
      (is (= "translate(calc(-100% - 8px), -50%)"
             (aget (.-style hover-tooltip) "transform")))
      (is (contains? tooltip-strings "Returns"))
      (is (contains? tooltip-strings "-0.10%"))
      (is (some? hover-benchmark-value))
      (is (= "#f7931a" (aget (.-style hover-benchmark-value) "color")))
      (is (some? secondary-path)))))

(deftest chart-section-renders-pnl-split-area-in-d3-runtime-test
  (let [view (chart/chart-section {:axis-kind :pnl
                                   :y-ticks [{:value 100 :y-ratio 0}
                                             {:value 0 :y-ratio 0.5}
                                             {:value -100 :y-ratio 1}]
                                   :selected-series :pnl
                                   :series-tabs [{:value :returns :label "Returns"}
                                                 {:value :pnl :label "PNL"}]
                                   :series [{:id :strategy
                                             :label "Vault"
                                             :stroke "#16d6a1"
                                             :has-data? true
                                             :area-positive-fill "rgba(22, 214, 161, 0.24)"
                                             :area-negative-fill "rgba(237, 112, 136, 0.24)"
                                             :zero-y-ratio 0.5
                                             :points [{:time-ms 1700000000000 :value -10 :x-ratio 0.0 :y-ratio 0.8}
                                                      {:time-ms 1700003600000 :value 40 :x-ratio 1.0 :y-ratio 0.3}]}]
                                   :points [{:time-ms 1700000000000 :value -10 :x-ratio 0.0 :y-ratio 0.8}
                                            {:time-ms 1700003600000 :value 40 :x-ratio 1.0 :y-ratio 0.3}]
                                   :hover {:active? false}
                                   :returns-benchmark {:selected-options [{:value "BTC"
                                                                           :label "Bitcoin"}]}
                                   :timeframe-options [{:value :day :label "24H"}]
                                   :selected-timeframe :month})
        selector (hiccup/find-first-node view
                                         #(= "vault-detail-returns-benchmark-selector"
                                             (get-in % [1 :data-role])))
        host-node (hiccup/find-first-node view
                                          #(= "vault-detail-chart-d3-host"
                                              (get-in % [1 :data-role])))
        host (mount-d3-host! (get-in host-node [1 :replicant/on-render]))]
    (is (nil? selector))
    (is (some? (find-dom-node-by-role host "vault-detail-chart-area-positive")))
    (is (some? (find-dom-node-by-role host "vault-detail-chart-area-negative")))
    (is (= "rgba(22, 214, 161, 0.24)"
           (.getAttribute (find-dom-node-by-role host "vault-detail-chart-area-positive") "fill")))
    (is (= "rgba(237, 112, 136, 0.24)"
           (.getAttribute (find-dom-node-by-role host "vault-detail-chart-area-negative") "fill")))))

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
                                             :area-fill "rgba(247, 147, 26, 0.24)"
                                             :points [{:time-ms 1700000000000 :value 100 :x-ratio 0.0 :y-ratio 0.9}
                                                      {:time-ms 1700003600000 :value 120 :x-ratio 1.0 :y-ratio 0.2}]}]
                                   :points [{:time-ms 1700000000000 :value 100 :x-ratio 0.0 :y-ratio 0.9}
                                            {:time-ms 1700003600000 :value 120 :x-ratio 1.0 :y-ratio 0.2}]
                                   :hover {:active? false}
                                   :returns-benchmark {:selected-options [{:value "BTC"
                                                                           :label "Bitcoin"}]}
                                   :timeframe-options [{:value :day :label "24H"}]
                                   :selected-timeframe :month})
        selector (hiccup/find-first-node view
                                         #(= "vault-detail-returns-benchmark-selector"
                                             (get-in % [1 :data-role])))
        host-node (hiccup/find-first-node view
                                          #(= "vault-detail-chart-d3-host"
                                              (get-in % [1 :data-role])))
        host (mount-d3-host! (get-in host-node [1 :replicant/on-render]))
        chip-rail (hiccup/find-first-node view
                                          #(= "vault-detail-returns-benchmark-chip-rail"
                                              (get-in % [1 :data-role])))
        area-node (find-dom-node-by-role host "vault-detail-chart-area")
        split-positive-node (find-dom-node-by-role host "vault-detail-chart-area-positive")
        split-negative-node (find-dom-node-by-role host "vault-detail-chart-area-negative")]
    (is (some? area-node))
    (is (nil? split-positive-node))
    (is (nil? split-negative-node))
    (is (= "rgba(247, 147, 26, 0.24)"
           (.getAttribute area-node "fill")))
    (is (nil? selector))
    (is (nil? chip-rail))
    (is (= "none"
           (aget (.-style (find-dom-node-by-role host "vault-detail-chart-hover-tooltip"))
                 "display")))))
