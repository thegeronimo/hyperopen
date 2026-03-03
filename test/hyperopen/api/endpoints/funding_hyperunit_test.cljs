(ns hyperopen.api.endpoints.funding-hyperunit-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.funding-hyperunit :as funding-hyperunit]
            [hyperopen.test-support.async :as async-support]))

(defn- ok-response
  [payload]
  #js {:ok true
       :status 200
       :text (fn []
               (js/Promise.resolve (js/JSON.stringify (clj->js payload))))})

(defn- error-response
  [status payload]
  #js {:ok false
       :status status
       :text (fn []
               (if (string? payload)
                 (js/Promise.resolve payload)
                 (js/Promise.resolve (js/JSON.stringify (clj->js payload)))))} )

(deftest request-generate-address-builds-url-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url init])
                     (js/Promise.resolve
                      (ok-response
                       {:address "0xProtocolAddress"
                        :status "OK"
                        :isSufficientlySigned "true"
                        :signatures {"field-node" "a"
                                     "hl-node" "b"}})))]
      (-> (funding-hyperunit/request-generate-address!
           fetch-fn
           "https://api.hyperunit-testnet.xyz/"
           {:source-chain "  Bitcoin  "
            :destination-chain "hyperliquid"
            :asset " BTC "
            :destination-address "0xAbC"})
          (.then (fn [result]
                   (let [[url init] (first @calls)]
                     (is (= "https://api.hyperunit-testnet.xyz/gen/bitcoin/hyperliquid/btc/0xAbC"
                            url))
                     (is (= {:method "GET"
                             :headers {:Content-Type "application/json"}}
                            (js->clj init :keywordize-keys true))))
                   (is (= {:address "0xProtocolAddress"
                           :status "OK"
                           :signatures {"field-node" "a"
                                        "hl-node" "b"}
                           :signature-operation-id nil
                           :signature-endpoint-error nil
                           :sufficiently-signed? true
                           :error nil}
                          result))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-generate-address-rejects-on-http-error-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve
                      (error-response 400 {:error "invalid request"})))]
      (-> (funding-hyperunit/request-generate-address!
           fetch-fn
           funding-hyperunit/default-mainnet-base-url
           {:source-chain "bitcoin"
            :destination-chain "hyperliquid"
            :asset "btc"
            :destination-address "0xabc"})
          (.then (fn [_]
                   (is false "Expected request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= 400 (aget err "status")))
                    (is (= "invalid request" (.-message err)))
                    (done)))))))

(deftest request-operations-short-circuits-when-address-missing-test
  (async done
    (let [calls (atom 0)
          fetch-fn (fn [_url _init]
                     (swap! calls inc)
                     (js/Promise.resolve (ok-response {})))]
      (-> (funding-hyperunit/request-operations!
           fetch-fn
           funding-hyperunit/default-mainnet-base-url
           {:address "   "})
          (.then (fn [result]
                   (is (= {:addresses []
                           :operations []
                           :error nil}
                          result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-operations-normalizes-payload-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve
                      (ok-response
                       {:addresses [{:sourceCoinType "Bitcoin"
                                     :sourceChain "bitcoin"
                                     :destinationChain "hyperliquid"
                                     :address "tb1x"
                                     :signatures {"field-node" "sig-a"}}]
                       :operations [{:operationId "op-1"
                                      :opCreatedAt "2025-03-12T23:40:49.279929Z"
                                      :protocolAddress "tb1x"
                                      :sourceAddress "tb1src"
                                      :destinationAddress "0xDest"
                                      :sourceChain "bitcoin"
                                      :destinationChain "hyperliquid"
                                      :sourceAmount "10000000"
                                      :destinationFeeAmount "0"
                                      :sweepFeeAmount "0"
                                      :stateStartedAt "2025-03-12T23:40:49.465291Z"
                                      :stateUpdatedAt "2025-03-12T23:40:49.465291Z"
                                      :stateNextAttemptAt "2025-03-12T23:42:50.804735Z"
                                      :sourceTxHash "0xsrc"
                                      :sourceTxConfirmations "2"
                                      :destinationTxHash ""
                                      :destinationTxConfirmations 1
                                      :positionInWithdrawQueue "3"
                                      :asset "BTC"
                                      :state "waitForSrcTxFinalization"}]})))]
      (-> (funding-hyperunit/request-operations!
           fetch-fn
           funding-hyperunit/default-mainnet-base-url
           {:address "0xabc"})
          (.then (fn [result]
                   (is (= 1 (count (:addresses result))))
                   (is (= {:source-coin-type "bitcoin"
                           :source-chain "bitcoin"
                           :destination-chain "hyperliquid"
                           :address "tb1x"
                           :signatures {"field-node" "sig-a"}}
                          (first (:addresses result))))
                   (let [op (first (:operations result))]
                     (is (= "op-1" (:operation-id op)))
                     (is (= :wait-for-src-tx-finalization (:state-key op)))
                     (is (= 2 (:source-tx-confirmations op)))
                     (is (= 1 (:destination-tx-confirmations op)))
                     (is (= 3 (:position-in-withdraw-queue op)))
                     (is (= "btc" (:asset op))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-estimate-fees-normalizes-chain-metrics-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve
                      (ok-response
                       {:bitcoin {"bitcoin-depositEta" "21m"
                                  "bitcoin-depositFee" 2065
                                  "bitcoin-withdrawalEta" "14m"
                                  "bitcoin-withdrawalFee" 715}})))]
      (-> (funding-hyperunit/request-estimate-fees!
           fetch-fn
           funding-hyperunit/default-mainnet-base-url
           {})
          (.then (fn [result]
                   (is (= 1 (count (:chains result))))
                   (is (= {:chain "bitcoin"
                           :deposit-eta "21m"
                           :withdrawal-eta "14m"
                           :deposit-fee 2065
                           :withdrawal-fee 715
                           :metrics {"bitcoin-depositEta" "21m"
                                     "bitcoin-depositFee" 2065
                                     "bitcoin-withdrawalEta" "14m"
                                     "bitcoin-withdrawalFee" 715}}
                          (get-in result [:by-chain "bitcoin"])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-withdrawal-queue-normalizes-response-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve
                      (ok-response
                       {:bitcoin {:lastWithdrawQueueOperationTxID "tx-a"
                                  :withdrawalQueueLength 2}
                        :ethereum {:lastWithdrawQueueOperationTxID "tx-b"
                                   :withdrawalQueueLength "0"}})))]
      (-> (funding-hyperunit/request-withdrawal-queue!
           fetch-fn
           funding-hyperunit/default-mainnet-base-url
           {})
          (.then (fn [result]
                   (is (= 2 (count (:chains result))))
                   (is (= {:chain "bitcoin"
                           :last-withdraw-queue-operation-tx-id "tx-a"
                           :withdrawal-queue-length 2}
                          (get-in result [:by-chain "bitcoin"])))
                   (is (= {:chain "ethereum"
                           :last-withdraw-queue-operation-tx-id "tx-b"
                           :withdrawal-queue-length 0}
                          (get-in result [:by-chain "ethereum"])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
