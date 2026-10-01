(ns hyperopen.funding.domain.evm-transfer-amounts-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.domain.evm-transfer-amounts :as evm-amounts]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.test-support.bridge-fixtures :as bridge-fixtures]))

(defn- token
  [index]
  (tokens/token-by-index bridge-fixtures/bridge-spot-meta index))

(defn- max-text
  [state route index]
  (evm-amounts/max-amount-text state route (token index) support/now-ms))

(def ^:private spot->evm {:from :spot :to :hyperevm :valid? true})
(def ^:private evm->spot {:from :hyperevm :to :spot :valid? true})

(deftest max-floors-to-the-route-precision-test
  (testing "HYPE keeps all 8 of its Core decimals"
    (let [state (assoc-in (support/state) [:spot :clearinghouse-state :balances]
                          [{:coin "HYPE" :token 150 :total "0.12345678" :hold "0"}])]
      (is (= "0.12345678" (max-text state spot->evm support/hype-index)))))
  (testing "USDC moves Core -> EVM at 6 decimals, floored (never rounded up)"
    (let [state (assoc-in (support/state) [:spot :clearinghouse-state :balances]
                          [{:coin "USDC" :token 0 :total "12.34567891" :hold "0"}])]
      (is (= "12.345678" (max-text state spot->evm support/usdc-index)))))
  (testing "spot availability is total minus hold"
    (let [state (assoc-in (support/state) [:spot :clearinghouse-state :balances]
                          [{:coin "PURR" :token 1 :total "9800" :hold "800.5"}])]
      (is (= "8999.5" (max-text state spot->evm support/purr-index))))))

(deftest max-keeps-a-gas-reserve-on-native-hype-test
  (is (= "12.499" (max-text (support/state) evm->spot support/hype-index)))
  (is (= "0" (max-text (support/with-evm-entry (support/state)
                        (assoc support/evm-entry :native-wei "500000000000000"))
                      evm->spot support/hype-index))
      "a balance below the reserve has nothing to move"))

(deftest max-never-exceeds-the-bridge-capacity-test
  (testing "Core -> EVM is capped by the HyperEVM system address"
    (let [state (assoc-in (support/state) [:hyperevm :bridge :evm-system-units support/purr-index]
                          "1234560000000000000000")]
      (is (= "1234.56" (max-text state spot->evm support/purr-index)))))
  (testing "EVM -> Core is capped by the HyperCore system address"
    (let [state (assoc-in (support/state)
                          [:hyperevm :bridge :core-system-balances support/purr-index]
                          {:amount "7.123456789" :loaded-at-ms support/now-ms})]
      (is (= "7.12345" (max-text state evm->spot support/purr-index)))))
  (testing "an empty bridge side caps MAX at zero"
    (is (= "0" (max-text (support/state) spot->evm support/six-index))))
  (testing "an unknown or stale capacity makes MAX unknown"
    (is (nil? (max-text (assoc-in (support/state) [:hyperevm :bridge :evm-system-units] {})
                        spot->evm support/purr-index)))
    (is (nil? (max-text (assoc-in (support/state)
                                  [:hyperevm :bridge :core-system-balances support/purr-index
                                   :loaded-at-ms]
                                  (- support/now-ms 60000))
                        evm->spot support/purr-index))))
  (testing "USDC and HYPE are never capped by a system balance"
    (is (= "1240" (max-text (support/state) evm->spot support/usdc-index)))))

(deftest max-is-unknown-without-a-balance-read-test
  (is (nil? (max-text (support/with-evm-entry (support/state) nil) evm->spot support/purr-index)))
  (is (nil? (max-text (assoc-in (support/state) [:spot :clearinghouse-state] nil)
                      spot->evm support/purr-index))))

(deftest percent-amounts-floor-a-fraction-of-max-test
  (is (= "3.12475" (evm-amounts/percent-amount-text (support/state) evm->spot
                                                    (token support/hype-index)
                                                    support/now-ms 25)))
  (is (= "4900" (evm-amounts/percent-amount-text (support/state) spot->evm
                                                 (token support/purr-index)
                                                 support/now-ms 50))))

(deftest gas-kinds-match-the-transactions-sent-test
  (is (= [:native] (evm-amounts/gas-kinds (token support/hype-index))))
  (is (= [:erc20] (evm-amounts/gas-kinds (token support/purr-index))))
  (is (= [:usdc-approve :usdc-deposit] (evm-amounts/gas-kinds (token support/usdc-index)))))

(deftest transfer-balances-keep-exact-decimal-strings-test
  (is (= "1,240.00" (transfer-balances/display-amount "1240")))
  (is (= "0.12345678" (transfer-balances/display-amount "0.12345678")))
  (is (= "12.50" (transfer-balances/display-amount "12.5")))
  (is (= "1,234,567.891" (transfer-balances/display-amount "1234567.891")))
  (is (nil? (transfer-balances/display-amount nil)))
  (is (= "50.123455" (transfer-balances/number->text (- 100.123456 50.000001))))
  (is (nil? (transfer-balances/number->text -1)))
  (is (= "262.5" (transfer-balances/add-text "12.5" "250")))
  (is (= "162.08" (transfer-balances/sub-text "412.08" "250")))
  (is (nil? (transfer-balances/sub-text "1" "2")) "never negative")
  (is (= "1" (transfer-balances/min-text "1" "2")))
  (is (= "2" (transfer-balances/min-text nil "2")))
  (testing "a location reports unknown rather than zero before its balances load"
    (is (nil? (transfer-balances/spot-available-text {} (token support/purr-index))))
    (is (= "0" (transfer-balances/spot-available-text
                (support/state) (token 197))))
    (is (nil? (transfer-balances/location-available-text
               (support/state) :perps (token support/purr-index) support/owner))
        "Perps holds USDC only")))

(deftest route-token-reads-the-modal-asset-test
  (is (= "PURR" (:name (transfer-route/route-token (support/state) {:transfer-asset 1}))))
  (is (nil? (transfer-route/route-token (support/state) {:transfer-asset nil}))))
