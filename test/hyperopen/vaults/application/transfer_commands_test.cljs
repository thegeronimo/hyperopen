(ns hyperopen.vaults.application.transfer-commands-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.application.transfer-commands :as transfer-commands]))

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
            state)))))
