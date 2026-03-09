(ns hyperopen.views.account-info.tabs.trade-history-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.test-support.hiccup-selectors :as selectors]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.tabs.trade-history :as trade-history-tab]
            [hyperopen.views.account-info-view :as view]))

(defn- reset-trade-history-sort-cache-fixture
  [f]
  (trade-history-tab/reset-trade-history-sort-cache!)
  (f)
  (trade-history-tab/reset-trade-history-sort-cache!))

(use-fixtures :each reset-trade-history-sort-cache-fixture)

(deftest trade-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (view/sortable-trade-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (hiccup/node-children header-node)))]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-trade-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest sort-trade-history-by-column-is-deterministic-on-ties-and-formats-derived-values-test
  (let [rows [{:tid 2
               :coin "xyz:NVDA"
               :side "B"
               :sz "2"
               :px "10"
               :fee "0.1"
               :time 1700000000000}
              {:tid 1
               :coin "BTC"
               :side "A"
               :sz "1"
               :px "15"
               :fee "0.2"
               :time 1700000000000}
              {:tid 3
               :coin "ETH"
               :dir "Open Long (Price Improved)"
               :sz "3"
               :px "8"
               :tradeValue "24"
               :closedPnl "-0.3"
               :fee "0.05"
               :time 1700000001000}]
        time-asc (view/sort-trade-history-by-column rows "Time" :asc {})
        value-desc (view/sort-trade-history-by-column rows "Trade Value" :desc {})
        direction-asc (view/sort-trade-history-by-column rows "Direction" :asc {})]
    (is (= [1 2 3] (mapv :tid time-asc)))
    (is (= [3 2 1] (mapv :tid value-desc)))
    (is (= [2 3 1] (mapv :tid direction-asc)))))

