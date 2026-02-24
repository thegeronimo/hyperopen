(ns hyperopen.views.account-info.tabs.positions-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info-view :as view]))

(defn- reset-positions-sort-cache-fixture
  [f]
  (positions-tab/reset-positions-sort-cache!)
  (f)
  (positions-tab/reset-positions-sort-cache!))

(use-fixtures :each reset-positions-sort-cache-fixture)

(deftest position-headers-use-secondary-text-and-hover-affordance-test
  (let [position-header-node (view/position-table-header fixtures/default-sort-state)
        position-coin-header (hiccup/find-first-node position-header-node
                                              #(contains? (hiccup/direct-texts %) "Coin"))
        sortable-node (view/sortable-header "Coin" fixtures/default-sort-state)]
    (is (some? position-coin-header))
    (is (contains? (hiccup/node-class-set sortable-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set sortable-node) "hover:text-trading-text"))))

(deftest position-header-coin-cell-includes-left-padding-class-test
  (let [position-header-node (view/position-table-header fixtures/default-sort-state)
        header-cells (vec (hiccup/node-children position-header-node))
        coin-header-cell (nth header-cells 0)]
    (is (contains? (hiccup/node-class-set coin-header-cell) "pl-3"))))

(deftest positions-tab-content-memoizes-sorting-by-input-identity-and-sort-state-test
  (let [positions [fixtures/sample-position-data]
        sort-state {:column "Coin" :direction :asc}
        sort-calls (atom 0)]
    (positions-tab/reset-positions-sort-cache!)
    (with-redefs [positions-tab/sort-positions-by-column
                  (fn [rows _column _direction]
                    (swap! sort-calls inc)
                    rows)]
      (view/positions-tab-content positions sort-state)
      (view/positions-tab-content positions sort-state)
      (is (= 1 @sort-calls))

      (let [desc-state (assoc sort-state :direction :desc)]
        (view/positions-tab-content positions desc-state)
        (view/positions-tab-content positions desc-state)
        (is (= 2 @sort-calls))

        (view/positions-tab-content (into [] positions) desc-state)
        (is (= 3 @sort-calls))))))

(deftest positions-tab-content-does-not-render-legacy-subheader-row-test
  (let [webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}}
        content (view/positions-tab-content webdata2 fixtures/default-sort-state {})
        title-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Positions ("))
        active-positions-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Active positions"))]
    (is (nil? title-node))
    (is (nil? active-positions-node))))

(deftest position-table-uses-left-alignment-for-value-columns-test
  (let [header-node (view/position-table-header fixtures/default-sort-state)
        header-cells (vec (hiccup/node-children header-node))
        row-node (view/position-row fixtures/sample-position-data)
        row-cells (vec (hiccup/node-children row-node))]
    (doseq [idx (range 1 9)]
      (is (contains? (hiccup/node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx (range 1 9)]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left")))
    (doseq [idx [9]]
      (is (contains? (hiccup/node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx [9]]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left")))))

(deftest position-size-format-removes-leverage-and-uses-base-symbol-test
  (is (= "0.500 NVDA"
         (view/format-position-size {:coin "xyz:NVDA"
                                     :szi "0.500"
                                     :leverage {:value 10}})))
  (is (= "0.500 NVDA"
         (view/format-position-size {:coin "NVDA"
                                     :szi "0.500"
                                     :leverage {:value 10}})))
  (is (= "0.500 NVDA"
         (view/format-position-size {:coin "xyz:NVDA"
                                     :szi "-0.500"
                                     :leverage {:value 10}}))))

(deftest position-row-renders-green-leverage-and-dex-chips-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (hiccup/node-children row-node))
        coin-cell (nth row-cells 0)
        size-cell (nth row-cells 1)
        coin-strings (set (hiccup/collect-strings coin-cell))
        leverage-chip (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "10x"))
        dex-chip (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "xyz"))
        expected-chip-classes #{"bg-emerald-500/20" "text-emerald-300" "border-emerald-500/30"}]
    (is (contains? coin-strings "NVDA"))
    (is (contains? coin-strings "10x"))
    (is (contains? coin-strings "xyz"))
    (is (not (contains? coin-strings "xyz:NVDA")))
    (is (= #{"0.500 NVDA"} (set (hiccup/collect-strings size-cell))))
    (is (contains? (hiccup/node-class-set size-cell) "text-success"))
    (is (every? #(contains? (hiccup/node-class-set leverage-chip) %) expected-chip-classes))
    (is (every? #(contains? (hiccup/node-class-set dex-chip) %) expected-chip-classes))))

(deftest position-row-renders-red-gradient-and-absolute-size-for-shorts-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "-0.500"))
        row-cells (vec (hiccup/node-children row-node))
        coin-cell (nth row-cells 0)
        size-cell (nth row-cells 1)
        coin-label-node (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "NVDA"))
        leverage-chip (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "10x"))
        expected-background "transparent linear-gradient(90deg, rgb(237, 112, 136) 0px, rgb(237, 112, 136) 4px, rgba(52, 36, 46, 1) 0%, transparent 100%)"
        expected-chip-classes #{"bg-red-500/20" "text-red-300" "border-red-500/30"}]
    (is (= expected-background
           (get-in coin-cell [1 :style :background])))
    (is (= "12px" (get-in coin-cell [1 :style :padding-left])))
    (is (contains? (hiccup/node-class-set coin-label-node) "text-red-300"))
    (is (contains? (hiccup/node-class-set size-cell) "text-error"))
    (is (= #{"0.500 NVDA"} (set (hiccup/collect-strings size-cell))))
    (is (not-any? #(str/includes? % "-0.500") (hiccup/collect-strings size-cell)))
    (is (every? #(contains? (hiccup/node-class-set leverage-chip) %) expected-chip-classes))))

(deftest position-row-coin-cell-uses-hyperliquid-gradient-background-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        coin-cell (first (vec (hiccup/node-children row-node)))
        expected-background "linear-gradient(90deg, rgb(31, 166, 125) 0px, rgb(31, 166, 125) 4px, rgb(11, 50, 38) 4px, transparent 100%) transparent"]
    (is (= expected-background
           (get-in coin-cell [1 :style :background])))
    (is (= "12px" (get-in coin-cell [1 :style :padding-left])))
    (is (nil? (get-in coin-cell [1 :style :margin-left])))
    (is (nil? (get-in coin-cell [1 :style :padding-right])))
    (is (contains? (hiccup/node-class-set coin-cell) "self-stretch"))))

(deftest position-row-dedupes-explicit-and-prefixed-dex-label-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500" "xyz"))
        coin-cell (first (vec (hiccup/node-children row-node)))
        coin-strings (hiccup/collect-strings coin-cell)]
    (is (= 1 (count (filter #(= "xyz" %) coin-strings))))
    (is (contains? (set coin-strings) "NVDA"))
    (is (contains? (set coin-strings) "10x"))))

(deftest sort-positions-by-column-uses-mark-price-over-entry-price-test
  (let [positions [{:position {:coin "AAA"
                               :entryPx "100"
                               :markPx "90"}}
                   {:position {:coin "BBB"
                               :entryPx "10"
                               :markPx "120"}}
                   {:position {:coin "CCC"
                               :entryPx "75"}}]
        asc-result (view/sort-positions-by-column positions "Mark Price" :asc)
        desc-result (view/sort-positions-by-column positions "Mark Price" :desc)]
    (is (= ["CCC" "AAA" "BBB"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["BBB" "AAA" "CCC"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest position-row-uses-safe-placeholders-for-invalid-pnl-and-funding-values-test
  (let [row-node (view/position-row {:position {:coin "HYPE"
                                                :leverage {:value 3}
                                                :szi "1.0"
                                                :positionValue "100"
                                                :entryPx "10"
                                                :markPx "11"
                                                :unrealizedPnl "not-a-number"
                                                :returnOnEquity "invalid"
                                                :liquidationPx nil
                                                :marginUsed "40"
                                                :cumFunding {:allTime "oops"}}})
        row-cells (vec (hiccup/node-children row-node))
        pnl-cell (nth row-cells 5)
        funding-cell (nth row-cells 8)
        pnl-strings (set (hiccup/collect-strings pnl-cell))
        funding-strings (set (hiccup/collect-strings funding-cell))
        funding-value-node (hiccup/find-first-node funding-cell #(= :span (first %)))]
    (is (contains? pnl-strings "--"))
    (is (contains? funding-strings "--"))
    (is (not-any? #(str/includes? % "NaN") (hiccup/collect-strings row-node)))
    (is (contains? (hiccup/node-class-set funding-value-node) "text-trading-text"))))

(deftest position-row-renders-pnl-on-one-line-with-inline-percent-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (hiccup/node-children row-node))
        pnl-cell (nth row-cells 5)
        pnl-texts (set (hiccup/collect-strings pnl-cell))]
    (is (contains? pnl-texts "+$1.25 (+10.0%)"))
    (is (nil? (hiccup/find-first-node pnl-cell #(contains? (hiccup/node-class-set %) "text-xs"))))))

(deftest position-headers-render-tooltip-affordance-for-pnl-margin-and-funding-test
  (let [header-node (view/position-table-header fixtures/default-sort-state)
        pnl-header-label (hiccup/find-first-node header-node #(and (contains? (hiccup/direct-texts %) "PNL (ROE %)")
                                                                   (contains? (hiccup/node-class-set %) "underline")))
        margin-header-label (hiccup/find-first-node header-node #(and (contains? (hiccup/direct-texts %) "Margin")
                                                                      (contains? (hiccup/node-class-set %) "underline")))
        funding-header-label (hiccup/find-first-node header-node #(and (contains? (hiccup/direct-texts %) "Funding")
                                                                       (contains? (hiccup/node-class-set %) "underline")))
        header-strings (set (hiccup/collect-strings header-node))]
    (is (some? pnl-header-label))
    (is (some? margin-header-label))
    (is (some? funding-header-label))
    (is (contains? header-strings "Unrealized PNL with return on equity (ROE) shown in parentheses."))
    (is (contains? header-strings "Margin currently allocated to this position."))
    (is (contains? header-strings "Funding paid or received for this position."))))

(deftest position-row-tpsl-cell-includes-edit-affordance-icon-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (hiccup/node-children row-node))
        tpsl-cell (nth row-cells 9)
        edit-icon-node (hiccup/find-first-node tpsl-cell #(= :svg (first %)))
        tpsl-strings (set (hiccup/collect-strings tpsl-cell))]
    (is (contains? tpsl-strings "-- / --"))
    (is (some? edit-icon-node))))

(deftest position-table-layout-prioritizes-coin-column-over-right-edge-actions-test
  (let [grid-template-class "grid-cols-[minmax(170px,1.9fr)_minmax(130px,1.2fr)_minmax(110px,1fr)_minmax(110px,1fr)_minmax(110px,1fr)_minmax(130px,1.3fr)_minmax(110px,1fr)_minmax(100px,1fr)_minmax(100px,1fr)_minmax(80px,0.8fr)]"
        header-node (view/position-table-header fixtures/default-sort-state)
        row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        coin-cell (first (vec (hiccup/node-children row-node)))
        coin-label-node (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "NVDA"))]
    (is (contains? (hiccup/node-class-set header-node) grid-template-class))
    (is (contains? (hiccup/node-class-set header-node) "min-w-[1140px]"))
    (is (contains? (hiccup/node-class-set row-node) grid-template-class))
    (is (contains? (hiccup/node-class-set row-node) "min-w-[1140px]"))
    (is (contains? (hiccup/node-class-set coin-label-node) "whitespace-nowrap"))
    (is (not (contains? (hiccup/node-class-set coin-label-node) "truncate")))))
