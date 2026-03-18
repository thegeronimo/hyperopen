(ns hyperopen.views.trade.order-form-view.size-and-slider-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                   collect-input-attrs
                                                                   collect-strings
                                                                   collect-text-and-placeholders
                                                                   find-all-nodes
                                                                   find-first-node
                                                                   first-index]]
            [hyperopen.views.trade.order-form-view :as view]))

(deftest limit-mode-renders-price-before-size-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        tokens (vec (collect-text-and-placeholders view-node))
        price-index (first-index tokens "Price (USDC)")
        size-index (first-index tokens "Size")]
    (is (number? price-index))
    (is (number? size-index))
    (is (< price-index size-index))))

(deftest price-row-populates-initial-value-and-renders-clickable-mid-context-test
  (let [view-node (view/order-form-view (base-state {:type :limit :price ""}))
        strings (set (collect-strings view-node))
        price-input (find-first-node view-node
                                     (fn [node]
                                       (let [attrs (when (map? (second node)) (second node))]
                                         (and (= :input (first node))
                                             (= "Price (USDC)" (:placeholder attrs))))))
        price-attrs (second price-input)
        price-focus (get-in price-attrs [:on :focus])
        price-blur (get-in price-attrs [:on :blur])
        mid-button (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))]
                                        (and (= :button (first node))
                                             (= "Mid" (last node))
                                             (= [[:actions/set-order-price-to-mid]]
                                                (get-in attrs [:on :click]))))))]
    (is (some? price-input))
    (is (seq (:value price-attrs)))
    (is (= [[:actions/focus-order-price-input]] price-focus))
    (is (= [[:actions/blur-order-price-input]] price-blur))
    (is (contains? strings "Mid"))
    (is (some? mid-button))))

(deftest price-row-pauses-midpoint-fallback-while-input-is-focused-test
  (let [unfocused-view (view/order-form-view (base-state {:type :limit
                                                           :price ""
                                                           :price-input-focused? false}))
        focused-view (view/order-form-view (base-state {:type :limit
                                                        :price ""
                                                        :price-input-focused? true}))
        find-price-input (fn [view-node]
                           (find-first-node view-node
                                            (fn [node]
                                              (let [attrs (when (map? (second node)) (second node))]
                                                (and (= :input (first node))
                                                     (= "Price (USDC)" (:placeholder attrs)))))))
        unfocused-price-input (find-price-input unfocused-view)
        focused-price-input (find-price-input focused-view)]
    (is (some? unfocused-price-input))
    (is (some? focused-price-input))
    (is (seq (:value (second unfocused-price-input))))
    (is (= "" (:value (second focused-price-input))))))

(deftest slider-percent-input-is-editable-and-no-numeric-spinner-input-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size-percent 37}))
        percent-input (find-first-node view-node
                                       (fn [node]
                                         (let [attrs (when (map? (second node)) (second node))
                                               classes (set (:class attrs))]
                                           (and (= :input (first node))
                                                (contains? classes "order-size-percent-input")))))
        percent-input-attrs (second percent-input)
        input-attrs (collect-input-attrs view-node)]
    (is (some? percent-input))
    (is (= "text" (:type percent-input-attrs)))
    (is (= "37" (:value percent-input-attrs)))
    (is (= [[:actions/set-order-size-percent [:event.target/value]]]
           (get-in percent-input-attrs [:on :input])))
    (is (not-any? #(= "number" (:type %)) input-attrs))))

(deftest slider-renders-five-quarter-notches-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size-percent 40}))
        slider-input (find-first-node view-node
                                      (fn [node]
                                        (let [attrs (when (map? (second node)) (second node))
                                              classes (set (:class attrs))]
                                          (and (= :input (first node))
                                               (= "range" (:type attrs))
                                               (contains? classes "order-size-slider")))))
        notch-layer (find-first-node view-node
                                     (fn [node]
                                       (let [attrs (when (map? (second node)) (second node))
                                             classes (set (:class attrs))]
                                         (contains? classes "order-size-slider-notches"))))
        slider-progress (get-in slider-input [1 :style :--order-size-slider-progress])
        slider-progress-string-key (get-in slider-input [1 :style "--order-size-slider-progress"])
        notches (find-all-nodes view-node
                                (fn [node]
                                  (let [attrs (when (map? (second node)) (second node))
                                        classes (set (:class attrs))]
                                    (contains? classes "order-size-slider-notch"))))]
    (is (= 5 (count notches)))
    (is (= "40%" slider-progress))
    (is (nil? slider-progress-string-key))
    (is (contains? (set (:class (second notch-layer))) "z-30"))
    (is (contains? (set (:class (second slider-input))) "z-20"))))

