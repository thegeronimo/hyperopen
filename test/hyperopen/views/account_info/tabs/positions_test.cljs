(ns hyperopen.views.account-info.tabs.positions-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]
            [hyperopen.views.account-info-view :as view]))

(defn- reset-positions-sort-cache-fixture
  [f]
  (positions-tab/reset-positions-sort-cache!)
  (f)
  (positions-tab/reset-positions-sort-cache!))

(defn- with-viewport [width height f]
  (let [original-inner-width (.-innerWidth js/globalThis)
        original-inner-height (.-innerHeight js/globalThis)]
    (set! (.-innerWidth js/globalThis) width)
    (set! (.-innerHeight js/globalThis) height)
    (try
      (f)
      (finally
        (set! (.-innerWidth js/globalThis) original-inner-width)
        (set! (.-innerHeight js/globalThis) original-inner-height)))))

(defn- with-phone-viewport [f]
  (with-viewport 430 932 f))

(use-fixtures :each reset-positions-sort-cache-fixture)

(defn- render-positions-tab-from-rows
  ([rows sort-state]
   (render-positions-tab-from-rows rows sort-state nil nil nil {}))
  ([rows sort-state tpsl-modal positions-state]
   (render-positions-tab-from-rows rows sort-state tpsl-modal nil nil positions-state))
  ([rows sort-state tpsl-modal reduce-popover margin-modal positions-state]
   (view/positions-tab-content {:positions rows
                                :sort-state sort-state
                                :tpsl-modal tpsl-modal
                                :reduce-popover reduce-popover
                                :margin-modal margin-modal
                                :positions-state positions-state})))

(defn- render-positions-tab-from-webdata
  [webdata2 sort-state perp-dex-states]
  (view/positions-tab-content {:webdata2 webdata2
                               :sort-state sort-state
                               :perp-dex-states perp-dex-states}))

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

(deftest positions-tab-content-recomputes-sorting-per-render-without-view-cache-test
  (let [positions [fixtures/sample-position-data]
        sort-state {:column "Coin" :direction :asc}
        sort-calls (atom 0)]
    (positions-tab/reset-positions-sort-cache!)
    (with-redefs [positions-vm/sort-row-vms-by-column
                  (fn [rows _column _direction]
                    (swap! sort-calls inc)
                    rows)]
      (render-positions-tab-from-rows positions sort-state)
      (render-positions-tab-from-rows positions sort-state)
      (is (= 2 @sort-calls))

      (let [desc-state (assoc sort-state :direction :desc)]
        (render-positions-tab-from-rows positions desc-state)
        (render-positions-tab-from-rows positions desc-state)
        (is (= 4 @sort-calls))

        (let [churned-positions (into [] positions)]
          (render-positions-tab-from-rows churned-positions desc-state)
          (render-positions-tab-from-rows churned-positions desc-state)
          (is (= 6 @sort-calls)))

        (let [changed-positions (assoc-in (into [] positions) [0 :position :coin] "xyz:TSLA")]
          (render-positions-tab-from-rows changed-positions desc-state)
          (is (= 7 @sort-calls)))))))

