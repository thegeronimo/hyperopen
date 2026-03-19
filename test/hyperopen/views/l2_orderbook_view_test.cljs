(ns hyperopen.views.l2-orderbook-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.orderbook-policy :as orderbook-policy]
            [hyperopen.websocket.trades :as ws-trades]
            [hyperopen.views.l2-orderbook-view :as view]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn- collect-all-classes [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (concat (classes-from-tag (first node))
              (class-values (:class attrs))
              (mapcat collect-all-classes children)))

    (seq? node)
    (mapcat collect-all-classes node)

    :else []))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- node-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))]
    (set (class-values (:class attrs)))))

(defn- node-attrs [node]
  (when (and (vector? node) (map? (second node)))
    (second node)))

(defn- data-role= [role]
  (fn [node]
    (= role (:data-role (node-attrs node)))))

(defn- count-nodes [node pred]
  (cond
    (vector? node)
    (let [attrs (node-attrs node)
          children (if attrs (drop 2 node) (drop 1 node))]
      (+ (if (pred node) 1 0)
         (reduce + 0 (map #(count-nodes % pred) children))))

    (seq? node)
    (reduce + 0 (map #(count-nodes % pred) node))

    :else 0))

(defn- animated-depth-bar-node? [node]
  (let [classes (node-class-set node)]
    (and (contains? classes "transition-all")
         (contains? classes "duration-300")
         (contains? classes "ease-[cubic-bezier(0.68,-0.6,0.32,1.6)]"))))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (if (map? (second node)) (drop 2 node) (drop 1 node)))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- single-render-row [side order]
  (let [snapshot (case side
                   :ask (orderbook-policy/build-render-snapshot [] [order] 1)
                   :bid (orderbook-policy/build-render-snapshot [order] [] 1))]
    (first (case side
             :ask (:desktop-asks snapshot)
             :bid (:desktop-bids snapshot)))))

(defn- responsive-layout [viewport-width]
  {:viewport-width viewport-width})

(deftest symbol-resolution-test
  (testing "market metadata takes precedence"
    (is (= "PUMP" (view/resolve-base-symbol "PUMP" {:base "PUMP"})))
    (is (= "USDC" (view/resolve-quote-symbol "PUMP" {:quote "USDC"}))))

  (testing "coin fallback works for spot and dex-perp strings"
    (is (= "PURR" (view/resolve-base-symbol "PURR/USDC" nil)))
    (is (= "USDC" (view/resolve-quote-symbol "PURR/USDC" nil)))
    (is (= "GOLD" (view/resolve-base-symbol "hyna:GOLD" nil)))
    (is (= "USDC" (view/resolve-quote-symbol "hyna:GOLD" nil)))))

(deftest quote-vs-base-size-total-test
  (let [order {:px "2.5"
               :sz "100"
               :cum-size 250
               :cum-value 625}
        base-unit :base
        quote-unit :quote]
    (testing "size conversion switches between base and quote units"
      (is (= 100 (view/order-size-for-unit order base-unit)))
      (is (= 250 (view/order-size-for-unit order quote-unit))))

    (testing "cumulative total switches between base and quote units"
      (is (= 250 (view/order-total-for-unit order base-unit)))
      (is (= 625 (view/order-total-for-unit order quote-unit))))

    (testing "formatted quote values are rounded whole numbers"
      (is (= "250" (view/format-order-size order quote-unit)))
      (is (= "625" (view/format-order-total order quote-unit))))

    (testing "formatted base values preserve raw size and cumulative precision"
      (is (= "100" (view/format-order-size order base-unit)))
      (is (= "250" (view/format-order-total order base-unit))))))

(deftest cumulative-totals-test
  (let [orders [{:px "2" :sz "3"}
                {:px "4" :sz "5"}]
        totals (view/calculate-cumulative-totals orders)]
    (is (= 2 (count totals)))
    (is (= 3 (:cum-size (first totals))))
    (is (= 6 (:cum-value (first totals))))
    (is (= 8 (:cum-size (second totals))))
    (is (= 26 (:cum-value (second totals))))))

(deftest normalize-orderbook-tab-test
  (testing "valid tabs pass through and invalid tabs fallback to orderbook"
    (is (= :orderbook (view/normalize-orderbook-tab :orderbook)))
    (is (= :trades (view/normalize-orderbook-tab :trades)))
    (is (= :trades (view/normalize-orderbook-tab "trades")))
    (is (= :orderbook (view/normalize-orderbook-tab "invalid")))
    (is (= :orderbook (view/normalize-orderbook-tab nil)))))

(deftest trade-time-formatting-test
  (testing "seconds and milliseconds normalize to the same HH:MM:SS output"
    (is (= 1700000000000 (view/trade-time->ms 1700000000)))
    (is (= 1700000000000 (view/trade-time->ms 1700000000000)))
    (let [formatted-seconds (view/format-trade-time 1700000000)
          formatted-millis (view/format-trade-time 1700000000000)]
      (is (= formatted-seconds formatted-millis))
      (is (re-matches #"\d{2}:\d{2}:\d{2}" formatted-seconds)))))

(deftest trade-side-class-test
  (testing "trade sides map to expected price classes"
    (is (= "text-green-400" (view/trade-side->price-class "B")))
    (is (= "text-red-400" (view/trade-side->price-class "A")))
    (is (= "text-red-400" (view/trade-side->price-class "S")))
    (is (= "text-gray-100" (view/trade-side->price-class "X")))))

(deftest order-row-renders-hyperliquid-neutral-size-and-total-columns-test
  (let [order {:px "101.5"
               :sz "2"
               :cum-size 2
               :cum-value 203}
        ask-row (single-render-row :ask order)
        bid-row (single-render-row :bid order)
        ask-classes (frequencies (collect-all-classes (view/order-row ask-row :base)))
        bid-classes (frequencies (collect-all-classes (view/order-row bid-row :base)))]
    (testing "ask rows keep price red while rendering size/total in Hyperliquid neutral tone"
      (is (= 1 (get ask-classes "text-[rgb(237,112,136)]" 0)))
      (is (= 2 (get ask-classes "text-[rgb(210,218,215)]" 0)))
      (is (= 0 (get ask-classes "text-white" 0))))
    (testing "bid rows keep price green while rendering size/total in Hyperliquid neutral tone"
      (is (= 1 (get bid-classes "text-[rgb(31,166,125)]" 0)))
      (is (= 2 (get bid-classes "text-[rgb(210,218,215)]" 0)))
      (is (= 0 (get bid-classes "text-white" 0))))))

(deftest order-row-uses-15pct-depth-bar-translucency-test
  (let [order {:px "101.5"
               :sz "2"
               :cum-size 2
               :cum-value 203}
        ask-row (single-render-row :ask order)
        bid-row (single-render-row :bid order)
        ask-classes (set (collect-all-classes (view/order-row ask-row :base)))
        bid-classes (set (collect-all-classes (view/order-row bid-row :base)))]
    (testing "ask depth bars use Hyperliquid red at 15pct translucency"
      (is (contains? ask-classes "bg-[rgba(237,112,136,0.15)]"))
      (is (not (contains? ask-classes "bg-[rgba(237,112,136,0.20)]")))
      (is (not (contains? ask-classes "bg-red-500/30"))))
    (testing "bid depth bars use Hyperliquid green at 15pct translucency"
      (is (contains? bid-classes "bg-[rgba(31,166,125,0.15)]"))
      (is (not (contains? bid-classes "bg-[rgba(31,166,125,0.20)]")))
      (is (not (contains? bid-classes "bg-green-500/30"))))))

(deftest orderbook-price-column-is-left-aligned-with-readable-left-inset-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}})
        header-row (find-first-node panel (data-role= "orderbook-column-headers-row"))
        level-content-row (find-first-node panel (data-role= "orderbook-level-content-row"))
        price-header-cell (find-first-node panel (data-role= "orderbook-price-header-cell"))
        size-header-cell (find-first-node panel (data-role= "orderbook-size-header-cell"))
        total-header-cell (find-first-node panel (data-role= "orderbook-total-header-cell"))
        price-level-cell (find-first-node panel (data-role= "orderbook-level-price-cell"))
        size-level-cell (find-first-node panel (data-role= "orderbook-level-size-cell"))
        total-level-cell (find-first-node panel (data-role= "orderbook-level-total-cell"))
        header-row-classes (node-class-set header-row)
        level-content-row-classes (node-class-set level-content-row)
        price-header-classes (node-class-set price-header-cell)
        size-header-classes (node-class-set size-header-cell)
        total-header-classes (node-class-set total-header-cell)
        price-level-classes (node-class-set price-level-cell)
        size-level-classes (node-class-set size-level-cell)
        total-level-classes (node-class-set total-level-cell)]
    (testing "orderbook header and level content expose stable alignment data roles"
      (is (some? header-row))
      (is (some? level-content-row))
      (is (some? price-header-cell))
      (is (some? size-header-cell))
      (is (some? total-header-cell))
      (is (some? price-level-cell))
      (is (some? size-level-cell))
      (is (some? total-level-cell)))

    (testing "price column is left aligned while size/total stay right aligned"
      (is (contains? price-header-classes "text-left"))
      (is (contains? price-level-classes "text-left"))
      (is (contains? size-header-classes "text-right"))
      (is (contains? total-header-classes "text-right"))
      (is (contains? size-level-classes "text-right"))
      (is (contains? total-level-classes "text-right")))

    (testing "orderbook rows use tighter Hyperliquid-style 1:2:2 column allocation"
      (is (contains? header-row-classes "grid-cols-[1fr_2fr_2fr]"))
      (is (contains? level-content-row-classes "grid-cols-[1fr_2fr_2fr]")))

    (testing "readable inset contract keeps small left padding without large horizontal gutters"
      (is (contains? header-row-classes "pl-2"))
      (is (contains? level-content-row-classes "pl-2"))
      (is (not (contains? header-row-classes "px-3")))
      (is (not (contains? header-row-classes "pl-3")))
      (is (not (contains? level-content-row-classes "px-3")))
      (is (not (contains? level-content-row-classes "pl-3"))))))

(deftest recent-trades-for-coin-test
  (testing "mixed coin trades are filtered to the selected coin and sorted newest first"
    (with-redefs [ws-trades/get-recent-trades
                  (fn []
                    [{:coin "ETH" :px "3010.5" :sz "0.2" :side "B" :time 1700000001}
                     {:coin "BTC" :px "61500.1" :sz "0.03" :side "A" :time 1700000003}
                     {:coin "BTC" :px "61499.9" :sz "0.01" :side "B" :time 1700000002}])]
      (let [filtered-trades (view/recent-trades-for-coin "BTC")]
        (is (= 2 (count filtered-trades)))
        (is (every? #(= "BTC" (:coin %)) filtered-trades))
        (is (>= (:time-ms (first filtered-trades))
                (:time-ms (second filtered-trades))))))))

(deftest recent-trades-for-coin-prefers-cached-slice-test
  (with-redefs [ws-trades/get-recent-trades-for-coin (fn [_]
                                                        [{:coin "BTC"
                                                          :price 61500.1
                                                          :price-raw "61500.1"
                                                          :size 0.03
                                                          :size-raw "0.03"
                                                          :side "A"
                                                          :time-ms 1700000003000
                                                          :tid "t1"}])
                ws-trades/get-recent-trades (fn []
                                              (throw (js/Error. "fallback should not be called")))]
    (let [trades (view/recent-trades-for-coin "BTC")]
      (is (= 1 (count trades)))
      (is (= "BTC" (:coin (first trades))))
      (is (= 1700000003000 (:time-ms (first trades)))))))

(deftest trades-price-column-is-left-aligned-with-readable-left-inset-test
  (with-redefs [ws-trades/get-recent-trades
                (fn []
                  [{:coin "BTC" :px "61500.1" :sz "0.03" :side "A" :time 1700000003}
                   {:coin "BTC" :px "61500.2" :sz "0.01" :side "B" :time 1700000004}])]
    (let [panel (view/trades-panel "BTC" "BTC")
          header-row (find-first-node panel (data-role= "trades-column-headers-row"))
          trade-content-row (find-first-node panel (data-role= "trades-level-content-row"))
          price-header-cell (find-first-node panel (data-role= "trades-price-header-cell"))
          size-header-cell (find-first-node panel (data-role= "trades-size-header-cell"))
          time-header-cell (find-first-node panel (data-role= "trades-time-header-cell"))
          price-level-cell (find-first-node panel (data-role= "trades-level-price-cell"))
          size-level-cell (find-first-node panel (data-role= "trades-level-size-cell"))
          time-level-cell (find-first-node panel (data-role= "trades-level-time-cell"))
          header-row-classes (node-class-set header-row)
          trade-content-row-classes (node-class-set trade-content-row)
          price-header-classes (node-class-set price-header-cell)
          size-header-classes (node-class-set size-header-cell)
          time-header-classes (node-class-set time-header-cell)
          price-level-classes (node-class-set price-level-cell)
          size-level-classes (node-class-set size-level-cell)
          time-level-classes (node-class-set time-level-cell)]
      (testing "trades header and level content expose stable alignment data roles"
        (is (some? header-row))
        (is (some? trade-content-row))
        (is (some? price-header-cell))
        (is (some? size-header-cell))
        (is (some? time-header-cell))
        (is (some? price-level-cell))
        (is (some? size-level-cell))
        (is (some? time-level-cell)))

      (testing "price column is left aligned while size/time stay right aligned"
        (is (contains? price-header-classes "text-left"))
        (is (contains? price-level-classes "text-left"))
        (is (contains? size-header-classes "text-right"))
        (is (contains? time-header-classes "text-right"))
        (is (contains? size-level-classes "text-right"))
        (is (contains? time-level-classes "text-right")))

      (testing "trades rows use tighter Hyperliquid-style 1:2:2 column allocation"
        (is (contains? header-row-classes "grid-cols-[1fr_2fr_2fr]"))
        (is (contains? trade-content-row-classes "grid-cols-[1fr_2fr_2fr]")))

      (testing "readable inset contract keeps small left padding without large horizontal gutters"
        (is (contains? header-row-classes "pl-2"))
        (is (contains? trade-content-row-classes "pl-2"))
        (is (not (contains? header-row-classes "px-3")))
        (is (not (contains? header-row-classes "pl-3")))
        (is (not (contains? trade-content-row-classes "px-3")))
        (is (not (contains? trade-content-row-classes "pl-3")))))))

(deftest orderbook-panel-uses-base-background-and-border-tokens-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}})
        classes (set (collect-all-classes panel))]
    (is (contains? classes "bg-base-100"))
    (is (contains? classes "border-base-300"))
    (is (not (contains? classes "bg-gray-900")))))

(deftest orderbook-and-trades-use-scoped-numeric-typography-utilities-test
  (let [orderbook-panel (view/l2-orderbook-panel "BTC"
                                                 {:market-type :perp
                                                  :base "BTC"
                                                  :quote "USDC"
                                                  :szDecimals 4}
                                                 {:bids [{:px "99" :sz "2"}]
                                                  :asks [{:px "101" :sz "1"}]}
                                                 {:size-unit :base
                                                  :size-unit-dropdown-visible? false
                                                  :price-aggregation-dropdown-visible? false
                                                  :price-aggregation-by-coin {"BTC" :full}})
        orderbook-classes (set (collect-all-classes orderbook-panel))
        trades-view (with-redefs [ws-trades/get-recent-trades
                                  (fn []
                                    [{:coin "BTC" :px "61500.1" :sz "0.03" :side "A" :time 1700000003}])]
                      (view/l2-orderbook-view {:coin "BTC"
                                               :market {:base "BTC" :quote "USDC"}
                                               :orderbook-ui {:active-tab :trades}}))
        trades-classes (set (collect-all-classes trades-view))]
    (is (contains? orderbook-classes "num"))
    (is (contains? orderbook-classes "orderbook-panel-aligned"))
    (is (not (contains? orderbook-classes "num-dense")))
    (is (contains? trades-classes "num-dense"))))

(deftest trades-tab-viewport-is-scrollable-with-hidden-scrollbar-test
  (with-redefs [ws-trades/get-recent-trades
                (fn []
                  [{:coin "BTC" :px "61500.1" :sz "0.03" :side "A" :time 1700000003}])]
    (let [view-node (view/l2-orderbook-view {:coin "BTC"
                                             :market {:base "BTC" :quote "USDC"}
                                             :orderbook-ui {:active-tab :trades}})
          classes (set (collect-all-classes view-node))]
      (is (contains? classes "overflow-y-auto"))
      (is (contains? classes "scrollbar-hide")))))

(deftest l2-orderbook-view-can-hide-tabs-and-force-trades-panel-test
  (with-redefs [ws-trades/get-recent-trades
                (fn []
                  [{:coin "BTC" :px "61500.1" :sz "0.03" :side "A" :time 1700000003}])]
    (let [view-node (view/l2-orderbook-view {:coin "BTC"
                                             :market {:base "BTC" :quote "USDC"}
                                             :orderbook-ui {:active-tab :orderbook}
                                             :active-tab-override :trades
                                             :show-tabs? false})
          tabs-row (find-first-node view-node (data-role= "orderbook-tabs-row"))
          trades-header-row (find-first-node view-node (data-role= "trades-column-headers-row"))
          orderbook-header-row (find-first-node view-node (data-role= "orderbook-column-headers-row"))]
      (is (nil? tabs-row))
      (is (some? trades-header-row))
      (is (nil? orderbook-header-row)))))

(deftest orderbook-and-trades-share-constrained-tab-viewport-sizing-test
  (let [required-classes #{"flex-1" "h-full" "min-h-0" "overflow-hidden" "bg-base-100"}
        orderbook-view (view/l2-orderbook-view {:coin "BTC"
                                                :market {:market-type :perp
                                                         :base "BTC"
                                                         :quote "USDC"
                                                         :szDecimals 4}
                                                :orderbook {:bids [{:px "99" :sz "2"}]
                                                            :asks [{:px "101" :sz "1"}]}
                                                :orderbook-ui {:active-tab :orderbook}})
        trades-view (with-redefs [ws-trades/get-recent-trades
                                  (fn []
                                    [{:coin "BTC" :px "61500.1" :sz "0.03" :side "A" :time 1700000003}])]
                      (view/l2-orderbook-view {:coin "BTC"
                                               :market {:base "BTC" :quote "USDC"}
                                               :orderbook-ui {:active-tab :trades}}))
        viewport-pred (fn [candidate]
                        (let [classes (node-class-set candidate)]
                          (every? classes required-classes)))
        orderbook-viewport (find-first-node orderbook-view viewport-pred)
        trades-viewport (find-first-node trades-view viewport-pred)
        orderbook-classes (node-class-set orderbook-viewport)
        trades-classes (node-class-set trades-viewport)]
    (is (some? orderbook-viewport))
      (is (some? trades-viewport))
      (is (= orderbook-classes trades-classes))
      (is (every? orderbook-classes required-classes))))

(deftest orderbook-depth-bar-transitions-are-suppressed-when-animation-is-disabled-test
  (let [view-node (view/l2-orderbook-view {:coin "BTC"
                                           :market {:base "BTC" :quote "USDC"}
                                           :orderbook {:bids [{:px "99" :sz "2"}]
                                                       :asks [{:px "101" :sz "1"}]}
                                           :orderbook-ui {:active-tab :orderbook}
                                           :trading-settings {:animate-orderbook? false}})
        animated-depth-bar-count (count-nodes view-node animated-depth-bar-node?)]
    (is (= 0 animated-depth-bar-count))))

(deftest orderbook-panel-renders-full-available-depth-instead-of-hard-capping-at-nine-test
  (let [asks (mapv (fn [idx]
                     {:px (str (+ 101 idx))
                      :sz (str (+ 1 idx))})
                   (range 12))
        bids (mapv (fn [idx]
                     {:px (str (- 99 idx))
                      :sz (str (+ 1 idx))})
                   (range 12))
        panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids bids
                                        :asks asks}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}})
        level-row-count (count-nodes panel (data-role= "orderbook-level-row"))]
    (is (= 24 level-row-count))))

