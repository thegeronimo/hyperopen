(ns hyperopen.views.l2-orderbook-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
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

(deftest order-row-renders-white-size-and-total-columns-test
  (let [order {:px "101.5"
               :sz "2"
               :cum-size 2
               :cum-value 203}
        ask-classes (frequencies (collect-all-classes (view/order-row order 3 true :base)))
        bid-classes (frequencies (collect-all-classes (view/order-row order 3 false :base)))]
    (testing "ask rows keep price red while rendering size/total as white"
      (is (= 1 (get ask-classes "text-red-400" 0)))
      (is (= 2 (get ask-classes "text-white" 0))))
    (testing "bid rows keep price green while rendering size/total as white"
      (is (= 1 (get bid-classes "text-green-400" 0)))
      (is (= 2 (get bid-classes "text-white" 0))))))

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
    (is (every? bids-pane-classes #{"flex-1" "min-h-0" "overflow-hidden" "flex" "flex-col"}))))
