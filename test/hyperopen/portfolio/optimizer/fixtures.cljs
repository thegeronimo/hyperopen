(ns hyperopen.portfolio.optimizer.fixtures
  (:require [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]
            [hyperopen.portfolio.optimizer.defaults :as defaults]))

(def ^:private default-scenario-id
  "fixture-scenario")

(def ^:private default-as-of-ms
  5000)

(def ^:private replace-on-override
  #{:by-instrument
    :current-weights-by-instrument
    :exposures
    :fee-bps-by-id
    :frontier
    :funding-history-by-coin
    :instrument-ids
    :markets
    :ordered-ids
    :prices-by-id
    :return-decomposition-by-instrument
    :rows
    :target-weights-by-instrument
    :target-weights
    :universe})

(declare deep-merge-fixture)

(defn- merge-entry
  [key left right]
  (if (contains? replace-on-override key)
    right
    (deep-merge-fixture left right)))

(defn- deep-merge-fixture
  [left right]
  (if (and (map? left)
           (map? right))
    (reduce-kv (fn [acc key value]
                 (assoc acc key (merge-entry key (get acc key) value)))
               left
               right)
    right))

(defn- with-overrides
  [base overrides]
  (if (or (nil? overrides)
          (and (map? overrides)
               (empty? overrides)))
    base
    (deep-merge-fixture base overrides)))

(defn sample-universe
  ([]
   (sample-universe {}))
  ([overrides]
   (with-overrides
    [{:instrument-id "perp:BTC"
      :key "perp:BTC"
      :market-type :perp
      :coin "BTC"
      :symbol "BTC-USDC"
      :base "BTC"
      :quote "USDC"
      :shortable? true}
     {:instrument-id "perp:ETH"
      :key "perp:ETH"
      :market-type :perp
      :coin "ETH"
      :symbol "ETH-USDC"
      :base "ETH"
      :quote "USDC"
      :shortable? true}
     {:instrument-id "spot:PURR"
      :key "spot:PURR"
      :market-type :spot
      :coin "PURR"
      :symbol "PURR/USDC"
      :base "PURR"
      :quote "USDC"
      :shortable? false}]
    overrides)))

(defn sample-draft
  ([]
   (sample-draft {}))
  ([overrides]
   (with-overrides
    (-> (defaults/default-draft)
        (assoc :id default-scenario-id
               :name "Fixture Optimization"
               :status :draft
               :universe (sample-universe)
               :objective {:kind :max-sharpe}
               :return-model {:kind :historical-mean}
               :risk-model {:kind :diagonal-shrink
                            :shrinkage 0.2}
               :constraints {:long-only? true
                             :gross-max 1.0
                             :net-min 0.0
                             :net-max 1.0
                             :max-asset-weight 0.85
                             :dust-usdc 10.0
                             :asset-overrides {}
                             :held-locks []
                             :perp-leverage {"perp:BTC" {:max-weight 0.75}
                                             "perp:ETH" {:max-weight 0.7}}
                             :allowlist []
                             :blocklist []
                             :max-turnover 0.5
                             :rebalance-tolerance 0.02}
               :execution-assumptions {:default-order-type :market
                                       :fallback-slippage-bps 20
                                       :fee-mode :taker
                                       :manual-capital-usdc nil
                                       :prices-by-id {"perp:BTC" 100000
                                                      "perp:ETH" 5000
                                                      "spot:PURR" 0.5}
                                       :fee-bps-by-id {"perp:BTC" 4
                                                      "perp:ETH" 4
                                                      "spot:PURR" 10}})
        (assoc-in [:metadata :created-at-ms] 1000)
        (assoc-in [:metadata :updated-at-ms] 4000)
        (assoc-in [:metadata :dirty?] false))
    overrides)))

(defn sample-current-portfolio
  ([]
   (sample-current-portfolio {}))
  ([overrides]
   (with-overrides
    {:address "0x1111111111111111111111111111111111111111"
     :loaded? true
     :snapshot-loaded? true
     :capital-ready? true
     :execution-ready? true
     :account {:mode :unified
               :read-only? false}
     :capital {:nav-usdc 100000
               :account-value-usd 100000
               :cash-usdc 20000
               :gross-exposure-usdc 80000
               :net-exposure-usdc 80000
               :total-margin-used-usdc 12000}
     :exposures [{:instrument-id "perp:BTC"
                  :market-type :perp
                  :coin "BTC"
                  :signed-size 0.45
                  :mark-price 100000
                  :signed-notional-usdc 45000
                  :abs-notional-usdc 45000
                  :side :long
                  :weight 0.45
                  :source :fixture}
                 {:instrument-id "perp:ETH"
                  :market-type :perp
                  :coin "ETH"
                  :signed-size 6
                  :mark-price 5000
                  :signed-notional-usdc 30000
                  :abs-notional-usdc 30000
                  :side :long
                  :weight 0.3
                  :source :fixture}
                 {:instrument-id "spot:PURR"
                  :market-type :spot
                  :coin "PURR"
                  :symbol "PURR/USDC"
                  :base "PURR"
                  :quote "USDC"
                  :available-size 10000
                  :hold-size 0
                  :mark-price 0.5
                  :signed-notional-usdc 5000
                  :abs-notional-usdc 5000
                  :side :long
                  :weight 0.05
                  :source :fixture}]
     :by-instrument {"perp:BTC" {:weight 0.45
                                 :signed-notional-usdc 45000
                                 :market-type :perp
                                 :coin "BTC"}
                     "perp:ETH" {:weight 0.3
                                 :signed-notional-usdc 30000
                                 :market-type :perp
                                 :coin "ETH"}
                     "spot:PURR" {:weight 0.05
                                  :signed-notional-usdc 5000
                                  :market-type :spot
                                  :coin "PURR"}}
     :warnings []
     :signature {:source :fixture
                 :as-of-ms default-as-of-ms}}
    overrides)))

