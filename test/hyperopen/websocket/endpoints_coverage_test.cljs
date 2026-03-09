(ns hyperopen.websocket.endpoints-coverage-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.endpoints.funding-hyperunit :as funding-hyperunit-endpoints]
            [hyperopen.api.endpoints.market :as market-endpoints]
            [hyperopen.api.endpoints.orders :as orders-endpoints]
            [hyperopen.api.endpoints.vaults :as vaults-endpoints]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]))

(defn- ok-response
  [payload]
  #js {:ok true
       :status 200
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(defn- ok-text-response
  [payload]
  #js {:ok true
       :status 200
       :text (fn []
               (js/Promise.resolve
                (if (string? payload)
                  payload
                  (js/JSON.stringify (clj->js payload)))))})

(defn- error-text-response
  [status payload]
  #js {:ok false
       :status status
       :text (fn []
               (js/Promise.resolve
                (if (string? payload)
                  payload
                  (js/JSON.stringify (clj->js payload)))))} )

(deftest ws-account-endpoints-coverage-smoke-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      (fn [body _opts]
                        (case (get body "type")
                          "userFunding" (if (= 0 (get body "startTime"))
                                          {:data {:fundings [{:time 1000}]}}
                                          {:data {:fundings []}})
                          "spotClearinghouseState" {:ok true}
                          "userAbstraction" "unifiedAccount"
                          "clearinghouseState" {:ok true}
                          "portfolio" {:data {"day" {:equity "1"}
                                              "perpAllTime" {:equity "2"}}}
                          "userFees" {:makerFee "0.01"}
                          "userNonFundingLedgerUpdates" {:data {:nonFundingLedgerUpdates [{:time 123}]}}
                          nil)))
          normalize-rows-fn (fn [rows]
                              (mapv (fn [row]
                                      {:time-ms (:time row)})
                                    rows))
          summary-input [["day" {:v 1}]
                         ["week" {:v 2}]
                         ["month" {:v 3}]
                         ["3m" {:v 4}]
                         ["6m" {:v 5}]
                         ["1y" {:v 6}]
                         ["2y" {:v 7}]
                         ["alltime" {:v 8}]
                         ["perpday" {:v 9}]
                         ["perpweek" {:v 10}]
                         ["perpmonth" {:v 11}]
                         ["perp3m" {:v 12}]
                         ["perp6m" {:v 13}]
                         ["perp1y" {:v 14}]
                         ["perp2y" {:v 15}]
                         ["perpalltime" {:v 16}]
                         ["customRange" {:v 17}]]
          summary (account-endpoints/normalize-portfolio-summary summary-input)]
      (-> (js/Promise.all
           #js [(account-endpoints/request-user-funding-history! post-info!
                                                                 normalize-rows-fn
                                                                 identity
                                                                 "0xAbC"
                                                                 0
                                                                 5000
                                                                 {})
                (account-endpoints/request-user-funding-history! post-info!
                                                                 normalize-rows-fn
                                                                 identity
                                                                 nil
                                                                 0
                                                                 5000
                                                                 {})
                (account-endpoints/request-spot-clearinghouse-state! post-info! "0xabc" {})
                (account-endpoints/request-user-abstraction! post-info! "0xAbC" {})
                (account-endpoints/request-clearinghouse-state! post-info! "0xAbC" "vault" {})
                (account-endpoints/request-portfolio! post-info! "0xAbC" {})
                (account-endpoints/request-user-fees! post-info! "0xAbC" {})
                (account-endpoints/request-user-non-funding-ledger-updates! post-info!
                                                                            "0xAbC"
                                                                            10.5
                                                                            20.5
                                                                            {})
                (account-endpoints/request-portfolio! post-info! nil {})
                (account-endpoints/request-user-fees! post-info! nil {})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= [{:time-ms 1000}] (nth results* 0)))
               (is (= [] (nth results* 1)))
               (is (= {:ok true} (nth results* 2)))
               (is (= "unifiedAccount" (nth results* 3)))
               (is (= {:ok true} (nth results* 4)))
               (is (= {:day {:equity "1"}
                       :perp-all-time {:equity "2"}}
                      (nth results* 5)))
               (is (= {:makerFee "0.01"} (nth results* 6)))
               (is (= [{:time 123}] (nth results* 7)))
               (is (= {} (nth results* 8)))
               (is (nil? (nth results* 9)))
               (is (= :unified (account-endpoints/normalize-user-abstraction-mode "portfolioMargin")))
               (is (= :classic (account-endpoints/normalize-user-abstraction-mode "dexAbstraction")))
               (is (= :classic (account-endpoints/normalize-user-abstraction-mode "unknown")))
               (is (= 17 (count summary)))
               (is (some #(= "portfolio" (get-in % [0 "type"])) @calls))
               (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest ws-account-endpoints-extra-agent-and-webdata-normalization-coverage-test
  (async done
    (let [calls (atom [])
          address "0xABCDEFabcdefABCDEFabcdefABCDEFabcdefABCD"
          post-info! (api-stubs/post-info-stub
                      calls
                      (fn [body _opts]
                        (case (get body "type")
                          "extraAgents" {:data {:wallets [{:walletName " Desk "
                                                           :walletAddress address
                                                           :valid-until-ms "1700"}
                                                          {:agentName "Ignored"
                                                           :agentAddress "  "}
                                                          :invalid]}}
                          "webData2" "not-a-map"
                          nil)))]
      (-> (js/Promise.all
           #js [(account-endpoints/request-extra-agents! post-info! address {})
                (account-endpoints/request-user-webdata2! post-info! address {})
                (account-endpoints/request-extra-agents! post-info! nil {})
                (account-endpoints/request-user-webdata2! post-info! nil {})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= [{:row-kind :named
                        :name "Desk"
                        :approval-name "Desk"
                        :address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                        :valid-until-ms 1700}]
                      (nth results* 0)))
               (is (= {} (nth results* 1)))
               (is (= [] (nth results* 2)))
               (is (= {} (nth results* 3)))
               (let [[extra-body extra-opts] (first @calls)
                     [webdata-body webdata-opts] (second @calls)]
                 (is (= {"type" "extraAgents"
                         "user" address}
                        extra-body))
                 (is (= {:priority :high
                         :dedupe-key [:extra-agents "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
                         :cache-ttl-ms 5000}
                        extra-opts))
                 (is (= {"type" "webData2"
                         "user" address}
                        webdata-body))
                 (is (= {:priority :high
                         :dedupe-key [:user-webdata2 "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"]
                         :cache-ttl-ms 5000}
                        webdata-opts)))
               (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest ws-market-endpoints-coverage-smoke-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      (fn [body _opts]
                        (case (get body "type")
                          "metaAndAssetCtxs" [{:universe []
                                               :marginTables []}
                                              []]
                          "perpDexs" [{:name "vault"}
                                      {:name "scaled"
                                       :deployerFeeScale 0.25}]
                          "candleSnapshot" []
                          "spotMeta" {:ok true}
                          "webData2" {:ok true}
                          "predictedFundings" {:rows []}
                          nil)))
          now-ms-fn (fn [] 10000)]
      (with-redefs [hyperopen.asset-selector.markets/build-perp-markets
                    (fn [_meta _asset-ctxs _token-by-index & {:keys [dex]}]
                      [{:key [:perp dex]
                        :coin (or dex "DEFAULT")}])
                    hyperopen.asset-selector.markets/build-spot-markets
                    (fn [_spot-meta _spot-asset-ctxs]
                      [{:key [:spot "HYPE"]
                        :coin "HYPE"}])
                    hyperopen.asset-selector.markets/resolve-market-by-coin
                    (fn [market-by-key _active-asset]
                      (get market-by-key [:perp "vault"]))]
        (let [market-state (market-endpoints/build-market-state (fn [] 77)
                                                                 "BTC"
                                                                 :live
                                                                 ["vault"]
                                                                 {:tokens [{:index 0
                                                                            :name "HYPE"}]}
                                                                 {}
                                                                 [[{:meta :m0}
                                                                   {:ctx :c0}]
                                                                  [{:meta :m1}
                                                                   {:ctx :c1}]])]
          (-> (js/Promise.all
               #js [(market-endpoints/request-asset-contexts! post-info! {})
                    (market-endpoints/request-meta-and-asset-ctxs! post-info! nil {})
                    (market-endpoints/request-meta-and-asset-ctxs! post-info! "vault" {})
                    (market-endpoints/request-perp-dexs! post-info! {})
                    (market-endpoints/request-candle-snapshot! post-info!
                                                               now-ms-fn
                                                               "BTC"
                                                               {:interval :1m
                                                                :bars 10
                                                                :priority :low})
                    (market-endpoints/request-candle-snapshot! post-info! now-ms-fn nil {})
                    (market-endpoints/request-spot-meta! post-info! {})
                    (market-endpoints/request-public-webdata2! post-info! {})
                    (market-endpoints/request-predicted-fundings! post-info! {})])
              (.then
               (fn [results]
                 (let [results* (vec (array-seq results))]
                   (is (map? (nth results* 0)))
                   (is (= [:meta-and-asset-ctxs "vault"]
                          (:dedupe-key (second (nth @calls 2)))))
                   (is (= ["vault" "scaled"] (:dex-names (nth results* 3))))
                   (is (nil? (nth results* 5)))
                   (is (= 8 (count @calls)))
                   (is (some #(= {"type" "spotMeta"} (first %)) @calls))
                   (is (some #(= {"type" "webData2"
                                  "user" "0x0000000000000000000000000000000000000000"}
                                 (first %))
                             @calls))
                   (is (some #(= {"type" "predictedFundings"} (first %)) @calls))
                   (is (= 3 (count (:markets market-state))))
                   (is (= [:perp "vault"] (:key (:active-market market-state))))
                   (is (= 77 (:loaded-at-ms market-state)))
                   (done))))
              (.catch (async-support/unexpected-error done))))))))

(deftest ws-market-endpoints-funding-history-pagination-coverage-test
  (async done
    (let [calls (atom [])
          coin "BTC"
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve
                        {:data {:fundingHistory (case (count @calls)
                                                  1 [{:coin coin
                                                      :fundingRate "0.01"
                                                      :premium "0.001"
                                                      :time "100"}
                                                     {:coin coin
                                                      :fundingRate "0.02"
                                                      :premium "bad"
                                                      :time 101}]
                                                  2 [{:coin coin
                                                      :fundingRate "0.02"
                                                      :premium "0.002"
                                                      :time 101}
                                                     {:coin coin
                                                      :fundingRate "0.03"
                                                      :premium "0.003"
                                                      :time-ms "102"}]
                                                  [])}}))]
      (-> (js/Promise.all
           #js [(market-endpoints/request-market-funding-history!
                 post-info!
                 coin
                 {:start-time-ms 100
                  :end-time-ms 102
                  :market-funding-history-page-size 2
                  :dedupe-key :explicit-flight
                  :cache-key :explicit-cache
                  :priority :low})
                (market-endpoints/request-market-funding-history!
                 post-info!
                 "   "
                 {:start-time-ms 100})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))
                   rows (nth results* 0)
                   [[body-1 opts-1] [body-2 opts-2]] @calls]
               (is (= [] (nth results* 1)))
               (is (= [{:coin coin
                        :time-ms 100
                        :time 100
                        :funding-rate-raw 0.01
                        :fundingRate 0.01
                        :premium 0.001}
                       {:coin coin
                        :time-ms 101
                        :time 101
                        :funding-rate-raw 0.02
                        :fundingRate 0.02
                        :premium 0.002}
                       {:coin coin
                        :time-ms 102
                        :time 102
                        :funding-rate-raw 0.03
                        :fundingRate 0.03
                        :premium 0.003}]
                      rows))
               (is (= 2 (count @calls)))
               (is (= {"type" "fundingHistory"
                       "coin" coin
                       "startTime" 100
                       "endTime" 102}
                      body-1))
               (is (= {"type" "fundingHistory"
                       "coin" coin
                       "startTime" 102
                       "endTime" 102}
                      body-2))
               (is (= {:priority :low
                       :dedupe-key [:market-funding-history-page
                                    :explicit-flight
                                    100
                                    102]
                       :cache-key [:market-funding-history-page-cache
                                   :explicit-cache
                                   100
                                   102]
                       :cache-ttl-ms 15000}
                      opts-1))
               (is (= {:priority :low
                       :dedupe-key [:market-funding-history-page
                                    :explicit-flight
                                    102
                                    102]
                       :cache-key [:market-funding-history-page-cache
                                   :explicit-cache
                                   102
                                   102]
                       :cache-ttl-ms 15000}
                      opts-2))
               (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest ws-orders-endpoints-coverage-smoke-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      (fn [body _opts]
                        (case (get body "type")
                          "frontendOpenOrders" [{:coin "BTC"}]
                          "userFills" [{:coin "ETH"}]
                          "historicalOrders" {:orders [{:coin "SOL"
                                                        :oid 1}
                                                       {:order {:coin "BTC"
                                                                :oid 2}}
                                                       "invalid"]}
                          nil)))]
      (-> (js/Promise.all
           #js [(orders-endpoints/request-frontend-open-orders! post-info! "0xabc" nil {})
                (orders-endpoints/request-frontend-open-orders! post-info! "0xabc" "vault" {})
                (orders-endpoints/request-user-fills! post-info! "0xabc" {})
                (orders-endpoints/request-historical-orders! post-info! "0xabc" {})
                (orders-endpoints/request-historical-orders! post-info! nil {})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= {"type" "frontendOpenOrders"
                       "user" "0xabc"}
                      (first (nth @calls 0))))
               (is (= {"type" "frontendOpenOrders"
                       "user" "0xabc"
                       "dex" "vault"}
                      (first (nth @calls 1))))
               (is (= {"type" "userFills"
                       "user" "0xabc"
                       "aggregateByTime" true}
                      (first (nth @calls 2))))
               (is (= 2 (count (nth results* 3))))
               (is (= "SOL" (get-in results* [3 0 :order :coin])))
               (is (= [] (nth results* 4)))
               (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest ws-orders-endpoints-historical-orders-payload-shape-coverage-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      (fn [body _opts]
                        (case (count @calls)
                          1 {:historicalOrders [{:coin "ETH"
                                                 :oid 2}]}
                          2 {:data [{:coin "DOGE"
                                     :oid 3}]}
                          {:data {:unexpected true}})))]
      (-> (js/Promise.all
           #js [(orders-endpoints/request-historical-orders! post-info! "0xAbC" {:priority :low})
                (orders-endpoints/request-historical-orders! post-info! "0xAbC" {:priority :low})
                (orders-endpoints/request-historical-orders! post-info! "0xAbC" {:priority :low})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= "ETH" (get-in results* [0 0 :order :coin])))
               (is (= "DOGE" (get-in results* [1 0 :order :coin])))
               (is (= [] (nth results* 2)))
               (is (= {:priority :low
                       :dedupe-key [:historical-orders "0xabc"]
                       :cache-ttl-ms 5000}
                      (second (first @calls))))
               (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest ws-funding-hyperunit-endpoints-normalizer-coverage-test
  (let [generate-response (funding-hyperunit-endpoints/normalize-generate-address-response
                           {:address " 0xProtocol "
                            :status " signed "
                            :signatures {:field-node "sig-a"
                                         :ignored " "}
                            :signature-operation-id "op-1"
                            :signature-endpoint-error "signature lag"
                            :is-sufficiently-signed "false"
                            :detail "still signing"})
        operations-response (funding-hyperunit-endpoints/normalize-operations-response
                             {:addresses [{:sourceCoinType "Bitcoin"
                                           :sourceChain "bitcoin"
                                           :destinationChain "hyperliquid"
                                           :address "tb1x"
                                           :signatures {"field-node" "sig-a"}}
                                          {:address "  "}]
                              :operations [{:operationId "op-1"
                                            :protocolAddress "tb1x"
                                            :sourceAddress "tb1src"
                                            :destinationAddress "0xDest"
                                            :sourceChain "bitcoin"
                                            :destinationChain "hyperliquid"
                                            :sourceAmount 42
                                            :destinationFeeAmount "0.1"
                                            :sweepFeeAmount "0"
                                            :state "waitForSrcTxFinalization"
                                            :sourceTxConfirmations "2"
                                            :destinationTxConfirmations 1
                                            :positionInWithdrawQueue "3"
                                            :asset "BTC"}
                                           {:operationId "op-2"
                                            :state "  "
                                            :asset "USDC"}]})
        estimate-fees-response (funding-hyperunit-endpoints/normalize-estimate-fees-response
                                {"bitcoin" {"bitcoin-depositEta" "21m"
                                            "bitcoin-depositFee" "2065"
                                            "bitcoin-withdrawalEta" "14m"
                                            "bitcoin-withdrawalFee" 715}
                                 :ethereum {:ethereum-deposit-eta "5m"
                                            :ethereum-withdrawal-fee "1.2"}})
        withdrawal-queue-response (funding-hyperunit-endpoints/normalize-withdrawal-queue-response
                                   {:bitcoin {:lastWithdrawQueueOperationTxID "tx-a"
                                              :withdrawalQueueLength 2}
                                    :ethereum {:lastWithdrawQueueOperationTxId "tx-b"
                                               :withdrawal-queue-length "0"}})]
    (is (= "https://api.hyperunit-testnet.xyz/gen/bitcoin/hyperliquid/USDC/0xAbC%20123"
           (funding-hyperunit-endpoints/generate-address-url
            "https://api.hyperunit-testnet.xyz/"
            " bitcoin "
            "hyperliquid"
            " USDC "
            "0xAbC 123")))
    (is (= "https://api.hyperunit.xyz/operations/0xAbC"
           (funding-hyperunit-endpoints/operations-url
            funding-hyperunit-endpoints/default-mainnet-base-url
            "0xAbC")))
    (is (= "https://api.hyperunit.xyz/v2/estimate-fees"
           (funding-hyperunit-endpoints/estimate-fees-url nil)))
    (is (= "https://api.hyperunit.xyz/withdrawal-queue"
           (funding-hyperunit-endpoints/withdrawal-queue-url nil)))
    (is (= {:address "0xProtocol"
            :status "signed"
            :signatures {"field-node" "sig-a"}
            :signature-operation-id "op-1"
            :signature-endpoint-error "signature lag"
            :sufficiently-signed? false
            :error "still signing"}
           generate-response))
    (is (= [{:source-coin-type "bitcoin"
             :source-chain "bitcoin"
             :destination-chain "hyperliquid"
             :address "tb1x"
             :signatures {"field-node" "sig-a"}}]
           (:addresses operations-response)))
    (is (= "op-1" (get-in operations-response [:operations 0 :operation-id])))
    (is (= :wait-for-src-tx-finalization
           (get-in operations-response [:operations 0 :state-key])))
    (is (= "42"
           (get-in operations-response [:operations 0 :source-amount])))
    (is (= 3
           (get-in operations-response [:operations 0 :position-in-withdraw-queue])))
    (is (= "usdc"
           (get-in operations-response [:operations 1 :asset])))
    (is (= {:chain "bitcoin"
            :deposit-eta "21m"
            :withdrawal-eta "14m"
            :deposit-fee 2065
            :withdrawal-fee 715
            :metrics {"bitcoin-depositEta" "21m"
                      "bitcoin-depositFee" 2065
                      "bitcoin-withdrawalEta" "14m"
                      "bitcoin-withdrawalFee" 715}}
           (get-in estimate-fees-response [:by-chain "bitcoin"])))
    (is (= 2 (count (:chains estimate-fees-response))))
    (is (= {:chain "bitcoin"
            :last-withdraw-queue-operation-tx-id "tx-a"
            :withdrawal-queue-length 2}
           (get-in withdrawal-queue-response [:by-chain "bitcoin"])))
    (is (= {:chain "ethereum"
            :last-withdraw-queue-operation-tx-id "tx-b"
            :withdrawal-queue-length 0}
           (get-in withdrawal-queue-response [:by-chain "ethereum"])))))

