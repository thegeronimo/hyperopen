(ns hyperopen.funding.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.effects :as effects]))

(defn- seed-modal
  [mode]
  {:open? true
   :mode mode
   :submitting? true
   :error nil
   :amount-input "10"
   :destination-input "0x1234567890abcdef1234567890abcdef12345678"})

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
