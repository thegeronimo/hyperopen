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

(defn- class-values [node]
  (let [class-attr (get-in node [1 :class])]
    (cond
      (vector? class-attr) class-attr
      (seq? class-attr) (vec class-attr)
      (string? class-attr) (str/split class-attr #"\s+")
      :else [])))

(defn- px-width [value]
  (some->> value
           (re-matches #"^([0-9]+)px$")
           second
           (#(js/Number.parseInt % 10))))

(def sample-state
  {:account {:mode :classic}
   :portfolio-ui {:summary-scope :all
                  :summary-time-range :month
                  :chart-tab :account-value
                  :summary-scope-dropdown-open? false
                  :summary-time-range-dropdown-open? false}
   :portfolio {:summary-by-key {:month {:pnlHistory [[1 10] [2 15]]
                                        :accountValueHistory [[1 100] [2 100]]
                                        :vlm 2255561.85}}
               :user-fees {:userCrossRate 0.00045
                           :userAddRate 0.00015
                           :dailyUserVlm [{:exchange 100
                                           :userCross 70
                                           :userAdd 30}
                                          {:exchange 50
                                           :userCross 20
                                           :userAdd 10}]}}
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
   :borrow-lend {:total-supplied-usd 0}
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
        scope-selector (find-first-node view-node #(= "portfolio-summary-scope-selector" (get-in % [1 :data-role])))
        time-range-selector (find-first-node view-node #(= "portfolio-summary-time-range-selector" (get-in % [1 :data-role])))
        chart-account-value-tab (find-first-node view-node #(= "portfolio-chart-tab-account-value" (get-in % [1 :data-role])))
        chart-pnl-tab (find-first-node view-node #(= "portfolio-chart-tab-pnl" (get-in % [1 :data-role])))
        chart-returns-tab (find-first-node view-node #(= "portfolio-chart-tab-returns" (get-in % [1 :data-role])))
        chart-shell (find-first-node view-node #(= "portfolio-chart-shell" (get-in % [1 :data-role])))
        chart-path (find-first-node view-node #(= "portfolio-chart-path" (get-in % [1 :data-role])))
        account-table (find-first-node view-node #(= "portfolio-account-table" (get-in % [1 :data-role])))
        all-text (set (collect-strings view-node))]
    (is (some? root-node))
    (is (some? actions-row))
    (is (some? volume-card))
    (is (some? fees-card))
    (is (some? summary-card))
    (is (some? scope-selector))
    (is (some? time-range-selector))
    (is (some? chart-account-value-tab))
    (is (some? chart-pnl-tab))
    (is (some? chart-returns-tab))
    (is (some? chart-shell))
    (is (some? chart-path))
    (is (some? account-table))
    (is (contains? all-text "Portfolio"))
    (is (contains? all-text "14 Day Volume"))
    (is (contains? all-text "Fees (Taker / Maker)"))
    (is (contains? all-text "Perps + Spot + Vaults"))
    (is (contains? all-text "30D"))
    (is (contains? all-text "Account Value"))
    (is (contains? all-text "PNL"))
    (is (contains? all-text "Returns"))
    (is (contains? all-text "Max Drawdown"))
    (is (contains? all-text "Vault Equity"))
    (is (contains? all-text "Staking Account"))
    (is (some #(str/includes? % "Open Orders") all-text))))

(deftest portfolio-view-chart-y-axis-allocates-readable-gutter-for-large-values-test
  (let [state (-> sample-state
                  (assoc-in [:portfolio-ui :chart-tab] :pnl)
                  (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                            [[1 -2500000] [2 1500000] [3 3750000]]))
        view-node (portfolio-view/portfolio-view state)
        y-axis-node (find-first-node view-node #(= "portfolio-chart-y-axis" (get-in % [1 :data-role])))
        y-axis-width-px (some-> y-axis-node
                                (get-in [1 :style :width])
                                px-width)
        y-axis-label-node (find-first-node
                           view-node
                           (fn [candidate]
                             (let [classes (set (class-values candidate))
                                   text-values (collect-strings candidate)]
                               (and (contains? classes "num")
                                    (contains? classes "text-right")
                                    (some #(re-find #"," %) text-values)))))
        all-text (collect-strings view-node)]
    (is (some? y-axis-node))
    (is (number? y-axis-width-px))
    (is (> y-axis-width-px 56))
    (is (some? y-axis-label-node))
    (is (some #(re-find #"[0-9],[0-9]" %) all-text))))

(deftest portfolio-view-returns-tab-renders-percent-axis-labels-test
  (let [state (-> sample-state
                  (assoc-in [:portfolio-ui :chart-tab] :returns)
                  (assoc-in [:portfolio :summary-by-key :month :pnlHistory]
                            [[1 0] [2 2] [3 -1]])
                  (assoc-in [:portfolio :summary-by-key :month :accountValueHistory]
                            [[1 100] [2 102] [3 99]]))
        view-node (portfolio-view/portfolio-view state)
        all-text (collect-strings view-node)]
    (is (some #(= "Returns" %) all-text))
    (is (some #(re-find #"\+[0-9]+\.[0-9]{2}%" %) all-text))
    (is (some #(re-find #"-[0-9]+\.[0-9]{2}%" %) all-text))))
