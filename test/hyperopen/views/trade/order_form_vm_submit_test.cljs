(ns hyperopen.views.trade.order-form-vm-submit-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form-vm-submit :as vm-submit]))

(deftest submit-tooltip-message-branch-coverage-test
  (is (= "Fill required fields: Size, Price."
         (vm-submit/submit-tooltip-message ["Size" "Price"] false nil nil)))
  (is (= "Load order book data before placing a market order."
         (vm-submit/submit-tooltip-message [] true nil nil)))
  (is (= "Ghost Mode is read-only. Stop Ghost Mode to place trades or move funds."
         (vm-submit/submit-tooltip-message [] false :ghost-mode-read-only
                                           "Ghost Mode is read-only. Stop Ghost Mode to place trades or move funds.")))
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
  (is (= "Ghost Mode is read-only. Stop Ghost Mode to place trades or move funds."
         (vm-submit/submit-tooltip-from-policy {:required-fields []
                                                :market-price-missing? false
                                                :reason :ghost-mode-read-only
                                                :error-message "Ghost Mode is read-only. Stop Ghost Mode to place trades or move funds."}))))
