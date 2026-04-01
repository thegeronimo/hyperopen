(ns hyperopen.funding.application.hyperunit-submit-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.funding.application.hyperunit-submit :as hyperunit-submit]
            [hyperopen.funding.application.submit-effects :as submit-effects]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.funding.test-support.hyperunit :as hyperunit-fixtures]
            [hyperopen.test-support.async :as async-support]))

(defn- submit-deps
  [overrides]
  (merge (effects-support/base-submit-effect-deps)
         overrides))

(defn- non-blank-text
  [value]
  (when (string? value)
    (let [trimmed (.trim value)]
      (when (seq trimmed)
        trimmed))))

(deftest submit-hyperunit-address-deposit-request-reuses-existing-address-before-generate-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}})
          operations-calls (atom 0)
          generate-calls (atom 0)
          wallet-address "0x1111111111111111111111111111111111111111"]
      (-> (hyperunit-submit/submit-hyperunit-address-deposit-request!
           {:normalize-address identity
            :non-blank-text non-blank-text
            :resolve-hyperunit-base-urls (fn [_store] ["https://api.hyperunit.xyz"])
            :request-existing-hyperunit-deposit-address!
            (fn [_base-url _base-urls _destination-address _source-chain _asset]
              (swap! operations-calls inc)
              (js/Promise.resolve {:address "bc1qexisting"
                                   :signatures {"hl-node" "sig-a"}}))
            :fetch-hyperunit-address-with-source-fallbacks!
            (fn [& _args]
              (swap! generate-calls inc)
              (js/Promise.resolve {:address "bc1qgenerated"
                                   :signatures {"hl-node" "sig-generated"}}))
            :hyperunit-request-error-message (fn [_err _ctx] "unexpected")}
           store
           wallet-address
           {:asset "btc"
            :fromChain "bitcoin"
            :network "Bitcoin"})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= true (:reused-address? resp)))
                   (is (= "bc1qexisting" (:deposit-address resp)))
                   (is (= 1 @operations-calls))
                   (is (= 0 @generate-calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest api-submit-funding-withdraw-hyperunit-send-asset-polls-and-updates-lifecycle-test
  (async done
    (let [wallet-address "0x1234567890abcdef1234567890abcdef12345678"
          destination-address "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
          protocol-address "bc1qprotocolrouteaddress"
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (effects-support/seed-modal :withdraw)
                                                  :withdraw-selected-asset-key :btc
                                                  :destination-input destination-address
                                                  :withdraw-generated-address nil)}})
          submit-calls (atom [])
          operation-calls (atom [])
          queue-calls (atom [])
          timeout-calls (atom 0)
          toasts (atom [])
          dispatches (atom [])]
      (-> (submit-effects/api-submit-funding-withdraw!
           (submit-deps
            {:store store
            :request {:action {:type "hyperunitSendAssetWithdraw"
                               :asset "btc"
                               :token "BTC"
                               :amount "0.25"
                               :destination destination-address
                               :destinationChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-send-asset-withdraw-request-fn
            (fn [_store address action submit-send-asset-fn]
              (swap! submit-calls conj [address action (fn? submit-send-asset-fn)])
              (js/Promise.resolve {:status "ok"
                                   :keep-modal-open? true
                                   :asset "btc"
                                   :destination destination-address
                                   :protocol-address protocol-address
                                   :network "Bitcoin"}))
            :request-hyperunit-operations! (fn [opts]
                                             (swap! operation-calls conj opts)
                                             (js/Promise.resolve
                                              {:operations [{:operation-id "op_w1"
                                                             :asset "btc"
                                                             :protocol-address protocol-address
                                                             :source-address wallet-address
                                                             :destination-address destination-address
                                                             :state-key :done
                                                             :status "completed"
                                                             :position-in-withdraw-queue 2
                                                             :destination-tx-hash "0xwithdraw"}]}))
            :request-hyperunit-withdrawal-queue! (fn [opts]
                                                   (swap! queue-calls conj opts)
                                                   (js/Promise.resolve
                                                    {:by-chain {"bitcoin" {:chain "bitcoin"
                                                                           :withdrawal-queue-length 7
                                                                           :last-withdraw-queue-operation-tx-id "0xqueue-op"}}}))
            :set-timeout-fn (fn [_f _delay-ms]
                              (swap! timeout-calls inc)
                              :timer-id)
            :dispatch! (effects-support/capture-dispatch! dispatches)
            :show-toast! (effects-support/capture-toast! toasts)}))
          (.then (fn [_resp]
                   (js/setTimeout
                    (fn []
                      (is (= [[wallet-address
                               {:type "hyperunitSendAssetWithdraw"
                                :asset "btc"
                                :token "BTC"
                                :amount "0.25"
                                :destination destination-address
                                :destinationChain "bitcoin"
                                :network "Bitcoin"}
                               true]]
                             @submit-calls))
                      (is (= [{:base-url "https://api.hyperunit.xyz"
                               :base-urls ["https://api.hyperunit.xyz"]
                               :address wallet-address}]
                             @operation-calls))
                      (is (= [{:base-url "https://api.hyperunit.xyz"
                               :base-urls ["https://api.hyperunit.xyz"]}]
                             @queue-calls))
                      (is (= protocol-address
                             (get-in @store [:funding-ui :modal :withdraw-generated-address])))
                      (is (= :withdraw
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :direction])))
                      (is (= :btc
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :asset-key])))
                      (is (= "op_w1"
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :operation-id])))
                      (is (= :done
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :state])))
                      (is (= :completed
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :status])))
                      (is (= 2
                             (get-in @store [:funding-ui :modal :hyperunit-lifecycle :position-in-withdraw-queue])))
                      (is (= :ready
                             (get-in @store [:funding-ui :modal :hyperunit-withdrawal-queue :status])))
                      (is (= 7
                             (get-in @store [:funding-ui :modal :hyperunit-withdrawal-queue :by-chain "bitcoin" :withdrawal-queue-length])))
                      (is (= 0 @timeout-calls))
                      (is (= [[[[:actions/load-user-data wallet-address]]]]
                             (mapv (fn [[_store event]] [event]) @dispatches)))
                      (is (= [[:success "Withdrawal submitted."]]
                             @toasts))
                      (done))
                    20)))
          (.catch (async-support/unexpected-error done))))))