(deftest orderbook-panel-renders-mobile-split-book-with-top-ten-levels-per-side-test
  (let [asks (mapv (fn [idx]
                     {:px (str (+ 101 idx))
                      :sz (str (+ 1 idx))})
                   (range 12))
        bids (mapv (fn [idx]
                     {:px (str (- 99 idx))
                      :sz (str (+ 1 idx))})
                   (range 12))
        panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids bids
                                        :asks asks}
                                       {:size-unit :quote
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}}
                                       nil
                                       true
                                       (responsive-layout 375))
        mobile-panel (find-first-node panel (data-role= "orderbook-mobile-split-panel"))
        desktop-panel (find-first-node panel (data-role= "orderbook-desktop-panel"))
        mobile-header-row (find-first-node panel (data-role= "orderbook-mobile-split-headers"))
        mobile-panel-classes (node-class-set mobile-panel)
        header-text (set (collect-strings mobile-header-row))
        mobile-row-count (count-nodes panel (data-role= "orderbook-mobile-split-row"))
        level-row-count (count-nodes panel (data-role= "orderbook-level-row"))
        bid-price-cell-count (count-nodes panel (data-role= "orderbook-mobile-bid-price-cell"))
        ask-price-cell-count (count-nodes panel (data-role= "orderbook-mobile-ask-price-cell"))]
    (is (some? mobile-panel))
    (is (nil? desktop-panel))
    (is (contains? mobile-panel-classes "lg:hidden"))
    (is (= 10 mobile-row-count))
    (is (= 0 level-row-count))
    (is (= 10 bid-price-cell-count))
    (is (= 10 ask-price-cell-count))
    (is (contains? header-text "Bid"))
    (is (contains? header-text "Ask"))
    (is (contains? header-text "Total (USDC)"))))

