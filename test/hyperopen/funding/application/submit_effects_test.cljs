(ns hyperopen.funding.application.submit-effects-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.test-support.async :as async-support]))

(defn- submit-deps
  [overrides]
  (merge (effects-support/base-submit-effect-deps)
         overrides))

(deftest api-submit-funding-send-no-wallet-sets-error-test
  (let [store (atom {:wallet {}
                     :funding-ui {:modal (assoc (effects-support/seed-modal :send)
                                                :send-token "USDC"
                                                :send-symbol "USDC"
                                                :send-max-amount 12.5)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-send!
          (submit-deps
           {:store store
           :request {:action {:type "sendAsset"
                              :destination "0x1234567890abcdef1234567890abcdef12345678"
                              :sourceDex "spot"
                              :destinationDex "spot"
                              :token "USDC"
                              :amount "10"
                              :fromSubAccount ""}}
           :submit-send-asset! (fn [_store _address _action]
                                 (swap! submit-calls inc)
                                 (js/Promise.resolve {:status "ok"}))
           :show-toast! (effects-support/capture-toast! toasts)}))))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before sending tokens."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before sending tokens."]]
           @toasts))))

(deftest api-submit-funding-send-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (assoc (effects-support/seed-modal :send)
                                                  :send-token "USDC"
                                                  :send-symbol "USDC"
                                                  :send-max-amount 12.5)}})
          submit-calls (atom [])
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-send!
           (submit-deps
            {:store store
            :request {:action {:type "sendAsset"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"
                               :sourceDex "spot"
                               :destinationDex "spot"
                               :token "USDC"
                               :amount "10"
                               :fromSubAccount ""}}
            :submit-send-asset! (fn [store* address action]
                                  (swap! submit-calls conj [store* address action])
                                  (js/Promise.resolve {:status "ok"}))
            :show-toast! (effects-support/capture-toast! toasts)
            :dispatch! (effects-support/capture-dispatch! dispatches)
            :default-funding-modal-state (fn [] default-modal)}))
          (.then (fn [resp]
                   (is (= {:status "ok"} resp))
                   (is (= [["0xabc" {:type "sendAsset"
                                     :destination "0x1234567890abcdef1234567890abcdef12345678"
                                     :sourceDex "spot"
                                     :destinationDex "spot"
                                     :token "USDC"
                                     :amount "10"
                                     :fromSubAccount ""}]]
                          (mapv (fn [[_store address action]]
                                  [address action])
                                @submit-calls)))
                   (is (= default-modal (get-in @store [:funding-ui :modal])))
                   (is (= [[:success "Send submitted."]]
                          @toasts))
                   (is (= [[[[:actions/load-user-data "0xabc"]]]]
                          (mapv (fn [[_store event]] [event]) @dispatches)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected send success-path error: " err))
                    (done)))))))

(deftest api-submit-funding-transfer-no-wallet-sets-error-test
  (let [store (atom {:wallet {}
                     :funding-ui {:modal (effects-support/seed-modal :transfer)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-transfer!
          (submit-deps
           {:store store
           :request {:action {:type "usdClassTransfer"
                              :amount "10"
                              :toPerp true}}
           :submit-usd-class-transfer! (fn [_store _address _action]
                                         (swap! submit-calls inc)
                                         (js/Promise.resolve {:status "ok"}))
           :show-toast! (effects-support/capture-toast! toasts)}))))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before transferring funds."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before transferring funds."]]
           @toasts))))

(deftest api-submit-funding-transfer-blocks-mutations-while-spectate-mode-active-test
  (let [store (atom {:wallet {:address "0xabc"}
                     :account-context {:spectate-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}}
                     :funding-ui {:modal (effects-support/seed-modal :transfer)}})
        toasts (atom [])]
    (effects/api-submit-funding-transfer!
     (submit-deps
      {:store store
      :request {:action {:type "usdClassTransfer"
                         :amount "10"
                         :toPerp true}}
      :show-toast! (effects-support/capture-toast! toasts)}))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= account-context/spectate-mode-read-only-message
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error account-context/spectate-mode-read-only-message]]
           @toasts))))

