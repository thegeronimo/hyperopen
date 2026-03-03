(ns hyperopen.funding.effects-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.effects :as effects]))

(defn- seed-modal
  [mode]
  {:open? true
   :mode mode
   :submitting? true
   :error nil
   :amount-input "10"
   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
   :withdraw-selected-asset-key :usdc
   :withdraw-generated-address nil
   :hyperunit-lifecycle (funding-actions/default-hyperunit-lifecycle-state)
   :hyperunit-fee-estimate (funding-actions/default-hyperunit-fee-estimate-state)
   :hyperunit-withdrawal-queue (funding-actions/default-hyperunit-withdrawal-queue-state)})

(deftest api-submit-funding-transfer-no-wallet-sets-error-test
  (let [store (atom {:wallet {}
                     :funding-ui {:modal (seed-modal :transfer)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-transfer!
          {:store store
           :request {:action {:type "usdClassTransfer"
                              :amount "10"
                              :toPerp true}}
           :submit-usd-class-transfer! (fn [_store _address _action]
                                         (swap! submit-calls inc)
                                         (js/Promise.resolve {:status "ok"}))
           :show-toast! (fn [_store kind message]
                          (swap! toasts conj [kind message])
                          nil)})))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before transferring funds."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before transferring funds."]]
           @toasts))))

(deftest api-submit-funding-transfer-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal {:open? false :mode nil :submitting? false :error nil}
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :transfer)}})
          submit-calls (atom [])
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-transfer!
           {:store store
            :request {:action {:type "usdClassTransfer"
                               :amount "10"
                               :toPerp true}}
            :submit-usd-class-transfer! (fn [store* address action]
                                          (swap! submit-calls conj [store* address action])
                                          (js/Promise.resolve {:status "ok"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))
            :dispatch! (fn [store* _ event]
                         (swap! dispatches conj [store* event]))
            :default-funding-modal-state (fn [] default-modal)})
          (.then (fn [resp]
                   (is (= {:status "ok"} resp))
                   (is (= [["0xabc" {:type "usdClassTransfer"
                                     :amount "10"
                                     :toPerp true}]]
                          (mapv (fn [[_store address action]]
                                  [address action])
                                @submit-calls)))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Transfer submitted."]]
                          @toasts))
                   (is (= [[[[:actions/load-user-data "0xabc"]]]]
                          (mapv (fn [[_store event]] [event]) @dispatches)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected transfer success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-transfer-error-response-sets-error-state-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :transfer)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-transfer!
           {:store store
            :request {:action {:type "usdClassTransfer"
                               :amount "10"
                               :toPerp true}}
            :submit-usd-class-transfer! (fn [_store _address _action]
                                          (js/Promise.resolve {:status "err"
                                                               :error "insufficient margin"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (= false (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= "Transfer failed: insufficient margin"
                          (get-in @store [:funding-ui :modal :error])))
                   (is (= [[:error "Transfer failed: insufficient margin"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected transfer error-response failure: " err))
                    (done)))))))

(deftest api-submit-funding-withdraw-no-wallet-sets-error-test
  (let [store (atom {:wallet {}
                     :funding-ui {:modal (seed-modal :withdraw)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-withdraw!
          {:store store
           :request {:action {:type "withdraw3"
                              :amount "6"
                              :destination "0x1234567890abcdef1234567890abcdef12345678"}}
           :submit-withdraw3! (fn [_store _address _action]
                                (swap! submit-calls inc)
                                (js/Promise.resolve {:status "ok"}))
           :show-toast! (fn [_store kind message]
                          (swap! toasts conj [kind message])
                          nil)})))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before withdrawing."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before withdrawing."]]
           @toasts))))

