(ns hyperopen.views.account-info.tabs.balances.desktop-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as account-table]
            [hyperopen.views.account-info.tabs.balances :as balances-tab]
            [hyperopen.views.account-info.tabs.balances.test-support :as test-support]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(deftest balances-header-contrast-test
  (let [header-node (balances-tab/balance-table-header fixtures/default-sort-state)
        sortable-node (balances-tab/sortable-balances-header "Coin" fixtures/default-sort-state)
        sortable-left-node (balances-tab/sortable-balances-header "Total Balance" fixtures/default-sort-state :left)
        non-sortable-node (account-table/non-sortable-header "Send")
        non-sortable-center-node (account-table/non-sortable-header "Send" :center)]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text"))
    (is (contains? (hiccup/node-class-set sortable-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set sortable-node) "justify-start"))
    (is (contains? (hiccup/node-class-set sortable-left-node) "justify-start"))
    (is (contains? (hiccup/node-class-set sortable-node) "hover:text-trading-text"))
    (is (contains? (hiccup/node-class-set non-sortable-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set non-sortable-center-node) "justify-center"))))

(deftest balances-header-includes-contract-column-as-final-cell-test
  (let [header-node (balances-tab/balance-table-header fixtures/default-sort-state)
        header-cells (vec (hiccup/node-children header-node))
        header-labels (mapv #(first (hiccup/collect-strings %)) header-cells)]
    (is (= 9 (count header-cells)))
    (is (= ["Coin"
            "Total Balance"
            "Available Balance"
            "USDC Value"
            "PNL (ROE %)"
            "Send"
            "Transfer"
            "Repay"
            "Contract"]
           header-labels))))

(deftest sort-balances-by-column-keeps-usdc-partition-on-top-for-coin-sort-test
  (let [rows [{:key "spot-usdc" :coin "USDC (Spot)"}
              {:key "hype" :coin "HYPE"}
              {:key "perps-usdc" :coin "USDC (Perps)"}
              {:key "btc" :coin "BTC"}]
        coin-asc (balances-tab/sort-balances-by-column rows "Coin" :asc)
        coin-desc (balances-tab/sort-balances-by-column rows "Coin" :desc)]
    (is (= ["USDC (Perps)" "USDC (Spot)" "BTC" "HYPE"]
           (mapv :coin coin-asc)))
    (is (= ["USDC (Spot)" "USDC (Perps)" "HYPE" "BTC"]
           (mapv :coin coin-desc)))))

(deftest sort-balances-by-column-keeps-usdc-partition-on-top-for-numeric-columns-test
  (let [rows [{:key "usdc-low" :coin "USDC (Spot)" :usdc-value 5}
              {:key "coin-high" :coin "AAA" :usdc-value 1000}
              {:key "usdc-high" :coin "USDC (Perps)" :usdc-value 100}
              {:key "coin-low" :coin "BBB" :usdc-value 1}]
        value-asc (balances-tab/sort-balances-by-column rows "USDC Value" :asc)
        value-desc (balances-tab/sort-balances-by-column rows "USDC Value" :desc)]
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
        asc-result (balances-tab/sort-balances-by-column rows "USDC Value" :asc)
        desc-result (balances-tab/sort-balances-by-column rows "USDC Value" :desc)
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

(deftest balance-row-primary-value-and-action-contrast-test
  (let [row-node (balances-tab/balance-row (assoc fixtures/sample-balance-row :selection-coin "USDC"))
        coin-node (first (vec (hiccup/node-children row-node)))
        send-button-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Send"))
        transfer-button-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Transfer"))]
    (is (contains? (hiccup/node-class-set row-node) "text-trading-text"))
    (is (contains? (hiccup/node-class-set coin-node) "font-semibold"))
    (is (contains? (hiccup/node-class-set send-button-node) "text-trading-text"))
    (is (contains? (hiccup/node-class-set transfer-button-node) "text-trading-text"))))

(deftest balance-row-coin-cell-dispatches-select-asset-action-test
  (let [row-node (balances-tab/balance-row (assoc fixtures/sample-balance-row
                                                  :coin "USDC (Spot)"
                                                  :selection-coin "USDC"))
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (= :button (first coin-cell)))
    (is (= [[:actions/select-asset "USDC"]]
           (get-in coin-cell [1 :on :click])))))

(deftest balance-row-renders-unified-transfer-disabled-label-test
  (let [row-node (balances-tab/balance-row (assoc fixtures/sample-balance-row
                                                  :coin "USDC"
                                                  :transfer-disabled? true))
        transfer-label-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Unified"))
        transfer-button-node (hiccup/find-first-node row-node #(contains? (hiccup/direct-texts %) "Transfer"))]
    (is (some? transfer-label-node))
    (is (contains? (hiccup/node-class-set transfer-label-node) "text-trading-text-secondary"))
    (is (nil? transfer-button-node))))

(deftest balance-row-unified-available-balance-renders-dashed-tooltip-test
  (let [row-node (balances-tab/balance-row {:key "unified-usdc"
                                            :coin "USDC"
                                            :total-balance 204.419365
                                            :available-balance 201.389365
                                            :usdc-value 204.41
                                            :pnl-value nil
                                            :pnl-pct nil
                                            :amount-decimals 8
                                            :transfer-disabled? true})
        tooltip-trigger-node (hiccup/find-first-node row-node #(and (contains? (hiccup/node-class-set %) "decoration-dashed")
                                                                     (contains? (hiccup/direct-texts %) "201.38936500 USDC")))
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

(deftest balance-row-renders-balance-units-and-placeholder-repay-column-test
  (let [row-node (balances-tab/balance-row (assoc fixtures/sample-balance-row :selection-coin "USDC"))
        row-cells (vec (hiccup/node-children row-node))
        total-cell (nth row-cells 1)
        available-cell (nth row-cells 2)
        repay-cell (nth row-cells 7)]
    (is (= 9 (count row-cells)))
    (is (= "150.12 USDC"
           (->> (hiccup/collect-strings total-cell)
                (remove str/blank?)
                (str/join " "))))
    (is (= "120.45 USDC"
           (->> (hiccup/collect-strings available-cell)
                (remove str/blank?)
                (str/join " "))))
    (is (empty? (remove str/blank? (hiccup/collect-strings repay-cell))))))

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
        content (test-support/render-balances-tab rows false fixtures/default-sort-state)
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
        row-node (balances-tab/balance-row (assoc fixtures/sample-balance-row :contract-id contract-id))
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
  (let [missing-row (balances-tab/balance-row fixtures/sample-balance-row)
        invalid-row (balances-tab/balance-row (assoc fixtures/sample-balance-row :contract-id "bad id"))
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
        short-row (balances-tab/balance-row (assoc fixtures/sample-balance-row :contract-id short-id))
        no-prefix-row (balances-tab/balance-row (assoc fixtures/sample-balance-row :contract-id no-prefix-id))
        short-strings (set (hiccup/collect-strings (hiccup/balance-row-contract-cell short-row)))
        no-prefix-strings (set (hiccup/collect-strings (hiccup/balance-row-contract-cell no-prefix-row)))]
    (is (contains? short-strings short-id))
    (is (contains? no-prefix-strings "abcd...mnop"))))

(deftest balance-pnl-color-and-placeholder-contrast-test
  (testing "pnl uses success/error colors and white placeholders"
    (let [positive (shared/format-pnl 2.0 1.5)
          negative (shared/format-pnl -3.0 -2.0)
          zero (shared/format-pnl 0 0)
          missing (shared/format-pnl nil nil)]
      (is (contains? (hiccup/node-class-set positive) "text-success"))
      (is (contains? (hiccup/node-class-set negative) "text-error"))
      (is (contains? (hiccup/node-class-set zero) "text-trading-text"))
      (is (contains? (hiccup/node-class-set missing) "text-trading-text")))))

(deftest balances-and-positions-values-use-semibold-weight-test
  (let [balance-row-node (balances-tab/balance-row fixtures/sample-balance-row)
        position-row-node (positions-tab/position-row fixtures/sample-position-data)
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
  (let [row-node (balances-tab/balance-row fixtures/sample-balance-row)
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (nil? (get-in coin-cell [1 :style :background])))))

(deftest balance-row-non-usdc-coin-uses-highlight-color-test
  (let [row-node (balances-tab/balance-row (assoc fixtures/sample-balance-row :coin "HYPE"))
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (= "rgb(151, 252, 228)"
           (get-in coin-cell [1 :style :color])))))

(deftest balance-row-usdc-coin-keeps-default-color-test
  (let [row-node (balances-tab/balance-row (assoc fixtures/sample-balance-row :coin "USDC (Perps)"))
        coin-cell (first (vec (hiccup/node-children row-node)))]
    (is (nil? (get-in coin-cell [1 :style :color])))))
