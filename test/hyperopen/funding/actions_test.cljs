(ns hyperopen.funding.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.actions :as funding-actions]))

(defn- base-state
  []
  {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
   :spot {:clearinghouse-state {:balances [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}]}}
   :webdata2 {:clearinghouseState {:availableToWithdraw "8.5"
                                   :marginSummary {:accountValue "20"
                                                   :totalMarginUsed "11.5"}}}
   :funding-ui {:modal (funding-actions/default-funding-modal-state)}})

(defn- expected-open-modal
  [mode & {:keys [legacy-kind] :or {legacy-kind nil}}]
  {:open? true
   :mode mode
   :legacy-kind legacy-kind
   :deposit-step :asset-select
   :deposit-search-input ""
   :deposit-selected-asset-key nil
   :deposit-generated-address nil
   :deposit-generated-signatures nil
   :deposit-generated-asset-key nil
   :amount-input ""
   :to-perp? true
   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
   :hyperunit-lifecycle (funding-actions/default-hyperunit-lifecycle-state)
   :submitting? false
   :error nil})

(deftest open-funding-modal-actions-set-mode-and-open-state-test
  (let [state (base-state)]
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :deposit)]]
           (funding-actions/open-funding-deposit-modal state)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :transfer)]]
           (funding-actions/open-funding-transfer-modal state)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :withdraw)]]
           (funding-actions/open-funding-withdraw-modal state)))))

(deftest set-funding-modal-compat-preserves-legacy-fallback-test
  (let [state (base-state)]
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :transfer)]]
           (funding-actions/set-funding-modal-compat state :send)))
    (is (= [[:effects/save [:funding-ui :modal]
             (expected-open-modal :legacy :legacy-kind :history)]]
           (funding-actions/set-funding-modal-compat state :history)))))

(deftest submit-funding-transfer-validates-and-emits-api-effect-test
  (let [state-invalid (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :transfer
                                 :to-perp? true
                                 :amount-input ""})
        state-valid (assoc-in (base-state)
                              [:funding-ui :modal]
                              {:open? true
                               :mode :transfer
                               :to-perp? false
                               :amount-input "2.25"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Enter a valid amount."]]]]
           (funding-actions/submit-funding-transfer state-invalid)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-transfer
             {:action {:type "usdClassTransfer"
                       :amount "2.25"
                       :toPerp false}}]]
           (funding-actions/submit-funding-transfer state-valid)))))

(deftest submit-funding-withdraw-validates-destination-and-minimum-test
  (let [invalid-destination (assoc-in (base-state)
                                      [:funding-ui :modal]
                                      {:open? true
                                       :mode :withdraw
                                       :destination-input "abc"
                                       :amount-input "10"})
        below-minimum (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :withdraw
                                 :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                                 :amount-input "1"})
        valid (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :withdraw
                         :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                         :amount-input "6.5"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Enter a valid destination address."]]]]
           (funding-actions/submit-funding-withdraw invalid-destination)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Minimum withdrawal is 5 USDC."]]]]
           (funding-actions/submit-funding-withdraw below-minimum)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-withdraw
             {:action {:type "withdraw3"
                       :amount "6.5"
                       :destination "0x1234567890abcdef1234567890abcdef12345678"}}]]
           (funding-actions/submit-funding-withdraw valid)))))

(deftest submit-funding-deposit-validates-step-asset-and-amount-test
  (let [no-asset (assoc-in (base-state)
                           [:funding-ui :modal]
                           {:open? true
                            :mode :deposit
                            :deposit-step :amount-entry
                            :deposit-selected-asset-key nil
                            :amount-input "10"})
        invalid-amount (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                  :mode :deposit
                                  :deposit-step :amount-entry
                                  :deposit-selected-asset-key :usdc
                                  :amount-input ""})
        below-minimum (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :deposit
                                 :deposit-step :amount-entry
                                 :deposit-selected-asset-key :usdc
                                 :amount-input "1"})
        valid-mainnet (assoc-in (base-state)
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :deposit
                                 :deposit-step :amount-entry
                                 :deposit-selected-asset-key :usdc
                                 :amount-input "5.25"})
        valid-testnet (assoc-in (assoc (base-state)
                                       :wallet {:address "0x1234567890abcdef1234567890abcdef12345678"
                                                :chain-id "0x66eee"})
                                [:funding-ui :modal]
                                {:open? true
                                 :mode :deposit
                                 :deposit-step :amount-entry
                                 :deposit-selected-asset-key :usdc
                                 :amount-input "7"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Select an asset to deposit."]]]]
           (funding-actions/submit-funding-deposit no-asset)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Enter a valid amount."]]]]
           (funding-actions/submit-funding-deposit invalid-amount)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Minimum deposit is 5 USDC."]]]]
           (funding-actions/submit-funding-deposit below-minimum)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "bridge2Deposit"
                       :asset "usdc"
                       :amount "5.25"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit valid-mainnet)))
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "bridge2Deposit"
                       :asset "usdc"
                       :amount "7"
                       :chainId "0x66eee"}}]]
           (funding-actions/submit-funding-deposit valid-testnet)))))

