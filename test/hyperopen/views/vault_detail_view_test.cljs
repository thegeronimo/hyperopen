(ns hyperopen.views.vault-detail-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vault-detail-view :as vault-detail-view]))

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

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(def sample-state
  {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}
   :vaults-ui {:detail-tab :about
               :detail-activity-tab :performance-metrics
               :detail-activity-sort-by-tab {}
               :detail-activity-direction-filter :all
               :detail-activity-filter-open? false
               :detail-chart-series :pnl
               :detail-returns-benchmark-coins ["BTC"]
               :detail-returns-benchmark-coin "BTC"
               :detail-returns-benchmark-search ""
               :detail-returns-benchmark-suggestions-open? false
               :snapshot-range :month
               :detail-loading? false}
   :asset-selector {:markets [{:coin "BTC"
                               :symbol "BTC"
                               :dex "hl"
                               :market-type :perp
                               :openInterest 1000}
                              {:coin "ETH"
                               :symbol "ETH"
                               :dex "hl"
                               :market-type :perp
                               :openInterest 800}]}
   :candles {"BTC" {:1h [[1 0 0 0 100]
                         [2 0 0 0 110]
                         [3 0 0 0 120]]}
             "ETH" {:1h [[1 0 0 0 2000]
                         [2 0 0 0 2100]
                         [3 0 0 0 2200]]}}
   :vaults {:errors {:details-by-address {}
                     :webdata-by-vault {}
                     :fills-by-vault {}
                     :funding-history-by-vault {}
                     :order-history-by-vault {}
                     :ledger-updates-by-vault {}}
            :loading {:fills-by-vault {}
                      :funding-history-by-vault {}
                      :order-history-by-vault {}
                      :ledger-updates-by-vault {}}
            :details-by-address {"0x1234567890abcdef1234567890abcdef12345678"
                                 {:name "Vault Detail"
                                  :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                  :description "Sample vault"
                                  :portfolio {:month {:accountValueHistory [[1 10] [2 11] [3 15]]
                                                      :pnlHistory [[1 -1] [2 0.5] [3 2.5]]}}
                                  :followers [{:user "0xf1"} {:user "0xf2"}]
                                  :leader-commission 0.15
                                  :relationship {:type :parent
                                                 :child-addresses ["0x9999999999999999999999999999999999999999"]}
                                  :follower-state {:vault-equity 50
                                                   :all-time-pnl 12}}}
            :webdata-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                               {:openOrders [{:order {:coin "BTC"
                                                      :side "B"
                                                      :sz "0.1"
                                                      :limitPx "100"
                                                      :timestamp 9}}]
                                :twapStates [{:coin "BTC"
                                              :sz "1.0"
                                              :executedSz "0.1"
                                              :avgPx "101"
                                              :creationTime 20}]
                                :clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                                   :szi "0.2"
                                                                                   :entryPx "100"
                                                                                   :positionValue "20"
                                                                                   :unrealizedPnl "1"
                                                                                   :returnOnEquity "0.05"}}]}}}
            :fills-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                             [{:time 3
                               :coin "BTC"
                               :side "buy"
                               :sz "0.5"
                               :px "101"}]}
            :funding-history-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                       [{:time-ms 5
                                         :coin "BTC"
                                         :fundingRate 0.001
                                         :positionSize 3
                                         :payment -4.2}]}
            :order-history-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                     [{:order {:coin "BTC"
                                               :side "B"
                                               :origSz "1.0"
                                               :limitPx "99"
                                               :orderType "Limit"
                                               :timestamp 10}
                                       :status "filled"
                                       :statusTimestamp 11}]}
            :ledger-updates-by-vault {"0x1234567890abcdef1234567890abcdef12345678"
                                      [{:time 12
                                        :hash "0xabc"
                                        :delta {:type "vaultDeposit"
                                                :vault "0x1234567890abcdef1234567890abcdef12345678"
                                                :usdc "10.0"}}]}
            :user-equity-by-address {"0x1234567890abcdef1234567890abcdef12345678"
                                     {:equity 50}}
            :merged-index-rows [{:name "Vault Detail"
                                 :vault-address "0x1234567890abcdef1234567890abcdef12345678"
                                 :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                 :tvl 200
                                 :apr 0.2
                                 :snapshot-by-key {:month [0.1 0.2]
                                                   :all-time [0.5]}}]}})