(deftest ws-funding-hyperunit-endpoints-request-coverage-test
  (async done
    (let [generate-calls (atom [])
          generate-fetch-fn (fn [url init]
                              (swap! generate-calls conj [url (js->clj init :keywordize-keys true)])
                              (js/Promise.resolve
                               (ok-text-response
                                {:address "0xProtocolAddress"
                                 :status "OK"
                                 :isSufficientlySigned "true"
                                 :signatures {"field-node" "sig-a"
                                              "hl-node" "sig-b"}})))
          error-fetch-fn (fn [_url _init]
                           (js/Promise.resolve
                            (error-text-response 422 {:message "invalid request"})))
          operations-calls (atom 0)
          operations-fetch-fn (fn [_url _init]
                                (swap! operations-calls inc)
                                (js/Promise.resolve
                                 {:addresses [{:sourceChain "bitcoin"
                                               :destinationChain "hyperliquid"
                                               :address "tb1x"}]
                                  :operations [{:operationId "op-1"
                                                :state "queued"
                                                :asset "BTC"}]}))
          estimate-calls (atom [])
          estimate-fetch-fn (fn [url init]
                              (swap! estimate-calls conj [url (js->clj init :keywordize-keys true)])
                              (js/Promise.resolve
                               (ok-response
                                {:bitcoin {"bitcoin-depositFee" 10
                                           "bitcoin-withdrawalFee" 11}})))
          queue-fetch-fn (fn [_url _init]
                           (js/Promise.resolve
                            #js {:ok true
                                 :status 200
                                 :text (fn []
                                         (js/Promise.resolve ""))}))]
      (-> (js/Promise.all
           #js [(funding-hyperunit-endpoints/request-generate-address!
                 generate-fetch-fn
                 "https://api.hyperunit-testnet.xyz/"
                 {:source-chain " Bitcoin "
                  :destination-chain "hyperliquid"
                  :asset " BTC "
                  :destination-address "0xAbC"})
                (-> (funding-hyperunit-endpoints/request-generate-address!
                     nil
                     funding-hyperunit-endpoints/default-mainnet-base-url
                     {:source-chain nil
                      :destination-chain "hyperliquid"
                      :asset "btc"
                      :destination-address "0xabc"})
                    (.then (fn [_] {:status :unexpected}))
                    (.catch (fn [err]
                              {:message (.-message err)})))
                (-> (funding-hyperunit-endpoints/request-generate-address!
                     error-fetch-fn
                     funding-hyperunit-endpoints/default-mainnet-base-url
                     {:source-chain "bitcoin"
                      :destination-chain "hyperliquid"
                      :asset "btc"
                      :destination-address "0xabc"})
                    (.then (fn [_] {:status :unexpected}))
                    (.catch (fn [err]
                              {:status (aget err "status")
                               :message (.-message err)})))
                (funding-hyperunit-endpoints/request-operations!
                 operations-fetch-fn
                 funding-hyperunit-endpoints/default-mainnet-base-url
                 {:address "0xabc"})
                (funding-hyperunit-endpoints/request-operations!
                 operations-fetch-fn
                 funding-hyperunit-endpoints/default-mainnet-base-url
                 {:address "   "})
                (funding-hyperunit-endpoints/request-estimate-fees!
                 estimate-fetch-fn
                 funding-hyperunit-endpoints/default-mainnet-base-url
                 {:fetch-opts {:method "post"
                               :body "{}"}})
                (funding-hyperunit-endpoints/request-withdrawal-queue!
                 queue-fetch-fn
                 funding-hyperunit-endpoints/default-mainnet-base-url
                 {})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))
                   [generate-url generate-init] (first @generate-calls)
                   [estimate-url estimate-init] (first @estimate-calls)]
               (is (= "https://api.hyperunit-testnet.xyz/gen/bitcoin/hyperliquid/btc/0xAbC"
                      generate-url))
               (is (= {:method "GET"}
                      generate-init))
               (is (= {:address "0xProtocolAddress"
                       :status "OK"
                       :signatures {"field-node" "sig-a"
                                    "hl-node" "sig-b"}
                       :signature-operation-id nil
                       :signature-endpoint-error nil
                       :sufficiently-signed? true
                       :error nil}
                      (nth results* 0)))
               (is (= {:message "HyperUnit source chain is required."}
                      (nth results* 1)))
               (is (= {:status 422
                       :message "invalid request"}
                      (nth results* 2)))
               (is (= "tb1x"
                      (get-in results* [3 :addresses 0 :address])))
               (is (= {:addresses []
                       :operations []
                       :error nil}
                      (nth results* 4)))
               (is (= 1 @operations-calls))
               (is (= "https://api.hyperunit.xyz/v2/estimate-fees"
                      estimate-url))
               (is (= {:method "post"
                       :headers {:Content-Type "application/json"}
                       :body "{}"}
                      estimate-init))
               (is (= 10
                      (get-in results* [5 :by-chain "bitcoin" :deposit-fee])))
               (is (= {:by-chain {}
                       :chains []
                       :error nil}
                      (nth results* 6)))
               (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest ws-vaults-endpoints-normalization-coverage-smoke-test
  (let [pnls (vaults-endpoints/normalize-vault-pnls
              [["day" ["1.5" "2.5"]]
               ["allTime" ["3.5"]]
               ["ignored" ["4.5"]]
               [:bad]])
        relationship-parent (vaults-endpoints/normalize-vault-relationship
                             {:type "parent"
                              :data {:childAddresses ["0xC1"
                                                      " "]}})
        relationship-child (vaults-endpoints/normalize-vault-relationship
                            {:type "child"
                             :data {:parentAddress "0xPARENT"}})
        summary (vaults-endpoints/normalize-vault-summary
                 {:name " Vault Alpha "
                  :vaultAddress "0xABc"
                  :leader "0xDEF"
                  :tvl "12.5"
                  :isClosed "false"
                  :relationship {:type "parent"
                                 :data {:childAddresses ["0xC1"]}}
                  :createTimeMillis "1700"})
        index-row (vaults-endpoints/normalize-vault-index-row
                   {:apr "0.25"
                    :pnls [["day" ["1.0"]]]
                    :summary {:vaultAddress "0xA1"
                              :leader "0xB2"}})
        normalized-details (vaults-endpoints/normalize-vault-details
                            {:name "Vault Detail"
                             :vaultAddress "0xVaUlT"
                             :leader "0xLEADER"
                             :description "  hello  "
                             :portfolio [["day" {:accountValue "10"}]]
                             :apr "0.7"
                             :followers [{:user "0xA"}
                                         {:user "0xB"}]
                             :followersCount "3"
                             :isClosed "false"
                             :relationship {:type "child"
                                            :data {:parentAddress "0xPARENT"}}
                             :allowDeposits "true"
                             :alwaysCloseOnWithdraw false})]
    (is (= {:day [1.5 2.5]
            :all-time [3.5]}
           pnls))
    (is (= {:type :parent
            :child-addresses ["0xc1"]}
           relationship-parent))
    (is (= {:type :child
            :parent-address "0xparent"}
           relationship-child))
    (is (= "Vault Alpha" (:name summary)))
    (is (= "0xabc" (:vault-address summary)))
    (is (= "0xdef" (:leader summary)))
    (is (= 12.5 (:tvl summary)))
    (is (= "0xa1" (:vault-address index-row)))
    (is (= {:day [1]} (:snapshot-by-key index-row)))
    (is (= 2 (:followers-count normalized-details)))
    (is (true? (:allow-deposits? normalized-details)))
    (is (false? (:always-close-on-withdraw? normalized-details)))
    (is (= ["0x1" "0x2" "0x3"]
           (mapv :vault-address
                 (vaults-endpoints/merge-vault-index-with-summaries
                  [{:summary {:vaultAddress "0x1"
                              :createTimeMillis 100}}
                   {:summary {:vaultAddress "0x2"
                              :createTimeMillis 200}}]
                  [{:summary {:vaultAddress "0x2"
                              :createTimeMillis 350}}
                   {:summary {:vaultAddress "0x3"
                              :createTimeMillis 300}}
                   {:summary {:vaultAddress "0x4"
                              :createTimeMillis 20}}]))))
    (is (= {:vault-address "0xa1"
            :equity 120.5
            :equity-raw "120.5"
            :locked-until-ms 1700}
           (vaults-endpoints/normalize-user-vault-equity
            {:vaultAddress "0xA1"
             :equity "120.5"
             :lockedUntilTimestamp "1700"})))
    (is (= [{:vault-address "0xa1"
             :equity 1
             :equity-raw "1"
             :locked-until-ms nil}]
           (vaults-endpoints/normalize-user-vault-equities
            [{:vaultAddress "0xA1"
              :equity "1"}
             {:vaultAddress " "
              :equity "2"}])))))

