(ns hyperopen.views.account-info.position-tpsl-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.views.account-info.position-tpsl-modal :as position-tpsl-modal]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(defn- sample-modal-view []
  (position-tpsl-modal/position-tpsl-modal-view
   (position-tpsl/from-position-row
    (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))))

(defn- first-index-of [items target]
  (first (keep-indexed (fn [idx item]
                         (when (= item target)
                           idx))
                       items)))

(deftest position-tpsl-modal-text-inputs-use-event-target-value-placeholder-vector-test
  (let [modal-view (sample-modal-view)
        text-inputs (hiccup/find-all-nodes
                     modal-view
                     #(and (= :input (first %))
                           (= "text" (get-in % [1 :type]))))
        editable-text-inputs (filter #(contains? (get-in % [1 :on]) :input) text-inputs)
        input-actions (map #(first (get-in % [1 :on :input])) editable-text-inputs)
        input-paths (set (map second input-actions))]
    (is (= 4 (count editable-text-inputs)))
    (is (= #{[:tp-price] [:tp-gain] [:sl-price] [:sl-loss]}
           input-paths))
    (doseq [action input-actions]
      (is (= :actions/set-position-tpsl-modal-field
             (first action)))
      (is (= [:event.target/value]
             (nth action 2))))))

(deftest position-tpsl-modal-keydown-and-checkboxes-use-event-placeholders-test
  (let [modal-view (sample-modal-view)
        modal-surface (hiccup/find-first-node
                       modal-view
                       #(= "true" (get-in % [1 :data-position-tpsl-surface])))
        checkbox-inputs (hiccup/find-all-nodes
                         modal-view
                         #(and (= :input (first %))
                               (= "checkbox" (get-in % [1 :type]))))]
    (is (= [[:actions/handle-position-tpsl-modal-keydown [:event/key]]]
           (get-in modal-surface [1 :on :keydown])))
    (is (= 2 (count checkbox-inputs)))
    (doseq [checkbox-node checkbox-inputs]
      (is (= [:event.target/checked]
             (last (first (get-in checkbox-node [1 :on :change]))))))))

(deftest position-tpsl-modal-configure-amount-controls-render-and-dispatch-test
  (let [modal (-> (position-tpsl/from-position-row
                   (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                  (assoc :configure-amount? true
                         :size-input "0.25"
                         :size-percent-input "50"))
        modal-view (position-tpsl-modal/position-tpsl-modal-view modal)
        slider-input (hiccup/find-first-node
                      modal-view
                      #(and (= :input (first %))
                            (= "range" (get-in % [1 :type]))))
        percent-input (hiccup/find-first-node
                       modal-view
                       #(and (= :input (first %))
                             (contains? (hiccup/node-class-set %)
                                        "order-size-percent-input")))
        size-input (hiccup/find-first-node
                    modal-view
                    #(and (= :input (first %))
                          (= "text" (get-in % [1 :type]))
                          (= [:actions/set-position-tpsl-modal-field
                              [:size-input]
                              [:event.target/value]]
                             (first (get-in % [1 :on :input])))))
        max-button (hiccup/find-first-node
                    modal-view
                    #(and (= :button (first %))
                          (contains? (hiccup/direct-texts %) "MAX")))
        modal-strings (vec (hiccup/collect-strings modal-view))
        configure-label-index (first-index-of modal-strings "Configure Amount")
        amount-label-index (first-index-of modal-strings "Amount")
        limit-price-label-index (first-index-of modal-strings "Limit Price")]
    (is (= 50 (get-in slider-input [1 :value])))
    (is (= [[:actions/set-position-tpsl-modal-field
             [:size-percent-input]
             [:event.target/value]]]
           (get-in slider-input [1 :on :input])))
    (is (= [[:actions/set-position-tpsl-modal-field
             [:size-percent-input]
             [:event.target/value]]]
           (get-in percent-input [1 :on :input])))
    (is (= [[:actions/set-position-tpsl-modal-field
             [:size-input]
             [:event.target/value]]]
           (get-in size-input [1 :on :input])))
    (is (= [[:actions/set-position-tpsl-modal-field
             [:size-percent-input]
             "100"]]
           (get-in max-button [1 :on :click])))
    (is (contains? (hiccup/node-class-set max-button) "min-w-6"))
    (is (some? configure-label-index))
    (is (some? amount-label-index))
    (is (some? limit-price-label-index))
    (is (> amount-label-index configure-label-index))
    (is (> limit-price-label-index amount-label-index))))

(deftest position-tpsl-modal-size-metric-stays-at-full-position-size-test
  (let [modal (-> (position-tpsl/from-position-row
                   (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                  (assoc :configure-amount? true
                         :size-input "0.25"
                         :size-percent-input "50"))
        modal-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view modal)))]
    (is (contains? modal-strings "0.5 NVDA"))
    (is (not (contains? modal-strings "0.25 NVDA")))))

