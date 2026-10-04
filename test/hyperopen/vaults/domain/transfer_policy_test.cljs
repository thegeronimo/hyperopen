(ns hyperopen.vaults.domain.transfer-policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.formal.vault-transfer-vectors :as formal-vectors]
            [hyperopen.schema.vault-transfer-contracts :as contracts]
            [hyperopen.vaults.domain.transfer-policy :as transfer-policy]))

(def ^:private vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private leader-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- base-state
  [locale allow-deposits?]
  {:ui {:locale locale}
   :wallet {:address leader-address}
   :vaults {:details-by-address {vault-address {:name "Vault Detail"
                                                :leader leader-address
                                                :allow-deposits? allow-deposits?}}
            :merged-index-rows [{:vault-address vault-address
                                 :name "Vault Detail"
                                 :leader leader-address}]}})

(deftest parse-usdc-micros-conforms-to-committed-formal-vectors-test
  (doseq [{:keys [id input expected]} formal-vectors/parse-usdc-micros-vectors]
    (testing (name id)
      (is (= expected
             (transfer-policy/parse-usdc-micros input))))))

(deftest vault-transfer-deposit-allowed-conforms-to-committed-formal-vectors-test
  (doseq [{:keys [id state vault-address expected]} formal-vectors/deposit-eligibility-vectors]
    (testing (name id)
      (is (= expected
             (transfer-policy/vault-transfer-deposit-allowed? state vault-address))))))

(deftest vault-transfer-preview-conforms-to-committed-formal-vectors-test
  (doseq [{:keys [id route-vault-address state modal expected]} formal-vectors/vault-transfer-preview-vectors]
    (testing (name id)
      (let [result (transfer-policy/vault-transfer-preview
                    {:route-vault-address-fn (when route-vault-address
                                               (fn [_] route-vault-address))}
                    state
                    modal)]
        (is (= expected result))
        (is (contracts/preview-result-valid? result))))))

(deftest parse-usdc-micros-handles-integer-padding-truncation-zero-and-overflow-test
  (is (= 12000000
         (transfer-policy/parse-usdc-micros "12")))
  (is (= 12000000
         (transfer-policy/parse-usdc-micros "12.")))
  (is (= 500000
         (transfer-policy/parse-usdc-micros ".5")))
  (is (= 1234567
         (transfer-policy/parse-usdc-micros "1.2345679")))
  (is (= 0
         (transfer-policy/parse-usdc-micros "0")))
  (is (= 9007199254740991
         (transfer-policy/parse-usdc-micros "9007199254.740991")))
  (is (nil? (transfer-policy/parse-usdc-micros "9007199254.740992"))))

(deftest normalize-vault-transfer-mode-falls-back-to-default-for-invalid-input-test
  (is (= :withdraw
         (transfer-policy/normalize-vault-transfer-mode " WITHDRAW ")))
  (is (= transfer-policy/default-vault-transfer-mode
         (transfer-policy/normalize-vault-transfer-mode "unsupported")))
  (is (= transfer-policy/default-vault-transfer-mode
         (transfer-policy/normalize-vault-transfer-mode :sideways)))
  (is (= transfer-policy/default-vault-transfer-mode
         (transfer-policy/normalize-vault-transfer-mode 7))))

(deftest vault-transfer-deposit-allowed-blocks-liquidator-vaults-even-for-the-leader-test
  (let [leader-state (base-state "en-US" false)
        liquidator-state (assoc-in leader-state
                                   [:vaults :details-by-address vault-address :name]
                                   " Liquidator ")
        merged-liquidator-state {:wallet {:address leader-address}
                                 :vaults {:details-by-address {vault-address {:name "   "
                                                                              :allow-deposits? false}}
                                          :merged-index-rows [{:vault-address vault-address
                                                               :name " LIQUIDATOR "
                                                               :leader leader-address}]}}]
    (is (true?
         (transfer-policy/vault-transfer-deposit-allowed? leader-state vault-address)))
    (is (false?
         (transfer-policy/vault-transfer-deposit-allowed? liquidator-state vault-address)))
    (is (false?
         (transfer-policy/vault-transfer-deposit-allowed? merged-liquidator-state vault-address)))))

(deftest vault-transfer-preview-uses-route-fallback-and-localized-amount-test
  (let [state (base-state "fr-FR" true)
        result (transfer-policy/vault-transfer-preview
                {:route-vault-address-fn (fn [_] vault-address)}
                state
                {:open? true
                 :mode :deposit
                 :amount-input "2,5"
                 :withdraw-all? false})]
    (is (true? (:ok? result)))
    (is (= vault-address (:vault-address result)))
    (is (= 2500000 (get-in result [:request :action :usd])))
    (is (contracts/preview-result-valid? result))))

(deftest vault-transfer-preview-normalizes-route-fallback-address-test
  (let [state (base-state "en-US" true)
        result (transfer-policy/vault-transfer-preview
                {:route-vault-address-fn (fn [_] " 0X1234567890ABCDEF1234567890ABCDEF12345678 ")}
                state
                {:open? true
                 :mode :withdraw
                 :amount-input "1"
                 :withdraw-all? false})]
    (is (true? (:ok? result)))
    (is (= vault-address (:vault-address result)))
    (is (= vault-address (get-in result [:request :vault-address])))))

(deftest vault-transfer-preview-rejects-invalid-vault-address-test
  (let [result (transfer-policy/vault-transfer-preview
                {}
                {}
                {:open? true
                 :mode :deposit
                 :amount-input "1"
                 :withdraw-all? false})]
    (is (false? (:ok? result)))
    (is (= "Invalid vault address."
           (:display-message result)))))

