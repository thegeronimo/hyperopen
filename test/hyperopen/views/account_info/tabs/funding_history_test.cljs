(ns hyperopen.views.account-info.tabs.funding-history-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.test-support.hiccup-selectors :as selectors]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.funding-history :as funding-history-tab]
            [hyperopen.views.account-info-view :as view]))

(defn- reset-funding-history-sort-cache-fixture
  [f]
  (funding-history-tab/reset-funding-history-sort-cache!)
  (f)
  (funding-history-tab/reset-funding-history-sort-cache!))

(use-fixtures :each reset-funding-history-sort-cache-fixture)

(deftest funding-history-sortable-header-uses-secondary-text-hover-and-action-test
  (let [header-node (view/sortable-funding-history-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (hiccup/node-children header-node)))]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-funding-history "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest funding-history-tab-content-memoizes-sorting-by-input-identity-and-sort-state-test
  (let [fundings [(fixtures/funding-history-row 1)]
        table-state {:sort {:column "Time" :direction :desc}
                     :loading? false}
        sort-calls (atom 0)]
    (funding-history-tab/reset-funding-history-sort-cache!)
    (with-redefs [funding-history-tab/sort-funding-history-by-column
                  (fn [rows _column _direction]
                    (swap! sort-calls inc)
                    rows)]
      (view/funding-history-tab-content fundings table-state fundings)
      (view/funding-history-tab-content fundings table-state fundings)
      (is (= 1 @sort-calls))

      (let [asc-state (assoc-in table-state [:sort :direction] :asc)]
        (view/funding-history-tab-content fundings asc-state fundings)
        (view/funding-history-tab-content fundings asc-state fundings)
        (is (= 2 @sort-calls))

        (view/funding-history-tab-content (into [] fundings) asc-state fundings)
        (is (= 3 @sort-calls))))))

(deftest sort-funding-history-by-column-respects-direction-and-deterministic-fallback-test
  (let [rows [{:id "2"
               :time-ms 2000
               :coin "ETH"
               :position-size-raw 2
               :payment-usdc-raw 0.5
               :funding-rate-raw 0.0002}
              {:id "1"
               :time-ms 1000
               :coin "BTC"
               :position-size-raw -1
               :payment-usdc-raw 1.2
               :funding-rate-raw -0.0001}
              {:id "3"
               :time-ms 1500
               :coin "SOL"
               :position-size-raw 0.5
               :payment-usdc-raw -0.3
               :funding-rate-raw 0.0004}]
        coin-asc (view/sort-funding-history-by-column rows "Coin" :asc)
        payment-desc (view/sort-funding-history-by-column rows "Payment" :desc)
        missing-values [{:id "b" :coin "ETH"}
                        {:id "a" :coin "BTC"}]
        missing-asc (view/sort-funding-history-by-column missing-values "Payment" :asc)]
    (is (= ["BTC" "ETH" "SOL"] (mapv :coin coin-asc)))
    (is (= [1.2 0.5 -0.3] (mapv :payment-usdc-raw payment-desc)))
    (is (= ["a" "b"] (mapv :id missing-asc)))))

(deftest funding-row-sort-id-and-sort-accessors-fall-back-to-legacy-row-shapes-test
  (let [funding-row-sort-id @#'funding-history-tab/funding-row-sort-id
        explicit-id-row {:id "existing-id"
                         :time-ms 1700000001000
                         :coin "BTC"}
        legacy-row {:time 1700000000000
                    :coin "SOL"
                    :positionSize -2.5
                    :payment 0.75
                    :fundingRate 0.0001}
        legacy-size-row {:time 1700000003000
                         :coin "ETH"
                         :size-raw 4.0
                         :payment -0.2
                         :fundingRate 0.0003}
        sorted-by-size (funding-history-tab/sort-funding-history-by-column [legacy-size-row legacy-row]
                                                                            "Size"
                                                                            :asc)
        sorted-by-rate (funding-history-tab/sort-funding-history-by-column [legacy-size-row legacy-row]
                                                                            "Rate"
                                                                            :desc)]
    (is (= "existing-id" (funding-row-sort-id explicit-id-row)))
    (is (= "1700000000000|SOL|-2.5|0.75|0.0001"
           (funding-row-sort-id legacy-row)))
    (is (= ["SOL" "ETH"] (mapv :coin sorted-by-size)))
    (is (= ["ETH" "SOL"] (mapv :coin sorted-by-rate)))))

