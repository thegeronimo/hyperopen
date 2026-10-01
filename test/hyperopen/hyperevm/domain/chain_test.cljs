(ns hyperopen.hyperevm.domain.chain-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.hyperevm.domain.chain :as chain]))

(def ^:private address-keys
  [:multicall3-address
   :hype-system-address
   :usdc-token-address
   :usdc-core-deposit-wallet])

(deftest mainnet-constants-match-live-verification-test
  (is (= {:chain-id "0x3e7"
          :chain-id-decimal 999
          :chain-name "HyperEVM"
          :native-currency {:name "HYPE" :symbol "HYPE" :decimals 18}
          :rpc-url "https://rpc.hyperliquid.xyz/evm"
          :explorer-url "https://hyperevmscan.io"
          :multicall3-address "0xca11bde05977b3631167028862be2a173976ca11"
          :hype-system-address "0x2222222222222222222222222222222222222222"
          :usdc-token-address "0xb88339cb7199b77e23db6e890353e22632ba630f"
          :usdc-core-deposit-wallet "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24"
          :core->evm-system-gas 200000
          :core-deposit-destination-dex {:perps 0 :spot 4294967295}}
         chain/mainnet)))

(deftest testnet-is-a-distinct-fixture-network-test
  (is (= "0x3e6" (:chain-id chain/testnet)))
  (is (= 998 (:chain-id-decimal chain/testnet)))
  (is (= "https://rpc.hyperliquid-testnet.xyz/evm" (:rpc-url chain/testnet)))
  (is (= "0x2b3370ee501b4a559b57d449569354196457d8ab" (:usdc-token-address chain/testnet)))
  (is (= "0x0b80659a4076e9e93c7dbe0f10675a16a3e5c206" (:usdc-core-deposit-wallet chain/testnet)))
  (is (= (set (keys chain/mainnet)) (set (keys chain/testnet)))
      "fixtures can swap networks without missing keys"))

(deftest chain-ids-are-normalized-lowercase-hex-test
  ;; ensure-wallet-chain! compares the wallet's normalized (lowercase, no
  ;; leading zeros) chain id against :chain-id verbatim.
  (doseq [network [chain/mainnet chain/testnet]]
    (testing (:chain-name network)
      (is (re-matches #"^0x[1-9a-f][0-9a-f]*$" (:chain-id network)))
      (is (= (:chain-id-decimal network)
             (js/parseInt (subs (:chain-id network) 2) 16))))))

(deftest addresses-are-lowercase-and-well-formed-test
  (doseq [network [chain/mainnet chain/testnet]
          k address-keys]
    (testing [(:chain-name network) k]
      (is (re-matches #"^0x[0-9a-f]{40}$" (get network k))))))

(deftest usdc-erc20-is-not-the-core-deposit-wallet-test
  ;; spotMeta's USDC evmContract is the CoreDepositWallet. Reading balanceOf
  ;; on it reverts, and sending USDC to it loses the funds.
  (doseq [network [chain/mainnet chain/testnet]]
    (is (not= (:usdc-token-address network)
              (:usdc-core-deposit-wallet network)))))

(deftest explorer-urls-test
  (is (= "https://hyperevmscan.io/tx/0xabc"
         (chain/explorer-tx-url "0xabc")))
  (is (= "https://hyperevmscan.io/token/0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
         (chain/explorer-token-url "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e")))
  (is (= "https://testnet.purrsec.com/tx/0xabc"
         (chain/explorer-tx-url chain/testnet "0xabc")))
  (is (nil? (chain/explorer-tx-url nil)))
  (is (nil? (chain/explorer-tx-url "")))
  (is (nil? (chain/explorer-token-url 42))))