(deftest api-submit-funding-withdraw-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal {:open? false :mode nil :submitting? false :error nil}
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :withdraw)}})
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-withdraw!
           {:store store
            :request {:action {:type "withdraw3"
                               :amount "6.5"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"}}
            :submit-withdraw3! (fn [_store _address _action]
                                 (js/Promise.resolve {:status "ok"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))
            :dispatch! (fn [store* _ event]
                         (swap! dispatches conj [store* event]))
            :default-funding-modal-state (fn [] default-modal)})
          (.then (fn [resp]
                   (is (= {:status "ok"} resp))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Withdrawal submitted."]]
                          @toasts))
                   (is (= [[[[:actions/load-user-data "0xabc"]]]]
                          (mapv (fn [[_store event]] [event]) @dispatches)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected withdraw success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-withdraw-runtime-error-sets-error-state-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :withdraw)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-withdraw!
           {:store store
            :request {:action {:type "withdraw3"
                               :amount "6.5"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"}}
            :submit-withdraw3! (fn [_store _address _action]
                                 (js/Promise.reject (js/Error. "network timeout")))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message])
                           nil)})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= false (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= "Withdrawal failed: network timeout"
                          (get-in @store [:funding-ui :modal :error])))
                   (is (= [[:error "Withdrawal failed: network timeout"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected withdraw runtime failure-path rejection: " err))
                    (done)))))))

(deftest api-submit-funding-withdraw-hyperunit-send-asset-polls-and-updates-lifecycle-test
  (async done
    (let [wallet-address "0x1234567890abcdef1234567890abcdef12345678"
          destination-address "bc1qexamplexyz0p4y0p4y0p4y0p4y0p4y0p4y0p"
          protocol-address "bc1qprotocolrouteaddress"
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (seed-modal :withdraw)
                                                  :withdraw-selected-asset-key :btc
                                                  :destination-input destination-address
                                                  :withdraw-generated-address nil)}})
          submit-calls (atom [])
          operation-calls (atom [])
          queue-calls (atom [])
          timeout-calls (atom 0)
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-withdraw!
           {:store store
            :request {:action {:type "hyperunitSendAssetWithdraw"
                               :asset "btc"
                               :token "BTC"
                               :amount "0.25"
                               :destination destination-address
                               :destinationChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-send-asset-withdraw-request-fn (fn [_store address action submit-send-asset-fn]
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
            :dispatch! (fn [store* _ event]
                         (swap! dispatches conj [store* event]))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [_resp]
                   (js/setTimeout
                    (fn []
                      (is (= [["0x1234567890abcdef1234567890abcdef12345678"
                               {:type "hyperunitSendAssetWithdraw"
                                :asset "btc"
                                :token "BTC"
                                :amount "0.25"
                                :destination destination-address
                                :destinationChain "bitcoin"
                                :network "Bitcoin"}
                               true]]
                             @submit-calls))
                      (is (= [{:base-url "/api/hyperunit/mainnet"
                               :base-urls ["/api/hyperunit/mainnet"
                                           "https://api.hyperunit.xyz"]
                               :address wallet-address}]
                             @operation-calls))
                      (is (= [{:base-url "/api/hyperunit/mainnet"
                               :base-urls ["/api/hyperunit/mainnet"
                                           "https://api.hyperunit.xyz"]}]
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
                    0)))
          (.catch (fn [err]
                    (is false (str "Unexpected HyperUnit withdrawal lifecycle polling error: " err))
                    (done)))))))

(deftest api-fetch-hyperunit-fee-estimate-updates-modal-on-success-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :withdraw
                                            :hyperunit-fee-estimate (funding-actions/default-hyperunit-fee-estimate-state)}}})
          clock (atom [1700000000000 1700000001000])]
      (-> (effects/api-fetch-hyperunit-fee-estimate!
           {:store store
            :request-hyperunit-estimate-fees! (fn [_opts]
                                                (js/Promise.resolve
                                                 {:by-chain {"bitcoin" {:chain "bitcoin"
                                                                        :withdrawal-eta "~20 mins"
                                                                        :withdrawal-fee "0.00001"}}}))
            :now-ms-fn (fn []
                         (let [value (first @clock)]
                           (swap! clock rest)
                           value))})
          (.then (fn [_]
                   (let [estimate (get-in @store [:funding-ui :modal :hyperunit-fee-estimate])]
                     (is (= :ready (:status estimate)))
                     (is (= 1700000000000 (:requested-at-ms estimate)))
                     (is (= 1700000001000 (:updated-at-ms estimate)))
                     (is (= "~20 mins" (get-in estimate [:by-chain "bitcoin" :withdrawal-eta])))
                     (is (= "0.00001" (get-in estimate [:by-chain "bitcoin" :withdrawal-fee])))
                     (is (nil? (:error estimate)))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected fee-estimate success-path error: " err))
                    (done)))))))