(deftest set-funding-modal-field-selecting-deposit-asset-advances-step-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :asset-select
                         :deposit-selected-asset-key nil
                         :deposit-generated-address "bc1old"
                         :deposit-generated-signatures [{:r "0x1"}]
                         :deposit-generated-asset-key :btc
                         :amount-input "12"})]
    (let [[effect-id path next-modal]
          (first (funding-actions/set-funding-modal-field state
                                                          [:deposit-selected-asset-key]
                                                          :usdc))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= :usdc (:deposit-selected-asset-key next-modal)))
      (is (= :amount-entry (:deposit-step next-modal)))
      (is (= "" (:amount-input next-modal)))
      (is (nil? (:deposit-generated-address next-modal)))
      (is (nil? (:deposit-generated-signatures next-modal)))
      (is (nil? (:deposit-generated-asset-key next-modal))))))

(deftest set-funding-modal-field-amount-input-normalizes-grouping-separators-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdc
                         :amount-input ""})
        [_ _ next-modal]
        (first (funding-actions/set-funding-modal-field state
                                                        [:amount-input]
                                                        "100,000.25"))]
    (is (= "100000.25" (:amount-input next-modal)))))

(deftest set-funding-modal-field-clears-hyperunit-lifecycle-on-critical-input-change-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :amount-input "10"
                         :hyperunit-lifecycle {:direction :deposit
                                               :asset-key :btc
                                               :operation-id "op_123"
                                               :state :source-confirmed
                                               :status :pending
                                               :source-tx-confirmations 1
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash nil
                                               :state-next-at 1000
                                               :last-updated-ms 2000
                                               :error nil}})
        [_ _ amount-modal] (first (funding-actions/set-funding-modal-field
                                   state
                                   [:amount-input]
                                   "11"))
        [_ _ destination-modal] (first (funding-actions/set-funding-modal-field
                                        state
                                        [:destination-input]
                                        "0x1234567890abcdef1234567890abcdef12345678"))]
    (is (= (funding-actions/default-hyperunit-lifecycle-state)
           (:hyperunit-lifecycle amount-modal)))
    (is (= (funding-actions/default-hyperunit-lifecycle-state)
           (:hyperunit-lifecycle destination-modal)))))

(deftest set-hyperunit-lifecycle-normalizes-snapshot-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :hyperunit-lifecycle (funding-actions/default-hyperunit-lifecycle-state)})
        [effect-id path next-modal] (first (funding-actions/set-hyperunit-lifecycle
                                            state
                                            {:direction "deposit"
                                             :asset-key "btc"
                                             :operation-id "op_123"
                                             :state "SOURCE_CONFIRMED"
                                             :status "IN_PROGRESS"
                                             :source-tx-confirmations "2"
                                             :destination-tx-confirmations 0
                                             :position-in-withdraw-queue "5"
                                             :destination-tx-hash "0xabc123"
                                             :state-next-at "1700000000000"
                                             :last-updated-ms 1700000000123
                                             :error "temporary issue"}))]
    (is (= :effects/save effect-id))
    (is (= [:funding-ui :modal] path))
    (is (= {:direction :deposit
            :asset-key :btc
            :operation-id "op_123"
            :state :source-confirmed
            :status :in-progress
            :source-tx-confirmations 2
            :destination-tx-confirmations 0
            :position-in-withdraw-queue 5
            :destination-tx-hash "0xabc123"
            :state-next-at 1700000000000
            :last-updated-ms 1700000000123
            :error "temporary issue"}
           (:hyperunit-lifecycle next-modal)))))

(deftest clear-hyperunit-lifecycle-resets-to-default-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :hyperunit-lifecycle {:direction :deposit
                                               :asset-key :btc
                                               :operation-id "op_123"
                                               :state :done
                                               :status :done
                                               :source-tx-confirmations 6
                                               :destination-tx-confirmations 1
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash "0xabc"
                                               :state-next-at nil
                                               :last-updated-ms 1700
                                               :error nil}})
        [_ _ next-modal] (first (funding-actions/clear-hyperunit-lifecycle state))]
    (is (= (funding-actions/default-hyperunit-lifecycle-state)
           (:hyperunit-lifecycle next-modal)))))

(deftest set-hyperunit-lifecycle-error-updates-error-field-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :hyperunit-lifecycle {:direction :deposit
                                               :asset-key :btc
                                               :operation-id "op_123"
                                               :state :source-confirmed
                                               :status :pending
                                               :source-tx-confirmations 1
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash nil
                                               :state-next-at nil
                                               :last-updated-ms 1700
                                               :error nil}})
        [_ _ next-modal] (first (funding-actions/set-hyperunit-lifecycle-error
                                 state
                                 "  temporary API issue  "))]
    (is (= "temporary API issue"
           (get-in next-modal [:hyperunit-lifecycle :error])))))

