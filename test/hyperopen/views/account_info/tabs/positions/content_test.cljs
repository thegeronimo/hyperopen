(ns hyperopen.views.account-info.tabs.positions.content-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info.tabs.positions.test-support :as test-support]))

(use-fixtures :each test-support/reset-positions-sort-cache-fixture)

(deftest positions-tab-content-recomputes-sorting-per-render-without-view-cache-test
  (let [positions [fixtures/sample-position-data]
        sort-state {:column "Coin" :direction :asc}
        sort-calls (atom 0)]
    (positions-tab/reset-positions-sort-cache!)
    (with-redefs [positions-vm/sort-row-vms-by-column
                  (fn [rows _column _direction]
                    (swap! sort-calls inc)
                    rows)]
      (test-support/render-positions-tab-from-rows positions sort-state)
      (test-support/render-positions-tab-from-rows positions sort-state)
      (is (= 2 @sort-calls))

      (let [desc-state (assoc sort-state :direction :desc)]
        (test-support/render-positions-tab-from-rows positions desc-state)
        (test-support/render-positions-tab-from-rows positions desc-state)
        (is (= 4 @sort-calls))

        (let [churned-positions (into [] positions)]
          (test-support/render-positions-tab-from-rows churned-positions desc-state)
          (test-support/render-positions-tab-from-rows churned-positions desc-state)
          (is (= 6 @sort-calls)))

        (let [changed-positions (assoc-in (into [] positions) [0 :position :coin] "xyz:TSLA")]
          (test-support/render-positions-tab-from-rows changed-positions desc-state)
          (is (= 7 @sort-calls)))))))

(deftest positions-tab-content-filters-rows-by-direction-filter-test
  (let [rows [(fixtures/sample-position-row "LONGCOIN" 5 "1.0")
              (fixtures/sample-position-row "SHORTA" 5 "-2.0")
              (fixtures/sample-position-row "SHORTB" 5 "-3.0")]
        sort-state {:column "Coin" :direction :asc}
        all-content (test-support/render-positions-tab-from-rows rows sort-state nil {:direction-filter :all})
        long-content (test-support/render-positions-tab-from-rows rows sort-state nil {:direction-filter :long})
        short-content (test-support/render-positions-tab-from-rows rows sort-state nil {:direction-filter :short})
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
    (is (not (contains? long-text "SHORTB")))
    (is (contains? short-text "SHORTA"))
    (is (contains? short-text "SHORTB"))
    (is (not (contains? short-text "LONGCOIN")))))

(deftest positions-tab-content-filters-rows-by-fuzzy-coin-search-test
  (let [rows [(fixtures/sample-position-row "xyz:NVDA" 5 "1.0")
              (fixtures/sample-position-row "SOL" 5 "1.0")
              (fixtures/sample-position-row "PUMP" 5 "-1.0")]
        sort-state {:column "Coin" :direction :asc}
        search-content (test-support/render-positions-tab-from-rows rows
                                                                    sort-state
                                                                    nil
                                                                    {:direction-filter :all
                                                                     :coin-search "nd"})
        short-and-search-content (test-support/render-positions-tab-from-rows rows
                                                                              sort-state
                                                                              nil
                                                                              {:direction-filter :short
                                                                               :coin-search "pu"})
        search-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node search-content))))
        short-search-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node short-and-search-content))))
        search-strings (set (hiccup/collect-strings search-content))
        short-search-strings (set (hiccup/collect-strings short-and-search-content))]
    (is (= 1 search-row-count))
    (is (contains? search-strings "NVDA"))
    (is (not (contains? search-strings "SOL")))
    (is (= 1 short-search-row-count))
    (is (contains? short-search-strings "PUMP"))
    (is (not (contains? short-search-strings "NVDA")))))

(deftest positions-tab-content-empty-state-distinguishes-no-rows-vs-no-matches-test
  (let [sort-state {:column "Coin" :direction :asc}
        no-rows-content (test-support/render-positions-tab-from-rows []
                                                                sort-state
                                                                nil
                                                                {:direction-filter :all})
        no-matches-content (test-support/render-positions-tab-from-rows [(fixtures/sample-position-row "SOL" 5 "1.0")]
                                                                        sort-state
                                                                        nil
                                                                        {:direction-filter :short
                                                                         :coin-search "zzzz"})
        no-rows-strings (set (hiccup/collect-strings no-rows-content))
        no-matches-strings (set (hiccup/collect-strings no-matches-content))]
    (is (contains? no-rows-strings "No active positions"))
    (is (contains? no-matches-strings "No matching positions"))))

(deftest positions-tab-content-invalid-direction-filter-falls-back-to-all-test
  (let [rows [(fixtures/sample-position-row "LONGCOIN" 5 "1.0")
              (fixtures/sample-position-row "SHORTCOIN" 5 "-1.0")]
        sort-state {:column "Coin" :direction :asc}
        content (test-support/render-positions-tab-from-rows rows
                                                             sort-state
                                                             nil
                                                             {:direction-filter "unsupported"})
        row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node content))))
        strings (set (hiccup/collect-strings content))]
    (is (= 2 row-count))
    (is (contains? strings "LONGCOIN"))
    (is (contains? strings "SHORTCOIN"))))