(defn- candle
  [time-ms close]
  {:time-ms time-ms
   :close close})

(defn sample-history-data
  ([]
   (sample-history-data {}))
  ([overrides]
   (with-overrides
    {:candle-history-by-coin
     {"BTC" [(candle 1000 "94000")
             (candle 2000 "98000")
             (candle 3000 "101000")
             (candle 4000 "100000")]
      "ETH" [(candle 1000 "4600")
             (candle 2000 "4800")
             (candle 3000 "5100")
             (candle 4000 "5000")]
      "PURR" [(candle 1000 "0.42")
              (candle 2000 "0.46")
              (candle 3000 "0.52")
              (candle 4000 "0.50")]}
     :funding-history-by-coin
     {"BTC" [{:time-ms 1000 :funding-rate-raw 0.0001}
             {:time-ms 2000 :funding-rate-raw 0.00012}
             {:time-ms 3000 :funding-rate-raw 0.00011}]
      "ETH" [{:time-ms 1000 :funding-rate-raw 0.00005}
             {:time-ms 2000 :funding-rate-raw 0.00006}
             {:time-ms 3000 :funding-rate-raw 0.00004}]}
     :warnings []}
    overrides)))

(defn sample-engine-request
  ([]
   (sample-engine-request {}))
  ([overrides]
   (let [input (with-overrides
                 {:draft (sample-draft)
                  :current-portfolio (sample-current-portfolio)
                  :history-data (sample-history-data)
                  :market-cap-by-coin {"BTC" 600
                                       "ETH" 300
                                       "PURR" 100}
                  :as-of-ms default-as-of-ms
                  :stale-after-ms 86400000}
                 overrides)]
     (request-builder/build-engine-request input))))

(defn- current-weights-by-instrument
  []
  (into {}
        (map (fn [[instrument-id exposure]]
               [instrument-id (:weight exposure)]))
        (:by-instrument (sample-current-portfolio))))

(defn sample-solved-result
  ([]
   (sample-solved-result {}))
  ([overrides]
   (let [instrument-ids ["perp:BTC" "perp:ETH" "spot:PURR"]
         target-weights [0.5 0.35 0.05]
         current-weights [0.45 0.3 0.05]
         target-by-id (zipmap instrument-ids target-weights)]
     (with-overrides
      {:status :solved
       :scenario-id default-scenario-id
       :as-of-ms default-as-of-ms
       :instrument-ids instrument-ids
       :target-weights target-weights
       :current-weights current-weights
       :labels-by-instrument {"perp:BTC" "BTC"
                              "perp:ETH" "ETH"
                              "spot:PURR" "PURR"}
       :target-weights-by-instrument target-by-id
       :current-weights-by-instrument (current-weights-by-instrument)
       :dropped-weights []
       :current-expected-return 0.12
       :current-volatility 0.24
       :current-performance {:in-sample-sharpe 0.5
                             :shrunk-sharpe 0.25}
       :expected-return 0.16
       :volatility 0.28
       :performance {:in-sample-sharpe 0.5714285714285714
                     :shrunk-sharpe 0.2857142857142857}
       :history-summary {:return-observations 3
                         :oldest-common-ms 1000
                         :latest-common-ms 4000
                         :age-ms 1000
                         :stale? false}
       :solver {:strategy :single
                :objective-kind :max-sharpe}
       :solver-results [{:status :solved
                         :solver :fixture-solver
                         :weights target-weights
                         :iterations 8
                         :elapsed-ms 2}]
       :frontier [{:id 0
                   :expected-return 0.12
                   :volatility 0.24
                   :sharpe 0.5}
                  {:id 1
                   :expected-return 0.16
                   :volatility 0.28
                   :sharpe 0.5714285714285714}]
       :diagnostics {:gross-exposure 0.9
                     :net-exposure 0.9
                     :effective-n 2.6
                     :turnover 0.15
                     :covariance-conditioning {:status :ok
                                               :condition-number 2500
                                               :min-eigenvalue 0.001}
                     :weight-sensitivity-by-instrument
                     {"perp:BTC" {:base-expected-return 0.16
                                  :down-expected-return 0.14
                                  :up-expected-return 0.18}}
                     :binding-constraints []}
       :return-model :historical-mean
       :risk-model :diagonal-shrink
       :return-decomposition-by-instrument
       {"perp:BTC" {:return-component 0.1
                    :funding-component 0.04
                    :funding-source :market-funding-history}
        "perp:ETH" {:return-component 0.08
                    :funding-component 0.02
                    :funding-source :market-funding-history}
        "spot:PURR" {:return-component 0.12
                     :funding-component 0
                     :funding-source :not-applicable}}
       :black-litterman-diagnostics nil
       :warnings []
       :rebalance-preview
       {:status :ready
        :capital-usd 100000
        :summary {:ready-count 2
                  :blocked-count 0
                  :skipped-count 1
                  :gross-trade-notional-usd 10000
                  :estimated-fees-usd 5.0
                  :estimated-slippage-usd 20.0}
        :rows [{:instrument-id "perp:BTC"
                :instrument-type :perp
                :status :ready
                :side :buy
                :quantity 0.05
                :delta-weight 0.05
                :delta-notional-usd 5000}
               {:instrument-id "perp:ETH"
                :instrument-type :perp
                :status :ready
                :side :buy
                :quantity 1
                :delta-weight 0.05
                :delta-notional-usd 5000}
               {:instrument-id "spot:PURR"
                :instrument-type :spot
                :status :skipped
                :reason :within-tolerance
                :side :hold
                :quantity 0
                :delta-weight 0
                :delta-notional-usd 0}]}}
      overrides))))