(deftest funding-history-headers-use-secondary-text-and-sort-actions-test
  (let [fundings [{:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                   :time-ms 1700000000000
                   :coin "HYPE"
                   :position-size-raw 120.0
                   :payment-usdc-raw -0.42
                   :funding-rate-raw 0.0006}]
        content (@#'view/funding-history-table fundings {:sort {:column "Time" :direction :desc}})
        header-cells (vec (hiccup/node-children (hiccup/tab-header-node content)))
        columns ["Time" "Coin" "Size" "Position Side" "Payment" "Rate"]]
    (doseq [[idx column-name] (map-indexed vector columns)]
      (let [button-node (first (vec (hiccup/node-children (nth header-cells idx))))]
        (is (= :button (first button-node)))
        (is (contains? (hiccup/node-class-set button-node) "text-trading-text-secondary"))
        (is (contains? (hiccup/node-class-set button-node) "hover:text-trading-text"))
        (is (= [[:actions/sort-funding-history column-name]]
               (get-in button-node [1 :on :click])))))))

(deftest funding-history-content-sorts-by-sort-state-and-default-fallback-test
  (let [rows [{:id "2"
               :time-ms 2000
               :coin "BTC"
               :position-size-raw 2
               :payment-usdc-raw 0.5
               :funding-rate-raw 0.0002}
              {:id "1"
               :time-ms 3000
               :coin "ETH"
               :position-size-raw -1
               :payment-usdc-raw 1.2
               :funding-rate-raw -0.0001}
              {:id "3"
               :time-ms 1000
               :coin "SOL"
               :position-size-raw 0.5
               :payment-usdc-raw -0.3
               :funding-rate-raw 0.0004}]
        coin-sorted (@#'view/funding-history-table rows {:sort {:column "Coin" :direction :asc}})
        coin-row (hiccup/first-viewport-row coin-sorted)
        coin-value (nth (vec (hiccup/node-children coin-row)) 1)
        default-sorted (@#'view/funding-history-table rows {})
        default-row (hiccup/first-viewport-row default-sorted)
        default-coin (nth (vec (hiccup/node-children default-row)) 1)]
    (is (contains? (set (hiccup/collect-strings coin-value)) "BTC"))
    (is (contains? (set (hiccup/collect-strings default-coin)) "ETH"))))

(deftest funding-history-row-renders-coin-chip-and-size-without-prefix-test
  (let [rows [{:id "1700000000000|xyz:NVDA|0.5|-0.42|0.0006"
               :time-ms 1700000000000
               :coin "xyz:NVDA"
               :position-size-raw 0.5
               :payment-usdc-raw -0.42
               :funding-rate-raw 0.0006}]
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}})
        row (hiccup/first-viewport-row content)
        row-cells (vec (hiccup/node-children row))
        coin-cell (nth row-cells 1)
        size-cell (nth row-cells 2)
        row-strings (set (hiccup/collect-strings row))
        coin-strings (set (hiccup/collect-strings coin-cell))
        size-strings (set (hiccup/collect-strings size-cell))
        coin-base (hiccup/find-first-node coin-cell #(and (= :span (first %))
                                                   (contains? (hiccup/node-class-set %) "truncate")
                                                   (contains? (hiccup/direct-texts %) "NVDA")))
        xyz-chip (hiccup/find-first-node coin-cell #(contains? (hiccup/direct-texts %) "xyz"))]
    (is (contains? coin-strings "NVDA"))
    (is (contains? coin-strings "xyz"))
    (is (not (contains? row-strings "xyz:NVDA")))
    (is (= #{"0.500 NVDA"} size-strings))
    (is (some? coin-base))
    (is (contains? (hiccup/node-class-set coin-base) "font-semibold"))
    (is (= view/order-history-long-coin-color
           (get-in coin-base [1 :style :color])))
    (is (some? xyz-chip))
    (is (contains? (hiccup/node-class-set xyz-chip) "bg-[#242924]"))))

(deftest funding-history-coin-cell-dispatches-select-asset-action-test
  (let [rows [{:id "1700000000000|xyz:NVDA|0.5|-0.42|0.0006"
               :time-ms 1700000000000
               :coin "xyz:NVDA"
               :position-size-raw 0.5
               :payment-usdc-raw -0.42
               :funding-rate-raw 0.0006}]
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}})
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 1)
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "xyz:NVDA"]]
           (get-in coin-button [1 :on :click])))))

