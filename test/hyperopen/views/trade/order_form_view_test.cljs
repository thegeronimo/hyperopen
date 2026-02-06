(ns hyperopen.views.trade.order-form-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.views.trade.order-form-view :as view]))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- collect-text-and-placeholders [node]
  (cond
    (string? node) [node]

    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          placeholder (when (string? (:placeholder attrs))
                        [(:placeholder attrs)])]
      (concat placeholder (mapcat collect-text-and-placeholders children)))

    (seq? node)
    (mapcat collect-text-and-placeholders node)

    :else []))

(defn- first-index [items target]
  (first (keep-indexed (fn [idx item]
                         (when (= item target) idx))
                       items)))

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

(defn- collect-input-attrs [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          self (if (= :input (first node)) [attrs] [])]
      (into self (mapcat collect-input-attrs children)))

    (seq? node)
    (mapcat collect-input-attrs node)

    :else []))

(defn- base-state
  ([] (base-state {}))
  ([order-form-overrides]
   {:active-asset "BTC"
    :active-market {:coin "BTC"
                    :quote "USDC"
                    :mark 100
                    :maxLeverage 40
                    :market-type :perp
                    :szDecimals 4}
    :orderbooks {"BTC" {:bids [{:px "99"}]
                        :asks [{:px "101"}]}}
    :webdata2 {:clearinghouseState {:marginSummary {:accountValue "1000"
                                                    :totalMarginUsed "250"}}}
    :order-form (merge (trading/default-order-form) order-form-overrides)}))

(deftest order-form-parity-controls-render-test
  (let [view-node (view/order-form-view (base-state))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Cross"))
    (is (contains? strings "20x"))
    (is (contains? strings "Classic"))
    (is (contains? strings "Market"))
    (is (contains? strings "Limit"))
    (is (contains? strings "Pro"))
    (is (contains? strings "Buy / Long"))
    (is (contains? strings "Sell / Short"))))

(deftest limit-mode-renders-price-before-size-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        tokens (vec (collect-text-and-placeholders view-node))
        price-index (first-index tokens "Price (USDC)")
        size-index (first-index tokens "Size")]
    (is (number? price-index))
    (is (number? size-index))
    (is (< price-index size-index))))

(deftest limit-mode-renders-inline-tif-and-removes-quick-chips-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "TIF"))
    (is (contains? strings "GTC"))
    (is (not (contains? strings "Time In Force")))
    (is (not (contains? strings "25%")))
    (is (not (contains? strings "50%")))
    (is (not (contains? strings "75%")))
    (is (not (contains? strings "100%")))))

(deftest slippage-is-hidden-for-limit-and-shown-for-market-test
  (let [limit-view (view/order-form-view (base-state {:type :limit}))
        limit-strings (set (collect-strings limit-view))
        market-view (view/order-form-view (base-state {:type :market}))
        market-strings (set (collect-strings market-view))]
    (is (not (contains? limit-strings "Slippage")))
    (is (contains? market-strings "Slippage"))))

(deftest price-row-populates-field-value-and-hides-mid-reference-accessory-test
  (let [view-node (view/order-form-view (base-state {:type :limit :price ""}))
        strings (set (collect-strings view-node))
        price-input (find-first-node view-node
                                     (fn [node]
                                       (let [attrs (when (map? (second node)) (second node))]
                                         (and (= :input (first node))
                                              (= "Price (USDC)" (:placeholder attrs))))))
        price-attrs (second price-input)]
    (is (some? price-input))
    (is (seq (:value price-attrs)))
    (is (not (contains? strings "Mid")))
    (is (not (contains? strings "Ref")))))

(deftest slider-percent-badge-is-visible-without-numeric-spinner-input-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size-percent 37}))
        strings (collect-strings view-node)
        input-attrs (collect-input-attrs view-node)]
    (is (some #(re-find #"\d+\s%" %) strings))
    (is (not-any? #(= "number" (:type %)) input-attrs))))

(deftest price-and-size-rows-use-single-field-surface-test
  (let [view-node (view/order-form-view (base-state {:type :limit :price ""}))
        price-input (find-first-node view-node
                                     (fn [node]
                                       (let [attrs (when (map? (second node)) (second node))]
                                         (and (= :input (first node))
                                              (= "Price (USDC)" (:placeholder attrs))))))
        size-input (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))]
                                        (and (= :input (first node))
                                             (= "Size" (:placeholder attrs))))))
        price-class (set (:class (second price-input)))
        size-class (set (:class (second size-input)))]
    (is (contains? price-class "border"))
    (is (contains? size-class "border"))
    (is (not (contains? price-class "bg-transparent")))
    (is (not (contains? size-class "bg-transparent")))))

(deftest pro-mode-renders-advanced-controls-test
  (let [view-node (view/order-form-view (base-state {:type :stop-market}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Pro Order Type"))
    (is (contains? strings "Stop Market"))
    (is (contains? strings "Trigger"))))

(deftest order-summary-and-position-fallback-render-test
  (let [state (assoc (base-state)
                     :orderbooks {}
                     :webdata2 {}
                     :active-market {:coin "BTC" :quote "USDC" :market-type :perp}
                     :order-form (merge (trading/default-order-form) {:type :limit :price "" :size ""}))
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))]
    (is (contains? strings "Available to Trade"))
    (is (contains? strings "Current position"))
    (is (contains? strings "Liquidation Price"))
    (is (contains? strings "Order Value"))
    (is (contains? strings "Margin Required"))
    (is (contains? strings "Fees"))
    (is (contains? strings "N/A"))))
