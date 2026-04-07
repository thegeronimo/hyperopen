(ns hyperopen.funding.effects.facade-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.effects :as effects]
            [hyperopen.ui.dialog-focus-runtime :as dialog-focus-runtime]))

(deftest funding-effect-facade-helpers-cover-modal-reset-refresh-and-submit-errors-test
  (let [initial-state {:funding-ui {:modal {:submitting? true
                                            :error nil
                                            :open? true
                                            :opener-data-role "funding-action-deposit"
                                            :focus-return-token 4}}}
        toast-calls (atom [])
        dispatch-calls (atom [])
        restore-focus-calls (atom 0)
        modal-store (atom initial-state)]
    (is (= {:funding-ui {:modal {:submitting? false
                                 :error "failed"
                                 :open? true
                                 :opener-data-role "funding-action-deposit"
                                 :focus-return-token 4}}}
           (effects/update-funding-submit-error initial-state "failed")))
    (effects/set-funding-submit-error! modal-store
                                       (fn [_store kind message]
                                         (swap! toast-calls conj [kind message]))
                                       "toast failure")
    (is (= false (get-in @modal-store [:funding-ui :modal :submitting?])))
    (is (= "toast failure" (get-in @modal-store [:funding-ui :modal :error])))
    (is (= [[:error "toast failure"]] @toast-calls))
    (with-redefs [dialog-focus-runtime/restore-remembered-focus! (fn
                                                                   ([] (swap! restore-focus-calls inc))
                                                                   ([_dialog-node] (swap! restore-focus-calls inc)))]
      (effects/close-funding-modal! modal-store
                                    (fn []
                                      {:open? false
                                       :mode nil})))
    (is (= {:open? false
            :mode nil
            :focus-return-data-role "funding-action-deposit"
            :focus-return-token 5}
           (get-in @modal-store [:funding-ui :modal])))
    (is (= 1 @restore-focus-calls))
    (effects/refresh-after-funding-submit! modal-store
                                           (fn [_store _ctx event]
                                             (swap! dispatch-calls conj event))
                                           "0xabc")
    (effects/refresh-after-funding-submit! modal-store
                                           (fn [_store _ctx event]
                                             (swap! dispatch-calls conj event))
                                           nil)
    (effects/refresh-after-funding-submit! modal-store nil "0xabc")
    (is (= [[[:actions/load-user-data "0xabc"]]]
           @dispatch-calls))))
