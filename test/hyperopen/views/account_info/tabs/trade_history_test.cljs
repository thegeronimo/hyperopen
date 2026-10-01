(ns hyperopen.views.account-info.tabs.trade-history-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.shared :as account-info-shared]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.tabs.trade-history :as trade-history-tab]))

(deftest trade-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (trade-history-tab/sortable-trade-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (hiccup/node-children header-node)))]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-trade-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        direction-cell (nth (vec (hiccup/node-children row-node)) 2)
        direction-strings (set (hiccup/collect-strings direction-cell))
        group-node (hiccup/find-first-node direction-cell #(and (= :div (first %))
                                                                (contains? (hiccup/node-class-set %) "group")))
        focusable-label (hiccup/find-first-node direction-cell #(and (= :span (first %))
                                                                     (= 0 (get-in % [1 :tabindex]))))
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
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
        content (trade-history-tab/trade-history-tab-content fills)
        row-node (hiccup/first-viewport-row content)
        time-cell (first (vec (hiccup/node-children row-node)))
        time-cell-classes (hiccup/node-class-set time-cell)
        link-node (hiccup/find-first-node time-cell #(= :a (first %)))
        link-classes (hiccup/node-class-set link-node)
        icon-node (hiccup/find-first-node link-node #(= :svg (first %)))
        expected-time (account-info-shared/format-open-orders-time 1700000000000)
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
    (is (= expected (account-info-shared/format-open-orders-time ms)))
    (is (= expected (account-info-shared/format-funding-history-time ms)))
    (is (nil? (account-info-shared/format-open-orders-time nil)))
    (is (nil? (account-info-shared/format-funding-history-time nil)))))

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
        content (trade-history-tab/trade-history-tab-content fills)
        viewport (hiccup/tab-rows-viewport-node content)
        rendered-rows (vec (hiccup/node-children viewport))
        expected-times (set (mapv (comp account-info-shared/format-open-orders-time :time) fills))
        rendered-times (->> rendered-rows
                            (map (fn [row]
                                   (let [time-cell (first (vec (hiccup/node-children row)))]
                                     (is (nil? (hiccup/find-first-node time-cell #(= :a (first %)))))
                                     (hiccup/collect-strings time-cell))))
                            (reduce into #{}))]
    (doseq [expected expected-times]
      (is (contains? rendered-times expected)))))
