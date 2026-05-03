(ns hyperopen.core-bootstrap.order-effects.agent-recovery-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core.compat :as core]
            [hyperopen.core-bootstrap.order-effects.test-support :as support]))

(deftest api-submit-order-effect-opens-enable-trading-recovery-modal-for-inconclusive-agent-wallet-lookup-test
  (async done
    (let [store (atom (support/base-submit-order-store))
          original-submit-order trading-api/submit-order!]
      (support/clear-order-feedback-toast-timeout!)
      (set! trading-api/submit-order!
            (fn [store _address _action]
              (swap! store update-in [:wallet :agent] merge
                     {:status :error
                      :error "Agent wallet lookup was inconclusive. Preserved local trading key."
                      :recovery-modal-open? true})
              (js/Promise.resolve
               {:status "err"
                :error "Agent wallet lookup was inconclusive. Preserved local trading key."
                :response "User or API Wallet 0xf1a4e7adfdbdb118d5ce167427e9de1de3a41568 does not exist."})))
      (core/api-submit-order nil store {:action {:type "order"
                                                 :orders []
                                                 :grouping "na"}})
      (js/setTimeout
       (fn []
         (try
           (is (false? (get-in @store [:order-form-runtime :submitting?])))
           (is (nil? (get-in @store [:order-form-runtime :error])))
           (is (= :error (get-in @store [:wallet :agent :status])))
           (is (= "Agent wallet lookup was inconclusive. Preserved local trading key."
                  (get-in @store [:wallet :agent :error])))
           (is (true? (get-in @store [:wallet :agent :recovery-modal-open?])))
           (is (nil? (get-in @store [:ui :toast])))
           (finally
             (support/clear-order-feedback-toast-timeout!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))
