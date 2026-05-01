(ns hyperopen.views.portfolio-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.chart.d3.hover-state :as chart-hover-state]
            [hyperopen.views.portfolio.test-support :refer [button-with-text
                                                            class-values
                                                            collect-strings
                                                            find-first-node
                                                            find-nodes
                                                            sample-state]]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(use-fixtures :each
  (fn [f]
    (chart-hover-state/clear-hover-state!)
    (f)
    (chart-hover-state/clear-hover-state!)))

(def ^:private trader-route-address
  "0x3333333333333333333333333333333333333333")

(defn- trader-portfolio-state
  [selected-tab]
  (-> sample-state
      (assoc :router {:path (str "/portfolio/trader/" trader-route-address)})
      (assoc-in [:portfolio-ui :account-info-tab] selected-tab)
      (assoc-in [:account-info :selected-tab] selected-tab)
      (assoc-in [:leaderboard :rows]
                [{:eth-address trader-route-address
                  :display-name "Gamma"}])))

(defn- trader-balances-state []
  (-> (trader-portfolio-state :balances)
      (assoc :asset-selector {:market-by-key {"spot:MEOW/USDC" {:coin "MEOW/USDC"
                                                                :mark 0.02}}})
      (assoc :spot {:meta {:tokens [{:index 0 :name "USDC" :weiDecimals 6}
                                    {:index 1 :name "MEOW" :weiDecimals 6}]
                           :universe [{:name "MEOW/USDC"
                                       :tokens [1 0]
                                       :index 0}]}
                    :clearinghouse-state {:balances [{:coin "MEOW"
                                                      :token 1
                                                      :hold "0.0"
                                                      :total "2.0"
                                                      :entryNtl "0.03"}]}})))

(defn- trader-positions-state []
  (-> (trader-portfolio-state :positions)
      (assoc :webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}})))

(defn- portfolio-positions-state []
  (-> sample-state
      (assoc :router {:path "/portfolio"})
      (assoc-in [:portfolio-ui :account-info-tab] :positions)
      (assoc-in [:account-info :selected-tab] :positions)
      (assoc :webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}})))

(defn- trader-open-orders-state []
  (-> (trader-portfolio-state :open-orders)
      (assoc-in [:orders :open-orders]
                [{:coin "BTC"
                  :oid 101
                  :side "B"
                  :sz "1.0"
                  :origSz "1.0"
                  :limitPx "100.0"
                  :orderType "Limit"
                  :timestamp 1700000000000
                  :reduceOnly false
                  :isTrigger false
                  :isPositionTpsl false}])
      (assoc :asset-selector {:market-by-key {"perp:BTC" {:coin "BTC"
                                                          :symbol "BTC"}}})))

