(ns hyperopen.funding.domain.policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.domain.assets :as assets-domain]
            [hyperopen.funding.domain.policy :as policy]))

(defn- base-state
  []
  {:spot {:clearinghouse-state {:balances [{:coin "USDC" :available "12.5" :total "12.5" :hold "0"}
                                           {:coin "BTC" :available "1.25" :total "1.25" :hold "0"}]}}
   :webdata2 {:clearinghouseState {:availableToWithdraw "8.5"
                                   :marginSummary {:accountValue "20"
                                                   :totalMarginUsed "11.5"}}}})

(deftest direct-balance-row-available-prefers-supported-direct-fields-test
  (let [direct-balance-row-available @#'hyperopen.funding.domain.policy/direct-balance-row-available]
    (is (= 10.5
           (direct-balance-row-available {:available "10.5"
                                          :availableBalance "9"
                                          :free "8"})))
    (is (= 8
           (direct-balance-row-available {:availableBalance "8"
                                          :free "7"})))
    (is (= 7
           (direct-balance-row-available {:free "7"})))
    (is (nil? (direct-balance-row-available {:available "NaN"
                                             :availableBalance nil
                                             :free ""})))))

(deftest derived-balance-row-available-uses-total-minus-hold-when-needed-test
  (let [derived-balance-row-available @#'hyperopen.funding.domain.policy/derived-balance-row-available]
    (is (= 6
           (derived-balance-row-available {:total "10"
                                           :hold "4"})))
    (is (= 12
           (derived-balance-row-available {:totalBalance "12"})))
    (is (= -2
           (derived-balance-row-available {:total "5"
                                           :hold "7"})))
    (is (nil? (derived-balance-row-available {:hold "2"})))))

(deftest balance-row-available-wraps-direct-and-derived-values-test
  (let [balance-row-available @#'hyperopen.funding.domain.policy/balance-row-available]
    (is (= 10.5
           (balance-row-available {:available "10.5"
                                   :availableBalance "9"
                                   :free "8"
                                   :total "100"
                                   :hold "50"})))
    (is (= 0
           (balance-row-available {:total "5"
                                   :hold "7"})))
    (is (nil? (balance-row-available {:available "NaN"})))
    (is (nil? (balance-row-available nil)))))

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

(deftest normalize-mode-recognizes-legacy-and-ignores-unknown-values-test
  (is (= :legacy (policy/normalize-mode "legacy")))
  (is (= :withdraw (policy/normalize-mode :withdraw)))
  (is (nil? (policy/normalize-mode "unknown"))))

(deftest summary-derived-withdrawable-uses-margin-summary-and-clamps-negative-values-test
  (let [summary-derived-withdrawable @#'hyperopen.funding.domain.policy/summary-derived-withdrawable]
    (is (= 8.5
           (summary-derived-withdrawable {:accountValue "20"
                                          :totalMarginUsed "11.5"})))
    (is (= 0
           (summary-derived-withdrawable {:accountValue "5"
                                          :totalMarginUsed "7"})))
    (is (nil? (summary-derived-withdrawable {:accountValue "invalid"
                                             :totalMarginUsed "7"})))))

(deftest withdrawable-usdc-prefers-unified-spot-before-perps-fallback-test
  (let [withdrawable-usdc @#'hyperopen.funding.domain.policy/withdrawable-usdc]
    (is (= 12
           (withdrawable-usdc {:account {:mode :unified}
                               :spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                                        :available "12"
                                                                        :total "12"
                                                                        :hold "0"}]}}
                               :webdata2 {:clearinghouseState {:withdrawable "4"}}})))
    (is (= 4
           (withdrawable-usdc {:spot {:clearinghouse-state {:balances []}}
                               :webdata2 {:clearinghouseState {:withdrawable "4"}}})))))

(deftest withdraw-available-amount-handles-nil-usdc-and-spot-assets-test
  (let [withdraw-available-amount @#'hyperopen.funding.domain.policy/withdraw-available-amount
        state {:spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                        :available "3"
                                                        :total "3"
                                                        :hold "0"}
                                                       {:coin "BTC"
                                                        :available "1.25"
                                                        :total "1.25"
                                                        :hold "0"}]}}
               :webdata2 {:clearinghouseState {:withdrawable "4"}}}]
    (is (= 0
           (withdraw-available-amount state nil)))
    (is (= 4
           (withdraw-available-amount state {:key :usdc})))
    (is (= 1.25
           (withdraw-available-amount state {:key :btc
                                             :symbol "BTC"})))))

(deftest withdraw-assets-filtered-and-selected-asset-helpers-honor-search-and-fallbacks-test
  (with-redefs [assets-domain/withdraw-assets
                (fn [_state]
                  [{:key :btc
                    :symbol "BTC"
                    :name "Bitcoin"
                    :network "Bitcoin"
                    :available-amount 1
                    :available-display "1"
                    :available-detail-display "1"}
                   {:key :sol
                    :symbol "SOL"
                    :name "Solana"
                    :network "Solana"
                    :available-amount 2
                    :available-display "2"
                    :available-detail-display "2"}])]
    (is (= 2
           (count (policy/withdraw-assets-filtered {} {:withdraw-search-input " "}))))
    (is (= [:btc]
           (mapv :key (policy/withdraw-assets-filtered {} {:withdraw-search-input "bit"}))))
    (is (= [:sol]
           (mapv :key (policy/withdraw-assets-filtered {} {:withdraw-search-input "solana"}))))
    (is (= :btc
           (:key (policy/withdraw-asset {} {:withdraw-selected-asset-key :btc}))))
    (is (= :btc
           (:key (policy/withdraw-asset {} {:withdraw-selected-asset-key :missing}))))))

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
                    :unimplemented {:key :doge
                                     :symbol "DOGE"
                                     :flow-kind :route
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
            :display-message "DOGE route deposits are not implemented yet in Hyperopen."}
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
                                    :amount-input "4"})))
    (is (= {:ok? false
            :display-message "Maximum deposit is 1000000 USDH."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-usdh
                                    :amount-input "1000001"})))
    (is (= {:ok? false
            :display-message "FOO route deposits are not implemented yet in Hyperopen."}
           (policy/deposit-preview (base-state)
                                   {:deposit-step :amount-entry
                                    :deposit-selected-asset-key :route-other
                                    :amount-input "10"})))
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
