(ns hyperopen.views.portfolio.vm.benchmarks-helpers-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.views.portfolio.vm.benchmarks :as vm-benchmarks]))

(defn- approx=
  [a b]
  (< (js/Math.abs (- a b)) 1e-9))

(use-fixtures :each
  (fn [f]
    (vm-benchmarks/reset-portfolio-vm-cache!)
    (f)
    (vm-benchmarks/reset-portfolio-vm-cache!)))

(deftest vault-benchmark-helpers-cover-normalization-selection-and-cap-test
  (is (= "vault:0xabc"
         (vm-benchmarks/vault-benchmark-value "0xabc")))
  (is (= "0xabc"
         (vm-benchmarks/vault-benchmark-address "vault:0xAbC")))
  (is (nil? (vm-benchmarks/vault-benchmark-address "BTC")))
  (is (true? (vm-benchmarks/benchmark-vault-row? {:vault-address "0xabc"
                                                  :relationship {:type :normal}})))
  (is (false? (vm-benchmarks/benchmark-vault-row? {:vault-address "0xabc"
                                                   :relationship {:type :child}})))
  (is (= 12
         (vm-benchmarks/benchmark-vault-tvl {:tvl "12"})))
  (is (= "Alpha"
         (vm-benchmarks/benchmark-vault-name {:name " Alpha "})))
  (is (= [ -12 "alpha" "0xabc"]
         (vm-benchmarks/benchmark-vault-option-rank {:tvl "12"
                                                     :name "Alpha"
                                                     :vault-address "0xabc"})))
  (let [rows (vec (concat
                   [{:name "Drop Child"
                     :vault-address "0xchild"
                     :relationship {:type :child}
                     :tvl 999}]
                   (for [idx (range 1 106)]
                     {:name (str "Vault " idx)
                      :vault-address (str "0x" idx)
                      :relationship {:type :normal}
                      :tvl idx})))
        eligible (vm-benchmarks/eligible-vault-benchmark-rows rows)
        built (vm-benchmarks/build-vault-benchmark-selector-options rows)]
    (is (= 100 (count eligible)))
    (is (= "0x105" (:vault-address (first eligible))))
    (is (= "0x6" (:vault-address (last eligible))))
    (is (= "vault:0x105" (get-in built [0 :value])))
    (is (= "Vault 105 (VAULT)" (get-in built [0 :label])))
    (is (= 105 (get-in built [0 :tvl])))))

(deftest market-benchmark-helpers-cover_rank_signature_and_search_paths-test
  (let [market {:coin "BTC"
                :symbol "BTC-USD"
                :dex "hl"
                :market-type :perp
                :openInterest "300"
                :cache-order "2"
                :key "btc-perp"}]
    (is (= :spot (vm-benchmarks/market-type-token "SPOT")))
    (is (= :perp (vm-benchmarks/market-type-token " perp ")))
    (is (nil? (vm-benchmarks/market-type-token 7)))
    (is (= 300 (vm-benchmarks/benchmark-open-interest market)))
    (is (= 0 (vm-benchmarks/benchmark-open-interest {:openInterest "bad"})))
    (is (= "BTC-USD (HL PERP)"
           (vm-benchmarks/benchmark-option-label market)))
    (is (= [ -300 2 "btc-usd" "btc" "btc-perp"]
           (vm-benchmarks/benchmark-option-rank market)))
    (is (number? (vm-benchmarks/benchmark-market-signature market)))
    (is (= (vm-benchmarks/mix-benchmark-markets-hash 7 market)
           (vm-benchmarks/mix-benchmark-markets-hash 7 market)))
    (is (= {:count 0 :rolling-hash 1 :xor-hash 0}
           (vm-benchmarks/benchmark-markets-signature [])))
    (is (= 2 (:count (vm-benchmarks/benchmark-markets-signature [market (assoc market :coin "ETH")]))))
    (is (= "btc"
           (vm-benchmarks/normalize-benchmark-search-query "  BTC  ")))
    (is (true? (vm-benchmarks/benchmark-option-matches-search? {:label "BTC-USD (HL PERP)"} "btc")))
    (is (true? (vm-benchmarks/benchmark-option-matches-search? {:label "SPY" :value "SPY"} "spy")))
    (is (false? (vm-benchmarks/benchmark-option-matches-search? {:label "BTC"} "eth")))))