(deftest orderbook-panel-renders-only-desktop-ladder-at-lg-breakpoint-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}}
                                       nil
                                       true
                                       (responsive-layout 1280))
        mobile-panel (find-first-node panel (data-role= "orderbook-mobile-split-panel"))
        desktop-panel (find-first-node panel (data-role= "orderbook-desktop-panel"))
        desktop-classes (node-class-set desktop-panel)
        mobile-row-count (count-nodes panel (data-role= "orderbook-mobile-split-row"))
        level-row-count (count-nodes panel (data-role= "orderbook-level-row"))]
    (is (nil? mobile-panel))
    (is (some? desktop-panel))
    (is (contains? desktop-classes "hidden"))
    (is (contains? desktop-classes "lg:flex"))
    (is (= 0 mobile-row-count))
    (is (= 2 level-row-count))))

(deftest orderbook-panel-can-render-from-precomputed-slices-without-raw-levels-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids []
                                        :asks []
                                        :render {:display-bids [{:px "100" :sz "2" :px-num 100 :sz-num 2}]
                                                 :display-asks [{:px "101" :sz "1" :px-num 101 :sz-num 1}]
                                                 :bids-with-totals [{:px "100" :sz "2" :px-num 100 :sz-num 2 :cum-size 2 :cum-value 200}]
                                                 :asks-with-totals [{:px "101" :sz "1" :px-num 101 :sz-num 1 :cum-size 1 :cum-value 101}]
                                                 :best-bid {:px "100" :sz "2" :px-num 100 :sz-num 2}
                                                 :best-ask {:px "101" :sz "1" :px-num 101 :sz-num 1}}}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}})
        level-row-count (count-nodes panel (data-role= "orderbook-level-row"))]
    (is (= 2 level-row-count))))

