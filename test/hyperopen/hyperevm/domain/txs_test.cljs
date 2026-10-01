(ns hyperopen.hyperevm.domain.txs-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.hyperevm.domain.fees :as fees]
            [hyperopen.hyperevm.domain.txs :as txs]))

(def ^:private owner "0x1234567890abcdef1234567890abcdef12345678")
(def ^:private hype-system "0x2222222222222222222222222222222222222222")
(def ^:private purr-system "0x2000000000000000000000000000000000000001")
(def ^:private purr-contract "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e")
(def ^:private usdc-token "0xb88339cb7199b77e23db6e890353e22632ba630f")
(def ^:private deposit-wallet "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24")

(def ^:private native-action
  {:kind "native" :recipient hype-system :units "10000000000000000000"})

(def ^:private erc20-action
  {:kind "erc20" :tokenAddress purr-contract :recipient purr-system :units "100000000000000000000"})

(def ^:private usdc-action
  {:kind "usdcCoreDeposit" :tokenAddress usdc-token :spender deposit-wallet
   :recipient nil :units "1000000000" :destinationDex 4294967295})

(deftest native-hype-is-a-pinned-value-transfer-to-its-system-address-test
  (is (= {:from owner :to hype-system :value "0x8ac7230489e80000" :chainId "0x3e7"}
         (txs/native-to-core-tx owner native-action)))
  (is (nil? (txs/native-to-core-tx owner (assoc native-action :recipient purr-system)))
      "native HYPE only ever goes to 0x2222…")
  (is (nil? (txs/native-to-core-tx owner (assoc native-action :units "0"))))
  (is (nil? (txs/native-to-core-tx "0xnope" native-action))))

(deftest erc20-transfers-to-its-own-system-address-test
  (is (= {:from owner
          :to purr-contract
          :data (str "0xa9059cbb"
                     "0000000000000000000000002000000000000000000000000000000000000001"
                     "0000000000000000000000000000000000000000000000056bc75e2d63100000")
          :chainId "0x3e7"}
         (txs/erc20-to-core-tx owner erc20-action)))
  (is (nil? (txs/erc20-to-core-tx owner (assoc erc20-action :recipient "0x20")))))

(deftest usdc-approves-the-exact-amount-then-deposits-to-spot-test
  ;; approve(0x6b9e…0a24, 1000000000) on 0xb883…630f, then
  ;; deposit(1000000000, 4294967295) on 0x6b9e…0a24.
  (is (= {:from owner
          :to usdc-token
          :data (str "0x095ea7b3"
                     "0000000000000000000000006b9e773128f453f5c2c60935ee2de2cbc5390a24"
                     "000000000000000000000000000000000000000000000000000000003b9aca00")
          :chainId "0x3e7"}
         (txs/usdc-approve-tx owner usdc-action)))
  (is (= {:from owner
          :to deposit-wallet
          :data (str "0x2b2dfd2c"
                     "000000000000000000000000000000000000000000000000000000003b9aca00"
                     "00000000000000000000000000000000000000000000000000000000ffffffff")
          :chainId "0x3e7"}
         (txs/usdc-core-deposit-tx owner usdc-action))))

(deftest transfer-plans-follow-the-token-kind-with-no-default-test
  (is (= [[:send :native false]]
         (mapv (juxt :step :gas-kind (comp boolean :optional?)) (txs/transfer-plan owner native-action))))
  (is (= [[:send :erc20 false]]
         (mapv (juxt :step :gas-kind (comp boolean :optional?)) (txs/transfer-plan owner erc20-action))))
  (is (= [[:approve :usdc-approve true] [:deposit :usdc-deposit false]]
         (mapv (juxt :step :gas-kind (comp boolean :optional?)) (txs/transfer-plan owner usdc-action))))
  (is (every? #(= "0x3e7" (get-in % [:tx :chainId]))
              (mapcat #(txs/transfer-plan owner %) [native-action erc20-action usdc-action])))
  (is (thrown? js/Error (txs/transfer-plan owner (assoc native-action :kind "mystery")))))

(deftest pricing-sets-explicit-eip-1559-fields-and-never-gas-price-test
  (let [tx (txs/native-to-core-tx owner native-action)]
    (is (= (assoc tx :gas "0x6aa4" :maxFeePerGas "0x17d78400" :maxPriorityFeePerGas "0x0")
           (txs/priced-tx tx :native "21000" "100000000"))
        "21000 x 1.3 gas; 0.1 gwei is floored to 0.2 gwei, then doubled")
    (is (= "0x7530" (:gas (txs/priced-tx tx :native nil "100000000")))
        "no estimate falls back to the native table")
    (is (= "0x2540be400" (:maxFeePerGas (txs/priced-tx tx :native "21000" "5000000000")))
        "2 x a 5 gwei gas price")
    (is (not (contains? (txs/priced-tx tx :native "21000" "100000000") :gasPrice)))
    (is (nil? (txs/priced-tx tx :native "21000" nil)) "an unknown gas price prices nothing")
    (is (= {:from owner :to hype-system :value "0x8ac7230489e80000"}
           (txs/estimate-request (txs/priced-tx tx :native "21000" "100000000"))))))

(deftest gas-limits-and-max-fees-test
  (is (= "27300" (.toString (fees/gas-limit "21000" :native))))
  (is (= "27302" (.toString (fees/gas-limit "21001" :native))) "27301.3 rounds up")
  (is (= "90000" (.toString (fees/gas-limit "0" :usdc-deposit))))
  (is (= "60000" (.toString (fees/gas-limit "garbage" :erc20))))
  (is (nil? (fees/gas-limit nil :unknown)))
  (is (= "400000000" (.toString (fees/max-fee-per-gas-wei "1"))))
  (is (nil? (fees/max-fee-per-gas-wei nil))))

(deftest allowance-coverage-test
  (is (txs/allowance-covers? "1000000000" "1000000000"))
  (is (txs/allowance-covers? "2000000000" "1000000000"))
  (is (not (txs/allowance-covers? "999999999" "1000000000")))
  (is (not (txs/allowance-covers? nil "1000000000"))))
