(ns hyperopen.vaults.detail.benchmarks-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.detail.benchmarks :as benchmarks]))

(use-fixtures :each
  (fn [f]
    (benchmarks/reset-vault-detail-benchmarks-cache!)
    (f)
    (benchmarks/reset-vault-detail-benchmarks-cache!)))

(deftest returns-benchmark-selector-model-builds-market-and-vault-options-test
  (let [vault-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        state {:asset-selector {:markets [{:coin "BTC"
                                           :symbol "BTC"
                                           :dex "hl"
                                           :market-type :perp
                                           :openInterest 1000}]}
               :vaults-ui {:detail-returns-benchmark-coins ["BTC"
                                                            (str "vault:" vault-address)]
                           :detail-returns-benchmark-search ""
                           :detail-returns-benchmark-suggestions-open? false}
               :vaults {:merged-index-rows [{:name "Peer Vault"
                                             :vault-address vault-address
                                             :relationship {:type :normal}
                                             :tvl 120}]}}
        model (benchmarks/returns-benchmark-selector-model state)]
    (is (= ["BTC" (str "vault:" vault-address)]
           (:selected-coins model)))
    (is (= "BTC (HL PERP)"
           (get-in model [:label-by-coin "BTC"])))
    (is (= "Peer Vault (VAULT)"
           (get-in model [:label-by-coin (str "vault:" vault-address)])))))

(deftest benchmark-cumulative-return-points-by-coin-supports-market-and-vault-benchmarks-test
  (let [vault-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        benchmark-summary {:summary-id :peer-vault-month}
        state {:candles {"BTC" {:1h [[1 0 0 0 100]
                                     [2 0 0 0 110]]}}
               :vaults {:benchmark-details-by-address
                        {vault-address {:portfolio {:month benchmark-summary}}}}}
        strategy-return-points [{:time-ms 1 :value 0}
                                {:time-ms 2 :value 10}]]
    (with-redefs [portfolio-metrics/returns-history-rows
                  (fn [_state summary _scope]
                    (if (= benchmark-summary summary)
                      [[1 0] [2 8]]
                      []))]
      (let [rows-by-coin (benchmarks/benchmark-cumulative-return-points-by-coin
                          state
                          :month
                          ["BTC" (str "vault:" vault-address)]
                          strategy-return-points)]
        (is (= [0 10]
               (mapv :value (get rows-by-coin "BTC"))))
        (is (= [0 8]
               (mapv :value (get rows-by-coin (str "vault:" vault-address)))))))))

(deftest benchmark-cumulative-return-points-by-coin-accepts-map-shaped-candles-and-skips-invalid-rows-test
  (let [state {:candles {"BTC" {:1h [{:timestamp "1" :close "100"}
                                     {:timeMs 2 :c "110"}
                                     {:t 3 :c "0"}
                                     {:t "bad" :c "120"}]}}
               :vaults {:merged-index-rows []}}
        strategy-return-points [{:time-ms 1 :value 0}
                                {:time-ms 2 :value 10}]
        rows-by-coin (benchmarks/benchmark-cumulative-return-points-by-coin
                      state
                      :month
                      ["BTC"]
                      strategy-return-points)]
    (is (= [{:index 0 :time-ms 1 :value 0}
            {:index 1 :time-ms 2 :value 10}]
           (get rows-by-coin "BTC")))))

(deftest vault-benchmark-selector-cache-hits-identity-signature-and-invalidation-paths-test
  (let [rows-a [{:name "Alpha"
                 :vault-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                 :relationship {:type :normal}
                 :tvl 120}
                {:name "Beta"
                 :vault-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                 :relationship {:type :normal}
                 :tvl 90}]
        rows-b (mapv identity rows-a)
        rows-c (assoc-in rows-b [1 :tvl] 180)
        first-rows (benchmarks/memoized-eligible-vault-benchmark-rows rows-a)
        second-rows (benchmarks/memoized-eligible-vault-benchmark-rows rows-a)
        signature-hit (benchmarks/memoized-eligible-vault-benchmark-rows rows-b)
        invalidated (benchmarks/memoized-eligible-vault-benchmark-rows rows-c)]
    (is (identical? first-rows second-rows))
    (is (identical? first-rows signature-hit))
    (is (not (identical? first-rows invalidated)))
    (is (= "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
           (:vault-address (first invalidated))))))

(deftest returns-benchmark-selector-model-cache-reuses-closed-suggestion-models-test
  (let [state {:asset-selector {:markets [{:coin "BTC"
                                           :symbol "BTC"
                                           :dex "hl"
                                           :market-type :perp
                                           :openInterest 1000}]}
               :vaults-ui {:detail-returns-benchmark-coins ["BTC"]
                           :detail-returns-benchmark-search "btc"
                           :detail-returns-benchmark-suggestions-open? false}
               :vaults {:merged-index-rows []}}]
    (let [first-model (benchmarks/returns-benchmark-selector-model state)
          second-model (benchmarks/returns-benchmark-selector-model (assoc state :toast {:id 1}))
          reopened-model (benchmarks/returns-benchmark-selector-model
                          (assoc-in state [:vaults-ui :detail-returns-benchmark-suggestions-open?] true))]
    (is (identical? first-model second-model))
    (is (not (identical? first-model reopened-model)))
    (is (= [] (mapv :value (:candidates first-model)))))))

(deftest benchmark-cumulative-return-points-by-coin-preserves-dense-market-path-over-effective-window-test
  (let [t0 (.getTime (js/Date. "2025-04-14T00:00:00Z"))
        t1 (.getTime (js/Date. "2025-07-14T00:00:00Z"))
        t2 (.getTime (js/Date. "2025-10-14T00:00:00Z"))
        t3 (.getTime (js/Date. "2026-01-14T00:00:00Z"))
        t4 (.getTime (js/Date. "2026-04-14T00:00:00Z"))
        state {:candles {"BTC" {:12h [{:t t0 :c 100}
                                      {:t t1 :c 94}
                                      {:t t2 :c 90}
                                      {:t t3 :c 86}
                                      {:t t4 :c 88}]}}
               :vaults {:merged-index-rows []}}
        strategy-return-points [{:time-ms t3 :value -14}
                                {:time-ms t4 :value -12}]
        strategy-window {:cutoff-ms t0
                         :window-end-ms t4}
        rows-by-coin (benchmarks/benchmark-cumulative-return-points-by-coin
                      state
                      :one-year
                      ["BTC"]
                      strategy-return-points
                      strategy-window)]
    (is (= [t0 t1 t2 t3 t4]
           (mapv :time-ms (get rows-by-coin "BTC"))))
    (is (= [0 -6 -10 -14 -12]
           (mapv :value (get rows-by-coin "BTC"))))
    (is (> (count (get rows-by-coin "BTC"))
           (count strategy-return-points)))))