(deftest orderbook-panel-consumes-fully-precomputed-render-contract-test
  (let [bid-row {:side :bid
                 :row-key "bid-custom"
                 :px "100"
                 :display {:price "bid-price-label"
                           :size {:base "bid-size-base"
                                  :quote "bid-size-quote"}
                           :total {:base "bid-total-base"
                                   :quote "bid-total-quote"}
                           :bar-width {:base "33%"
                                       :quote "44%"}}}
        ask-row {:side :ask
                 :row-key "ask-custom"
                 :px "101"
                 :display {:price "ask-price-label"
                           :size {:base "ask-size-base"
                                  :quote "ask-size-quote"}
                           :total {:base "ask-total-base"
                                   :quote "ask-total-quote"}
                           :bar-width {:base "55%"
                                       :quote "66%"}}}
        panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids []
                                        :asks []
                                        :render {:desktop-bids [bid-row]
                                                 :desktop-asks [ask-row]
                                                 :mobile-pairs [{:bid bid-row
                                                                 :ask ask-row
                                                                 :row-key "mobile-split-row-custom"}]
                                                 :best-bid {:px "100"}
                                                 :best-ask {:px "101"}
                                                 :spread {:absolute-label "spread-absolute-label"
                                                          :percentage-label "spread-percent-label"}
                                                 :max-total-by-unit {:base 10
                                                                     :quote 20}}}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}})
        panel-strings (set (collect-strings panel))]
    (is (contains? panel-strings "bid-price-label"))
    (is (contains? panel-strings "bid-total-base"))
    (is (contains? panel-strings "ask-price-label"))
    (is (contains? panel-strings "ask-total-base"))
    (is (contains? panel-strings "spread-absolute-label"))
    (is (contains? panel-strings "spread-percent-label"))))

