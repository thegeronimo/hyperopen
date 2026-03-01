(ns hyperopen.views.portfolio.vm.benchmarks-helpers-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.views.portfolio.vm.benchmarks :as vm-benchmarks]
            [hyperopen.views.portfolio.vm.constants :as constants]))

(use-fixtures :each
  (fn [f]
    (vm-benchmarks/reset-portfolio-vm-cache!)
    (f)
    (vm-benchmarks/reset-portfolio-vm-cache!)))

(deftest vault-benchmark-helpers-cover-normalization-and-selection-test
  (is (= "vault:0xabc"
         (vm-benchmarks/vault-benchmark-value "0xabc")))
  (is (= "0xabc"
         (vm-benchmarks/vault-benchmark-address "vault:0xabc")))
  (is (nil? (vm-benchmarks/vault-benchmark-address "BTC")))
  (is (nil? (vm-benchmarks/vault-benchmark-address 42)))
  (is (true? (vm-benchmarks/benchmark-vault-row? {:isVault true :tvl 10})))
  (is (false? (vm-benchmarks/benchmark-vault-row? {:isVault true :tvl 0})))
  (is (false? (vm-benchmarks/benchmark-vault-row? {:isVault false :tvl 10})))
  (is (= "Unknown Vault"
         (vm-benchmarks/benchmark-vault-name {})))
  (is (= -10
         (vm-benchmarks/benchmark-vault-option-rank {:tvl 10}))))

(deftest vault-benchmark-option-builders-filter-order-and-cap-test
  (let [rows (vec (concat
                   [{:isVault true :vaultAddress "0xdrop" :name "No TVL"}
                    {:isVault false :vaultAddress "0xskip" :name "Not Vault" :tvl 999}]
                   (for [idx (range 1 15)]
                     {:isVault true
                      :vaultAddress (str "0x" idx)
                      :name (str "Vault " idx)
                      :tvl idx})))
        eligible (vm-benchmarks/eligible-vault-benchmark-rows rows)
        built (vm-benchmarks/build-vault-benchmark-selector-options eligible)]
    (is (= constants/max-vault-benchmark-options
           (count eligible)))
    (is (= "0x14" (:vaultAddress (first eligible))))
    (is (= "0x5" (:vaultAddress (last eligible))))
    (is (= "vault:0x14" (get-in built [0 :value])))
    (is (= "Vault 14" (get-in built [0 :label])))
    (is (= "Vaults" (get-in built [0 :group])))
    (is (= 14
           (js/Number (get-in built [0 :tvl]))))
    (is (= {:isVault true
            :vaultAddress "0x7"
            :name "Vault 7"
            :tvl 7}
           (vm-benchmarks/vault-benchmark-row-by-address rows "0x7")))))

