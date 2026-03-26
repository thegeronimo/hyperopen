(ns hyperopen.vaults.application.transfer-commands-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.vault-transfer-contracts :as contracts]
            [hyperopen.vaults.application.transfer-commands :as transfer-commands]
            [hyperopen.vaults.application.transfer-state :as transfer-state]))

(deftest close-vault-transfer-modal-resets-the-application-owned-modal-state-test
  (is (= [[:effects/save
           [:vaults-ui :vault-transfer-modal]
           (transfer-state/default-vault-transfer-modal-state)]]
         (transfer-commands/close-vault-transfer-modal
          {:vaults-ui {:vault-transfer-modal {:open? true
                                              :mode :withdraw
                                              :vault-address "0x1234567890abcdef1234567890abcdef12345678"
                                              :amount-input "10"
                                              :withdraw-all? true
                                              :submitting? true
                                              :error "boom"}}}))))

(deftest set-vault-transfer-amount-falls-back-to-default-modal-state-when-storage-is-malformed-test
  (is (= [[:effects/save
           [:vaults-ui :vault-transfer-modal]
           {:open? false
            :mode :deposit
            :vault-address nil
            :amount-input "2.5"
            :withdraw-all? false
            :submitting? false
            :error nil}]]
         (transfer-commands/set-vault-transfer-amount
          {:vaults-ui {:vault-transfer-modal :not-a-map}}
          "2.5"))))

(deftest submit-vault-transfer-uses-route-fallback-preview-and-emits-submit-effect-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        state {:wallet {:address leader-address}
               :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                            :leader leader-address
                                                            :allow-deposits? true}}
                        :merged-index-rows [{:vault-address vault-address
                                             :name "Vault Detail"
                                             :leader leader-address}]}
               :vaults-ui {:vault-transfer-modal {:open? true
                                                  :mode :deposit
                                                  :vault-address nil
                                                  :amount-input "2.5"
                                                  :withdraw-all? false
                                                  :submitting? false
                                                  :error nil}}}]
    (is (= [[:effects/save-many [[[:vaults-ui :vault-transfer-modal :submitting?] true]
                                 [[:vaults-ui :vault-transfer-modal :error] nil]]]
            [:effects/api-submit-vault-transfer
             {:vault-address vault-address
              :action {:type "vaultTransfer"
                       :vaultAddress vault-address
                       :isDeposit true
                       :usd 2500000}}]]
           (transfer-commands/submit-vault-transfer
            {:route-vault-address-fn (fn [_] vault-address)}
            state)))
    (is (= {:vault-address vault-address
            :action {:type "vaultTransfer"
                     :vaultAddress vault-address
                     :isDeposit true
                     :usd 2500000}}
           (contracts/assert-vault-transfer-request!
            {:vault-address vault-address
             :action {:type "vaultTransfer"
                      :vaultAddress vault-address
                      :isDeposit true
                      :usd 2500000}}
            {:test :submit-vault-transfer-wrapper})))))

(deftest submit-vault-transfer-surfaces-preview-failure-message-test
  (let [vault-address "0x1234567890abcdef1234567890abcdef12345678"
        leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        state {:wallet {:address leader-address}
               :vaults {:details-by-address {vault-address {:name "Liquidator"
                                                            :leader leader-address
                                                            :allow-deposits? true}}
                        :merged-index-rows [{:vault-address vault-address
                                             :name "Liquidator"
                                             :leader leader-address}]}
               :vaults-ui {:vault-transfer-modal {:open? true
                                                  :mode :deposit
                                                  :vault-address vault-address
                                                  :amount-input "1"
                                                  :withdraw-all? false
                                                  :submitting? false
                                                  :error nil}}}]
    (is (= [[:effects/save-many [[[:vaults-ui :vault-transfer-modal :submitting?] false]
                                 [[:vaults-ui :vault-transfer-modal :error] "Deposits are disabled for this vault."]]]]
           (transfer-commands/submit-vault-transfer {} state)))))
