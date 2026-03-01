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
                                          :details details
                                          :webdata {}})]
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
                                          :details details
                                          :webdata {}})]
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
                                          :details details
                                          :webdata {}})]
    (is (= :withdraw (:mode model)))
    (is (= "Withdraw" (:title model)))
    (is (= "Withdrawing..." (:confirm-label model)))
    (is (= true (:submit-disabled? model)))))