(defn- trader-twap-state []
  (-> (trader-portfolio-state :twap)
      (assoc :orders {:open-orders []
                      :open-orders-snapshot []
                      :open-orders-snapshot-by-dex {}
                      :fills [{:time (.now js/Date)
                               :sz "2"
                               :px "100"}]
                      :order-history []
                      :twap-states [[17 {:coin "xyz:CL"
                                         :side "B"
                                         :sz "1.0"
                                         :executedSz "0.4"
                                         :executedNtl "40.0"
                                         :minutes 30
                                         :timestamp 1700000000000
                                         :reduceOnly false}]]})))

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
        summary-scope-trigger (find-first-node view-node #(= "portfolio-summary-scope-selector-trigger" (get-in % [1 :data-role])))
        summary-time-range-trigger (find-first-node view-node #(= "portfolio-summary-time-range-selector-trigger" (get-in % [1 :data-role])))
        metrics-time-range-trigger (find-first-node view-node #(= "portfolio-performance-metrics-time-range-selector-trigger" (get-in % [1 :data-role])))
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
        portfolio-action-buttons (into {}
                                      (for [role ["portfolio-action-link-staking"
                                                  "portfolio-action-perps-spot"
                                                  "portfolio-action-withdraw"
                                                  "portfolio-action-deposit"]]
                                        [role (find-first-node view-node #(= role (get-in % [1 :data-role])))]))
        removed-portfolio-action-buttons (into {}
                                               (for [role ["portfolio-action-swap-stablecoins"
                                                           "portfolio-action-evm-core"
                                                           "portfolio-action-portfolio-margin"
                                                           "portfolio-action-send"]]
                                                 [role (find-first-node view-node #(= role (get-in % [1 :data-role])))]))
        account-tables-panel (find-first-node view-node #(= "account-tables" (get-in % [1 :data-parity-id])))
        balances-account-tables-panel (find-first-node balances-view-node #(= "account-tables" (get-in % [1 :data-parity-id])))
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
    (is (contains? (set (class-values root-node)) "w-full"))
    (is (contains? (set (class-values root-node)) "app-shell-gutter"))
    (is (not (contains? (set (class-values root-node)) "flex-1")))
    (is (not (contains? (set (class-values root-node)) "min-h-0")))
    (is (not (contains? (set (class-values root-node)) "overflow-y-auto")))
    (is (= "3.5rem" (get-in root-node [1 :style :padding-bottom])))
    (is (some? actions-row))
    (is (some? volume-card))
    (is (some? fees-card))
    (is (some? summary-card))
    (is (some? scope-selector))
    (is (some? time-range-selector))
    (is (some? summary-scope-trigger))
    (is (false? (get-in summary-scope-trigger [1 :aria-expanded])))
    (is (some? summary-time-range-trigger))
    (is (false? (get-in summary-time-range-trigger [1 :aria-expanded])))
    (is (some? metrics-time-range-trigger))
    (is (false? (get-in metrics-time-range-trigger [1 :aria-expanded])))
    (is (some? chart-account-value-tab))
    (is (some? chart-pnl-tab))
    (is (some? chart-returns-tab))
    (is (some? chart-shell))
    (is (some? chart-host))
    (is (nil? (find-first-node root-node #(= :<> (first %)))))
    (is (nil? background-status))
    (is (fn? (get-in chart-host [1 :replicant/on-render])))
    (is (some? account-table))
    (is (not (contains? (set (class-values account-table)) "mb-2")))
    (is (not (contains? (set (class-values account-table)) "lg:mb-3")))
    (is (some? performance-tab-button))
    (is (some? balances-tab-button))
    (is (every? some? (vals portfolio-action-buttons)))
    (is (every? nil? (vals removed-portfolio-action-buttons)))
    (is (= [[:actions/navigate "/staking"]]
           (get-in (get portfolio-action-buttons "portfolio-action-link-staking") [1 :on :click])))
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "portfolio-action-perps-spot"]]
           (get-in (get portfolio-action-buttons "portfolio-action-perps-spot") [1 :on :click])))
    (is (= [[:actions/open-funding-withdraw-modal
             :event.currentTarget/bounds
             "portfolio-action-withdraw"]]
           (get-in (get portfolio-action-buttons "portfolio-action-withdraw") [1 :on :click])))
    (is (= [[:actions/open-funding-deposit-modal
             :event.currentTarget/bounds
             "portfolio-action-deposit"]]
           (get-in (get portfolio-action-buttons "portfolio-action-deposit") [1 :on :click])))
    (is (= "Performance Metrics" (first portfolio-tab-labels)))
    (is (str/starts-with? (or (second portfolio-tab-labels) "") "Balances"))
    (is (contains? (set (class-values account-tables-panel)) "min-h-0"))
    (is (not (contains? (set (class-values account-tables-panel)) "h-96")))
    (is (= "min(44rem, calc(100dvh - 24rem))"
           (get-in account-tables-panel [1 :style :height])))
    (is (= "min(44rem, calc(100dvh - 24rem))"
           (get-in account-tables-panel [1 :style :max-height])))
    (is (contains? (set (class-values balances-account-tables-panel)) "min-h-0"))
    (is (not (contains? (set (class-values balances-account-tables-panel)) "h-96")))
    (is (= "min(44rem, calc(100dvh - 24rem))"
           (get-in balances-account-tables-panel [1 :style :height])))
    (is (= "min(44rem, calc(100dvh - 24rem))"
           (get-in balances-account-tables-panel [1 :style :max-height])))
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

(deftest portfolio-view-optimizer-route-uses-dark-route-frame-test
  (let [view-node (portfolio-view/portfolio-view
                   (assoc sample-state :router {:path "/portfolio/optimize/draft"}))
        root-node (find-first-node view-node #(= "portfolio-root" (get-in % [1 :data-parity-id])))
        scenario-surface (find-first-node view-node #(= "portfolio-optimizer-scenario-detail-surface"
                                                        (get-in % [1 :data-role])))]
    (is (some? root-node))
    (is (= "portfolio-optimizer-route-frame" (get-in root-node [1 :data-role])))
    (is (contains? (set (class-values root-node)) "portfolio-optimizer-v4"))
    (is (contains? (set (class-values root-node)) "w-full"))
    (is (not (contains? (set (class-values root-node)) "app-shell-gutter")))
    (is (not (contains? (set (class-values root-node)) "py-4")))
    (is (= "var(--optimizer-bg)" (get-in root-node [1 :style :background-color])))
    (is (= "calc(100vh - 3.5rem)" (get-in root-node [1 :style :min-height])))
    (is (= "3.5rem" (get-in root-node [1 :style :padding-bottom])))
    (is (nil? (get-in root-node [1 :style :background-image])))
    (is (some? scenario-surface))))

(deftest portfolio-view-exposes-route-local-summary-selector-hooks-test
  (let [state (-> sample-state
                  (assoc-in [:portfolio-ui :summary-scope-dropdown-open?] true)
                  (assoc-in [:portfolio-ui :summary-time-range-dropdown-open?] true)
                  (assoc-in [:portfolio-ui :performance-metrics-time-range-dropdown-open?] true))
        view-node (portfolio-view/portfolio-view state)
        summary-scope-trigger (find-first-node view-node #(= "portfolio-summary-scope-selector-trigger" (get-in % [1 :data-role])))
        summary-scope-all-option (find-first-node view-node #(= "portfolio-summary-scope-selector-option-all" (get-in % [1 :data-role])))
        summary-scope-perps-option (find-first-node view-node #(= "portfolio-summary-scope-selector-option-perps" (get-in % [1 :data-role])))
        summary-time-range-trigger (find-first-node view-node #(= "portfolio-summary-time-range-selector-trigger" (get-in % [1 :data-role])))
        summary-time-range-month-option (find-first-node view-node #(= "portfolio-summary-time-range-selector-option-month" (get-in % [1 :data-role])))
        summary-time-range-day-option (find-first-node view-node #(= "portfolio-summary-time-range-selector-option-day" (get-in % [1 :data-role])))
        performance-time-range-trigger (find-first-node view-node #(= "portfolio-performance-metrics-time-range-selector-trigger" (get-in % [1 :data-role])))
        performance-time-range-month-option (find-first-node view-node #(= "portfolio-performance-metrics-time-range-selector-option-month" (get-in % [1 :data-role])))]
    (is (some? summary-scope-trigger))
    (is (true? (get-in summary-scope-trigger [1 :aria-expanded])))
    (is (= "portfolio-summary-scope-selector-trigger" (get-in summary-scope-trigger [1 :data-role])))
    (is (some? summary-scope-all-option))
    (is (true? (get-in summary-scope-all-option [1 :aria-pressed])))
    (is (some? summary-scope-perps-option))
    (is (false? (get-in summary-scope-perps-option [1 :aria-pressed])))
    (is (some? summary-time-range-trigger))
    (is (true? (get-in summary-time-range-trigger [1 :aria-expanded])))
    (is (some? summary-time-range-month-option))
    (is (true? (get-in summary-time-range-month-option [1 :aria-pressed])))
    (is (some? summary-time-range-day-option))
    (is (false? (get-in summary-time-range-day-option [1 :aria-pressed])))
    (is (some? performance-time-range-trigger))
    (is (true? (get-in performance-time-range-trigger [1 :aria-expanded])))
    (is (some? performance-time-range-month-option))
    (is (true? (get-in performance-time-range-month-option [1 :aria-pressed])))))

(deftest portfolio-view-renders-trader-inspection-header-and-hides-mutation-tabs-test
  (let [view-node (portfolio-view/portfolio-view
                   (-> sample-state
                       (assoc :router {:path (str "/portfolio/trader/" trader-route-address)})
                       (assoc-in [:portfolio-ui :account-info-tab] :deposits-withdrawals)
                       (assoc-in [:leaderboard :rows]
                                 [{:eth-address trader-route-address
                                   :display-name "Gamma"}])))
        standard-actions-row (find-first-node view-node #(= "portfolio-actions-row" (get-in % [1 :data-role])))
        inspection-header (find-first-node view-node #(= "portfolio-inspection-header" (get-in % [1 :data-role])))
        inspection-address (find-first-node view-node #(= "portfolio-inspection-address" (get-in % [1 :data-role])))
        inspection-summary (find-first-node view-node #(= "portfolio-inspection-summary" (get-in % [1 :data-role])))
        own-portfolio-button (find-first-node view-node #(= "portfolio-inspection-own-portfolio" (get-in % [1 :data-role])))
        explorer-link (find-first-node view-node #(= "portfolio-inspection-explorer-link" (get-in % [1 :data-role])))
        deposit-action-button (find-first-node view-node #(= "portfolio-action-deposit" (get-in % [1 :data-role])))
        deposits-tab-button (find-first-node view-node
                                             #(= [[:actions/set-portfolio-account-info-tab :deposits-withdrawals]]
                                                 (get-in % [1 :on :click])))
        all-text (set (collect-strings view-node))]
    (is (nil? standard-actions-row))
    (is (some? inspection-header))
    (is (contains? all-text "Trader View"))
    (is (contains? all-text "Read Only"))
    (is (some #(str/includes? % "Gamma") (collect-strings inspection-summary)))
    (is (contains? (set (collect-strings inspection-address)) trader-route-address))
    (is (= [[:actions/navigate "/portfolio"]]
           (get-in own-portfolio-button [1 :on :click])))
    (is (= "https://app.hyperliquid.xyz/explorer/address/0x3333333333333333333333333333333333333333"
           (get-in explorer-link [1 :href])))
    (is (nil? deposit-action-button))
    (is (nil? deposits-tab-button))
    (is (not (contains? all-text "Deposits & Withdrawals")))))

(deftest portfolio-view-composes-trader-read-only-account-info-tabs-test
  (doseq [{:keys [label view-node required-text forbidden-texts forbidden-buttons forbidden-aria-labels]}
          [{:label "balances"
            :view-node (portfolio-view/portfolio-view (trader-balances-state))
            :required-text "MEOW"
            :forbidden-texts ["Send" "Transfer" "Repay"]}
           {:label "positions"
            :view-node (portfolio-view/portfolio-view (trader-positions-state))
            :required-text "HYPE"
            :forbidden-texts ["Close All"]
            :forbidden-buttons ["Reduce"]
            :forbidden-aria-labels ["Edit Margin" "Edit TP/SL"]}
           {:label "open orders"
            :view-node (portfolio-view/portfolio-view (trader-open-orders-state))
            :required-text "BTC"
            :forbidden-texts ["Cancel All"]
            :forbidden-buttons ["Cancel"]}
           {:label "twap"
            :view-node (portfolio-view/portfolio-view (trader-twap-state))
            :required-text "Active (1)"
            :forbidden-texts ["Terminate"]}]]
    (let [account-table (find-first-node view-node #(= "portfolio-account-table" (get-in % [1 :data-role])))
          table-strings (set (collect-strings account-table))]
      (is (contains? table-strings required-text) label)
      (doseq [forbidden-text forbidden-texts]
        (is (not (contains? table-strings forbidden-text))
            (str label " omits " forbidden-text)))
      (doseq [forbidden-button forbidden-buttons]
        (is (nil? (button-with-text account-table forbidden-button))
            (str label " omits button " forbidden-button)))
      (doseq [aria-label forbidden-aria-labels]
        (is (nil? (find-first-node account-table #(= aria-label (get-in % [1 :aria-label]))))
            (str label " omits aria-label " aria-label))))))

(deftest portfolio-view-positions-coin-cell-navigates-to-trade-market-test
  (let [view-node (portfolio-view/portfolio-view (portfolio-positions-state))
        account-table (find-first-node view-node #(= "portfolio-account-table" (get-in % [1 :data-role])))
        coin-button (find-first-node account-table #(= "positions-coin-select" (get-in % [1 :data-role])))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "HYPE"]
            [:actions/navigate "/trade/HYPE"]]
           (get-in coin-button [1 :on :click])))))

(deftest portfolio-view-funding-actions-pass-explicit-anchor-bounds-test
  (let [view-node (portfolio-view/portfolio-view sample-state)
        deposit-button (find-first-node view-node
                                        #(= [[:actions/open-funding-deposit-modal
                                               :event.currentTarget/bounds
                                               "portfolio-action-deposit"]]
                                            (get-in % [1 :on :click])))
        transfer-button (find-first-node view-node
                                         #(= [[:actions/open-funding-transfer-modal
                                                :event.currentTarget/bounds
                                                "portfolio-action-perps-spot"]]
                                             (get-in % [1 :on :click])))
        withdraw-button (find-first-node view-node
                                         #(= [[:actions/open-funding-withdraw-modal
                                                :event.currentTarget/bounds
                                                "portfolio-action-withdraw"]]
                                             (get-in % [1 :on :click])))]
    (is (some? deposit-button))
    (is (some? transfer-button))
    (is (some? withdraw-button))))
