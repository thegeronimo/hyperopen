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

(deftest order-history-tab-content-memoizes-normalize-and-sort-by-input-identity-filter-and-sort-state-test
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
                     :loading? false}
        normalize-calls (atom 0)
        sort-calls (atom 0)]
    (order-history-tab/reset-order-history-sort-cache!)
    (with-redefs [order-history-tab/normalized-order-history
                  (fn [_rows]
                    (swap! normalize-calls inc)
                    [normalized-row])
                  order-history-tab/sort-order-history-by-column
                  (fn [rows _column _direction]
                    (swap! sort-calls inc)
                    rows)]
      (view/order-history-tab-content raw-rows table-state)
      (view/order-history-tab-content raw-rows table-state)
      (is (= 1 @normalize-calls))
      (is (= 1 @sort-calls))

      (let [asc-state (assoc-in table-state [:sort :direction] :asc)]
        (view/order-history-tab-content raw-rows asc-state)
        (view/order-history-tab-content raw-rows asc-state)
        (is (= 2 @normalize-calls))
        (is (= 2 @sort-calls))

        (view/order-history-tab-content (into [] raw-rows) asc-state)
        (is (= 3 @normalize-calls))
        (is (= 3 @sort-calls))))))

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
    (is (contains? strings "Filled"))
    (is (contains? strings "Canceled"))))

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
                                                      (contains? (hiccup/node-class-set %) "truncate")
                                                      (contains? (hiccup/direct-texts %) "NVDA")))
        sell-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                      (contains? (hiccup/node-class-set %) "truncate")
                                                      (contains? (hiccup/direct-texts %) "PUMP")))]
    (is (some? long-coin-base))
    (is (some? sell-coin-base))
    (is (contains? (hiccup/node-class-set long-coin-base) "font-semibold"))
    (is (contains? (hiccup/node-class-set sell-coin-base) "font-semibold"))
    (is (= "rgb(151, 252, 228)"
           (get-in long-coin-base [1 :style :color])))
    (is (= "rgb(234, 175, 184)"
           (get-in sell-coin-base [1 :style :color])))))

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
                             :statusTimestamp 1700000000100})]
    (is (= "Market" (@#'view/format-order-history-price market-row)))
    (is (= "--" (@#'view/format-order-history-filled-size (:filled-size unfilled-limit-row))))
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

(deftest order-history-status-filter-controls-and-filtering-test
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
                                                               :status-filter :filled
                                                               :loading? false})
        filtered-strings (set (hiccup/collect-strings filtered-content))
        panel-state (-> fixtures/sample-account-info-state
                        (assoc-in [:account-info :selected-tab] :order-history)
                        (assoc-in [:account-info :order-history]
                                  {:sort {:column "Time" :direction :desc}
                                   :status-filter :filled
                                   :filter-open? true
                                   :loading? false
                                   :error nil
                                   :request-id 1})
                        (assoc-in [:orders :order-history] rows))
        panel (view/account-info-panel panel-state)
        filter-button (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Filter"))
        filled-option (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Filled"))]
    (is (contains? filtered-strings "Filled"))
    (is (not (contains? filtered-strings "Canceled")))
    (is (some? filter-button))
    (is (some? filled-option))
    (is (= [[:actions/toggle-order-history-filter-open]]
           (get-in filter-button [1 :on :click])))
    (is (= [[:actions/set-order-history-status-filter :filled]]
           (get-in filled-option [1 :on :click])))))

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
