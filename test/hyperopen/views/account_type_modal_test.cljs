(ns hyperopen.views.account-type-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-type-modal :as modal]
            [hyperopen.views.trade.order-form.test-support :refer [collect-strings
                                                                   find-first-node]]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(defn- node-by-role
  [view-node role]
  (find-first-node view-node #(= role (get-in % [1 :data-role]))))

(defn- modal-state
  [ui-overrides]
  (merge {:wallet {:address owner-address}
          :account {:mode :unified
                    :abstraction-raw "unifiedAccount"}
          :account-type-ui {:modal-open? true
                            :selected :unified-account}}
         ui-overrides))

(deftest account-type-modal-hides-when-closed-test
  (is (nil? (modal/account-type-modal-view {:account-type-ui {}}))))

(deftest account-type-modal-renders-options-and-actions-when-open-test
  (let [view-node (modal/account-type-modal-view (modal-state {}))
        strings (set (collect-strings view-node))
        dialog (node-by-role view-node "account-type-modal")
        close-button (node-by-role view-node "account-type-modal-close")
        confirm-button (node-by-role view-node "account-type-modal-confirm")
        warning (node-by-role view-node "account-type-modal-warning")]
    (is (some? dialog))
    (is (= "dialog" (get-in dialog [1 :role])))
    (is (contains? strings "Account Type"))
    (is (contains? strings "Unified Account"))
    (is (contains? strings "Portfolio Margin"))
    (is (contains? strings "Manual"))
    (is (contains? strings "The default account setting where each collateral asset has a separate balance. Perps can only use the settlement asset as collateral, and margining is only shared across cross margin assets with the same collateral asset."))
    (is (contains? strings "Only recommended for automated traders. All DEXs have separate balances and cross margin applies separately within each DEX."))
    (is (contains? strings "Establish Connection"))
    (is (contains? (set (collect-strings warning))
                   "Account value must be < $25M to use portfolio margin in beta mode."))
    (is (= [[:actions/close-account-type-modal]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/submit-account-type :unified-account]]
           (get-in confirm-button [1 :on :click])))))

(deftest account-type-modal-checks-the-selected-option-only-test
  (let [view-node (modal/account-type-modal-view (modal-state {:account-type-ui {:modal-open? true
                                                                                 :selected :portfolio-margin}}))
        unified-row (node-by-role view-node "account-type-option-unified-account")
        pm-row (node-by-role view-node "account-type-option-portfolio-margin")
        manual-row (node-by-role view-node "account-type-option-manual")]
    (is (false? (get-in unified-row [1 :aria-checked])))
    (is (true? (get-in pm-row [1 :aria-checked])))
    (is (false? (get-in manual-row [1 :aria-checked])))
    (is (= [[:actions/select-account-type :portfolio-margin]]
           (get-in pm-row [1 :on :click])))))

(deftest account-type-modal-disables-confirm-for-current-type-test
  (let [view-node (modal/account-type-modal-view (modal-state {}))
        confirm-button (node-by-role view-node "account-type-modal-confirm")]
    (is (true? (get-in confirm-button [1 :disabled])))
    (is (contains? (set (collect-strings confirm-button))
                   "Establish Connection"))))

(deftest account-type-modal-enables-confirm-for-a-different-type-test
  (let [view-node (modal/account-type-modal-view (modal-state {:account-type-ui {:modal-open? true
                                                                                 :selected :manual}}))
        confirm-button (node-by-role view-node "account-type-modal-confirm")]
    (is (false? (get-in confirm-button [1 :disabled])))
    (is (= [[:actions/submit-account-type :manual]]
           (get-in confirm-button [1 :on :click])))))

(deftest account-type-modal-disables-confirm-while-submitting-test
  (let [view-node (modal/account-type-modal-view (modal-state {:account-type-ui {:modal-open? true
                                                                                 :selected :manual
                                                                                 :submitting? true}}))
        confirm-button (node-by-role view-node "account-type-modal-confirm")]
    (is (true? (get-in confirm-button [1 :disabled])))
    (is (contains? (set (collect-strings confirm-button))
                   "Awaiting signature..."))))

(deftest account-type-modal-renders-error-message-test
  (let [view-node (modal/account-type-modal-view (modal-state {:account-type-ui {:modal-open? true
                                                                                 :selected :manual
                                                                                 :error-message "Connect your wallet before changing account type."}}))
        error (node-by-role view-node "account-type-error-message")]
    (is (some? error))
    (is (contains? (set (collect-strings error))
                   "Connect your wallet before changing account type."))))
