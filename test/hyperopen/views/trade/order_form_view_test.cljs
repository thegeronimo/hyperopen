(ns hyperopen.views.trade.order-form-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
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

(declare find-first-node find-all-nodes)

(defn- button-node-by-label [node label]
  (find-first-node node
                   (fn [candidate]
                     (and (= :button (first candidate))
                          (some #{label} (collect-strings candidate))))))

(defn- button-node-by-click-action [node action]
  (find-first-node node
                   (fn [candidate]
                     (and (= :button (first candidate))
                          (= action (ffirst (get-in candidate [1 :on :click])))))))

(defn- pro-dropdown-option-nodes [node]
  (find-all-nodes node
                  (fn [candidate]
                    (and (= :button (first candidate))
                         (= :actions/select-pro-order-type
                            (ffirst (get-in candidate [1 :on :click])))))))

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

(defn- find-all-nodes [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          self (if (pred node) [node] [])]
      (into self (mapcat #(find-all-nodes % pred) children)))

    (seq? node)
    (mapcat #(find-all-nodes % pred) node)

    :else []))

(defn- metric-value-node-by-label [node label]
  (find-first-node node
                   (fn [candidate]
                     (and (= :div (first candidate))
                          (some #(= label %) (collect-strings candidate))
                          (some #(and (vector? %)
                                      (= :span (first %))
                                      (contains? (set (get-in % [1 :class]))
                                                 "num"))
                                (drop (if (map? (second candidate)) 2 1) candidate))))))

(defn- metric-value-text [node label]
  (let [row (metric-value-node-by-label node label)
        attrs (when (map? (second row)) (second row))
        children (if attrs (drop 2 row) (drop 1 row))
        value-span (some #(when (and (vector? %)
                                     (= :span (first %))
                                     (contains? (set (get-in % [1 :class]))
                                                "num"))
                            %)
                         children)]
    (first (collect-strings value-span))))

(defn- preview-leading-size [text]
  (js/parseFloat (or (first (str/split (or text "") #" " 2))
                     "")))

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
   (let [merged-form (merge (trading/default-order-form) order-form-overrides)
         order-form (if (and (contains? order-form-overrides :type)
                             (not (contains? order-form-overrides :entry-mode)))
                      (assoc merged-form :entry-mode (trading/entry-mode-for-type (:type merged-form)))
                      merged-form)]
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
      :order-form order-form})))

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

(deftest market-mode-tab-is-active-and-limit-pro-tabs-are-inactive-test
  (let [view-node (view/order-form-view (base-state {:entry-mode :market :type :limit}))
        market-button (button-node-by-label view-node "Market")
        limit-button (button-node-by-label view-node "Limit")
        pro-button (button-node-by-label view-node "Pro")
        market-classes (set (get-in market-button [1 :class]))
        limit-classes (set (get-in limit-button [1 :class]))
        pro-classes (set (get-in pro-button [1 :class]))]
    (is (contains? market-classes "border-primary"))
    (is (not (contains? limit-classes "border-primary")))
    (is (not (contains? pro-classes "border-primary")))))

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

(deftest market-slippage-row-renders-estimate-with-4dp-and-max-with-2dp-test
  (let [state (-> (base-state {:type :market
                               :side :buy
                               :size "2.5"})
                  (assoc :orderbooks {"BTC" {:bids [{:px "99" :sz "2"}
                                                    {:px "100" :sz "2"}]
                                             :asks [{:px "102" :sz "1"}
                                                    {:price "101" :size "2"}
                                                    {:p "103" :s "5"}]}}))
        view-node (view/order-form-view state)
        strings (set (collect-strings view-node))]
    (is (contains? strings "Slippage"))
    (is (contains? strings "Est 0.6965% / Max 8.00%"))))

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

(deftest price-row-populates-initial-value-and-renders-clickable-mid-context-test
  (let [view-node (view/order-form-view (base-state {:type :limit :price ""}))
        strings (set (collect-strings view-node))
        price-input (find-first-node view-node
                                     (fn [node]
                                       (let [attrs (when (map? (second node)) (second node))]
                                         (and (= :input (first node))
                                              (= "Price (USDC)" (:placeholder attrs))))))
        price-attrs (second price-input)
        mid-button (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))]
                                        (and (= :button (first node))
                                             (= "Mid" (last node))
                                             (= [[:actions/set-order-price-to-mid]]
                                                (get-in attrs [:on :click]))))))]
    (is (some? price-input))
    (is (seq (:value price-attrs)))
    (is (contains? strings "Mid"))
    (is (some? mid-button))))

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
        slider-progress (get-in slider-input [1 :style "--order-size-slider-progress"])
        notches (find-all-nodes view-node
                                (fn [node]
                                  (let [attrs (when (map? (second node)) (second node))
                                        classes (set (:class attrs))]
                                    (contains? classes "order-size-slider-notch"))))]
    (is (= 5 (count notches)))
    (is (= "40%" slider-progress))
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