(deftest slider-highlights-passed-quarter-notches-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size-percent 50}))
        notches (find-all-nodes view-node
                                (fn [node]
                                  (let [attrs (when (map? (second node)) (second node))
                                        classes (set (:class attrs))]
                                    (contains? classes "order-size-slider-notch"))))
        active-count (count (filter (fn [node]
                                      (contains? (set (:class (second node)))
                                                 "order-size-slider-notch-active"))
                                    notches))
        inactive-count (count (filter (fn [node]
                                        (contains? (set (:class (second node)))
                                                   "order-size-slider-notch-inactive"))
                                      notches))
        hidden-count (count (filter (fn [node]
                                      (contains? (set (:class (second node)))
                                                 "opacity-0"))
                                    notches))]
    (is (= 5 (count notches)))
    (is (= 3 active-count))
    (is (= 2 inactive-count))
    (is (= 1 hidden-count))))

(deftest slider-hides-overlapped-notch-within-thumb-overlap-range-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size-percent 52}))
        notches (find-all-nodes view-node
                                (fn [node]
                                  (let [attrs (when (map? (second node)) (second node))
                                        classes (set (:class attrs))]
                                    (contains? classes "order-size-slider-notch"))))
        hidden-count (count (filter (fn [node]
                                      (contains? (set (:class (second node)))
                                                 "opacity-0"))
                                    notches))]
    (is (= 1 hidden-count))))

(deftest size-row-preserves-input-value-and-resolves-quote-symbol-fallback-test
  (let [state (-> (base-state {:type :limit :price "" :size "1" :size-display "1"})
                  (assoc :active-market {:coin "BTC"
                                         :symbol "BTC-USDT"
                                         :mark 100
                                         :maxLeverage 40
                                         :market-type :perp
                                         :szDecimals 4}))
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))
        price-input (find-first-node view-node
                                     (fn [node]
                                       (let [attrs (when (map? (second node)) (second node))]
                                         (and (= :input (first node))
                                              (= "Price (USDT)" (:placeholder attrs))))))
        size-input (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))]
                                        (and (= :input (first node))
                                             (= "Size" (:placeholder attrs))))))
        size-value (:value (second size-input))]
    (is (some? price-input))
    (is (contains? strings "USDT"))
    (is (= "1" size-value))))

