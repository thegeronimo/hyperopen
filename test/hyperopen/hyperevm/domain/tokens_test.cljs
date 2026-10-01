(ns hyperopen.hyperevm.domain.tokens-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.account-ledger.derive :as ledger-derive]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.test-support.fixtures :as fixtures]))

(def ^:private expected-usdc
  {:index 0
   :name "USDC"
   :token-id "0x6d1e7cde53ba9467b783cb7c530ce054"
   :wire-id "USDC:0x6d1e7cde53ba9467b783cb7c530ce054"
   :wei-decimals 8
   :evm-decimals 6
   :core-precision 6
   :kind :usdc-cdw
   :erc20-address "0xb88339cb7199b77e23db6e890353e22632ba630f"
   :spender "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24"
   :system-address "0x2000000000000000000000000000000000000000"
   :evm->core-recipient nil
   :verified? true})

(def ^:private expected-purr
  {:index 1
   :name "PURR"
   :token-id "0xc1fb593aeffbeb02f85e0308e9956a90"
   :wire-id "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"
   :wei-decimals 5
   :evm-decimals 18
   :core-precision 5
   :kind :erc20
   :erc20-address "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
   :spender nil
   :system-address "0x2000000000000000000000000000000000000001"
   :evm->core-recipient "0x2000000000000000000000000000000000000001"
   :verified? true})

(def ^:private expected-hope
  {:index 122
   :name "HOPE"
   :token-id "0xe6ac9d5a7cf91cdd9fdf34fcbbdd58e9"
   :wire-id "HOPE:0xe6ac9d5a7cf91cdd9fdf34fcbbdd58e9"
   :wei-decimals 5
   :evm-decimals 5
   :core-precision 5
   :kind :erc20
   :erc20-address "0x869ac826b78bc1d9501014994196e41025b5224b"
   :spender nil
   :system-address "0x200000000000000000000000000000000000007a"
   :evm->core-recipient "0x200000000000000000000000000000000000007a"
   :verified? false})

(def ^:private expected-hype
  {:index 150
   :name "HYPE"
   :token-id "0x0d01dc56dcaaca66ad901c959b4011ec"
   :wire-id "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec"
   :wei-decimals 8
   :evm-decimals 18
   :core-precision 8
   :kind :native
   :erc20-address nil
   :spender nil
   :system-address "0x2222222222222222222222222222222222222222"
   :evm->core-recipient "0x2222222222222222222222222222222222222222"
   :verified? true})

(def ^:private expected-ubtc
  {:index 197
   :name "UBTC"
   :token-id "0x8f254b963e8468305d409b33aa137c67"
   :wire-id "UBTC:0x8f254b963e8468305d409b33aa137c67"
   :wei-decimals 10
   :evm-decimals 8
   :core-precision 8
   :kind :erc20
   :erc20-address "0x9fdbda0a5e284c32744d2f17ee5c74b284993463"
   :spender nil
   :system-address "0x20000000000000000000000000000000000000c5"
   :evm->core-recipient "0x20000000000000000000000000000000000000c5"
   :verified? false})

(def ^:private expected-funt
  {:index 478
   :name "FUNT"
   :token-id "0x1aaa916f86510ab37b20fbffeb9c05bb"
   :wire-id "FUNT:0x1aaa916f86510ab37b20fbffeb9c05bb"
   :wei-decimals 6
   :evm-decimals 8
   :core-precision 6
   :kind :erc20
   :erc20-address "0xd6f92d754818307d0e2853eada247178f1ae605b"
   :spender nil
   :system-address "0x20000000000000000000000000000000000001de"
   :evm->core-recipient "0x20000000000000000000000000000000000001de"
   :verified? false})

(deftest linked-tokens-builds-the-catalog-from-live-mainnet-rows-test
  (is (= [expected-usdc expected-purr expected-hope expected-hype expected-ubtc expected-funt]
         (tokens/linked-tokens fixtures/mainnet-spot-meta))))

(deftest hype-is-linked-although-mainnet-reports-no-evm-contract-test
  (is (nil? (:evmContract fixtures/hype-row)) "precondition: live mainnet shape")
  (let [hype (tokens/token-by-name fixtures/mainnet-spot-meta "HYPE")]
    (is (= :native (:kind hype)))
    (is (= 18 (:evm-decimals hype)))
    (is (= "0x2222222222222222222222222222222222222222" (:system-address hype)))
    (is (not= (tokens/system-address 150) (:system-address hype))
        "the generic 0x20… rule would send HYPE to the wrong address")))