(deftest vault-transfer-preview-defaults-non-map-modal-and-ignores-non-function-route-fallback-test
  (let [result (transfer-policy/vault-transfer-preview
                {:route-vault-address-fn :not-a-function}
                (base-state "en-US" true)
                :not-a-map)]
    (is (= {:ok? false
            :display-message "Invalid vault address."}
           result))))

(deftest vault-transfer-preview-prefers-explicit-modal-vault-address-over-route-fallback-test
  (let [result (transfer-policy/vault-transfer-preview
                {:route-vault-address-fn (fn [_] "not-a-vault")}
                (base-state "en-US" true)
                {:open? true
                 :mode :deposit
                 :vault-address " 0X1234567890ABCDEF1234567890ABCDEF12345678 "
                 :amount-input "1"
                 :withdraw-all? false})]
    (is (true? (:ok? result)))
    (is (= vault-address
           (:vault-address result)))
    (is (= {:vault-address vault-address
            :action {:type "vaultTransfer"
                     :vaultAddress vault-address
                     :isDeposit true
                     :usd 1000000}}
           (:request result)))))

(deftest vault-transfer-preview-rejects-when-deposit-gating-disables-vault-test
  (let [state {:wallet {:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
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

(deftest vault-transfer-preview-rejects-invalid-and-zero-amounts-test
  (let [state (base-state "en-US" true)
        invalid-amount (transfer-policy/vault-transfer-preview
                        {:route-vault-address-fn (fn [_] vault-address)}
                        state
                        {:open? true
                         :mode :deposit
                         :amount-input "nope"
                         :withdraw-all? false})
        zero-amount (transfer-policy/vault-transfer-preview
                     {:route-vault-address-fn (fn [_] vault-address)}
                     state
                     {:open? true
                      :mode :deposit
                      :amount-input "0"
                      :withdraw-all? false})]
    (is (false? (:ok? invalid-amount)))
    (is (= "Enter an amount greater than 0."
           (:display-message invalid-amount)))
    (is (false? (:ok? zero-amount)))
    (is (= "Enter an amount greater than 0."
           (:display-message zero-amount)))))

(deftest vault-transfer-preview-allows-withdraw-all-with-zero-request-usd-test
  (let [state (base-state "en-US" true)
        result (transfer-policy/vault-transfer-preview
                {}
                state
                {:open? true
                 :mode :withdraw
                 :vault-address vault-address
                 :withdraw-all? true
                 :amount-input ""})]
    (is (true? (:ok? result)))
    (is (= nil (:display-message result)))
    (is (= {:vault-address vault-address
            :action {:type "vaultTransfer"
                     :vaultAddress vault-address
                     :isDeposit false
                     :usd 0}}
           (:request result)))
    (is (contracts/preview-result-valid? result))))

(deftest vault-transfer-preview-allows-smallest-positive-usdc-amount-test
  (let [state (base-state "en-US" true)
        result (transfer-policy/vault-transfer-preview
                {:route-vault-address-fn (fn [_] vault-address)}
                state
                {:open? true
                 :mode :deposit
                 :amount-input "0.000001"
                 :withdraw-all? false})]
    (is (true? (:ok? result)))
    (is (= 1
           (get-in result [:request :action :usd])))))

(defn- deposit-preview
  [account-state amount-input]
  (transfer-policy/vault-transfer-preview
   (merge (base-state "en-US" true) account-state)
   {:mode :deposit
    :vault-address vault-address
    :amount-input amount-input}))

(def ^:private classic-account
  {:account {:mode :classic}
   :webdata2 {:clearinghouseState {:withdrawable "1350.69"}}
   :spot {:clearinghouse-state {:balances [{:coin "USDC" :total "244789.13"}]}}})

(deftest deposit-available-usdc-follows-account-mode-test
  (is (= 1350.69 (transfer-policy/deposit-available-usdc classic-account)))
  (is (= 244789.13 (transfer-policy/deposit-available-usdc
                    (assoc classic-account :account {:mode :unified}))))
  (is (nil? (transfer-policy/deposit-available-usdc
             (dissoc classic-account :account))))
  (is (nil? (transfer-policy/deposit-available-usdc {:account {:mode :classic}}))))

(deftest vault-transfer-preview-caps-deposit-at-available-usdc-test
  (testing "classic: exact perps balance is allowed"
    (is (true? (:ok? (deposit-preview classic-account "1350.69")))))
  (testing "classic: over perps but covered by spot hints at spot->perps transfer"
    (is (= {:ok? false
            :display-message "Vault deposits use your perps USDC. Transfer USDC from spot to perps first."}
           (deposit-preview classic-account "20000"))))
  (testing "classic: over perps and spot is a plain shortfall"
    (is (= "Amount exceeds your available USDC."
           (:display-message (deposit-preview classic-account "300000")))))
  (testing "unified: spot USDC is spendable"
    (is (true? (:ok? (deposit-preview (assoc classic-account :account {:mode :unified})
                                      "20000")))))
  (testing "unknown mode does not block (balance not trusted yet)"
    (is (true? (:ok? (deposit-preview (dissoc classic-account :account) "20000")))))
  (testing "withdrawals are never capped by the wallet balance"
    (is (true? (:ok? (transfer-policy/vault-transfer-preview
                      (merge (base-state "en-US" true) classic-account)
                      {:mode :withdraw
                       :vault-address vault-address
                       :amount-input "20000"}))))))
