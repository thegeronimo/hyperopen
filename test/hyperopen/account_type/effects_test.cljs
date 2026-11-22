(ns hyperopen.account-type.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account-type.effects :as effects]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(defn- base-store
  []
  {:account-type-ui {:modal-open? true
                     :selected :portfolio-margin
                     :submitting? true
                     :error-message nil}})

(deftest api-set-user-abstraction-closes-modal-and-refreshes-on-ok-test
  (async done
    (let [store (atom (base-store))
          submitted (atom nil)
          refreshed (atom nil)
          promise (effects/api-set-user-abstraction!
                   {:store store
                    :submit-user-set-abstraction!
                    (fn [_store address action]
                      (reset! submitted [address action])
                      (js/Promise.resolve {:status "ok"}))
                    :refresh-user-abstraction!
                    (fn [_store owner]
                      (reset! refreshed owner)
                      nil)}
                   {:owner owner-address
                    :abstraction "portfolioMargin"})]
      (-> promise
          (.then (fn [resp]
                   (is (= {:status "ok"} resp))
                   (is (= [owner-address
                           {:type "userSetAbstraction"
                            :user owner-address
                            :abstraction "portfolioMargin"}]
                          @submitted))
                   (is (= owner-address @refreshed))
                   (is (false? (get-in @store [:account-type-ui :modal-open?])))
                   (is (false? (get-in @store [:account-type-ui :submitting?])))
                   (is (nil? (get-in @store [:account-type-ui :error-message])))
                   (done)))
          (.catch (fn [err]
                    (is (nil? err) (str "unexpected rejection: " err))
                    (done)))))))

(deftest api-set-user-abstraction-surfaces-exchange-error-test
  (async done
    (let [store (atom (base-store))
          promise (effects/api-set-user-abstraction!
                   {:store store
                    :submit-user-set-abstraction!
                    (fn [_store _address _action]
                      (js/Promise.resolve {:status "err"
                                           :response "User cannot set abstraction"}))
                    :refresh-user-abstraction!
                    (fn [& _] (throw (js/Error. "refresh must not run on error")))}
                   {:owner owner-address
                    :abstraction "unifiedAccount"})]
      (-> promise
          (.then (fn [_]
                   (is (true? (get-in @store [:account-type-ui :modal-open?])))
                   (is (false? (get-in @store [:account-type-ui :submitting?])))
                   (is (= "User cannot set abstraction"
                          (get-in @store [:account-type-ui :error-message])))
                   (done)))
          (.catch (fn [err]
                    (is (nil? err) (str "unexpected rejection: " err))
                    (done)))))))

(deftest api-set-user-abstraction-surfaces-signature-rejection-test
  (async done
    (let [store (atom (base-store))
          promise (effects/api-set-user-abstraction!
                   {:store store
                    :submit-user-set-abstraction!
                    (fn [_store _address _action]
                      (js/Promise.reject (js/Error. "User rejected the request.")))
                    :refresh-user-abstraction!
                    (fn [& _] (throw (js/Error. "refresh must not run on rejection")))}
                   {:owner owner-address
                    :abstraction "unifiedAccount"})]
      (-> promise
          (.then (fn [_]
                   (is (true? (get-in @store [:account-type-ui :modal-open?])))
                   (is (false? (get-in @store [:account-type-ui :submitting?])))
                   (is (= "User rejected the request."
                          (get-in @store [:account-type-ui :error-message])))
                   (done)))
          (.catch (fn [err]
                    (is (nil? err) (str "unexpected rejection: " err))
                    (done)))))))

(deftest api-set-user-abstraction-requires-a-wallet-test
  (async done
    (let [store (atom (base-store))
          promise (effects/api-set-user-abstraction!
                   {:store store
                    :submit-user-set-abstraction!
                    (fn [& _] (throw (js/Error. "must not be called without a wallet")))}
                   {:owner nil
                    :abstraction "unifiedAccount"})]
      (-> promise
          (.then (fn [_]
                   (is (= "Connect your wallet before changing account type."
                          (get-in @store [:account-type-ui :error-message])))
                   (done)))
          (.catch (fn [err]
                    (is (nil? err) (str "unexpected rejection: " err))
                    (done)))))))
