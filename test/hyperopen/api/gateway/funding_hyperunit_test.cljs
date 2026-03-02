(ns hyperopen.api.gateway.funding-hyperunit-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.endpoints.funding-hyperunit :as funding-hyperunit-endpoints]
            [hyperopen.api.gateway.funding-hyperunit :as funding-hyperunit-gateway]))

(deftest funding-hyperunit-gateway-wrapper-delegation-test
  (let [calls (atom [])]
    (with-redefs [funding-hyperunit-endpoints/request-generate-address! (fn [& args]
                                                                          (swap! calls conj [:generate args])
                                                                          {:ok :generate})
                  funding-hyperunit-endpoints/request-operations! (fn [& args]
                                                                    (swap! calls conj [:operations args])
                                                                    {:ok :operations})
                  funding-hyperunit-endpoints/request-estimate-fees! (fn [& args]
                                                                       (swap! calls conj [:estimate-fees args])
                                                                       {:ok :estimate-fees})
                  funding-hyperunit-endpoints/request-withdrawal-queue! (fn [& args]
                                                                          (swap! calls conj [:withdrawal-queue args])
                                                                          {:ok :withdrawal-queue})]
      (is (= {:ok :generate}
             (funding-hyperunit-gateway/request-hyperunit-generate-address!
              {:fetch-fn :custom-fetch
               :hyperunit-base-url "https://unit.custom"}
              {:source-chain "bitcoin"
               :destination-chain "hyperliquid"
               :asset "btc"
               :destination-address "0xabc"})))
      (is (= {:ok :operations}
             (funding-hyperunit-gateway/request-hyperunit-operations!
              {}
              {:address "0xabc"})))
      (is (= {:ok :estimate-fees}
             (funding-hyperunit-gateway/request-hyperunit-estimate-fees!
              {}
              {:base-url "https://unit.testnet"})))
      (is (= {:ok :withdrawal-queue}
             (funding-hyperunit-gateway/request-hyperunit-withdrawal-queue!
              {}
              {:hyperunit-base-url "https://unit.testnet"})))
      (let [[_ [_fetch-fn generate-base-url _generate-opts]] (first @calls)
            [_ [_fetch-fn* operations-base-url _operations-opts]] (second @calls)
            [_ [_fetch-fn** estimate-base-url estimate-opts]] (nth @calls 2)
            [_ [_fetch-fn*** queue-base-url queue-opts]] (nth @calls 3)]
        (is (= "https://unit.custom" generate-base-url))
        (is (= funding-hyperunit-endpoints/default-mainnet-base-url
               operations-base-url))
        (is (= "https://unit.testnet" estimate-base-url))
        (is (= "https://unit.testnet" queue-base-url))
        (is (= {} estimate-opts))
        (is (= {} queue-opts))))))
