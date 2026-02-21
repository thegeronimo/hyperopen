(ns hyperopen.views.trade.order-form.test-support
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading]))

(defn collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn collect-text-and-placeholders [node]
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

(defn first-index [items target]
  (first (keep-indexed (fn [idx item]
                         (when (= item target) idx))
                       items)))

(defn find-first-node [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn find-all-nodes [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          self (if (pred node) [node] [])]
      (into self (mapcat #(find-all-nodes % pred) children)))

    (seq? node)
    (mapcat #(find-all-nodes % pred) node)

    :else []))

(defn button-node-by-label [node label]
  (find-first-node node
                   (fn [candidate]
                     (and (= :button (first candidate))
                          (some #{label} (collect-strings candidate))))))

(defn button-node-by-click-action [node action]
  (find-first-node node
                   (fn [candidate]
                     (and (= :button (first candidate))
                          (= action (ffirst (get-in candidate [1 :on :click])))))))

(defn pro-dropdown-option-nodes [node]
  (find-all-nodes node
                  (fn [candidate]
                    (and (= :button (first candidate))
                         (= :actions/select-pro-order-type
                            (ffirst (get-in candidate [1 :on :click])))))))

(defn metric-value-node-by-label [node label]
  (find-first-node node
                   (fn [candidate]
                     (and (= :div (first candidate))
                          (some #(= label %) (collect-strings candidate))
                          (some #(and (vector? %)
                                      (= :span (first %))
                                      (contains? (set (get-in % [1 :class]))
                                                 "num"))
                                (drop (if (map? (second candidate)) 2 1) candidate))))))

(defn metric-value-text [node label]
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

(defn preview-leading-size [text]
  (js/parseFloat (or (first (str/split (or text "") #" " 2))
                     "")))

(defn collect-input-attrs [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          self (if (= :input (first node)) [attrs] [])]
      (into self (mapcat collect-input-attrs children)))

    (seq? node)
    (mapcat collect-input-attrs node)

    :else []))

(def liquidation-price-tooltip-text
  "Position risk is low, so there is no liquidation price for the time being. Note that increasing the position or reducing the margin will increase the risk.")

(def ^:private order-form-ui-keys
  #{:entry-mode
    :ui-leverage
    :size-input-mode
    :size-input-source
    :size-display
    :size-unit-dropdown-open?
    :pro-order-type-dropdown-open?
    :price-input-focused?
    :tpsl-panel-open?})

(defn base-state
  ([] (base-state {} {}))
  ([order-form-overrides]
   (base-state order-form-overrides {}))
  ([order-form-overrides order-form-ui-overrides]
   (let [ui-overrides-from-form (select-keys order-form-overrides order-form-ui-keys)
         normalized-order-form-overrides (reduce dissoc order-form-overrides order-form-ui-keys)
         merged-form (merge (trading/default-order-form) normalized-order-form-overrides)
         inferred-entry-mode (when (contains? normalized-order-form-overrides :type)
                               (trading/entry-mode-for-type (:type merged-form)))
         final-entry-mode (or (:entry-mode order-form-ui-overrides)
                              (:entry-mode ui-overrides-from-form)
                              inferred-entry-mode)
         order-form-ui (cond-> (merge (trading/default-order-form-ui)
                                      ui-overrides-from-form
                                      order-form-ui-overrides)
                         final-entry-mode
                         (assoc :entry-mode final-entry-mode))]
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
      :order-form merged-form
      :order-form-ui order-form-ui})))
