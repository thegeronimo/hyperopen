(ns hyperopen.views.account-info.tabs.balances-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info-view :as view]))

(deftest balances-header-contrast-test
  (let [header-node (view/balance-table-header fixtures/default-sort-state)
        sortable-node (view/sortable-balances-header "Coin" fixtures/default-sort-state)
        sortable-left-node (view/sortable-balances-header "Total Balance" fixtures/default-sort-state :left)
        non-sortable-node (view/non-sortable-header "Send")
        non-sortable-center-node (view/non-sortable-header "Send" :center)]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text"))
    (is (contains? (hiccup/node-class-set sortable-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set sortable-node) "justify-start"))
    (is (contains? (hiccup/node-class-set sortable-left-node) "justify-start"))
    (is (contains? (hiccup/node-class-set sortable-node) "hover:text-trading-text"))
    (is (contains? (hiccup/node-class-set non-sortable-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set non-sortable-center-node) "justify-center"))))

(deftest balances-header-includes-contract-column-as-final-cell-test
  (let [header-node (view/balance-table-header fixtures/default-sort-state)
        header-cells (vec (hiccup/node-children header-node))
        header-labels (mapv #(first (hiccup/collect-strings %)) header-cells)]
    (is (= 8 (count header-cells)))
    (is (= ["Coin"
            "Total Balance"
            "Available Balance"
            "USDC Value"
            "PNL (ROE %)"
            "Send"
            "Transfer"
            "Contract"]
           header-labels))))

(deftest sort-balances-by-column-keeps-usdc-partition-on-top-for-coin-sort-test
  (let [rows [{:key "spot-usdc" :coin "USDC (Spot)"}
              {:key "hype" :coin "HYPE"}
              {:key "perps-usdc" :coin "USDC (Perps)"}
              {:key "btc" :coin "BTC"}]
        coin-asc (view/sort-balances-by-column rows "Coin" :asc)
        coin-desc (view/sort-balances-by-column rows "Coin" :desc)]
    (is (= ["USDC (Perps)" "USDC (Spot)" "BTC" "HYPE"]
           (mapv :coin coin-asc)))
    (is (= ["USDC (Spot)" "USDC (Perps)" "HYPE" "BTC"]
           (mapv :coin coin-desc)))))

(deftest sort-balances-by-column-keeps-usdc-partition-on-top-for-numeric-columns-test
  (let [rows [{:key "usdc-low" :coin "USDC (Spot)" :usdc-value 5}
              {:key "coin-high" :coin "AAA" :usdc-value 1000}
              {:key "usdc-high" :coin "USDC (Perps)" :usdc-value 100}
              {:key "coin-low" :coin "BBB" :usdc-value 1}]
        value-asc (view/sort-balances-by-column rows "USDC Value" :asc)
        value-desc (view/sort-balances-by-column rows "USDC Value" :desc)]
    (is (= ["USDC (Spot)" "USDC (Perps)" "BBB" "AAA"]
           (mapv :coin value-asc)))
    (is (= ["USDC (Perps)" "USDC (Spot)" "AAA" "BBB"]
           (mapv :coin value-desc)))))

(deftest sort-balances-by-column-is-deterministic-on-ties-with-coin-then-key-test
  (let [rows [{:key "u-b" :coin "USDC Beta" :usdc-value 10}
              {:key "u-a2" :coin "USDC Alpha" :usdc-value 10}
              {:key "u-a1" :coin "USDC Alpha" :usdc-value 10}
              {:key "n-z" :coin "ZZZ" :usdc-value 20}
              {:key "n-a2" :coin "AAA" :usdc-value 20}
              {:key "n-a1" :coin "AAA" :usdc-value 20}]
        asc-result (view/sort-balances-by-column rows "USDC Value" :asc)
        desc-result (view/sort-balances-by-column rows "USDC Value" :desc)
        expected [["USDC Alpha" "u-a1"]
                  ["USDC Alpha" "u-a2"]
                  ["USDC Beta" "u-b"]
                  ["AAA" "n-a1"]
                  ["AAA" "n-a2"]
                  ["ZZZ" "n-z"]]]
    (is (= expected
           (mapv (juxt :coin :key) asc-result)))
    (is (= expected
           (mapv (juxt :coin :key) desc-result)))))

(deftest balances-tab-content-filters-by-fuzzy-coin-search-test
  (let [rows [{:key "nvda"
               :coin "NVDA"
               :selection-coin "xyz:NVDA"
               :total-balance 1
               :available-balance 1
               :usdc-value 10
               :pnl-value 0
               :pnl-pct 0
               :amount-decimals 2}
              {:key "sol"
               :coin "SOL"
               :total-balance 2
               :available-balance 2
               :usdc-value 20
               :pnl-value 0
               :pnl-pct 0
               :amount-decimals 2}
              {:key "usdc"
               :coin "USDC (Spot)"
               :total-balance 3
               :available-balance 3
               :usdc-value 30
               :pnl-value 0
               :pnl-pct 0
               :amount-decimals 2}]
        sort-state {:column nil :direction :asc}
        all-content (view/balances-tab-content rows false sort-state "")
        fuzzy-content (view/balances-tab-content rows false sort-state "nd")
        exact-content (view/balances-tab-content rows false sort-state "USDC")
        fuzzy-coins (hiccup/balance-tab-coins fuzzy-content)
        exact-coins (hiccup/balance-tab-coins exact-content)]
    (is (= ["NVDA" "SOL" "USDC (Spot)"]
           (hiccup/balance-tab-coins all-content)))
    (is (= ["NVDA"] fuzzy-coins))
    (is (= ["USDC (Spot)"] exact-coins))))

(deftest build-balance-rows-attaches-contract-id-for-non-usdc-spot-and-leaves-usdc-rows-empty-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "12.5"
                                                    :totalMarginUsed "2.5"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8
                                :tokenId "0x11111111111111111111111111111111"}
                               {:index 1
                                :name "HYPE"
                                :weiDecimals 5
                                :tokenId "0x22222222222222222222222222222222"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (nil? (:contract-id (get by-coin "USDC (Perps)"))))
    (is (nil? (:contract-id (get by-coin "USDC (Spot)"))))
    (is (= 5.0
           (view/parse-num (:usdc-value (get by-coin "USDC (Spot)")))))
    (is (= "0x22222222222222222222222222222222"
           (:contract-id (get by-coin "HYPE"))))))

