(ns hyperopen.hyperevm.domain.fees-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.test-support.fixtures :as fixtures]))

(def ^:private purr
  (tokens/token-by-name fixtures/mainnet-spot-meta "PURR"))

(def ^:private usdc
  (tokens/token-by-name fixtures/mainnet-spot-meta "USDC"))

(def ^:private hype
  (tokens/token-by-name fixtures/mainnet-spot-meta "HYPE"))

(deftest core-to-evm-fee-matches-live-ledger-fees-test
  ;; Two live Core->EVM ledger fees on 2026-09-30 against the base fee of the
  ;; next small block: 0.10371 gwei -> 0.00002075 HYPE, 0.11533 gwei ->
  ;; 0.00002307 HYPE. Both are 200000 x base fee, rounded UP to 8 decimals.
  (is (= "0.00002075" (fees/core->evm-fee-hype "103710000" purr)))
  (is (= "0.00002307" (fees/core->evm-fee-hype "115330000" usdc))))

(deftest core-to-evm-fee-rounding-test
  (testing "the live eth_gasPrice from the RPC probe, decimal or hex"
    ;; 200000 x 107410883 wei = 0.0000214821766 HYPE -> 0.00002149
    (is (= "0.00002149" (fees/core->evm-fee-hype fixtures/probe-gas-price-wei purr)))
    (is (= "0.00002149" (fees/core->evm-fee-hype fixtures/probe-gas-price-hex purr))))
  (testing "an exact 8-decimal fee is not bumped"
    (is (= "0.00002" (fees/core->evm-fee-hype "100000000" purr)))
    (is (= "0.00001" (fees/core->evm-fee-hype "50000000" purr))))
  (testing "one wei over an exact fee rounds up one Core unit"
    (is (= "0.00002001" (fees/core->evm-fee-hype "100000001" purr)))))

(deftest core-to-evm-fee-is-zero-for-hype-test
  (is (= "0" (fees/core->evm-fee-hype "103710000" hype)))
  (is (= "0" (fees/core->evm-fee-hype nil hype)) "HYPE needs no gas price at all"))

(deftest core-to-evm-fee-unknown-gas-price-test
  (doseq [value [nil "" "abc" "-1" "1.5"]]
    (is (nil? (fees/core->evm-fee-hype value purr)))))

(deftest evm-gas-limit-fallback-table-test
  (is (= 30000 (fees/evm-gas-limit :native)))
  (is (= 60000 (fees/evm-gas-limit :erc20)))
  (is (= 70000 (fees/evm-gas-limit :usdc-approve)))
  (is (= 90000 (fees/evm-gas-limit :usdc-deposit)))
  (is (nil? (fees/evm-gas-limit :unknown))))

(deftest evm-tx-cost-applies-the-fee-multiplier-and-floor-test
  (testing "a gas price below 0.2 gwei is charged at the floor"
    ;; 30000 x 2 x 0.2 gwei
    (is (= "0.000012" (fees/evm-tx-cost-hype fixtures/probe-gas-price-wei [:native])))
    ;; (70000 + 90000) x 2 x 0.2 gwei
    (is (= "0.000064" (fees/evm-tx-cost-hype "100000000" [:usdc-approve :usdc-deposit]))))
  (testing "above the floor, the quoted price is doubled"
    ;; 30000 x 2 x 3.47 gwei, the largest base fee seen live
    (is (= "0.0002082" (fees/evm-tx-cost-hype "3470000000" [:native]))))
  (testing "unknown inputs"
    (is (nil? (fees/evm-tx-cost-hype nil [:native])))
    (is (nil? (fees/evm-tx-cost-hype "100000000" [:native :bogus])))
    (is (= "0" (fees/evm-tx-cost-hype "100000000" [])))))

(deftest native-max-reserve-test
  (is (= "0.001" (fees/native-max-reserve-hype fixtures/probe-gas-price-wei)))
  (is (= "0.001" (fees/native-max-reserve-hype nil))
      "unknown gas price falls back to the floor")
  ;; 30000 x 2 x 20 gwei = 0.0012 HYPE exceeds the floor
  (is (= "0.0012" (fees/native-max-reserve-hype "20000000000"))))

(deftest evm-gas-status-test
  (is (= :none (fees/evm-gas-status "0" "0.000012")))
  (is (= :none (fees/evm-gas-status "0.0" nil)))
  (is (= :low (fees/evm-gas-status "0.00001" "0.000012")))
  (is (= :ok (fees/evm-gas-status "0.000012" "0.000012")))
  (is (= :ok (fees/evm-gas-status "12.5" "0.000064")))
  (is (= :ok (fees/evm-gas-status "1" nil)) "unknown need, positive balance")
  (is (nil? (fees/evm-gas-status nil "0.000012")) "an unread balance is not :none")
  (is (nil? (fees/evm-gas-status "abc" "0.000012"))))
