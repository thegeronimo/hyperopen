(ns hyperopen.api.trading.internal-seams-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts :as contracts]))

(deftest safe-private-key->agent-address-catches-errors-test
  (with-redefs [hyperopen.wallet.agent-session/private-key->agent-address
                (fn [_]
                  (throw (js/Error. "boom")))]
    (is (nil? (@#'hyperopen.api.trading/safe-private-key->agent-address
               "0xbroken")))))

(deftest next-nonce-falls-back-to-now-and-remains-monotonic-test
  (with-redefs [platform/now-ms (fn [] 1700000015000)]
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce nil)))
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce "bad")))
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce 1700000014999)))
    (is (= 1700000016001
           (@#'hyperopen.api.trading/next-nonce 1700000016000)))))

(deftest post-signed-action-private-helper-includes-optional-fields-test
  (async done
    (let [fetch-call (atom nil)
          assert-call (atom nil)
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (reset! fetch-call [url opts])
                            (js/Promise.resolve #js {:ok true})))
          action {:type "order"
                  :orders []
                  :grouping "na"}
          signature {:r "0x1"
                     :s "0x2"
                     :v 27}]
      (with-redefs [contracts/validation-enabled? (constantly true)
                    contracts/assert-signed-exchange-payload!
                    (fn [payload context]
                      (reset! assert-call [payload context]))]
        (let [request (@#'hyperopen.api.trading/post-signed-action!
                       action
                       1700000017000
                       signature
                       :vault-address "0xABCDEF"
                       :expires-after 1700000017999)]
          (.finally
           (.catch
            (.then request
                   (fn [_]
                     (let [[url fetch-opts] @fetch-call
                           payload (support/fetch-body->map fetch-opts)
                           [asserted-payload asserted-context] @assert-call]
                       (is (= trading/exchange-url url))
                       (is (= "0xABCDEF" (:vaultAddress payload)))
                       (is (= 1700000017999 (:expiresAfter payload)))
                       (is (= signature (:signature payload)))
                       (is (= payload asserted-payload))
                       (is (= {:boundary :api-trading/post-signed-action
                               :action-type "order"}
                              asserted-context))
                       (done))))
            (fn [err]
              (is false (str "Unexpected error: " err))
              (done)))
           (fn []
             (restore-fetch!))))))))

(deftest post-signed-action-private-helper-preserves-safe-integer-nonce-test
  (async done
    (let [fetch-call (atom nil)
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (reset! fetch-call [url opts])
                            (js/Promise.resolve #js {:ok true})))
          safe-nonce 9007199254740991
          action {:type "order"
                  :orders []
                  :grouping "na"}
          signature {:r "0x1"
                     :s "0x2"
                     :v 27}]
      (with-redefs [contracts/validation-enabled? (constantly false)]
        (let [request (@#'hyperopen.api.trading/post-signed-action!
                       action
                       safe-nonce
                       signature)]
          (.finally
           (.catch
            (.then request
                   (fn [_]
                     (let [[url fetch-opts] @fetch-call
                           payload (support/fetch-body->map fetch-opts)]
                       (is (= trading/exchange-url url))
                       (is (= safe-nonce (:nonce payload)))
                       (done))))
            (fn [err]
              (is false (str "Unexpected error: " err))
              (done)))
           (fn []
             (restore-fetch!))))))))