(deftest api-submit-funding-deposit-hyperunit-address-keeps-modal-open-for-supported-assets-test
  (async done
    (let [promises
          (mapv (fn [{:keys [asset from-chain network generated-address signature]}]
                  (let [store (atom {:wallet {:address "0xabc"}
                                     :funding-ui {:modal (assoc (effects-support/seed-modal :deposit)
                                                                :deposit-step :amount-entry
                                                                :deposit-selected-asset-key (keyword asset)
                                                                :deposit-generated-address nil
                                                                :deposit-generated-signatures nil
                                                                :deposit-generated-asset-key nil)}})
                        toasts (atom [])
                        submit-calls (atom [])]
                    (-> (submit-effects/api-submit-funding-deposit!
                         (submit-deps
                          {:store store
                          :request {:action {:type "hyperunitGenerateDepositAddress"
                                             :asset asset
                                             :fromChain from-chain
                                             :network network}}
                          :submit-hyperunit-address-request! (fn [_store address action]
                                                               (swap! submit-calls conj [address action])
                                                               (js/Promise.resolve {:status "ok"
                                                                                    :keep-modal-open? true
                                                                                    :asset asset
                                                                                    :deposit-address generated-address
                                                                                    :deposit-signatures [signature]}))
                          :show-toast! (effects-support/capture-toast! toasts)}))
                        (.then (fn [resp]
                                 (testing (str asset " deposit address generation")
                                   (is (= "ok" (:status resp)))
                                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                                     :asset asset
                                                     :fromChain from-chain
                                                     :network network}]]
                                          @submit-calls))
                                   (is (= generated-address
                                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                                   (is (= [signature]
                                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                                   (is (= (keyword asset)
                                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                                   (is (= false
                                          (get-in @store [:funding-ui :modal :submitting?])))
                                   (is (= [[:success "Deposit address generated."]]
                                          @toasts)))
                                 true)))))
                hyperunit-fixtures/deposit-address-cases)]
      (-> (js/Promise.all (into-array promises))
          (.then (fn [_] (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest api-submit-funding-deposit-hyperunit-address-reused-response-shows-existing-address-toast-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (effects-support/seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])]
      (-> (submit-effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-address-request! (fn [_store _address _action]
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                 :asset "btc"
                                                 :deposit-address "bc1qexisting"
                                                 :deposit-signatures {"guardian-a" "sig-a"}
                                                 :reused-address? true}))
            :show-toast! (effects-support/capture-toast! toasts)}))
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= true (:reused-address? resp)))
                   (is (= [[:success "Using existing deposit address."]]
                          @toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest api-submit-funding-deposit-sync-submitter-throw-sets-error-and-clears-submitting-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (effects-support/seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc)}})
          toasts (atom [])]
      (-> (submit-effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-address-request! (fn [_store _address _action]
                                                 (throw (js/Error. "sync submitter failure")))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))}))
          (.then (fn [resp]
                   (is (= [[:error "Deposit failed: sync submitter failure"]]
                          resp))
                   (is (= false (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= "Deposit failed: sync submitter failure"
                          (get-in @store [:funding-ui :modal :error])))
                   (is (= [[:error "Deposit failed: sync submitter failure"]]
                          @toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
