(ns hyperopen.schema.contracts.effect-args-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-only-test
  (is (= [:interval :1m]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m]
          {:phase :test}))))

(deftest assert-effect-args-accepts-sync-active-candle-subscription-interval-test
  (is (= [:interval :1m]
         (contracts/assert-effect-args!
          :effects/sync-active-candle-subscription
          [:interval :1m]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-and-bars-test
  (is (= [:interval :1m :bars 330]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m :bars 330]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-coin-interval-and-bars-test
  (is (= [:coin "SPY" :interval :1h :bars 800]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:coin "SPY" :interval :1h :bars 800]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-detail-route-vault-address-test
  (is (= [:coin "SPY"
          :interval :1h
          :bars 800
          :detail-route-vault-address "0x1234567890abcdef1234567890abcdef12345678"]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:coin "SPY"
           :interval :1h
           :bars 800
           :detail-route-vault-address "0x1234567890abcdef1234567890abcdef12345678"]
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

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-blank-coin-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:coin "   " :interval :1h]
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

(deftest assert-effect-args-accepts-confirm-api-submit-order-variant-test
  (is (= [{:variant :open-order
           :message "Submit?"
           :request {:action {:type "order"}}
           :path-values [[[:order-form-runtime :error] nil]]}]
         (contracts/assert-effect-args!
          :effects/confirm-api-submit-order
          [{:variant :open-order
            :message "Submit?"
            :request {:action {:type "order"}}
            :path-values [[[:order-form-runtime :error] nil]]}]
          {:phase :test}))))

(deftest assert-effect-args-rejects-export-funding-history-csv-when-not-vector-of-maps-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/export-funding-history-csv
        ["not-a-vector"]
        {:phase :test}))))

(deftest assert-effect-args-validates-funding-predictability-sync-coin-test
  (is (= ["BTC"]
         (contracts/assert-effect-args!
          :effects/sync-active-asset-funding-predictability
          ["BTC"]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/sync-active-asset-funding-predictability
        [""]
        {:phase :test}))))
