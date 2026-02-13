(ns hyperopen.schema.contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.system :as system]))

(deftest assert-app-state-rejects-active-market-without-symbol-test
  (let [state (assoc (system/default-store-state)
                     :active-market {:coin "BTC"})]
    (is (thrown-with-msg?
         js/Error
         #"app state"
         (contracts/assert-app-state! state {:phase :test})))))

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

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-only-test
  (is (= [:interval :1m]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-and-bars-test
  (is (= [:interval :1m :bars 330]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m :bars 330]
          {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-odd-kv-arity-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:interval]
        {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-unknown-key-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:foo 1]
        {:phase :test}))))

(deftest assert-effect-args-rejects-funding-history-request-id-when-not-non-negative-integer-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/api-fetch-user-funding-history
        ["abc"]
        {:phase :test}))))

(deftest assert-effect-args-rejects-order-history-request-id-when-negative-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/api-fetch-historical-orders
        [-1]
        {:phase :test}))))

(deftest assert-effect-args-rejects-export-funding-history-csv-when-not-vector-of-maps-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/export-funding-history-csv
        ["not-a-vector"]
        {:phase :test}))))
