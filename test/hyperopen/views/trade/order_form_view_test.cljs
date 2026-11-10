(ns hyperopen.views.trade.order-form-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form.test-support :refer [base-state
                                                                  collect-strings
                                                                  button-node-by-label
                                                                  find-first-node
                                                                  find-all-nodes
                                                                  first-index]]
            [hyperopen.views.trade.order-form-view :as view]))

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

(deftest order-form-renders-raw-portfolio-margin-mode-and-scale-post-only-test
  (doseq [[raw-abstraction expected-label]
          [[" portfolioMargin " "PM"]
           ["unifiedAccount" "Classic"]
           ["default" "Classic"]
           ["disabled" "Classic"]
           ["dexAbstraction" "Classic"]
           ["unknown" "Classic"]
           [nil "Classic"]]]
    (let [view-node (view/order-form-view
                     (assoc (base-state {:type :limit})
                            :account {:mode :unified
                                      :abstraction-raw raw-abstraction}))]
      (is (contains? (set (collect-strings view-node)) expected-label)
          (str "raw abstraction " (pr-str raw-abstraction)))))
  (let [view-node (view/order-form-view
                   (base-state {:type :scale :post-only true}))
        post-only-input (find-first-node view-node
                                         #(= "trade-toggle-post-only"
                                             (get-in % [1 :id])))]
    (is (some? post-only-input))
    (is (true? (get-in post-only-input [1 :checked])))))

(deftest order-form-renders-outcome-pick-a-side-ticket-test
  (let [state (assoc (base-state {:type :limit})
                     :active-asset "#0"
                     :active-market {:coin "#0"
                                     :quote "USDC"
                                     :market-type :outcome
                                     :outcome-shape :yes-no
                                     :szDecimals 0
                                     :outcome-sides [{:side-index 0
                                                      :side-name "Yes"
                                                      :coin "#0"
                                                      :asset-id 100000000}
                                                     {:side-index 1
                                                      :side-name "No"
                                                      :coin "#1"
                                                      :asset-id 100000001}]})
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))
        sides (find-all-nodes view-node #(= "outcome-ticket-side" (get-in % [1 :data-role])))
        no-click (get-in (second sides) [1 :on :click])]
    (is (= 2 (count sides)))
    (is (contains? strings "Pick a side"))
    (is (contains? strings "Limit price"))
    (is (contains? strings "Amount"))
    (is (contains? strings "Yes"))
    (is (contains? strings "No"))
    (is (not (contains? strings "Buy / Long")))
    (is (not (contains? strings "Sell / Short")))
    (is (not (contains? strings "Cross")))
    (is (not (contains? strings "20x")))
    (is (not (contains? strings "Classic")))
    (is (nil? (button-node-by-label view-node "Sell")) "selling is Cash out, never a tab")
    (is (= [:actions/update-order-form [:outcome-side] 1] (first no-click)))))

