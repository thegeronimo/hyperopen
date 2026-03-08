(ns hyperopen.views.account-info.tabs.order-history-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.test-support.hiccup-selectors :as selectors]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.order-history :as order-history-tab]
            [hyperopen.views.account-info-view :as view]))

(defn- reset-order-history-sort-cache-fixture
  [f]
  (order-history-tab/reset-order-history-sort-cache!)
  (f)
  (order-history-tab/reset-order-history-sort-cache!))

(use-fixtures :each reset-order-history-sort-cache-fixture)

(deftest order-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (view/sortable-order-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (hiccup/node-children header-node)))]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-order-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest order-history-tab-content-memoizes-normalize-sort-and-index-by-input-signatures-test
  (let [raw-rows [{:order {:coin "ETH"
                           :oid 1
                           :side "B"
                           :origSz "1.0"
                           :remainingSz "0.0"
                           :limitPx "100"
                           :orderType "Limit"
                           :isTrigger false
                           :isPositionTpsl false
                           :timestamp 1700000000000}
                   :status "filled"
                   :statusTimestamp 1700000000000}]
        normalized-row {:time-ms 1700000000000
                        :type "Limit"
                        :coin "ETH"
                        :side "B"
                        :size 1
                        :filled-size 1
                        :order-value 100
                        :px "100"
                        :status-key :filled
                        :status-label "Filled"
                        :oid "1"}
        table-state {:sort {:column "Time" :direction :desc}
                     :status-filter :all
                     :loading? false
                     :market-by-key {}}
        equivalent-market (into {} (:market-by-key table-state))
        changed-market {"spot:ETH/USDC" {:coin "spot:ETH/USDC"
                                         :symbol "ETH/USDC"}}
        normalize-calls (atom 0)
        sort-calls (atom 0)
        index-calls (atom 0)
        original-index-builder @#'order-history-tab/*build-order-history-coin-search-index*]
    (order-history-tab/reset-order-history-sort-cache!)
    (with-redefs [order-history-tab/normalized-order-history
                  (fn [_rows]
                    (swap! normalize-calls inc)
                    [normalized-row])
                  order-history-tab/sort-order-history-by-column
                  (fn [rows _column _direction]
                    (swap! sort-calls inc)
                    rows)
                  order-history-tab/*build-order-history-coin-search-index*
                  (fn [rows market-by-key]
                    (swap! index-calls inc)
                    (original-index-builder rows market-by-key))]
      (view/order-history-tab-content raw-rows table-state)
      (view/order-history-tab-content raw-rows table-state)
      (is (= 1 @normalize-calls))
      (is (= 1 @sort-calls))
      (is (= 1 @index-calls))

      (let [asc-state (assoc-in table-state [:sort :direction] :asc)]
        (view/order-history-tab-content raw-rows asc-state)
        (view/order-history-tab-content raw-rows asc-state)
        (is (= 2 @normalize-calls))
        (is (= 2 @sort-calls))
        (is (= 2 @index-calls))

        (view/order-history-tab-content raw-rows (assoc asc-state :coin-search "et"))
        (view/order-history-tab-content raw-rows (assoc asc-state :coin-search "et"))
        (is (= 2 @normalize-calls))
        (is (= 2 @sort-calls))
        (is (= 2 @index-calls))

        (let [churned-rows (into [] raw-rows)]
          (view/order-history-tab-content churned-rows asc-state)
          (view/order-history-tab-content churned-rows asc-state)
          (is (= 2 @normalize-calls))
          (is (= 2 @sort-calls))
          (is (= 2 @index-calls)))

        (view/order-history-tab-content raw-rows (assoc asc-state :market-by-key equivalent-market))
        (is (= 2 @normalize-calls))
        (is (= 2 @sort-calls))
        (is (= 2 @index-calls))

        (view/order-history-tab-content raw-rows (assoc asc-state :market-by-key changed-market))
        (is (= 2 @normalize-calls))
        (is (= 2 @sort-calls))
        (is (= 3 @index-calls))

        (let [changed-rows (assoc-in (into [] raw-rows) [0 :order :limitPx] "101")]
          (view/order-history-tab-content changed-rows asc-state)
          (is (= 3 @normalize-calls))
          (is (= 3 @sort-calls))
          (is (= 4 @index-calls)))))))

(deftest order-history-content-renders-hyperliquid-columns-and-values-test
  (let [rows [{:order {:coin "xyz:NVDA"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "0"
                       :orderType "Market"
                       :reduceOnly false
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000500}
              {:order {:coin "PUMP"
                       :oid 275043415805
                       :side "B"
                       :origSz "11386"
                       :remainingSz "11386"
                       :limitPx "0.001000"
                       :orderType "Limit"
                       :reduceOnly true
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "canceled"
               :statusTimestamp 1699999999000}]
        content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :loading? false})
        strings (set (hiccup/collect-strings content))]
    (is (some? (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Filled Size"))))
    (is (some? (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Trigger Conditions"))))
    (is (some? (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Order ID"))))
    (is (contains? strings "NVDA"))
    (is (contains? strings "xyz"))
    (is (not (contains? strings "xyz:NVDA")))
    (is (contains? strings "Market"))
    (is (contains? strings "N/A"))
    (is (contains? strings "No"))
    (is (contains? strings "Yes"))
    (is (contains? strings "Long"))
    (is (contains? strings "Close Short"))
    (is (contains? strings "Filled"))
    (is (contains? strings "Canceled"))))

(deftest order-history-status-and-order-id-cells-use-overflow-safe-classes-test
  (let [rows [{:order {:coin "PUMP"
                       :oid 3300074759
                       :side "A"
                       :origSz "11386"
                       :remainingSz "11386"
                       :limitPx "0.001000"
                       :orderType "Take Profit Market"
                       :reduceOnly true
                       :isTrigger true
                       :triggerCondition "Above"
                       :triggerPx "0.001949"
                       :timestamp 1700000000000}
               :status "reduceonlycanceled"
               :statusTimestamp 1699999999000}]
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :loading? false})
        row-node (hiccup/first-viewport-row content)
        cells (vec (hiccup/node-children row-node))
        direction-cell (nth cells 3)
        status-cell (nth cells 11)
        order-id-cell (nth cells 12)
        direction-strings (set (hiccup/collect-strings direction-cell))
        status-classes (hiccup/node-class-set status-cell)
        order-id-classes (hiccup/node-class-set order-id-cell)
        status-strings (set (hiccup/collect-strings status-cell))
        underlined-status (hiccup/find-first-node status-cell
                                                  #(contains? (hiccup/node-class-set %)
                                                              "underline"))
        status-tooltip-container (hiccup/find-first-node status-cell
                                                         #(contains? (hiccup/node-class-set %)
                                                                     "pointer-events-none"))
        status-tooltip-panel (hiccup/find-first-node status-cell
                                                     #(contains? (hiccup/node-class-set %)
                                                                 "bg-gray-800"))]
    (is (contains? status-classes "break-words"))
    (is (contains? status-classes "leading-4"))
    (is (contains? direction-strings "Close Long"))
    (is (contains? status-strings "Canceled"))
    (is (contains? status-strings "Canceled due to reduce only."))
    (is (some? underlined-status))
    (is (some? status-tooltip-container))
    (is (contains? (hiccup/node-class-set status-tooltip-container) "left-1/2"))
    (is (contains? (hiccup/node-class-set status-tooltip-container) "-translate-x-1/2"))
    (is (some? status-tooltip-panel))
    (is (contains? (hiccup/node-class-set status-tooltip-panel) "w-max"))
    (is (= :div (first status-tooltip-panel)))
    (is (contains? order-id-classes "order-history-order-id-text"))
    (is (contains? order-id-classes "tracking-tight"))
    (is (contains? order-id-classes "whitespace-nowrap"))))

(deftest order-history-tab-content-dedupes-open-and-filled-rows-with-the-same-order-id-test
  (let [rows [{:order {:coin "PUMP"
                       :oid 330007475448
                       :side "A"
                       :origSz "11273"
                       :remainingSz "11273"
                       :limitPx "0.001772"
                       :orderType "Limit"
                       :timestamp 1700000000000}
               :status "open"
               :statusTimestamp 1700000000000}
              {:order {:coin "PUMP"
                       :oid 330007475448
                       :side "A"
                       :origSz "11273"
                       :remainingSz "0.0"
                       :limitPx "0.001772"
                       :orderType "Limit"
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000000}]
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :loading? false})
        viewport (hiccup/tab-rows-viewport-node content)
        rendered-rows (vec (hiccup/node-children viewport))
        row-strings (set (hiccup/collect-strings (first rendered-rows)))
        all-strings (hiccup/collect-strings content)]
    (is (= 1 (count rendered-rows)))
    (is (contains? row-strings "Filled"))
    (is (not (contains? row-strings "Open")))
    (is (= 1 (count (filter #(= "330007475448" %) all-strings))))))

(deftest order-history-coin-labels-are-bold-and-side-colored-test
  (let [rows [{:order {:coin "xyz:NVDA"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "0"
                       :orderType "Market"
                       :reduceOnly false
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000500}
              {:order {:coin "PUMP"
                       :oid 275043415805
                       :side "A"
                       :origSz "11386"
                       :remainingSz "11386"
                       :limitPx "0.001000"
                       :orderType "Limit"
                       :reduceOnly true
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "canceled"
               :statusTimestamp 1699999999000}]
        content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :loading? false})
        long-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                      (contains? (hiccup/node-class-set %) "whitespace-nowrap")
                                                      (contains? (hiccup/direct-texts %) "NVDA")))
        sell-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                      (contains? (hiccup/node-class-set %) "whitespace-nowrap")
                                                      (contains? (hiccup/direct-texts %) "PUMP")))]
    (is (some? long-coin-base))
    (is (some? sell-coin-base))
    (is (contains? (hiccup/node-class-set long-coin-base) "font-semibold"))
    (is (contains? (hiccup/node-class-set sell-coin-base) "font-semibold"))
    (is (= "rgb(151, 252, 228)"
           (get-in long-coin-base [1 :style :color])))
    (is (= "rgb(234, 175, 184)"
           (get-in sell-coin-base [1 :style :color])))))

(deftest order-history-desktop-grid-and-coin-label-avoid-unnecessary-truncation-test
  (let [rows [{:order {:coin "xyz:SILVER"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "95.242"
                       :orderType "Limit"
                       :reduceOnly false
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000500}]
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :loading? false})
        header-node (hiccup/tab-header-node content)
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 2)
        coin-base (hiccup/find-first-node coin-cell #(and (= :span (first %))
                                                          (contains? (hiccup/direct-texts %) "SILVER")))
        header-classes (hiccup/node-class-set header-node)
        row-classes (hiccup/node-class-set row-node)
        flexible-grid-class "grid-cols-[minmax(130px,1.45fr)_minmax(110px,1.15fr)_minmax(84px,1.35fr)_minmax(64px,1.25fr)_minmax(82px,0.9fr)_minmax(72px,0.8fr)_minmax(100px,1.05fr)_minmax(72px,0.8fr)_minmax(74px,0.8fr)_minmax(140px,1.35fr)_minmax(60px,0.7fr)_minmax(120px,1.15fr)_minmax(106px,1.05fr)]"
        old-grid-class "grid-cols-[minmax(130px,1.45fr)_minmax(110px,1.25fr)_minmax(84px,0.9fr)_minmax(64px,0.7fr)_minmax(82px,0.9fr)_minmax(72px,0.75fr)_minmax(100px,1.05fr)_minmax(72px,0.8fr)_minmax(74px,0.72fr)_minmax(140px,1.55fr)_minmax(60px,0.65fr)_minmax(120px,1.25fr)_minmax(106px,1.2fr)]"]
    (is (contains? header-classes flexible-grid-class))
    (is (contains? row-classes flexible-grid-class))
    (is (not (contains? header-classes old-grid-class)))
    (is (not (contains? row-classes old-grid-class)))
    (is (some? coin-base))
    (is (contains? (hiccup/node-class-set coin-base) "whitespace-nowrap"))
    (is (not (contains? (hiccup/node-class-set coin-base) "truncate")))))

(deftest order-history-desktop-coin-and-direction-columns-keep-readable-separation-test
  (let [rows [{:order {:coin "xyz:SILVER"
                       :oid 307891000622
                       :side "A"
                       :origSz "1.13"
                       :remainingSz "0.000"
                       :limitPx "88.30"
                       :orderType "Limit"
                       :reduceOnly true
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000500}]
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :loading? false})
        header-node (hiccup/tab-header-node content)
        row-node (hiccup/first-viewport-row content)
        header-cells (vec (hiccup/node-children header-node))
        row-cells (vec (hiccup/node-children row-node))
        coin-header (nth header-cells 2)
        direction-header (nth header-cells 3)
        coin-cell (nth row-cells 2)
        direction-cell (nth row-cells 3)]
    (is (contains? (hiccup/node-class-set coin-header) "pr-4"))
    (is (contains? (hiccup/node-class-set direction-header) "pl-2"))
    (is (contains? (hiccup/node-class-set coin-cell) "pr-4"))
    (is (contains? (hiccup/node-class-set direction-cell) "pl-2"))))

(deftest order-history-coin-cell-dispatches-select-asset-action-test
  (let [rows [{:order {:coin "xyz:NVDA"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "0"
                       :orderType "Market"
                       :reduceOnly false
                       :isTrigger false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000500}]
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :loading? false})
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 2)
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "xyz:NVDA"]]
           (get-in coin-button [1 :on :click])))))

(deftest order-history-coin-label-prefers-market-base-for-spot-id-test
  (let [rows [{:order {:coin "@230"
                       :oid 307891000622
                       :side "B"
                       :origSz "0.500"
                       :remainingSz "0.000"
                       :limitPx "0.000"
                       :orderType "Market"
                       :reduceOnly false
                       :isTrigger false
                       :isPositionTpsl false
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000005000}]
        content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :loading? false
                                                      :market-by-key {"spot:@230" {:coin "@230"
                                                                                    :symbol "SOL/USDC"
                                                                                    :base "SOL"
                                                                                    :market-type :spot}}})
        strings (set (hiccup/collect-strings content))]
    (is (contains? strings "SOL"))
    (is (not (contains? strings "@230")))))

(deftest order-history-formatting-distinguishes-market-price-and-filled-size-placeholder-test
  (let [market-row (view/normalize-order-history-row
                    {:order {:coin "NVDA"
                             :oid 1
                             :side "B"
                             :origSz "2.0"
                             :remainingSz "1.0"
                             :limitPx "0"
                             :orderType "Market"}
                     :status "filled"
                     :statusTimestamp 1700000000000})
        unfilled-limit-row (view/normalize-order-history-row
                            {:order {:coin "PUMP"
                                     :oid 2
                                     :side "A"
                                     :origSz "3.0"
                                     :remainingSz "3.0"
                                     :limitPx "0.0012"
                                     :orderType "Limit"}
                             :status "open"
                             :statusTimestamp 1700000000100})
        filled-row-with-sz-fallback (view/normalize-order-history-row
                                     {:order {:coin "PUMP"
                                              :oid 3
                                              :side "A"
                                              :origSz "11273"
                                              :sz "0.0"
                                              :limitPx "0.001772"
                                              :orderType "Limit"}
                                      :status "filled"
                             :statusTimestamp 1700000000100})]
    (is (= "Market" (@#'view/format-order-history-price market-row)))
    (is (= "--" (@#'view/format-order-history-filled-size (:filled-size unfilled-limit-row))))
    (is (= "11,273" (@#'view/format-order-history-filled-size (:filled-size filled-row-with-sz-fallback))))
    (is (= "No" (@#'view/format-order-history-reduce-only (assoc market-row :reduce-only false))))
    (is (= "N/A" (@#'view/format-order-history-trigger market-row)))))

(deftest sort-order-history-by-column-is-deterministic-on-ties-test
  (let [rows (view/normalized-order-history
              [{:order {:coin "BTC" :oid "2" :side "B" :origSz "1.0" :remainingSz "0.0" :limitPx "1.0"}
                :status "filled"
                :statusTimestamp 2000}
               {:order {:coin "BTC" :oid "1" :side "B" :origSz "1.0" :remainingSz "0.0" :limitPx "1.0"}
                :status "filled"
                :statusTimestamp 2000}])
        time-asc (view/sort-order-history-by-column rows "Time" :asc)
        oid-desc (view/sort-order-history-by-column rows "Order ID" :desc)]
    (is (= ["1" "2"] (mapv (comp str :oid) time-asc)))
    (is (= ["2" "1"] (mapv (comp str :oid) oid-desc)))))

(deftest order-history-direction-filter-controls-and-filtering-test
  (let [rows [{:order {:coin "NVDA"
                       :oid 1
                       :side "B"
                       :origSz "1.0"
                       :remainingSz "0.0"
                       :limitPx "0"
                       :orderType "Market"}
               :status "filled"
               :statusTimestamp 1700000000000}
              {:order {:coin "PUMP"
                       :oid 2
                       :side "A"
                       :origSz "1.0"
                       :remainingSz "0.0"
                       :limitPx "0.001"
                       :orderType "Limit"}
               :status "canceled"
               :statusTimestamp 1699999999000}]
        filtered-content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                               :status-filter :long
                                                               :loading? false})
        filtered-strings (set (hiccup/collect-strings filtered-content))
        panel-state (-> fixtures/sample-account-info-state
                        (assoc-in [:account-info :selected-tab] :order-history)
                        (assoc-in [:account-info :order-history]
                                  {:sort {:column "Time" :direction :desc}
                                   :status-filter :short
                                   :filter-open? true
                                   :loading? false
                                   :error nil
                                   :request-id 1})
                        (assoc-in [:orders :order-history] rows))
        panel (view/account-info-panel panel-state)
        filter-button (hiccup/find-first-node panel #(and (contains? (hiccup/direct-texts %) "Short")
                                                           (= [[:actions/toggle-order-history-filter-open]]
                                                              (get-in % [1 :on :click]))))
        short-option (hiccup/find-first-node panel #(and (contains? (hiccup/direct-texts %) "Short")
                                                          (= [[:actions/set-order-history-status-filter :short]]
                                                              (get-in % [1 :on :click]))))]
    (is (contains? filtered-strings "NVDA"))
    (is (not (contains? filtered-strings "PUMP")))
    (is (some? filter-button))
    (is (some? short-option))
    (is (= [[:actions/toggle-order-history-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= [[:actions/set-order-history-status-filter :short]]
           (get-in short-option [1 :on :click])))))

(deftest order-history-filters-by-fuzzy-coin-search-test
  (let [rows [{:order {:coin "xyz:NVDA"
                       :oid 1
                       :side "B"
                       :origSz "1.0"
                       :remainingSz "0.0"
                       :limitPx "0"
                       :orderType "Market"
                       :timestamp 1700000000000}
               :status "filled"
               :statusTimestamp 1700000000000}
              {:order {:coin "@230"
                       :oid 2
                       :side "A"
                       :origSz "1.0"
                       :remainingSz "0.0"
                       :limitPx "0.001"
                       :orderType "Limit"
                       :timestamp 1699999999000}
               :status "filled"
               :statusTimestamp 1699999999000}]
        market-by-key {"spot:@230" {:coin "@230"
                                     :symbol "SOL/USDC"
                                     :base "SOL"
                                     :market-type :spot}}
        nv-content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                         :status-filter :all
                                                         :coin-search "nd"
                                                         :loading? false
                                                         :market-by-key market-by-key})
        sol-content (view/order-history-tab-content rows {:sort {:column "Time" :direction :desc}
                                                          :status-filter :all
                                                          :coin-search "sl"
                                                          :loading? false
                                                          :market-by-key market-by-key})
        nv-strings (set (hiccup/collect-strings nv-content))
        sol-strings (set (hiccup/collect-strings sol-content))]
    (is (contains? nv-strings "NVDA"))
    (is (not (contains? nv-strings "SOL")))
    (is (contains? sol-strings "SOL"))
    (is (not (contains? sol-strings "NVDA")))))

(deftest order-history-pagination-renders-only-current-page-rows-test
  (let [rows (mapv fixtures/order-history-row (range 55))
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :page-size 25
                                                   :page 2
                                                   :page-input "2"
                                                   :loading? false})
        viewport (hiccup/tab-rows-viewport-node content)
        rendered-rows (vec (hiccup/node-children viewport))
        all-strings (set (hiccup/collect-strings content))]
    (is (= 25 (count rendered-rows)))
    (is (contains? all-strings "Page 2 of 3"))
    (is (contains? all-strings "Total: 55"))))

(deftest order-history-pagination-controls-disable-prev-next-at-edges-test
  (let [rows (mapv fixtures/order-history-row (range 51))
        first-page (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                      :status-filter :all
                                                      :page-size 25
                                                      :page 1
                                                      :page-input "1"
                                                      :loading? false})
        first-prev (hiccup/find-first-node first-page selectors/prev-button-predicate)
        first-next (hiccup/find-first-node first-page selectors/next-button-predicate)
        last-page (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                     :status-filter :all
                                                     :page-size 25
                                                     :page 3
                                                     :page-input "3"
                                                     :loading? false})
        last-prev (hiccup/find-first-node last-page selectors/prev-button-predicate)
        last-next (hiccup/find-first-node last-page selectors/next-button-predicate)]
    (is (= true (get-in first-prev [1 :disabled])))
    (is (not= true (get-in first-next [1 :disabled])))
    (is (not= true (get-in last-prev [1 :disabled])))
    (is (= true (get-in last-next [1 :disabled])))))

(deftest order-history-pagination-controls-wire-actions-test
  (let [rows (mapv fixtures/order-history-row (range 12))
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :page-size 25
                                                   :page 1
                                                   :page-input "4"
                                                   :loading? false})
        page-size-select (hiccup/find-first-node content (selectors/select-id-predicate "order-history-page-size"))
        jump-input (hiccup/find-first-node content (selectors/input-id-predicate "order-history-page-input"))
        go-button (hiccup/find-first-node content selectors/go-button-predicate)]
    (is (= [[:actions/set-order-history-page-size [:event.target/value]]]
           (get-in page-size-select [1 :on :change])))
    (is (= [[:actions/set-order-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :input])))
    (is (= [[:actions/set-order-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :change])))
    (is (= [[:actions/handle-order-history-page-input-keydown [:event/key] 1]]
           (get-in jump-input [1 :on :keydown])))
    (is (= [[:actions/apply-order-history-page-input 1]]
           (get-in go-button [1 :on :click])))))

(deftest order-history-pagination-clamps-page-when-data-shrinks-test
  (let [rows (mapv fixtures/order-history-row (range 10))
        content (@#'view/order-history-table rows {:sort {:column "Time" :direction :desc}
                                                   :status-filter :all
                                                   :page-size 25
                                                   :page 4
                                                   :page-input "4"
                                                   :loading? false})
        viewport (hiccup/tab-rows-viewport-node content)
        jump-input (hiccup/find-first-node content (selectors/input-id-predicate "order-history-page-input"))
        all-strings (set (hiccup/collect-strings content))]
    (is (= 10 (count (vec (hiccup/node-children viewport)))))
    (is (contains? all-strings "Page 1 of 1"))
    (is (= "1" (get-in jump-input [1 :value])))))