(deftest trade-history-tab-content-memoizes-by-input-signatures-and-rebuilds-only-market-index-on-market-change-test
  (let [fills [{:tid 1
                :coin "ETH"
                :side "B"
                :sz "1.0"
                :px "100.0"
                :fee "0.1"
                :time 1700000000000}]
        trade-history-state {:sort {:column "Time" :direction :desc}
                             :direction-filter :all
                             :coin-search ""
                             :market-by-key {}}
        equivalent-market (into {} (:market-by-key trade-history-state))
        changed-market {"spot:ETH/USDC" {:coin "spot:ETH/USDC"
                                         :symbol "ETH/USDC"}}
        sort-calls (atom 0)
        index-calls (atom 0)
        original-index-builder @#'trade-history-tab/*build-trade-history-coin-search-index*]
    (trade-history-tab/reset-trade-history-sort-cache!)
    (with-redefs [trade-history-tab/sort-trade-history-by-column
                  (fn
                    ([rows _column _direction]
                     (swap! sort-calls inc)
                     rows)
                    ([rows _column _direction _market-by-key]
                     (swap! sort-calls inc)
                     rows))
                  trade-history-tab/*build-trade-history-coin-search-index*
                  (fn [rows market-by-key]
                    (swap! index-calls inc)
                    (original-index-builder rows market-by-key))]
      (view/trade-history-tab-content fills trade-history-state)
      (view/trade-history-tab-content fills trade-history-state)
      (is (= 1 @sort-calls))
      (is (= 1 @index-calls))

      (let [churned-fills (into [] fills)]
        (view/trade-history-tab-content churned-fills trade-history-state)
        (view/trade-history-tab-content churned-fills trade-history-state)
        (is (= 1 @sort-calls))
        (is (= 1 @index-calls)))

      (view/trade-history-tab-content fills (assoc trade-history-state :market-by-key equivalent-market))
      (is (= 1 @sort-calls))
      (is (= 1 @index-calls))

      (view/trade-history-tab-content fills (assoc trade-history-state :market-by-key changed-market))
      (is (= 1 @sort-calls))
      (is (= 2 @index-calls))

      (view/trade-history-tab-content fills
                                      (assoc trade-history-state
                                             :market-by-key changed-market
                                             :coin-search "et"))
      (view/trade-history-tab-content fills
                                      (assoc trade-history-state
                                             :market-by-key changed-market
                                             :coin-search "et"))
      (is (= 1 @sort-calls))
      (is (= 2 @index-calls))

      (view/trade-history-tab-content fills
                                      (assoc trade-history-state
                                             :market-by-key changed-market
                                             :direction-filter :short))
      (view/trade-history-tab-content fills
                                      (assoc trade-history-state
                                             :market-by-key changed-market
                                             :direction-filter :short))
      (is (= 2 @sort-calls))
      (is (= 3 @index-calls))

      (let [changed-fills (assoc-in (into [] fills) [0 :px] "101.0")]
        (view/trade-history-tab-content changed-fills trade-history-state)
        (is (= 3 @sort-calls))
        (is (= 4 @index-calls))))))

(deftest trade-history-tab-content-filters-rows-by-direction-filter-test
  (let [fills [{:tid 1
                :coin "LONGCOIN"
                :side "B"
                :sz "1.0"
                :px "100.0"
                :fee "0.1"
                :time 1700000002000}
               {:tid 2
                :coin "SHORTA"
                :side "A"
                :sz "2.0"
                :px "99.0"
                :fee "0.1"
                :time 1700000001000}
               {:tid 3
                :coin "SHORTS"
                :side "S"
                :sz "3.0"
                :px "98.0"
                :fee "0.1"
                :time 1700000000000}]
        all-content (view/trade-history-tab-content fills {:direction-filter :all})
        long-content (view/trade-history-tab-content fills {:direction-filter :long})
        short-content (view/trade-history-tab-content fills {:direction-filter :short})
        all-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node all-content))))
        long-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node long-content))))
        short-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node short-content))))
        long-text (set (hiccup/collect-strings long-content))
        short-text (set (hiccup/collect-strings short-content))]
    (is (= 3 all-row-count))
    (is (= 1 long-row-count))
    (is (= 2 short-row-count))
    (is (contains? long-text "LONGCOIN"))
    (is (not (contains? long-text "SHORTA")))
    (is (not (contains? long-text "SHORTS")))
    (is (contains? short-text "SHORTA"))
    (is (contains? short-text "SHORTS"))
    (is (not (contains? short-text "LONGCOIN")))))

(deftest trade-history-tab-content-filters-rows-by-coin-search-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :side "B"
                :sz "1.0"
                :px "100.0"
                :fee "0.1"
                :time 1700000002000}
               {:tid 2
                :coin "HYPE"
                :side "A"
                :sz "2.0"
                :px "99.0"
                :fee "0.1"
                :time 1700000001000}]
        all-content (view/trade-history-tab-content fills {:coin-search ""})
        symbol-search-content (view/trade-history-tab-content fills {:coin-search "nv"})
        prefix-search-content (view/trade-history-tab-content fills {:coin-search "xyz"})
        all-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node all-content))))
        symbol-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node symbol-search-content))))
        prefix-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node prefix-search-content))))
        symbol-text (set (hiccup/collect-strings symbol-search-content))
        prefix-text (set (hiccup/collect-strings prefix-search-content))]
    (is (= 2 all-row-count))
    (is (= 1 symbol-row-count))
    (is (= 1 prefix-row-count))
    (is (contains? symbol-text "NVDA"))
    (is (not (contains? symbol-text "HYPE")))
    (is (contains? prefix-text "NVDA"))
    (is (contains? prefix-text "xyz"))))

(deftest trade-history-headers-match-hyperliquid-order-and-contrast-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :dir "Open Long"
                :side "B"
                :sz "0.500"
                :px "187.88"
                :tradeValue "93.94"
                :fee "0.01"
                :closedPnl "-0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        header-node (hiccup/tab-header-node content)
        header-cells (vec (hiccup/node-children header-node))
        header-buttons (mapv #(first (vec (hiccup/node-children %))) header-cells)
        header-labels (mapv #(first (hiccup/collect-strings %)) header-buttons)]
    (is (= ["Time" "Coin" "Direction" "Price" "Size" "Trade Value" "Fee" "Closed PNL"]
           header-labels))
    (is (every? #(= :button (first %)) header-buttons))
    (is (every? #(contains? (hiccup/node-class-set %) "text-trading-text-secondary") header-buttons))
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text-secondary"))))

(deftest trade-history-parity-renders-coin-direction-and-usdc-fields-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :side "A"
                :dir "Open Long (Price Improved)"
                :sz "0.500"
                :px "187.88"
                :tradeValue "93.94"
                :fee "0.01"
                :closedPnl "-0.01"
                :time 1700000000000}
               {:tid 2
                :coin "HYPE"
                :side "S"
                :sz "2"
                :px "10"
                :fee "0.02"
                :time 1700000001000}]
        content (view/trade-history-tab-content fills)
        viewport (hiccup/tab-rows-viewport-node content)
        rendered-rows (vec (hiccup/node-children viewport))
        nvda-row (some #(when (contains? (set (hiccup/collect-strings %)) "NVDA") %) rendered-rows)
        hype-row (some #(when (contains? (set (hiccup/collect-strings %)) "HYPE") %) rendered-rows)
        nvda-row-cells (vec (hiccup/node-children nvda-row))
        hype-row-cells (vec (hiccup/node-children hype-row))
        nvda-coin-strings (set (hiccup/collect-strings (nth nvda-row-cells 1)))
        nvda-row-strings (set (hiccup/collect-strings nvda-row))
        nvda-direction-strings (set (hiccup/collect-strings (nth nvda-row-cells 2)))
        hype-direction-strings (set (hiccup/collect-strings (nth hype-row-cells 2)))]
    (is (some? nvda-row))
    (is (some? hype-row))
    (is (contains? nvda-coin-strings "NVDA"))
    (is (contains? nvda-coin-strings "xyz"))
    (is (not (contains? nvda-row-strings "xyz:NVDA")))
    (is (contains? nvda-direction-strings "Open Long (Price Improved)"))
    (is (contains? hype-direction-strings "Open Short"))
    (is (contains? (hiccup/direct-texts (nth hype-row-cells 5)) "20.00 USDC"))
    (is (contains? (hiccup/direct-texts (nth nvda-row-cells 6)) "0.01 USDC"))
    (is (contains? (hiccup/direct-texts (nth nvda-row-cells 7)) "-0.01 USDC"))
    (is (contains? (hiccup/direct-texts (nth hype-row-cells 7)) "--"))))

(deftest trade-history-direction-and-coin-colors-follow-action-intent-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :side "B"
                :dir "Open Long"
                :sz "0.500"
                :px "187.88"
                :fee "0.01"
                :time 1700000000000}
               {:tid 2
                :coin "PUMP"
                :side "A"
                :dir "Sell"
                :sz "2"
                :px "10"
                :fee "0.02"
                :time 1700000001000}
               {:tid 3
                :coin "HYPE"
                :side "A"
                :dir "Market Order Liquidation: Close Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000002000}]
        content (view/trade-history-tab-content fills)
        viewport (hiccup/tab-rows-viewport-node content)
        rendered-rows (vec (hiccup/node-children viewport))
        row-for (fn [needle]
                  (some #(when (contains? (set (hiccup/collect-strings %)) needle)
                           %)
                        rendered-rows))
        nvda-row (row-for "NVDA")
        pump-row (row-for "PUMP")
        hype-row (row-for "HYPE")
        nvda-cells (vec (hiccup/node-children nvda-row))
        pump-cells (vec (hiccup/node-children pump-row))
        hype-cells (vec (hiccup/node-children hype-row))
        nvda-coin-cell (nth nvda-cells 1)
        pump-coin-cell (nth pump-cells 1)
        hype-coin-cell (nth hype-cells 1)
        nvda-coin-base (hiccup/find-first-node nvda-coin-cell #(and (= :span (first %))
                                                              (contains? (hiccup/direct-texts %) "NVDA")))
        pump-coin-base (hiccup/find-first-node pump-coin-cell #(and (= :span (first %))
                                                              (contains? (hiccup/direct-texts %) "PUMP")))
        hype-coin-base (hiccup/find-first-node hype-coin-cell #(and (= :span (first %))
                                                              (contains? (hiccup/direct-texts %) "HYPE")))
        xyz-chip (hiccup/find-first-node nvda-coin-cell #(and (= :span (first %))
                                                       (contains? (hiccup/direct-texts %) "xyz")))
        nvda-direction-cell (nth nvda-cells 2)
        pump-direction-cell (nth pump-cells 2)
        hype-direction-cell (nth hype-cells 2)]
    (is (some? nvda-row))
    (is (some? pump-row))
    (is (some? hype-row))
    (is (some? nvda-coin-base))
    (is (some? pump-coin-base))
    (is (some? hype-coin-base))
    (is (some? xyz-chip))
    (is (contains? (hiccup/node-class-set nvda-direction-cell) "text-success"))
    (is (contains? (hiccup/node-class-set nvda-coin-base) "text-success"))
    (is (contains? (hiccup/node-class-set pump-direction-cell) "text-error"))
    (is (contains? (hiccup/node-class-set pump-coin-base) "text-error"))
    (is (contains? (hiccup/node-class-set hype-direction-cell) "text-error"))
    (is (contains? (hiccup/node-class-set hype-coin-base) "text-error"))
    (is (contains? (set (hiccup/collect-strings pump-direction-cell)) "Sell"))
    (is (contains? (set (hiccup/collect-strings hype-direction-cell))
                   "Market Order Liquidation: Close Long"))))

(deftest trade-history-desktop-grid-and-value-cells-use-available-width-test
  (let [fills [{:tid 1
                :coin "xyz:SILVER"
                :side "A"
                :dir "Close Long"
                :sz "0.52"
                :px "95.242"
                :tradeValue "49.53"
                :fee "0.00"
                :closedPnl "0.19"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        header-node (hiccup/tab-header-node content)
        row-node (hiccup/first-viewport-row content)
        cells (vec (hiccup/node-children row-node))
        coin-cell (nth cells 1)
        size-cell (nth cells 4)
        value-cell (nth cells 5)
        fee-cell (nth cells 6)
        pnl-cell (nth cells 7)
        coin-base (hiccup/find-first-node coin-cell #(and (= :span (first %))
                                                          (contains? (hiccup/direct-texts %) "SILVER")))
        header-classes (hiccup/node-class-set header-node)
        row-classes (hiccup/node-class-set row-node)
        flexible-grid-class "grid-cols-[minmax(180px,1.45fr)_minmax(90px,1.05fr)_minmax(160px,1.2fr)_minmax(90px,0.8fr)_minmax(130px,1.1fr)_minmax(130px,1.05fr)_minmax(110px,0.9fr)_minmax(120px,1fr)]"
        old-grid-class "grid-cols-[180px_90px_160px_90px_130px_130px_110px_120px]"]
    (is (contains? header-classes flexible-grid-class))
    (is (contains? row-classes flexible-grid-class))
    (is (not (contains? header-classes old-grid-class)))
    (is (not (contains? row-classes old-grid-class)))
    (is (some? coin-base))
    (is (contains? (hiccup/node-class-set coin-base) "whitespace-nowrap"))
    (is (not (contains? (hiccup/node-class-set coin-base) "truncate")))
    (is (contains? (hiccup/node-class-set size-cell) "whitespace-nowrap"))
    (is (contains? (hiccup/node-class-set value-cell) "whitespace-nowrap"))
    (is (contains? (hiccup/node-class-set fee-cell) "whitespace-nowrap"))
    (is (contains? (hiccup/node-class-set pnl-cell) "whitespace-nowrap"))))

(deftest trade-history-coin-cell-dispatches-select-asset-action-test
  (let [fills [{:tid 1
                :coin "xyz:NVDA"
                :side "B"
                :dir "Open Long"
                :sz "0.500"
                :px "187.88"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 1)
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "xyz:NVDA"]]
           (get-in coin-button [1 :on :click])))))

(deftest trade-history-price-improved-direction-renders-liquidation-tooltip-test
  (let [tooltip-copy "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation."
        fills [{:tid 1
                :coin "PUMP"
                :side "B"
                :dir "Open Long (Price Improved)"
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        direction-cell (nth (vec (hiccup/node-children row-node)) 2)
        direction-strings (set (hiccup/collect-strings direction-cell))
        group-node (hiccup/find-first-node direction-cell #(and (= :div (first %))
                                                         (contains? (hiccup/node-class-set %) "group")))
        focusable-label (hiccup/find-first-node direction-cell #(and (= :span (first %))
                                                              (= 0 (get-in % [1 :tab-index]))))
        focusable-label-classes (hiccup/node-class-set focusable-label)
        tooltip-panel-node (hiccup/find-first-node direction-cell #(and (= :div (first %))
                                                                 (contains? (hiccup/node-class-set %) "group-hover:opacity-100")
                                                                 (contains? (hiccup/node-class-set %) "group-focus-within:opacity-100")))
        tooltip-panel-classes (hiccup/node-class-set tooltip-panel-node)
        tooltip-bubble-node (hiccup/find-first-node tooltip-panel-node #(and (= :div (first %))
                                                                      (contains? (hiccup/node-class-set %) "w-[520px]")))
        tooltip-bubble-classes (hiccup/node-class-set tooltip-bubble-node)]
    (is (contains? direction-strings "Open Long (Price Improved)"))
    (is (some? group-node))
    (is (some? focusable-label))
    (is (some? tooltip-panel-node))
    (is (some? tooltip-bubble-node))
    (is (contains? (set (hiccup/collect-strings tooltip-panel-node)) tooltip-copy))
    (is (contains? focusable-label-classes "underline"))
    (is (contains? focusable-label-classes "decoration-dotted"))
    (is (contains? focusable-label-classes "underline-offset-2"))
    (is (contains? tooltip-panel-classes "bottom-full"))
    (is (contains? tooltip-panel-classes "mb-2"))
    (is (contains? tooltip-panel-classes "group-hover:opacity-100"))
    (is (contains? tooltip-panel-classes "group-focus-within:opacity-100"))
    (is (contains? tooltip-bubble-classes "w-[520px]"))))

(deftest trade-history-standard-direction-does-not-render-liquidation-tooltip-test
  (let [tooltip-copy "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation."
        fills [{:tid 1
                :coin "PUMP"
                :side "B"
                :dir "Open Long"
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        direction-cell (nth (vec (hiccup/node-children row-node)) 2)
        tooltip-panel-node (hiccup/find-first-node direction-cell #(and (= :div (first %))
                                                                 (contains? (hiccup/node-class-set %) "group-hover:opacity-100")
                                                                 (contains? (hiccup/node-class-set %) "group-focus-within:opacity-100")))
        direction-strings (set (hiccup/collect-strings direction-cell))]
    (is (contains? direction-strings "Open Long"))
    (is (not (contains? direction-strings tooltip-copy)))
    (is (nil? tooltip-panel-node))))

(deftest trade-history-liquidation-metadata-infers-price-improved-direction-test
  (let [tooltip-copy "This fill price was more favorable to you than the price chart at that time, because your order provided liquidity to another user's liquidation."
        fills [{:tid 1
                :coin "PUMP"
                :side "B"
                :dir "Open Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        direction-cell (nth (vec (hiccup/node-children row-node)) 2)
        direction-strings (set (hiccup/collect-strings direction-cell))
        tooltip-panel-node (hiccup/find-first-node direction-cell #(and (= :div (first %))
                                                                 (contains? (hiccup/node-class-set %) "group-hover:opacity-100")
                                                                 (contains? (hiccup/node-class-set %) "group-focus-within:opacity-100")))]
    (is (contains? direction-strings "Open Long (Price Improved)"))
    (is (contains? direction-strings tooltip-copy))
    (is (some? tooltip-panel-node))))

(deftest trade-history-liquidation-direction-remains-unmodified-test
  (let [fills [{:tid 1
                :coin "PUMP"
                :side "A"
                :dir "Market Order Liquidation: Close Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        direction-cell (nth (vec (hiccup/node-children row-node)) 2)
        direction-strings (set (hiccup/collect-strings direction-cell))]
    (is (contains? direction-strings "Market Order Liquidation: Close Long"))
    (is (not (contains? direction-strings "Market Order Liquidation: Close Long (Price Improved)")))))

(deftest trade-history-liquidation-close-direction-is-inferred-from-metadata-test
  (let [fills [{:tid 1
                :coin "PUMP"
                :side "A"
                :dir "Close Long"
                :liquidation {:markPx "0.001780"
                              :method "market"}
                :sz "100"
                :px "0.001765"
                :fee "0.01"
                :time 1700000000000}]
        content (view/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        direction-cell (nth (vec (hiccup/node-children row-node)) 2)
        direction-strings (set (hiccup/collect-strings direction-cell))]
    (is (contains? direction-strings "Market Order Liquidation: Close Long"))
    (is (not (contains? direction-strings "Close Long (Price Improved)")))
    (is (not (contains? direction-strings "Market Order Liquidation: Close Long (Price Improved)")))))

(deftest trade-history-time-cell-renders-explorer-link-when-valid-hash-present-test
  (let [hash-value "0xcb13be47d7d3e736cc8d04346f1535020494002d72d706086edc699a96d7c121"
        fills [{:tid 1
                :coin "HYPE"
                :side "B"
                :sz "1.2"
                :px "100.0"
                :fee "0.1"
                :time 1700000000000
                :hash hash-value}]
        content (view/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        time-cell (first (vec (hiccup/node-children row-node)))
        time-cell-classes (hiccup/node-class-set time-cell)
        link-node (hiccup/find-first-node time-cell #(= :a (first %)))
        link-classes (hiccup/node-class-set link-node)
        icon-node (hiccup/find-first-node link-node #(= :svg (first %)))
        expected-time (view/format-open-orders-time 1700000000000)
        strings (set (hiccup/collect-strings time-cell))]
    (is (some? link-node))
    (is (contains? time-cell-classes "whitespace-nowrap"))
    (is (contains? link-classes "whitespace-nowrap"))
    (is (= (str "https://app.hyperliquid.xyz/explorer/tx/" hash-value)
           (get-in link-node [1 :href])))
    (is (= "_blank" (get-in link-node [1 :target])))
    (is (= "noopener noreferrer" (get-in link-node [1 :rel])))
    (is (contains? strings expected-time))
    (is (some? icon-node))))

(deftest time-format-wrapper-parity-test
  (let [ms 1700000000000
        expected (fmt/format-local-date-time ms)]
    (is (= expected (view/format-open-orders-time ms)))
    (is (= expected (view/format-funding-history-time ms)))
    (is (nil? (view/format-open-orders-time nil)))
    (is (nil? (view/format-funding-history-time nil)))))

(deftest trade-history-time-cell-falls-back-to-plain-text-when-hash-missing-or-invalid-test
  (let [fills [{:tid 1
                :coin "HYPE"
                :side "B"
                :sz "1.2"
                :px "100.0"
                :fee "0.1"
                :time 1700000000000}
               {:tid 2
                :coin "BTC"
                :side "A"
                :sz "0.8"
                :px "95.0"
                :fee "0.05"
                :time 1700000001000
                :hash "0x1234"}]
        content (view/trade-history-tab-content fills)
        viewport (hiccup/tab-rows-viewport-node content)
        rendered-rows (vec (hiccup/node-children viewport))
        expected-times (set (mapv (comp view/format-open-orders-time :time) fills))
        rendered-times (->> rendered-rows
                            (map (fn [row]
                                   (let [time-cell (first (vec (hiccup/node-children row)))]
                                     (is (nil? (hiccup/find-first-node time-cell #(= :a (first %)))))
                                     (hiccup/collect-strings time-cell))))
                            (reduce into #{}))]
    (doseq [expected expected-times]
      (is (contains? rendered-times expected)))))

(deftest trade-history-pagination-renders-only-current-page-rows-test
  (let [rows (mapv fixtures/trade-history-row (range 55))
        content (@#'view/trade-history-table rows {:page-size 25
                                                   :page 2
                                                   :page-input "2"})
        viewport (hiccup/tab-rows-viewport-node content)
        rendered-rows (vec (hiccup/node-children viewport))
        all-strings (set (hiccup/collect-strings content))]
    (is (= 25 (count rendered-rows)))
    (is (contains? all-strings "Page 2 of 3"))
    (is (contains? all-strings "Total: 55"))))

(deftest trade-history-pagination-controls-disable-prev-next-at-edges-test
  (let [rows (mapv fixtures/trade-history-row (range 51))
        first-page (@#'view/trade-history-table rows {:page-size 25
                                                      :page 1
                                                      :page-input "1"})
        first-prev (hiccup/find-first-node first-page selectors/prev-button-predicate)
        first-next (hiccup/find-first-node first-page selectors/next-button-predicate)
        last-page (@#'view/trade-history-table rows {:page-size 25
                                                     :page 3
                                                     :page-input "3"})
        last-prev (hiccup/find-first-node last-page selectors/prev-button-predicate)
        last-next (hiccup/find-first-node last-page selectors/next-button-predicate)]
    (is (= true (get-in first-prev [1 :disabled])))
    (is (not= true (get-in first-next [1 :disabled])))
    (is (not= true (get-in last-prev [1 :disabled])))
    (is (= true (get-in last-next [1 :disabled])))))

(deftest trade-history-pagination-controls-wire-actions-test
  (let [rows (mapv fixtures/trade-history-row (range 12))
        content (@#'view/trade-history-table rows {:page-size 25
                                                   :page 1
                                                   :page-input "4"})
        page-size-select (hiccup/find-first-node content (selectors/select-id-predicate "trade-history-page-size"))
        jump-input (hiccup/find-first-node content (selectors/input-id-predicate "trade-history-page-input"))
        go-button (hiccup/find-first-node content selectors/go-button-predicate)]
    (is (= [[:actions/set-trade-history-page-size [:event.target/value]]]
           (get-in page-size-select [1 :on :change])))
    (is (= [[:actions/set-trade-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :input])))
    (is (= [[:actions/set-trade-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :change])))
    (is (= [[:actions/handle-trade-history-page-input-keydown [:event/key] 1]]
           (get-in jump-input [1 :on :keydown])))
    (is (= [[:actions/apply-trade-history-page-input 1]]
           (get-in go-button [1 :on :click])))))

(deftest trade-history-direction-filter-controls-wire-actions-test
  (let [rows [{:tid 1
               :coin "LONGCOIN"
               :side "B"
               :sz "1.0"
               :px "100.0"
               :fee "0.1"
               :time 1700000001000}
              {:tid 2
               :coin "SHORTCOIN"
               :side "A"
               :sz "1.0"
               :px "99.0"
               :fee "0.1"
               :time 1700000000000}]
        panel-state (-> fixtures/sample-account-info-state
                        (assoc-in [:account-info :selected-tab] :trade-history)
                        (assoc-in [:account-info :trade-history]
                                  {:sort {:column "Time" :direction :desc}
                                   :direction-filter :short
                                   :coin-search "nv"
                                   :filter-open? true
                                   :page-size 25
                                   :page 1
                                   :page-input "1"})
                        (assoc-in [:orders :fills] rows))
        panel (view/account-info-panel panel-state)
        filter-button (hiccup/find-first-node panel #(and (contains? (hiccup/direct-texts %) "Short")
                                                           (= [[:actions/toggle-trade-history-direction-filter-open]]
                                                              (get-in % [1 :on :click]))))
        search-input (hiccup/find-first-node panel #(= [[:actions/set-account-info-coin-search :trade-history [:event.target/value]]]
                                                        (get-in % [1 :on :input])))
        short-option (hiccup/find-first-node panel #(and (contains? (hiccup/direct-texts %) "Short")
                                                          (= [[:actions/set-trade-history-direction-filter :short]]
                                                             (get-in % [1 :on :click]))))
        long-option (hiccup/find-first-node panel #(and (contains? (hiccup/direct-texts %) "Long")
                                                         (= [[:actions/set-trade-history-direction-filter :long]]
                                                            (get-in % [1 :on :click]))))]
    (is (some? filter-button))
    (is (some? search-input))
    (is (= "nv" (get-in search-input [1 :value])))
    (is (some? short-option))
    (is (some? long-option))
    (is (= [[:actions/toggle-trade-history-direction-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= [[:actions/set-trade-history-direction-filter :short]]
           (get-in short-option [1 :on :click])))
    (is (= [[:actions/set-trade-history-direction-filter :long]]
           (get-in long-option [1 :on :click])))))

(deftest trade-history-pagination-clamps-page-when-data-shrinks-test
  (let [rows (mapv fixtures/trade-history-row (range 10))
        content (@#'view/trade-history-table rows {:page-size 25
                                                   :page 4
                                                   :page-input "4"})
        viewport (hiccup/tab-rows-viewport-node content)
        jump-input (hiccup/find-first-node content (selectors/input-id-predicate "trade-history-page-input"))
        all-strings (set (hiccup/collect-strings content))]
    (is (= 10 (count (vec (hiccup/node-children viewport)))))
    (is (contains? all-strings "Page 1 of 1"))
    (is (= "1" (get-in jump-input [1 :value])))))

(deftest trade-history-table-renders-mobile-summary-cards-with-inline-expansion-test
  (let [fills [{:tid 7
                :coin "xyz:SILVER"
                :side "A"
                :sz "0.52"
                :px "95.242"
                :tradeValue "49.53"
                :fee "0.00"
                :closedPnl "0.19"
                :time 1700000000000
                :hash "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"}
               {:tid 8
                :coin "BTC"
                :side "B"
                :sz "1.25"
                :px "100.5"
                :fee "0.05"
                :time 1700000001000}]
        expanded-row-id (@#'trade-history-tab/trade-history-row-id (first fills))
        collapsed-row-id (@#'trade-history-tab/trade-history-row-id (second fills))
        content (@#'view/trade-history-table fills {:page-size 25
                                                    :page 1
                                                    :page-input "1"
                                                    :mobile-expanded-card {:trade-history expanded-row-id}})
        mobile-viewport (hiccup/find-by-data-role content "trade-history-mobile-cards-viewport")
        mobile-cards (->> (hiccup/node-children mobile-viewport)
                          (filter vector?)
                          vec)
        expanded-card (hiccup/find-by-data-role content (str "mobile-trade-history-card-" expanded-row-id))
        collapsed-card (hiccup/find-by-data-role content (str "mobile-trade-history-card-" collapsed-row-id))
        expanded-button (first (vec (hiccup/node-children expanded-card)))
        collapsed-button (first (vec (hiccup/node-children collapsed-card)))
        expanded-button-classes (hiccup/node-class-set expanded-button)
        summary-grid (hiccup/find-first-node expanded-button #(contains? (hiccup/node-class-set %) "grid-cols-[minmax(0,1fr)_minmax(0,0.95fr)_minmax(0,0.72fr)_auto]"))
        expanded-button-strings (set (hiccup/collect-strings expanded-button))
        expanded-strings (set (hiccup/collect-strings expanded-card))
        collapsed-strings (set (hiccup/collect-strings collapsed-card))
        namespace-chip (hiccup/find-first-node expanded-card #(and (= :span (first %))
                                                                   (contains? (hiccup/direct-texts %) "xyz")))
        links (hiccup/find-all-nodes expanded-card #(= :a (first %)))
        time-link (first links)
        pnl-link (second links)]
    (is (some? mobile-viewport))
    (is (= 3 (count mobile-cards)))
    (is (= true (get-in expanded-button [1 :aria-expanded])))
    (is (= [[:actions/toggle-account-info-mobile-card :trade-history expanded-row-id]]
           (get-in expanded-button [1 :on :click])))
    (is (contains? expanded-button-classes "px-3.5"))
    (is (contains? expanded-button-classes "hover:bg-[#0c1b24]"))
    (is (contains? (hiccup/node-class-set expanded-card) "bg-[#08161f]"))
    (is (contains? (hiccup/node-class-set expanded-card) "border-[#17313d]"))
    (is (not (contains? (hiccup/node-class-set expanded-card) "bg-[#0f1920]")))
    (is (some? summary-grid))
    (is (some? namespace-chip))
    (is (contains? (hiccup/node-class-set namespace-chip) "bg-[#242924]"))
    (is (contains? (hiccup/node-class-set namespace-chip) "border"))
    (is (contains? (hiccup/node-class-set namespace-chip) "rounded-lg"))
    (is (contains? expanded-button-strings "Coin"))
    (is (contains? expanded-button-strings "Direction"))
    (is (contains? expanded-button-strings "Price"))
    (is (not (contains? expanded-button-strings "Time")))
    (is (not (contains? expanded-button-strings "Size")))
    (is (contains? expanded-strings "Coin"))
    (is (contains? expanded-strings "Time"))
    (is (contains? expanded-strings "Direction"))
    (is (contains? expanded-strings "Price"))
    (is (contains? expanded-strings "Size"))
    (is (contains? expanded-strings "Trade Value"))
    (is (contains? expanded-strings "Fee"))
    (is (contains? expanded-strings "Closed PNL"))
    (is (some? time-link))
    (is (some? pnl-link))
    (is (= "https://app.hyperliquid.xyz/explorer/tx/0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
           (get-in time-link [1 :href])))
    (is (= "https://app.hyperliquid.xyz/explorer/tx/0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
           (get-in pnl-link [1 :href])))
    (is (= false (get-in collapsed-button [1 :aria-expanded])))
    (is (contains? collapsed-strings "BTC"))
    (is (not (contains? collapsed-strings "Trade Value")))))
