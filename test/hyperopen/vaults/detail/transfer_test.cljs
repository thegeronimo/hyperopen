(ns hyperopen.vaults.detail.transfer-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.vaults.detail.transfer :as transfer]))

(def vault-address "0x1234567890abcdef1234567890abcdef12345678")
(def leader-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest balance-row-available-prefers-direct-fields-and-clamps-derived-values-test
  (let [balance-row-available @#'hyperopen.vaults.detail.transfer/balance-row-available]
    (is (= 10.5
           (balance-row-available {:available "10.5"
                                   :total "20"
                                   :hold "15"})))
    (is (= 8
           (balance-row-available {:availableBalance "8"})))
    (is (= 7
           (balance-row-available {:free "7"})))
    (is (= 9
           (balance-row-available {:total "10"
                                   :hold "1"})))
    (is (= 12
           (balance-row-available {:totalBalance "12"})))
    (is (= 0
           (balance-row-available {:total "5"
                                   :hold "9"})))
    (is (nil? (balance-row-available {:available "NaN"})))
    (is (nil? (balance-row-available nil)))))

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

(deftest read-model-uses-webdata-balance-rows-for-deposit-max-test
  (let [details {:allow-deposits? true
                 :name "Vault Detail"}
        state {:wallet {:address leader-address
                        :agent {:status :ready}}
               :webdata2 {:spotState {:balances [{:coin "USDC"
                                                  :available "88.888"
                                                  :total "90"}]}}
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
    (is (= 88.88 (:deposit-max-usdc model)))
    (is (= "88.88" (:deposit-max-display model)))
    (is (= "88.88" (:deposit-max-input model)))))

(deftest read-model-uses-spot-balance-token-fallback-for-deposit-max-test
  (let [details {:allow-deposits? true
                 :name "Vault Detail"}
        state {:wallet {:address leader-address
                        :agent {:status :ready}}
               :spot {:clearinghouse-state {:balances [{:token "USDC.e"
                                                       :free "42.129"}]}}
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
    (is (= 42.12 (:deposit-max-usdc model)))
    (is (= "42.12" (:deposit-max-display model)))
    (is (= "42.12" (:deposit-max-input model)))))

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
