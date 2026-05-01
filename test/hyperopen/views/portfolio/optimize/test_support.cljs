(ns hyperopen.views.portfolio.optimize.test-support
  (:require [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.test-support.hiccup :as hiccup]))

(def collect-strings hiccup/collect-strings)

(def collect-nodes hiccup/find-all-nodes)

(def node-by-role hiccup/find-by-data-role)

(defn text-node
  [node value]
  (hiccup/find-first-node
   node
   #(and (= :text (first %))
         (some #{value} (collect-strings %)))))

(defn click-actions
  [node]
  (get-in node [1 :on :click]))

(defn input-actions
  [node]
  (get-in node [1 :on :input]))

(defn change-actions
  [node]
  (get-in node [1 :on :change]))

(defn node-attr
  [node attr]
  (get-in node [1 attr]))

(defn data-role-order
  [node]
  (keep #(get-in % [1 :data-role])
        (collect-nodes node #(some? (get-in % [1 :data-role])))))

(defn index-of
  [coll value]
  (first (keep-indexed (fn [idx item]
                         (when (= value item) idx))
                       coll)))

(defn drag-start-actions
  [node]
  (get-in node [1 :on :drag-start]))

(defn drag-enter-actions
  [node]
  (get-in node [1 :on :drag-enter]))

(def solved-result
  (fixtures/sample-solved-result
   {:instrument-ids ["perp:BTC" "spot:PURR"]
    :current-weights [0.2 0.1]
    :target-weights [0.35 -0.02]
    :target-weights-by-instrument {"perp:BTC" 0.35
                                   "spot:PURR" -0.02}
    :current-weights-by-instrument {"perp:BTC" 0.2
                                    "spot:PURR" 0.1}
    :expected-return 0.18
    :volatility 0.42
    :performance {:in-sample-sharpe 0.43
                  :shrunk-sharpe 0.215}
    :history-summary {:return-observations 2
                      :stale? false}
    :frontier [{:id 0
                :expected-return 0.12
                :volatility 0.24
                :sharpe 0.5
                :weights [0.5 0.5]}
               {:id 1
                :expected-return 0.18
                :volatility 0.42
                :sharpe 0.43
                :weights [0.5 0.5]}]
    :frontier-summary {:source :display-sweep
                       :point-count 2}
    :frontier-overlays
    {:standalone [{:instrument-id "perp:BTC"
                   :label "BTC"
                   :target-weight 0.35
                   :expected-return 0.12
                   :volatility 0.4}
                  {:instrument-id "spot:PURR"
                   :label "PURR"
                   :target-weight -0.02
                   :expected-return 0.08
                   :volatility 0.22}]
     :contribution [{:instrument-id "perp:BTC"
                     :label "BTC"
                     :target-weight 0.35
                     :expected-return 0.042
                     :volatility 0.14}
                    {:instrument-id "spot:PURR"
                     :label "PURR"
                     :target-weight -0.02
                     :expected-return -0.0016
                     :volatility -0.01}]}
    :return-decomposition-by-instrument
    {"perp:BTC" {:return-component 0.12
                 :funding-component 0.04
                 :funding-source :market-funding-history}
     "spot:PURR" {:return-component 0.08
                  :funding-component 0
                  :funding-source :missing}}
    :diagnostics {:gross-exposure 0.37
                  :net-exposure 0.33
                  :effective-n 2.2
                  :turnover 0.135
                  :covariance-conditioning {:status :watch
                                            :condition-number 12000
                                            :min-eigenvalue 0.001}
                  :weight-sensitivity-by-instrument
                  {"perp:BTC" {:base-expected-return 0.18
                               :down-expected-return 0.17
                               :up-expected-return 0.19}}
                  :binding-constraints [{:instrument-id "perp:BTC"
                                         :constraint :upper-bound}]}
    :warnings [{:code :low-invested-exposure
                :message "Minimum variance selected a near-cash signed portfolio."}]
    :rebalance-preview {:status :partially-blocked
                        :capital-usd 10000
                        :summary {:ready-count 1
                                  :blocked-count 1
                                  :gross-trade-notional-usd 2700
                                  :estimated-fees-usd 1.2
                                  :estimated-slippage-usd 2.4}
                        :rows [{:instrument-id "perp:BTC"
                                :status :ready
                                :side :buy
                                :delta-notional-usd 1500}
                               {:instrument-id "spot:PURR"
                                :status :blocked
                                :reason :spot-submit-unsupported
                                :side :sell
                                :delta-notional-usd -1200}]}}))