(deftest positions-tab-content-invokes-sort-kernel-across-direction-filter-renders-test
  (let [rows [(fixtures/sample-position-row "ETH" 5 "1.0")
              (fixtures/sample-position-row "BTC" 5 "-1.0")]
        sort-state {:column "Coin" :direction :asc}
        sort-calls (atom 0)]
    (positions-tab/reset-positions-sort-cache!)
    (with-redefs [positions-vm/sort-row-vms-by-column
                  (fn [positions _column _direction]
                    (swap! sort-calls inc)
                    positions)]
      (test-support/render-positions-tab-from-rows rows sort-state nil {:direction-filter :all})
      (test-support/render-positions-tab-from-rows rows sort-state nil {:direction-filter :all})
      (test-support/render-positions-tab-from-rows rows sort-state nil {:direction-filter :short})
      (test-support/render-positions-tab-from-rows rows sort-state nil {:direction-filter :short})
      (is (= 4 @sort-calls)))))

(deftest positions-tab-content-does-not-render-legacy-subheader-row-test
  (let [webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}}
        content (test-support/render-positions-tab-from-webdata webdata2 fixtures/default-sort-state {})
        title-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Positions ("))
        active-positions-node (hiccup/find-first-node content #(contains? (hiccup/direct-texts %) "Active positions"))]
    (is (nil? title-node))
    (is (nil? active-positions-node))))

(deftest positions-tab-content-coin-cell-can-opt-into-trade-navigation-test
  (let [content (test-support/render-positions-tab-from-rows
                 [(fixtures/sample-position-row "xyz:NVDA" 10 "0.500")]
                 fixtures/default-sort-state
                 nil
                 nil
                 nil
                 {:navigate-to-trade-on-coin-click? true})
        coin-button (hiccup/find-first-node content
                                            #(= "positions-coin-select"
                                                (get-in % [1 :data-role])))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "xyz:NVDA"]
            [:actions/navigate "/trade/xyz:NVDA"]]
           (get-in coin-button [1 :on :click])))))

(deftest sort-positions-by-column-uses-mark-price-over-entry-price-test
  (let [positions [{:position {:coin "AAA"
                               :entryPx "100"
                               :markPx "90"}}
                   {:position {:coin "BBB"
                               :entryPx "10"
                               :markPx "120"}}
                   {:position {:coin "CCC"
                               :entryPx "75"}}]
        asc-result (positions-tab/sort-positions-by-column positions "Mark Price" :asc)
        desc-result (positions-tab/sort-positions-by-column positions "Mark Price" :desc)]
    (is (= ["CCC" "AAA" "BBB"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["BBB" "AAA" "CCC"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest sort-positions-by-column-funding-uses-since-open-display-sign-convention-test
  (let [positions [{:position {:coin "XRP"
                               :cumFunding {:allTime "-40929.847103"
                                            :sinceOpen "0.718656"}}}
                   {:position {:coin "SOL"
                               :cumFunding {:allTime "-160892.374868"
                                            :sinceOpen "-44.233025"}}}
                   {:position {:coin "ZERO"
                               :cumFunding {:allTime "0"
                                            :sinceOpen "0"}}}]
        asc-result (positions-tab/sort-positions-by-column positions "Funding" :asc)
        desc-result (positions-tab/sort-positions-by-column positions "Funding" :desc)]
    (is (= ["XRP" "ZERO" "SOL"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["SOL" "ZERO" "XRP"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest sort-positions-by-column-size-uses-absolute-size-values-test
  (let [positions [{:position {:coin "TEN" :szi "-10"}}
                   {:position {:coin "ONE" :szi "1"}}
                   {:position {:coin "TWO" :szi "-2"}}]
        asc-result (positions-tab/sort-positions-by-column positions "Size" :asc)
        desc-result (positions-tab/sort-positions-by-column positions "Size" :desc)]
    (is (= ["ONE" "TWO" "TEN"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["TEN" "TWO" "ONE"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest sort-positions-by-column-coin-uses-displayed-coin-label-test
  (let [positions [{:position {:coin "z:AAA"}}
                   {:position {:coin "AAB"}}
                   {:position {:coin "z:AAC"}}]
        asc-result (positions-tab/sort-positions-by-column positions "Coin" :asc)]
    (is (= ["z:AAA" "AAB" "z:AAC"] (mapv #(get-in % [:position :coin]) asc-result)))))

(deftest sort-positions-by-column-pnl-uses-roe-tie-breaker-test
  (let [positions [{:position {:coin "LOW-ROE"
                               :unrealizedPnl "5"
                               :returnOnEquity "0.10"}}
                   {:position {:coin "HIGH-ROE"
                               :unrealizedPnl "5"
                               :returnOnEquity "0.25"}}
                   {:position {:coin "LOWER-PNL"
                               :unrealizedPnl "4"
                               :returnOnEquity "0.99"}}]
        asc-result (positions-tab/sort-positions-by-column positions "PNL (ROE %)" :asc)
        desc-result (positions-tab/sort-positions-by-column positions "PNL (ROE %)" :desc)]
    (is (= ["LOWER-PNL" "LOW-ROE" "HIGH-ROE"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["HIGH-ROE" "LOW-ROE" "LOWER-PNL"] (mapv #(get-in % [:position :coin]) desc-result)))))
