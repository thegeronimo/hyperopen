(ns hyperopen.views.trade.order-form-view.entry-mode-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   button-node-by-click-action
                                                                   button-node-by-label
                                                                   collect-strings
                                                                   collect-text-and-placeholders
                                                                   pro-dropdown-option-nodes
                                                                   find-all-nodes
                                                                   find-first-node]]
            [hyperopen.views.trade.order-form-view :as view]))

(deftest market-mode-tab-is-active-and-limit-pro-tabs-are-inactive-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :market :type :limit}))
        market-button (button-node-by-label view-node "Market")
        limit-button (button-node-by-label view-node "Limit")
        pro-button (button-node-by-label view-node "Pro")
        indicator (find-first-node view-node
                                   (fn [candidate]
                                     (= "entry-mode-active-indicator"
                                        (get-in candidate [1 :data-role]))))
        market-classes (set (get-in market-button [1 :class]))
        limit-classes (set (get-in limit-button [1 :class]))
        pro-classes (set (get-in pro-button [1 :class]))]
    (is (contains? market-classes "text-[#F6FEFD]"))
    (is (contains? limit-classes "text-[#949E9C]"))
    (is (contains? pro-classes "text-[#949E9C]"))
    (is (= "0%"
           (get-in indicator [1 :style :left])))
    (is (= "33.333333%"
           (get-in indicator [1 :style :width])))))

(deftest market-mode-renders-market-entry-controls-and-hides-limit-pro-fields-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :market :type :limit}))
        strings (set (collect-strings view-node))
        tokens (set (collect-text-and-placeholders view-node))]
    (is (contains? strings "Buy / Long"))
    (is (contains? strings "Sell / Short"))
    (is (contains? strings "Reduce Only"))
    (is (contains? strings "Take Profit / Stop Loss"))
    (is (contains? tokens "Size"))
    (is (not (contains? tokens "Price (USDC)")))
    (is (not (contains? strings "TIF")))
    (is (not (contains? strings "Pro Order Type")))))

(deftest market-mode-button-dispatches-select-order-entry-market-action-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :limit :type :limit}))
        market-button (button-node-by-label view-node "Market")
        market-click (get-in market-button [1 :on :click])]
    (is (= [[:actions/select-order-entry-mode :market]] market-click))))

(deftest third-tab-shows-pro-label-when-market-or-limit-mode-active-test
  (let [market-view (view/order-form-view (base-state {:entry-mode :market
                                                       :type :market
                                                       :pro-order-type-dropdown-open? false}))
        limit-view (view/order-form-view (base-state {:entry-mode :limit
                                                      :type :limit
                                                      :pro-order-type-dropdown-open? false}))
        market-pro-button (button-node-by-click-action market-view :actions/toggle-pro-order-type-dropdown)
        limit-pro-button (button-node-by-click-action limit-view :actions/toggle-pro-order-type-dropdown)]
    (is (some #{"Pro"} (collect-strings market-pro-button)))
    (is (some #{"Pro"} (collect-strings limit-pro-button)))))

(deftest third-tab-shows-selected-pro-type-label-when-pro-mode-active-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :pro
                                                      :type :scale
                                                      :pro-order-type-dropdown-open? false}))
        pro-button (button-node-by-click-action view-node :actions/toggle-pro-order-type-dropdown)
        labels (set (collect-strings pro-button))]
    (is (contains? labels "Scale"))
    (is (not (contains? labels "Pro")))))

(deftest third-tab-click-dispatches-toggle-pro-dropdown-action-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :limit :type :limit}))
        pro-button (button-node-by-click-action view-node :actions/toggle-pro-order-type-dropdown)
        pro-click (get-in pro-button [1 :on :click])]
    (is (= [[:actions/toggle-pro-order-type-dropdown]] pro-click))))