(deftest vault-detail-view-renders-hero-metrics-tabs-and-activity-test
  (let [view (vault-detail-view/vault-detail-view sample-state)
        root (find-first-node view #(= "vault-detail-root" (get-in % [1 :data-parity-id])))
        detail-tab-button (find-first-node view
                                           #(= [[:actions/set-vault-detail-tab :vault-performance]]
                                               (get-in % [1 :on :click])))
        chart-tab-button (find-first-node view
                                          #(= [[:actions/set-vault-detail-chart-series :account-value]]
                                              (get-in % [1 :on :click])))
        returns-chart-tab-button (find-first-node view
                                                  #(= [[:actions/set-vault-detail-chart-series :returns]]
                                                      (get-in % [1 :on :click])))
        pnl-area-positive (find-first-node view
                                           #(= "vault-detail-chart-area-positive"
                                               (get-in % [1 :data-role])))
        pnl-area-negative (find-first-node view
                                           #(= "vault-detail-chart-area-negative"
                                               (get-in % [1 :data-role])))
        timeframe-selector (find-first-node view
                                            #(= [[:actions/set-vaults-snapshot-range [:event.target/value]]]
                                                (get-in % [1 :on :change])))
        performance-metrics-tab-button (find-first-node view
                                                        #(= [[:actions/set-vault-detail-activity-tab :performance-metrics]]
                                                            (get-in % [1 :on :click])))
        activity-tab-button (find-first-node view
                                             #(= [[:actions/set-vault-detail-activity-tab :open-orders]]
                                                 (get-in % [1 :on :click])))
        text (set (collect-strings view))]
    (is (some? root))
    (is (some? detail-tab-button))
    (is (some? chart-tab-button))
    (is (some? returns-chart-tab-button))
    (is (some? pnl-area-positive))
    (is (some? pnl-area-negative))
    (is (some? timeframe-selector))
    (is (some? performance-metrics-tab-button))
    (is (some? activity-tab-button))
    (is (contains? text "Vault Detail"))
    (is (contains? text "Past Month Return"))
    (is (contains? text "Performance Metrics"))
    (is (contains? text "Range "))
    (is (contains? text "Open Orders (1)"))
    (is (contains? text "Funding History (1)"))))

(deftest vault-detail-view-renders-account-value-area-fill-test
  (let [state (assoc-in sample-state [:vaults-ui :detail-chart-series] :account-value)
        view (vault-detail-view/vault-detail-view state)
        area-node (find-first-node view
                                   #(= "vault-detail-chart-area" (get-in % [1 :data-role])))]
    (is (some? area-node))
    (is (= "rgba(247, 147, 26, 0.24)"
           (get-in area-node [1 :fill])))))

(deftest vault-detail-view-shows-name-skeleton-while-detail-name-is-unresolved-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        state (-> sample-state
                  (assoc-in [:vaults-ui :detail-loading?] true)
                  (assoc-in [:vaults :details-by-address vault-address :name] nil)
                  (assoc-in [:vaults :merged-index-rows 0 :name] nil))
        view (vault-detail-view/vault-detail-view state)
        text (set (collect-strings view))
        breadcrumb-skeleton (find-first-node view
                                             #(= "vault-detail-breadcrumb-skeleton"
                                                 (get-in % [1 :data-role])))
        title-skeleton (find-first-node view
                                        #(= "vault-detail-title-skeleton"
                                            (get-in % [1 :data-role])))]
    (is (some? breadcrumb-skeleton))
    (is (some? title-skeleton))
    (is (contains? text "Loading vault name"))
    (is (not (contains? text vault-address)))))

(deftest vault-detail-view-renders-returns-benchmark-controls-and-performance-metrics-panel-test
  (let [returns-state (assoc-in sample-state [:vaults-ui :detail-chart-series] :returns)
        returns-view (vault-detail-view/vault-detail-view returns-state)
        benchmark-selector (find-first-node returns-view
                                            #(= "vault-detail-returns-benchmark-selector"
                                                (get-in % [1 :data-role])))
        benchmark-chip-rail (find-first-node returns-view
                                             #(= "vault-detail-returns-benchmark-chip-rail"
                                                 (get-in % [1 :data-role])))
        benchmark-input (find-first-node returns-view
                                         #(= [[:actions/set-vault-detail-returns-benchmark-search [:event.target/value]]]
                                             (get-in % [1 :on :input])))
        metrics-state (assoc-in sample-state [:vaults-ui :detail-activity-tab] :performance-metrics)
        metrics-view (vault-detail-view/vault-detail-view metrics-state)
        time-in-market-row (find-first-node metrics-view
                                            #(= "vault-detail-performance-metric-time-in-market"
                                                (get-in % [1 :data-role])))
        sharpe-row (find-first-node metrics-view
                                    #(= "vault-detail-performance-metric-sharpe"
                                        (get-in % [1 :data-role])))
        max-drawdown-row (find-first-node metrics-view
                                          #(= "vault-detail-performance-metric-max-drawdown"
                                              (get-in % [1 :data-role])))
        text (set (collect-strings metrics-view))]
    (is (some? benchmark-selector))
    (is (some? benchmark-chip-rail))
    (is (some? benchmark-input))
    (is (contains? text "Time in Market"))
    (is (some? time-in-market-row))
    (is (nil? sharpe-row))
    (is (nil? max-drawdown-row))))