(deftest submit-funding-deposit-accepts-comma-grouped-amount-input-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdc
                         :amount-input "100,000"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "bridge2Deposit"
                       :asset "usdc"
                       :amount "100000"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-usdt-route-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdt
                         :amount-input "10"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                       :asset "usdt"
                       :amount "10"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-usdh-route-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :usdh
                         :amount-input "10"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "acrossUsdcToUsdhDeposit"
                       :asset "usdh"
                       :amount "10"
                       :chainId "0xa4b1"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-btc-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "btc"
                       :fromChain "bitcoin"
                       :network "Bitcoin"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-eth-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :eth
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "eth"
                       :fromChain "ethereum"
                       :network "Ethereum"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-sol-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :sol
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "sol"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-2z-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :2z
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "2z"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-bonk-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :bonk
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "bonk"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-ena-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :ena
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "ena"
                       :fromChain "ethereum"
                       :network "Ethereum"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-fart-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :fart
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "fart"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-mon-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :mon
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "mon"
                       :fromChain "monad"
                       :network "Monad"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-pump-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :pump
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "pump"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-spxs-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :spxs
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "spxs"
                       :fromChain "solana"
                       :network "Solana"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-supports-xpl-hyperunit-address-flow-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :xpl
                         :amount-input ""})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] true]
                                 [[:funding-ui :modal :error] nil]]]
            [:effects/api-submit-funding-deposit
             {:action {:type "hyperunitGenerateDepositAddress"
                       :asset "xpl"
                       :fromChain "plasma"
                       :network "Plasma"}}]]
           (funding-actions/submit-funding-deposit state)))))

(deftest submit-funding-deposit-rejects-unknown-asset-selection-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :does-not-exist
                         :amount-input "10"})]
    (is (= [[:effects/save-many [[[:funding-ui :modal :submitting?] false]
                                 [[:funding-ui :modal :error] "Select an asset to deposit."]]]]
           (funding-actions/submit-funding-deposit state)))))

(deftest funding-modal-view-model-includes-multi-asset-deposit-catalog-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :asset-select
                         :deposit-search-input ""
                         :deposit-selected-asset-key nil
                         :amount-input ""})
        view-model (funding-actions/funding-modal-view-model state)
        asset-keys (set (map :key (:deposit-assets view-model)))]
    (is (contains? asset-keys :usdc))
    (is (contains? asset-keys :usdt))
    (is (contains? asset-keys :btc))
    (is (contains? asset-keys :xpl))
    (is (contains? asset-keys :usdh))))

(deftest funding-modal-view-model-exposes-normalized-hyperunit-lifecycle-test
  (let [state (assoc-in (base-state)
                        [:funding-ui :modal]
                        {:open? true
                         :mode :deposit
                         :deposit-step :amount-entry
                         :deposit-selected-asset-key :btc
                         :hyperunit-lifecycle {:direction "deposit"
                                               :asset-key "btc"
                                               :operation-id "op_123"
                                               :state "source_confirmed"
                                               :status "in_progress"
                                               :source-tx-confirmations "2"
                                               :destination-tx-confirmations nil
                                               :position-in-withdraw-queue nil
                                               :destination-tx-hash "0xabc"
                                               :state-next-at "1700000000000"
                                               :last-updated-ms "1700000001000"
                                               :error nil}})
        view-model (funding-actions/funding-modal-view-model state)]
    (is (= {:direction :deposit
            :asset-key :btc
            :operation-id "op_123"
            :state :source-confirmed
            :status :in-progress
            :source-tx-confirmations 2
            :destination-tx-confirmations nil
            :position-in-withdraw-queue nil
            :destination-tx-hash "0xabc"
            :state-next-at 1700000000000
            :last-updated-ms 1700000001000
            :error nil}
           (:hyperunit-lifecycle view-model)))))

(deftest set-funding-amount-to-max-respects-active-mode-test
  (let [transfer-state (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                  :mode :transfer
                                  :to-perp? true
                                  :amount-input ""})
        withdraw-state (assoc-in (base-state)
                                 [:funding-ui :modal]
                                 {:open? true
                                  :mode :withdraw
                                  :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                                  :amount-input ""})]
    (let [[effect-id path next-modal] (first (funding-actions/set-funding-amount-to-max transfer-state))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "12.5" (:amount-input next-modal)))
      (is (nil? (:error next-modal))))
    (let [[effect-id path next-modal] (first (funding-actions/set-funding-amount-to-max withdraw-state))]
      (is (= :effects/save effect-id))
      (is (= [:funding-ui :modal] path))
      (is (= "8.5" (:amount-input next-modal)))
      (is (nil? (:error next-modal))))))