(deftest ws-vaults-endpoints-request-coverage-smoke-test
  (async done
    (let [fetch-calls (atom [])
          fetch-fn (fn [url init]
                     (swap! fetch-calls conj [url init])
                     (js/Promise.resolve
                      (ok-response
                       [{:apr "0.25"
                         :summary {:name "Alpha Vault"
                                   :vaultAddress "0xABc"
                                   :leader "0xDEF"
                                   :tvl "12.5"
                                   :isClosed "false"
                                   :relationship {:type "normal"}
                                   :createTimeMillis "1700"}}])))
          post-calls (atom [])
          post-info! (api-stubs/post-info-stub
                      post-calls
                      (fn [body _opts]
                        (case (get body "type")
                          "vaultSummaries" [{:summary {:name "Summary Vault"
                                                       :vaultAddress "0xA1"
                                                       :leader "0xB2"
                                                       :tvl "9.0"
                                                       :createTimeMillis 22}}]
                          "userVaultEquities" [{:vaultAddress "0xA1"
                                                :equity "120.5"
                                                :lockedUntilTimestamp "1700"}]
                          "vaultDetails" {:name "Vault Detail"
                                          :vaultAddress "0xVaUlT"
                                          :leader "0xLEADER"
                                          :portfolio [["day" {:accountValue "10"}]]
                                          :followers [{:user "0xA"}]
                                          :followersCount "2"
                                          :allowDeposits "true"}
                          "webData2" {:ok true}
                          nil)))]
      (-> (js/Promise.all
           #js [(vaults-endpoints/request-vault-index! fetch-fn "https://vaults.test/index" {:fetch-opts {:cache "no-store"}})
                (-> (vaults-endpoints/request-vault-index!
                     (fn [_url _init]
                       (js/Promise.resolve #js {:ok false
                                                :status 503}))
                     "https://vaults.test/fail"
                     {})
                    (.then (fn [_] {:status :unexpected}))
                    (.catch (fn [err]
                              {:status (aget err "status")})))
                (-> (vaults-endpoints/request-vault-index!
                     (fn [_url _init]
                       (js/Promise.resolve [{:summary {:vaultAddress "0xB1"}}]))
                     "https://vaults.test/direct"
                     {})
                    (.then count))
                (vaults-endpoints/request-vault-summaries! post-info! {})
                (vaults-endpoints/request-user-vault-equities! post-info! "0xAbC" {})
                (vaults-endpoints/request-vault-details! post-info! "0xVaUlT" {:user "0xUsEr"})
                (vaults-endpoints/request-vault-webdata2! post-info! "0xVaUlT" {})])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= {:status 503} (nth results* 1)))
               (is (= 1 (nth results* 2)))
               (is (= "https://vaults.test/index" (ffirst @fetch-calls)))
               (is (= {"type" "vaultSummaries"} (ffirst @post-calls)))
               (is (= {"type" "userVaultEquities"
                       "user" "0xabc"}
                      (first (nth @post-calls 1))))
               (is (= {"type" "vaultDetails"
                       "vaultAddress" "0xvault"
                       "user" "0xuser"}
                      (first (nth @post-calls 2))))
               (is (= {"type" "webData2"
                       "user" "0xvault"}
                      (first (nth @post-calls 3))))
               (done))))
          (.catch (async-support/unexpected-error done))))))