(deftest orderbook-panel-consumes-mobile-only-render-contract-on-mobile-layout-test
  (let [bid-row {:side :bid
                 :row-key "bid-mobile-only"
                 :px "100"
                 :display {:price "mobile-bid-price"
                           :size {:base "mobile-bid-size"
                                  :quote "mobile-bid-size-quote"}
                           :total {:base "mobile-bid-total"
                                   :quote "mobile-bid-total-quote"}
                           :bar-width {:base "33%"
                                       :quote "44%"}}}
        ask-row {:side :ask
                 :row-key "ask-mobile-only"
                 :px "101"
                 :display {:price "mobile-ask-price"
                           :size {:base "mobile-ask-size"
                                  :quote "mobile-ask-size-quote"}
                           :total {:base "mobile-ask-total"
                                   :quote "mobile-ask-total-quote"}
                           :bar-width {:base "55%"
                                       :quote "66%"}}}
        panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids []
                                        :asks []
                                        :render {:mobile-pairs [{:bid bid-row
                                                                 :ask ask-row
                                                                 :row-key "mobile-pair-only"}]
                                                 :best-bid {:px "100"}
                                                 :best-ask {:px "101"}
                                                 :spread {:absolute-label "mobile-spread-absolute"
                                                          :percentage-label "mobile-spread-percent"}
                                                 :max-total-by-unit {:base 10
                                                                     :quote 20}}}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}}
                                       nil
                                       true
                                       (responsive-layout 375))
        mobile-panel (find-first-node panel (data-role= "orderbook-mobile-split-panel"))
        desktop-panel (find-first-node panel (data-role= "orderbook-desktop-panel"))
        panel-strings (set (collect-strings panel))]
    (is (some? mobile-panel))
    (is (nil? desktop-panel))
    (is (contains? panel-strings "mobile-bid-price"))
    (is (contains? panel-strings "mobile-bid-total"))
    (is (contains? panel-strings "mobile-ask-price"))
    (is (contains? panel-strings "mobile-ask-total"))))

