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
