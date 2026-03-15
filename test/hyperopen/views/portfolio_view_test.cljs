(ns hyperopen.views.portfolio-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.portfolio.vm :as portfolio-vm]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

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

(defn- find-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          child-matches (mapcat #(find-nodes % pred) children)]
      (if (pred node)
        (cons node child-matches)
        child-matches))

    (seq? node)
    (mapcat #(find-nodes % pred) node)

    :else []))

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

(defn- mount-d3-host!
  [on-render]
  (let [document (fake-dom/make-fake-document)
        host (fake-dom/make-fake-element "div")
        remembered* (atom nil)]
    (aset host "ownerDocument" document)
    (set! (.-clientWidth host) 400)
    (set! (.-clientHeight host) 220)
    (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                :replicant/node host
                :replicant/remember (fn [memory]
                                      (reset! remembered* memory))})
    {:host host
     :remembered remembered*}))

(defn- find-dom-node-by-role
  [root data-role]
  (fake-dom/find-dom-node root #(and (= 1 (.-nodeType %))
                                     (= data-role (.getAttribute % "data-role")))))

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
        balances-view-node (portfolio-view/portfolio-view (assoc-in sample-state
                                                                    [:portfolio-ui :account-info-tab]
                                                                    :balances))
        root-node (find-first-node view-node #(= "portfolio-root" (get-in % [1 :data-parity-id])))
        actions-row (find-first-node view-node #(= "portfolio-actions-row" (get-in % [1 :data-role])))
        volume-card (find-first-node view-node #(= "portfolio-14d-volume-card" (get-in % [1 :data-role])))
        fees-card (find-first-node view-node #(= "portfolio-fees-card" (get-in % [1 :data-role])))
        summary-card (find-first-node view-node #(= "portfolio-account-summary-card" (get-in % [1 :data-role])))
        scope-selector (find-first-node view-node #(= "portfolio-summary-scope-selector" (get-in % [1 :data-role])))
        time-range-selector (find-first-node view-node #(= "portfolio-summary-time-range-selector" (get-in % [1 :data-role])))
        chart-account-value-tab (find-first-node view-node #(= "portfolio-chart-tab-account-value" (get-in % [1 :data-role])))
        chart-pnl-tab (find-first-node view-node #(= "portfolio-chart-tab-pnl" (get-in % [1 :data-role])))
        chart-returns-tab (find-first-node view-node #(= "portfolio-chart-tab-returns" (get-in % [1 :data-role])))
        chart-shell (find-first-node view-node #(= "portfolio-chart-shell" (get-in % [1 :data-role])))
        chart-host (find-first-node view-node #(= "portfolio-chart-d3-host" (get-in % [1 :data-role])))
        background-status (find-first-node view-node #(= "portfolio-background-status" (get-in % [1 :data-role])))
        account-table (find-first-node view-node #(= "portfolio-account-table" (get-in % [1 :data-role])))
        performance-tab-button (find-first-node
                                view-node
                                #(= [[:actions/set-portfolio-account-info-tab :performance-metrics]]
                                    (get-in % [1 :on :click])))
        balances-tab-button (find-first-node
                             view-node
                             #(= [[:actions/set-portfolio-account-info-tab :balances]
                                  [:actions/select-account-info-tab :balances]]
                                 (get-in % [1 :on :click])))
        balances-view-performance-tab-button (find-first-node
                                              balances-view-node
                                              #(= [[:actions/set-portfolio-account-info-tab :performance-metrics]]
                                                  (get-in % [1 :on :click])))
        balances-view-balances-tab-button (find-first-node
                                           balances-view-node
                                           #(= [[:actions/set-portfolio-account-info-tab :balances]
                                                [:actions/select-account-info-tab :balances]]
                                               (get-in % [1 :on :click])))
        portfolio-tab-buttons (find-nodes
                               view-node
                               (fn [node]
                                 (= :actions/set-portfolio-account-info-tab
                                    (some-> (get-in node [1 :on :click])
                                            first
                                            first))))
        portfolio-tab-labels (mapv #(first (collect-strings %)) portfolio-tab-buttons)
        performance-metrics-card (find-first-node view-node #(= "portfolio-performance-metrics-card" (get-in % [1 :data-role])))
        balances-performance-metrics-card (find-first-node balances-view-node #(= "portfolio-performance-metrics-card" (get-in % [1 :data-role])))
        performance-metric-row (find-first-node view-node #(= "portfolio-performance-metric-cumulative-return" (get-in % [1 :data-role])))
        metrics-time-range-selector (find-first-node view-node #(= "portfolio-performance-metrics-time-range-selector" (get-in % [1 :data-role])))
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
    (is (some? scope-selector))
    (is (some? time-range-selector))
    (is (some? chart-account-value-tab))
    (is (some? chart-pnl-tab))
    (is (some? chart-returns-tab))
    (is (some? chart-shell))
    (is (some? chart-host))
    (is (nil? background-status))
    (is (fn? (get-in chart-host [1 :replicant/on-render])))
    (is (some? account-table))
    (is (some? performance-tab-button))
    (is (some? balances-tab-button))
    (is (= "Performance Metrics" (first portfolio-tab-labels)))
    (is (str/starts-with? (or (second portfolio-tab-labels) "") "Balances"))
    (is (contains? (set (class-values performance-tab-button)) "account-info-tab-button-active"))
    (is (contains? (set (class-values balances-tab-button)) "account-info-tab-button-inactive"))
    (is (some? balances-view-performance-tab-button))
    (is (some? balances-view-balances-tab-button))
    (is (some? performance-metrics-card))
    (is (nil? balances-performance-metrics-card))
    (is (some? metrics-time-range-selector))
    (is (some? metrics-time-range-button))
    (is (contains? (set (class-values balances-view-performance-tab-button)) "account-info-tab-button-inactive"))
    (is (contains? (set (class-values balances-view-balances-tab-button)) "account-info-tab-button-active"))
    (is (contains? (set (class-values performance-metric-row)) "hover:bg-base-300"))
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
    (is (contains? all-text "Interest"))
    (is (contains? all-text "Deposits & Withdrawals"))
    (is (contains? all-text "Vault Equity"))
    (is (contains? all-text "Staking Account"))
    (is (some #(str/includes? % "Open Orders") all-text))))

(deftest portfolio-view-renders-background-status-banner-when-pending-work-exists-test
  (with-redefs [portfolio-vm/portfolio-vm (fn [_]
                                             {:volume-14d-usd 0
                                              :fees {:taker 0 :maker 0}
                                              :background-status {:visible? true
                                                                  :title "Portfolio analytics are still syncing"
                                                                  :detail "The chart is ready. The remaining analytics will fill in automatically."
                                                                  :items [{:id :benchmark-history
                                                                           :label "Benchmark history"}
                                                                          {:id :performance-metrics
                                                                           :label "Performance metrics"}]}
                                              :chart {:selected-tab :returns
                                                      :axis-kind :percent
                                                      :tabs [{:value :returns :label "Returns"}
                                                             {:value :account-value :label "Account Value"}
                                                             {:value :pnl :label "PNL"}]
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
                                                          :performance-metrics-time-range {:value :month
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
                                              :performance-metrics {:loading? false
                                                                    :benchmark-selected? false
                                                                    :benchmark-columns []
                                                                    :values {}
                                                                    :groups []}})
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
          banner-node (find-first-node view-node #(= "portfolio-background-status" (get-in % [1 :data-role])))
          benchmark-item (find-first-node view-node #(= "portfolio-background-status-item-benchmark-history" (get-in % [1 :data-role])))
          metrics-item (find-first-node view-node #(= "portfolio-background-status-item-performance-metrics" (get-in % [1 :data-role])))
          banner-strings (set (collect-strings banner-node))]
      (is (some? banner-node))
      (is (contains? banner-strings "Portfolio analytics are still syncing"))
      (is (contains? banner-strings "The chart is ready. The remaining analytics will fill in automatically."))
      (is (= "Benchmark history" (first (collect-strings benchmark-item))))
      (is (= "Performance metrics" (first (collect-strings metrics-item)))))))

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

(deftest portfolio-view-funding-actions-pass-explicit-anchor-bounds-test
  (let [view-node (portfolio-view/portfolio-view sample-state)
        deposit-button (find-first-node view-node
                                        #(= [[:actions/open-funding-deposit-modal :event.currentTarget/bounds]]
                                            (get-in % [1 :on :click])))
        transfer-button (find-first-node view-node
                                         #(= [[:actions/open-funding-transfer-modal :event.currentTarget/bounds]]
                                             (get-in % [1 :on :click])))
        withdraw-button (find-first-node view-node
                                         #(= [[:actions/open-funding-withdraw-modal :event.currentTarget/bounds]]
                                             (get-in % [1 :on :click])))]
    (is (some? deposit-button))
    (is (some? transfer-button))
    (is (some? withdraw-button))))

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

(deftest portfolio-view-performance-metrics-loading-overlay-renders-explainer-copy-test
  (with-redefs [portfolio-vm/portfolio-vm (fn [_]
                                             {:volume-14d-usd 0
                                              :fees {:taker 0 :maker 0}
                                              :background-status {:visible? false
                                                                  :items []}
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
                                                          :performance-metrics-time-range {:value :month
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
                                              :performance-metrics {:loading? true
                                                                    :benchmark-selected? false
                                                                    :benchmark-columns []
                                                                    :values {}
                                                                    :groups []}})
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
          overlay-node (find-first-node view-node #(= "portfolio-performance-metrics-loading-overlay" (get-in % [1 :data-role])))
          overlay-strings (set (collect-strings overlay-node))]
      (is (some? overlay-node))
      (is (= "status" (get-in overlay-node [1 :role])))
      (is (contains? overlay-strings "Calculating performance metrics"))
      (is (contains? overlay-strings "Returns stay visible while the remaining analytics finish in the background.")))))

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
      (is (str/includes? month-tooltip-class "min-w-[188px]")))
    (fake-dom/dispatch-dom-event-with-payload! (:host day-runtime) "pointermove" #js {:clientX 390})
    (let [day-tooltip-node (find-dom-node-by-role (:host day-runtime) "portfolio-chart-hover-tooltip")
          day-tooltip-strings (set (fake-dom/collect-text-content day-tooltip-node))]
      (is (some? day-tooltip-node))
      (is (contains? day-tooltip-strings "PNL"))
      (is (contains? day-tooltip-strings "$203"))
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