(deftest pro-dropdown-renders-only-when-open-flag-is-true-test
  (let [closed-view (view/order-form-view (base-state {:entry-mode :limit
                                                        :type :limit
                                                        :pro-order-type-dropdown-open? false}))
        open-view (view/order-form-view (base-state {:entry-mode :limit
                                                     :type :limit
                                                     :pro-order-type-dropdown-open? true}))
        closed-options (pro-dropdown-option-nodes closed-view)
        open-options (pro-dropdown-option-nodes open-view)]
    (is (= 0 (count closed-options)))
    (is (= 6 (count open-options)))))

(deftest pro-dropdown-renders-options-in-hyperliquid-order-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :limit
                                                      :type :limit
                                                      :pro-order-type-dropdown-open? true}))
        option-labels (mapv (comp first collect-strings) (pro-dropdown-option-nodes view-node))]
    (is (= ["Scale" "Stop Limit" "Stop Market" "Take Limit" "Take Market" "TWAP"]
           option-labels))))

(deftest pro-dropdown-option-dispatches-select-pro-order-type-payload-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :limit
                                                      :type :limit
                                                      :pro-order-type-dropdown-open? true}))
        first-option (first (pro-dropdown-option-nodes view-node))
        first-option-click (get-in first-option [1 :on :click])]
    (is (= [[:actions/select-pro-order-type :scale]] first-option-click))))

(deftest pro-dropdown-overlay-click-dispatches-close-action-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :limit
                                                      :type :limit
                                                      :pro-order-type-dropdown-open? true}))
        overlay (find-first-node view-node
                                 (fn [candidate]
                                   (and (= :div (first candidate))
                                        (= [[:actions/close-pro-order-type-dropdown]]
                                           (get-in candidate [1 :on :click]))
                                        (contains? (set (get-in candidate [1 :class])) "fixed"))))
        overlay-click (get-in overlay [1 :on :click])]
    (is (= [[:actions/close-pro-order-type-dropdown]] overlay-click))))

(deftest pro-dropdown-escape-key-dispatches-keydown-action-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :limit
                                                      :type :limit
                                                      :pro-order-type-dropdown-open? true}))
        pro-button (button-node-by-click-action view-node :actions/toggle-pro-order-type-dropdown)
        keydown (get-in pro-button [1 :on :keydown])]
    (is (= [[:actions/handle-pro-order-type-dropdown-keydown [:event/key]]]
           keydown))))

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

(deftest limit-mode-tif-trigger-dispatches-toggle-and-keydown-actions-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        tif-trigger (find-first-node view-node
                                     (fn [candidate]
                                       (and (= :button (first candidate))
                                            (= "Time in force"
                                               (get-in candidate [1 :aria-label])))))]
    (is (= [[:actions/toggle-tif-dropdown]]
           (get-in tif-trigger [1 :on :click])))
    (is (= [[:actions/handle-tif-dropdown-keydown [:event/key]]]
           (get-in tif-trigger [1 :on :keydown])))))