(deftest api-fetch-hyperunit-fee-estimate-sets-error-state-on-failure-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :deposit
                                            :hyperunit-fee-estimate (funding-actions/default-hyperunit-fee-estimate-state)}}})
          now-ms (atom [1700000000000 1700000001000])]
      (-> (effects/api-fetch-hyperunit-fee-estimate!
           {:store store
            :request-hyperunit-estimate-fees! (fn [_opts]
                                                (js/Promise.reject (js/Error. "gateway timeout")))
            :now-ms-fn (fn []
                         (let [value (first @now-ms)]
                           (swap! now-ms rest)
                           value))
            :runtime-error-message (fn [err]
                                     (or (some-> err .-message)
                                         "unknown"))})
          (.then (fn [result]
                   (is (map? result))
                   (let [estimate (get-in @store [:funding-ui :modal :hyperunit-fee-estimate])]
                     (is (= :error (:status estimate)))
                     (is (= 1700000000000 (:requested-at-ms estimate)))
                     (is (= 1700000001000 (:updated-at-ms estimate)))
                     (is (= "gateway timeout" (:error estimate))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected fee-estimate failure-path rejection: " err))
                    (done)))))))

(deftest api-fetch-hyperunit-fee-estimate-prefetches-existing-hyperunit-deposit-address-test
  (async done
    (let [wallet-address "0x1111111111111111111111111111111111111111"
          store (atom {:wallet {:chain-id "0xa4b1"
                                :address wallet-address}
                       :funding-ui {:modal {:open? true
                                            :mode :deposit
                                            :deposit-step :amount-entry
                                            :deposit-selected-asset-key :btc
                                            :deposit-generated-address nil
                                            :deposit-generated-signatures nil
                                            :deposit-generated-asset-key nil
                                            :hyperunit-fee-estimate (funding-actions/default-hyperunit-fee-estimate-state)}}})]
      (with-redefs [hyperopen.funding.effects/request-hyperunit-operations!
                    (fn [_opts]
                      (js/Promise.resolve
                       {:addresses [{:source-coin-type "bitcoin"
                                     :destination-chain "hyperliquid"
                                     :address "bc1qprefetched"
                                     :signatures {"hl-node" "sig-prefetched"}}]
                        :operations []}))]
        (-> (effects/api-fetch-hyperunit-fee-estimate!
             {:store store
              :request-hyperunit-estimate-fees! (fn [_opts]
                                                  (js/Promise.resolve
                                                   {:by-chain {"bitcoin" {:chain "bitcoin"
                                                                          :deposit-eta "~20 mins"
                                                                          :deposit-fee "0.00001"}}}))
              :now-ms-fn (fn [] 1700000000000)})
            (.then (fn [_]
                     (js/setTimeout
                      (fn []
                        (is (= "bc1qprefetched"
                               (get-in @store [:funding-ui :modal :deposit-generated-address])))
                        (is (= {"hl-node" "sig-prefetched"}
                               (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                        (is (= :btc
                               (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                        (done))
                      0)))
            (.catch (fn [err]
                      (is false (str "Unexpected fee-estimate deposit-prefetch error: " err))
                      (done))))))))

(deftest api-fetch-hyperunit-withdrawal-queue-updates-modal-on-success-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :withdraw
                                            :withdraw-selected-asset-key :btc
                                            :hyperunit-withdrawal-queue (funding-actions/default-hyperunit-withdrawal-queue-state)}}})
          clock (atom [1700000000000 1700000001000])]
      (-> (effects/api-fetch-hyperunit-withdrawal-queue!
           {:store store
            :request-hyperunit-withdrawal-queue! (fn [_opts]
                                                   (js/Promise.resolve
                                                    {:by-chain {"bitcoin" {:chain "bitcoin"
                                                                           :withdrawal-queue-length 4
                                                                           :last-withdraw-queue-operation-tx-id "0xqueue-next"}}}))
            :now-ms-fn (fn []
                         (let [value (first @clock)]
                           (swap! clock rest)
                           value))})
          (.then (fn [_]
                   (let [queue-state (get-in @store [:funding-ui :modal :hyperunit-withdrawal-queue])]
                     (is (= :ready (:status queue-state)))
                     (is (= 1700000000000 (:requested-at-ms queue-state)))
                     (is (= 1700000001000 (:updated-at-ms queue-state)))
                     (is (= 4 (get-in queue-state [:by-chain "bitcoin" :withdrawal-queue-length])))
                     (is (= "0xqueue-next"
                            (get-in queue-state [:by-chain "bitcoin" :last-withdraw-queue-operation-tx-id])))
                     (is (nil? (:error queue-state)))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected withdrawal-queue success-path error: " err))
                    (done)))))))

