(ns hyperopen.funding.application.hyperunit-query-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.hyperunit-query :as query]
            [hyperopen.test-support.async :as async-support]))

(defn- canonical-chain-token
  [value]
  (some-> value str str/trim str/lower-case))

(defn- canonical-token
  [value]
  (some-> value str str/trim str/lower-case))

(defn- same-chain-token?
  [a b]
  (= (canonical-chain-token a)
     (canonical-chain-token b)))

(defn- non-blank-text
  [value]
  (when (and (string? value)
             (not (str/blank? value)))
    (str/trim value)))

(defn- protocol-address-matches-source-chain?
  [source-token address]
  (cond
    (= "bitcoin" source-token) (str/starts-with? (str/lower-case (or address "")) "bc1")
    (= "ethereum" source-token) (str/starts-with? (str/lower-case (or address "")) "0x")
    (= "solana" source-token) (str/starts-with? (or address "") "So")
    (= "monad" source-token) true
    (= "plasma" source-token) true
    :else false))

(defn- selection-deps
  []
  {:canonical-chain-token canonical-chain-token
   :canonical-token canonical-token
   :same-chain-token? same-chain-token?
   :op-sort-ms-fn (fn [op] (or (:submitted-at-ms op)
                               (:created-at-ms op)
                               0))
   :non-blank-text non-blank-text
   :protocol-address-matches-source-chain? protocol-address-matches-source-chain?
   :known-source-chain-tokens #{"bitcoin" "ethereum" "solana" "monad" "plasma"}})

(deftest select-existing-hyperunit-deposit-address-normalizes-chain-aliases-and-validates-address-shape-test
  (let [select-existing-address query/select-existing-hyperunit-deposit-address]
    (is (= {:address "bc1qalias"
            :signatures {"node-a" "sig-a"}}
           (select-existing-address
            (selection-deps)
            {:addresses [{:source-coin-type "btc"
                          :destination-chain "hyperliquid"
                          :address "bc1qalias"
                          :signatures {"node-a" "sig-a"}}]
             :operations []}
            "bitcoin"
            "btc"
            "0xabc")))
    (is (= {:address "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s"
            :signatures {"node-b" "sig-b"}}
           (select-existing-address
            (selection-deps)
            {:addresses [{:source-coin-type "unknown-source"
                          :destination-chain "hyperliquid"
                          :address "bc1qpz0qv7jw4x3kg8qdpv9k7n4kl2f5dx6n9d5p3s"
                          :signatures {"node-b" "sig-b"}}]
             :operations []}
            "bitcoin"
            "btc"
            "0xabc")))
    (is (nil? (select-existing-address
               (selection-deps)
               {:addresses [{:source-coin-type "bitcoin"
                             :destination-chain "hyperliquid"
                             :address "bc1qbtc"
                             :signatures {"node-c" "sig-c"}}]
                :operations []}
               "ethereum"
               "eth"
               "0xabc")))
    (is (= {:address "0xethchain"
            :signatures {"node-d" "sig-d"}}
           (select-existing-address
            (selection-deps)
            {:addresses [{:source-chain "ethereum"
                          :destination-chain "hyperliquid"
                          :address "0xethchain"
                          :signatures {"node-d" "sig-d"}}]
             :operations []}
            "ethereum"
            "eth"
            "0xabc")))
    (is (= {:address "0x1111111111111111111111111111111111111111"
            :signatures {"node-e" "sig-e"}}
           (select-existing-address
            (selection-deps)
            {:addresses [{:source-coin-type "unknown-source"
                          :destination-chain "hyperliquid"
                          :address "0x1111111111111111111111111111111111111111"
                          :signatures {"node-e" "sig-e"}}]
             :operations []}
            "ethereum"
            "eth"
            "0xabc")))))

(deftest request-existing-hyperunit-deposit-address-returns-matching-entry-test
  (async done
    (let [calls (atom [])]
      (-> (query/request-existing-hyperunit-deposit-address!
           {:request-hyperunit-operations!
            (fn [opts]
              (swap! calls conj opts)
              (js/Promise.resolve
               {:addresses [{:source-coin-type "bitcoin"
                             :destination-chain "hyperliquid"
                             :address "bc1qexisting"
                             :signatures {"hl-node" "sig-a"}}]
                :operations []}))
            :select-existing-hyperunit-deposit-address
            (fn [operations-response source-chain asset destination-address]
              (query/select-existing-hyperunit-deposit-address
               (selection-deps)
               operations-response
               source-chain
               asset
               destination-address))}
           "https://api.hyperunit.xyz"
           ["https://api.hyperunit.xyz"]
           "0xabc"
           "bitcoin"
           "btc")
          (.then (fn [resp]
                   (is (= 1 (count @calls)))
                   (is (= "bc1qexisting" (:address resp)))
                   (is (= {"hl-node" "sig-a"} (:signatures resp)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest prefetch-selected-hyperunit-deposit-address-populates-existing-address-test
  (async done
    (let [store (atom {:wallet {:address "0x1111111111111111111111111111111111111111"}
                       :funding-ui {:modal {:open? true
                                            :mode :deposit
                                            :deposit-step :amount-entry
                                            :deposit-selected-asset-key :btc
                                            :deposit-generated-address nil
                                            :deposit-generated-signatures nil
                                            :deposit-generated-asset-key nil}}})
          requested (atom [])]
      (-> (query/prefetch-selected-hyperunit-deposit-address!
           {:funding-modal-view-model-fn
            (fn [_state]
              {:deposit-selected-asset {:key :btc
                                        :flow-kind :hyperunit-address
                                        :hyperunit-source-chain "bitcoin"}})
            :normalize-asset-key (fn [value]
                                   (when value
                                     (keyword (str/lower-case (name value)))))
            :non-blank-text non-blank-text
            :canonical-chain-token canonical-chain-token
            :normalize-address identity
            :resolve-hyperunit-base-urls (fn [_store] ["https://api.hyperunit.xyz"])
            :request-existing-hyperunit-deposit-address!
            (fn [_base-url _base-urls destination-address source-chain asset]
              (swap! requested conj [destination-address source-chain asset])
              (js/Promise.resolve {:address "bc1qprefetched"
                                   :signatures {"hl-node" "sig-prefetched"}}))}
           store)
          (.then (fn [_]
                   (is (= [["0x1111111111111111111111111111111111111111"
                            "bitcoin"
                            "btc"]]
                          @requested))
                   (is (= "bc1qprefetched"
                          (get-in @store [:funding-ui :modal :deposit-generated-address])))
                   (is (= {"hl-node" "sig-prefetched"}
                          (get-in @store [:funding-ui :modal :deposit-generated-signatures])))
                   (is (= :btc
                          (get-in @store [:funding-ui :modal :deposit-generated-asset-key])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest api-fetch-hyperunit-fee-estimate-updates-modal-on-success-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :withdraw
                                            :hyperunit-fee-estimate {:status :ready
                                                                     :by-chain {}
                                                                     :error nil}}}})
          clock (atom [1700000000000 1700000001000])]
      (-> (query/api-fetch-hyperunit-fee-estimate!
           {:modal-active-for-fee-estimate? (fn [_store] true)
            :normalize-hyperunit-fee-estimate (fn [value]
                                                (merge {:status :ready
                                                        :by-chain {}
                                                        :error nil}
                                                       value))
            :resolve-hyperunit-base-urls (fn [_store] ["https://api.hyperunit.xyz"])
            :prefetch-selected-hyperunit-deposit-address! (fn [_store] nil)
            :non-blank-text non-blank-text
            :fallback-runtime-error-message (fn [err]
                                              (or (some-> err .-message) "unknown"))}
           {:store store
            :request-hyperunit-estimate-fees! (fn [_opts]
                                                (js/Promise.resolve
                                                 {:by-chain {"bitcoin" {:chain "bitcoin"
                                                                        :withdrawal-eta "~20 mins"
                                                                        :withdrawal-fee "0.00001"}}}))
            :now-ms-fn (fn []
                         (let [value (first @clock)]
                           (swap! clock rest)
                           value))})
          (.then (fn [_]
                   (let [estimate (get-in @store [:funding-ui :modal :hyperunit-fee-estimate])]
                     (is (= :ready (:status estimate)))
                     (is (= 1700000000000 (:requested-at-ms estimate)))
                     (is (= 1700000001000 (:updated-at-ms estimate)))
                     (is (= "~20 mins" (get-in estimate [:by-chain "bitcoin" :withdrawal-eta])))
                     (is (= "0.00001" (get-in estimate [:by-chain "bitcoin" :withdrawal-fee])))
                     (is (nil? (:error estimate)))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest api-fetch-hyperunit-fee-estimate-sets-error-state-on-failure-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :deposit
                                            :hyperunit-fee-estimate {:status :ready
                                                                     :by-chain {}
                                                                     :error nil}}}})
          now-ms (atom [1700000000000 1700000001000])]
      (-> (query/api-fetch-hyperunit-fee-estimate!
           {:modal-active-for-fee-estimate? (fn [_store] true)
            :normalize-hyperunit-fee-estimate (fn [value]
                                                (merge {:status :ready
                                                        :by-chain {}
                                                        :error nil}
                                                       value))
            :resolve-hyperunit-base-urls (fn [_store] ["https://api.hyperunit.xyz"])
            :prefetch-selected-hyperunit-deposit-address! (fn [_store] nil)
            :non-blank-text non-blank-text
            :fallback-runtime-error-message (fn [err]
                                              (or (some-> err .-message) "unknown"))}
           {:store store
            :request-hyperunit-estimate-fees! (fn [_opts]
                                                (js/Promise.reject (js/Error. "gateway timeout")))
            :now-ms-fn (fn []
                         (let [value (first @now-ms)]
                           (swap! now-ms rest)
                           value))})
          (.then (fn [result]
                   (is (map? result))
                   (let [estimate (get-in @store [:funding-ui :modal :hyperunit-fee-estimate])]
                     (is (= :error (:status estimate)))
                     (is (= 1700000000000 (:requested-at-ms estimate)))
                     (is (= 1700000001000 (:updated-at-ms estimate)))
                     (is (= "gateway timeout" (:error estimate))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-hyperunit-withdrawal-queue-updates-modal-on-success-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :withdraw
                                            :withdraw-selected-asset-key :btc
                                            :hyperunit-withdrawal-queue {:status :ready
                                                                         :by-chain {}
                                                                         :error nil}}}})
          clock (atom [1700000000000 1700000001000])]
      (-> (query/fetch-hyperunit-withdrawal-queue!
           {:modal-active-for-withdraw-queue? (fn [_store _asset-key] true)
            :normalize-hyperunit-withdrawal-queue (fn [value]
                                                    (merge {:status :ready
                                                            :by-chain {}
                                                            :error nil}
                                                           value))
            :resolve-hyperunit-base-urls (fn [_store] ["https://api.hyperunit.xyz"])
            :non-blank-text non-blank-text
            :fallback-runtime-error-message (fn [err]
                                              (or (some-> err .-message) "unknown"))}
           {:store store
            :request-hyperunit-withdrawal-queue! (fn [_opts]
                                                   (js/Promise.resolve
                                                    {:by-chain {"bitcoin" {:chain "bitcoin"
                                                                           :withdrawal-queue-length 4
                                                                           :last-withdraw-queue-operation-tx-id "0xqueue-next"}}}))
            :now-ms-fn (fn []
                         (let [value (first @clock)]
                           (swap! clock rest)
                           value))})
          (.then (fn [_]
                   (let [queue-state (get-in @store [:funding-ui :modal :hyperunit-withdrawal-queue])]
                     (is (= :ready (:status queue-state)))
                     (is (= 1700000000000 (:requested-at-ms queue-state)))
                     (is (= 1700000001000 (:updated-at-ms queue-state)))
                     (is (= 4 (get-in queue-state [:by-chain "bitcoin" :withdrawal-queue-length])))
                     (is (= "0xqueue-next"
                            (get-in queue-state [:by-chain "bitcoin" :last-withdraw-queue-operation-tx-id])))
                     (is (nil? (:error queue-state)))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest fetch-hyperunit-withdrawal-queue-sets-error-state-on-failure-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"}
                       :funding-ui {:modal {:open? true
                                            :mode :withdraw
                                            :withdraw-selected-asset-key :btc
                                            :hyperunit-withdrawal-queue {:status :ready
                                                                         :by-chain {}
                                                                         :error nil}}}})
          now-ms (atom [1700000000000 1700000001000])]
      (-> (query/fetch-hyperunit-withdrawal-queue!
           {:modal-active-for-withdraw-queue? (fn [_store _asset-key] true)
            :normalize-hyperunit-withdrawal-queue (fn [value]
                                                    (merge {:status :ready
                                                            :by-chain {}
                                                            :error nil}
                                                           value))
            :resolve-hyperunit-base-urls (fn [_store] ["https://api.hyperunit.xyz"])
            :non-blank-text non-blank-text
            :fallback-runtime-error-message (fn [err]
                                              (or (some-> err .-message) "unknown"))}
           {:store store
            :request-hyperunit-withdrawal-queue! (fn [_opts]
                                                   (js/Promise.reject (js/Error. "queue offline")))
            :now-ms-fn (fn []
                         (let [value (first @now-ms)]
                           (swap! now-ms rest)
                           value))})
          (.then (fn [result]
                   (is (map? result))
                   (let [queue-state (get-in @store [:funding-ui :modal :hyperunit-withdrawal-queue])]
                     (is (= :error (:status queue-state)))
                     (is (= 1700000000000 (:requested-at-ms queue-state)))
                     (is (= 1700000001000 (:updated-at-ms queue-state)))
                     (is (= "queue offline" (:error queue-state))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
