(ns hyperopen.views.account-info.tabs.open-orders-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]
            [hyperopen.views.account-info-view :as view]))

(defn- reset-open-orders-sort-cache-fixture
  [f]
  (open-orders-tab/reset-open-orders-sort-cache!)
  (f)
  (open-orders-tab/reset-open-orders-sort-cache!))

(use-fixtures :each reset-open-orders-sort-cache-fixture)

(deftest open-orders-sortable-header-uses-secondary-text-and-hover-affordance-test
  (let [header-node (view/sortable-open-orders-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (hiccup/node-children header-node)))]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-open-orders "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest open-orders-static-headers-use-secondary-text-style-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only true
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        header-node (hiccup/tab-header-node content)]
    (doseq [label ["Reduce Only" "Trigger Conditions" "TP/SL" "Cancel All"]
            :let [label-node (hiccup/find-first-node header-node
                                                     #(and (= :div (first %))
                                                           (contains? (hiccup/direct-texts %) label)))
                  label-classes (hiccup/node-class-set label-node)]]
      (is (some? label-node))
      (is (contains? label-classes "text-trading-text-secondary"))
      (is (contains? label-classes "min-h-6"))
      (is (contains? label-classes "w-full")))))

(deftest open-orders-grid-template-keeps-right-columns-readable-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only true
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        header-grid-class (some #(when (str/starts-with? % "grid-cols-[") %)
                                (hiccup/node-class-set (hiccup/tab-header-node content)))
        row-grid-class (some #(when (str/starts-with? % "grid-cols-[") %)
                             (hiccup/node-class-set (hiccup/first-viewport-row content)))]
    (is (some? header-grid-class))
    (is (= header-grid-class row-grid-class))
    (is (str/includes? header-grid-class
                       "minmax(80px,0.95fr)_minmax(120px,1.35fr)_minmax(70px,0.8fr)_minmax(80px,0.9fr)"))))

(deftest open-orders-tab-content-memoizes-sorting-by-input-identity-and-sort-state-test
  (let [rows [{:oid 1001
               :coin "ETH"
               :side "B"
               :sz "2.0"
               :orig-sz "2.0"
               :px "100.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        sort-calls (atom 0)]
    (open-orders-tab/reset-open-orders-sort-cache!)
    (with-redefs [open-orders-tab/sort-open-orders-by-column
                  (fn [orders _column _direction]
                    (swap! sort-calls inc)
                    orders)]
      (view/open-orders-tab-content rows sort-state)
      (view/open-orders-tab-content rows sort-state)
      (is (= 1 @sort-calls))

      (let [sort-state-asc (assoc sort-state :direction :asc)]
        (view/open-orders-tab-content rows sort-state-asc)
        (view/open-orders-tab-content rows sort-state-asc)
        (is (= 2 @sort-calls))

        (view/open-orders-tab-content (into [] rows) sort-state-asc)
        (is (= 3 @sort-calls))))))

(deftest open-orders-tab-content-filters-rows-by-direction-filter-test
  (let [rows [{:oid 1001
               :coin "LONGCOIN"
               :side "B"
               :sz "1.0"
               :orig-sz "1.0"
               :px "100.0"
               :type "Limit"
               :time 1700000002000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "SHORTA"
               :side "A"
               :sz "2.0"
               :orig-sz "2.0"
               :px "99.0"
               :type "Limit"
               :time 1700000001000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1003
               :coin "SHORTS"
               :side "S"
               :sz "3.0"
               :orig-sz "3.0"
               :px "98.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        all-content (view/open-orders-tab-content rows sort-state {:direction-filter :all})
        long-content (view/open-orders-tab-content rows sort-state {:direction-filter :long})
        short-content (view/open-orders-tab-content rows sort-state {:direction-filter :short})
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
    (is (not (contains? long-text "SHORTS")))
    (is (contains? short-text "SHORTA"))
    (is (contains? short-text "SHORTS"))
    (is (not (contains? short-text "LONGCOIN")))))

(deftest open-orders-tab-content-filters-rows-by-coin-search-test
  (let [rows [{:oid 1001
               :coin "xyz:NVDA"
               :side "B"
               :sz "1.0"
               :orig-sz "1.0"
               :px "100.0"
               :type "Limit"
               :time 1700000002000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "HYPE"
               :side "A"
               :sz "2.0"
               :orig-sz "2.0"
               :px "99.0"
               :type "Limit"
               :time 1700000001000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        all-content (view/open-orders-tab-content rows sort-state {:coin-search ""})
        symbol-search-content (view/open-orders-tab-content rows sort-state {:coin-search "nv"})
        prefix-search-content (view/open-orders-tab-content rows sort-state {:coin-search "xyz"})
        all-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node all-content))))
        symbol-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node symbol-search-content))))
        prefix-row-count (count (vec (hiccup/node-children (hiccup/tab-rows-viewport-node prefix-search-content))))
        symbol-text (set (hiccup/collect-strings symbol-search-content))
        prefix-text (set (hiccup/collect-strings prefix-search-content))]
    (is (= 2 all-row-count))
    (is (= 1 symbol-row-count))
    (is (= 1 prefix-row-count))
    (is (contains? symbol-text "NVDA"))
    (is (not (contains? symbol-text "HYPE")))
    (is (contains? prefix-text "NVDA"))
    (is (contains? prefix-text "xyz"))))