(deftest position-tpsl-modal-gain-and-loss-inputs-default-to-zero-and-select-on-entry-test
  (let [modal-view (sample-modal-view)
        editable-text-inputs (filter #(and (= :input (first %))
                                           (= "text" (get-in % [1 :type]))
                                           (contains? (get-in % [1 :on]) :input))
                                     (hiccup/find-all-nodes modal-view vector?))
        zero-inputs (filter #(= "0" (get-in % [1 :value])) editable-text-inputs)]
    (is (= 2 (count zero-inputs)))
    (is (not-any? #(= "0.00" (get-in % [1 :value])) editable-text-inputs))
    (doseq [input-node zero-inputs]
      (is (fn? (get-in input-node [1 :on :focus])))
      (is (fn? (get-in input-node [1 :on :click])))
      (is (nil? (get-in input-node [1 :on-focus])))
      (is (nil? (get-in input-node [1 :on-click])))
      (let [classes (hiccup/node-class-set input-node)]
        (is (contains? classes "text-right"))
        (is (contains? classes "pr-[64px]"))))))

(deftest position-tpsl-modal-unit-selectors-dispatch-mode-update-actions-test
  (let [modal-view (sample-modal-view)
        gain-select (hiccup/find-first-node
                     modal-view
                     #(and (= :select (first %))
                           (= "Gain unit" (get-in % [1 :aria-label]))))
        loss-select (hiccup/find-first-node
                     modal-view
                     #(and (= :select (first %))
                           (= "Loss unit" (get-in % [1 :aria-label]))))
        option-labels (set (hiccup/collect-strings gain-select))
        tooltip-nodes (hiccup/find-all-nodes
                       modal-view
                       #(contains? (hiccup/node-class-set %) "group-hover:opacity-100"))
        modal-strings (set (hiccup/collect-strings modal-view))]
    (is (= [[:actions/set-position-tpsl-modal-field [:tp-gain-mode] [:event.target/value]]]
           (get-in gain-select [1 :on :change])))
    (is (= [[:actions/set-position-tpsl-modal-field [:sl-loss-mode] [:event.target/value]]]
           (get-in loss-select [1 :on :change])))
    (is (contains? option-labels "$"))
    (is (contains? option-labels "%(E)"))
    (is (contains? option-labels "%(P)"))
    (is (nil? (get-in gain-select [1 :title])))
    (is (= 2 (count tooltip-nodes)))
    (is (contains? modal-strings "$: profit/loss in USDC."))))

(deftest position-tpsl-modal-unit-selectors-are-borderless-and-focus-neutral-test
  (let [modal-view (sample-modal-view)
        unit-selects (hiccup/find-all-nodes
                      modal-view
                      #(and (= :select (first %))
                            (#{"Gain unit" "Loss unit"} (get-in % [1 :aria-label]))))]
    (is (= 2 (count unit-selects)))
    (doseq [select-node unit-selects]
      (let [classes (hiccup/node-class-set select-node)]
        (is (contains? classes "border-0"))
        (is (contains? classes "focus:outline-none"))
        (is (contains? classes "focus:ring-0"))
        (is (contains? classes "text-left"))
        (is (contains? classes "pr-5"))
        (is (not (contains? classes "border")))
        (is (not (contains? classes "border-base-300")))))))

(deftest position-tpsl-modal-expected-profit-swaps-between-percent-and-usdc-test
  (let [base-modal (-> (position-tpsl/from-position-row
                        (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                       (assoc :tp-price "12"))
        roe-mode-modal (position-tpsl/set-modal-field base-modal [:tp-gain-mode] :roe-percent)
        position-mode-modal (position-tpsl/set-modal-field base-modal [:tp-gain-mode] :position-percent)
        usd-mode-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view base-modal)))
        roe-mode-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view roe-mode-modal)))
        position-mode-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view position-mode-modal)))]
    (is (contains? usd-mode-strings "Expected profit:"))
    (is (contains? usd-mode-strings "1% Position | 8.33% ROE"))
    (is (contains? roe-mode-strings "1.00 USDC | 1% Position"))
    (is (contains? position-mode-strings "1.00 USDC | 8.33% ROE"))))

(deftest position-tpsl-modal-expected-loss-swaps-between-percent-and-usdc-test
  (let [base-modal (-> (position-tpsl/from-position-row
                        (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
                       (assoc :sl-price "8"))
        roe-mode-modal (position-tpsl/set-modal-field base-modal [:sl-loss-mode] :roe-percent)
        position-mode-modal (position-tpsl/set-modal-field base-modal [:sl-loss-mode] :position-percent)
        usd-mode-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view base-modal)))
        roe-mode-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view roe-mode-modal)))
        position-mode-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view position-mode-modal)))]
    (is (contains? usd-mode-strings "Expected loss:"))
    (is (contains? usd-mode-strings "1% Position | 8.33% ROE"))
    (is (contains? roe-mode-strings "1.00 USDC | 1% Position"))
    (is (contains? position-mode-strings "1.00 USDC | 8.33% ROE"))))

(deftest position-tpsl-modal-hides-expected-profit-and-loss-at-zero-test
  (let [modal (position-tpsl/from-position-row
               (fixtures/sample-position-row "xyz:NVDA" 10 "0.500"))
        modal-strings (set (hiccup/collect-strings (position-tpsl-modal/position-tpsl-modal-view modal)))]
    (is (not (contains? modal-strings "Expected profit:")))
    (is (not (contains? modal-strings "Expected loss:")))))

(deftest position-tpsl-modal-editable-inputs-use-neutral-focus-theme-test
  (let [modal-view (sample-modal-view)
        editable-text-inputs (filter #(and (= :input (first %))
                                           (= "text" (get-in % [1 :type]))
                                           (contains? (get-in % [1 :on]) :input))
                                     (hiccup/find-all-nodes modal-view vector?))]
    (is (= 4 (count editable-text-inputs)))
    (doseq [input-node editable-text-inputs]
      (let [classes (hiccup/node-class-set input-node)]
        (is (contains? classes "focus:ring-offset-0"))
        (is (contains? classes "focus:shadow-none"))
        (is (contains? classes "focus:border-[#8a96a6]"))))))