(deftest positions-tab-content-filters-rows-by-direction-filter-test
  (let [rows [(fixtures/sample-position-row "LONGCOIN" 5 "1.0")
              (fixtures/sample-position-row "SHORTA" 5 "-2.0")
              (fixtures/sample-position-row "SHORTB" 5 "-3.0")]
        sort-state {:column "Coin" :direction :asc}
        all-content (render-positions-tab-from-rows rows sort-state nil {:direction-filter :all})
        long-content (render-positions-tab-from-rows rows sort-state nil {:direction-filter :long})
        short-content (render-positions-tab-from-rows rows sort-state nil {:direction-filter :short})
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
        search-content (render-positions-tab-from-rows rows
                                                       sort-state
                                                       nil
                                                       {:direction-filter :all
                                                        :coin-search "nd"})
        short-and-search-content (render-positions-tab-from-rows rows
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
        no-rows-content (render-positions-tab-from-rows []
                                                       sort-state
                                                       nil
                                                       {:direction-filter :all})
        no-matches-content (render-positions-tab-from-rows [(fixtures/sample-position-row "SOL" 5 "1.0")]
                                                           sort-state
                                                           nil
                                                           {:direction-filter :short
                                                            :coin-search "zzzz"})
        no-rows-strings (set (hiccup/collect-strings no-rows-content))
        no-matches-strings (set (hiccup/collect-strings no-matches-content))]
    (is (contains? no-rows-strings "No active positions"))
    (is (contains? no-matches-strings "No matching positions"))))

(deftest positions-tab-content-renders-mobile-summary-cards-with-inline-expansion-test
  (with-phone-viewport
    (fn []
      (let [expanded-row (-> (fixtures/sample-position-row "xyz:GOLD" 20 "0.0185" "xyz")
                             (assoc-in [:position :positionValue] "95.55")
                             (assoc-in [:position :entryPx] "5382.4")
                             (assoc-in [:position :markPx] "5164.6")
                             (assoc-in [:position :liquidationPx] "4407.1")
                             (assoc-in [:position :marginUsed] "15.64")
                             (assoc-in [:position :returnOnEquity] "-0.809")
                             (assoc-in [:position :unrealizedPnl] "-4.03")
                             (assoc-in [:position :cumFunding :allTime] "-0.05")
                             (assoc-in [:position :leverage :type] "isolated"))
            collapsed-row (fixtures/sample-position-row "SOL" 10 "0.61")
            expanded-row-id (view/position-unique-key expanded-row)
            content (render-positions-tab-from-rows [expanded-row collapsed-row]
                                                    fixtures/default-sort-state
                                                    nil
                                                    nil
                                                    nil
                                                    {:direction-filter :all
                                                     :mobile-expanded-card {:positions expanded-row-id}})
            mobile-viewport (hiccup/find-by-data-role content "positions-mobile-cards-viewport")
            mobile-cards (vec (hiccup/node-children mobile-viewport))
            expanded-card (hiccup/find-by-data-role content (str "mobile-position-card-" expanded-row-id))
            collapsed-card (hiccup/find-by-data-role content (str "mobile-position-card-" (view/position-unique-key collapsed-row)))
            expanded-button (first (vec (hiccup/node-children expanded-card)))
            collapsed-button (first (vec (hiccup/node-children collapsed-card)))
            expanded-card-classes (hiccup/node-class-set expanded-card)
            expanded-button-classes (hiccup/node-class-set expanded-button)
            expanded-strings (set (hiccup/collect-strings expanded-card))
            collapsed-strings (set (hiccup/collect-strings collapsed-card))
            margin-edit-button (hiccup/find-first-node expanded-card #(= "Edit Margin" (get-in % [1 :aria-label])))
            tpsl-edit-button (hiccup/find-first-node expanded-card #(= "Edit TP/SL" (get-in % [1 :aria-label])))
            close-button (hiccup/find-first-node expanded-card #(and (= :button (first %))
                                                                     (contains? (hiccup/direct-texts %) "Close")))
            margin-button (hiccup/find-first-node expanded-card #(and (= :button (first %))
                                                                      (contains? (hiccup/direct-texts %) "Margin")))
            tpsl-button (hiccup/find-first-node expanded-card #(and (= :button (first %))
                                                                    (contains? (hiccup/direct-texts %) "TP/SL")))
            margin-action (first (get-in margin-edit-button [1 :on :click]))
            tpsl-action (first (get-in tpsl-edit-button [1 :on :click]))
            close-action (first (get-in close-button [1 :on :click]))
            margin-footer-action (first (get-in margin-button [1 :on :click]))
            tpsl-footer-action (first (get-in tpsl-button [1 :on :click]))
            margin-anchor (nth margin-action 2)
            tpsl-anchor (nth tpsl-action 2)
            close-anchor (nth close-action 2)
            margin-footer-anchor (nth margin-footer-action 2)
            tpsl-footer-anchor (nth tpsl-footer-action 2)]
        (is (some? mobile-viewport))
        (is (= 2 (count mobile-cards)))
        (is (= true (get-in expanded-button [1 :aria-expanded])))
        (is (= [[:actions/toggle-account-info-mobile-card :positions expanded-row-id]]
               (get-in expanded-button [1 :on :click])))
        (is (contains? expanded-card-classes "bg-[#08161f]"))
        (is (contains? expanded-card-classes "border-[#17313d]"))
        (is (not (contains? expanded-card-classes "bg-base-200/70")))
        (is (contains? expanded-button-classes "px-3.5"))
        (is (contains? expanded-button-classes "hover:bg-[#0c1b24]"))
        (is (contains? expanded-strings "Coin"))
        (is (contains? expanded-strings "Size"))
        (is (contains? expanded-strings "PNL (ROE %)"))
        (is (contains? expanded-strings "Entry Price"))
        (is (contains? expanded-strings "Mark Price"))
        (is (contains? expanded-strings "Liq. Price"))
        (is (contains? expanded-strings "Position Value"))
        (is (contains? expanded-strings "Margin"))
        (is (contains? expanded-strings "Funding"))
        (is (contains? expanded-strings "TP/SL"))
        (is (not (contains? expanded-strings "Actions")))
        (is (contains? expanded-strings "Close"))
        (is (contains? expanded-strings "GOLD"))
        (is (contains? expanded-strings "20x"))
        (is (contains? expanded-strings "xyz"))
        (is (contains? (hiccup/node-class-set close-button) "text-trading-green"))
        (is (not (contains? (hiccup/node-class-set close-button) "border")))
        (is (not (contains? (hiccup/node-class-set close-button) "rounded-full")))
        (is (not (contains? (hiccup/node-class-set close-button) "bg-base-100/70")))
        (is (contains? (hiccup/node-class-set margin-edit-button) "h-6"))
        (is (contains? (hiccup/node-class-set margin-edit-button) "w-6"))
        (is (= :actions/open-position-margin-modal
               (first margin-action)))
        (is (= expanded-row
               (second margin-action)))
        (is (map? margin-anchor))
        (is (= 430 (:viewport-width margin-anchor)))
        (is (= 932 (:viewport-height margin-anchor)))
        (is (= "true" (get-in margin-edit-button [1 :data-position-margin-trigger])))
        (is (= :actions/open-position-tpsl-modal
               (first tpsl-action)))
        (is (= expanded-row
               (second tpsl-action)))
        (is (map? tpsl-anchor))
        (is (= 430 (:viewport-width tpsl-anchor)))
        (is (= 932 (:viewport-height tpsl-anchor)))
        (is (= "true" (get-in tpsl-edit-button [1 :data-position-tpsl-trigger])))
        (is (= :actions/open-position-reduce-popover
               (first close-action)))
        (is (map? close-anchor))
        (is (= 430 (:viewport-width close-anchor)))
        (is (= 932 (:viewport-height close-anchor)))
        (is (= :actions/open-position-margin-modal
               (first margin-footer-action)))
        (is (map? margin-footer-anchor))
        (is (= 430 (:viewport-width margin-footer-anchor)))
        (is (= 932 (:viewport-height margin-footer-anchor)))
        (is (= :actions/open-position-tpsl-modal
               (first tpsl-footer-action)))
        (is (map? tpsl-footer-anchor))
        (is (= 430 (:viewport-width tpsl-footer-anchor)))
        (is (= 932 (:viewport-height tpsl-footer-anchor)))
        (is (contains? (hiccup/node-class-set margin-button) "text-trading-green"))
        (is (contains? (hiccup/node-class-set tpsl-button) "text-trading-green"))
        (is (= false (get-in collapsed-button [1 :aria-expanded])))
        (is (contains? collapsed-strings "SOL"))
        (is (not (contains? collapsed-strings "Entry Price")))))))

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
      (render-positions-tab-from-rows rows sort-state nil {:direction-filter :all})
      (render-positions-tab-from-rows rows sort-state nil {:direction-filter :all})
      (render-positions-tab-from-rows rows sort-state nil {:direction-filter :short})
      (render-positions-tab-from-rows rows sort-state nil {:direction-filter :short})
      (is (= 4 @sort-calls)))))