(deftest usdc-reads-native-usdc-and-approves-the-core-deposit-wallet-test
  (let [usdc (tokens/token-by-index fixtures/mainnet-spot-meta 0)
        contract (get-in fixtures/usdc-row [:evmContract :address])]
    (is (= contract (:spender usdc)))
    (is (not= contract (:erc20-address usdc))
        "balanceOf on the CoreDepositWallet reverts")
    (is (= (:usdc-token-address chain/mainnet) (:erc20-address usdc)))
    (is (= (+ (:weiDecimals fixtures/usdc-row)
              (get-in fixtures/usdc-row [:evmContract :evm_extra_wei_decimals]))
           (:evm-decimals usdc))
        "6 matches weiDecimals + evm_extra_wei_decimals")))

(deftest usdc-has-no-evm-side-transfer-recipient-test
  ;; Core->EVM USDC is a sendAsset to 0x2000…0000, but EVM->Core USDC must go
  ;; through CoreDepositWallet.deposit: native USDC transferred to 0x2000…0000
  ;; is not credited. The catalog must not hand EVM-side code that address.
  (let [linked (tokens/linked-tokens fixtures/mainnet-spot-meta)
        usdc (tokens/token-by-index fixtures/mainnet-spot-meta 0)]
    (is (= "0x2000000000000000000000000000000000000000" (:system-address usdc))
        "the Core->EVM sendAsset destination")
    (is (nil? (:evm->core-recipient usdc)))
    (is (= [:usdc-cdw] (->> linked (remove :evm->core-recipient) (mapv :kind)))
        "every other linked token has an EVM->Core recipient")
    (doseq [token linked
            :when (= :erc20 (:kind token))]
      (is (= (:system-address token) (:evm->core-recipient token))))
    (is (= (:hype-system-address chain/mainnet)
           (:evm->core-recipient (tokens/token-by-name fixtures/mainnet-spot-meta "HYPE"))))))

(deftest system-addresses-come-from-index-not-array-position-test
  (let [position (.indexOf (to-array (:tokens fixtures/mainnet-spot-meta)) fixtures/funt-row)
        funt (tokens/token-by-name fixtures/mainnet-spot-meta "FUNT")]
    (is (= 5 position))
    (is (not= position (:index funt)))
    (is (not= fixtures/funt-live-array-position (:index funt)))
    (is (= (tokens/system-address 478) (:system-address funt)))
    (is (= "0x20000000000000000000000000000000000001de" (:system-address funt)))))

(deftest evm-decimals-and-core-precision-follow-the-extra-decimals-test
  (testing "positive extra: Core precision is the Core weiDecimals"
    (is (= [18 5] ((juxt :evm-decimals :core-precision)
                   (tokens/token-by-name fixtures/mainnet-spot-meta "PURR")))))
  (testing "negative extra: Core precision is the smaller EVM decimals"
    (is (= [8 8] ((juxt :evm-decimals :core-precision)
                  (tokens/token-by-name fixtures/mainnet-spot-meta "UBTC"))))))

(deftest only-hype-purr-and-usdc-are-verified-test
  (is (= #{"HYPE" "PURR" "USDC"}
         (->> (tokens/linked-tokens fixtures/mainnet-spot-meta)
              (filter :verified?)
              (map :name)
              set))))

(deftest testnet-shapes-resolve-against-the-testnet-chain-test
  (let [[usdc hype] (tokens/linked-tokens fixtures/testnet-spot-meta chain/testnet)]
    (is (= :native (:kind hype)) "testnet reports HYPE with the zero address")
    (is (= 1105 (:index hype)))
    (is (= 18 (:evm-decimals hype)))
    (is (nil? (:erc20-address hype)))
    (is (= "0x2222222222222222222222222222222222222222" (:system-address hype)))
    (is (= "0x2b3370ee501b4a559b57d449569354196457d8ab" (:erc20-address usdc)))
    (is (= "0x0b80659a4076e9e93c7dbe0f10675a16a3e5c206" (:spender usdc)))))

(deftest usdc-is-dropped-when-spot-meta-and-chain-disagree-test
  ;; USDC's spender receives an ERC-20 allowance, so a CoreDepositWallet that
  ;; is not the chain's own (mainnet spotMeta read against testnet config, or
  ;; the reverse) must never become one.
  (is (nil? (tokens/token-by-index fixtures/mainnet-spot-meta chain/testnet 0)))
  (is (nil? (tokens/token-by-index fixtures/testnet-spot-meta chain/mainnet 0)))
  (is (= :native (:kind (tokens/token-by-name fixtures/mainnet-spot-meta chain/testnet "HYPE")))))