(deftest benchmark-selector-model-and-memoization-cover-cache-branches-test
  (let [state {:asset-selector {:markets [{:coin "BTC"
                                           :symbol "BTC-USD"
                                           :dex "hl"
                                           :market-type :perp
                                           :openInterest "300"
                                           :cache-order 1}
                                          {:coin "ETH"
                                           :symbol "ETH-USD"
                                           :dex "hl"
                                           :market-type :perp
                                           :openInterest "200"
                                           :cache-order 2}]}
               :vaults {:merged-index-rows [{:name "Alpha"
                                             :vault-address "0xabc"
                                             :relationship {:type :normal}
                                             :tvl 10}]}
               :portfolio-ui {:returns-benchmark-coins ["BTC" "vault:0xabc"]
                              :returns-benchmark-search "eth"
                              :returns-benchmark-suggestions-open? true}}
        short-state (assoc state :portfolio-ui {:returns-benchmark-coins ["BTC"]
                                                :returns-benchmark-search "eth"
                                                :returns-benchmark-suggestions-open? false})]
    (is (= ["BTC" "vault:0xabc"]
           (vm-benchmarks/selected-returns-benchmark-coins state)))
    (is (= [{:value "BTC"
             :label "BTC-USD (HL PERP)"
             :open-interest 300}
            {:value "vault:0xabc"
             :label "Alpha (VAULT)"
             :tvl 10}]
           (vm-benchmarks/selected-benchmark-options
            (vm-benchmarks/benchmark-selector-options state)
            ["BTC" "vault:0xabc"])))
    (let [model (vm-benchmarks/returns-benchmark-selector-model state)
          search-model (vm-benchmarks/returns-benchmark-selector-model short-state)]
      (is (= ["BTC" "vault:0xabc"] (:selected-coins model)))
      (is (= "ETH" (:top-coin model)))
      (is (= "eth" (:coin-search search-model)))
      (is (= ["ETH"] (mapv :value (:candidates search-model))))
      (is (true? (:suggestions-open? model))))
    (vm-benchmarks/reset-portfolio-vm-cache!)
    (let [build-count (atom 0)
          original-builder vm-benchmarks/*build-benchmark-selector-options*
          markets-a [{:coin "BTC"
                      :symbol "BTC-USD"
                      :dex "hl"
                      :market-type :perp
                      :openInterest "300"
                      :cache-order 1}
                     {:coin "ETH"
                      :symbol "ETH-USD"
                      :dex "hl"
                      :market-type :perp
                      :openInterest "200"
                      :cache-order 2}]
          markets-b (mapv identity markets-a)
          markets-c (assoc-in markets-b [1 :openInterest] "1200")]
      (with-redefs [vm-benchmarks/*build-benchmark-selector-options*
                    (fn [markets]
                      (swap! build-count inc)
                      (original-builder markets))]
        (let [first-options (vm-benchmarks/memoized-benchmark-selector-options markets-a)
              second-options (vm-benchmarks/memoized-benchmark-selector-options markets-a)
              signature-hit (vm-benchmarks/memoized-benchmark-selector-options markets-b)
              changed-options (vm-benchmarks/memoized-benchmark-selector-options markets-c)]
          (is (= 2 @build-count))
          (is (= ["BTC" "ETH"] (mapv :value first-options)))
          (is (= ["BTC" "ETH"] (mapv :value second-options)))
          (is (= ["BTC" "ETH"] (mapv :value signature-hit)))
          (is (= ["ETH" "BTC"] (mapv :value changed-options)))))
      (let [vaults [{:name "Alpha"
                     :vault-address "0xabc"
                     :relationship {:type :normal}
                     :tvl 10}]
            first-rows (vm-benchmarks/memoized-eligible-vault-benchmark-rows vaults)
            second-rows (vm-benchmarks/memoized-eligible-vault-benchmark-rows vaults)]
        (is (identical? first-rows second-rows))))))

(deftest benchmark-computation-context-builds_strategy_and_benchmark_rows-test
  (let [t0 1704067200000
        t1 (+ t0 (* 24 60 60 1000))
        t2 (+ t1 (* 24 60 60 1000))
        vault-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        vault-ref (str "vault:" vault-address)
        state {:candles {"SPY" {:1h [{:t t0 :c 50}
                                     {:t t1 :c 55}
                                     {:t t2 :c 60}]}}
               :vaults {:merged-index-rows [{:name "Peer Vault"
                                             :vault-address vault-address
                                             :relationship {:type :normal}
                                             :tvl 120
                                             :snapshot-by-key {:month [0.05 0.15]}}]}}
        selector {:selected-coins ["SPY" vault-ref]
                  :label-by-coin {"SPY" "SPY (HL SPOT)"
                                  vault-ref "Peer Vault (VAULT)"}}]
    (with-redefs [portfolio-metrics/returns-history-rows (fn [_state _summary _scope]
                                                           [[t0 0]
                                                            [t1 10]
                                                            [t2 20]])]
      (let [context (vm-benchmarks/benchmark-computation-context state
                                                                 {:dummy true}
                                                                 :all
                                                                 :month
                                                                 selector)]
        (is (= [[t0 0] [t1 10] [t2 20]]
               (:strategy-cumulative-rows context)))
        (is (every? true?
                    (map approx=
                         [0 10 20]
                         (mapv second (get (:benchmark-cumulative-rows-by-coin context) "SPY")))))
        (is (every? true?
                    (map approx=
                         [5 15 15]
                         (mapv second (get (:benchmark-cumulative-rows-by-coin context) vault-ref)))))
        (is (number? (:strategy-source-version context)))
        (is (number? (get (:benchmark-source-version-map context) "SPY")))
        (is (number? (get (:benchmark-source-version-map context) vault-ref)))))))

(deftest benchmark-computation-context-aligns-benchmarks-to-latest-prior-candle-test
  (let [c0 1704067200000
        c1 (+ c0 3600000)
        c2 (+ c1 3600000)
        state {:candles {"BTC" {:1h [{:t c0 :c 100}
                                     {:t c1 :c 110}
                                     {:t c2 :c 121}]}}}
        selector {:selected-coins ["BTC"]
                  :label-by-coin {"BTC" "BTC-USDC (PERP)"}}]
    (with-redefs [portfolio-metrics/returns-history-rows (fn [_state _summary _scope]
                                                           [[(+ c0 1800000) 0]
                                                            [(+ c1 1800000) 5]
                                                            [(+ c2 1800000) 10]])]
      (let [context (vm-benchmarks/benchmark-computation-context state
                                                                 {:dummy true}
                                                                 :all
                                                                 :month
                                                                 selector)]
        (is (= [(+ c0 1800000)
                (+ c1 1800000)
                (+ c2 1800000)]
               (mapv first (get (:benchmark-cumulative-rows-by-coin context) "BTC"))))
        (is (every? true?
                    (map approx=
                         [0 10 21]
                         (mapv second (get (:benchmark-cumulative-rows-by-coin context) "BTC")))))))))