(deftest build-balance-rows-attaches-contract-id-when-token-reference-is-symbol-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:name "MEOW"
                                :weiDecimals 5
                                :contractAddress "0x3333333333333333333333333333333333333333"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "MEOW"
                                                 :token "MEOW"
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0x3333333333333333333333333333333333333333"
           (:contract-id (get by-coin "MEOW"))))))

(deftest build-balance-rows-attaches-contract-id-from-balance-payload-keys-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "HYPE"
                                :weiDecimals 5}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 1
                                                 :tokenAddress "0x4444444444444444444444444444444444444444"
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0x4444444444444444444444444444444444444444"
           (:contract-id (get by-coin "HYPE"))))))

(deftest build-balance-rows-attaches-contract-id-from-nested-token-metadata-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "PUFF"
                                :weiDecimals 5
                                :tokenInfo {:evmContract {:address "0X5555555555555555555555555555555555555555"}}}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "PUFF"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0X5555555555555555555555555555555555555555"
           (:contract-id (get by-coin "PUFF"))))))

(deftest build-balance-rows-extracts-embedded-hex-contract-id-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "BOLT"
                                :weiDecimals 5
                                :contractAddress "eip155:42161/erc20:0x6666666666666666666666666666666666666666"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "BOLT"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (= "0x6666666666666666666666666666666666666666"
           (:contract-id (get by-coin "BOLT"))))))

(deftest build-balance-rows-ignores-generic-address-keys-for-contract-id-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "1.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 8}
                               {:index 1
                                :name "HYPE"
                                :weiDecimals 5
                                :address "0x7777777777777777777777777777777777777777"}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "5.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 1
                                                 :address "0x8888888888888888888888888888888888888888"
                                                 :hold "0.0"
                                                 :total "7.0"
                                                 :entryNtl "0"}]}})
        by-coin (into {} (map (juxt :coin identity)) rows)]
    (is (nil? (:contract-id (get by-coin "HYPE"))))))