(deftest limit-mode-open-tif-dropdown-renders-overlay-and-option-actions-test
  (let [view-node (view/order-form-view (base-state {:type :limit :tif :gtc}
                                                     {:tif-dropdown-open? true}))
        overlay (find-first-node view-node
                                 (fn [candidate]
                                   (and (= :button (first candidate))
                                        (= "Close TIF menu"
                                           (get-in candidate [1 :aria-label])))))
        ioc-option (find-first-node view-node
                                    (fn [candidate]
                                      (and (= :button (first candidate))
                                           (= "option" (get-in candidate [1 :role]))
                                           (some #{"IOC"} (collect-strings candidate)))))]
    (is (= [[:actions/close-tif-dropdown]]
           (get-in overlay [1 :on :click])))
    (is (= [[:actions/close-tif-dropdown]
            [:actions/update-order-form [:tif] :ioc]]
           (get-in ioc-option [1 :on :click])))))

(deftest pro-mode-renders-advanced-controls-test
  (let [view-node (view/order-form-view (base-state {:type :stop-market}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Stop Market"))
    (is (not (contains? strings "Pro Order Type")))
    (is (contains? strings "Trigger"))))

(deftest limit-mode-renders-tpsl-toggle-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Take Profit / Stop Loss"))))

(deftest open-tpsl-panel-renders-price-and-gain-loss-rows-test
  (let [view-node (view/order-form-view (base-state {:type :limit
                                                      :price "100"
                                                      :size "1"}
                                                     {:tpsl-panel-open? true}))
        tokens (set (collect-text-and-placeholders view-node))
        strings (set (collect-strings view-node))]
    (is (contains? tokens "TP Price"))
    (is (contains? tokens "Gain"))
    (is (contains? tokens "SL Price"))
    (is (contains? tokens "Loss"))
    (is (not (contains? strings "Enable TP")))
    (is (not (contains? strings "Enable SL")))))

(deftest open-tpsl-panel-allows-gain-loss-input-before-price-and-size-test
  (let [view-node (view/order-form-view (base-state {:type :limit
                                                      :side :buy
                                                      :price ""
                                                      :size ""
                                                      :tp {:enabled? false
                                                           :trigger ""
                                                           :offset-input "12"
                                                           :is-market true
                                                           :limit ""}
                                                      :sl {:enabled? false
                                                           :trigger ""
                                                           :offset-input "7"
                                                           :is-market true
                                                           :limit ""}}
                                                     {:tpsl-panel-open? true}))
        gain-input (find-first-node view-node
                                    (fn [candidate]
                                      (and (= :input (first candidate))
                                           (= "Gain" (get-in candidate [1 :aria-label])))))
        loss-input (find-first-node view-node
                                    (fn [candidate]
                                      (and (= :input (first candidate))
                                           (= "Loss" (get-in candidate [1 :aria-label])))))]
    (is (= "12" (get-in gain-input [1 :value])))
    (is (= "7" (get-in loss-input [1 :value])))
    (is (false? (boolean (get-in gain-input [1 :disabled]))))
    (is (false? (boolean (get-in loss-input [1 :disabled]))))))

(deftest open-tpsl-panel-preserves-raw-gain-loss-offset-input-text-test
  (let [view-node (view/order-form-view (base-state {:type :limit
                                                      :side :buy
                                                      :price "0.62095"
                                                      :size "102"
                                                      :ui-leverage 20
                                                      :tp {:enabled? true
                                                           :trigger "0.63075"
                                                           :offset-input "1"
                                                           :is-market true
                                                           :limit ""}
                                                      :sl {:enabled? true
                                                           :trigger "0.61115"
                                                           :offset-input "1"
                                                           :is-market true
                                                           :limit ""}}
                                                     {:tpsl-panel-open? true}))
        gain-input (find-first-node view-node
                                    (fn [candidate]
                                      (and (= :input (first candidate))
                                           (= "Gain" (get-in candidate [1 :aria-label])))))
        loss-input (find-first-node view-node
                                    (fn [candidate]
                                      (and (= :input (first candidate))
                                           (= "Loss" (get-in candidate [1 :aria-label])))))]
    (is (= "1" (get-in gain-input [1 :value])))
    (is (= "1" (get-in loss-input [1 :value])))))

(deftest open-tpsl-panel-renders-custom-unit-dropdown-trigger-without-native-select-test
  (let [view-node (view/order-form-view (base-state {:type :limit
                                                      :price "100"
                                                      :size "1"}
                                                     {:tpsl-panel-open? true}))
        unit-triggers (find-all-nodes view-node
                                      (fn [candidate]
                                        (and (= :button (first candidate))
                                             (= "TP/SL gain-loss unit"
                                                (get-in candidate [1 :aria-label])))))
        native-selects (find-all-nodes view-node
                                       (fn [candidate]
                                         (= :select (first candidate))))]
    (is (= 2 (count unit-triggers)))
    (is (every? #(= [[:actions/toggle-tpsl-unit-dropdown]]
                    (get-in % [1 :on :click]))
                unit-triggers))
    (is (every? #(= [[:actions/handle-tpsl-unit-dropdown-keydown [:event/key]]]
                    (get-in % [1 :on :keydown]))
                unit-triggers))
    (is (empty? native-selects))))