(deftest orderbook-panel-depth-panes-use-flex-constrained-layout-contract-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}})
        depth-body (find-first-node panel (data-role= "orderbook-depth-body"))
        asks-pane (find-first-node panel (data-role= "orderbook-asks-pane"))
        bids-pane (find-first-node panel (data-role= "orderbook-bids-pane"))
        depth-body-classes (node-class-set depth-body)
        asks-pane-classes (node-class-set asks-pane)
        bids-pane-classes (node-class-set bids-pane)]
    (is (some? depth-body))
    (is (some? asks-pane))
    (is (some? bids-pane))
    (is (every? depth-body-classes #{"flex-1" "min-h-0" "flex" "flex-col"}))
    (is (every? asks-pane-classes #{"flex-1" "min-h-0" "overflow-hidden" "flex" "flex-col" "justify-end"}))
    (is (every? bids-pane-classes #{"flex-1" "min-h-0" "overflow-hidden" "flex" "flex-col"}))
    (is (contains? asks-pane-classes "gap-0.5"))
    (is (contains? bids-pane-classes "gap-0.5"))))

(deftest orderbook-panel-renders-freshness-cue-from-health-snapshot-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}}
                                       {:generated-at-ms 5000
                                        :streams {["l2Book" "BTC" nil nil nil]
                                                  {:topic "l2Book"
                                                   :status :live
                                                   :subscribed? true
                                                   :last-payload-at-ms 4880
                                                   :stale-threshold-ms 5000}}})
        cue-node (find-first-node panel (data-role= "orderbook-freshness-cue"))]
    (is (some? cue-node))
    (is (str/includes? (str/join " " (collect-strings cue-node)) "Updated 120ms ago"))))

