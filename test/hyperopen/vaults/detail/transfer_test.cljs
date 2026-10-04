(ns hyperopen.vaults.detail.transfer-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.detail.transfer :as transfer]))

(def vault-address "0x1234567890abcdef1234567890abcdef12345678")
(def leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest read-model-builds-deposit-state-with-hlp-lockup-test
  (let [details {:allow-deposits? true
                 :name "Hyperliquidity Provider (HLP)"}
        state {:wallet {:address leader-address
                        :agent {:status :ready}}
               :webdata2 {:clearinghouseState {:withdrawable 159.379}}
               :vaults-ui {:vault-transfer-modal {:open? true
                                                  :mode :deposit
                                                  :vault-address vault-address
                                                  :amount-input "1.5"
                                                  :withdraw-all? false
                                                  :submitting? false
                                                  :error nil}}
               :vaults {:details-by-address {vault-address details}}}
        model (transfer/read-model state {:vault-address vault-address
                                          :vault-name (:name details)
                                          :details details})]
    (is (= true (:can-open-deposit? model)))
    (is (= true (:open? model)))
    (is (= :deposit (:mode model)))
    (is (= "Deposit" (:title model)))
    (is (= "Deposit" (:confirm-label model)))
    (is (= 159.37 (:deposit-max-usdc model)))
    (is (= "159.37" (:deposit-max-display model)))
    (is (= "159.37" (:deposit-max-input model)))
    (is (= 4 (:deposit-lockup-days model)))
    (is (= "Deposit funds to Hyperliquidity Provider (HLP). The deposit lock-up period is 4 days."
           (:deposit-lockup-copy model)))))

(defn- deposit-model
  [account-state]
  (let [details {:allow-deposits? true
                 :name "Vault Detail"}
        state (merge {:wallet {:address leader-address
                               :agent {:status :ready}}
                      :vaults-ui {:vault-transfer-modal {:open? true
                                                         :mode :deposit
                                                         :vault-address vault-address
                                                         :amount-input "1"
                                                         :withdraw-all? false
                                                         :submitting? false
                                                         :error nil}}
                      :vaults {:details-by-address {vault-address details}}}
                     account-state)]
    (transfer/read-model state {:vault-address vault-address
                                :vault-name (:name details)
                                :details details})))

(deftest read-model-classic-deposit-max-uses-perps-withdrawable-not-spot-usdc-test
  ;; Classic accounts fund vault deposits from perps; spot USDC is not
  ;; spendable until moved to perps, so it must never inflate MAX.
  (let [model (deposit-model {:account {:mode :classic}
                              :webdata2 {:clearinghouseState {:withdrawable "1350.6912"}}
                              :spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                                       :total "244789.13"
                                                                       :hold "0"}]}}})]
    (is (= 1350.69 (:deposit-max-usdc model)))
    (is (= "1,350.69" (:deposit-max-display model)))
    (is (= "1350.69" (:deposit-max-input model)))))

(deftest read-model-unified-deposit-max-uses-spot-usdc-available-test
  (let [model (deposit-model {:account {:mode :unified}
                              :webdata2 {:clearinghouseState {:withdrawable "3"}}
                              :spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                                       :total "90"
                                                                       :hold "1.112"}]}}})]
    (is (= 88.88 (:deposit-max-usdc model)))
    (is (= "88.88" (:deposit-max-input model)))))

(deftest read-model-unknown-mode-deposit-max-falls-back-to-perps-withdrawable-test
  (let [model (deposit-model {:webdata2 {:clearinghouseState {:withdrawable "42.129"}}
                              :spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                                       :total "500"}]}}})]
    (is (= 42.12 (:deposit-max-usdc model)))))

(deftest read-model-blocks-classic-deposit-above-perps-with-spot-transfer-hint-test
  (let [model (deposit-model {:account {:mode :classic}
                              :webdata2 {:clearinghouseState {:withdrawable "1350.69"}}
                              :spot {:clearinghouse-state {:balances [{:coin "USDC"
                                                                       :total "244789.13"}]}}
                              :vaults-ui {:vault-transfer-modal {:open? true
                                                                 :mode :deposit
                                                                 :vault-address vault-address
                                                                 :amount-input "20000"}}})]
    (is (false? (:preview-ok? model)))
    (is (true? (:submit-disabled? model)))
    (is (= "Vault deposits use your perps USDC. Transfer USDC from spot to perps first."
           (:preview-message model)))))

(deftest read-model-prefers-follower-lockup-window-test
  (let [details {:allow-deposits? true
                 :name "Vault Detail"
                 :follower-state {:vault-entry-time-ms 1000
                                  :lockup-until-ms (+ 1000 (* 2 24 60 60 1000))}}
        state {:wallet {:address leader-address
                        :agent {:status :ready}}
               :webdata2 {:clearinghouseState {:withdrawable 50}}
               :vaults-ui {:vault-transfer-modal {:open? true
                                                  :mode :deposit
                                                  :vault-address vault-address
                                                  :amount-input "1"
                                                  :withdraw-all? false
                                                  :submitting? false
                                                  :error nil}}
               :vaults {:details-by-address {vault-address details}}}
        model (transfer/read-model state {:vault-address vault-address
                                          :vault-name (:name details)
                                          :details details})]
    (is (= 2 (:deposit-lockup-days model)))
    (is (= "Deposit funds to Vault Detail. The deposit lock-up period is 2 days."
           (:deposit-lockup-copy model)))))

(deftest read-model-emits-withdraw-submitting-label-test
  (let [details {:allow-deposits? false
                 :name "Vault Detail"}
        state {:wallet {:address leader-address
                        :agent {:status :ready}}
               :webdata2 {:clearinghouseState {:withdrawable 40}}
               :vaults-ui {:vault-transfer-modal {:open? true
                                                  :mode :withdraw
                                                  :vault-address vault-address
                                                  :amount-input "1"
                                                  :withdraw-all? false
                                                  :submitting? true
                                                  :error nil}}
               :vaults {:details-by-address {vault-address details}}}
        model (transfer/read-model state {:vault-address vault-address
                                          :vault-name (:name details)
                                          :details details})]
    (is (= :withdraw (:mode model)))
    (is (= "Withdraw" (:title model)))
    (is (= "Withdrawing..." (:confirm-label model)))
    (is (= true (:submit-disabled? model)))))
