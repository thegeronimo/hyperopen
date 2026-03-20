(ns hyperopen.funding.application.lifecycle-polling-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.effects :as effects]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.test-support.async :as async-support]))

(deftest api-submit-funding-deposit-hyperunit-address-terminal-lifecycle-refreshes-user-data-test
  (async done
    (let [wallet-address "0xabc"
          deposit-address "bc1qexamplexyz"
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (effects-support/seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          dispatches (atom [])
          timeout-calls (atom 0)]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-address-request! (fn [_store _address _action]
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "btc"
                                                                      :deposit-address deposit-address
                                                                      :deposit-signatures [{:r "0x1"}]}))
            :request-hyperunit-operations! (fn [_opts]
                                             (js/Promise.resolve
                                              {:operations [{:operation-id "op_d1"
                                                             :asset "btc"
                                                             :protocol-address deposit-address
                                                             :destination-address wallet-address
                                                             :state-key :done
                                                             :status "completed"}]}))
            :set-timeout-fn (fn [_f _delay-ms]
                              (swap! timeout-calls inc)
                              :timer-id)
            :dispatch! (effects-support/capture-dispatch! dispatches)})
          (.then (fn [_resp]
                   (js/setTimeout
                    (fn []
                      (is (= [[[[:actions/load-user-data wallet-address]]]]
                             (mapv (fn [[_store event]] [event]) @dispatches)))
                      (is (= 0 @timeout-calls))
                      (is (= :done
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :state])))
                      (done))
                    0)))
          (.catch (async-support/unexpected-error done))))))

(deftest api-submit-funding-deposit-hyperunit-address-polls-and-updates-lifecycle-test
  (async done
    (let [wallet-address "0x1234567890abcdef1234567890abcdef12345678"
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (effects-support/seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          operation-calls (atom [])
          timeout-calls (atom 0)]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-address-request! (fn [_store _address _action]
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "btc"
                                                                      :deposit-address "bc1qexamplexyz"
                                                                      :deposit-signatures [{:r "0x1"}]}))
            :request-hyperunit-operations! (fn [opts]
                                             (swap! operation-calls conj opts)
                                             (js/Promise.resolve
                                              {:operations [{:operation-id "op_123"
                                                             :asset "btc"
                                                             :protocol-address "bc1qexamplexyz"
                                                             :destination-address wallet-address
                                                             :state-key :done
                                                             :status "completed"
                                                             :source-tx-confirmations 6
                                                             :destination-tx-hash "0xabc"}]}))
            :set-timeout-fn (fn [_f _delay-ms]
                              (swap! timeout-calls inc)
                              :timer-id)})
          (.then (fn [_resp]
                   (js/setTimeout
                    (fn []
                      (is (= [{:base-url "https://api.hyperunit.xyz"
                               :base-urls ["https://api.hyperunit.xyz"]
                               :address wallet-address}]
                             @operation-calls))
                      (is (= :deposit
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :direction])))
                      (is (= :btc
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :asset-key])))
                      (is (= "op_123"
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :operation-id])))
                      (is (= :done
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :state])))
                      (is (= :completed
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :status])))
                      (is (= 0 @timeout-calls))
                      (done))
                    0)))
          (.catch (async-support/unexpected-error done))))))

(deftest api-submit-funding-deposit-hyperunit-address-schedules-next-poll-from-state-next-attempt-test
  (async done
    (let [wallet-address "0x1234567890abcdef1234567890abcdef12345678"
          now-ms 1700000000000
          state-next-at-ms (+ now-ms 4500)
          state-next-at-text (.toISOString (js/Date. state-next-at-ms))
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (effects-support/seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          scheduled-delays (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-address-request! (fn [_store _address _action]
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "btc"
                                                                      :deposit-address "bc1qexamplexyz"
                                                                      :deposit-signatures [{:r "0x1"}]}))
            :request-hyperunit-operations! (fn [_opts]
                                             (js/Promise.resolve
                                              {:operations [{:operation-id "op_124"
                                                             :asset "btc"
                                                             :protocol-address "bc1qexamplexyz"
                                                             :destination-address wallet-address
                                                             :state-key :wait-for-src-tx-finalization
                                                             :status "pending"
                                                             :state-next-attempt-at state-next-at-text}]}))
            :set-timeout-fn (fn [_f delay-ms]
                              (swap! scheduled-delays conj delay-ms)
                              :timer-id)
            :now-ms-fn (fn [] now-ms)})
          (.then (fn [_resp]
                   (js/setTimeout
                    (fn []
                      (is (= [4500] @scheduled-delays))
                      (is (= :wait-for-src-tx-finalization
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :state])))
                      (is (= :pending
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :status])))
                      (done))
                    0)))
          (.catch (async-support/unexpected-error done))))))
