(ns hyperopen.funding.domain.policy-preview-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.domain.assets :as assets-domain]
            [hyperopen.funding.domain.availability :as availability]
            [hyperopen.funding.domain.policy :as policy]))

(defn- base-state
  []
  {:spot {:clearinghouse-state {:balances [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                                           {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}]}}
   :webdata2 {:clearinghouseState {:availableToWithdraw "8.5"
                                   :marginSummary {:accountValue "20"
                                                   :totalMarginUsed "11.5"}}}})

(deftest withdraw-preview-validates-standard-destination-and-balance-test
  (is (= {:ok? false
          :display-message "Enter a valid destination address."}
         (policy/withdraw-preview (base-state)
                                  {:withdraw-selected-asset-key :usdc
                                   :destination-input "abc"
                                   :amount-input "6.5"})))
  (is (= {:ok? false
          :display-message "Amount exceeds withdrawable balance."}
         (policy/withdraw-preview (base-state)
                                  {:withdraw-selected-asset-key :usdc
                                   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                                   :amount-input "9"}))))

(deftest withdraw-preview-builds-standard-withdraw-request-test
  (is (= {:ok? true
          :request {:action {:type "withdraw3"
                             :amount "6.5"
                             :destination "0x1234567890abcdef1234567890abcdef12345678"}}}
         (policy/withdraw-preview (base-state)
                                  {:withdraw-selected-asset-key :usdc
                                   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                                   :amount-input "6.5"}))))

(deftest withdraw-preview-requires-hyperunit-source-chain-and-preserves-request-shape-test
  (with-redefs [assets-domain/withdraw-assets (fn [_state]
                                                [{:key :btc
                                                  :symbol "BTC"
                                                  :network "Bitcoin"
                                                  :flow-kind :hyperunit-address}])]
    (is (= {:ok? false
            :display-message "Withdrawal source chain is unavailable for BTC."}
           (policy/withdraw-preview (base-state)
                                    {:withdraw-selected-asset-key :btc
                                     :destination-input "bc1qmissingchain"
                                     :amount-input "0.25"}))))
  (is (= {:ok? true
          :request {:action {:type "hyperunitSendAssetWithdraw"
                             :asset "btc"
                             :token "BTC"
                             :amount "0.25"
                             :destination "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                             :destinationChain "bitcoin"
                             :network "Bitcoin"}}}
         (policy/withdraw-preview (base-state)
                                  {:withdraw-selected-asset-key :btc
                                   :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                                   :amount-input "0.25"}))))

(deftest withdraw-preview-reports-no-withdrawable-balance-test
  (is (= {:ok? false
          :display-message "No withdrawable balance available."}
         (policy/withdraw-preview {:spot {:clearinghouse-state {:balances []}}
                                   :webdata2 {:clearinghouseState {:availableToWithdraw "0"}}}
                                  {:withdraw-selected-asset-key :usdc
                                   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                                   :amount-input "1"}))))

(deftest transfer-preview-covers-no-balance-invalid-amount-and-success-branches-test
  (is (= {:ok? false
          :display-message "No spot USDC available to transfer."}
         (policy/transfer-preview {:spot {:clearinghouse-state {:balances []}}}
                                  {:amount-input "1"
                                   :to-perp? true})))
  (is (= {:ok? false
          :display-message "No perps balance available to transfer."}
         (policy/transfer-preview {:spot {:clearinghouse-state {:balances []}}}
                                  {:amount-input "1"
                                   :to-perp? false})))
  (is (= {:ok? false
          :display-message "Enter a valid amount."}
         (policy/transfer-preview {:spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                                            :available "9"
                                                                            :total "9"
                                                                            :hold "0"}]}}}
                                  {:amount-input "bad"
                                   :to-perp? true})))
  (is (= {:ok? true
          :request {:action {:type "usdClassTransfer"
                             :amount "1.5"
                             :toPerp true}}}
         (policy/transfer-preview {:spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                                            :available "9"
                                                                            :total "9"
                                                                            :hold "0"}]}}}
                                  {:amount-input "1.5"
                                   :to-perp? true}))))