(deftest open-orders-tab-content-re-sorts-when-direction-filter-changes-test
  (let [rows [{:oid 1001
               :coin "ETH"
               :side "B"
               :sz "2.0"
               :orig-sz "2.0"
               :px "100.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "BTC"
               :side "A"
               :sz "1.0"
               :orig-sz "1.0"
               :px "99.0"
               :type "Limit"
               :time 1699999999000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Time" :direction :desc}
        sort-calls (atom 0)]
    (open-orders-tab/reset-open-orders-sort-cache!)
    (with-redefs [open-orders-tab/sort-open-orders-by-column
                  (fn [orders _column _direction]
                    (swap! sort-calls inc)
                    orders)]
      (view/open-orders-tab-content rows sort-state {:direction-filter :all})
      (view/open-orders-tab-content rows sort-state {:direction-filter :all})
      (is (= 1 @sort-calls))
      (view/open-orders-tab-content rows sort-state {:direction-filter :short})
      (view/open-orders-tab-content rows sort-state {:direction-filter :short})
      (is (= 2 @sort-calls))
      (view/open-orders-tab-content rows sort-state {:direction-filter :short
                                                     :coin-search "eth"})
      (view/open-orders-tab-content rows sort-state {:direction-filter :short
                                                     :coin-search "eth"})
      (is (= 3 @sort-calls)))))

(deftest open-orders-columns-use-left-alignment-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only true
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        header-node (hiccup/tab-header-node content)
        header-cells (vec (hiccup/node-children header-node))
        row-node (hiccup/first-viewport-row content)
        row-cells (vec (hiccup/node-children row-node))]
    (doseq [idx (range (count header-cells))]
      (is (not (contains? (hiccup/node-class-set (nth header-cells idx)) "text-right"))))
    (doseq [idx (range (count row-cells))]
      (is (not (contains? (hiccup/node-class-set (nth row-cells idx)) "text-right")))
      (is (not (contains? (hiccup/node-class-set (nth row-cells idx)) "num-right"))))
    (doseq [idx (range (count header-cells))]
      (is (contains? (hiccup/node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx (range (count row-cells))]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left")))))

(deftest normalized-open-orders-prefers-live-source-and-includes-dex-snapshots-test
  (let [live-orders [{:order {:coin "BTC" :oid 1 :side "B" :sz "1.0" :limitPx "100" :timestamp 1000}}]
        snapshot-orders [{:order {:coin "ETH" :oid 2 :side "A" :sz "2.0" :limitPx "200" :timestamp 900}}]
        snapshot-by-dex {:dex-a [{:order {:coin "SOL" :oid 3 :side "B" :sz "3.0" :limitPx "50" :timestamp 800}}]}
        with-live (view/normalized-open-orders live-orders snapshot-orders snapshot-by-dex)
        without-live (view/normalized-open-orders nil snapshot-orders snapshot-by-dex)]
    (is (= #{"1" "3"} (set (map :oid with-live))))
    (is (= #{"BTC" "SOL"} (set (map :coin with-live))))
    (is (= #{"2" "3"} (set (map :oid without-live))))
    (is (= #{"ETH" "SOL"} (set (map :coin without-live))))))

(deftest open-orders-coin-labels-are-bold-and-side-colored-test
  (let [open-orders [{:oid 101
                      :coin "xyz:NVDA"
                      :side "B"
                      :sz "1.0"
                      :orig-sz "1.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000001000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}
                     {:oid 102
                      :coin "PUMP"
                      :side "A"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "99.5"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        long-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                      (contains? (hiccup/node-class-set %) "truncate")
                                                      (contains? (hiccup/direct-texts %) "NVDA")))
        short-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                       (contains? (hiccup/node-class-set %) "truncate")
                                                       (contains? (hiccup/direct-texts %) "PUMP")))]
    (is (some? long-coin-base))
    (is (some? short-coin-base))
    (is (contains? (hiccup/node-class-set long-coin-base) "font-semibold"))
    (is (contains? (hiccup/node-class-set short-coin-base) "font-semibold"))
    (is (= "rgb(151, 252, 228)"
           (get-in long-coin-base [1 :style :color])))
    (is (= "rgb(234, 175, 184)"
           (get-in short-coin-base [1 :style :color])))))

(deftest open-orders-coin-cell-dispatches-select-asset-action-test
  (let [open-orders [{:oid 101
                      :coin "xyz:NVDA"
                      :side "B"
                      :sz "1.0"
                      :orig-sz "1.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000001000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (view/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 2)
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "xyz:NVDA"]]
           (get-in coin-button [1 :on :click])))))

(deftest format-tp-sl-treats-reduce-only-take-profit-orders-as-position-tpsl-test
  (is (= "TP/SL"
         (view/format-tp-sl {:is-position-tpsl false
                             :reduce-only true
                             :type "Take Profit Market"})))
  (is (= "-- / --"
         (view/format-tp-sl {:is-position-tpsl false
                             :reduce-only false
                             :type "Take Profit Market"}))))