(deftest api-fetch-hyperunit-withdrawal-queue-sets-error-state-on-failure-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :withdraw
                                            :withdraw-selected-asset-key :btc
                                            :hyperunit-withdrawal-queue (funding-actions/default-hyperunit-withdrawal-queue-state)}}})
          now-ms (atom [1700000000000 1700000001000])]
      (-> (effects/api-fetch-hyperunit-withdrawal-queue!
           {:store store
            :request-hyperunit-withdrawal-queue! (fn [_opts]
                                                   (js/Promise.reject (js/Error. "queue offline")))
            :now-ms-fn (fn []
                         (let [value (first @now-ms)]
                           (swap! now-ms rest)
                           value))
            :runtime-error-message (fn [err]
                                     (or (some-> err .-message)
                                         "unknown"))})
          (.then (fn [result]
                   (is (map? result))
                   (let [queue-state (get-in @store [:funding-ui :modal :hyperunit-withdrawal-queue])]
                     (is (= :error (:status queue-state)))
                     (is (= 1700000000000 (:requested-at-ms queue-state)))
                     (is (= 1700000001000 (:updated-at-ms queue-state)))
                     (is (= "queue offline" (:error queue-state))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected withdrawal-queue failure-path rejection: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-no-wallet-sets-error-test
  (let [store (atom {:wallet {}
                     :funding-ui {:modal (seed-modal :deposit)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-deposit!
          {:store store
           :request {:action {:type "bridge2Deposit"
                              :asset "usdc"
                              :amount "5"
                              :chainId "0xa4b1"}}
           :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                           (swap! submit-calls inc)
                                           (js/Promise.resolve {:status "ok"}))
           :show-toast! (fn [_store kind message]
                          (swap! toasts conj [kind message])
                          nil)})))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before depositing."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before depositing."]]
           @toasts))))

