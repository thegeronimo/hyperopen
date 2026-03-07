(ns hyperopen.views.api-wallets.vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.api-wallets.vm :as vm]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def generated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest api-wallets-vm-merges-sorts-and-exposes-generated-private-key-test
  (let [state {:wallet {:address owner-address}
               :api-wallets-ui {:form {:name "Desk"
                                       :address generated-address
                                       :days-valid "30"}
                                :sort {:column :valid-until
                                       :direction :desc}
                                :modal {:open? true
                                        :type :authorize
                                        :submitting? false}
                                :generated {:address generated-address
                                            :private-key "0xpriv"}}
               :api-wallets {:extra-agents [{:row-kind :named
                                             :name "Desk"
                                             :approval-name "Desk valid_until 1700000000000"
                                             :address generated-address
                                             :valid-until-ms 1700000000000}]
                             :default-agent-row {:row-kind :default
                                                 :name "app.hyperopen.xyz"
                                                 :approval-name nil
                                                 :address owner-address
                                                 :valid-until-ms 1800000000000}
                             :server-time-ms 1700000000000
                             :loading {:extra-agents? false
                                       :default-agent? false}
                             :errors {:extra-agents nil
                                      :default-agent nil}}}
        result (vm/api-wallets-vm state)]
    (is (= ["app.hyperopen.xyz" "Desk"]
           (mapv :name (:rows result))))
    (is (= "0xpriv" (:generated-private-key result)))
    (is (= 1702592000000 (:valid-until-preview-ms result)))
    (is (false? (:authorize-disabled? result)))
    (is (false? (:modal-confirm-disabled? result)))))

(deftest api-wallets-vm-disables-authorize-when-disconnected-or-invalid-test
  (let [state {:wallet {:address nil}
               :api-wallets-ui {:form {:name ""
                                       :address "bad-address"
                                       :days-valid ""}
                                :modal {:open? false
                                        :type nil
                                        :submitting? false}}
               :api-wallets {:extra-agents []
                             :default-agent-row nil
                             :loading {:extra-agents? false
                                       :default-agent? false}
                             :errors {:extra-agents nil
                                      :default-agent nil}}}
        result (vm/api-wallets-vm state)]
    (is (false? (:connected? result)))
    (is (= "Enter an API wallet name."
           (get-in result [:form-errors :name])))
    (is (= "Enter a valid wallet address."
           (get-in result [:form-errors :address])))
    (is (true? (:authorize-disabled? result)))))