(deftest unusable-rows-are-excluded-test
  (let [meta* (fn [& rows] {:tokens (vec rows)})]
    (testing "negative EVM decimals"
      (is (= []
             (tokens/linked-tokens
              (meta* (assoc-in fixtures/ubtc-row [:evmContract :evm_extra_wei_decimals] -11))))))
    (testing "more than 36 EVM decimals"
      (is (= []
             (tokens/linked-tokens
              (meta* (assoc-in fixtures/purr-row [:evmContract :evm_extra_wei_decimals] 32))))))
    (testing "an unlinked token (evmContract null) other than HYPE"
      (is (= [] (tokens/linked-tokens (meta* (assoc fixtures/purr-row :evmContract nil))))))
    (testing "a malformed contract address"
      (is (= [] (tokens/linked-tokens
                 (meta* (assoc-in fixtures/purr-row [:evmContract :address] "0x9b49"))))))
    (testing "missing metadata"
      (is (= [] (tokens/linked-tokens nil)))
      (is (= [] (tokens/linked-tokens {})))
      (is (= [] (tokens/linked-tokens {:tokens "not-a-vector"}))))))

(deftest string-keyed-spot-meta-is-accepted-test
  (let [string-keyed (js->clj (clj->js fixtures/mainnet-spot-meta))]
    (is (= (tokens/linked-tokens fixtures/mainnet-spot-meta)
           (tokens/linked-tokens string-keyed)))))

(deftest linked-tokens-memoizes-on-identical-spot-meta-test
  (let [first-call (tokens/linked-tokens fixtures/mainnet-spot-meta)]
    (is (identical? first-call (tokens/linked-tokens fixtures/mainnet-spot-meta)))
    (let [equal-copy (update fixtures/mainnet-spot-meta :tokens vec)
          rebuilt (tokens/linked-tokens (assoc equal-copy :marker true))]
      (is (= first-call rebuilt))
      (is (not (identical? first-call rebuilt))))))

(deftest token-lookups-test
  (is (= expected-purr (tokens/token-by-index fixtures/mainnet-spot-meta 1)))
  (is (= expected-purr (tokens/token-by-name fixtures/mainnet-spot-meta "purr")))
  (is (= expected-purr (tokens/token-by-name fixtures/mainnet-spot-meta " PURR ")))
  (is (nil? (tokens/token-by-index fixtures/mainnet-spot-meta 2)))
  (is (nil? (tokens/token-by-name fixtures/mainnet-spot-meta "NOPE")))
  (is (nil? (tokens/token-by-name fixtures/mainnet-spot-meta nil))))

(deftest system-address-rule-test
  (is (= "0x2000000000000000000000000000000000000000" (tokens/system-address 0)))
  (is (= "0x2000000000000000000000000000000000000001" (tokens/system-address 1)))
  (is (= "0x2000000000000000000000000000000000000096" (tokens/system-address 150)))
  (is (= "0x200000000000000000000000000000000000ffff" (tokens/system-address 0xffff)))
  (doseq [value [-1 1.5 "1" nil]]
    (is (nil? (tokens/system-address value)))))

(deftest system-address-agrees-with-the-ledger-recognizer-test
  ;; hyperopen.domain.account-ledger.derive labels HyperEVM bridge rows by
  ;; recognising these addresses; the builder and recognizer must agree.
  (doseq [index [0 1 122 150 197 478 0xffff]]
    (testing index
      (is (true? (ledger-derive/token-system-address? (tokens/system-address index))))))
  (doseq [token (tokens/linked-tokens fixtures/mainnet-spot-meta)]
    (testing (:name token)
      (is (true? (ledger-derive/token-system-address? (:system-address token))))))
  (is (= (:hype-system-address chain/mainnet) ledger-derive/hype-system-address)
      "the ledger's HYPE system address is the chain config's")
  (is (false? (ledger-derive/token-system-address?
               "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"))
      "an ordinary contract address is not a system address"))

(deftest system-address-predicate-matches-the-ledger-recognizer-test
  ;; `tokens/system-address?` guards signing (the pre-signing invariant) and
  ;; must accept and refuse exactly what the ledger's recognizer does.
  (doseq [address (concat
                   (map tokens/system-address [0 1 122 150 197 478 0xffff 0x10000])
                   ["0x2222222222222222222222222222222222222222"
                    "0X2000000000000000000000000000000000000001"
                    "0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e"
                    "0x20a0000000000000000000000000000000000001"
                    "0x2000000000000000000000000000000000000000ff"
                    "0x2000"
                    "0x2222222222222222222222222222222222222223"
                    ""
                    nil])]
    (testing address
      (is (= (ledger-derive/token-system-address? address)
             (tokens/system-address? address))))))