(deftest api-submit-funding-deposit-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal {:open? false :mode nil :submitting? false :error nil}
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :deposit)}})
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "5"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.resolve {:status "ok"
                                                                 :network "Arbitrum"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))
            :dispatch! (fn [store* _ event]
                         (swap! dispatches conj [store* event]))
            :default-funding-modal-state (fn [] default-modal)})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Deposit submitted on Arbitrum."]]
                          @toasts))
                   (is (= [[[[:actions/load-user-data "0xabc"]]]]
                          (mapv (fn [[_store event]] [event]) @dispatches)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected deposit success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-usdt-route-delegates-to-lifi-submitter-test
  (async done
    (let [default-modal {:open? false :mode nil :submitting? false :error nil}
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :deposit)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "lifiUsdtToUsdcBridge2Deposit"
                               :asset "usdt"
                               :amount "10"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.resolve {:status "err"
                                                                 :error "wrong submitter"}))
            :submit-usdt-lifi-deposit! (fn [_store address action]
                                         (swap! submit-calls conj [address action])
                                         (js/Promise.resolve {:status "ok"
                                                              :network "Arbitrum"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))
            :default-funding-modal-state (fn [] default-modal)})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "lifiUsdtToUsdcBridge2Deposit"
                                     :asset "usdt"
                                     :amount "10"
                                     :chainId "0xa4b1"}]]
                          @submit-calls))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Deposit submitted on Arbitrum."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected USDT route deposit success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-usdh-route-delegates-to-across-submitter-test
  (async done
    (let [default-modal {:open? false :mode nil :submitting? false :error nil}
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :deposit)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "acrossUsdcToUsdhDeposit"
                               :asset "usdh"
                               :amount "10"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.resolve {:status "err"
                                                                 :error "wrong submitter"}))
            :submit-usdt-lifi-deposit! (fn [_store _address _action]
                                         (js/Promise.resolve {:status "err"
                                                              :error "wrong submitter"}))
            :submit-usdh-across-deposit! (fn [_store address action]
                                           (swap! submit-calls conj [address action])
                                           (js/Promise.resolve {:status "ok"
                                                                :network "Arbitrum"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))
            :default-funding-modal-state (fn [] default-modal)})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "acrossUsdcToUsdhDeposit"
                                     :asset "usdh"
                                     :amount "10"
                                     :chainId "0xa4b1"}]]
                          @submit-calls))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Deposit submitted on Arbitrum."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected USDH route deposit success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "btc"
                                                                      :deposit-address "bc1qexamplexyz"
                                                                      :deposit-signatures [{:r "0x1"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "btc"
                                     :fromChain "bitcoin"
                                     :network "Bitcoin"}]]
                          @submit-calls))
                   (is (= "bc1qexamplexyz"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x1"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :btc
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest select-existing-hyperunit-deposit-address-normalizes-chain-aliases-and-validates-address-shape-test
  (let [select-existing-address
        @#'hyperopen.funding.effects/select-existing-hyperunit-deposit-address]
    (is (= {:address "bc1qalias"
            :signatures {"node-a" "sig-a"}}
           (select-existing-address
            {:addresses [{:source-coin-type "btc"
                          :destination-chain "hyperliquid"
                          :address "bc1qalias"
                          :signatures {"node-a" "sig-a"}}]
             :operations []}
            "bitcoin"
            "btc"
            "0xabc")))
    (is (= {:address "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s"
            :signatures {"node-b" "sig-b"}}
           (select-existing-address
            {:addresses [{:source-coin-type "unknown-source"
                          :destination-chain "hyperliquid"
                          :address "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s"
                          :signatures {"node-b" "sig-b"}}]
             :operations []}
            "bitcoin"
            "btc"
            "0xabc")))
    (is (nil? (select-existing-address
               {:addresses [{:source-coin-type "bitcoin"
                             :destination-chain "hyperliquid"
                             :address "bc1qbtc"
                             :signatures {"node-c" "sig-c"}}]
                :operations []}
               "ethereum"
               "eth"
               "0xabc")))
    (is (= {:address "0xethchain"
            :signatures {"node-d" "sig-d"}}
           (select-existing-address
            {:addresses [{:source-chain "ethereum"
                          :destination-chain "hyperliquid"
                          :address "0xethchain"
                          :signatures {"node-d" "sig-d"}}]
             :operations []}
            "ethereum"
            "eth"
            "0xabc")))
    (is (= {:address "0x1111111111111111111111111111111111111111"
            :signatures {"node-e" "sig-e"}}
           (select-existing-address
            {:addresses [{:source-coin-type "unknown-source"
                          :destination-chain "hyperliquid"
                          :address "0x1111111111111111111111111111111111111111"
                          :signatures {"node-e" "sig-e"}}]
             :operations []}
            "ethereum"
            "eth"
            "0xabc")))))

(deftest hyperunit-source-chain-candidates-normalizes-aliases-test
  (let [source-chain-candidates
        @#'hyperopen.funding.effects/hyperunit-source-chain-candidates]
    (is (= ["ethereum" "eth"] (source-chain-candidates "ethereum")))
    (is (= ["ethereum" "eth"] (source-chain-candidates "eth")))
    (is (= ["bitcoin" "btc"] (source-chain-candidates "btc")))
    (is (= ["solana" "sol"] (source-chain-candidates "solana")))
    (is (= ["monad"] (source-chain-candidates "monad")))))

(deftest protocol-address-matches-source-chain-validates-address-shapes-test
  (let [matches? @#'hyperopen.funding.effects/protocol-address-matches-source-chain?]
    (is (= true (matches? "bitcoin" "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s")))
    (is (= true (matches? "ethereum" "0x1111111111111111111111111111111111111111")))
    (is (= true (matches? "solana" "So11111111111111111111111111111111111111112")))
    (is (= false (matches? "ethereum" "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s")))
    (is (= false (matches? "bitcoin" "0x1111111111111111111111111111111111111111")))))

(deftest request-hyperunit-operations-retries-direct-base-url-after-proxy-failure-test
  (async done
    (let [calls (atom [])]
      (-> (@#'hyperopen.funding.effects/with-hyperunit-base-url-fallbacks!
           {:base-url "/api/hyperunit/mainnet"
            :base-urls ["/api/hyperunit/mainnet"
                        "https://api.hyperunit.xyz"]
            :request-fn (fn [candidate-base-url]
                          (swap! calls conj candidate-base-url)
                          (if (= "/api/hyperunit/mainnet" candidate-base-url)
                            (js/Promise.reject (js/Error. "proxy unavailable"))
                            (js/Promise.resolve {:operations [{:operation-id "op-1"}]})))
            :error-message "Unable to load HyperUnit operations."})
          (.then (fn [resp]
                   (is (= ["/api/hyperunit/mainnet"
                           "https://api.hyperunit.xyz"]
                          @calls))
                   (is (= "op-1"
                          (get-in resp [:operations 0 :operation-id])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected base-url fallback error: " err))
                    (done)))))))

(deftest submit-hyperunit-address-deposit-request-reuses-existing-address-before-generate-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}})
          operations-calls (atom 0)
          generate-calls (atom 0)
          wallet-address "0x1111111111111111111111111111111111111111"]
      (with-redefs [hyperopen.funding.effects/request-hyperunit-operations!
                    (fn [_opts]
                      (swap! operations-calls inc)
                      (js/Promise.resolve
                       {:addresses [{:source-coin-type "bitcoin"
                                     :destination-chain "hyperliquid"
                                     :address "bc1qexisting"
                                     :signatures {"hl-node" "sig-a"}}]
                        :operations []}))
                    hyperopen.funding.effects/fetch-hyperunit-address!
                    (fn [& _args]
                      (swap! generate-calls inc)
                      (js/Promise.resolve {:address "bc1qgenerated"
                                           :signatures {"hl-node" "sig-generated"}}))]
        (-> (@#'hyperopen.funding.effects/submit-hyperunit-address-deposit-request!
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
            (.catch (fn [err]
                      (is false (str "Unexpected existing-address reuse failure: " err))
                      (done))))))))

(deftest hyperunit-request-error-message-network-failure-is-actionable-test
  (let [message (@#'hyperopen.funding.effects/hyperunit-request-error-message
                 (js/Error. "Failed to fetch")
                 {:asset "eth"
                  :source-chain "ethereum"})]
    (is (str/includes? message
                       "Unable to reach HyperUnit address service for ETH on Ethereum"))))

(deftest api-submit-funding-deposit-hyperunit-address-reused-response-shows-existing-address-toast-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])]
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
                                                                      :deposit-address "bc1qexisting"
                                                                      :deposit-signatures {"guardian-a" "sig-a"}
                                                                      :reused-address? true}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= true (:reused-address? resp)))
                   (is (= [[:success "Using existing deposit address."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected reused-address toast-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-sync-submitter-throw-sets-error-and-clears-submitting-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :btc)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "btc"
                               :fromChain "bitcoin"
                               :network "Bitcoin"}}
            :submit-hyperunit-address-request! (fn [_store _address _action]
                                                 (throw (js/Error. "sync submitter failure")))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= [[:error "Deposit failed: sync submitter failure"]]
                          resp))
                   (is (= false (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= "Deposit failed: sync submitter failure"
                          (get-in @store [:funding-ui :modal :error])))
                   (is (= [[:error "Deposit failed: sync submitter failure"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected sync-submitter throw-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-hyperunit-address-terminal-lifecycle-refreshes-user-data-test
  (async done
    (let [wallet-address "0xabc"
          deposit-address "bc1qexamplexyz"
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
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
            :dispatch! (fn [store* _ event]
                         (swap! dispatches conj [store* event]))})
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
          (.catch (fn [err]
                    (is false (str "Unexpected HyperUnit deposit terminal refresh error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-eth-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :eth
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "eth"
                               :fromChain "ethereum"
                               :network "Ethereum"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "eth"
                                                                      :deposit-address "0xfeedbeef"
                                                                      :deposit-signatures [{:r "0x2"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "eth"
                                     :fromChain "ethereum"
                                     :network "Ethereum"}]]
                          @submit-calls))
                   (is (= "0xfeedbeef"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x2"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :eth
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected ETH HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-sol-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :sol
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "sol"
                               :fromChain "solana"
                               :network "Solana"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "sol"
                                                                      :deposit-address "solanaAddressExample"
                                                                      :deposit-signatures [{:r "0x3"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "sol"
                                     :fromChain "solana"
                                     :network "Solana"}]]
                          @submit-calls))
                   (is (= "solanaAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x3"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :sol
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected SOL HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-2z-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :2z
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "2z"
                               :fromChain "solana"
                               :network "Solana"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "2z"
                                                                      :deposit-address "zzAddressExample"
                                                                      :deposit-signatures [{:r "0x4"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "2z"
                                     :fromChain "solana"
                                     :network "Solana"}]]
                          @submit-calls))
                   (is (= "zzAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x4"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :2z
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected 2Z HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-bonk-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :bonk
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "bonk"
                               :fromChain "solana"
                               :network "Solana"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "bonk"
                                                                      :deposit-address "bonkAddressExample"
                                                                      :deposit-signatures [{:r "0x5"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "bonk"
                                     :fromChain "solana"
                                     :network "Solana"}]]
                          @submit-calls))
                   (is (= "bonkAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x5"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :bonk
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected BONK HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-ena-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :ena
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "ena"
                               :fromChain "ethereum"
                               :network "Ethereum"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "ena"
                                                                      :deposit-address "0xenaAddressExample"
                                                                      :deposit-signatures [{:r "0x6"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "ena"
                                     :fromChain "ethereum"
                                     :network "Ethereum"}]]
                          @submit-calls))
                   (is (= "0xenaAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x6"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :ena
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected ENA HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-fart-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :fart
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "fart"
                               :fromChain "solana"
                               :network "Solana"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "fart"
                                                                      :deposit-address "fartAddressExample"
                                                                      :deposit-signatures [{:r "0x7"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "fart"
                                     :fromChain "solana"
                                     :network "Solana"}]]
                          @submit-calls))
                   (is (= "fartAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x7"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :fart
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected FART HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-mon-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :mon
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "mon"
                               :fromChain "monad"
                               :network "Monad"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "mon"
                                                                      :deposit-address "monAddressExample"
                                                                      :deposit-signatures [{:r "0x8"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "mon"
                                     :fromChain "monad"
                                     :network "Monad"}]]
                          @submit-calls))
                   (is (= "monAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x8"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :mon
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected MON HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-pump-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :pump
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "pump"
                               :fromChain "solana"
                               :network "Solana"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "pump"
                                                                      :deposit-address "pumpAddressExample"
                                                                      :deposit-signatures [{:r "0x9"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "pump"
                                     :fromChain "solana"
                                     :network "Solana"}]]
                          @submit-calls))
                   (is (= "pumpAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0x9"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :pump
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected PUMP HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-spxs-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :spxs
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "spxs"
                               :fromChain "solana"
                               :network "Solana"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "spxs"
                                                                      :deposit-address "spxAddressExample"
                                                                      :deposit-signatures [{:r "0xa"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "spxs"
                                     :fromChain "solana"
                                     :network "Solana"}]]
                          @submit-calls))
                   (is (= "spxAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0xa"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :spxs
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected SPX HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-xpl-hyperunit-address-keeps-modal-open-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
                                                  :deposit-step :amount-entry
                                                  :deposit-selected-asset-key :xpl
                                                  :deposit-generated-address nil
                                                  :deposit-generated-signatures nil
                                                  :deposit-generated-asset-key nil)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "hyperunitGenerateDepositAddress"
                               :asset "xpl"
                               :fromChain "plasma"
                               :network "Plasma"}}
            :submit-hyperunit-address-request! (fn [_store address action]
                                                 (swap! submit-calls conj [address action])
                                                 (js/Promise.resolve {:status "ok"
                                                                      :keep-modal-open? true
                                                                      :asset "xpl"
                                                                      :deposit-address "xplAddressExample"
                                                                      :deposit-signatures [{:r "0xb"}]}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabc" {:type "hyperunitGenerateDepositAddress"
                                     :asset "xpl"
                                     :fromChain "plasma"
                                     :network "Plasma"}]]
                          @submit-calls))
                   (is (= "xplAddressExample"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= [{:r "0xb"}]
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :xpl
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (is (= false
                          (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= [[:success "Deposit address generated."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected XPL HyperUnit deposit-address success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-runtime-error-sets-error-state-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (seed-modal :deposit)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-deposit!
           {:store store
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "5"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.reject (js/Error. "wallet unavailable")))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message])
                           nil)})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= false (get-in @store [:funding-ui :modal :submitting?])))
                   (is (= "Deposit failed: wallet unavailable"
                          (get-in @store [:funding-ui :modal :error])))
                   (is (= [[:error "Deposit failed: wallet unavailable"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                   (is false (str "Unexpected deposit runtime failure-path rejection: " err))
                   (done)))))))

(deftest api-submit-funding-deposit-hyperunit-address-polls-and-updates-lifecycle-test
  (async done
    (let [wallet-address "0x1234567890abcdef1234567890abcdef12345678"
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
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
                      (is (= [{:base-url "/api/hyperunit/mainnet"
                               :base-urls ["/api/hyperunit/mainnet"
                                           "https://api.hyperunit.xyz"]
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
          (.catch (fn [err]
                    (is false (str "Unexpected HyperUnit lifecycle polling error: " err))
                    (done)))))))

(deftest api-submit-funding-deposit-hyperunit-address-schedules-next-poll-from-state-next-attempt-test
  (async done
    (let [wallet-address "0x1234567890abcdef1234567890abcdef12345678"
          now-ms 1700000000000
          state-next-at-ms (+ now-ms 4500)
          state-next-at-text (.toISOString (js/Date. state-next-at-ms))
          store (atom {:wallet {:address wallet-address}
                       :funding-ui {:modal (assoc (seed-modal :deposit)
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
          (.catch (fn [err]
                    (is false (str "Unexpected HyperUnit lifecycle backoff error: " err))
                    (done)))))))
