(ns hyperopen.order.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.order.actions :as order-actions]))

(deftest expand-order-feedback-toast-persists-blotter-and-clears-timeout-test
  (let [state {:ui {:toasts [{:id "toast-1"
                              :message "4 fills"
                              :toast-surface :trade-confirmation
                              :variant :consolidated}
                             {:id "toast-2"
                              :message "Order placed"}]}}]
    (is (= [[:effects/save-many
             [[[:ui :toasts]
               [{:id "toast-1"
                 :message "4 fills"
                 :toast-surface :trade-confirmation
                 :variant :consolidated
                 :expanded? true
                 :auto-timeout? false}
                {:id "toast-2"
                 :message "Order placed"}]]
              [[:ui :toast]
               {:message "Order placed"}]]]
            [:effects/clear-order-feedback-toast-timeout "toast-1"]]
           (order-actions/expand-order-feedback-toast state "toast-1")))))