(deftest positions-tab-content-does-not-render-legacy-subheader-row-test
  (let [webdata2 {:clearinghouseState {:assetPositions [fixtures/sample-position-data]}}
        content (render-positions-tab-from-webdata webdata2 fixtures/default-sort-state {})
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
    (doseq [idx [9 10]]
      (is (contains? (hiccup/node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx [9 10]]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left")))))

(deftest position-table-header-close-all-dispatches-placeholder-action-test
  (let [header-node (view/position-table-header fixtures/default-sort-state)
        close-all-button (hiccup/find-first-node
                          header-node
                          #(and (= :button (first %))
                                (contains? (hiccup/direct-texts %) "Close All")))]
    (is (some? close-all-button))
    (is (= [[:actions/trigger-close-all-positions]]
           (get-in close-all-button [1 :on :click])))))

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
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "-0.500"))
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

(deftest position-row-coin-cell-dispatches-select-asset-action-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        coin-cell (first (vec (hiccup/node-children row-node)))
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "xyz:NVDA"]]
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
        asc-result (view/sort-positions-by-column positions "Mark Price" :asc)
        desc-result (view/sort-positions-by-column positions "Mark Price" :desc)]
    (is (= ["CCC" "AAA" "BBB"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["BBB" "AAA" "CCC"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest sort-positions-by-column-funding-uses-display-sign-convention-test
  (let [positions [{:position {:coin "PAID" :cumFunding {:allTime "0.25"}}}
                   {:position {:coin "RECEIVED" :cumFunding {:allTime "-0.10"}}}
                   {:position {:coin "ZERO" :cumFunding {:allTime "0"}}}]
        asc-result (view/sort-positions-by-column positions "Funding" :asc)
        desc-result (view/sort-positions-by-column positions "Funding" :desc)]
    (is (= ["PAID" "ZERO" "RECEIVED"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["RECEIVED" "ZERO" "PAID"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest sort-positions-by-column-size-uses-absolute-size-values-test
  (let [positions [{:position {:coin "TEN" :szi "-10"}}
                   {:position {:coin "ONE" :szi "1"}}
                   {:position {:coin "TWO" :szi "-2"}}]
        asc-result (view/sort-positions-by-column positions "Size" :asc)
        desc-result (view/sort-positions-by-column positions "Size" :desc)]
    (is (= ["ONE" "TWO" "TEN"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["TEN" "TWO" "ONE"] (mapv #(get-in % [:position :coin]) desc-result)))))

(deftest sort-positions-by-column-coin-uses-displayed-coin-label-test
  (let [positions [{:position {:coin "z:AAA"}}
                   {:position {:coin "AAB"}}
                   {:position {:coin "z:AAC"}}]
        asc-result (view/sort-positions-by-column positions "Coin" :asc)]
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
        asc-result (view/sort-positions-by-column positions "PNL (ROE %)" :asc)
        desc-result (view/sort-positions-by-column positions "PNL (ROE %)" :desc)]
    (is (= ["LOWER-PNL" "LOW-ROE" "HIGH-ROE"] (mapv #(get-in % [:position :coin]) asc-result)))
    (is (= ["HIGH-ROE" "LOW-ROE" "LOWER-PNL"] (mapv #(get-in % [:position :coin]) desc-result)))))

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

(deftest position-row-mark-price-renders-placeholder-when-mark-is-missing-test
  (let [row-node (view/position-row {:position {:coin "NOMARK"
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
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        row-cells (vec (hiccup/node-children row-node))
        pnl-cell (nth row-cells 5)
        pnl-texts (set (hiccup/collect-strings pnl-cell))]
    (is (contains? pnl-texts "+$1.25 (+10.0%)"))
    (is (nil? (hiccup/find-first-node pnl-cell #(contains? (hiccup/node-class-set %) "text-xs"))))))

(deftest position-row-limits-liquidation-price-display-to-six-chars-test
  (let [row-data (-> (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                     (assoc-in [:position :liquidationPx] "5222.57562052"))
        row-node (view/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        liq-cell (nth row-cells 6)
        liq-texts (set (hiccup/collect-strings liq-cell))]
    (is (contains? liq-texts "$5,222.6"))
    (is (not-any? #(str/includes? % "57562052") liq-texts))))

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
        with-since-change-cell (nth (vec (hiccup/node-children (view/position-row with-since-change-row))) 8)
        with-since-open-cell (nth (vec (hiccup/node-children (view/position-row with-since-open-row))) 8)
        without-since-change-cell (nth (vec (hiccup/node-children (view/position-row without-since-change-row))) 8)
        with-since-change-strings (set (hiccup/collect-strings with-since-change-cell))
        with-since-open-strings (set (hiccup/collect-strings with-since-open-cell))
        without-since-change-strings (set (hiccup/collect-strings without-since-change-cell))]
    (is (contains? with-since-change-strings "All-time: $-0.50 Since change: $-0.25"))
    (is (contains? with-since-open-strings "All-time: $-0.50 Since change: $-0.10"))
    (is (contains? without-since-change-strings "All-time: $-0.50 Since change: --"))
    (is (not-any? #(str/includes? % "NaN") (hiccup/collect-strings with-since-change-cell)))
    (is (not-any? #(str/includes? % "NaN") (hiccup/collect-strings with-since-open-cell)))
    (is (not-any? #(str/includes? % "NaN") (hiccup/collect-strings without-since-change-cell)))))

(deftest position-row-funding-display-shows-received-as-positive-test
  (let [row-data (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                           [:position :cumFunding]
                           {:allTime "-0.5"
                            :sinceChange "-0.25"})
        funding-cell (nth (vec (hiccup/node-children (view/position-row row-data))) 8)
        funding-strings (set (hiccup/collect-strings funding-cell))
        funding-value-node (hiccup/find-first-node funding-cell
                                                   #(and (= :span (first %))
                                                         (contains? (hiccup/node-class-set %) "text-success")))]
    (is (contains? funding-strings "$0.50"))
    (is (contains? funding-strings "All-time: $0.50 Since change: $0.25"))
    (is (some? funding-value-node))))

(deftest position-row-tpsl-cell-includes-edit-affordance-icon-test
  (let [row-node (view/position-row (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
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
        row-node (view/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        tpsl-cell (nth row-cells 10)
        expected-copy (str (view/format-trade-price "12.5")
                           " / "
                           (view/format-trade-price "9.5"))
        tpsl-strings (set (hiccup/collect-strings tpsl-cell))]
    (is (contains? tpsl-strings expected-copy))
    (is (not (contains? tpsl-strings "-- / --")))))

(deftest position-row-tpsl-cell-dispatches-open-modal-action-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        row-node (view/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        tpsl-cell (nth row-cells 10)
        action-button (hiccup/find-first-node tpsl-cell #(= :button (first %)))
        click-actions (get-in action-button [1 :on :click])]
    (is (vector? click-actions))
    (is (= :actions/open-position-tpsl-modal
           (first (first click-actions))))
    (is (= row-data
           (second (first click-actions))))
    (is (= :event.currentTarget/bounds
           (nth (first click-actions) 2)))
    (is (= "true" (get-in action-button [1 :data-position-tpsl-trigger])))))

(deftest position-row-reduce-cell-dispatches-open-popover-action-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        row-node (view/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        reduce-cell (nth row-cells 9)
        action-button (hiccup/find-first-node reduce-cell #(= :button (first %)))
        click-actions (get-in action-button [1 :on :click])]
    (is (= #{"Reduce"} (set (hiccup/collect-strings reduce-cell))))
    (is (vector? click-actions))
    (is (= :actions/open-position-reduce-popover
           (first (first click-actions))))
    (is (= row-data
           (second (first click-actions))))
    (is (= :event.currentTarget/bounds
           (nth (first click-actions) 2)))
    (is (= "true" (get-in action-button [1 :data-position-reduce-trigger])))))

(deftest position-row-reduce-cell-renders-text-button-without-btn-chrome-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        row-node (view/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        reduce-cell (nth row-cells 9)
        action-button (hiccup/find-first-node reduce-cell #(= :button (first %)))
        button-classes (hiccup/node-class-set action-button)]
    (is (some? action-button))
    (is (contains? (set (hiccup/collect-strings reduce-cell)) "Reduce"))
    (is (contains? button-classes "inline-flex"))
    (is (not (contains? button-classes "btn")))
    (is (not (contains? button-classes "btn-spectate")))))

(deftest position-row-margin-cell-dispatches-open-modal-action-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        row-node (view/position-row row-data)
        row-cells (vec (hiccup/node-children row-node))
        margin-cell (nth row-cells 7)
        action-button (hiccup/find-first-node margin-cell #(= :button (first %)))
        edit-icon-node (hiccup/find-first-node margin-cell #(= :svg (first %)))
        value-node (hiccup/find-first-node margin-cell #(and (= :span (first %))
                                                              (contains? (hiccup/node-class-set %) "select-text")))
        click-actions (get-in action-button [1 :on :click])
        margin-strings (set (hiccup/collect-strings margin-cell))]
    (is (contains? margin-strings "$12.00"))
    (is (some? value-node))
    (is (contains? (hiccup/node-class-set action-button) "h-6"))
    (is (contains? (hiccup/node-class-set action-button) "w-6"))
    (is (contains? (hiccup/node-class-set edit-icon-node) "h-4"))
    (is (contains? (hiccup/node-class-set edit-icon-node) "w-4"))
    (is (vector? click-actions))
    (is (= :actions/open-position-margin-modal
           (first (first click-actions))))
    (is (= row-data
           (second (first click-actions))))
    (is (= :event.currentTarget/bounds
           (nth (first click-actions) 2)))
    (is (= "true" (get-in action-button [1 :data-position-margin-trigger])))
    (is (= "Edit Margin" (get-in action-button [1 :aria-label])))))

(deftest position-row-margin-cell-renders-cross-and-isolated-mode-labels-test
  (let [isolated-row (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                               [:position :leverage :type]
                               "isolated")
        cross-row (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                            [:position :leverage :type]
                            "cross")
        isolated-cell (nth (vec (hiccup/node-children (view/position-row isolated-row))) 7)
        cross-cell (nth (vec (hiccup/node-children (view/position-row cross-row))) 7)
        isolated-strings (set (hiccup/collect-strings isolated-cell))
        cross-strings (set (hiccup/collect-strings cross-cell))]
    (is (contains? isolated-strings "(Isolated)"))
    (is (contains? cross-strings "(Cross)"))))

(deftest position-row-margin-cell-preserves-explicit-false-is-cross-flag-test
  (let [row-data (-> (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                     (assoc-in [:position :leverage :type] nil)
                     (assoc-in [:position :isCross] false))
        row-node (view/position-row row-data)
        margin-cell (nth (vec (hiccup/node-children row-node)) 7)
        margin-strings (set (hiccup/collect-strings margin-cell))
        action-button (hiccup/find-first-node margin-cell #(= :button (first %)))]
    (is (contains? margin-strings "(Isolated)"))
    (is (some? action-button))))

(deftest position-row-cross-margin-cell-omits-edit-affordance-test
  (let [row-data (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                           [:position :leverage :type]
                           "cross")
        row-node (view/position-row row-data)
        margin-cell (nth (vec (hiccup/node-children row-node)) 7)
        action-button (hiccup/find-first-node margin-cell #(= :button (first %)))
        margin-strings (set (hiccup/collect-strings margin-cell))]
    (is (contains? margin-strings "$12.00"))
    (is (contains? margin-strings "(Cross)"))
    (is (nil? action-button))))

(deftest positions-tab-content-mobile-cross-margin-card-omits-margin-actions-test
  (with-phone-viewport
    (fn []
      (let [cross-row (assoc-in (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                                [:position :leverage :type]
                                "cross")
            row-id (view/position-unique-key cross-row)
            content (render-positions-tab-from-rows [cross-row]
                                                    fixtures/default-sort-state
                                                    nil
                                                    nil
                                                    nil
                                                    {:direction-filter :all
                                                     :mobile-expanded-card {:positions row-id}})
            expanded-card (hiccup/find-by-data-role content (str "mobile-position-card-" row-id))
            margin-edit-button (hiccup/find-first-node expanded-card #(= "Edit Margin" (get-in % [1 :aria-label])))
            margin-footer-button (hiccup/find-first-node expanded-card #(and (= :button (first %))
                                                                             (contains? (hiccup/direct-texts %) "Margin")))
            expanded-strings (set (hiccup/collect-strings expanded-card))]
        (is (contains? expanded-strings "Margin"))
        (is (contains? expanded-strings "(Cross)"))
        (is (nil? margin-edit-button))
        (is (nil? margin-footer-button))))))

(deftest positions-tab-content-mobile-collapsed-row-still-renders-hoisted-margin-overlay-test
  (with-phone-viewport
    (fn []
      (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
            content (render-positions-tab-from-rows [row-data]
                                                    fixtures/default-sort-state
                                                    nil
                                                    nil
                                                    (position-margin/from-position-row {} row-data)
                                                    {:direction-filter :all})
            collapsed-card (hiccup/find-by-data-role content
                                                     (str "mobile-position-card-"
                                                          (view/position-unique-key row-data)))
            card-strings (set (hiccup/collect-strings collapsed-card))
            overlay-layer (hiccup/find-by-data-role content "position-margin-mobile-sheet-layer")
            overlay-surface (hiccup/find-first-node content
                                                    #(= "true" (get-in % [1 :data-position-margin-surface])))]
        (is (some? collapsed-card))
        (is (contains? card-strings "NVDA"))
        (is (not (contains? card-strings "Adjust Margin")))
        (is (some? overlay-layer))
        (is (some? overlay-surface))))))

(deftest positions-tab-content-mobile-renders-one-active-margin-overlay-test
  (with-phone-viewport
    (fn []
      (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
            row-id (view/position-unique-key row-data)
            content (render-positions-tab-from-rows [row-data]
                                                    fixtures/default-sort-state
                                                    nil
                                                    nil
                                                    (position-margin/from-position-row {} row-data)
                                                    {:direction-filter :all
                                                     :mobile-expanded-card {:positions row-id}})
            overlay-surfaces (hiccup/find-all-nodes content
                                                    #(= "true" (get-in % [1 :data-position-margin-surface])))
            overlay-layers (hiccup/find-all-nodes content
                                                  #(= "position-margin-mobile-sheet-layer"
                                                      (get-in % [1 :data-role])))]
        (is (= 1 (count overlay-surfaces)))
        (is (= 1 (count overlay-layers)))))))

(deftest positions-tab-content-card-layout-renders-hoisted-margin-overlay-at-tablet-width-test
  (with-viewport
    768
    1024
    (fn []
      (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
            content (render-positions-tab-from-rows [row-data]
                                                    fixtures/default-sort-state
                                                    nil
                                                    nil
                                                    (position-margin/from-position-row {} row-data)
                                                    {:direction-filter :all})
            overlay-layer (hiccup/find-by-data-role content "position-margin-mobile-sheet-layer")
            overlay-surface (hiccup/find-first-node content
                                                    #(= "true" (get-in % [1 :data-position-margin-surface])))]
        (is (nil? overlay-layer))
        (is (some? overlay-surface))
        (is (contains? (hiccup/node-class-set overlay-surface) "fixed"))))))

(deftest position-row-renders-inline-margin-modal-for-active-row-key-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        margin-modal (position-margin/from-position-row {} row-data)
        row-node (view/position-row row-data nil nil margin-modal)
        panel-node (hiccup/find-first-node
                    row-node
                    #(= "true" (get-in % [1 :data-position-margin-surface])))]
    (is (some? panel-node))
    (is (contains? (hiccup/node-class-set panel-node) "fixed"))
    (is (contains? (hiccup/node-class-set panel-node) "space-y-3"))))

(deftest position-row-margin-slider-hides-overlapping-notch-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        margin-modal (assoc (position-margin/from-position-row {} row-data)
                            :amount-percent-input "25")
        row-node (view/position-row row-data nil nil margin-modal)
        notch-nodes (hiccup/find-all-nodes
                     row-node
                     #(contains? (hiccup/node-class-set %) "order-size-slider-notch"))
        hidden-notch-count (count (filter #(contains? (hiccup/node-class-set %) "opacity-0")
                                          notch-nodes))]
    (is (seq notch-nodes))
    (is (= 1 hidden-notch-count))))

(deftest position-row-renders-inline-reduce-popover-for-active-row-key-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        popover (position-reduce/from-position-row row-data)
        row-node (view/position-row row-data nil popover)
        panel-node (hiccup/find-first-node
                    row-node
                    #(= "true" (get-in % [1 :data-position-reduce-surface])))]
    (is (some? panel-node))
    (is (contains? (hiccup/node-class-set panel-node) "fixed"))
    (is (contains? (hiccup/node-class-set panel-node) "space-y-3"))))

(deftest position-row-reduce-popover-mid-button-dispatches-mid-price-action-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        popover (assoc (position-reduce/from-position-row row-data)
                       :close-type :limit
                       :mid-price "10")
        row-node (view/position-row row-data nil popover)
        mid-button (hiccup/find-first-node
                    row-node
                    #(and (= :button (first %))
                          (contains? (hiccup/direct-texts %) "MID")))]
    (is (some? mid-button))
    (is (= [[:actions/set-position-reduce-limit-price-to-mid]]
           (get-in mid-button [1 :on :click])))
    (is (false? (boolean (get-in mid-button [1 :disabled]))))))

(deftest position-row-reduce-popover-mid-button-disabled-without-mid-price-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        popover (assoc (position-reduce/from-position-row row-data)
                       :close-type :limit
                       :mid-price nil)
        row-node (view/position-row row-data nil popover)
        mid-button (hiccup/find-first-node
                    row-node
                    #(and (= :button (first %))
                          (contains? (hiccup/direct-texts %) "MID")))]
    (is (some? mid-button))
    (is (nil? (get-in mid-button [1 :on :click])))
    (is (true? (boolean (get-in mid-button [1 :disabled]))))))

(deftest position-row-renders-inline-position-tpsl-panel-for-active-row-key-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        modal (position-tpsl/from-position-row row-data)
        row-node (view/position-row row-data modal)
        panel-node (hiccup/find-first-node
                    row-node
                    #(= "true" (get-in % [1 :data-position-tpsl-surface])))]
    (is (some? panel-node))
    (is (contains? (hiccup/node-class-set panel-node) "fixed"))
    (is (not (contains? (hiccup/node-class-set panel-node) "absolute")))
    (is (contains? (hiccup/node-class-set panel-node) "overflow-y-auto"))
    (is (not (contains? (hiccup/node-class-set panel-node) "inset-0")))))

(deftest position-row-does-not-render-inline-position-tpsl-panel-for-different-row-key-test
  (let [row-data (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
        other-row (fixtures/sample-position-row "xyz:TSLA" 10 "0.500")
        modal (position-tpsl/from-position-row other-row)
        row-node (view/position-row row-data modal)
        panel-node (hiccup/find-first-node
                    row-node
                    #(= "true" (get-in % [1 :data-position-tpsl-surface])))]
    (is (nil? panel-node))))

(deftest position-table-layout-reclaims-right-edge-space-and-truncates-long-coin-labels-test
  (let [grid-template-class "grid-cols-[minmax(180px,2.15fr)_minmax(142px,1.34fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(114px,1.06fr)_minmax(88px,0.82fr)_minmax(124px,1.08fr)_minmax(80px,0.78fr)_minmax(94px,0.8fr)_minmax(146px,1.06fr)]"
        header-node (view/position-table-header fixtures/default-sort-state)
        row-node (view/position-row (fixtures/sample-position-row "xyz:BRENTOIL" 20 "0.41"))
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