(deftest api-submit-funding-transfer-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :transfer)}})
          submit-calls (atom [])
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-transfer!
           (submit-deps
            {:store store
            :request {:action {:type "usdClassTransfer"
                               :amount "10"
                               :toPerp true}}
            :submit-usd-class-transfer! (fn [store* address action]
                                          (swap! submit-calls conj [store* address action])
                                          (js/Promise.resolve {:status "ok"}))
            :show-toast! (effects-support/capture-toast! toasts)
            :dispatch! (effects-support/capture-dispatch! dispatches)
            :default-funding-modal-state (fn [] default-modal)}))
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
                       :funding-ui {:modal (effects-support/seed-modal :transfer)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-transfer!
           (submit-deps
            {:store store
            :request {:action {:type "usdClassTransfer"
                               :amount "10"
                               :toPerp true}}
            :submit-usd-class-transfer! (fn [_store _address _action]
                                          (js/Promise.resolve {:status "err"
                                                               :error "insufficient margin"}))
            :show-toast! (effects-support/capture-toast! toasts)}))
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
                     :funding-ui {:modal (effects-support/seed-modal :withdraw)}})
        submit-calls (atom 0)
        toasts (atom [])]
    (is (nil?
         (effects/api-submit-funding-withdraw!
          (submit-deps
           {:store store
           :request {:action {:type "withdraw3"
                              :amount "6"
                              :destination "0x1234567890abcdef1234567890abcdef12345678"}}
           :submit-withdraw3! (fn [_store _address _action]
                                (swap! submit-calls inc)
                                (js/Promise.resolve {:status "ok"}))
           :show-toast! (effects-support/capture-toast! toasts)}))))
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= "Connect your wallet before withdrawing."
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error "Connect your wallet before withdrawing."]]
           @toasts))))

(deftest api-submit-funding-withdraw-blocks-mutations-while-spectate-mode-active-test
  (let [store (atom {:wallet {:address "0xabc"}
                     :account-context {:spectate-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}}
                     :funding-ui {:modal (effects-support/seed-modal :withdraw)}})
        toasts (atom [])]
    (effects/api-submit-funding-withdraw!
     (submit-deps
      {:store store
      :request {:action {:type "withdraw3"
                         :amount "6.5"
                         :destination "0x1234567890abcdef1234567890abcdef12345678"}}
      :show-toast! (effects-support/capture-toast! toasts)}))
    (is (= false (get-in @store [:funding-ui :modal :submitting?])))
    (is (= account-context/spectate-mode-read-only-message
           (get-in @store [:funding-ui :modal :error])))
    (is (= [[:error account-context/spectate-mode-read-only-message]]
           @toasts))))

(deftest api-submit-funding-withdraw-success-closes-modal-and-refreshes-test
  (async done
    (let [default-modal (effects-support/default-funding-modal-state)
          store (atom {:wallet {:address "0xabc"}
                       :funding-ui {:modal (effects-support/seed-modal :withdraw)}})
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-funding-withdraw!
           (submit-deps
            {:store store
            :request {:action {:type "withdraw3"
                               :amount "6.5"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"}}
            :submit-withdraw3! (fn [_store _address _action]
                                 (js/Promise.resolve {:status "ok"}))
            :show-toast! (effects-support/capture-toast! toasts)
            :dispatch! (effects-support/capture-dispatch! dispatches)
            :default-funding-modal-state (fn [] default-modal)}))
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
                       :funding-ui {:modal (effects-support/seed-modal :withdraw)}})
          toasts (atom [])]
      (-> (effects/api-submit-funding-withdraw!
           (submit-deps
            {:store store
            :request {:action {:type "withdraw3"
                               :amount "6.5"
                               :destination "0x1234567890abcdef1234567890abcdef12345678"}}
            :submit-withdraw3! (fn [_store _address _action]
                                 (js/Promise.reject (js/Error. "network timeout")))
            :show-toast! (effects-support/capture-toast! toasts)}))
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