(deftest build-balance-rows-unified-mode-prefers-spot-usdc-without-perps-double-count-test
  (let [rows (view/build-balance-rows-for-account
              {:clearinghouseState {:marginSummary {:accountValue "3.03"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs [{:markPx "1"}]}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6
                                :tokenId "0x11111111111111111111111111111111"}
                               {:index 1
                                :name "MEOW"
                                :weiDecimals 6
                                :tokenId "0x22222222222222222222222222222222"}]
                      :universe [{:tokens [0 0]
                                  :index 0}]}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "201.42"
                                                 :entryNtl "0"}
                                                {:coin "MEOW"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        by-coin (into {} (map (juxt :coin identity)) rows)
        usdc-row (get by-coin "USDC")]
    (is (= 2 (count rows)))
    (is (some? usdc-row))
    (is (= 201.42 (view/parse-num (:total-balance usdc-row))))
    (is (= 201.42 (view/parse-num (:usdc-value usdc-row))))
    (is (true? (:transfer-disabled? usdc-row)))
    (is (nil? (get by-coin "USDC (Spot)")))
    (is (nil? (get by-coin "USDC (Perps)")))))

(deftest build-balance-rows-unified-mode-falls-back-to-perps-usdc-when-spot-usdc-missing-test
  (let [rows (view/build-balance-rows-for-account
              {:clearinghouseState {:marginSummary {:accountValue "3.03"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6}
                               {:index 1
                                :name "MEOW"
                                :weiDecimals 6}]
                      :universe []}
               :clearinghouse-state {:balances [{:coin "MEOW"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        by-coin (into {} (map (juxt :coin identity)) rows)
        usdc-row (get by-coin "USDC")]
    (is (= 2 (count rows)))
    (is (some? usdc-row))
    (is (= 3.03 (view/parse-num (:total-balance usdc-row))))
    (is (= 3.03 (view/parse-num (:usdc-value usdc-row))))
    (is (true? (:transfer-disabled? usdc-row)))))

(deftest build-balance-rows-usdc-value-falls-back-to-coin-identity-when-meta-missing-test
  (let [rows (view/build-balance-rows-for-account
              {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs []}
              {:meta {:tokens []
                      :universe []}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "3.03000000"
                                                 :total "204.41936500"
                                                 :entryNtl "0"}]}}
              {:mode :unified
               :abstraction-raw "unifiedAccount"})
        usdc-row (first rows)]
    (is (= 1 (count rows)))
    (is (= "USDC" (:coin usdc-row)))
    (is (= 204.419365 (view/parse-num (:usdc-value usdc-row))))
    (is (= 201.389365 (view/parse-num (:available-balance usdc-row))))))

(deftest build-balance-rows-usdc-identity-invariant-holds-without-pricing-context-test
  (let [rows (view/build-balance-rows
              {:spotAssetCtxs nil}
              {:meta nil
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "204.41"
                                                 :entryNtl "0"}
                                                {:coin "USDC.e"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "0.04"
                                                 :entryNtl "0"}]}})
        usdc-like-rows (filter #(str/starts-with? (:coin %) "USDC") rows)]
    (is (= 2 (count usdc-like-rows)))
    (doseq [row usdc-like-rows]
      (is (= (view/parse-num (:total-balance row))
             (view/parse-num (:usdc-value row)))))))

