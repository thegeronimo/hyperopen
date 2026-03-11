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
    (doseq [label ["Reduce Only" "Trigger Conditions" "TP/SL"]
            :let [label-node (hiccup/find-first-node header-node
                                                     #(and (= :div (first %))
                                                           (contains? (hiccup/direct-texts %) label)))
                  label-classes (hiccup/node-class-set label-node)]]
      (is (some? label-node))
      (is (contains? label-classes "text-trading-text-secondary"))
      (is (contains? label-classes "min-h-6"))
      (is (contains? label-classes "w-full")))))

(deftest open-orders-cancel-all-header-renders-as-action-button-for-visible-rows-test
  (let [btc-row {:oid 101
                 :coin "BTC"
                 :side "B"
                 :sz "1.0"
                 :orig-sz "1.0"
                 :px "100.0"
                 :type "Limit"
                 :time 1700000000000
                 :reduce-only false
                 :is-trigger false
                 :trigger-condition nil
                 :is-position-tpsl false}
        sol-short-older {:oid 202
                         :coin "SOL"
                         :side "A"
                         :sz "2.0"
                         :orig-sz "2.0"
                         :px "90.0"
                         :type "Limit"
                         :time 1700000001000
                         :reduce-only false
                         :is-trigger false
                         :trigger-condition nil
                         :is-position-tpsl false}
        sol-short-newer {:oid 303
                         :coin "SOL"
                         :side "S"
                         :sz "3.0"
                         :orig-sz "3.0"
                         :px "91.0"
                         :type "Limit"
                         :time 1700000002000
                         :reduce-only false
                         :is-trigger false
                         :trigger-condition nil
                         :is-position-tpsl false}
        content (view/open-orders-tab-content [btc-row sol-short-older sol-short-newer]
                                              {:column "Time" :direction :desc}
                                              {:direction-filter :short
                                               :coin-search "sol"})
        header-node (hiccup/tab-header-node content)
        cancel-button (hiccup/find-first-node header-node
                                              #(and (= :button (first %))
                                                    (contains? (hiccup/direct-texts %) "Cancel All")))
        cancel-button-classes (hiccup/node-class-set cancel-button)]
    (is (some? cancel-button))
    (is (= "Cancel all visible open orders"
           (get-in cancel-button [1 :aria-label])))
    (is (contains? cancel-button-classes "text-trading-red"))
    (is (contains? cancel-button-classes "min-h-6"))
    (is (contains? cancel-button-classes "w-full"))
    (is (contains? cancel-button-classes "hover:text-[#f2b8c5]"))
    (is (= [[:actions/confirm-cancel-visible-open-orders [sol-short-newer
                                                          sol-short-older]
                                                    :event.currentTarget/bounds]]
           (get-in cancel-button [1 :on :click])))))

