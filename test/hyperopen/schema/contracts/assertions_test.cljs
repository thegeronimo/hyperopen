(ns hyperopen.schema.contracts.assertions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.test-support.lifecycle :as lifecycle-fixtures]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.system :as system]))

(deftest assert-signed-exchange-payload-requires-action-map-test
  (is (thrown-with-msg?
       js/Error
       #"exchange payload"
       (contracts/assert-signed-exchange-payload!
        {:action nil
         :nonce 42
         :signature {:r "0x1"
                     :s "0x2"
                     :v 27}}
        {:boundary :test}))))

(deftest assert-provider-message-requires-channel-test
  (is (= {:channel "websocket"}
         (contracts/assert-provider-message!
          {:channel "websocket"}
          {:boundary :test})))
  (is (thrown-with-msg?
       js/Error
       #"provider payload"
       (contracts/assert-provider-message!
        {:channel ""}
        {:boundary :test}))))

(deftest assert-exchange-response-accepts-map-payload-test
  (let [payload {:status "ok"}]
    (is (= payload
           (contracts/assert-exchange-response! payload {:boundary :test})))))

(deftest assert-effect-call-validates-single-effect-vector-test
  (let [effect [:effects/connect-wallet]]
    (is (= effect
           (contracts/assert-effect-call! effect {:phase :test}))))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-call! [] {:phase :test}))))

(deftest assert-emitted-effects-validates-sequences-of-effects-test
  (let [effects [[:effects/connect-wallet]
                 [:effects/disconnect-wallet]]]
    (is (= effects
           (contracts/assert-emitted-effects! effects {:phase :test}))))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-emitted-effects! :not-a-seq {:phase :test}))))

(deftest assert-app-state-accepts-default-store-state-test
  (let [state (system/default-store-state)]
    (is (= state
           (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-rejects-stale-account-surfaces-without-an-effective-account-test
  (let [state (lifecycle-fixtures/seed-stale-account-surfaces
               (lifecycle-fixtures/state-for-kind :disconnected))]
    (is (thrown-with-msg?
         js/Error
         #"account lifecycle invariant failed"
         (contracts/assert-app-state! state {:phase :test})))))

(deftest assert-app-state-allows-account-surfaces-while-an-effective-account-is-present-test
  (let [state (lifecycle-fixtures/seed-stale-account-surfaces
               (lifecycle-fixtures/state-for-kind :owner))]
    (is (= state
           (contracts/assert-app-state! state {:phase :test})))))