(deftest build-balance-rows-filters-zero-balance-rows-by-default-test
  (let [rows (view/build-balance-rows
              {:clearinghouseState {:marginSummary {:accountValue "0.0"
                                                    :totalMarginUsed "0.0"}}
               :spotAssetCtxs [{:markPx "1"}]}
              {:meta {:tokens [{:index 0
                                :name "USDC"
                                :weiDecimals 6}
                               {:index 1
                                :name "USDE"
                                :weiDecimals 6}
                               {:index 2
                                :name "HYPE"
                                :weiDecimals 6}]
                      :universe [{:tokens [0 0]
                                  :index 0}]}
               :clearinghouse-state {:balances [{:coin "USDC"
                                                 :token 0
                                                 :hold "0.0"
                                                 :total "10.0"
                                                 :entryNtl "0"}
                                                {:coin "USDE"
                                                 :token 1
                                                 :hold "0.0"
                                                 :total "0.0"
                                                 :entryNtl "0"}
                                                {:coin "HYPE"
                                                 :token 2
                                                 :hold "0.0"
                                                 :total "1.0"
                                                 :entryNtl "0"}]}})
        coins (set (map :coin rows))]
    (is (contains? coins "USDC (Spot)"))
    (is (contains? coins "HYPE"))
    (is (not (contains? coins "USDE")))
    (is (not (contains? coins "USDC (Perps)")))))

(deftest balances-tab-content-without-active-sort-preserves-input-order-test
  (let [rows [{:key "row-1" :coin "HYPE" :total-balance 1 :available-balance 1 :usdc-value 1}
              {:key "row-2" :coin "USDC (Spot)" :total-balance 2 :available-balance 2 :usdc-value 2}
              {:key "row-3" :coin "BTC" :total-balance 3 :available-balance 3 :usdc-value 3}]
        tab-content (view/balances-tab-content rows false {:column nil :direction :asc})]
    (is (= ["HYPE" "USDC (Spot)" "BTC"]
           (hiccup/balance-tab-coins tab-content)))))

(deftest balances-tab-content-does-not-render-legacy-subheader-row-test
  (let [content (view/balances-tab-content [fixtures/sample-balance-row] false fixtures/default-sort-state)
        title-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Balances ("))
        filter-label-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Hide Small Balances"))]
    (is (nil? title-node))
    (is (nil? filter-label-node))))

(deftest balances-tab-viewport-has-no-artificial-top-gap-test
  (let [content (view/balances-tab-content [fixtures/sample-balance-row] false fixtures/default-sort-state)
        rows-viewport-classes (hiccup/node-class-set (hiccup/tab-rows-viewport-node content))]
    (is (not (contains? rows-viewport-classes "-mt-8")))
    (is (not (contains? rows-viewport-classes "pt-12")))))

(deftest balance-row-primary-value-and-action-contrast-test
  (let [row-node (view/balance-row fixtures/sample-balance-row)
        coin-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "USDC (Spot)"))
        send-button-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Send"))
        transfer-button-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Transfer"))]
    (is (contains? (hiccup/node-class-set row-node) "text-trading-text"))
    (is (contains? (hiccup/node-class-set coin-node) "font-semibold"))
    (is (contains? (hiccup/node-class-set send-button-node) "text-trading-text"))
    (is (contains? (hiccup/node-class-set transfer-button-node) "text-trading-text"))))

(deftest balance-row-coin-cell-dispatches-select-asset-action-test
  (let [row-node (view/balance-row (assoc fixtures/sample-balance-row
                                          :coin "USDC (Spot)"
                                          :selection-coin "USDC"))
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (= :button (first coin-cell)))
    (is (= [[:actions/select-asset "USDC"]]
           (get-in coin-cell [1 :on :click])))))

(deftest balance-row-renders-unified-transfer-disabled-label-test
  (let [row-node (view/balance-row (assoc fixtures/sample-balance-row
                                          :coin "USDC"
                                          :transfer-disabled? true))
        transfer-label-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Unified"))
        transfer-button-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Transfer"))]
    (is (some? transfer-label-node))
    (is (contains? (hiccup/node-class-set transfer-label-node) "text-trading-text-secondary"))
    (is (nil? transfer-button-node))))

(deftest balance-row-unified-available-balance-renders-dashed-tooltip-test
  (let [row-node (view/balance-row {:key "unified-usdc"
                                    :coin "USDC"
                                    :total-balance 204.419365
                                    :available-balance 201.389365
                                    :usdc-value 204.41
                                    :pnl-value nil
                                    :pnl-pct nil
                                    :amount-decimals 8
                                    :transfer-disabled? true})
        tooltip-trigger-node (hiccup/find-first-node row-node #(and (contains? (hiccup/node-class-set %) "decoration-dashed")
                                                              (contains? (hiccup/direct-texts %) "201.38936500")))
        tooltip-panel-node (hiccup/find-first-node row-node #(and (= :div (first %))
                                                           (contains? (hiccup/node-class-set %) "group-hover:opacity-100")))
        tooltip-panel-classes (hiccup/node-class-set tooltip-panel-node)
        tooltip-bubble-node (hiccup/find-first-node tooltip-panel-node #(and (= :div (first %))
                                                                      (contains? (hiccup/node-class-set %) "w-[520px]")))
        tooltip-bubble-classes (hiccup/node-class-set tooltip-bubble-node)
        tooltip-text (str/join " " (hiccup/collect-strings tooltip-panel-node))]
    (is (some? tooltip-trigger-node))
    (is (contains? (hiccup/node-class-set tooltip-trigger-node) "underline"))
    (is (contains? (hiccup/node-class-set tooltip-trigger-node) "underline-offset-2"))
    (is (some? tooltip-panel-node))
    (is (contains? tooltip-panel-classes "left-1/2"))
    (is (contains? tooltip-panel-classes "-translate-x-1/2"))
    (is (not (contains? tooltip-panel-classes "right-0")))
    (is (contains? tooltip-panel-classes "bottom-full"))
    (is (contains? tooltip-panel-classes "mb-2"))
    (is (contains? tooltip-panel-classes "z-[120]"))
    (is (not (contains? tooltip-panel-classes "top-full")))
    (is (some? tooltip-bubble-node))
    (is (contains? tooltip-bubble-classes "text-left"))
    (is (str/includes? tooltip-text "201.38936500 USDC is available to withdraw or transfer."))))

