(ns hyperopen.trading-crypto-modules-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [shadow.loader :as loader]
            [hyperopen.trading-crypto.module]
            [hyperopen.trading-crypto-modules :as trading-crypto-modules]))

(defn- loader-thenable
  [value]
  #js {:then (fn [resolve _reject]
               (resolve value))})

(deftest load-trading-crypto-module-handles-loader-thenables-without-finally-test
  (async done
    (trading-crypto-modules/reset-trading-crypto-module-state!)
    (with-redefs [loader/loaded? (constantly false)
                  loader/load (fn [_]
                                (loader-thenable nil))]
      (-> (trading-crypto-modules/load-trading-crypto-module!)
          (.then (fn [resolved]
                   (trading-crypto-modules/reset-trading-crypto-module-state!)
                   (is (map? resolved))
                   (is (fn? (:create-agent-credentials! resolved)))
                   (done)))
          (.catch (fn [err]
                    (trading-crypto-modules/reset-trading-crypto-module-state!)
                    (is false (str "Expected thenable loader result to resolve, got: " err))
                    (done)))))))