(deftest open-orders-cancel-visible-confirmation-renders-dismiss-and-submit-actions-test
  (let [btc-row {:oid 101
                 :coin "BTC"
                 :side "B"
                 :sz "1.0"
                 :orig-sz "1.0"
                 :px "100.0"
                 :type "Limit"
                 :time 1700000000000
                 :reduce-only false
                 :is-trigger false
                 :trigger-condition nil
                 :is-position-tpsl false}
        content (view/open-orders-tab-content [btc-row]
                                              {:column "Time" :direction :desc}
                                              {:cancel-visible-confirmation
                                               {:open? true
                                                :orders [btc-row]
                                                :anchor {:left 940
                                                         :right 1012
                                                         :top 118
                                                         :bottom 142
                                                         :viewport-width 1440
                                                         :viewport-height 900}}})
        dialog (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation")
        backdrop (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-backdrop")
        close-button (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-close")
        cancel-button (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-cancel")
        submit-button (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-submit")
        count-pill (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-count")
        message-node (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-message")]
    (is (some? dialog))
    (is (= "Cancel Visible Orders?"
           (first (hiccup/direct-texts
                   (hiccup/find-by-data-role content
                                             "open-orders-cancel-visible-confirmation-title")))))
    (is (= "1 visible open order"
           (first (hiccup/direct-texts count-pill))))
    (is (str/includes? (first (hiccup/direct-texts message-node))
                       "currently shown in Open Orders"))
    (is (= [[:actions/close-cancel-visible-open-orders-confirmation]]
           (get-in backdrop [1 :on :click])))
    (is (= [[:actions/close-cancel-visible-open-orders-confirmation]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/close-cancel-visible-open-orders-confirmation]]
           (get-in cancel-button [1 :on :click])))
    (is (= [[:actions/submit-cancel-visible-open-orders-confirmation]]
           (get-in submit-button [1 :on :click])))
    (is (= [[:actions/handle-cancel-visible-open-orders-confirmation-keydown [:event/key]]]
           (get-in dialog [1 :on :keydown])))
    (is (string? (get-in dialog [1 :style :left])))
    (is (string? (get-in dialog [1 :style :top])))))

(deftest open-orders-grid-template-expands-the-coin-track-without-collapsing-tail-columns-test
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
                       "minmax(90px,1.15fr)"))
    (is (str/includes? header-grid-class
                       "minmax(76px,0.82fr)_minmax(112px,1.15fr)_minmax(64px,0.72fr)_minmax(72px,0.74fr)"))))

(deftest open-orders-rows-center-grid-items-vertically-test
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
        row-node (hiccup/first-viewport-row content)
        row-classes (hiccup/node-class-set row-node)]
    (is (contains? row-classes "grid"))
    (is (contains? row-classes "items-center"))))

(deftest open-orders-tab-content-memoizes-sorting-by-input-signature-and-sort-state-test
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
                  (fn
                    ([orders _column _direction]
                     (swap! sort-calls inc)
                     orders)
                    ([orders _column _direction _market-by-key]
                     (swap! sort-calls inc)
                     orders))]
      (view/open-orders-tab-content rows sort-state)
      (view/open-orders-tab-content rows sort-state)
      (is (= 1 @sort-calls))

      (let [sort-state-asc (assoc sort-state :direction :asc)]
        (view/open-orders-tab-content rows sort-state-asc)
        (view/open-orders-tab-content rows sort-state-asc)
        (is (= 2 @sort-calls))

        (let [churned-rows (into [] rows)]
          (view/open-orders-tab-content churned-rows sort-state-asc)
          (view/open-orders-tab-content churned-rows sort-state-asc)
          (is (= 2 @sort-calls)))

        (let [changed-rows (assoc-in (into [] rows) [0 :px] "101.0")]
          (view/open-orders-tab-content changed-rows sort-state-asc)
          (is (= 3 @sort-calls)))))))