(deftest transfer-preview-covers-unavailable-and-boundary-amount-branches-test
  (with-redefs [availability/transfer-max-amount (constantly js/NaN)]
    (is (= {:ok? false
            :display-message "Unable to determine transfer balance."}
           (policy/transfer-preview {}
                                    {:amount-input "1"
                                     :to-perp? true}))))
  (let [state {:spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                        :available "1"
                                                        :total "1"
                                                        :hold "0"}]}}}]
    (is (= {:ok? false
            :display-message "Enter an amount greater than 0."}
           (policy/transfer-preview state
                                    {:amount-input "0"
                                     :to-perp? true})))
    (is (= {:ok? true
            :request {:action {:type "usdClassTransfer"
                               :amount "1"
                               :toPerp true}}}
           (policy/transfer-preview state
                                    {:amount-input "1"
                                     :to-perp? true})))
    (is (= {:ok? false
            :display-message "Amount exceeds available balance."}
           (policy/transfer-preview state
                                    {:amount-input "1.000001"
                                     :to-perp? true})))))

(deftest send-preview-covers-token-destination-balance-and-success-branches-test
  (is (= {:ok? false
          :display-message "Select an asset to send."}
         (policy/send-preview {}
                              {:amount-input "1"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount 10})))
  (is (= {:ok? false
          :display-message "No sendable BTC balance available."}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "BTC"
                               :amount-input "1"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount 0})))
  (is (= {:ok? false
          :display-message "Enter a valid destination address."}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "Bitcoin"
                               :amount-input "1"
                               :destination-input "invalid"
                               :send-max-amount 10})))
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination "0x1234567890abcdef1234567890abcdef12345678"
                             :sourceDex "spot"
                             :destinationDex "spot"
                             :token "BTC"
                             :amount "1.5"
                             :fromSubAccount ""}}}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "Bitcoin"
                               :amount-input "1.5"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount 10}))))

(deftest send-preview-covers-unavailable-and-boundary-balance-branches-test
  (is (= {:ok? false
          :display-message "Unable to determine sendable balance."}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "BTC"
                               :amount-input "1"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount nil})))
  (is (= {:ok? false
          :display-message "Enter a valid amount."}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "Bitcoin"
                               :amount-input "bad"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount 1})))
  (is (= {:ok? false
          :display-message "Enter an amount greater than 0."}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "Bitcoin"
                               :amount-input "0"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount 1})))
  (is (= {:ok? true
          :request {:action {:type "sendAsset"
                             :destination "0x1234567890abcdef1234567890abcdef12345678"
                             :sourceDex "spot"
                             :destinationDex "spot"
                             :token "BTC"
                             :amount "1"
                             :fromSubAccount ""}}}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "Bitcoin"
                               :amount-input "1"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount 1})))
  (is (= {:ok? false
          :display-message "Amount exceeds available balance."}
         (policy/send-preview {}
                              {:send-token "BTC"
                               :send-symbol "Bitcoin"
                               :amount-input "1.000001"
                               :destination-input "0x1234567890abcdef1234567890abcdef12345678"
                               :send-max-amount 1}))))