(defn sample-last-successful-run
  ([]
   (sample-last-successful-run {}))
  ([overrides]
   (with-overrides
    {:request-signature {:scenario-id default-scenario-id
                         :instrument-ids ["perp:BTC" "perp:ETH" "spot:PURR"]}
     :computed-at-ms default-as-of-ms
     :result (sample-solved-result)}
    overrides)))

(defn sample-minimal-solved-result
  ([]
   (sample-minimal-solved-result {}))
  ([overrides]
   (with-overrides
    {:status :solved}
    overrides)))

(defn sample-minimal-last-successful-run
  ([]
   (sample-minimal-last-successful-run {}))
  ([overrides]
   (let [result-overrides (:result overrides)
         run-overrides (dissoc overrides :result)
         base {:request-signature {:scenario-id default-scenario-id}
               :computed-at-ms default-as-of-ms
               :result (sample-minimal-solved-result)}]
     (cond-> (merge base run-overrides)
       (contains? overrides :result)
       (assoc :result (sample-minimal-solved-result result-overrides))))))

(defn- scenario-summary
  [scenario-id draft result]
  {:id scenario-id
   :name (:name draft)
   :status :computed
   :objective-kind (get-in draft [:objective :kind])
   :return-model-kind (get-in draft [:return-model :kind])
   :risk-model-kind (get-in draft [:risk-model :kind])
   :expected-return (:expected-return result)
   :volatility (:volatility result)
   :rebalance-status (get-in result [:rebalance-preview :status])
   :updated-at-ms default-as-of-ms})

(defn- asset-selector-market
  [instrument]
  (assoc instrument :key (:instrument-id instrument)))

(defn sample-scenario-state
  ([]
   (sample-scenario-state {}))
  ([overrides]
   (let [draft (sample-draft)
         result (sample-solved-result)
         last-run (sample-last-successful-run {:result result})
         summary (scenario-summary default-scenario-id draft result)]
     (with-overrides
      {:router {:path (str "/portfolio/optimize/" default-scenario-id)}
       :portfolio-ui {:optimizer (defaults/default-optimizer-ui-state)}
       :asset-selector {:markets (mapv asset-selector-market (sample-universe))
                        :market-by-key (into {}
                                             (map (fn [market]
                                                    [(:key market) market]))
                                             (mapv asset-selector-market
                                                   (sample-universe)))}
       :portfolio {:optimizer
                   (-> (defaults/default-optimizer-state)
                       (assoc :draft draft
                              :active-scenario {:loaded-id default-scenario-id
                                                :status :computed
                                                :read-only? false}
                              :scenario-index {:ordered-ids [default-scenario-id]
                                               :by-id {default-scenario-id summary}}
                              :last-successful-run last-run
                              :history-data (sample-history-data)
                              :tracking {:status :tracking
                                         :scenario-id default-scenario-id
                                         :updated-at-ms default-as-of-ms
                                         :snapshots [{:timestamp-ms default-as-of-ms
                                                      :drift-pct 0.01
                                                      :tracking-error 0.02}]
                                         :error nil}
                              :run-state {:status :succeeded
                                          :run-id "fixture-run"
                                          :scenario-id default-scenario-id
                                          :request-signature (:request-signature last-run)
                                          :started-at-ms 4500
                                          :completed-at-ms default-as-of-ms
                                          :error nil
                                          :result result}))}}
      overrides))))