(deftest market-benchmark-helpers-cover-type-label-rank-signature-and-search-test
  (let [markets [{:coin "BTC" :type "PERP" :openInterest "300"}
                 {:coin "ETH" :type "PERP" :openInterest "200"}
                 {:coin "BTC" :type "SPOT" :openInterest "150"}
                 {:coin "ETH" :type "SPOT" :openInterest "120"}
                 {:coin "SOL" :type "PERP" :openInterest "500"}]
        selector-options (vm-benchmarks/build-benchmark-selector-options markets [])]
    (is (= "Spot" (vm-benchmarks/market-type-token "SPOT")))
    (is (= "Perp" (vm-benchmarks/market-type-token "PERP")))
    (is (= "FUT" (vm-benchmarks/market-type-token "FUT")))
    (is (nil? (vm-benchmarks/market-type-token :spot)))
    (is (= 500
           (js/Number (vm-benchmarks/benchmark-open-interest {:openInterest "500"}))))
    (is (= 0
           (vm-benchmarks/benchmark-open-interest {:openInterest 500})))
    (is (= "SOL Perp"
           (vm-benchmarks/benchmark-option-label {:coin "SOL" :type "PERP"})))
    (is (= "SOL"
           (vm-benchmarks/benchmark-option-label {:coin "SOL" :type :perp})))
    (is (= -1000000000000
           (vm-benchmarks/benchmark-option-rank {:coin "BTC" :type "PERP" :openInterest "1"})))
    (is (= -100000000000
           (vm-benchmarks/benchmark-option-rank {:coin "ETH" :type "PERP" :openInterest "1"})))
    (is (= -10000000000
           (vm-benchmarks/benchmark-option-rank {:coin "BTC" :type "SPOT" :openInterest "1"})))
    (is (= -1000000000
           (vm-benchmarks/benchmark-option-rank {:coin "ETH" :type "SPOT" :openInterest "1"})))
    (is (= -500
           (vm-benchmarks/benchmark-option-rank {:coin "SOL" :type "PERP" :openInterest "500"})))
    (is (= ["BTC" "ETH" "BTC" "ETH" "SOL"]
           (mapv :value selector-options)))
    (is (= "SOL-500"
           (vm-benchmarks/benchmark-market-signature {:coin "SOL" :openInterest "500"})))
    (is (= constants/empty-benchmark-markets-signature
           (vm-benchmarks/benchmark-markets-signature [])))
    (let [many-markets (for [idx (range 12)]
                         {:coin (str "C" idx)
                          :type "PERP"
                          :openInterest (str idx)})
          signature (vm-benchmarks/benchmark-markets-signature many-markets)]
      (is (= 10 (count (str/split signature #"\|")))))
    (is (number? (vm-benchmarks/mix-benchmark-markets-hash 7 {:coin "SOL" :openInterest "500"})))
    (is (= (vm-benchmarks/mix-benchmark-markets-hash 7 {:coin "SOL" :openInterest "500"})
           (vm-benchmarks/mix-benchmark-markets-hash 7 {:coin "SOL" :openInterest "500"})))
    (is (true? (vm-benchmarks/benchmark-option-matches-search? {:label "SPY Spot"} "spy")))
    (is (false? (vm-benchmarks/benchmark-option-matches-search? {:label "SPY Spot"} "eth")))
    (is (true? (vm-benchmarks/benchmark-option-matches-search? {:label "SPY Spot"} nil)))
    (is (= "btc"
           (vm-benchmarks/normalize-benchmark-search-query "  BTC  ")))
    (is (nil? (vm-benchmarks/normalize-benchmark-search-query nil)))))

(deftest benchmark-selector-model-and-memoization-cover-cache-branches-test
  (let [state {:market-data {:active-markets [{:coin "BTC" :type "PERP" :openInterest "300"}
                                              {:coin "ETH" :type "PERP" :openInterest "200"}
                                              {:coin "SOL" :type "PERP" :openInterest "100"}]}
               :portfolio {:summaries {:all {:vaults [{:isVault true
                                                       :vaultAddress "0xabc"
                                                       :name "Alpha"
                                                       :tvl 10}]}}}
               :ui {:preferences {:portfolio-returns-benchmarks ["BTC" "ETH" "SOL" "DOGE" "EXTRA"]}
                    :local-state {:portfolio-returns-benchmark-search "eth"}}}
        short-state (assoc-in state
                              [:ui :preferences :portfolio-returns-benchmarks]
                              ["BTC"])
        options [{:value "BTC" :label "BTC Perp"}
                 {:value "ETH" :label "ETH Perp"}
                 {:value "SOL" :label "SOL Perp"}]]
    (is (= ["BTC" "ETH" "SOL" "DOGE"]
           (vm-benchmarks/selected-returns-benchmark-coins state)))
    (is (= ["BTC" "ETH"]
           (vm-benchmarks/selected-returns-benchmark-coins {})))
    (is (= [{:value "ETH" :label "ETH Perp"}
            {:value "SOL" :label "SOL Perp"}]
           (vm-benchmarks/selected-benchmark-options options ["ETH" "MISSING" "SOL"])))
    (let [model-max (vm-benchmarks/returns-benchmark-selector-model state)
          model-search (vm-benchmarks/returns-benchmark-selector-model short-state)]
      (is (true? (:max-reached? model-max)))
      (is (= [] (:available-options model-max)))
      (is (= "eth" (:search-query model-search)))
      (is (= ["ETH"]
             (mapv :value (:available-options model-search)))))
    (let [build-calls (atom 0)
          markets [{:coin "BTC" :type "PERP" :openInterest "300"}]
          vaults [{:isVault true :vaultAddress "0xabc" :name "Alpha" :tvl 10}]]
      (with-redefs [vm-benchmarks/*build-benchmark-selector-options*
                    (fn [active-markets eligible-vaults]
                      (swap! build-calls inc)
                      [{:value (str (count active-markets) "-" (count eligible-vaults))}])]
        (vm-benchmarks/memoized-eligible-vault-benchmark-rows vaults)
        (let [first-rows (vm-benchmarks/memoized-eligible-vault-benchmark-rows vaults)
              second-rows (vm-benchmarks/memoized-eligible-vault-benchmark-rows vaults)]
          (is (identical? first-rows second-rows)))
        (vm-benchmarks/memoized-benchmark-selector-options markets vaults)
        (vm-benchmarks/memoized-benchmark-selector-options markets vaults)
        (let [build-count-before @build-calls]
          (is (= [{:value "1-1"}]
                 (vm-benchmarks/memoized-benchmark-selector-options markets vaults)))
          (is (= build-count-before @build-calls)))
        (vm-benchmarks/memoized-benchmark-selector-options
         (conj markets {:coin "ETH" :type "PERP" :openInterest "200"})
         vaults)
        (is (> @build-calls 0))
        (is (vector? (vm-benchmarks/benchmark-selector-options state)))))))