(deftest pro-mode-renders-advanced-controls-test
  (let [view-node (view/order-form-view (base-state {:type :stop-market}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Stop Market"))
    (is (not (contains? strings "Pro Order Type")))
    (is (contains? strings "Trigger"))))

(deftest scale-mode-renders-total-orders-and-size-skew-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        tokens (set (collect-text-and-placeholders view-node))]
    (is (contains? tokens "Total Orders"))
    (is (contains? tokens "Size Skew"))
    (is (not (contains? tokens "Order count")))))

(deftest scale-mode-renders-start-and-end-preview-rows-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "13.8"
                                                      :scale {:start "80"
                                                              :end "60"
                                                              :count 20
                                                              :skew "1.00"}}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Start"))
    (is (contains? strings "End"))))

(deftest scale-preview-rows-render-formatted-boundary-values-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "13.8"
                                                      :scale {:start "80"
                                                              :end "60"
                                                              :count 20
                                                              :skew "1.00"}}))]
    (is (= "0.6899 BTC @ 80.00 USDC" (metric-value-text view-node "Start")))
    (is (= "0.6899 BTC @ 60.00 USDC" (metric-value-text view-node "End")))))

(deftest scale-preview-rows-render-na-when-inputs-are-incomplete-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "13.8"
                                                      :scale {:start ""
                                                              :end "60"
                                                              :count 20
                                                              :skew "1.00"}}))]
    (is (= "N/A" (metric-value-text view-node "Start")))
    (is (= "N/A" (metric-value-text view-node "End")))))

(deftest scale-preview-rows-use-active-asset-as-canonical-base-symbol-fallback-test
  (let [state (-> (base-state {:type :scale
                               :size "13.8"
                               :scale {:start "80"
                                       :end "60"
                                       :count 20
                                       :skew "1.00"}})
                  (assoc :active-asset "SOL")
                  (assoc :active-market {:quote "USDC"
                                         :mark 100
                                         :maxLeverage 40
                                         :market-type :perp
                                         :szDecimals 4}))
        view-node (view/order-form-view state)]
    (is (= "0.6899 SOL @ 80.00 USDC" (metric-value-text view-node "Start")))
    (is (= "0.6899 SOL @ 60.00 USDC" (metric-value-text view-node "End")))))

(deftest scale-preview-values-follow-linear-ramp-ratio-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "9.45"
                                                      :scale {:start "80"
                                                              :end "70"
                                                              :count 20
                                                              :skew "2"}}))
        start-size (preview-leading-size (metric-value-text view-node "Start"))
        end-size (preview-leading-size (metric-value-text view-node "End"))]
    (is (number? start-size))
    (is (number? end-size))
    (is (<= (js/Math.abs (- 2 (/ end-size start-size))) 0.01))))

(deftest scale-preview-high-skew-front-size-near-zero-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "9.45"
                                                      :scale {:start "80"
                                                              :end "70"
                                                              :count 20
                                                              :skew "50"}}))
        start-size (preview-leading-size (metric-value-text view-node "Start"))
        end-size (preview-leading-size (metric-value-text view-node "End"))]
    (is (< start-size 0.03))
    (is (> end-size 0.9))))

(deftest scale-preview-low-skew-back-size-smaller-test
  (let [view-node (view/order-form-view (base-state {:type :scale
                                                      :size "9.45"
                                                      :scale {:start "80"
                                                              :end "70"
                                                              :count 20
                                                              :skew "0.25"}}))
        start-size (preview-leading-size (metric-value-text view-node "Start"))
        end-size (preview-leading-size (metric-value-text view-node "End"))]
    (is (> start-size 0.7))
    (is (< end-size 0.2))))

(deftest size-skew-input-dispatch-path-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        skew-input (find-first-node view-node
                                    (fn [node]
                                      (let [attrs (when (map? (second node)) (second node))]
                                        (and (= :input (first node))
                                             (= "Size Skew" (:aria-label attrs))))))
        skew-input-on (get-in skew-input [1 :on :input])
        skew-input-classes (set (get-in skew-input [1 :class]))]
    (is (some? skew-input))
    (is (contains? skew-input-classes "text-right"))
    (is (= [[:actions/update-order-form [:scale :skew] [:event.target/value]]]
           skew-input-on))))

(deftest scale-mode-hides-tpsl-toggle-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        strings (set (collect-strings view-node))]
    (is (not (contains? strings "Take Profit / Stop Loss")))))

(deftest scale-mode-hides-liquidation-price-metric-row-test
  (let [view-node (view/order-form-view (base-state {:type :scale}))
        strings (set (collect-strings view-node))]
    (is (not (contains? strings "Liquidation Price")))
    (is (contains? strings "Order Value"))
    (is (contains? strings "Margin Required"))
    (is (contains? strings "Fees"))))

(deftest limit-mode-renders-tpsl-toggle-test
  (let [view-node (view/order-form-view (base-state {:type :limit}))
        strings (set (collect-strings view-node))]
    (is (contains? strings "Take Profit / Stop Loss"))))

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
