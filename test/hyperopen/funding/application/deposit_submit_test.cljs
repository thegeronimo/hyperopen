(ns hyperopen.funding.application.deposit-submit-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.test-support.async :as async-support]))

(defn- submit-deps
  [overrides]
  (merge (effects-support/base-submit-effect-deps)
         overrides))

(deftest api-submit-funding-deposit-blocks-mutations-while-spectate-mode-active-test
  (let [store (atom {:wallet {:address "0xabc"}
                     :account-context {:spectate-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}}
                     :funding-ui {:modal (effects-support/seed-modal :deposit)}})
        toasts (atom [])]
    (effects/api-submit-funding-deposit!
     (submit-deps
      {:store store
      :request {:action {:type "bridge2Deposit"
                         :asset "usdc"
                         :amount "7"
                         :chainId "0xa4b1"}}
      :show-toast! (effects-support/capture-toast! toasts)}))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= account-context/spectate-mode-read-only-message
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error account-context/spectate-mode-read-only-message]]
           @toasts))))

(deftest api-submit-funding-deposit-no-wallet-sets-error-test
  (let [store (atom {:wallet {}
                     :funding-ui {:modal (effects-support/seed-modal :deposit)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-deposit!
          (submit-deps
           {:store store
           :request {:action {:type "bridge2Deposit"
                              :asset "usdc"
                              :amount "5"
                              :chainId "0xa4b1"}}
           :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                           (swap! submit-calls inc)
                                           (js/Promise.resolve {:status "ok"}))
           :show-toast! (effects-support/capture-toast! toasts)}))))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before depositing."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before depositing."]]
           @toasts))))

(deftest api-submit-funding-deposit-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "5"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.resolve {:status "ok"
                                                                 :network "Arbitrum"}))
            :show-toast! (effects-support/capture-toast! toasts)
            :dispatch! (effects-support/capture-dispatch! dispatches)
            :default-funding-modal-state (fn [] default-modal)}))
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
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
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
            :show-toast! (effects-support/capture-toast! toasts)
            :default-funding-modal-state (fn [] default-modal)}))
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
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])
          submit-calls (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
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
            :show-toast! (effects-support/capture-toast! toasts)
            :default-funding-modal-state (fn [] default-modal)}))
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

(deftest api-submit-funding-deposit-runtime-error-sets-error-state-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :deposit)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-deposit!
           (submit-deps
            {:store store
            :request {:action {:type "bridge2Deposit"
                               :asset "usdc"
                               :amount "5"
                               :chainId "0xa4b1"}}
            :submit-usdc-bridge2-deposit! (fn [_store _address _action]
                                            (js/Promise.reject (js/Error. "wallet unavailable")))
            :show-toast! (effects-support/capture-toast! toasts)}))
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
