(ns hyperopen.api.instance-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.compat :as api-compat]
            [hyperopen.api.endpoints.orders :as order-endpoints]
            [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.gateway.orders :as order-gateway]
            [hyperopen.api.gateway.vaults :as vault-gateway]
            [hyperopen.api.instance :as api-instance]
            [hyperopen.api.service :as api-service]
            [hyperopen.domain.funding-history :as funding-history]))

(deftest make-api-delegates-generated-ops-test
  (let [calls (atom [])
        store (atom {:active-asset "BTC"})
        record! (fn [label args]
                  (swap! calls conj [label args])
                  {:ok label})]
    (with-redefs [api-service/request-info! (fn
                                              ([service body opts]
                                               (record! :request-info [service body opts]))
                                              ([service body opts attempt]
                                               (record! :request-info-attempt [service body opts attempt])))
                  api-service/get-request-stats (fn [service]
                                                  (record! :get-request-stats [service]))
                  api-service/reset-service! (fn [service]
                                               (record! :reset-service [service]))
                  api-service/ensure-perp-dexs-data! (fn [service store* request-perp-dexs! opts]
                                                       (request-perp-dexs! opts)
                                                       (record! :ensure-perp-dexs-data [service store* opts]))
                  api-service/ensure-spot-meta-data! (fn [service store* request-spot-meta! opts]
                                                       (request-spot-meta! opts)
                                                       (record! :ensure-spot-meta-data [service store* opts]))
                  api-service/ensure-public-webdata2! (fn [service request-public-webdata2! opts]
                                                        (request-public-webdata2! opts)
                                                        (record! :ensure-public-webdata2 [service opts]))
                  market-gateway/request-asset-contexts! (fn [deps opts]
                                                           (record! :request-asset-contexts [deps opts]))
                  market-gateway/request-meta-and-asset-ctxs! (fn [deps dex opts]
                                                                (record! :request-meta-and-asset-ctxs [deps dex opts]))
                  market-gateway/request-perp-dexs! (fn [deps opts]
                                                      (record! :request-perp-dexs [deps opts]))
                  market-gateway/request-candle-snapshot! (fn [deps coin opts]
                                                            (record! :request-candle-snapshot
                                                                     [coin opts ((:now-ms-fn deps))]))
                  market-gateway/request-spot-meta! (fn [deps opts]
                                                      (record! :request-spot-meta [deps opts]))
                  market-gateway/request-public-webdata2! (fn [deps opts]
                                                            (record! :request-public-webdata2 [deps opts]))
                  market-gateway/build-market-state (fn [now-ms-fn active-asset phase _dexs _spot-meta _spot-asset-ctxs _perp-results]
                                                      (record! :build-market-state [active-asset phase (now-ms-fn)]))
                  market-gateway/request-asset-selector-markets! (fn [{:keys [opts
                                                                               ensure-perp-dexs-data!
                                                                               ensure-spot-meta-data!
                                                                               ensure-public-webdata2!
                                                                               request-meta-and-asset-ctxs!
                                                                               build-market-state]}]
                                                                   (ensure-perp-dexs-data! {:priority :high})
                                                                   (ensure-spot-meta-data! {:priority :high})
                                                                   (ensure-public-webdata2! {:priority :high})
                                                                   (request-meta-and-asset-ctxs! "dex-a" {:priority :high})
                                                                   (build-market-state "BTC" (:phase opts) [] {} {} [])
                                                                   (record! :request-asset-selector-markets [opts]))
                  order-endpoints/request-frontend-open-orders! (fn [post-info! address dex opts]
                                                                  (record! :request-frontend-open-orders [post-info! address dex opts]))
                  order-gateway/request-user-fills! (fn [deps address opts]
                                                     (record! :request-user-fills [deps address opts]))
                  api-compat/fetch-historical-orders! (fn [deps address opts]
                                                        (record! :request-historical-orders-data [deps address opts]))
                  order-gateway/request-historical-orders! (fn [{:keys [request-historical-orders-data!]} address opts]
                                                            (request-historical-orders-data! address opts))
                  account-gateway/request-user-funding-history-data! (fn [deps address opts]
                                                                       (record! :request-user-funding-history-data [deps address opts]))
                  account-gateway/request-user-funding-history! (fn [{:keys [request-user-funding-history-data!]} address opts]
                                                                  (request-user-funding-history-data! address opts))
                  account-gateway/request-spot-clearinghouse-state! (fn [deps address opts]
                                                                       (record! :request-spot-clearinghouse-state [deps address opts]))
                  account-gateway/request-user-abstraction! (fn [deps address opts]
                                                              (record! :request-user-abstraction [deps address opts]))
                  account-gateway/request-clearinghouse-state! (fn [deps address dex opts]
                                                                 (record! :request-clearinghouse-state [deps address dex opts]))
                  vault-gateway/request-vault-index! (fn
                                                       ([deps]
                                                        (record! :request-vault-index [deps {}]))
                                                       ([deps opts]
                                                        (record! :request-vault-index [deps opts])))
                  vault-gateway/request-vault-summaries! (fn [deps opts]
                                                           (record! :request-vault-summaries [deps opts]))
                  vault-gateway/request-merged-vault-index! (fn
                                                              ([deps]
                                                               (record! :request-merged-vault-index [deps {}]))
                                                              ([deps opts]
                                                               (record! :request-merged-vault-index [deps opts])))
                  vault-gateway/request-user-vault-equities! (fn [deps address opts]
                                                               (record! :request-user-vault-equities [deps address opts]))
                  vault-gateway/request-vault-details! (fn [deps vault-address opts]
                                                        (record! :request-vault-details [deps vault-address opts]))
                  vault-gateway/request-vault-webdata2! (fn [deps vault-address opts]
                                                         (record! :request-vault-webdata2 [deps vault-address opts]))]
      (let [api (api-instance/make-api {:service {:id :instance-test-service}
                                        :now-ms-fn (fn [] 4242)
                                        :log-fn (fn [& _] nil)})]
        (is (= {:ok :request-info}
               ((:request-info! api) {"type" "ping"})))
        (is (= {:ok :request-info}
               ((:request-info! api) {"type" "ping"} {:priority :high})))
        (is (= {:ok :request-info-attempt}
               ((:request-info! api) {"type" "ping"} {:priority :high} 2)))

        (is (= {:ok :request-asset-contexts}
               ((:request-asset-contexts! api))))
        (is (= {:ok :request-asset-contexts}
               ((:request-asset-contexts! api) {:priority :high})))
        (is (= {:ok :request-meta-and-asset-ctxs}
               ((:request-meta-and-asset-ctxs! api) "dex-a")))
        (is (= {:ok :request-meta-and-asset-ctxs}
               ((:request-meta-and-asset-ctxs! api) "dex-a" {:priority :high})))
        (is (= {:ok :request-perp-dexs}
               ((:request-perp-dexs! api))))
        (is (= {:ok :request-perp-dexs}
               ((:request-perp-dexs! api) {:priority :high})))
        (is (= {:ok :request-candle-snapshot}
               ((:request-candle-snapshot! api) "BTC")))
        (is (= {:ok :request-candle-snapshot}
               ((:request-candle-snapshot! api) "ETH" :interval :5m :bars 20 :priority :low)))
        (is (= {:ok :request-spot-meta}
               ((:request-spot-meta! api))))
        (is (= {:ok :request-spot-meta}
               ((:request-spot-meta! api) {:priority :high})))
        (is (= {:ok :request-public-webdata2}
               ((:request-public-webdata2! api))))
        (is (= {:ok :request-public-webdata2}
               ((:request-public-webdata2! api) {:priority :high})))

        (is (= {:ok :ensure-perp-dexs-data}
               ((:ensure-perp-dexs-data! api) store)))
        (is (= {:ok :ensure-perp-dexs-data}
               ((:ensure-perp-dexs-data! api) store {:priority :high})))
        (is (= {:ok :ensure-spot-meta-data}
               ((:ensure-spot-meta-data! api) store)))
        (is (= {:ok :ensure-spot-meta-data}
               ((:ensure-spot-meta-data! api) store {:priority :high})))
        (is (= {:ok :ensure-public-webdata2}
               ((:ensure-public-webdata2! api))))
        (is (= {:ok :ensure-public-webdata2}
               ((:ensure-public-webdata2! api) {:force? true})))
        (is (= {:ok :request-asset-selector-markets}
               ((:request-asset-selector-markets! api) store)))
        (is (= {:ok :request-asset-selector-markets}
               ((:request-asset-selector-markets! api) store {:phase :bootstrap})))

        (is (= {:ok :request-frontend-open-orders}
               ((:request-frontend-open-orders! api) "0xabc")))
        (is (= {:ok :request-frontend-open-orders}
               ((:request-frontend-open-orders! api) "0xabc" {:priority :high})))
        (is (= {:ok :request-frontend-open-orders}
               ((:request-frontend-open-orders! api) "0xabc" "dex-a" {:priority :high})))
        (is (= {:ok :request-user-fills}
               ((:request-user-fills! api) "0xabc")))
        (is (= {:ok :request-user-fills}
               ((:request-user-fills! api) "0xabc" {:priority :high})))
        (is (= {:ok :request-historical-orders-data}
               ((:request-historical-orders! api) "0xabc")))
        (is (= {:ok :request-historical-orders-data}
               ((:request-historical-orders! api) "0xabc" {:priority :high})))

        (is (= {:ok :request-user-funding-history-data}
               ((:request-user-funding-history! api) "0xabc")))
        (is (= {:ok :request-user-funding-history-data}
               ((:request-user-funding-history! api) "0xabc" {:priority :high})))
        (is (= {:ok :request-spot-clearinghouse-state}
               ((:request-spot-clearinghouse-state! api) "0xabc")))
        (is (= {:ok :request-spot-clearinghouse-state}
               ((:request-spot-clearinghouse-state! api) "0xabc" {:priority :high})))
        (is (= {:ok :request-user-abstraction}
               ((:request-user-abstraction! api) "0xabc")))
        (is (= {:ok :request-user-abstraction}
               ((:request-user-abstraction! api) "0xabc" {:priority :high})))
        (is (= {:ok :request-clearinghouse-state}
               ((:request-clearinghouse-state! api) "0xabc" "dex-a")))
        (is (= {:ok :request-clearinghouse-state}
               ((:request-clearinghouse-state! api) "0xabc" "dex-a" {:priority :high})))

        (is (= {:ok :request-vault-index}
               ((:request-vault-index! api))))
        (is (= {:ok :request-vault-index}
               ((:request-vault-index! api) {:fetch-opts {:cache "no-store"}})))
        (is (= {:ok :request-vault-summaries}
               ((:request-vault-summaries! api))))
        (is (= {:ok :request-vault-summaries}
               ((:request-vault-summaries! api) {:priority :high})))
        (is (= {:ok :request-merged-vault-index}
               ((:request-merged-vault-index! api))))
        (is (= {:ok :request-merged-vault-index}
               ((:request-merged-vault-index! api) {:priority :low})))
        (is (= {:ok :request-user-vault-equities}
               ((:request-user-vault-equities! api) "0xabc")))
        (is (= {:ok :request-user-vault-equities}
               ((:request-user-vault-equities! api) "0xabc" {:priority :high})))
        (is (= {:ok :request-vault-details}
               ((:request-vault-details! api) "0xvault")))
        (is (= {:ok :request-vault-details}
               ((:request-vault-details! api) "0xvault" {:user "0xabc"})))
        (is (= {:ok :request-vault-webdata2}
               ((:request-vault-webdata2! api) "0xvault")))
        (is (= {:ok :request-vault-webdata2}
               ((:request-vault-webdata2! api) "0xvault" {:priority :high})))

        (is (= 4242
               (:end-time-ms
                ((:normalize-funding-history-filters api) {:coin-set #{"BTC"}}))))
        (is (= [{:coin "BTC" :time-ms 4200}]
               ((:filter-funding-history-rows api)
                [{:coin "BTC" :time-ms 4200}]
                {:coin-set #{"BTC"}})))

        (is (= {:ok :get-request-stats}
               ((:get-request-stats api))))
        (is (= {:ok :reset-service}
               ((:reset-request-runtime! api))))

        (is (some #(= :build-market-state (first %)) @calls))
        (is (some #(= :request-info-attempt (first %)) @calls))
        (is (some #(= :request-frontend-open-orders (first %)) @calls))))))

(deftest make-api-falls-back-to-default-service-now-and-log-test
  (let [calls (atom [])]
    (with-redefs [api-instance/make-default-api-service (fn []
                                                           {:id :default-service})
                  api-service/now-ms (fn [service]
                                       (swap! calls conj [:now-ms service])
                                       777)
                  api-service/log-fn (fn [service]
                                       (swap! calls conj [:log-fn service])
                                       (fn [& _] :log-return))]
      (let [api (api-instance/make-api {})]
        (is (= {:id :default-service}
               (:service api)))
        (is (= 777
               ((:now-ms api))))
        (is (= :log-return
               ((:log-fn api) :message)))
        (is (= 777
               (:end-time-ms
                ((:normalize-funding-history-filters api) {:coin-set #{"ETH"}}))))
        (is (= [{:coin "ETH" :time-ms 700}]
               ((:filter-funding-history-rows api)
                [{:coin "ETH" :time-ms 700}]
                {:coin-set #{"ETH"}})))
        (is (some #(= :now-ms (first %)) @calls))
        (is (some #(= :log-fn (first %)) @calls))))))
