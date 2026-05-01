(ns hyperopen.views.portfolio-view-chart-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio.test-support :refer [class-values
                                                            collect-strings
                                                            count-nodes
                                                            find-dom-node-by-role
                                                            find-first-node
                                                            mount-d3-host!
                                                            px-width
                                                            sample-state]]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(use-fixtures :each
  (fn [f]
    (chart-hover-state/clear-hover-state!)
    (f)
    (chart-hover-state/clear-hover-state!)))

(deftest portfolio-view-chart-y-axis-allocates-readable-gutter-for-large-values-test
  (let [state (-> sample-state
                  (assoc-in [:portfolio-ui :chart-tab] :pnl)
                  (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                            [[1 -2500000] [2 1500000] [3 3750000]]))
        view-node (portfolio-view/portfolio-view state)
        y-axis-node (find-first-node view-node #(= "portfolio-chart-y-axis" (get-in % [1 :data-role])))
        y-axis-width-px (some-> y-axis-node
                                (get-in [1 :style :width])
                                px-width)
        y-axis-label-node (find-first-node
                           view-node
                           (fn [candidate]
                             (let [classes (set (class-values candidate))
                                   text-values (collect-strings candidate)]
                               (and (contains? classes "num")
                                    (contains? classes "text-right")
                                    (some #(re-find #"," %) text-values)))))
        all-text (collect-strings view-node)]
    (is (some? y-axis-node))
    (is (number? y-axis-width-px))
    (is (> y-axis-width-px 56))
    (is (some? y-axis-label-node))
    (is (some #(re-find #"[0-9],[0-9]" %) all-text))))

(deftest portfolio-view-returns-tab-renders-percent-axis-labels-test
  (let [state (-> sample-state
                  (assoc-in [:portfolio-ui :chart-tab] :returns)
                  (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                            [[1 0] [2 2] [3 -1]])
                  (assoc-in [:portfolio :summary-by-key :month :accountValueHistory]
                            [[1 100] [2 102] [3 99]]))
        view-node (portfolio-view/portfolio-view state)
        all-text (collect-strings view-node)]
    (is (some #(= "Returns" %) all-text))
    (is (some #(re-find #"\+[0-9]+\.[0-9]{2}%" %) all-text))
    (is (some #(re-find #"-[0-9]+\.[0-9]{2}%" %) all-text))))

(deftest portfolio-view-returns-tab-renders-benchmark-selector-chip-rail-and-secondary-path-test
  (let [state (-> sample-state
                  (assoc-in [:portfolio-ui :chart-tab] :returns)
                  (assoc-in [:portfolio-ui :returns-benchmark-coins] ["SPY"])
                  (assoc-in [:asset-selector :markets]
                            [{:coin "SPY"
                              :symbol "SPY"
                              :market-type :spot
                              :cache-order 1}
                             {:coin "BTC"
                              :symbol "BTC-USD"
                              :market-type :perp
                              :cache-order 2}])
                  (assoc-in [:portfolio :summary-by-key :month :accountValueHistory]
                            [[1 100] [2 110] [3 120] [4 130]])
                  (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                            [[1 0] [2 0] [3 0] [4 0]])
                  (assoc-in [:candles "SPY" :1h]
                            [{:t 1 :c 50}
                             {:t 3 :c 55}
                             {:t 4 :c 60}]))
        view-node (portfolio-view/portfolio-view state)
        selector-node (find-first-node view-node #(= "portfolio-returns-benchmark-selector" (get-in % [1 :data-role])))
        benchmark-search-input (find-first-node view-node #(= "portfolio-returns-benchmark-search" (get-in % [1 :id])))
        chip-rail-node (find-first-node view-node #(= "portfolio-returns-benchmark-chip-rail" (get-in % [1 :data-role])))
        chip-node (find-first-node view-node #(= "portfolio-returns-benchmark-chip-SPY" (get-in % [1 :data-role])))
        legend-node (find-first-node view-node #(= "portfolio-chart-legend" (get-in % [1 :data-role])))
        legend-count (count-nodes view-node #(= "portfolio-chart-legend" (get-in % [1 :data-role])))
        chart-host (find-first-node view-node #(= "portfolio-chart-d3-host" (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))
        chip-border-color (get-in chip-node [1 :style :border-color])]
    (is (some? selector-node))
    (is (= "Search benchmarks and press Enter" (get-in benchmark-search-input [1 :placeholder])))
    (is (some? chip-rail-node))
    (is (some? chip-node))
    (is (some? legend-node))
    (is (= 1 legend-count))
    (is (some? chart-host))
    (is (fn? (get-in chart-host [1 :replicant/on-render])))
    (is (= "rgba(242, 207, 102, 0.58)" chip-border-color))
    (is (contains? all-text "Portfolio"))
    (is (contains? all-text "SPY (SPOT)"))))

(deftest portfolio-view-returns-tab-compacts-benchmark-chip-labels-test
  (let [state (-> sample-state
                  (assoc-in [:portfolio-ui :chart-tab] :returns)
                  (assoc-in [:portfolio-ui :returns-benchmark-coins] ["BTC-USDC"])
                  (assoc-in [:asset-selector :markets]
                            [{:coin "BTC-USDC"
                              :symbol "BTC-USDC"
                              :market-type :perp
                              :cache-order 1}])
                  (assoc-in [:portfolio :summary-by-key :month :accountValueHistory]
                            [[1 100] [2 110] [3 120] [4 130]])
                  (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                            [[1 0] [2 0] [3 0] [4 0]])
                  (assoc-in [:candles "BTC-USDC" :1h]
                            [{:t 1 :c 50}
                             {:t 3 :c 55}
                             {:t 4 :c 60}]))
        view-node (portfolio-view/portfolio-view state)
        chip-node (find-first-node view-node #(= "portfolio-returns-benchmark-chip-BTC-USDC" (get-in % [1 :data-role])))
        chip-text (set (collect-strings chip-node))]
    (is (some? chip-node))
    (is (contains? chip-text "BTC"))
    (is (not (contains? chip-text "BTC-USDC (PERP)")))))

(deftest portfolio-view-chart-plot-area-renders-d3-host-with-local-hover-runtime-test
  (let [view-node (portfolio-view/portfolio-view sample-state)
        plot-area-node (find-first-node view-node #(= "portfolio-chart-plot-area" (get-in % [1 :data-role])))
        chart-host (find-first-node view-node #(= "portfolio-chart-d3-host" (get-in % [1 :data-role])))]
    (is (some? plot-area-node))
    (is (nil? (get-in plot-area-node [1 :on])))
    (is (some? chart-host))
    (is (fn? (get-in chart-host [1 :replicant/on-render])))))

(deftest portfolio-view-chart-hover-runtime-renders-date-and-time-tooltip-variants-test
  (let [time-a (.getTime (js/Date. 2026 1 19 2 4 0))
        time-b (.getTime (js/Date. 2026 1 26 8 30 0))
        base-state (-> sample-state
                       (assoc-in [:portfolio-ui :chart-tab] :pnl)
                       (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                                 [[time-a 0] [time-b 203]]))
        month-view-node (portfolio-view/portfolio-view base-state)
        month-host-node (find-first-node month-view-node #(= "portfolio-chart-d3-host" (get-in % [1 :data-role])))
        month-runtime (mount-d3-host! (get-in month-host-node [1 :replicant/on-render]))
        day-state (assoc-in base-state [:portfolio-ui :summary-time-range] :day)
        day-view-node (portfolio-view/portfolio-view day-state)
        day-host-node (find-first-node day-view-node #(= "portfolio-chart-d3-host" (get-in % [1 :data-role])))
        day-runtime (mount-d3-host! (get-in day-host-node [1 :replicant/on-render]))]
    (fake-dom/dispatch-dom-event-with-payload! (:host month-runtime) "pointermove" #js {:clientX 390})
    (let [month-hover-line (find-dom-node-by-role (:host month-runtime) "portfolio-chart-hover-line")
          month-tooltip-node (find-dom-node-by-role (:host month-runtime) "portfolio-chart-hover-tooltip")
          month-tooltip-strings (set (fake-dom/collect-text-content month-tooltip-node))
          month-tooltip-class (or (.-className month-tooltip-node) "")]
      (is (some? month-hover-line))
      (is (some? month-tooltip-node))
      (is (some #(and (str/includes? % "2026")
                      (str/includes? % "Feb")
                      (str/includes? % "26"))
                month-tooltip-strings))
      (is (contains? month-tooltip-strings "PNL"))
      (is (contains? month-tooltip-strings "$203"))
      (is (str/includes? month-tooltip-class "rounded-xl"))
      (is (str/includes? month-tooltip-class "min-w-[188px]"))
      (is (= "translate3d(390px, 110px, 0px) translate(calc(-100% - 8px), -50%)"
             (aget (.-style month-tooltip-node) "transform"))))
    (fake-dom/dispatch-dom-event-with-payload! (:host day-runtime) "pointermove" #js {:clientX 390})
    (let [day-tooltip-node (find-dom-node-by-role (:host day-runtime) "portfolio-chart-hover-tooltip")
          day-tooltip-strings (set (fake-dom/collect-text-content day-tooltip-node))]
      (is (some? day-tooltip-node))
      (is (contains? day-tooltip-strings "PNL"))
      (is (contains? day-tooltip-strings "$203"))
      (is (= "translate3d(390px, 110px, 0px) translate(calc(-100% - 8px), -50%)"
             (aget (.-style day-tooltip-node) "transform")))
      (is (some #(re-matches #"[0-9]{2}:[0-9]{2}" %) day-tooltip-strings)))))

(deftest portfolio-view-returns-tooltip-runtime-renders-selected-benchmark-values-with-series-color-test
  (let [time-a (.getTime (js/Date. 2026 1 19 2 4 0))
        time-b (.getTime (js/Date. 2026 1 26 8 30 0))
        time-c (.getTime (js/Date. 2026 2 3 11 15 0))
        state (-> sample-state
                  (assoc-in [:portfolio-ui :summary-time-range] :month)
                  (assoc-in [:portfolio-ui :chart-tab] :returns)
                  (assoc-in [:portfolio-ui :returns-benchmark-coins] ["SPY"])
                  (assoc-in [:asset-selector :markets]
                            [{:coin "SPY"
                              :symbol "SPY"
                              :market-type :spot
                              :cache-order 1}])
                  (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                            [[time-a 0] [time-b 0] [time-c 0]])
                  (assoc-in [:portfolio :summary-by-key :month :accountValueHistory]
                            [[time-a 100] [time-b 110] [time-c 120]])
                  (assoc-in [:candles "SPY" :1h]
                            [{:t time-a :c 50}
                             {:t time-b :c 55}
                             {:t time-c :c 57}]))
        view-node (portfolio-view/portfolio-view state)
        host-node (find-first-node view-node #(= "portfolio-chart-d3-host" (get-in % [1 :data-role])))
        runtime (mount-d3-host! (get-in host-node [1 :replicant/on-render]))]
    (fake-dom/dispatch-dom-event-with-payload! (:host runtime) "pointermove" #js {:clientX 390})
    (let [tooltip-node (find-dom-node-by-role (:host runtime) "portfolio-chart-hover-tooltip")
          tooltip-strings (set (fake-dom/collect-text-content tooltip-node))
          benchmark-row (find-dom-node-by-role (:host runtime) "portfolio-chart-hover-tooltip-benchmark-row-SPY")
          benchmark-value (find-dom-node-by-role (:host runtime) "portfolio-chart-hover-tooltip-benchmark-value-SPY")]
      (is (some? tooltip-node))
      (is (contains? tooltip-strings "Returns"))
      (is (contains? tooltip-strings "SPY (SPOT)"))
      (is (contains? tooltip-strings "+14.00%"))
      (is (some? benchmark-row))
      (is (= "#f2cf66" (aget (.-style benchmark-value) "color"))))))
