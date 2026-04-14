(ns hyperopen.views.account-info.tabs.balances.content-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.tabs.balances.test-support :as test-support]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

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
        all-content (test-support/render-balances-tab rows false sort-state)
        fuzzy-content (test-support/render-balances-tab rows false sort-state "nd")
        exact-content (test-support/render-balances-tab rows false sort-state "USDC")
        fuzzy-coins (hiccup/balance-tab-coins fuzzy-content)
        exact-coins (hiccup/balance-tab-coins exact-content)]
    (is (= ["NVDA" "SOL" "USDC (Spot)"]
           (hiccup/balance-tab-coins all-content)))
    (is (= ["NVDA"] fuzzy-coins))
    (is (= ["USDC (Spot)"] exact-coins))))

(deftest balances-tab-content-without-active-sort-preserves-input-order-test
  (let [rows [{:key "row-1" :coin "HYPE" :total-balance 1 :available-balance 1 :usdc-value 1}
              {:key "row-2" :coin "USDC (Spot)" :total-balance 2 :available-balance 2 :usdc-value 2}
              {:key "row-3" :coin "BTC" :total-balance 3 :available-balance 3 :usdc-value 3}]
        tab-content (test-support/render-balances-tab rows false {:column nil :direction :asc})]
    (is (= ["HYPE" "USDC (Spot)" "BTC"]
           (hiccup/balance-tab-coins tab-content)))))

(deftest balances-tab-content-does-not-render-legacy-subheader-row-test
  (let [content (test-support/render-balances-tab [fixtures/sample-balance-row] false fixtures/default-sort-state)
        title-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Balances ("))
        filter-label-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Hide Small Balances"))]
    (is (nil? title-node))
    (is (nil? filter-label-node))))

(deftest balances-tab-viewport-has-no-artificial-top-gap-test
  (let [content (test-support/render-balances-tab [fixtures/sample-balance-row] false fixtures/default-sort-state)
        rows-viewport-classes (hiccup/node-class-set (hiccup/tab-rows-viewport-node content))]
    (is (not (contains? rows-viewport-classes "-mt-8")))
    (is (not (contains? rows-viewport-classes "pt-12")))))

(deftest balances-tab-content-read-only-mode-omits-mutation-columns-and-mobile-actions-test
  (let [row (assoc fixtures/sample-balance-row
                   :key "usdc"
                   :coin "USDC"
                   :selection-coin "USDC"
                   :contract-id "0x1234567890abcdef1234567890abcdef12345678")
        desktop-content (test-support/render-balances-tab [row]
                                                          false
                                                          fixtures/default-sort-state
                                                          ""
                                                          {:read-only? true})
        desktop-header-strings (set (hiccup/collect-strings (hiccup/tab-header-node desktop-content)))
        desktop-row (hiccup/first-viewport-row desktop-content)
        desktop-row-buttons (hiccup/find-all-nodes desktop-row #(= :button (first %)))
        desktop-row-cells (vec (hiccup/node-children desktop-row))
        mobile-content (test-support/render-balances-tab [row]
                                                         false
                                                         fixtures/default-sort-state
                                                         ""
                                                         {:read-only? true
                                                          :mobile-expanded-card {:balances "usdc"}})
        mobile-card (hiccup/find-by-data-role mobile-content "mobile-balance-card-usdc")
        mobile-strings (set (hiccup/collect-strings mobile-card))]
    (is (not (contains? desktop-header-strings "Send")))
    (is (not (contains? desktop-header-strings "Transfer")))
    (is (not (contains? desktop-header-strings "Repay")))
    (is (= 6 (count desktop-row-cells)))
    (is (= 1 (count desktop-row-buttons)))
    (is (contains? mobile-strings "Contract"))
    (is (not (contains? mobile-strings "Send")))
    (is (not (contains? mobile-strings "Transfer to Perps")))))
