(ns hyperopen.views.trade.order-form-component-primitives-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]))

(defn- collect-nodes-by-tag [node tag]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          self (when (= tag (first node)) [node])]
      (into (or self [])
            (mapcat #(collect-nodes-by-tag % tag) children)))

    (seq? node)
    (mapcat #(collect-nodes-by-tag % tag) node)

    :else []))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(deftest row-toggle-builds-stable-id-and-event-binding-test
  (let [node (apply primitives/row-toggle
                    ["Enable TP" true [[:actions/toggle-tp-enabled]]])
        input-node (first (collect-nodes-by-tag node :input))
        label-node (first (collect-nodes-by-tag node :label))
        input-attrs (second input-node)
        label-attrs (second label-node)]
    (is (= "trade-toggle-enable-tp" (:id input-attrs)))
    (is (= "trade-toggle-enable-tp" (:for label-attrs)))
    (is (= {:change [[:actions/toggle-tp-enabled]]}
           (:on input-attrs)))
    (is (true? (:checked input-attrs)))))

(deftest row-toggle-honors-explicit-id-and-nil-handler-test
  (let [node (apply primitives/row-toggle
                    [nil false nil "custom-toggle-id"])
        input-node (first (collect-nodes-by-tag node :input))
        input-attrs (second input-node)]
    (is (= "custom-toggle-id" (:id input-attrs)))
    (is (nil? (:on input-attrs)))
    (is (nil? (:on-change input-attrs)))))

(deftest input-supports-function-and-map-handlers-test
  (let [input-fn-node (apply primitives/input
                             ["42" (fn [_] :ok)
                              :type "number"
                              :placeholder "Price"])
        input-fn-attrs (second input-fn-node)
        input-map-node (apply primitives/input
                              [nil {:input [[:actions/set-size]]
                                    :blur [[:actions/blur-size]]}])
        input-map-attrs (second input-map-node)]
    (is (= "number" (:type input-fn-attrs)))
    (is (= "Price" (:placeholder input-fn-attrs)))
    (is (= "42" (:value input-fn-attrs)))
    (is (fn? (:on-input input-fn-attrs)))
    (is (nil? (:on input-fn-attrs)))

    (is (= "text" (:type input-map-attrs)))
    (is (= "" (:placeholder input-map-attrs)))
    (is (= "" (:value input-map-attrs)))
    (is (= {:input [[:actions/set-size]]
            :blur [[:actions/blur-size]]}
           (:on input-map-attrs)))))

(deftest row-input-switches-padding-and-focus-bindings-test
  (let [with-accessory (apply primitives/row-input
                              ["1"
                               "Size"
                               [[:actions/set-size-display [:event.target/value]]]
                               [:span "USDC"]
                               :on-focus (fn [_] :focus)
                               :on-blur (fn [_] :blur)])
        no-accessory (apply primitives/row-input
                            ["" "Size"
                             [[:actions/set-size-display [:event.target/value]]]
                             nil])
        with-accessory-input-attrs (-> (collect-nodes-by-tag with-accessory :input)
                                       first
                                       second)
        with-accessory-wrapper-classes (->> (collect-nodes-by-tag with-accessory :div)
                                            (map second)
                                            (map :class)
                                            (filter #(and (coll? %)
                                                          (some #{"right-3"} %)))
                                            first
                                            set)
        no-accessory-input-attrs (-> (collect-nodes-by-tag no-accessory :input)
                                     first
                                     second)
        with-classes (set (:class with-accessory-input-attrs))
        no-classes (set (:class no-accessory-input-attrs))]
    (is (contains? with-classes "pr-20"))
    (is (contains? no-classes "pr-3"))
    (is (= {:input [[:actions/set-size-display [:event.target/value]]]}
           (:on with-accessory-input-attrs)))
    (is (fn? (:on-focus with-accessory-input-attrs)))
    (is (fn? (:on-blur with-accessory-input-attrs)))
    (is (contains? with-accessory-wrapper-classes "inset-y-0"))
    (is (contains? with-accessory-wrapper-classes "flex"))
    (is (contains? with-accessory-wrapper-classes "items-center"))
    (is (not (contains? with-accessory-wrapper-classes "-translate-y-1/2")))))

(deftest chip-button-active-and-disabled-branches-test
  (let [enabled-node (apply primitives/chip-button
                            ["25%"
                             false
                             :on-click [[:actions/set-order-size-percent 25]]])
        disabled-node (apply primitives/chip-button
                             ["Cross"
                              true
                              :disabled? true
                              :on-click [[:actions/noop]]])
        enabled-attrs (second enabled-node)
        disabled-attrs (second disabled-node)
        enabled-classes (set (:class enabled-attrs))
        disabled-classes (set (:class disabled-attrs))]
    (is (= {:click [[:actions/set-order-size-percent 25]]}
           (:on enabled-attrs)))
    (is (contains? enabled-classes "bg-base-200/60"))

    (is (true? (:disabled disabled-attrs)))
    (is (nil? (:on disabled-attrs)))
    (is (contains? disabled-classes "bg-base-200"))
    (is (contains? disabled-classes "text-gray-100"))))

(deftest side-button-selects-buy-sell-and-fallback-active-colors-test
  (let [buy-active-classes (-> (primitives/side-button "Buy" :buy true [[:actions/set-side :buy]])
                               second
                               :class
                               set)
        sell-active-classes (-> (primitives/side-button "Sell" :sell true [[:actions/set-side :sell]])
                                second
                                :class
                                set)
        other-active-classes (-> (primitives/side-button "Other" :other true [[:actions/noop]])
                                 second
                                 :class
                                 set)]
    (is (contains? buy-active-classes "bg-[#50D2C1]"))
    (is (contains? sell-active-classes "bg-[#ED7088]"))
    (is (contains? other-active-classes "bg-primary"))))

(deftest metric-row-default-and-custom-value-classes-test
  (let [default-node (apply primitives/metric-row ["Fees" "0.10"])
        default-classes (set (get-in default-node [3 1 :class]))
        custom-node (apply primitives/metric-row ["Slippage" "0.02%" "text-primary"])
        custom-classes (set (get-in custom-node [3 1 :class]))]
    (is (contains? default-classes "text-gray-100"))
    (is (contains? custom-classes "text-primary"))))

(deftest metric-row-tooltip-renders-dashed-label-and-tooltip-body-test
  (let [tooltip-text "Position risk is low, so there is no liquidation price for the time being. Note that increasing the position or reducing the margin will increase the risk."
        node (apply primitives/metric-row ["Liquidation Price" "N/A" nil tooltip-text])
        trigger-node (first (filter #(contains? (set (get-in % [1 :class]))
                                                "decoration-dashed")
                                    (collect-nodes-by-tag node :span)))
        trigger-classes (set (get-in trigger-node [1 :class]))
        wrapper-node (first (filter #(= 0 (get-in % [1 :tabindex]))
                                    (collect-nodes-by-tag node :div)))
        wrapper-classes (set (get-in wrapper-node [1 :class]))]
    (is (contains? trigger-classes "underline"))
    (is (contains? trigger-classes "decoration-dashed"))
    (is (= 0 (get-in wrapper-node [1 :tabindex])))
    (is (contains? wrapper-classes "group"))
    (is (contains? wrapper-classes "relative"))
    (is (contains? (set (collect-strings node)) tooltip-text))))
