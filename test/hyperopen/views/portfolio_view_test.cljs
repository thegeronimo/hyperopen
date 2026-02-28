(ns hyperopen.views.portfolio-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.portfolio.vm :as portfolio-vm]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- count-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          self-count (if (pred node) 1 0)]
      (+ self-count
         (reduce + 0 (map #(count-nodes % pred) children))))

    (seq? node)
    (reduce + 0 (map #(count-nodes % pred) node))

    :else 0))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- class-values [node]
  (let [class-attr (get-in node [1 :class])]
    (cond
      (vector? class-attr) class-attr
      (seq? class-attr) (vec class-attr)
      (string? class-attr) (str/split class-attr #"\s+")
      :else [])))

(defn- px-width [value]
  (some->> value
           (re-matches #"^([0-9]+)px$")
           second
           (#(js/Number.parseInt % 10))))

(def sample-state
  {:account {:mode :classic}
   :portfolio-ui {:summary-scope :all
                  :summary-time-range :month
                  :chart-tab :account-value
                  :summary-scope-dropdown-open? false
                  :summary-time-range-dropdown-open? false
                  :performance-metrics-time-range-dropdown-open? false}
   :portfolio {:summary-by-key {:month {:pnlHistory [[1 10] [2 15]]
                                        :accountValueHistory [[1 100] [2 100]]
                                        :vlm 2255561.85}}
               :user-fees {:userCrossRate 0.00045
                           :userAddRate 0.00015
                           :dailyUserVlm [{:exchange 100
                                           :userCross 70
                                           :userAdd 30}
                                          {:exchange 50
                                           :userCross 20
                                           :userAdd 10}]}}
   :account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort {:column nil :direction :asc}
                  :positions-sort {:column nil :direction :asc}
                  :open-orders-sort {:column "Time" :direction :desc}}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :fills [{:time (.now js/Date)
                     :sz "2"
                     :px "100"}]
            :fundings []
            :order-history []}
   :webdata2 {}
   :borrow-lend {:total-supplied-usd 0}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}})

(deftest portfolio-view-renders-phase1-layout-sections-test
  (let [view-node (portfolio-view/portfolio-view sample-state)
        root-node (find-first-node view-node #(= "portfolio-root" (get-in % [1 :data-parity-id])))
        actions-row (find-first-node view-node #(= "portfolio-actions-row" (get-in % [1 :data-role])))
        volume-card (find-first-node view-node #(= "portfolio-14d-volume-card" (get-in % [1 :data-role])))
        fees-card (find-first-node view-node #(= "portfolio-fees-card" (get-in % [1 :data-role])))
        summary-card (find-first-node view-node #(= "portfolio-account-summary-card" (get-in % [1 :data-role])))
        performance-metrics-card (find-first-node view-node #(= "portfolio-performance-metrics-card" (get-in % [1 :data-role])))
        scope-selector (find-first-node view-node #(= "portfolio-summary-scope-selector" (get-in % [1 :data-role])))
        time-range-selector (find-first-node view-node #(= "portfolio-summary-time-range-selector" (get-in % [1 :data-role])))
        metrics-time-range-selector (find-first-node view-node #(= "portfolio-performance-metrics-time-range-selector" (get-in % [1 :data-role])))
        chart-account-value-tab (find-first-node view-node #(= "portfolio-chart-tab-account-value" (get-in % [1 :data-role])))
        chart-pnl-tab (find-first-node view-node #(= "portfolio-chart-tab-pnl" (get-in % [1 :data-role])))
        chart-returns-tab (find-first-node view-node #(= "portfolio-chart-tab-returns" (get-in % [1 :data-role])))
        chart-shell (find-first-node view-node #(= "portfolio-chart-shell" (get-in % [1 :data-role])))
        chart-path (find-first-node view-node #(= "portfolio-chart-path" (get-in % [1 :data-role])))
        account-table (find-first-node view-node #(= "portfolio-account-table" (get-in % [1 :data-role])))
        performance-metric-row (find-first-node view-node #(= "portfolio-performance-metric-cumulative-return" (get-in % [1 :data-role])))
        performance-tab-button (find-first-node
                                view-node
                                #(= [[:actions/set-portfolio-account-info-tab :performance-metrics]]
                                    (get-in % [1 :on :click])))
        balances-tab-button (find-first-node
                             view-node
                             #(= [[:actions/set-portfolio-account-info-tab :balances]
                                  [:actions/select-account-info-tab :balances]]
                                 (get-in % [1 :on :click])))
        metrics-time-range-button (find-first-node
                                   view-node
                                   #(= [[:actions/toggle-portfolio-performance-metrics-time-range-dropdown]]
                                       (get-in % [1 :on :click])))
        metrics-scroll-node (find-first-node performance-metrics-card
                                             (fn [node]
                                               (contains? (set (class-values node))
                                                          "overflow-y-auto")))
        all-text (set (collect-strings view-node))]
    (is (some? root-node))
    (is (some? actions-row))
    (is (some? volume-card))
    (is (some? fees-card))
    (is (some? summary-card))
    (is (some? performance-metrics-card))
    (is (some? scope-selector))
    (is (some? time-range-selector))
    (is (some? metrics-time-range-selector))
    (is (some? chart-account-value-tab))
    (is (some? chart-pnl-tab))
    (is (some? chart-returns-tab))
    (is (some? chart-shell))
    (is (some? chart-path))
    (is (= "round" (get-in chart-path [1 :stroke-linecap])))
    (is (= "round" (get-in chart-path [1 :stroke-linejoin])))
    (is (some? account-table))
    (is (contains? (set (class-values performance-metric-row)) "hover:bg-base-300"))
    (is (some? performance-tab-button))
    (is (some? balances-tab-button))
    (is (some? metrics-time-range-button))
    (is (contains? (set (class-values performance-tab-button)) "border-primary"))
    (is (contains? (set (class-values balances-tab-button)) "border-transparent"))
    (is (some? metrics-scroll-node))
    (is (contains? (set (class-values metrics-scroll-node)) "flex-1"))
    (is (contains? all-text "Portfolio"))
    (is (contains? all-text "14 Day Volume"))
    (is (contains? all-text "Fees (Taker / Maker)"))
    (is (contains? all-text "Perps + Spot + Vaults"))
    (is (contains? all-text "30D"))
    (is (contains? all-text "3M"))
    (is (contains? all-text "6M"))
    (is (contains? all-text "1Y"))
    (is (contains? all-text "2Y"))
    (is (contains? all-text "Account Value"))
    (is (contains? all-text "PNL"))
    (is (contains? all-text "Returns"))
    (is (contains? all-text "Performance Metrics"))
    (is (contains? all-text "Cumulative Return"))
    (is (not (contains? all-text "Time in Market")))
    (is (not (contains? all-text "All-time (ann.)")))
    (is (contains? all-text "Max Drawdown"))
    (is (contains? all-text "Vault Equity"))
    (is (contains? all-text "Staking Account"))
    (is (some #(str/includes? % "Open Orders") all-text))))

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
        benchmark-path-node (find-first-node view-node #(= "portfolio-chart-path-benchmark-0" (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))
        chip-border-color (get-in chip-node [1 :style :border-color])]
    (is (some? selector-node))
    (is (= "Search benchmarks and press Enter" (get-in benchmark-search-input [1 :placeholder])))
    (is (some? chip-rail-node))
    (is (some? chip-node))
    (is (some? legend-node))
    (is (= 1 legend-count))
    (is (some? benchmark-path-node))
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

(deftest portfolio-view-performance-metrics-renders-formatting-and-fallbacks-test
  (with-redefs [portfolio-vm/portfolio-vm (fn [_]
                                             {:volume-14d-usd 0
                                              :fees {:taker 0 :maker 0}
                                              :chart {:selected-tab :pnl
                                                      :axis-kind :number
                                                      :tabs [{:value :account-value :label "Account Value"}
                                                             {:value :pnl :label "PNL"}
                                                             {:value :returns :label "Returns"}]
                                                      :points [{:time-ms 1 :value 0 :x-ratio 0 :y-ratio 1}
                                                               {:time-ms 2 :value 10 :x-ratio 1 :y-ratio 0}]
                                                      :path "M 0 100 L 100 0"
                                                      :series [{:id :strategy
                                                                :label "Portfolio"
                                                                :stroke "#f5f7f8"
                                                                :has-data? true
                                                                :points [{:time-ms 1 :value 0 :x-ratio 0 :y-ratio 1}
                                                                         {:time-ms 2 :value 10 :x-ratio 1 :y-ratio 0}]
                                                                :path "M 0 100 L 100 0"}]
                                                      :y-ticks [{:value 10 :y-ratio 0}
                                                                {:value 6 :y-ratio (/ 1 3)}
                                                                {:value 3 :y-ratio (/ 2 3)}
                                                                {:value 0 :y-ratio 1}]
                                                      :has-data? true}
                                              :selectors {:summary-scope {:value :all
                                                                          :label "Perps + Spot + Vaults"
                                                                          :open? false
                                                                          :options [{:value :all :label "Perps + Spot + Vaults"}]}
                                                          :summary-time-range {:value :month
                                                                               :label "30D"
                                                                               :open? false
                                                                               :options [{:value :month :label "30D"}]}
                                                          :returns-benchmark {:selected-coins []
                                                                              :selected-options []
                                                                              :coin-search ""
                                                                              :suggestions-open? false
                                                                              :candidates []
                                                                              :top-coin nil
                                                                              :empty-message nil
                                                                              :label-by-coin {}}}
                                              :summary {:selected-key :month
                                                        :pnl 0
                                                        :volume 0
                                                        :max-drawdown-pct nil
                                                        :total-equity 0
                                                        :show-perps-account-equity? false
                                                        :perps-account-equity 0
                                                        :spot-equity-label "Spot Account Equity"
                                                        :spot-account-equity 0
                                                        :show-vault-equity? false
                                                        :vault-equity 0
                                                        :show-earn-balance? false
                                                        :earn-balance 0
                                                        :show-staking-account? false
                                                        :staking-account-hype 0}
                                              :performance-metrics {:benchmark-selected? true
                                                                    :benchmark-coin "SPY"
                                                                    :benchmark-label "SPY (SPOT)"
                                                                    :benchmark-columns [{:coin "SPY"
                                                                                         :label "SPY (SPOT)"}
                                                                                        {:coin "QQQ"
                                                                                         :label "QQQ (SPOT)"}]
                                                                    :values {}
                                                                    :groups [{:id :sample
                                                                              :rows [{:key :expected-monthly
                                                                                      :label "Expected Monthly"
                                                                                      :kind :percent
                                                                                      :value 0.123
                                                                                      :benchmark-values {"SPY" 0.111
                                                                                                         "QQQ" 0.101}}
                                                                                     {:key :daily-var
                                                                                      :label "Daily Value-at-Risk"
                                                                                      :kind :percent
                                                                                      :value -0.045
                                                                                      :benchmark-values {"SPY" -0.033
                                                                                                         "QQQ" -0.022}}
                                                                                     {:key :information-ratio
                                                                                      :label "Information Ratio"
                                                                                      :kind :ratio
                                                                                      :value 1.2345}
                                                                                     {:key :max-dd-date
                                                                                      :label "Max DD Date"
                                                                                      :kind :date
                                                                                      :value "2024-01-02"}
                                                                                     {:key :max-consecutive-wins
                                                                                      :label "Max Consecutive Wins"
                                                                                      :kind :integer
                                                                                      :value 7}
                                                                                     {:key :r2
                                                                                      :label "R^2"
                                                                                      :kind :ratio
                                                                                      :value nil}]}]}})
                account-info-view/account-info-view (fn
                                                      ([_]
                                                       [:div {:data-role "stub-account-info"}])
                                                      ([_ {:keys [extra-tabs]}]
                                                       (or (some (fn [{:keys [id content]}]
                                                                   (when (= id :performance-metrics)
                                                                     content))
                                                                 extra-tabs)
                                                           [:div {:data-role "stub-account-info"}])))]
    (let [view-node (portfolio-view/portfolio-view {})
          all-text (set (collect-strings view-node))
          benchmark-label (find-first-node view-node #(= "portfolio-performance-metrics-benchmark-label" (get-in % [1 :data-role])))
          benchmark-label-qqq (find-first-node view-node #(= "portfolio-performance-metrics-benchmark-label-QQQ" (get-in % [1 :data-role])))
          nil-row (find-first-node view-node #(= "portfolio-performance-metric-r2" (get-in % [1 :data-role])))]
      (is (contains? all-text "Metric"))
      (is (contains? all-text "Portfolio"))
      (is (= "SPY (SPOT)" (first (collect-strings benchmark-label))))
      (is (= "QQQ (SPOT)" (first (collect-strings benchmark-label-qqq))))
      (is (contains? all-text "+12.30%"))
      (is (contains? all-text "+11.10%"))
      (is (contains? all-text "+10.10%"))
      (is (contains? all-text "-4.50%"))
      (is (contains? all-text "-3.30%"))
      (is (contains? all-text "-2.20%"))
      (is (contains? all-text "1.23"))
      (is (contains? all-text "2024-01-02"))
      (is (contains? all-text "7"))
      (is (nil? nil-row)))))

(deftest portfolio-view-chart-plot-area-wires-hover-actions-test
  (let [view-node (portfolio-view/portfolio-view sample-state)
        plot-area-node (find-first-node view-node #(= "portfolio-chart-plot-area" (get-in % [1 :data-role])))
        mousemove-action (get-in plot-area-node [1 :on :mousemove])
        mouseenter-action (get-in plot-area-node [1 :on :mouseenter])
        pointermove-action (get-in plot-area-node [1 :on :pointermove])
        pointerenter-action (get-in plot-area-node [1 :on :pointerenter])
        mouseleave-action (get-in plot-area-node [1 :on :mouseleave])]
    (is (some? plot-area-node))
    (is (= [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] 2]]
           mousemove-action))
    (is (= [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] 2]]
           mouseenter-action))
    (is (= [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] 2]]
           pointermove-action))
    (is (= [[:actions/set-portfolio-chart-hover [:event/clientX] [:event.currentTarget/bounds] 2]]
           pointerenter-action))
    (is (= [[:actions/clear-portfolio-chart-hover]]
           mouseleave-action))))

(deftest portfolio-view-chart-hover-overlay-renders-date-and-time-tooltip-variants-test
  (let [time-a (.getTime (js/Date. 2026 1 19 2 4 0))
        time-b (.getTime (js/Date. 2026 1 26 8 30 0))
        base-state (-> sample-state
                       (assoc-in [:portfolio-ui :chart-tab] :pnl)
                       (assoc-in [:portfolio-ui :chart-hover-index] 1)
                       (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                                 [[time-a 0] [time-b 203]]))
        month-view-node (portfolio-view/portfolio-view base-state)
        month-hover-line (find-first-node month-view-node #(= "portfolio-chart-hover-line" (get-in % [1 :data-role])))
        month-tooltip-node (find-first-node month-view-node #(= "portfolio-chart-hover-tooltip" (get-in % [1 :data-role])))
        month-tooltip-strings (set (collect-strings month-tooltip-node))
        month-tooltip-classes (set (class-values month-tooltip-node))
        day-state (assoc-in base-state [:portfolio-ui :summary-time-range] :day)
        day-view-node (portfolio-view/portfolio-view day-state)
        day-tooltip-node (find-first-node day-view-node #(= "portfolio-chart-hover-tooltip" (get-in % [1 :data-role])))
        day-tooltip-strings (set (collect-strings day-tooltip-node))]
    (is (some? month-hover-line))
    (is (some? month-tooltip-node))
    (is (contains? month-tooltip-strings "2026 Feb 26"))
    (is (contains? month-tooltip-strings "PNL"))
    (is (contains? month-tooltip-strings "$203"))
    (is (contains? month-tooltip-classes "rounded-xl"))
    (is (contains? month-tooltip-classes "min-w-[188px]"))
    (is (some? day-tooltip-node))
    (is (contains? day-tooltip-strings "PNL"))
    (is (contains? day-tooltip-strings "$203"))
    (is (some #(re-matches #"[0-9]{2}:[0-9]{2}" %) day-tooltip-strings))))

(deftest portfolio-view-returns-tooltip-renders-selected-benchmark-values-with-series-color-test
  (let [time-a (.getTime (js/Date. 2026 1 19 2 4 0))
        time-b (.getTime (js/Date. 2026 1 26 8 30 0))
        time-c (.getTime (js/Date. 2026 2 3 11 15 0))
        state (-> sample-state
                  (assoc-in [:portfolio-ui :summary-time-range] :month)
                  (assoc-in [:portfolio-ui :chart-tab] :returns)
                  (assoc-in [:portfolio-ui :chart-hover-index] 2)
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
        tooltip-node (find-first-node view-node #(= "portfolio-chart-hover-tooltip" (get-in % [1 :data-role])))
        tooltip-strings (set (collect-strings tooltip-node))
        benchmark-row (find-first-node view-node #(= "portfolio-chart-hover-tooltip-benchmark-row-SPY" (get-in % [1 :data-role])))
        benchmark-value (find-first-node view-node #(= "portfolio-chart-hover-tooltip-benchmark-value-SPY" (get-in % [1 :data-role])))]
    (is (some? tooltip-node))
    (is (contains? tooltip-strings "Returns"))
    (is (contains? tooltip-strings "SPY (SPOT)"))
    (is (contains? tooltip-strings "+14.00%"))
    (is (some? benchmark-row))
    (is (= "#f2cf66" (get-in benchmark-value [1 :style :color])))))
