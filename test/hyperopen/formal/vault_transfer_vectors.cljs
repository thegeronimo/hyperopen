(ns hyperopen.formal.vault-transfer-vectors)

(def vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def leader-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def other-address
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(def parse-usdc-micros-vectors
  [{:id :integer
    :input "12"
    :expected 12000000}
   {:id :trailing-decimal-point
    :input "12."
    :expected 12000000}
   {:id :leading-decimal-point
    :input ".5"
    :expected 500000}
   {:id :truncates-extra-fractional-digits
    :input "1.2345679"
    :expected 1234567}
   {:id :smallest-positive-unit
    :input "0.000001"
    :expected 1}
   {:id :zero
    :input "0"
    :expected 0}
   {:id :max-safe-boundary
    :input "9007199254.740991"
    :expected 9007199254740991}
   {:id :overflow
    :input "9007199254.740992"
    :expected nil}
   {:id :rejects-negative-input
    :input "-1"
    :expected nil}
   {:id :rejects-garbage
    :input "nope"
    :expected nil}])

(def deposit-eligibility-vectors
  [{:id :details-allow-deposits
    :state {:wallet {:address other-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? true}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :vault-address vault-address
    :expected true}
   {:id :leader-override-when-allow-deposits-false
    :state {:wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? false}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :vault-address vault-address
    :expected true}
   {:id :merged-row-leader-fallback
    :state {:wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :allow-deposits? false}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :vault-address vault-address
    :expected true}
   {:id :liquidator-blocked
    :state {:wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Liquidator"
                                                         :leader leader-address
                                                         :allow-deposits? true}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Liquidator"
                                          :leader leader-address}]}}
    :vault-address vault-address
    :expected false}
   {:id :invalid-address-blocked
    :state {:wallet {:address leader-address}
            :vaults {:details-by-address {}
                     :merged-index-rows []}}
    :vault-address "not-an-address"
    :expected false}])

(def vault-transfer-preview-vectors
  [{:id :route-fallback-localized-deposit
    :route-vault-address vault-address
    :state {:ui {:locale "fr-FR"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? true}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :deposit
            :amount-input "2,5"
            :withdraw-all? false}
    :expected {:ok? true
               :mode :deposit
               :vault-address vault-address
               :display-message nil
               :request (array-map :vault-address vault-address
                                   :action (array-map :type "vaultTransfer"
                                                      :vaultAddress vault-address
                                                      :isDeposit true
                                                      :usd 2500000))}}
   {:id :route-fallback-normalizes-before-preview
    :route-vault-address " 0X1234567890ABCDEF1234567890ABCDEF12345678 "
    :state {:ui {:locale "en-US"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? true}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :withdraw
            :amount-input "1"
            :withdraw-all? false}
    :expected {:ok? true
               :mode :withdraw
               :vault-address vault-address
               :display-message nil
               :request (array-map :vault-address vault-address
                                   :action (array-map :type "vaultTransfer"
                                                      :vaultAddress vault-address
                                                      :isDeposit false
                                                      :usd 1000000))}}
   {:id :invalid-route-fallback-is-rejected
    :route-vault-address "not-a-vault"
    :state {:ui {:locale "en-US"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {}
                     :merged-index-rows []}}
    :modal {:open? true
            :mode :withdraw
            :amount-input "1"
            :withdraw-all? false}
    :expected {:ok? false
               :display-message "Invalid vault address."}}
   {:id :invalid-address-wins-before-other-checks
    :route-vault-address nil
    :state {:ui {:locale "en-US"}
            :wallet {:address other-address}
            :vaults {:details-by-address {vault-address {:name "Liquidator"
                                                         :leader leader-address
                                                         :allow-deposits? false}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Liquidator"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :deposit
            :vault-address "garbage"
            :amount-input "0"
            :withdraw-all? false}
    :expected {:ok? false
               :display-message "Invalid vault address."}}
   {:id :deposit-disabled-wins-before-invalid-amount
    :route-vault-address vault-address
    :state {:ui {:locale "en-US"}
            :wallet {:address other-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? false}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :deposit
            :amount-input "nope"
            :withdraw-all? false}
    :expected {:ok? false
               :display-message "Deposits are disabled for this vault."}}
   {:id :leader-override-success
    :route-vault-address nil
    :state {:ui {:locale "en-US"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? false}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :deposit
            :vault-address vault-address
            :amount-input "1.25"
            :withdraw-all? false}
    :expected {:ok? true
               :mode :deposit
               :vault-address vault-address
               :display-message nil
               :request (array-map :vault-address vault-address
                                   :action (array-map :type "vaultTransfer"
                                                      :vaultAddress vault-address
                                                      :isDeposit true
                                                      :usd 1250000))}}
   {:id :merged-row-leader-fallback-success
    :route-vault-address nil
    :state {:ui {:locale "en-US"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :allow-deposits? false}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :deposit
            :vault-address vault-address
            :amount-input "3"
            :withdraw-all? false}
    :expected {:ok? true
               :mode :deposit
               :vault-address vault-address
               :display-message nil
               :request (array-map :vault-address vault-address
                                   :action (array-map :type "vaultTransfer"
                                                      :vaultAddress vault-address
                                                      :isDeposit true
                                                      :usd 3000000))}}
   {:id :liquidator-blocked
    :route-vault-address nil
    :state {:ui {:locale "en-US"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Liquidator"
                                                         :leader leader-address
                                                         :allow-deposits? true}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Liquidator"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :deposit
            :vault-address vault-address
            :amount-input "1"
            :withdraw-all? false}
    :expected {:ok? false
               :display-message "Deposits are disabled for this vault."}}
   {:id :withdraw-all-bypasses-amount
    :route-vault-address nil
    :state {:ui {:locale "en-US"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? true}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :withdraw
            :vault-address vault-address
            :amount-input ""
            :withdraw-all? true}
    :expected {:ok? true
               :mode :withdraw
               :vault-address vault-address
               :display-message nil
               :request (array-map :vault-address vault-address
                                   :action (array-map :type "vaultTransfer"
                                                      :vaultAddress vault-address
                                                      :isDeposit false
                                                      :usd 0))}}
   {:id :deposit-withdraw-all-flag-does-not-bypass-amount
    :route-vault-address nil
    :state {:ui {:locale "en-US"}
            :wallet {:address leader-address}
            :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                         :leader leader-address
                                                         :allow-deposits? true}}
                     :merged-index-rows [{:vault-address vault-address
                                          :name "Vault Detail"
                                          :leader leader-address}]}}
    :modal {:open? true
            :mode :deposit
            :vault-address vault-address
            :amount-input ""
            :withdraw-all? true}
    :expected {:ok? false
               :display-message "Enter an amount greater than 0."}}])