(deftest open-orders-tab-content-re-sorts-and-re-indexes-when-coin-market-labels-change-test
  (let [rows [{:oid 1001
               :coin "@107"
               :side "B"
               :sz "1.0"
               :orig-sz "1.0"
               :px "100.0"
               :type "Limit"
               :time 1700000000000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}
              {:oid 1002
               :coin "@108"
               :side "A"
               :sz "1.0"
               :orig-sz "1.0"
               :px "101.0"
               :type "Limit"
               :time 1699999999000
               :reduce-only false
               :is-trigger false
               :trigger-condition nil
               :is-position-tpsl false}]
        sort-state {:column "Coin" :direction :asc}
        market-by-key {"spot:@107" {:coin "@107"
                                    :symbol "ZZZ/USDC"
                                    :base "ZZZ"
                                    :market-type :spot}
                       "spot:@108" {:coin "@108"
                                    :symbol "AAA/USDC"
                                    :base "AAA"
                                    :market-type :spot}}
        equivalent-market (into {} market-by-key)
        changed-market {"spot:@107" {:coin "@107"
                                     :symbol "AAA/USDC"
                                     :base "AAA"
                                     :market-type :spot}
                        "spot:@108" {:coin "@108"
                                     :symbol "ZZZ/USDC"
                                     :base "ZZZ"
                                     :market-type :spot}}
        sort-calls (atom 0)
        index-calls (atom 0)
        original-sort open-orders-tab/sort-open-orders-by-column
        original-index-builder @#'open-orders-tab/*build-open-orders-coin-search-index*]
    (open-orders-tab/reset-open-orders-sort-cache!)
    (with-redefs [open-orders-tab/sort-open-orders-by-column
                  (fn
                    ([orders column direction]
                     (swap! sort-calls inc)
                     (original-sort orders column direction))
                    ([orders column direction market-by-key*]
                     (swap! sort-calls inc)
                     (original-sort orders column direction market-by-key*)))
                  open-orders-tab/*build-open-orders-coin-search-index*
                  (fn [sorted-rows market-by-key*]
                    (swap! index-calls inc)
                    (original-index-builder sorted-rows market-by-key*))]
      (view/open-orders-tab-content rows sort-state {:market-by-key market-by-key})
      (view/open-orders-tab-content rows sort-state {:market-by-key market-by-key})
      (is (= 1 @sort-calls))
      (is (= 1 @index-calls))

      (view/open-orders-tab-content rows sort-state {:market-by-key equivalent-market})
      (is (= 1 @sort-calls))
      (is (= 1 @index-calls))

      (let [changed-content (view/open-orders-tab-content rows sort-state {:market-by-key changed-market})
            changed-row (hiccup/first-viewport-row changed-content)
            changed-coin-cell (nth (vec (hiccup/node-children changed-row)) 2)
            changed-strings (set (hiccup/collect-strings changed-coin-cell))]
        (is (= 2 @sort-calls))
        (is (= 2 @index-calls))
        (is (contains? changed-strings "AAA"))
        (is (not (contains? changed-strings "ZZZ")))))))

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

(deftest open-orders-tab-content-re-sorts-when-direction-filter-changes-but-not-when-only-coin-search-changes-test
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
                  (fn
                    ([orders _column _direction]
                     (swap! sort-calls inc)
                     orders)
                    ([orders _column _direction _market-by-key]
                     (swap! sort-calls inc)
                     orders))]
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
      (is (= 2 @sort-calls)))))

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
                                                      (contains? (hiccup/node-class-set %) "whitespace-nowrap")
                                                      (contains? (hiccup/direct-texts %) "NVDA")))
        short-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                       (contains? (hiccup/node-class-set %) "whitespace-nowrap")
                                                       (contains? (hiccup/direct-texts %) "PUMP")))]
    (is (some? long-coin-base))
    (is (some? short-coin-base))
    (is (not (contains? (hiccup/node-class-set long-coin-base) "truncate")))
    (is (not (contains? (hiccup/node-class-set short-coin-base) "truncate")))
    (is (contains? (hiccup/node-class-set long-coin-base) "font-semibold"))
    (is (contains? (hiccup/node-class-set short-coin-base) "font-semibold"))
    (is (= "rgb(151, 252, 228)"
           (get-in long-coin-base [1 :style :color])))
    (is (= "rgb(234, 175, 184)"
           (get-in short-coin-base [1 :style :color])))))

(deftest open-orders-tab-content-resolves-raw-market-ids-through-market-by-key-test
  (let [open-orders [{:oid 101
                      :coin "@107"
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
        market-by-key {"spot:@107" {:coin "@107"
                                    :market-type :spot
                                    :symbol "AAPL/USDC"
                                    :base "AAPL"
                                    :quote "USDC"}}
        content (view/open-orders-tab-content open-orders
                                              {:column "Time" :direction :desc}
                                              {:coin-search "aa"
                                               :market-by-key market-by-key})
        text-content (set (hiccup/collect-strings content))
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 2)
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (contains? text-content "AAPL"))
    (is (not (contains? text-content "@107")))
    (is (some? coin-button))
    (is (= [[:actions/select-asset "@107"]]
           (get-in coin-button [1 :on :click])))))

(deftest open-orders-row-cancel-action-renders-text-button-without-btn-chrome-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
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
        cancel-cell (nth (vec (hiccup/node-children row-node)) 11)
        action-button (hiccup/find-first-node cancel-cell #(= :button (first %)))
        button-classes (hiccup/node-class-set action-button)]
    (is (some? action-button))
    (is (contains? (set (hiccup/collect-strings cancel-cell)) "Cancel"))
    (is (contains? button-classes "inline-flex"))
    (is (not (contains? button-classes "btn")))
    (is (not (contains? button-classes "btn-spectate")))))

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
