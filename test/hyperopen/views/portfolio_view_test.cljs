(ns hyperopen.views.portfolio-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(def sample-state
  {:account {:mode :classic}
   :account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort {:column nil :direction :asc}
                  :positions-sort {:column nil :direction :asc}
                  :open-orders-sort {:column "Time" :direction :desc}}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :fills [{:time (.now js/Date)
                     :sz "2"
                     :px "100"}]
            :fundings []
            :order-history []}
   :webdata2 {}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}})

(deftest portfolio-view-renders-phase1-layout-sections-test
  (let [view-node (portfolio-view/portfolio-view sample-state)
        root-node (find-first-node view-node #(= "portfolio-root" (get-in % [1 :data-parity-id])))
        actions-row (find-first-node view-node #(= "portfolio-actions-row" (get-in % [1 :data-role])))
        volume-card (find-first-node view-node #(= "portfolio-14d-volume-card" (get-in % [1 :data-role])))
        fees-card (find-first-node view-node #(= "portfolio-fees-card" (get-in % [1 :data-role])))
        summary-card (find-first-node view-node #(= "portfolio-account-summary-card" (get-in % [1 :data-role])))
        chart-shell (find-first-node view-node #(= "portfolio-chart-shell" (get-in % [1 :data-role])))
        account-table (find-first-node view-node #(= "portfolio-account-table" (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))]
    (is (some? root-node))
    (is (some? actions-row))
    (is (some? volume-card))
    (is (some? fees-card))
    (is (some? summary-card))
    (is (some? chart-shell))
    (is (some? account-table))
    (is (contains? all-text "Portfolio"))
    (is (contains? all-text "14 Day Volume"))
    (is (contains? all-text "Fees (Taker / Maker)"))
    (is (some #(str/includes? % "Open Orders") all-text))))
