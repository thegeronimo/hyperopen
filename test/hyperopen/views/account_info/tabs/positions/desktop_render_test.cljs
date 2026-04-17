(ns hyperopen.views.account-info.tabs.positions.desktop-render-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info.tabs.positions.test-support :as test-support]))

(use-fixtures :each test-support/reset-positions-sort-cache-fixture)

(deftest position-headers-use-secondary-text-and-hover-affordance-test
  (let [position-header-node (positions-tab/position-table-header fixtures/default-sort-state)
        position-coin-header (hiccup/find-first-node position-header-node
                                                     #(contains? (hiccup/direct-texts %) "Coin"))
        sortable-node (positions-tab/sortable-header "Coin" fixtures/default-sort-state)]
    (is (some? position-coin-header))
    (is (contains? (hiccup/node-class-set sortable-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set sortable-node) "hover:text-trading-text"))))

(deftest position-header-coin-cell-includes-left-padding-class-test
  (let [position-header-node (positions-tab/position-table-header fixtures/default-sort-state)
        header-cells (vec (hiccup/node-children position-header-node))
        coin-header-cell (nth header-cells 0)]
    (is (contains? (hiccup/node-class-set coin-header-cell) "pl-3"))))

(deftest position-table-uses-left-alignment-for-value-columns-test
  (let [header-node (positions-tab/position-table-header fixtures/default-sort-state)
        header-cells (vec (hiccup/node-children header-node))
        row-node (positions-tab/position-row fixtures/sample-position-data)
        row-cells (vec (hiccup/node-children row-node))]
    (doseq [idx (range 1 9)]
      (is (contains? (hiccup/node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx (range 1 9)]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left")))
    (doseq [idx [9 10]]
      (is (contains? (hiccup/node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx [9 10]]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left")))))

(deftest position-size-format-removes-leverage-and-uses-base-symbol-test
  (is (= "0.500 NVDA"
         (positions-tab/format-position-size {:coin "xyz:NVDA"
                                              :szi "0.500"
                                              :leverage {:value 10}})))
  (is (= "0.500 NVDA"
         (positions-tab/format-position-size {:coin "NVDA"
                                              :szi "0.500"
                                              :leverage {:value 10}})))
  (is (= "0.500 NVDA"
         (positions-tab/format-position-size {:coin "xyz:NVDA"
                                              :szi "-0.500"
                                              :leverage {:value 10}}))))

(deftest position-row-renders-green-leverage-and-dex-chips-test
  (let [row-node (positions-tab/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (hiccup/node-children row-node))
        coin-cell (nth row-cells 0)
        size-cell (nth row-cells 1)
        coin-strings (set (hiccup/collect-strings coin-cell))
        leverage-chip (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "10x"))
        dex-chip (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "xyz"))
        expected-chip-classes #{"bg-[#242924]" "text-emerald-300" "border-[#273035]"}]
    (is (contains? coin-strings "NVDA"))
    (is (contains? coin-strings "10x"))
    (is (contains? coin-strings "xyz"))
    (is (not (contains? coin-strings "xyz:NVDA")))
    (is (= #{"0.500 NVDA"} (set (hiccup/collect-strings size-cell))))
    (is (contains? (hiccup/node-class-set size-cell) "text-success"))
    (is (every? #(contains? (hiccup/node-class-set leverage-chip) %) expected-chip-classes))
    (is (every? #(contains? (hiccup/node-class-set dex-chip) %) expected-chip-classes))))

(deftest position-row-renders-red-gradient-and-absolute-size-for-shorts-test
  (let [row-node (positions-tab/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "-0.500"))
        row-cells (vec (hiccup/node-children row-node))
        coin-cell (nth row-cells 0)
        size-cell (nth row-cells 1)
        coin-label-node (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "NVDA"))
        leverage-chip (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "10x"))
        expected-background "transparent linear-gradient(90deg, rgb(237, 112, 136) 0px, rgb(237, 112, 136) 4px, rgba(52, 36, 46, 1) 0%, transparent 100%)"
        expected-chip-classes #{"bg-[#242924]" "text-red-300" "border-[#273035]"}]
    (is (= expected-background
           (get-in coin-cell [1 :style :background])))
    (is (= "12px" (get-in coin-cell [1 :style :padding-left])))
    (is (contains? (hiccup/node-class-set coin-label-node) "text-red-300"))
    (is (contains? (hiccup/node-class-set size-cell) "text-error"))
    (is (= #{"0.500 NVDA"} (set (hiccup/collect-strings size-cell))))
    (is (not-any? #(str/includes? % "-0.500") (hiccup/collect-strings size-cell)))
    (is (every? #(contains? (hiccup/node-class-set leverage-chip) %) expected-chip-classes))))

(deftest position-row-coin-cell-uses-hyperliquid-gradient-background-test
  (let [row-node (positions-tab/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        coin-cell (first (vec (hiccup/node-children row-node)))
        expected-background "linear-gradient(90deg, rgb(31, 166, 125) 0px, rgb(31, 166, 125) 4px, rgb(11, 50, 38) 4px, transparent 100%) transparent"]
    (is (= expected-background
           (get-in coin-cell [1 :style :background])))
    (is (= "12px" (get-in coin-cell [1 :style :padding-left])))
    (is (nil? (get-in coin-cell [1 :style :margin-left])))
    (is (nil? (get-in coin-cell [1 :style :padding-right])))
    (is (contains? (hiccup/node-class-set coin-cell) "self-stretch"))))

(deftest position-row-dedupes-explicit-and-prefixed-dex-label-test
  (let [row-node (positions-tab/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500" "xyz"))
        coin-cell (first (vec (hiccup/node-children row-node)))
        coin-strings (hiccup/collect-strings coin-cell)]
    (is (= 1 (count (filter #(= "xyz" %) coin-strings))))
    (is (contains? (set coin-strings) "NVDA"))
    (is (contains? (set coin-strings) "10x"))))

(deftest position-row-uses-safe-placeholders-for-invalid-pnl-and-funding-values-test
  (let [row-node (positions-tab/position-row {:position {:coin "HYPE"
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

(deftest position-row-mark-price-renders-placeholder-when-mark-is-missing-test
  (let [row-node (positions-tab/position-row {:position {:coin "NOMARK"
                                                         :leverage {:value 3}
                                                         :szi "1.0"
                                                         :positionValue "100"
                                                         :entryPx "10"
                                                         :markPx nil
                                                         :markPrice nil
                                                         :unrealizedPnl "1"
                                                         :returnOnEquity "0.1"
                                                         :liquidationPx "5"
                                                         :marginUsed "20"
                                                         :cumFunding {:allTime "0"}}})
        mark-cell (nth (vec (hiccup/node-children row-node)) 4)
        mark-strings (set (hiccup/collect-strings mark-cell))]
    (is (contains? mark-strings "--"))
    (is (not (contains? mark-strings "10.00")))))

(deftest position-row-renders-pnl-on-one-line-with-inline-percent-test
  (let [row-node (positions-tab/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (hiccup/node-children row-node))
        pnl-cell (nth row-cells 5)
        pnl-texts (set (hiccup/collect-strings pnl-cell))]
    (is (contains? pnl-texts "+$1.25 (+10.0%)"))
    (is (nil? (hiccup/find-first-node pnl-cell #(contains? (hiccup/node-class-set %) "text-xs"))))))

(deftest position-row-limits-liquidation-price-display-to-six-chars-test
  (let [row-data (-> (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                     (assoc-in [:position :liquidationPx] "5222.57562052"))
        row-node (positions-tab/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        liq-cell (nth row-cells 6)
        liq-texts (set (hiccup/collect-strings liq-cell))]
    (is (contains? liq-texts "$5,222.6"))
    (is (not-any? #(str/includes? % "57562052") liq-texts))))

(deftest position-headers-render-tooltip-affordance-for-pnl-margin-and-funding-test
  (let [header-node (positions-tab/position-table-header fixtures/default-sort-state)
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
    (is (contains? header-strings "Mark price is used to estimate unrealized PNL. Only trade prices are used for realized PNL."))
    (is (contains? header-strings "For isolated positions, margin includes unrealized pnl."))
    (is (contains? header-strings "Net funding payments since the position was opened. Hover for all-time and since changed."))))

(deftest position-row-funding-tooltip-uses-hyperliquid-copy-with-since-change-fallback-test
  (let [base-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        with-since-change-row (assoc-in base-row [:position :cumFunding] {:allTime "0.5"
                                                                           :sinceChange "0.25"})
        with-since-open-row (assoc-in base-row [:position :cumFunding] {:allTime "0.5"
                                                                         :sinceOpen "0.1"})
        without-since-change-row (assoc-in base-row [:position :cumFunding] {:allTime "0.5"})
        with-since-change-cell (nth (vec (hiccup/node-children (positions-tab/position-row with-since-change-row))) 8)
        with-since-open-cell (nth (vec (hiccup/node-children (positions-tab/position-row with-since-open-row))) 8)
        without-since-change-cell (nth (vec (hiccup/node-children (positions-tab/position-row without-since-change-row))) 8)
        with-since-change-strings (set (hiccup/collect-strings with-since-change-cell))
        with-since-open-strings (set (hiccup/collect-strings with-since-open-cell))
        without-since-change-strings (set (hiccup/collect-strings without-since-change-cell))]
    (is (contains? with-since-change-strings "All-time: -$0.50 Since change: -$0.25"))
    (is (contains? with-since-open-strings "All-time: -$0.50 Since change: -$0.10"))
    (is (contains? without-since-change-strings "All-time: -$0.50 Since change: --"))
    (is (not-any? #(str/includes? % "NaN") (hiccup/collect-strings with-since-change-cell)))
    (is (not-any? #(str/includes? % "NaN") (hiccup/collect-strings with-since-open-cell)))
    (is (not-any? #(str/includes? % "NaN") (hiccup/collect-strings without-since-change-cell)))))

(deftest position-row-funding-display-prefers-since-open-over-all-time-test
  (let [row-data (assoc-in (fixtures/sample-position-row "XRP" 20 "16517.0")
                           [:position :cumFunding]
                           {:allTime "-40929.847103"
                            :sinceOpen "0.718656"
                            :sinceChange "0.0"})
        funding-cell (nth (vec (hiccup/node-children (positions-tab/position-row row-data))) 8)
        funding-strings (set (hiccup/collect-strings funding-cell))
        visible-funding-node (hiccup/find-first-node funding-cell
                                                     #(and (= :span (first %))
                                                           (contains? (hiccup/node-class-set %) "text-error")))]
    (is (= #{"-$0.72"} (hiccup/direct-texts visible-funding-node)))
    (is (contains? funding-strings "All-time: $40,929.85 Since change: $0.00"))))

(deftest position-row-funding-display-shows-received-as-positive-test
  (let [row-data (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                           [:position :cumFunding]
                           {:allTime "-0.5"
                            :sinceChange "-0.25"})
        funding-cell (nth (vec (hiccup/node-children (positions-tab/position-row row-data))) 8)
        funding-strings (set (hiccup/collect-strings funding-cell))
        funding-value-node (hiccup/find-first-node funding-cell
                                                   #(and (= :span (first %))
                                                           (contains? (hiccup/node-class-set %) "text-success")))]
    (is (contains? funding-strings "$0.50"))
    (is (contains? funding-strings "All-time: $0.50 Since change: $0.25"))
    (is (some? funding-value-node))))

(deftest position-row-tpsl-cell-includes-edit-affordance-icon-test
  (let [row-node (positions-tab/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (hiccup/node-children row-node))
        tpsl-cell (nth row-cells 10)
        edit-icon-node (hiccup/find-first-node tpsl-cell #(= :svg (first %)))
        action-button (hiccup/find-first-node tpsl-cell #(= :button (first %)))
        value-node (hiccup/find-first-node tpsl-cell #(and (= :span (first %))
                                                            (contains? (hiccup/node-class-set %) "select-text")))
        tpsl-strings (set (hiccup/collect-strings tpsl-cell))]
    (is (contains? tpsl-strings "-- / --"))
    (is (some? edit-icon-node))
    (is (some? value-node))
    (is (contains? (hiccup/node-class-set action-button) "h-6"))
    (is (contains? (hiccup/node-class-set action-button) "w-6"))
    (is (contains? (hiccup/node-class-set edit-icon-node) "h-4"))
    (is (contains? (hiccup/node-class-set edit-icon-node) "w-4"))
    (is (= "1.6" (get-in edit-icon-node [1 :stroke-width])))
    (is (= "Edit TP/SL" (get-in action-button [1 :aria-label])))))

(deftest position-row-tpsl-cell-renders-derived-trigger-prices-when-present-test
  (let [row-data (-> (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                     (assoc :position-tp-trigger-px "12.5"
                            :position-sl-trigger-px "9.5"))
        row-node (positions-tab/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        tpsl-cell (nth row-cells 10)
        expected-copy (str (shared/format-trade-price "12.5")
                           " / "
                           (shared/format-trade-price "9.5"))
        tpsl-strings (set (hiccup/collect-strings tpsl-cell))]
    (is (contains? tpsl-strings expected-copy))
    (is (not (contains? tpsl-strings "-- / --")))))

(deftest position-row-margin-cell-renders-cross-and-isolated-mode-labels-test
  (let [isolated-row (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                               [:position :leverage :type]
                               "isolated")
        cross-row (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                            [:position :leverage :type]
                            "cross")
        isolated-cell (nth (vec (hiccup/node-children (positions-tab/position-row isolated-row))) 7)
        cross-cell (nth (vec (hiccup/node-children (positions-tab/position-row cross-row))) 7)
        isolated-strings (set (hiccup/collect-strings isolated-cell))
        cross-strings (set (hiccup/collect-strings cross-cell))]
    (is (contains? isolated-strings "(Isolated)"))
    (is (contains? cross-strings "(Cross)"))))

(deftest position-row-margin-cell-preserves-explicit-false-is-cross-flag-test
  (let [row-data (-> (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                     (assoc-in [:position :leverage :type] nil)
                     (assoc-in [:position :isCross] false))
        row-node (positions-tab/position-row row-data)
        margin-cell (nth (vec (hiccup/node-children row-node)) 7)
        margin-strings (set (hiccup/collect-strings margin-cell))
        action-button (hiccup/find-first-node margin-cell #(= :button (first %)))]
    (is (contains? margin-strings "(Isolated)"))
    (is (some? action-button))))

(deftest position-row-cross-margin-cell-omits-edit-affordance-test
  (let [row-data (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                           [:position :leverage :type]
                           "cross")
        row-node (positions-tab/position-row row-data)
        margin-cell (nth (vec (hiccup/node-children row-node)) 7)
        action-button (hiccup/find-first-node margin-cell #(= :button (first %)))
        margin-strings (set (hiccup/collect-strings margin-cell))]
    (is (contains? margin-strings "$12.00"))
    (is (contains? margin-strings "(Cross)"))
    (is (nil? action-button))))

(deftest position-table-layout-reclaims-right-edge-space-and-truncates-long-coin-labels-test
  (let [grid-template-class "grid-cols-[minmax(180px,2.15fr)_minmax(142px,1.34fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(114px,1.06fr)_minmax(88px,0.82fr)_minmax(124px,1.08fr)_minmax(80px,0.78fr)_minmax(94px,0.8fr)_minmax(146px,1.06fr)]"
        header-node (positions-tab/position-table-header fixtures/default-sort-state)
        row-node (positions-tab/position-row (fixtures/sample-position-row "xyz:BRENTOIL" 20 "0.41"))
        coin-cell (first (vec (hiccup/node-children row-node)))
        size-cell (second (vec (hiccup/node-children row-node)))
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))
        coin-label-node (hiccup/find-first-node coin-cell #(= "BRENTOIL" (get-in % [1 :title])))]
    (is (contains? (hiccup/node-class-set header-node) grid-template-class))
    (is (contains? (hiccup/node-class-set header-node) "min-w-[1335px]"))
    (is (contains? (hiccup/node-class-set row-node) grid-template-class))
    (is (contains? (hiccup/node-class-set row-node) "min-w-[1335px]"))
    (is (contains? (hiccup/node-class-set coin-cell) "min-w-0"))
    (is (contains? (hiccup/node-class-set coin-button) "overflow-hidden"))
    (is (contains? (hiccup/node-class-set coin-label-node) "min-w-0"))
    (is (contains? (hiccup/node-class-set coin-label-node) "truncate"))
    (is (= "BRENTOIL" (get-in coin-label-node [1 :title])))
    (is (contains? (hiccup/node-class-set size-cell) "min-w-0"))
    (is (contains? (hiccup/node-class-set size-cell) "truncate"))
    (is (= "0.41 BRENTOIL" (get-in size-cell [1 :title])))))