(deftest size-row-renders-styled-size-unit-dropdown-and-dispatches-mode-actions-test
  (let [view-node (view/order-form-view (base-state {:type :limit
                                                      :size-input-mode :quote}
                                                     {:size-unit-dropdown-open? true}))
        unit-trigger (find-first-node view-node
                                      (fn [node]
                                        (let [attrs (when (map? (second node)) (second node))]
                                          (and (= :button (first node))
                                               (= "Size unit" (:aria-label attrs))))))
        option-buttons (find-all-nodes view-node
                                       (fn [node]
                                         (and (= :button (first node))
                                              (= :actions/set-order-size-input-mode
                                                 (ffirst (get-in node [1 :on :click]))))))
        quote-option (first (filter #(some #{"USDC"} (collect-strings %)) option-buttons))
        quote-option-classes (set (get-in quote-option [1 :class]))
        click-payloads (->> option-buttons
                            (map #(get-in % [1 :on :click]))
                            set)]
    (is (some? unit-trigger))
    (is (some #{"USDC"} (collect-strings unit-trigger)))
    (is (= #{[[:actions/set-order-size-input-mode :quote]]
             [[:actions/set-order-size-input-mode :base]]}
           click-payloads))
    (is (contains? quote-option-classes "bg-[#273035]"))
    (is (contains? quote-option-classes "text-[#50D2C1]"))))

(deftest size-row-size-unit-dropdown-toggle-and-overlay-actions-test
  (let [closed-view (view/order-form-view (base-state {:type :limit}
                                                       {:size-unit-dropdown-open? false}))
        open-view (view/order-form-view (base-state {:type :limit}
                                                     {:size-unit-dropdown-open? true}))
        trigger-button (find-first-node closed-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= "Size unit" (:aria-label attrs))))))
        overlay-button (find-first-node open-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= "Close size unit menu" (:aria-label attrs))))))
        closed-listbox (find-first-node closed-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :div (first node))
                                                 (= "Size unit options" (:aria-label attrs))))))
        open-listbox (find-first-node open-view
                                      (fn [node]
                                        (let [attrs (when (map? (second node)) (second node))]
                                          (and (= :div (first node))
                                               (= "Size unit options" (:aria-label attrs))))))]
    (is (= [[:actions/toggle-size-unit-dropdown]]
           (get-in trigger-button [1 :on :click])))
    (is (= [[:actions/handle-size-unit-dropdown-keydown [:event/key]]]
           (get-in trigger-button [1 :on :keydown])))
    (is (= [[:actions/close-size-unit-dropdown]]
           (get-in overlay-button [1 :on :click])))
    (is (= "closed" (get-in closed-listbox [1 :data-ui-state])))
    (is (= true (get-in closed-listbox [1 :aria-hidden])))
    (is (= "open" (get-in open-listbox [1 :data-ui-state])))
    (is (= false (get-in open-listbox [1 :aria-hidden])))
    (is (= 1202 (get-in open-listbox [1 :style :z-index])))))

(deftest leverage-row-margin-mode-dropdown-toggle-and-selection-actions-test
  (let [closed-view (view/order-form-view (base-state {:type :limit
                                                        :margin-mode :isolated}
                                                       {:margin-mode-dropdown-open? false}))
        open-view (view/order-form-view (base-state {:type :limit
                                                     :margin-mode :isolated}
                                                    {:margin-mode-dropdown-open? true}))
        trigger-button (find-first-node closed-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= "Margin mode" (:aria-label attrs))))))
        overlay-button (find-first-node open-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= "Close margin mode menu" (:aria-label attrs))))))
        closed-listbox (find-first-node closed-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :div (first node))
                                                 (= "Margin mode options" (:aria-label attrs))))))
        open-listbox (find-first-node open-view
                                      (fn [node]
                                        (let [attrs (when (map? (second node)) (second node))]
                                          (and (= :div (first node))
                                               (= "Margin mode options" (:aria-label attrs))))))
        option-buttons (find-all-nodes open-view
                                       (fn [node]
                                         (and (= :button (first node))
                                              (= :actions/set-order-margin-mode
                                                 (ffirst (get-in node [1 :on :click]))))))
        isolated-option (first (filter #(some #{"Isolated"} (collect-strings %))
                                       option-buttons))
        isolated-option-classes (set (get-in isolated-option [1 :class]))
        click-payloads (->> option-buttons
                            (map #(get-in % [1 :on :click]))
                            set)]
    (is (some? trigger-button))
    (is (some #{"Isolated"} (collect-strings trigger-button)))
    (is (= [[:actions/toggle-margin-mode-dropdown]]
           (get-in trigger-button [1 :on :click])))
    (is (= [[:actions/handle-margin-mode-dropdown-keydown [:event/key]]]
           (get-in trigger-button [1 :on :keydown])))
    (is (= [[:actions/close-margin-mode-dropdown]]
           (get-in overlay-button [1 :on :click])))
    (is (= #{[[:actions/set-order-margin-mode :cross]]
             [[:actions/set-order-margin-mode :isolated]]}
           click-payloads))
    (is (contains? isolated-option-classes "bg-[#273035]"))
    (is (contains? isolated-option-classes "text-[#50D2C1]"))
    (is (= "closed" (get-in closed-listbox [1 :data-ui-state])))
    (is (= true (get-in closed-listbox [1 :aria-hidden])))
    (is (= "open" (get-in open-listbox [1 :data-ui-state])))
    (is (= false (get-in open-listbox [1 :aria-hidden])))
    (is (= 1202 (get-in open-listbox [1 :style :z-index])))))