(deftest balances-tab-content-first-row-tooltip-falls-back-below-test
  (let [rows [{:key "unified-usdc"
               :coin "USDC"
               :total-balance 204.419365
               :available-balance 201.389365
               :usdc-value 204.41
               :pnl-value nil
               :pnl-pct nil
               :amount-decimals 8
               :transfer-disabled? true}
              (assoc fixtures/sample-balance-row :key "spot-usdc" :coin "USDC (Spot)")]
        content (view/balances-tab-content rows false fixtures/default-sort-state)
        first-row (hiccup/first-viewport-row content)
        tooltip-panel-node (hiccup/find-first-node first-row #(and (= :div (first %))
                                                            (contains? (hiccup/node-class-set %) "group-hover:opacity-100")))
        tooltip-panel-classes (hiccup/node-class-set tooltip-panel-node)]
    (is (some? tooltip-panel-node))
    (is (contains? tooltip-panel-classes "top-full"))
    (is (contains? tooltip-panel-classes "mt-2"))
    (is (not (contains? tooltip-panel-classes "bottom-full")))))

(deftest balance-row-contract-cell-renders-explorer-link-with-abbreviated-id-test
  (let [contract-id "0x1234567890abcdef"
        row-node (view/balance-row (assoc fixtures/sample-balance-row :contract-id contract-id))
        contract-cell (hiccup/balance-row-contract-cell row-node)
        link-node (hiccup/find-first-node contract-cell #(= :a (first %)))
        icon-node (hiccup/find-first-node contract-cell #(= :svg (first %)))
        strings (set (hiccup/collect-strings contract-cell))]
    (is (some? link-node))
    (is (some? icon-node))
    (is (= (str "https://app.hyperliquid.xyz/explorer/token/" contract-id)
           (get-in link-node [1 :href])))
    (is (= "_blank" (get-in link-node [1 :target])))
    (is (= "noopener noreferrer" (get-in link-node [1 :rel])))
    (is (contains? strings "0x1234...cdef"))))

(deftest balance-row-contract-cell-stays-blank-when-contract-id-missing-or-invalid-test
  (let [missing-row (view/balance-row fixtures/sample-balance-row)
        invalid-row (view/balance-row (assoc fixtures/sample-balance-row :contract-id "bad id"))
        missing-cell (hiccup/balance-row-contract-cell missing-row)
        invalid-cell (hiccup/balance-row-contract-cell invalid-row)
        missing-link (hiccup/find-first-node missing-cell #(= :a (first %)))
        invalid-link (hiccup/find-first-node invalid-cell #(= :a (first %)))]
    (is (some? missing-cell))
    (is (some? invalid-cell))
    (is (nil? missing-link))
    (is (nil? invalid-link))
    (is (empty? (hiccup/collect-strings missing-cell)))
    (is (empty? (hiccup/collect-strings invalid-cell)))))

(deftest balance-row-contract-abbreviation-rules-test
  (let [short-id "1234567890"
        no-prefix-id "abcdefghijklmnop"
        short-row (view/balance-row (assoc fixtures/sample-balance-row :contract-id short-id))
        no-prefix-row (view/balance-row (assoc fixtures/sample-balance-row :contract-id no-prefix-id))
        short-strings (set (hiccup/collect-strings (hiccup/balance-row-contract-cell short-row)))
        no-prefix-strings (set (hiccup/collect-strings (hiccup/balance-row-contract-cell no-prefix-row)))]
    (is (contains? short-strings short-id))
    (is (contains? no-prefix-strings "abcd...mnop"))))

(deftest balance-pnl-color-and-placeholder-contrast-test
  (testing "pnl uses success/error colors and white placeholders"
    (let [positive (view/format-pnl 2.0 1.5)
          negative (view/format-pnl -3.0 -2.0)
          zero (view/format-pnl 0 0)
          missing (view/format-pnl nil nil)]
      (is (contains? (hiccup/node-class-set positive) "text-success"))
      (is (contains? (hiccup/node-class-set negative) "text-error"))
      (is (contains? (hiccup/node-class-set zero) "text-trading-text"))
      (is (contains? (hiccup/node-class-set missing) "text-trading-text")))))

(deftest balances-and-positions-values-use-semibold-weight-test
  (let [balance-row-node (view/balance-row fixtures/sample-balance-row)
        position-row-node (view/position-row fixtures/sample-position-data)
        balance-value-node (hiccup/find-first-node balance-row-node
                                            #(let [classes (hiccup/node-class-set %)]
                                               (and (contains? classes "font-semibold")
                                                    (contains? classes "num"))))
        position-value-node (hiccup/find-first-node position-row-node
                                             #(let [classes (hiccup/node-class-set %)]
                                                (and (contains? classes "font-semibold")
                                                     (contains? classes "num"))))]
    (is (some? balance-value-node))
    (is (some? position-value-node))))

(deftest balance-row-coin-cell-does-not-use-position-gradient-background-test
  (let [row-node (view/balance-row fixtures/sample-balance-row)
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (nil? (get-in coin-cell [1 :style :background])))))

(deftest balance-row-non-usdc-coin-uses-highlight-color-test
  (let [row-node (view/balance-row (assoc fixtures/sample-balance-row :coin "HYPE"))
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (= "rgb(151, 252, 228)"
           (get-in coin-cell [1 :style :color])))))

(deftest balance-row-usdc-coin-keeps-default-color-test
  (let [row-node (view/balance-row (assoc fixtures/sample-balance-row :coin "USDC (Perps)"))
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (nil? (get-in coin-cell [1 :style :color])))))

(deftest balances-tab-content-renders-mobile-summary-cards-with-inline-expansion-test
  (let [rows [(assoc fixtures/sample-balance-row
                     :key "usdc"
                     :coin "USDC"
                     :total-balance 388.555675
                     :available-balance 388.555675
                     :usdc-value 388.55
                     :amount-decimals 8
                     :contract-id "0x1234567890abcdef1234567890abcdef12345678")
              {:key "meow"
               :coin "MEOW"
               :selection-coin "xyz:MEOW"
               :total-balance 34.634736
               :available-balance 34.634736
               :usdc-value 0.01
               :pnl-value 0
               :pnl-pct 0
               :amount-decimals 6}]
        content (view/balances-tab-content rows
                                           false
                                           fixtures/default-sort-state
                                           ""
                                           {:balances "usdc"})
        mobile-viewport (hiccup/find-by-data-role content "balances-mobile-cards-viewport")
        mobile-cards (vec (hiccup/node-children mobile-viewport))
        expanded-card (hiccup/find-by-data-role content "mobile-balance-card-usdc")
        collapsed-card (hiccup/find-by-data-role content "mobile-balance-card-meow")
        expanded-button (first (vec (hiccup/node-children expanded-card)))
        collapsed-button (first (vec (hiccup/node-children collapsed-card)))
        expanded-button-classes (hiccup/node-class-set expanded-button)
        summary-grid (hiccup/find-first-node expanded-button #(contains? (hiccup/node-class-set %) "grid-cols-[minmax(0,0.82fr)_minmax(0,0.8fr)_minmax(0,1.25fr)_auto]"))
        footer-divider (hiccup/find-first-node expanded-card #(and (= :div (first %))
                                                                   (contains? (hiccup/node-class-set %) "border-t")
                                                                   (contains? (hiccup/node-class-set %) "border-[#17313d]")
                                                                   (contains? (hiccup/node-class-set %) "pt-2.5")
                                                                   (contains? (set (hiccup/collect-strings %)) "Send")))
        send-button (hiccup/find-first-node expanded-card #(= [[:actions/open-funding-send-modal
                                                                {:token "USDC"
                                                                 :symbol "USDC"
                                                                 :prefix-label nil
                                                                 :max-amount 388.555675
                                                                 :max-display "388.55567500"
                                                                 :max-input "388.55567500"}
                                                                :event.currentTarget/bounds]]
                                                              (get-in % [1 :on :click])))
        total-balance-value (hiccup/find-first-node expanded-card #(and (= :div (first %))
                                                                        (contains? (hiccup/direct-texts %) "388.55567500 USDC")
                                                                        (contains? (hiccup/node-class-set %) "whitespace-nowrap")))
        namespace-chip (hiccup/find-first-node collapsed-card #(and (= :span (first %))
                                                                    (contains? (hiccup/direct-texts %) "xyz")))
        expanded-strings (set (hiccup/collect-strings expanded-card))
        collapsed-strings (set (hiccup/collect-strings collapsed-card))]
    (is (some? mobile-viewport))
    (is (= 2 (count mobile-cards)))
    (is (= true (get-in expanded-button [1 :aria-expanded])))
    (is (= [[:actions/toggle-account-info-mobile-card :balances "usdc"]]
           (get-in expanded-button [1 :on :click])))
    (is (some? summary-grid))
    (is (contains? expanded-button-classes "px-3.5"))
    (is (contains? expanded-button-classes "hover:bg-[#0c1b24]"))
    (is (contains? (hiccup/node-class-set expanded-card) "bg-[#08161f]"))
    (is (contains? (hiccup/node-class-set expanded-card) "border-[#17313d]"))
    (is (not (contains? (hiccup/node-class-set expanded-card) "bg-[#1b2429]")))
    (is (some? total-balance-value))
    (is (some? send-button))
    (is (some? namespace-chip))
    (is (some? footer-divider))
    (is (contains? (hiccup/node-class-set namespace-chip) "bg-[#242924]"))
    (is (contains? (hiccup/node-class-set namespace-chip) "border"))
    (is (contains? (hiccup/node-class-set namespace-chip) "rounded-lg"))
    (is (contains? expanded-strings "Coin"))
    (is (contains? expanded-strings "USDC Value"))
    (is (contains? expanded-strings "Total Balance"))
    (is (contains? expanded-strings "Available Balance"))
    (is (contains? expanded-strings "PNL (ROE %)"))
    (is (contains? expanded-strings "Contract"))
    (is (contains? expanded-strings "Send"))
    (is (contains? expanded-strings "Transfer to Perps"))
    (is (not (contains? expanded-strings "Actions")))
    (is (zero? (hiccup/count-nodes expanded-card #(contains? (hiccup/node-class-set %) "rounded-full"))))
    (is (= false (get-in collapsed-button [1 :aria-expanded])))
    (is (contains? collapsed-strings "MEOW"))
    (is (not (contains? collapsed-strings "Available Balance")))))