(deftest order-form-marks-the-selected-outcome-no-side-test
  (let [state (assoc (base-state {:type :limit
                                  :outcome-side 1})
                     :active-asset "#1"
                     :active-market {:coin "#1"
                                     :quote "USDC"
                                     :market-type :outcome
                                     :outcome-shape :yes-no
                                     :outcome-side-index 1
                                     :szDecimals 0
                                     :outcome-sides [{:side-index 0
                                                      :side-name "Yes"
                                                      :coin "#0"
                                                      :asset-id 100000000}
                                                     {:side-index 1
                                                      :side-name "No"
                                                      :coin "#1"
                                                      :asset-id 100000001}]})
        view-node (view/order-form-view state)
        [yes-side no-side] (find-all-nodes view-node #(= "outcome-ticket-side" (get-in % [1 :data-role])))
        no-classes (set (get-in no-side [1 :class]))]
    (is (true? (get-in no-side [1 :aria-pressed])))
    (is (false? (get-in yes-side [1 :aria-pressed])))
    (is (contains? no-classes "bg-ho-sell-soft"))
    (is (contains? no-classes "border-ho-border-sell"))))

(defn- grouped-world-cup-state
  [ui-overrides]
  (assoc (base-state {:type :limit
                      :outcome-option-id 189
                      :outcome-side 0}
                     ui-overrides)
         :active-asset "#1890"
         :active-market {:coin "#1890"
                         :quote "USDH"
                         :market-type :outcome
                         :outcome-kind :question
                         :question-id 32
                         :szDecimals 0
                         :question-options [{:outcome-id 172
                                             :label "Algeria"
                                             :mark 0.01
                                             :volume24h 12
                                             :openInterest 41
                                             :sides [{:side-index 0 :side-label "Yes" :coin "#1720"}
                                                     {:side-index 1 :side-label "No" :coin "#1721"}]}
                                            {:outcome-id 173
                                             :label "Argentina"
                                             :mark 0.14
                                             :volume24h 2461
                                             :openInterest 35592
                                             :sides [{:side-index 0 :side-label "Yes" :coin "#1730"}
                                                     {:side-index 1 :side-label "No" :coin "#1731"}]}
                                            {:outcome-id 189
                                             :label "France"
                                             :mark 0.18
                                             :volume24h 5776
                                             :openInterest 41448
                                             :sides [{:side-index 0 :side-label "Yes" :coin "#1890"}
                                                     {:side-index 1 :side-label "No" :coin "#1891"}]}
                                            {:outcome-id 212
                                             :label "Spain"
                                             :mark 0.17
                                             :volume24h 1791
                                             :openInterest 27056
                                             :sides [{:side-index 0 :side-label "Yes" :coin "#2120"}
                                                     {:side-index 1 :side-label "No" :coin "#2121"}]}]
                         :outcome-sides [{:side-index 0
                                          :side-label "Yes"
                                          :coin "#1890"}
                                         {:side-index 1
                                          :side-label "No"
                                          :coin "#1891"}]}))

(deftest order-form-lists-every-answer-of-a-multi-outcome-event-test
  (let [view-node (view/order-form-view (grouped-world-cup-state {}))
        trigger (find-first-node view-node
                                 (fn [node]
                                   (= "outcome-option-select-trigger"
                                      (get-in node [1 :data-role]))))
        rows (find-all-nodes view-node #(= "outcome-ticket-option" (get-in % [1 :data-role])))
        yes-buttons (find-all-nodes view-node #(= "outcome-ticket-option-yes" (get-in % [1 :data-role])))
        strings (set (collect-strings view-node))]
    (is (nil? trigger) "answers are listed, not hidden behind a dropdown")
    (is (= 4 (count rows)))
    (is (= 4 (count yes-buttons)))
    (is (every? #(= :button (first %)) yes-buttons))
    (is (contains? strings "Spain"))
    (is (contains? strings "Pick an answer"))
    (is (contains? strings "France"))
    (is (= [[:actions/select-asset "#2120"]] (take 1 (get-in (last yes-buttons) [1 :on :click]))))))

(deftest order-form-open-multi-outcome-dropdown-state-does-not-render-body-menu-test
  (let [view-node (view/order-form-view
                   (grouped-world-cup-state {:outcome-option-dropdown-open? true
                                             :outcome-option-query "sp"}))
        menu (find-first-node view-node
                              (fn [node]
                                (= "outcome-option-select-menu"
                                   (get-in node [1 :data-role]))))
        search-input (find-first-node view-node
                                      (fn [node]
                                        (= "Search outcome options"
                                           (get-in node [1 :aria-label]))))
        option-buttons (find-all-nodes view-node
                                       (fn [node]
                                         (= "outcome-option-select-row"
                                            (get-in node [1 :data-role]))))
        strings (set (collect-strings view-node))]
    (is (nil? menu))
    (is (nil? search-input))
    (is (not (contains? strings "Live Outcomes")))
    (is (= [] option-buttons))))

(deftest order-form-shows-two-answer-questions-as-answer-rows-test
  (let [state (assoc (base-state {:type :limit
                                  :outcome-option-id 142
                                  :outcome-side 0})
                     :active-asset "#1420"
                     :active-market {:coin "#1420"
                                     :quote "USDH"
                                     :market-type :outcome
                                     :outcome-kind :question
                                     :outcome-shape :pick-one
                                     :question-id 31
                                     :szDecimals 0
                                     :question-options [{:outcome-id 141
                                                         :label "San Antonio"
                                                         :sides [{:side-index 0 :side-label "Yes" :coin "#1410"}
                                                                 {:side-index 1 :side-label "No" :coin "#1411"}]}
                                                        {:outcome-id 142
                                                         :label "New York"
                                                         :sides [{:side-index 0 :side-label "Yes" :coin "#1420"}
                                                                 {:side-index 1 :side-label "No" :coin "#1421"}]}]
                                     :outcome-sides [{:side-index 0
                                                      :side-label "Yes"
                                                      :coin "#1420"}
                                                     {:side-index 1
                                                      :side-label "No"
                                                      :coin "#1421"}]})
        view-node (view/order-form-view state)
        trigger (find-first-node view-node
                                 (fn [node]
                                   (= "outcome-option-select-trigger"
                                      (get-in node [1 :data-role]))))
        rows (find-all-nodes view-node #(= "outcome-ticket-option" (get-in % [1 :data-role])))
        sides (find-all-nodes view-node #(= "outcome-ticket-side" (get-in % [1 :data-role])))
        strings (set (collect-strings view-node))]
    (is (nil? trigger))
    (is (= 2 (count rows)))
    (is (= 2 (count sides)))
    (is (contains? strings "New York"))
    (is (contains? strings "Any other answer"))
    (is (not (contains? strings "Live Outcomes")))))

(deftest leverage-row-renders-isolated-margin-label-when-selected-test
  (let [view-node (view/order-form-view (base-state {:margin-mode :isolated}))
        trigger (find-first-node view-node
                                 (fn [node]
                                   (let [attrs (when (map? (second node)) (second node))]
                                     (re-find #"^Margin mode:" (or (:aria-label attrs) "")))))
        trigger-strings (set (collect-strings trigger))]
    (is (contains? trigger-strings "Isolated"))
    (is (not (contains? trigger-strings "Cross")))))

(deftest leverage-row-forces-isolated-label-when-market-disallows-cross-test
  (let [state (assoc (base-state {:margin-mode :cross})
                     :active-market {:coin "xyz:NATGAS"
                                     :quote "USDC"
                                     :market-type :perp
                                     :marginMode "noCross"
                                     :onlyIsolated true})
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))]
    (is (contains? strings "Isolated"))
    (is (not (contains? strings "Cross")))))

(deftest leverage-popover-renders-adjust-controls-when-open-test
  (let [view-node (view/order-form-view (base-state {:type :limit}
                                                     {:leverage-popover-open? true
                                                      :leverage-draft 18}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Adjust Leverage"))
    (is (contains? strings "Maximum leverage"))
    (is (contains? strings "Max position size"))
    (is (contains? strings "Confirm"))))

(deftest submit-button-renders-before-liquidation-metrics-test
  (let [view-node (view/order-form-view (base-state {:type :limit :size "1" :price "100"}))
        tokens (vec (collect-strings view-node))
        submit-index (first-index tokens "Place Order")
        liquidation-index (first-index tokens "Liquidation Price")]
    (is (number? submit-index))
    (is (number? liquidation-index))
    (is (< submit-index liquidation-index))))

(deftest scale-preview-renders-in-footer-after-submit-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                     :size "1000"
                                                     :scale {:start "84"
                                                             :end "79"
                                                             :count 5
                                                             :skew "1.00"}}))
        tokens (vec (collect-strings view-node))
        submit-index (first-index tokens "Place Order")
        start-index (first-index tokens "Start")
        end-index (first-index tokens "End")
        order-value-index (first-index tokens "Order Value")
        margin-index (first-index tokens "Margin Required")
        fees-index (first-index tokens "Fees")]
    (is (number? submit-index))
    (is (number? start-index))
    (is (number? end-index))
    (is (number? order-value-index))
    (is (number? margin-index))
    (is (number? fees-index))
    (is (< submit-index start-index))
    (is (< start-index end-index))
    (is (< end-index order-value-index))
    (is (< order-value-index margin-index))
    (is (< margin-index fees-index))))

(deftest submit-button-uses-compact-height-test
  (let [view-node (view/order-form-view (base-state))
        submit-button (find-first-node view-node
                                       (fn [node]
                                         (let [attrs (when (map? (second node)) (second node))]
                                           (= "trade-submit-order-button"
                                              (:data-parity-id attrs)))))
        classes (set (get-in submit-button [1 :class]))]
    (is (some? submit-button))
    (is (contains? classes "h-[33px]"))
    (is (not (contains? classes "h-10")))))

(deftest order-form-panel-does-not-force-legacy-min-height-test
  (let [view-node (view/order-form-view (base-state))
        panel (find-first-node view-node
                               (fn [node]
                                 (let [attrs (when (map? (second node)) (second node))]
                                   (= "order-form" (:data-parity-id attrs)))))
        classes (set (get-in panel [1 :class]))]
    (is (some? panel))
    (is (not (contains? classes "min-h-[500px]")))
    (is (not (contains? classes "lg:min-h-[560px]")))
    (is (not (contains? classes "xl:min-h-[640px]")))))
