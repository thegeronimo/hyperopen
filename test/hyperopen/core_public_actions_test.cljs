(ns hyperopen.core-public-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.core.compat :as compat]
            [hyperopen.order.actions :as order-actions]))

(deftest core-compat-exposes-public-action-aliases-test
  (let [state {:active-asset "ETH"
               :asset-selector {:open? true}
               :order-form {:entry-mode :pro
                            :type :stop-market}
               :active-market {:mark-price 101.0
                               :mid-price 101.0}}
        market {:coin "BTC" :symbol "BTC"}
        select-asset-effects (asset-actions/select-asset state market)
        select-order-entry-effects (order-actions/select-order-entry-mode state :market)]
    (is (= select-asset-effects
           (compat/select-asset state market)))
    (is (= select-order-entry-effects
           (compat/select-order-entry-mode state :market)))
    (is (= [[:effects/fetch-asset-selector-markets]]
           (compat/refresh-asset-markets state)))
    (is (= [[:effects/api-load-user-data "0xabc"]]
           (compat/load-user-data state "0xabc")))
    (is (= [[:effects/save [:funding-ui :modal]
             {:open? true
              :mode :legacy
              :legacy-kind :history
              :anchor nil
              :opener-data-role nil
              :focus-return-data-role nil
              :focus-return-token 0
              :deposit-step :asset-select
              :deposit-search-input ""
              :withdraw-step :asset-select
              :withdraw-search-input ""
              :deposit-selected-asset-key nil
              :deposit-generated-address nil
              :deposit-generated-signatures nil
              :deposit-generated-asset-key nil
              :send-token nil
              :send-symbol nil
              :send-prefix-label nil
              :send-max-amount nil
              :send-max-display nil
              :send-max-input ""
              :withdraw-selected-asset-key :usdc
              :withdraw-generated-address nil
              :amount-input ""
              :to-perp? true
              :destination-input ""
              :hyperunit-lifecycle {:direction nil
                                    :asset-key nil
                                    :operation-id nil
                                    :state nil
                                    :status nil
                                    :source-tx-confirmations nil
                                    :destination-tx-confirmations nil
                                    :position-in-withdraw-queue nil
                                    :destination-tx-hash nil
                                    :state-next-at nil
                                    :last-updated-ms nil
                                    :error nil}
              :hyperunit-fee-estimate {:status :idle
                                       :by-chain {}
                                       :requested-at-ms nil
                                       :updated-at-ms nil
                                       :error nil}
              :hyperunit-withdrawal-queue {:status :idle
                                           :by-chain {}
                                           :requested-at-ms nil
                                           :updated-at-ms nil
                                           :error nil}
              :submitting? false
              :error nil}]]
           (compat/set-funding-modal state :history)))))
