(ns hyperopen.vaults.domain.transfer-policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.domain.transfer-policy :as transfer-policy]))

(deftest vault-transfer-preview-uses-route-fallback-and-localized-amount-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        state {:ui {:locale "fr-FR"}
               :wallet {:address leader-address}
               :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                            :leader leader-address
                                                            :allow-deposits? true}}
                        :merged-index-rows [{:vault-address vault-address
                                             :name "Vault Detail"
                                             :leader leader-address}]}}
        result (transfer-policy/vault-transfer-preview
                {:route-vault-address-fn (fn [_] vault-address)}
                state
                {:open? true
                 :mode :deposit
                 :amount-input "2,5"
                 :withdraw-all? false})]
    (is (true? (:ok? result)))
    (is (= vault-address (:vault-address result)))
    (is (= 2500000 (get-in result [:request :action :usd])))))

(deftest vault-transfer-preview-rejects-when-deposit-gating-disables-vault-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        state {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
               :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                            :leader "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                                            :allow-deposits? false}}
                        :merged-index-rows [{:vault-address vault-address
                                             :name "Vault Detail"
                                             :leader "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]}}
        result (transfer-policy/vault-transfer-preview
                {:route-vault-address-fn (fn [_] vault-address)}
                state
                {:open? true
                 :mode :deposit
                 :amount-input "1"
                 :withdraw-all? false})]
    (is (false? (:ok? result)))
    (is (= "Deposits are disabled for this vault."
           (:display-message result)))))
