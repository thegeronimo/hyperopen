(ns hyperopen.views.trade.order-form-vm-submit-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form-vm-submit :as vm-submit]))

(deftest submit-tooltip-message-branch-coverage-test
  (is (= "Fill required fields: Size, Price."
         (vm-submit/submit-tooltip-message ["Size" "Price"] false nil nil)))
  (is (= "Load order book data before placing a market order."
         (vm-submit/submit-tooltip-message [] true nil nil)))
  (is (= "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."
         (vm-submit/submit-tooltip-message [] false :spectate-mode-read-only
                                           "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds.")))
  (is (nil? (vm-submit/submit-tooltip-message [] false nil nil))))

(deftest submit-tooltip-from-policy-delegates-to-message-rules-test
  (is (= "Fill required fields: Trigger."
         (vm-submit/submit-tooltip-from-policy {:required-fields ["Trigger"]
                                                :market-price-missing? true
                                                :reason :validation-errors
                                                :error-message "ignored"})))
  (is (= "Load order book data before placing a market order."
         (vm-submit/submit-tooltip-from-policy {:required-fields []
                                                :market-price-missing? true
                                                :reason :market-price-missing
                                                :error-message nil})))
  (is (= "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."
         (vm-submit/submit-tooltip-from-policy {:required-fields []
                                                :market-price-missing? false
                                                :reason :spectate-mode-read-only
                                                :error-message "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."}))))

(deftest outcome-ticket-asks-for-an-amount-not-a-form-field-test
  (is (= "Enter an amount"
         (vm-submit/submit-tooltip-message ["Size"] false :validation-errors nil true)))
  (is (= "Enter an amount"
         (vm-submit/submit-tooltip-from-policy {:required-fields ["Size"] :reason :validation-errors} true)))
  (is (= "Fill required fields: Size."
         (vm-submit/submit-tooltip-from-policy {:required-fields ["Size"] :reason :validation-errors}))
      "other tickets keep naming the field")
  (is (= "Fill required fields: Size, Price."
         (vm-submit/submit-tooltip-message ["Size" "Price"] false nil nil true))
      "only the single blank amount is rephrased"))
