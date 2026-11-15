(ns hyperopen.views.portfolio.optimize.execution-amend-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.execution-order-table :as order-table]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node
  [node pred]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-first-node % pred) (node-children node)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-text
  [node]
  (apply str (collect-strings node)))

(defn- working-row
  ([] (working-row {:amendable? true
                    :oid "777"
                    :remaining-size 300.5
                    :limit-px 0.045
                    :live-mark 0.0465
                    :order-type :limit
                    :limit-bps 0}))
  ([amend]
   (cond-> {:row-id "perp:ZETA"
            :instrument-id "perp:ZETA"
            :instrument-type :perp
            :instrument-label "ZETA"
            :side :buy
            :quantity 681.7
            :delta-notional-usd 31.36
            :order-type :passive
            :status :resting
            :order-no 1}
     (some? amend) (assoc :amend amend))))

(defn- table
  [model rows]
  (order-table/order-table (merge {:phase :resting
                                   :order-filter :all
                                   :overrides {}
                                   :params {}}
                                  model)
                           rows))

(deftest working-row-is-clickable-when-amendable-test
  (let [row-node (node-by-role (table {} [(working-row)])
                               "portfolio-optimizer-execution-order-row-perp-ZETA")]
    (is (= [[:actions/toggle-portfolio-optimizer-execution-row "perp:ZETA"]]
           (get-in row-node [1 :on :click]))))
  (testing "no amend affordance (spectate / gone from book) — row stays inert"
    (let [row-node (node-by-role (table {} [(working-row nil)])
                                 "portfolio-optimizer-execution-order-row-perp-ZETA")]
      (is (nil? (get-in row-node [1 :on :click]))))))

(deftest amend-editor-renders-and-commits-test
  (let [rendered (table {:open-row "perp:ZETA"} [(working-row)])
        editor (node-by-role rendered
                             "portfolio-optimizer-execution-order-amend-perp-ZETA")
        commit (node-by-role rendered
                             "portfolio-optimizer-execution-amend-commit")]
    (is (some? editor) "clicking the open row expands the amend editor")
    (is (= [[:actions/amend-portfolio-optimizer-execution-order "perp:ZETA"]]
           (get-in commit [1 :on :click])))
    (let [facts (node-text (node-by-role rendered
                                         "portfolio-optimizer-execution-amend-facts"))]
      (is (str/includes? facts "300.5")
          "the editor states the live remaining size the replacement will trade"))))

(deftest amend-editor-controls-dispatch-selection-actions-test
  (let [rendered (table {:open-row "perp:ZETA"} [(working-row)])]
    (testing "type toggle routes through the existing row-order-type action"
      (is (some? (find-first-node
                  rendered
                  #(= [[:actions/set-portfolio-optimizer-execution-row-order-type
                        "perp:ZETA" :market]]
                      (get-in % [1 :on :click]))))))
    (testing "bps presets are signed for the order's own side (buy rests below)"
      (is (some? (find-first-node
                  rendered
                  #(= [[:actions/set-portfolio-optimizer-execution-row-param
                        "perp:ZETA" :limit-bps -5]]
                      (get-in % [1 :on :click]))))))))

(deftest amend-editor-market-conversion-copy-test
  (let [rendered (table {:open-row "perp:ZETA"}
                        [(working-row {:amendable? true
                                       :remaining-size 300.5
                                       :order-type :market
                                       :limit-bps 0})])
        editor (node-by-role rendered
                             "portfolio-optimizer-execution-order-amend-perp-ZETA")]
    (is (str/includes? (node-text editor)
                                  "crosses immediately for the remaining size"))))

(deftest header-copy-mentions-amend-when-working-orders-present-test
  (is (str/includes? (node-text (table {} [(working-row)]))
                                "Click an open order to amend it")))

(deftest rejected-row-surfaces-the-exchange-error-test
  (let [message "Order could not immediately match against any resting orders. asset=110026"
        rendered (table {} [(-> (working-row nil)
                                (assoc :status :failed
                                       :error {:message message}))])
        error-node (node-by-role rendered "portfolio-optimizer-execution-row-error")]
    (is (some? error-node) "the exchange's own message renders on the failed row")
    (is (str/includes? (node-text error-node) "could not immediately match"))))
