(ns hyperopen.views.vaults.detail.transfer-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.vaults.detail.transfer-modal :as modal]))

(deftest hero-transfer-button-renders-enabled-and-disabled-states-test
  (let [enabled (modal/hero-transfer-button {:label "Deposit"
                                             :enabled? true
                                             :action [:actions/open-vault-transfer-modal "0xvault" :deposit]})
        disabled (modal/hero-transfer-button {:label "Withdraw"
                                              :enabled? false
                                              :action [:actions/open-vault-transfer-modal "0xvault" :withdraw]})]
    (is (= [[:actions/open-vault-transfer-modal "0xvault" :deposit]]
           (get-in enabled [1 :on :click])))
    (is (not (true? (get-in enabled [1 :disabled]))))
    (is (contains? (hiccup/node-class-set enabled) "hover:bg-[#12323a]"))
    (is (true? (get-in disabled [1 :disabled])))
    (is (contains? (hiccup/node-class-set disabled) "cursor-not-allowed"))))

(deftest vault-transfer-modal-view-renders-deposit-branch-test
  (is (nil? (modal/vault-transfer-modal-view {:open? false})))
  (let [view (modal/vault-transfer-modal-view {:open? true
                                               :title "Deposit"
                                               :mode :deposit
                                               :deposit-max-display "12.34"
                                               :deposit-max-input "12.34"
                                               :deposit-lockup-copy "Deposits are locked for 4 days."
                                               :amount-input "1.25"
                                               :withdraw-all? false
                                               :submitting? false
                                               :error nil
                                               :preview-ok? true
                                               :preview-message nil
                                               :confirm-label "Deposit"
                                               :submit-disabled? false})
        lockup-copy (hiccup/find-first-node view
                                            #(= "vault-transfer-deposit-lockup-copy"
                                                (get-in % [1 :data-role])))
        max-button (hiccup/find-first-node view
                                           #(= "vault-transfer-deposit-max"
                                               (get-in % [1 :data-role])))
        amount-input (hiccup/find-first-node view
                                             #(= "vault-transfer-amount-input"
                                                 (get-in % [1 :data-role])))
        submit-button (hiccup/find-first-node view
                                              #(= "vault-transfer-submit"
                                                  (get-in % [1 :data-role])))]
    (is (some? lockup-copy))
    (is (some? max-button))
    (is (= [[:actions/set-vault-transfer-amount "12.34"]]
           (get-in max-button [1 :on :click])))
    (is (= "Enter amount" (get-in amount-input [1 :placeholder])))
    (is (not (true? (get-in amount-input [1 :disabled]))))
    (is (some? submit-button))
    (is (= "Deposit" (last submit-button)))))

(deftest vault-transfer-modal-view-renders-withdraw-branch-and-status-prioritization-test
  (let [withdraw-view (modal/vault-transfer-modal-view {:open? true
                                                        :title "Withdraw"
                                                        :mode :withdraw
                                                        :deposit-max-display nil
                                                        :deposit-max-input nil
                                                        :deposit-lockup-copy nil
                                                        :amount-input "2.0"
                                                        :withdraw-all? true
                                                        :submitting? false
                                                        :error nil
                                                        :preview-ok? false
                                                        :preview-message "Insufficient withdrawable balance."
                                                        :confirm-label "Withdraw"
                                                        :submit-disabled? true})
        checkbox (hiccup/find-first-node withdraw-view
                                         #(and (= :input (first %))
                                               (= "checkbox" (get-in % [1 :type]))))
        amount-input (hiccup/find-first-node withdraw-view
                                             #(= "vault-transfer-amount-input"
                                                 (get-in % [1 :data-role])))
        status-node (hiccup/find-first-node withdraw-view
                                            #(= "vault-transfer-status"
                                                (get-in % [1 :data-role])))
        status-text (set (hiccup/collect-strings status-node))
        error-view (modal/vault-transfer-modal-view {:open? true
                                                     :title "Withdraw"
                                                     :mode :withdraw
                                                     :amount-input "2.0"
                                                     :withdraw-all? false
                                                     :submitting? false
                                                     :error "Transfer failed."
                                                     :preview-ok? false
                                                     :preview-message "Should not render."
                                                     :confirm-label "Withdraw"
                                                     :submit-disabled? false})
        error-status (hiccup/find-first-node error-view
                                             #(= "vault-transfer-status"
                                                 (get-in % [1 :data-role])))
        error-text (set (hiccup/collect-strings error-status))]
    (is (some? checkbox))
    (is (= [[:actions/set-vault-transfer-withdraw-all :event.target/checked]]
           (get-in checkbox [1 :on :change])))
    (is (= "Enter amount or use Withdraw All"
           (get-in amount-input [1 :placeholder])))
    (is (true? (get-in amount-input [1 :disabled])))
    (is (contains? status-text "Insufficient withdrawable balance."))
    (is (contains? error-text "Transfer failed."))
    (is (not (contains? error-text "Should not render.")))))