(deftest funding-history-pagination-renders-only-current-page-rows-test
  (let [rows (mapv fixtures/funding-history-row (range 55))
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
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

(deftest funding-history-pagination-controls-disable-prev-next-at-edges-test
  (let [rows (mapv fixtures/funding-history-row (range 51))
        first-page (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                        :page-size 25
                                                        :page 1
                                                        :page-input "1"
                                                        :loading? false})
        first-prev (hiccup/find-first-node first-page selectors/prev-button-predicate)
        first-next (hiccup/find-first-node first-page selectors/next-button-predicate)
        last-page (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
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

(deftest funding-history-pagination-controls-wire-actions-test
  (let [rows (mapv fixtures/funding-history-row (range 12))
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                     :page-size 25
                                                     :page 1
                                                     :page-input "4"
                                                     :loading? false})
        page-size-select (hiccup/find-first-node content (selectors/select-id-predicate "funding-history-page-size"))
        jump-input (hiccup/find-first-node content (selectors/input-id-predicate "funding-history-page-input"))
        go-button (hiccup/find-first-node content selectors/go-button-predicate)]
    (is (= [[:actions/set-funding-history-page-size [:event.target/value]]]
           (get-in page-size-select [1 :on :change])))
    (is (= [[:actions/set-funding-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :input])))
    (is (= [[:actions/set-funding-history-page-input [:event.target/value]]]
           (get-in jump-input [1 :on :change])))
    (is (= [[:actions/handle-funding-history-page-input-keydown [:event/key] 1]]
           (get-in jump-input [1 :on :keydown])))
    (is (= [[:actions/apply-funding-history-page-input 1]]
           (get-in go-button [1 :on :click])))))

(deftest funding-history-pagination-clamps-page-when-data-shrinks-test
  (let [rows (mapv fixtures/funding-history-row (range 10))
        content (@#'view/funding-history-table rows {:sort {:column "Time" :direction :desc}
                                                     :page-size 25
                                                     :page 4
                                                     :page-input "4"
                                                     :loading? false})
        viewport (hiccup/tab-rows-viewport-node content)
        jump-input (hiccup/find-first-node content (selectors/input-id-predicate "funding-history-page-input"))
        all-strings (set (hiccup/collect-strings content))]
    (is (= 10 (count (vec (hiccup/node-children viewport)))))
    (is (contains? all-strings "Page 1 of 1"))
    (is (= "1" (get-in jump-input [1 :value])))))

(deftest funding-history-panel-renders-controls-and-parity-columns-test
  (let [funding-row {:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                     :time-ms 1700000000000
                     :coin "HYPE"
                     :size-raw 120.0
                     :position-size-raw 120.0
                     :position-side :long
                     :payment-usdc-raw -0.42
                     :funding-rate-raw 0.0006}
        state (-> fixtures/sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :funding-history)
                  (assoc-in [:account-info :funding-history]
                            {:filters {:coin-set #{}
                                       :start-time-ms 0
                                       :end-time-ms 2000000000000}
                             :draft-filters {:coin-set #{}
                                             :start-time-ms 0
                                             :end-time-ms 2000000000000}
                             :filter-open? false
                             :loading? false
                             :error nil})
                  (assoc-in [:orders :fundings-raw] [funding-row])
                  (assoc-in [:orders :fundings] [funding-row]))
        panel (view/account-info-panel state)]
    (is (some? (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Filter"))))
    (is (some? (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "View All"))))
    (is (some? (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Export as CSV"))))
    (is (= 1 (hiccup/count-nodes panel #(contains? (hiccup/direct-texts %) "Export as CSV"))))
    (is (some? (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Position Side"))))
    (is (some? (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Long"))))))

(deftest funding-history-controls-renders-status-without-header-actions-test
  (let [controls (@#'view/funding-history-controls {:loading? true
                                                    :error "Boom"
                                                    :filters {:coin-set #{}}
                                                    :draft-filters {:coin-set #{}}}
                                                   [])
        status-row (first (vec (hiccup/node-children controls)))]
    (is (some? (hiccup/find-first-node status-row #(contains? (hiccup/direct-texts %) "Loading..."))))
    (is (some? (hiccup/find-first-node status-row #(contains? (hiccup/direct-texts %) "Boom"))))
    (is (nil? (hiccup/find-first-node controls #(contains? (hiccup/direct-texts %) "Filter"))))
    (is (nil? (hiccup/find-first-node controls #(contains? (hiccup/direct-texts %) "View All"))))
    (is (nil? (hiccup/find-first-node controls #(contains? (hiccup/direct-texts %) "Export as CSV"))))))

(deftest funding-history-filter-panel-renders-apply-and-cancel-controls-test
  (let [funding-row {:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                     :time-ms 1700000000000
                     :coin "HYPE"
                     :size-raw 120.0
                     :position-size-raw 120.0
                     :position-side :long
                     :payment-usdc-raw -0.42
                     :funding-rate-raw 0.0006}
        state (-> fixtures/sample-account-info-state
                  (assoc-in [:account-info :selected-tab] :funding-history)
                  (assoc-in [:account-info :funding-history]
                            {:filters {:coin-set #{}
                                       :start-time-ms 0
                                       :end-time-ms 2000000000000}
                             :draft-filters {:coin-set #{"HYPE"}
                                             :start-time-ms 1700000000000
                                             :end-time-ms 1700100000000}
                             :filter-open? true
                             :loading? false
                             :error nil})
                  (assoc-in [:orders :fundings-raw] [funding-row])
                  (assoc-in [:orders :fundings] [funding-row]))
        panel (view/account-info-panel state)
        datetime-input (hiccup/find-first-node panel #(= "datetime-local" (get-in % [1 :type])))
        apply-button (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Apply"))
        cancel-button (hiccup/find-first-node panel #(contains? (hiccup/direct-texts %) "Cancel"))
        apply-classes (hiccup/node-class-set apply-button)
        cancel-classes (hiccup/node-class-set cancel-button)]
    (is (some? datetime-input))
    (is (some? apply-button))
    (is (some? cancel-button))
    (is (contains? apply-classes "btn-xs"))
    (is (contains? cancel-classes "btn-xs"))
    (is (not (contains? apply-classes "btn-sm")))
    (is (not (contains? cancel-classes "btn-sm")))
    (is (contains? apply-classes "px-3"))
    (is (contains? cancel-classes "px-3"))
    (is (contains? apply-classes "min-w-[4.5rem]"))
    (is (contains? cancel-classes "min-w-[4.5rem]"))))

(deftest funding-history-coin-filter-uses-search-input-and-suggestions-with-enter-wiring-test
  (let [funding-row-hype {:id "1700000000000|HYPE|120.0|-0.42|0.0006"
                          :time-ms 1700000000000
                          :coin "HYPE"
                          :size-raw 120.0
                          :position-size-raw 120.0
                          :position-side :long
                          :payment-usdc-raw -0.42
                          :funding-rate-raw 0.0006}
        funding-row-sol {:id "1700000100000|SOL|80.0|0.18|0.0002"
                         :time-ms 1700000100000
                         :coin "SOL"
                         :size-raw 80.0
                         :position-size-raw 80.0
                         :position-side :long
                         :payment-usdc-raw 0.18
                         :funding-rate-raw 0.0002}
        funding-row-stx {:id "1700000200000|STX|90.0|0.20|0.0003"
                         :time-ms 1700000200000
                         :coin "STX"
                         :size-raw 90.0
                         :position-size-raw 90.0
                         :position-side :long
                         :payment-usdc-raw 0.20
                         :funding-rate-raw 0.0003}
        funding-history-state {:filters {:coin-set #{}}
                               :draft-filters {:coin-set #{"HYPE"}}
                               :coin-search "sol"
                               :coin-suggestions-open? true
                               :filter-open? true
                               :loading? false
                               :error nil}
        controls (@#'view/funding-history-controls funding-history-state
                                                   [funding-row-hype funding-row-sol funding-row-stx])
        search-input (hiccup/find-first-node controls #(= "funding-history-coin-search" (get-in % [1 :id])))
        suggestion-buttons (hiccup/find-all-nodes controls
                                                  (fn [node]
                                                    (= :actions/add-funding-history-filter-coin
                                                       (first (first (get-in node [1 :on :mousedown]))))))]
    (is (some? search-input))
    (is (= "search" (get-in search-input [1 :type])))
    (is (= "sol" (get-in search-input [1 :value])))
    (is (= [[:actions/set-funding-history-filters :coin-search [:event.target/value]]]
           (get-in search-input [1 :on :input])))
    (is (= [[:actions/handle-funding-history-coin-search-keydown [:event/key] "SOL"]]
           (get-in search-input [1 :on :keydown])))
    (is (= 1 (count suggestion-buttons)))
    (is (= [[:actions/add-funding-history-filter-coin "SOL"]]
           (get-in (first suggestion-buttons) [1 :on :mousedown])))))

(deftest funding-history-coin-filter-renders-prefixed-coins-as-chip-with-remove-button-test
  (let [funding-row-pump {:id "1700000000000|PUMP|120.0|-0.42|0.0006"
                          :time-ms 1700000000000
                          :coin "PUMP"
                          :size-raw 120.0
                          :position-size-raw 120.0
                          :position-side :long
                          :payment-usdc-raw -0.42
                          :funding-rate-raw 0.0006}
        funding-row-xyz {:id "1700000100000|xyz:GOOGL|80.0|0.18|0.0002"
                         :time-ms 1700000100000
                         :coin "xyz:GOOGL"
                         :size-raw 80.0
                         :position-size-raw 80.0
                         :position-side :long
                         :payment-usdc-raw 0.18
                         :funding-rate-raw 0.0002}
        funding-history-state {:filters {:coin-set #{}}
                               :draft-filters {:coin-set #{"xyz:GOOGL"}}
                               :coin-search ""
                               :coin-suggestions-open? true
                               :filter-open? true
                               :loading? false
                               :error nil}
        controls (@#'view/funding-history-controls funding-history-state
                                                   [funding-row-pump funding-row-xyz])
        xyz-label (hiccup/find-first-node controls #(contains? (hiccup/direct-texts %) "xyz"))
        googl-label (hiccup/find-first-node controls #(contains? (hiccup/direct-texts %) "GOOGL"))
        remove-button (hiccup/find-first-node controls #(= "Remove xyz:GOOGL filter" (get-in % [1 :aria-label])))
        controls-strings (set (hiccup/collect-strings controls))]
    (is (some? xyz-label))
    (is (some? googl-label))
    (is (some? remove-button))
    (is (= [[:actions/toggle-funding-history-filter-coin "xyz:GOOGL"]]
           (get-in remove-button [1 :on :click])))
    (is (contains? (hiccup/node-class-set xyz-label) "bg-[#242924]"))
    (is (contains? controls-strings "GOOGL"))
    (is (contains? controls-strings "xyz"))
    (is (not (contains? controls-strings "xyz:GOOGL")))
    (is (nil? (hiccup/find-first-node controls #(= "checkbox" (get-in % [1 :type])))))))