(deftest withdraw-preview-enforces-minimum-amount-for-hyperunit-assets-test
  (with-redefs [assets-domain/withdraw-assets
                (fn [_state]
                  [{:key :btc
                    :symbol "BTC"
                    :network "Bitcoin"
                    :flow-kind :hyperunit-address
                    :hyperunit-source-chain "bitcoin"
                    :min 0.0003
                    :available-amount 1
                    :available-display "1"
                    :available-detail-display "1"}])]
    (is (= {:ok? false
            :display-message "Minimum withdrawal is 0.0003 BTC."}
           (policy/withdraw-preview (base-state)
                                    {:withdraw-selected-asset-key :btc
                                     :destination-input "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                                     :amount-input "0.0001"})))))

(deftest withdraw-preview-error-helper-allows-zero-and-exact-minimum-boundaries-test
  (let [withdraw-preview-error @#'hyperopen.funding.domain.policy/withdraw-preview-error
        base-input {:selected-asset {:symbol "BTC"}
                    :destination "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
                    :destination-chain "bitcoin"
                    :max-amount 10}]
    (is (nil? (withdraw-preview-error (assoc base-input
                                             :amount 1
                                             :max-amount 1
                                             :min-amount 0))))
    (is (nil? (withdraw-preview-error (assoc base-input
                                             :amount -1
                                             :min-amount 0))))
    (is (nil? (withdraw-preview-error (assoc base-input
                                             :amount 0.0003
                                             :min-amount 0.0003))))))

(deftest deposit-preview-covers-step-validation-and-branch-coverage-test
  (with-redefs [assets-domain/deposit-asset
                (fn [_state modal]
                  (case (:deposit-selected-asset-key modal)
                    :missing nil
                    :route-usdt {:key :usdt
                                 :symbol "USDT"
                                 :flow-kind :route
                                 :minimum 5}
                    :route-usdh {:key :usdh
                                 :symbol "USDH"
                                 :flow-kind :route
                                 :minimum 5
                                 :maximum 1000000}
                    :route-other {:key :foo
                                  :symbol "FOO"
                                  :flow-kind :route
                                  :minimum 5}
                    :bridge2 {:key :usdc
                              :symbol "USDC"
                              :flow-kind :bridge2
                              :chain-id assets-domain/deposit-chain-id-mainnet
                              :minimum assets-domain/deposit-min-usdc}
                    :hyperunit {:key :btc
                                :symbol "BTC"
                                :flow-kind :hyperunit-address
                                :hyperunit-source-chain "bitcoin"
                                :network "Bitcoin"
                                :minimum 0.0003}
                    :hyperunit-missing-chain {:key :btc
                                              :symbol "BTC"
                                              :flow-kind :hyperunit-address
                                              :hyperunit-source-chain nil
                                              :network "Bitcoin"
                                              :minimum 0.0003}
                    :unimplemented {:key :unimplemented
                                     :symbol "DOGE"
                                     :flow-kind :route
                                     :minimum 1}
                    :unsupported-flow {:key :sol
                                       :symbol "SOL"
                                       :flow-kind :manual
                                       :minimum 1}
                    nil))
                assets-domain/deposit-asset-implemented?
                (fn [asset]
                  (not= :unimplemented (:key asset)))]
    (is (= {:ok? false}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :asset-select
                                    :deposit-selected-asset-key :bridge2
                                    :amount-input "1"})))
    (is (= {:ok? false
            :display-message "Select an asset to deposit."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :missing
                                    :amount-input "1"})))
    (is (= {:ok? false
            :display-message "DOGE deposits are not implemented yet in Hyperopen."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :unimplemented
                                    :amount-input "1"})))
    (is (= {:ok? false
            :display-message "BTC address deposits are not implemented yet in Hyperopen."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :hyperunit-missing-chain
                                    :amount-input "1"})))
    (is (= {:ok? true
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :hyperunit
                                    :amount-input "1"})))
    (is (= {:ok? false
            :display-message "Enter a valid amount."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdt
                                    :amount-input "bad"})))
    (is (= {:ok? false
            :display-message "Enter an amount greater than 0."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdt
                                    :amount-input "0"})))
    (is (= {:ok? false
            :display-message "Minimum deposit is 5 USDT."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdt
                                    :amount-input "1"})))
    (is (= {:ok? false
            :display-message "Minimum deposit is 5 USDT."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdt
                                    :amount-input "4"})))
    (is (= {:ok? false
            :display-message "Maximum deposit is 1000000 USDH."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdh
                                    :amount-input "1000001"})))
    (is (= {:ok? true
            :request {:action {:type "acrossUsdcToUsdhDeposit"
                               :asset "usdh"
                               :amount "1000000"
                               :chainId assets-domain/deposit-chain-id-mainnet}}}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdh
                                    :amount-input "1000000"})))
    (is (= {:ok? false
            :display-message "FOO route deposits are not implemented yet in Hyperopen."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-other
                                    :amount-input "10"})))
    (is (= {:ok? false
            :display-message "Deposit flow unavailable."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :unsupported-flow
                                    :amount-input "10"})))
    (is (= {:ok? true
            :request {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                               :asset "usdt"
                               :amount "5"
                               :chainId assets-domain/deposit-chain-id-mainnet}}}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdt
                                    :amount-input "5"})))
    (is (= {:ok? true
            :request {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                               :asset "usdt"
                               :amount "10"
                               :chainId assets-domain/deposit-chain-id-mainnet}}}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdt
                                    :amount-input "10"})))
    (is (= {:ok? true
            :request {:action {:type "acrossUsdcToUsdhDeposit"
                               :asset "usdh"
                               :amount "10"
                               :chainId assets-domain/deposit-chain-id-mainnet}}}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdh
                                    :amount-input "10"})))
    (is (= {:ok? false
            :display-message "Enter an amount greater than 0."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :bridge2
                                    :amount-input "0"})))
    (is (= {:ok? true
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "5"
                               :chainId assets-domain/deposit-chain-id-mainnet}}}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :bridge2
                                    :amount-input "5"})))
    (is (= {:ok? true
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "10"
                               :chainId assets-domain/deposit-chain-id-mainnet}}}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :bridge2
                                    :amount-input "10"})))))

(deftest preview-returns-unavailable-for-unknown-mode-test
  (is (= {:ok? false
          :display-message "Funding action unavailable."}
         (policy/preview (base-state)
                         {:mode :legacy}))))