(deftest vault-detail-view-selects-active-snapshot-timeframe-option-test
  (let [state (assoc-in sample-state [:vaults-ui :snapshot-range] :six-month)
        view (vault-detail-view/vault-detail-view state)
        selected-timeframe-option (find-first-node view
                                                   #(and (= :option (first %))
                                                         (= "six-month" (get-in % [1 :value]))
                                                         (true? (get-in % [1 :selected]))))
        timeframe-selector (find-first-node view
                                            #(and (= :select (first %))
                                                  (= "six-month" (get-in % [1 :value]))))]
    (is (some? timeframe-selector))
    (is (some? selected-timeframe-option))))

(deftest vault-detail-view-shows-invalid-message-when-route-address-is-invalid-test
  (let [view (vault-detail-view/vault-detail-view (assoc-in sample-state [:router :path] "/vaults/not-an-address"))
        text (set (collect-strings view))]
    (is (contains? text "Invalid vault address."))))

(deftest vault-detail-view-formats-large-activity-tab-counts-test
  (let [open-order {:order {:coin "BTC"
                            :side "B"
                            :sz "0.1"
                            :limitPx "100"}}
        state (assoc-in sample-state
                        [:vaults :webdata-by-vault "0x1234567890abcdef1234567890abcdef12345678" :openOrders]
                        (mapv (fn [idx]
                                (assoc-in open-order [:order :timestamp] idx))
                              (range 101)))
        view (vault-detail-view/vault-detail-view state)
        text (set (collect-strings view))]
    (is (contains? text "Open Orders (100+)"))))

(deftest vault-detail-view-activity-tab-style-parity-test
  (let [state (assoc-in sample-state [:vaults-ui :detail-activity-tab] :positions)
        view (vault-detail-view/vault-detail-view state)
        positions-tab-button (find-first-node view
                                              #(= [[:actions/set-vault-detail-activity-tab :positions]]
                                                  (get-in % [1 :on :click])))
        classes (set (get-in positions-tab-button [1 :class]))]
    (is (contains? classes "border-[#303030]"))
    (is (contains? classes "text-[#f6fefd]"))
    (is (not (contains? classes "bg-base-100/50")))))

(deftest vault-detail-view-applies-semantic-row-accent-styles-test
  (let [positions-state (assoc-in sample-state [:vaults-ui :detail-activity-tab] :positions)
        positions-view (vault-detail-view/vault-detail-view positions-state)
        accent-cell (find-first-node positions-view
                                     #(and (= :td (first %))
                                           (string? (get-in % [1 :style :background]))
                                           (str/includes? (get-in % [1 :style :background]) "linear-gradient(90deg,rgb(31,166,125)")))
        order-history-state (assoc-in sample-state [:vaults-ui :detail-activity-tab] :order-history)
        order-history-view (vault-detail-view/vault-detail-view order-history-state)
        filled-status-cell (find-first-node order-history-view
                                            #(and (= :td (first %))
                                                  (some (fn [s]
                                                          (= "filled" (str/lower-case s)))
                                                        (collect-strings %))
                                                  (some #{"text-[#1fa67d]"} (get-in % [1 :class]))))]
    (is (some? accent-cell))
    (is (some? filled-status-cell))))

(deftest vault-detail-view-wires-activity-sort-and-filter-interactions-test
  (let [filter-open-state (-> sample-state
                              (assoc-in [:vaults-ui :detail-activity-tab] :positions)
                              (assoc-in [:vaults-ui :detail-activity-filter-open?] true)
                              (assoc-in [:vaults-ui :detail-activity-direction-filter] :long))
        view (vault-detail-view/vault-detail-view filter-open-state)
        sort-header (find-first-node view
                                     #(= [[:actions/sort-vault-detail-activity :positions "Size"]]
                                         (get-in % [1 :on :click])))
        filter-toggle (find-first-node view
                                       #(= [[:actions/toggle-vault-detail-activity-filter-open]]
                                           (get-in % [1 :on :click])))
        short-filter-option (find-first-node view
                                             #(= [[:actions/set-vault-detail-activity-direction-filter :short]]
                                                 (get-in % [1 :on :click])))]
    (is (some? sort-header))
    (is (some? filter-toggle))
    (is (some? short-filter-option))))
