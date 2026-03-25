(ns hyperopen.schema.contracts.state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.state.trading.order-form-key-policy :as key-policy]
            [hyperopen.system :as system]))

(def ^:private valid-order-form-ui
  {:pro-order-type-dropdown-open? false
   :margin-mode-dropdown-open? false
   :leverage-popover-open? false
   :size-unit-dropdown-open? false
   :tpsl-unit-dropdown-open? false
   :tif-dropdown-open? false
   :price-input-focused? false
   :tpsl-panel-open? false
   :entry-mode :limit
   :ui-leverage 20
   :leverage-draft 20
   :margin-mode :cross
   :size-input-mode :quote
   :size-input-source :manual
   :size-display ""})

(deftest assert-app-state-rejects-active-market-without-symbol-test
  (let [state (assoc (system/default-store-state)
                     :active-market {:coin "BTC"})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-order-form-ui-with-non-boolean-flags-test
  (let [state (assoc (system/default-store-state)
                     :order-form-ui {:pro-order-type-dropdown-open? false
                                     :margin-mode-dropdown-open? false
                                     :leverage-popover-open? false
                                     :size-unit-dropdown-open? false
                                     :tpsl-unit-dropdown-open? false
                                     :tif-dropdown-open? false
                                     :price-input-focused? "yes"
                                     :tpsl-panel-open? false
                                     :entry-mode :limit
                                     :ui-leverage 20
                                     :leverage-draft 20
                                     :margin-mode :cross
                                     :size-input-mode :quote
                                     :size-input-source :manual
                                     :size-display ""})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-accepts-valid-order-form-ui-state-test
  (let [state (assoc (system/default-store-state)
                     :order-form-ui (assoc valid-order-form-ui
                                           :price-input-focused? true))]
    (is (= state (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-order-form-with-ui-owned-fields-test
  (let [state (assoc (system/default-store-state)
                     :order-form (assoc (:order-form (system/default-store-state))
                                        :entry-mode :pro))]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-order-form-with-policy-defined-deprecated-keys-test
  (let [base (system/default-store-state)]
    (doseq [key key-policy/deprecated-canonical-order-form-keys]
      (let [state (assoc base
                         :order-form (assoc (:order-form base) key true))]
        (is (thrown-with-msg?
             js/Error
             #"app state"
             (contracts/assert-app-state! state {:phase :test}))
            (str "expected app-state contract rejection for " key))))))

(deftest assert-app-state-rejects-order-form-runtime-with-invalid-shape-test
  (let [state (assoc (system/default-store-state)
                     :order-form-runtime {:submitting? "no"
                                          :error 42})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-funding-ui-when-hyperunit-lifecycle-shape-is-invalid-test
  (let [state (assoc-in (system/default-store-state)
                        [:funding-ui :modal :hyperunit-lifecycle]
                        {:direction :deposit
                         :asset-key :btc})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))
