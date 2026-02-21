(ns hyperopen.views.trade.order-form-view.styling-contract-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   button-node-by-label
                                                                   collect-strings
                                                                   find-all-nodes
                                                                   find-first-node
                                                                   metric-value-node-by-label]]
            [hyperopen.views.trade.order-form-view :as view]))

(deftest side-toggle-uses-hyperliquid-buy-and-sell-colors-test
  (let [buy-active-view (view/order-form-view (base-state {:side :buy}))
        sell-active-view (view/order-form-view (base-state {:side :sell}))
        buy-when-active-classes (set (get-in (button-node-by-label buy-active-view "Buy / Long") [1 :class]))
        sell-when-inactive-classes (set (get-in (button-node-by-label buy-active-view "Sell / Short") [1 :class]))
        sell-when-active-classes (set (get-in (button-node-by-label sell-active-view "Sell / Short") [1 :class]))
        buy-when-inactive-classes (set (get-in (button-node-by-label sell-active-view "Buy / Long") [1 :class]))]
    (is (contains? buy-when-active-classes "bg-[#50D2C1]"))
    (is (contains? buy-when-active-classes "text-[#0F1A1F]"))
    (is (contains? sell-when-active-classes "bg-[#ED7088]"))
    (is (contains? sell-when-active-classes "text-[#F6FEFD]"))
    (is (contains? buy-when-inactive-classes "bg-[#273035]"))
    (is (contains? sell-when-inactive-classes "bg-[#273035]"))))

(deftest market-slippage-value-uses-green-text-class-test
  (let [view-node (view/order-form-view (base-state {:type :market :side :buy :size "2.5"}))
        slippage-row (metric-value-node-by-label view-node "Slippage")
        value-span (some #(when (and (vector? %)
                                     (= :span (first %))
                                     (contains? (set (get-in % [1 :class])) "num"))
                            %)
                         (drop (if (map? (second slippage-row)) 2 1) slippage-row))
        value-classes (set (get-in value-span [1 :class]))]
    (is (some? slippage-row))
    (is (contains? value-classes "text-primary"))))

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

(deftest price-and-size-rows-render-persistent-leading-labels-and-balanced-input-padding-test
  (let [view-node (view/order-form-view (base-state {:type :limit
                                                      :price "87.996"
                                                      :size-display "1854.37"}))
        price-label (find-first-node view-node
                                     (fn [node]
                                       (let [attrs (when (map? (second node)) (second node))
                                             classes (set (:class attrs))]
                                         (and (= :span (first node))
                                              (contains? classes "order-row-input-label")
                                              (some #{"Price (USDC)"} (collect-strings node))))))
        size-label (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))
                                            classes (set (:class attrs))]
                                        (and (= :span (first node))
                                             (contains? classes "order-row-input-label")
                                             (some #{"Size"} (collect-strings node))))))
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
        price-classes (set (get-in price-input [1 :class]))
        size-classes (set (get-in size-input [1 :class]))]
    (is (some? price-label))
    (is (some? size-label))
    (is (contains? price-classes "pl-24"))
    (is (contains? size-classes "pl-24"))
    (is (contains? price-classes "pr-14"))
    (is (not (contains? price-classes "pr-20")))
    (is (contains? size-classes "pr-20"))))

(deftest toggle-checkboxes-use-green-checked-state-with-lighter-hover-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        toggle-checkboxes (find-all-nodes view-node
                                          (fn [node]
                                            (let [attrs (when (map? (second node)) (second node))
                                                  classes (set (:class attrs))]
                                              (and (= :input (first node))
                                                   (= "checkbox" (:type attrs))
                                                   (contains? classes "trade-toggle-checkbox")))))
        all-classes (map (comp set :class second) toggle-checkboxes)]
    (is (not-empty toggle-checkboxes))
    (is (every? #(contains? % "trade-toggle-checkbox") all-classes))
    (is (every? #(contains? % "focus:ring-offset-0") all-classes))
    (is (every? #(contains? % "focus:shadow-none") all-classes))))

(deftest text-inputs-use-neutral-focus-styles-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        text-inputs (find-all-nodes view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))]
                                        (and (= :input (first node))
                                             (= "text" (:type attrs))))))
        required-focus-classes #{"focus:outline-none"
                                 "hover:border-[#6f7a88]"
                                 "hover:ring-1"
                                 "hover:ring-[#6f7a88]/30"
                                 "hover:ring-offset-0"
                                 "focus:ring-1"
                                 "focus:ring-[#8a96a6]/40"
                                 "focus:ring-offset-0"
                                 "focus:shadow-none"
                                 "focus:border-[#8a96a6]"}
        text-input-class-sets (map (comp set :class second) text-inputs)]
    (is (not-empty text-inputs))
    (is (every? (fn [class-set]
                  (every? #(contains? class-set %) required-focus-classes))
                text-input-class-sets))))

(deftest tpsl-toggle-uses-explicit-text-label-binding-with-non-label-row-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        toggle-row (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))
                                            children (if attrs (drop 2 node) (drop 1 node))
                                            child-tags (into #{} (keep (fn [child]
                                                                         (when (vector? child)
                                                                           (first child))))
                                                             children)]
                                        (and (= :div (first node))
                                             (contains? child-tags :input)
                                             (contains? child-tags :label)
                                             (some #{"Take Profit / Stop Loss"} (collect-strings node))))))
        attrs (when (some? toggle-row) (second toggle-row))
        children (when (some? toggle-row)
                   (if (map? attrs) (drop 2 toggle-row) (drop 1 toggle-row)))
        checkbox-node (some #(when (and (vector? %) (= :input (first %))) %) children)
        text-label-node (some #(when (and (vector? %) (= :label (first %))) %) children)
        checkbox-id (get-in checkbox-node [1 :id])]
    (is (some? toggle-row))
    (is (= :div (first toggle-row)))
    (is (= "checkbox" (get-in checkbox-node [1 :type])))
    (is (string? checkbox-id))
    (is (= checkbox-id (get-in text-label-node [1 :for])))
    (is (= "Take Profit / Stop Loss" (last text-label-node)))))

