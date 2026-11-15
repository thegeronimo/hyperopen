(ns hyperopen.views.portfolio.optimize.result-vault-labels-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

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

(defn- solved-result
  [vault-id vault-label]
  (fixtures/sample-solved-result
   {:instrument-ids ["perp:BTC" vault-id]
    :current-weights [0.0 0.0]
    :target-weights [0.5 0.5]
    :labels-by-instrument {"perp:BTC" "BTC"
                           vault-id vault-label}
    :expected-return 0.12
    :volatility 0.24
    :diagnostics {:gross-exposure 1
                  :net-exposure 1
                  :effective-n 2
                  :turnover 0.5
                  :covariance-conditioning {:status :ok}
                  :weight-sensitivity-by-instrument
                  {vault-id {:base-expected-return 0.12
                             :down-expected-return 0.1
                             :up-expected-return 0.14}}
                  :binding-constraints [{:instrument-id vault-id
                                         :constraint :upper-bound}]}
    :rebalance-preview {:status :partially-blocked
                        :capital-usd 10000
                        :summary {:ready-count 0
                                  :blocked-count 1
                                  :gross-trade-notional-usd 1200
                                  :estimated-fees-usd 0
                                  :estimated-slippage-usd 0}
                        :rows [{:instrument-id vault-id
                                :status :blocked
                                :reason :vault-submit-unsupported
                                :side :sell
                                :delta-notional-usd -1200}]}
    :frontier [{:id 0
                :expected-return 0.12
                :volatility 0.24
                :sharpe 0.5
                :weights [0.5 0.5]}]
    :warnings [{:code :sparse-history-risk-estimation
                :instrument-id vault-id
                :message (str vault-id
                              ": sparse history uses mixed-frequency covariance.")}
               {:code :insufficient-pairwise-history
                :left-instrument-id "perp:BTC"
                :right-instrument-id vault-id
                :instrument-ids ["perp:BTC" vault-id]
                :message (str "perp:BTC / " vault-id
                              ": mixed-frequency covariance only had 1 shared interval; 2 required.")}]}))

(deftest results-panel-renders-vault-result-labels-by-name-test
  (let [vault-address "0x1e37a337ed460039d1b15bd3bc489de789768d5e"
        vault-id (str "vault:" vault-address)
        view-node (results-panel/results-panel
                   {:result (solved-result vault-id "HLP Vault")
                    :computed-at-ms 2600}
                   {:objective {:kind :target-volatility}}
                   {:frontier-overlay-mode :none})
        diamond (node-by-role view-node
                              "portfolio-optimizer-target-exposure-vault-diamond-HLP-Vault")
        text (node-text view-node)]
    (is (str/includes? text "HLP Vault"))
    (is (some? diamond))
    (is (not (str/includes? text vault-id)))
    (is (not (str/includes? text vault-address)))))