(deftest leverage-row-margin-mode-renders-static-isolated-chip-when-cross-is-disallowed-test
  (let [state (assoc (base-state {:type :limit
                                  :margin-mode :cross}
                                 {:margin-mode-dropdown-open? true})
                     :active-market {:coin "xyz:NATGAS"
                                     :quote "USDC"
                                     :market-type :perp
                                     :marginMode "noCross"
                                     :onlyIsolated true})
        view-node (view/order-form-view state)
        trigger-button (find-first-node view-node
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= "Margin mode" (:aria-label attrs))))))
        option-buttons (find-all-nodes view-node
                                       (fn [node]
                                         (and (= :button (first node))
                                              (= :actions/set-order-margin-mode
                                                 (ffirst (get-in node [1 :on :click]))))))
        strings (set (collect-strings view-node))]
    (is (nil? trigger-button))
    (is (= [] option-buttons))
    (is (contains? strings "Isolated"))
    (is (not (contains? strings "Cross")))))

(deftest leverage-popover-toggle-and-confirm-actions-test
  (let [closed-view (view/order-form-view (base-state {:type :limit}
                                                       {:leverage-popover-open? false}))
        open-view (view/order-form-view (base-state {:type :limit}
                                                     {:leverage-popover-open? true
                                                      :leverage-draft 18}))
        trigger-button (find-first-node closed-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= "Adjust leverage" (:aria-label attrs))))))
        overlay-button (find-first-node open-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= "Close leverage menu" (:aria-label attrs))
                                                 (= [[:actions/close-leverage-popover]]
                                                    (get-in attrs [:on :click]))))))
        slider-input (find-first-node open-view
                                      (fn [node]
                                        (let [attrs (when (map? (second node)) (second node))
                                              classes (set (:class attrs))]
                                          (and (= :input (first node))
                                               (= "Leverage slider" (:aria-label attrs))
                                               (contains? classes "leverage-adjust-slider")))))
        leverage-input (find-first-node open-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :input (first node))
                                                 (= "Leverage value" (:aria-label attrs))))))
        closed-popover (find-first-node closed-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (= "Adjust Leverage" (:aria-label attrs)))))
        open-popover (find-first-node open-view
                                      (fn [node]
                                        (let [attrs (when (map? (second node)) (second node))]
                                          (= "Adjust Leverage" (:aria-label attrs)))))
        confirm-button (find-first-node open-view
                                        (fn [node]
                                          (let [attrs (when (map? (second node)) (second node))]
                                            (and (= :button (first node))
                                                 (= [[:actions/confirm-order-ui-leverage]]
                                                    (get-in attrs [:on :click]))
                                                 (some #{"Confirm"} (collect-strings node))))))]
    (is (= [[:actions/toggle-leverage-popover]]
           (get-in trigger-button [1 :on :click])))
    (is (= [[:actions/handle-leverage-popover-keydown [:event/key]]]
           (get-in trigger-button [1 :on :keydown])))
    (is (= [[:actions/set-order-ui-leverage-draft [:event.target/value]]]
           (get-in slider-input [1 :on :input])))
    (is (= [[:actions/set-order-ui-leverage-draft [:event.target/value]]]
           (get-in leverage-input [1 :on :input])))
    (is (= "18" (str (:value (second leverage-input)))))
    (is (= "closed" (get-in closed-popover [1 :data-ui-state])))
    (is (= true (get-in closed-popover [1 :aria-hidden])))
    (is (= "open" (get-in open-popover [1 :data-ui-state])))
    (is (= false (get-in open-popover [1 :aria-hidden])))
    (is (= 1202 (get-in open-popover [1 :style :z-index])))
    (is (some? overlay-button))
    (is (some? confirm-button))))
