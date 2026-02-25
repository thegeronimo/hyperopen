(ns hyperopen.views.trade.order-form-component-sections-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form-component-sections :as sections]
            [hyperopen.views.trade.order-form-type-extensions :as type-extensions]))

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
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (mapcat collect-strings children))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- collect-input-placeholders [node]
  (->> (collect-nodes-by-tag node :input)
       (map second)
       (map :placeholder)
       (remove nil?)
       set))

(def ^:private entry-callbacks
  {:on-close-dropdown [[:actions/close-pro-order-type-dropdown]]
   :on-select-entry-market [[:actions/select-order-entry-mode :market]]
   :on-select-entry-limit [[:actions/select-order-entry-mode :limit]]
   :on-toggle-dropdown [[:actions/toggle-pro-order-type-dropdown]]
   :on-dropdown-keydown [[:actions/handle-pro-order-type-dropdown-keydown [:event/key]]]
   :on-select-pro-order-type (fn [order-type]
                               [[:actions/select-pro-order-type order-type]])})

(deftest entry-mode-tabs-renders-open-and-closed-dropdown-states-test
  (let [closed-node (sections/entry-mode-tabs {:entry-mode :limit
                                               :type :limit
                                               :pro-dropdown-open? false
                                               :pro-tab-label "Pro"
                                               :pro-dropdown-options [:scale :twap]
                                               :order-type-label name}
                                              entry-callbacks)
        open-node (sections/entry-mode-tabs {:entry-mode :pro
                                             :type :scale
                                             :pro-dropdown-open? true
                                             :pro-tab-label "Scale"
                                             :pro-dropdown-options [:scale :twap]
                                             :order-type-label name}
                                            entry-callbacks)
        closed-overlay-count (->> (collect-nodes-by-tag closed-node :div)
                                  (filter #(contains? (set (get-in % [1 :class])) "fixed"))
                                  count)
        open-overlays (->> (collect-nodes-by-tag open-node :div)
                           (filter #(contains? (set (get-in % [1 :class])) "fixed")))
        option-buttons (->> (collect-nodes-by-tag open-node :button)
                            (filter #(= :actions/select-pro-order-type
                                        (ffirst (get-in % [1 :on :click])))))
        selected-option-classes (set (get-in (first option-buttons) [1 :class]))
        unselected-option-classes (set (get-in (second option-buttons) [1 :class]))]
    (is (= 0 closed-overlay-count))
    (is (= 1 (count open-overlays)))
    (is (= [[:actions/close-pro-order-type-dropdown]]
           (get-in (first open-overlays) [1 :on :click])))

    (is (= 2 (count option-buttons)))
    (is (= [[:actions/select-pro-order-type :scale]]
           (get-in (first option-buttons) [1 :on :click])))
    (is (= [[:actions/select-pro-order-type :twap]]
           (get-in (second option-buttons) [1 :on :click])))
    (is (contains? selected-option-classes "bg-base-200"))
    (is (contains? unselected-option-classes "hover:bg-base-200"))))

(deftest tp-sl-panel-renders-hyperliquid-style-price-and-gain-loss-rows-test
  (let [node (sections/tp-sl-panel {:form {:tp {:trigger "3000"}
                                           :sl {:trigger "2900"}}
                                    :unit :usd
                                    :tp-offset "150"
                                    :sl-offset "75"
                                    :tp-offset-disabled? false
                                    :sl-offset-disabled? false}
                                   {:on-set-tp-trigger [[:actions/tp-trigger [:event.target/value]]]
                                    :on-set-tp-offset [[:actions/tp-offset [:event.target/value]]]
                                    :on-set-sl-trigger [[:actions/sl-trigger [:event.target/value]]]
                                    :on-set-sl-offset [[:actions/sl-offset [:event.target/value]]]
                                    :on-set-tpsl-unit [[:actions/tpsl-unit [:event.target/value]]]})
        placeholders (collect-input-placeholders node)
        labels (set (collect-strings node))
        select-node (first (collect-nodes-by-tag node :select))]
    (is (contains? placeholders "TP Price"))
    (is (contains? placeholders "Gain"))
    (is (contains? placeholders "SL Price"))
    (is (contains? placeholders "Loss"))
    (is (not (contains? labels "Enable TP")))
    (is (not (contains? labels "Enable SL")))
    (is (= [[:actions/tpsl-unit [:event.target/value]]]
           (get-in select-node [1 :on :change])))))

(deftest tif-inline-control-renders-custom-trigger-caret-and-dispatches-toggle-test
  (let [node (sections/tif-inline-control {:tif :ioc}
                                          {:dropdown-open? false
                                           :on-toggle-dropdown [[:actions/toggle-tif-dropdown]]
                                           :on-close-dropdown [[:actions/close-tif-dropdown]]
                                           :on-dropdown-keydown [[:actions/handle-tif-dropdown-keydown [:event/key]]]
                                           :on-select-tif (fn [tif]
                                                            [[:actions/close-tif-dropdown]
                                                             [:actions/update-order-form [:tif] tif]])})
        button-nodes (collect-nodes-by-tag node :button)
        trigger (first (filter #(= "Time in force" (get-in % [1 :aria-label])) button-nodes))
        chevron (first (collect-nodes-by-tag node :svg))
        menu (first (filter #(= "TIF options" (get-in % [1 :aria-label]))
                            (collect-nodes-by-tag node :div)))]
    (is (some? trigger))
    (is (contains? (set (collect-strings trigger)) "IOC"))
    (is (= [[:actions/toggle-tif-dropdown]]
           (get-in trigger [1 :on :click])))
    (is (= [[:actions/handle-tif-dropdown-keydown [:event/key]]]
           (get-in trigger [1 :on :keydown])))
    (is (contains? (set (get-in chevron [1 :class])) "rotate-0"))
    (is (true? (get-in menu [1 :aria-hidden])))))

(deftest tif-inline-control-renders-open-menu-overlay-and-option-actions-test
  (let [node (sections/tif-inline-control {:tif :gtc}
                                          {:dropdown-open? true
                                           :on-toggle-dropdown [[:actions/toggle-tif-dropdown]]
                                           :on-close-dropdown [[:actions/close-tif-dropdown]]
                                           :on-dropdown-keydown [[:actions/handle-tif-dropdown-keydown [:event/key]]]
                                           :on-select-tif (fn [tif]
                                                            [[:actions/close-tif-dropdown]
                                                             [:actions/update-order-form [:tif] tif]])})
        button-nodes (collect-nodes-by-tag node :button)
        overlay (first (filter #(= "Close TIF menu" (get-in % [1 :aria-label])) button-nodes))
        options (filter #(= "option" (get-in % [1 :role])) button-nodes)
        selected-option (first (filter #(contains? (set (collect-strings %)) "GTC") options))
        ioc-option (first (filter #(contains? (set (collect-strings %)) "IOC") options))
        chevron (first (collect-nodes-by-tag node :svg))
        menu (first (filter #(= "TIF options" (get-in % [1 :aria-label]))
                            (collect-nodes-by-tag node :div)))]
    (is (= [[:actions/close-tif-dropdown]]
           (get-in overlay [1 :on :click])))
    (is (= 3 (count options)))
    (is (= [[:actions/close-tif-dropdown]
            [:actions/update-order-form [:tif] :ioc]]
           (get-in ioc-option [1 :on :click])))
    (is (true? (get-in selected-option [1 :aria-selected])))
    (is (contains? (set (get-in selected-option [1 :class])) "text-[#F6FEFD]"))
    (is (contains? (set (get-in chevron [1 :class])) "rotate-180"))
    (is (false? (get-in menu [1 :aria-hidden])))))

(deftest section-module-delegates-to-type-extensions-test
  (with-redefs [type-extensions/render-order-type-sections
                (fn [order-type form callbacks]
                  [:delegated order-type form callbacks])
                type-extensions/supported-order-type-sections
                (fn [] #{:trigger :scale :twap})]
    (is (= [:delegated :scale {:x 1} {:on true}]
           (sections/render-order-type-sections :scale {:x 1} {:on true})))
    (is (= #{:trigger :scale :twap}
           (sections/supported-order-type-sections)))))
