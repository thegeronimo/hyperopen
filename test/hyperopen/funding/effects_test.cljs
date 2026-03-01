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