(deftest orderbook-panel-applies-subtle-dim-when-freshness-is-delayed-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}}
                                       {:generated-at-ms 20000
                                        :streams {["l2Book" "BTC" nil nil nil]
                                                  {:topic "l2Book"
                                                   :status :delayed
                                                   :subscribed? true
                                                   :last-payload-at-ms 1000
                                                   :stale-threshold-ms 5000}}})
        depth-body (find-first-node panel (data-role= "orderbook-depth-body"))
        classes (node-class-set depth-body)]
    (is (some? depth-body))
    (is (contains? classes "opacity-90"))))

(deftest orderbook-freshness-cue-falls-back-to-unique-topic-stream-when-exact-key-missing-test
  (let [panel (view/l2-orderbook-panel "BTC"
                                       {:market-type :perp
                                        :base "BTC"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"BTC" :full}}
                                       {:generated-at-ms 5000
                                        :streams {["l2Book" nil nil nil nil]
                                                  {:topic "l2Book"
                                                   :status :live
                                                   :subscribed? true
                                                   :last-payload-at-ms 4920
                                                   :stale-threshold-ms 5000}}})
        cue-node (find-first-node panel (data-role= "orderbook-freshness-cue"))
        cue-text (str/join " " (collect-strings cue-node))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Updated 80ms ago"))))

(deftest orderbook-freshness-cue-remains-idle-when-topic-stream-selection-is-ambiguous-test
  (let [panel (view/l2-orderbook-panel "SOL"
                                       {:market-type :perp
                                        :base "SOL"
                                        :quote "USDC"
                                        :szDecimals 4}
                                       {:bids [{:px "99" :sz "2"}]
                                        :asks [{:px "101" :sz "1"}]}
                                       {:size-unit :base
                                        :size-unit-dropdown-visible? false
                                        :price-aggregation-dropdown-visible? false
                                        :price-aggregation-by-coin {"SOL" :full}}
                                       {:generated-at-ms 5000
                                        :streams {["l2Book" "BTC" nil nil nil]
                                                  {:topic "l2Book"
                                                   :status :live
                                                   :subscribed? true
                                                   :last-payload-at-ms 4990
                                                   :stale-threshold-ms 5000}
                                                  ["l2Book" "ETH" nil nil nil]
                                                  {:topic "l2Book"
                                                   :status :live
                                                   :subscribed? true
                                                   :last-payload-at-ms 4990
                                                   :stale-threshold-ms 5000}}})
        cue-node (find-first-node panel (data-role= "orderbook-freshness-cue"))
        cue-text (str/join " " (collect-strings cue-node))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Waiting for first update..."))))

(deftest l2-orderbook-view-hides-freshness-cue-by-default-test
  (let [panel (view/l2-orderbook-view {:coin "BTC"
                                       :market {:market-type :perp
                                                :base "BTC"
                                                :quote "USDC"
                                                :szDecimals 4}
                                       :orderbook {:bids [{:px "99" :sz "2"}]
                                                   :asks [{:px "101" :sz "1"}]}
                                       :orderbook-ui {:size-unit :base
                                                      :size-unit-dropdown-visible? false
                                                      :price-aggregation-dropdown-visible? false
                                                      :price-aggregation-by-coin {"BTC" :full}
                                                      :active-tab :orderbook}
                                       :websocket-health {:generated-at-ms 5000
                                                          :streams {["l2Book" "BTC" nil nil nil]
                                                                    {:topic "l2Book"
                                                                     :status :live
                                                                     :subscribed? true
                                                                     :last-payload-at-ms 4880
                                                                     :stale-threshold-ms 5000}}}})
        cue-node (find-first-node panel (data-role= "orderbook-freshness-cue"))]
    (is (nil? cue-node))))

(deftest l2-orderbook-view-renders-freshness-cue-when-enabled-test
  (let [panel (view/l2-orderbook-view {:coin "BTC"
                                       :market {:market-type :perp
                                                :base "BTC"
                                                :quote "USDC"
                                                :szDecimals 4}
                                       :orderbook {:bids [{:px "99" :sz "2"}]
                                                   :asks [{:px "101" :sz "1"}]}
                                       :show-surface-freshness-cues? true
                                       :orderbook-ui {:size-unit :base
                                                      :size-unit-dropdown-visible? false
                                                      :price-aggregation-dropdown-visible? false
                                                      :price-aggregation-by-coin {"BTC" :full}
                                                      :active-tab :orderbook}
                                       :websocket-health {:generated-at-ms 5000
                                                          :streams {["l2Book" "BTC" nil nil nil]
                                                                    {:topic "l2Book"
                                                                     :status :live
                                                                     :subscribed? true
                                                                     :last-payload-at-ms 4880
                                                                     :stale-threshold-ms 5000}}}})
        cue-node (find-first-node panel (data-role= "orderbook-freshness-cue"))
        cue-text (str/join " " (collect-strings cue-node))]
    (is (some? cue-node))
    (is (str/includes? cue-text "Updated 120ms ago"))))
