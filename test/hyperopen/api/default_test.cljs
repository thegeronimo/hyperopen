(ns hyperopen.api.default-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.api.compat :as api-compat]
            [hyperopen.api.default :as api]
            [hyperopen.api.endpoints.orders :as order-endpoints]
            [hyperopen.api.gateway.account :as account-gateway]
            [hyperopen.api.gateway.market :as market-gateway]
            [hyperopen.api.gateway.orders :as order-gateway]
            [hyperopen.api.gateway.vaults :as vault-gateway]
            [hyperopen.api.instance :as api-instance]
            [hyperopen.api.service :as api-service]
            [hyperopen.platform :as platform]))

(use-fixtures
  :each
  {:before (fn []
             (api/reset-api-service!))
   :after (fn []
            (api/reset-api-service!))})

(deftest default-api-public-wrappers-delegate-test
  (let [calls (atom [])
        store (atom {:active-asset "BTC"})
        record! (fn [label args]
                  (swap! calls conj [label args])
                  {:ok label})]
    (with-redefs [api-instance/make-api (fn [opts]
                                          (record! :make-api [opts]))
                  api-service/log-fn (fn [_service]
                                       (fn [& _] nil))
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
                  api-compat/fetch-asset-contexts! (fn [deps store* opts]
                                                     (record! :fetch-asset-contexts [deps store* opts]))
                  market-gateway/request-meta-and-asset-ctxs! (fn [deps dex opts]
                                                                (record! :request-meta-and-asset-ctxs [deps dex opts]))
                  market-gateway/request-perp-dexs! (fn [deps opts]
                                                      (record! :request-perp-dexs [deps opts]))
                  api-compat/fetch-perp-dexs! (fn [deps store* opts]
                                                (record! :fetch-perp-dexs [deps store* opts]))
                  market-gateway/request-candle-snapshot! (fn [deps coin opts]
                                                            (record! :request-candle-snapshot [deps coin opts]))
                  api-compat/fetch-candle-snapshot! (fn [deps store* opts]
                                                      (record! :fetch-candle-snapshot [deps store* opts]))
                  order-endpoints/request-frontend-open-orders! (fn [post-info! address dex opts]
                                                                  (record! :request-frontend-open-orders [post-info! address dex opts]))
                  api-compat/fetch-frontend-open-orders! (fn [deps store* address opts]
                                                          (record! :fetch-frontend-open-orders [deps store* address opts]))
                  order-gateway/request-user-fills! (fn [deps address opts]
                                                     (record! :request-user-fills [deps address opts]))
                  api-compat/fetch-user-fills! (fn [deps store* address opts]
                                                 (record! :fetch-user-fills [deps store* address opts]))
                  api-compat/fetch-historical-orders! (fn [deps address opts]
                                                        (record! :fetch-historical-orders [deps address opts]))
                  order-gateway/request-historical-orders! (fn [{:keys [request-historical-orders-data!]} address opts]
                                                            (request-historical-orders-data! address opts))
                  account-gateway/request-user-funding-history-data! (fn [deps address opts]
                                                                       ((:normalize-funding-history-filters deps) {:coin-set #{"BTC"}})
                                                                       (record! :request-user-funding-history-data [deps address opts]))
                  account-gateway/request-user-funding-history! (fn [{:keys [request-user-funding-history-data!]} address opts]
                                                                  (request-user-funding-history-data! address opts))
                  market-gateway/request-spot-meta! (fn [deps opts]
                                                      (record! :request-spot-meta [deps opts]))
                  api-compat/fetch-spot-meta! (fn [deps store* opts]
                                                (record! :fetch-spot-meta [deps store* opts]))
                  market-gateway/request-public-webdata2! (fn [deps opts]
                                                            (record! :request-public-webdata2 [deps opts]))
                  market-gateway/request-predicted-fundings! (fn [deps opts]
                                                               (record! :request-predicted-fundings [deps opts]))
                  api-compat/ensure-perp-dexs! (fn [deps store* opts]
                                                 ((:ensure-perp-dexs-data! deps) store* opts)
                                                 (record! :ensure-perp-dexs [deps store* opts]))
                  api-compat/ensure-spot-meta! (fn [deps store* opts]
                                                 ((:ensure-spot-meta-data! deps) store* opts)
                                                 (record! :ensure-spot-meta [deps store* opts]))
                  api-compat/fetch-asset-selector-markets! (fn [deps store* opts]
                                                             (record! :fetch-asset-selector-markets [deps store* opts]))
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
                  api-compat/fetch-spot-clearinghouse-state! (fn [deps store* address opts]
                                                               (record! :fetch-spot-clearinghouse-state [deps store* address opts]))
                  account-gateway/request-spot-clearinghouse-state! (fn [deps address opts]
                                                                       (record! :request-spot-clearinghouse-state [deps address opts]))
                  api-compat/fetch-user-abstraction! (fn [deps store* address opts]
                                                       (record! :fetch-user-abstraction [deps store* address opts]))
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
                                                         (record! :request-vault-webdata2 [deps vault-address opts]))
                  api-compat/fetch-clearinghouse-state! (fn [deps store* address dex opts]
                                                          (record! :fetch-clearinghouse-state [deps store* address dex opts]))
                  api-compat/fetch-perp-dex-clearinghouse-states! (fn [deps store* address dex-names opts]
                                                                     ((:fetch-clearinghouse-state! deps) store* address (first dex-names) opts)
                                                                     (record! :fetch-perp-dex-clearinghouse-states [deps store* address dex-names opts]))
                  platform/now-ms (fn []
                                    1700000000000)]
      (api/install-api-service! {:id :default-test-service})

      (is (= {:ok :make-api}
             (api/make-api {:runtime :test})))
      (is (= {:ok :request-asset-contexts}
             (api/request-asset-contexts!)))
      (is (= {:ok :request-asset-contexts}
             (api/request-asset-contexts! {:priority :high})))
      (is (= {:ok :fetch-asset-contexts}
             (api/fetch-asset-contexts! store)))
      (is (= {:ok :fetch-asset-contexts}
             (api/fetch-asset-contexts! store {:priority :high})))

      (is (= {:ok :request-meta-and-asset-ctxs}
             (api/request-meta-and-asset-ctxs! "dex-a")))
      (is (= {:ok :request-meta-and-asset-ctxs}
             (api/request-meta-and-asset-ctxs! "dex-a" {:priority :high})))
      (is (= {:ok :request-meta-and-asset-ctxs}
             (api/fetch-meta-and-asset-ctxs! "dex-a")))
      (is (= {:ok :request-meta-and-asset-ctxs}
             (api/fetch-meta-and-asset-ctxs! "dex-a" {:priority :high})))

      (is (= {:ok :request-perp-dexs}
             (api/request-perp-dexs!)))
      (is (= {:ok :request-perp-dexs}
             (api/request-perp-dexs! {:priority :high})))
      (is (= {:ok :fetch-perp-dexs}
             (api/fetch-perp-dexs! store)))
      (is (= {:ok :fetch-perp-dexs}
             (api/fetch-perp-dexs! store {:priority :high})))

      (is (= {:ok :request-candle-snapshot}
             (api/request-candle-snapshot! "BTC")))
      (is (= {:ok :request-candle-snapshot}
             (api/request-candle-snapshot! "BTC" :interval :1h :bars 50 :priority :low)))
      (is (= {:ok :fetch-candle-snapshot}
             (api/fetch-candle-snapshot! store)))
      (is (= {:ok :fetch-candle-snapshot}
             (api/fetch-candle-snapshot! store :interval :5m :bars 20 :priority :high)))

      (is (= {:ok :request-frontend-open-orders}
             (api/request-frontend-open-orders! "0xabc")))
      (is (= {:ok :request-frontend-open-orders}
             (api/request-frontend-open-orders! "0xabc" {:priority :high})))
      (is (= {:ok :request-frontend-open-orders}
             (api/request-frontend-open-orders! "0xabc" "dex-a" {:priority :high})))

      (is (= {:ok :request-user-fills}
             (api/request-user-fills! "0xabc")))
      (is (= {:ok :request-user-fills}
             (api/request-user-fills! "0xabc" {:priority :high})))
      (is (= {:ok :fetch-user-fills}
             (api/fetch-user-fills! store "0xabc")))
      (is (= {:ok :fetch-user-fills}
             (api/fetch-user-fills! store "0xabc" {:priority :high})))

      (is (= {:ok :fetch-historical-orders}
             (api/fetch-historical-orders! store "0xabc")))
      (is (= {:ok :fetch-historical-orders}
             (api/fetch-historical-orders! store "0xabc" {:priority :high})))
      (is (= {:ok :fetch-historical-orders}
             (api/request-historical-orders! "0xabc")))
      (is (= {:ok :fetch-historical-orders}
             (api/request-historical-orders! "0xabc" {:priority :high})))

      (is (= {:ok :request-user-funding-history-data}
             (api/fetch-user-funding-history! store "0xabc")))
      (is (= {:ok :request-user-funding-history-data}
             (api/fetch-user-funding-history! store "0xabc" {:priority :high})))
      (is (= {:ok :request-user-funding-history-data}
             (api/request-user-funding-history! "0xabc")))
      (is (= {:ok :request-user-funding-history-data}
             (api/request-user-funding-history! "0xabc" {:priority :high})))

      (is (= {:ok :request-vault-index}
             (api/request-vault-index!)))
      (is (= {:ok :request-vault-index}
             (api/request-vault-index! {:fetch-opts {:cache "no-store"}})))
      (is (= {:ok :request-vault-summaries}
             (api/request-vault-summaries!)))
      (is (= {:ok :request-vault-summaries}
             (api/request-vault-summaries! {:priority :high})))
      (is (= {:ok :request-merged-vault-index}
             (api/request-merged-vault-index!)))
      (is (= {:ok :request-merged-vault-index}
             (api/request-merged-vault-index! {:priority :low})))
      (is (= {:ok :request-user-vault-equities}
             (api/request-user-vault-equities! "0xabc")))
      (is (= {:ok :request-user-vault-equities}
             (api/request-user-vault-equities! "0xabc" {:priority :high})))
      (is (= {:ok :request-vault-details}
             (api/request-vault-details! "0xvault")))
      (is (= {:ok :request-vault-details}
             (api/request-vault-details! "0xvault" {:user "0xabc"})))
      (is (= {:ok :request-vault-webdata2}
             (api/request-vault-webdata2! "0xvault")))
      (is (= {:ok :request-vault-webdata2}
             (api/request-vault-webdata2! "0xvault" {:priority :high})))

      (is (= {:ok :request-spot-meta}
             (api/request-spot-meta!)))
      (is (= {:ok :request-spot-meta}
             (api/request-spot-meta! {:priority :high})))
      (is (= {:ok :fetch-spot-meta}
             (api/fetch-spot-meta! store)))
      (is (= {:ok :fetch-spot-meta}
             (api/fetch-spot-meta! store {:priority :high})))
      (is (= {:ok :request-spot-meta}
             (api/fetch-spot-meta-raw!)))
      (is (= {:ok :request-spot-meta}
             (api/fetch-spot-meta-raw! {:priority :high})))

      (is (= {:ok :request-public-webdata2}
             (api/request-public-webdata2!)))
      (is (= {:ok :request-public-webdata2}
             (api/request-public-webdata2! {:priority :high})))
      (is (= {:ok :request-public-webdata2}
             (api/fetch-public-webdata2!)))
      (is (= {:ok :request-public-webdata2}
             (api/fetch-public-webdata2! {:priority :high})))
      (is (= {:ok :request-predicted-fundings}
             (api/request-predicted-fundings!)))
      (is (= {:ok :request-predicted-fundings}
             (api/request-predicted-fundings! {:priority :high})))

      (is (= {:ok :ensure-perp-dexs}
             (api/ensure-perp-dexs! store)))
      (is (= {:ok :ensure-perp-dexs}
             (api/ensure-perp-dexs! store {:priority :high})))
      (is (= {:ok :ensure-perp-dexs-data}
             (api/ensure-perp-dexs-data! store)))
      (is (= {:ok :ensure-perp-dexs-data}
             (api/ensure-perp-dexs-data! store {:priority :high})))
      (is (= {:ok :ensure-spot-meta-data}
             (api/ensure-spot-meta-data! store)))
      (is (= {:ok :ensure-spot-meta-data}
             (api/ensure-spot-meta-data! store {:priority :high})))
      (is (= {:ok :ensure-spot-meta}
             (api/ensure-spot-meta! store)))
      (is (= {:ok :ensure-spot-meta}
             (api/ensure-spot-meta! store {:priority :high})))
      (is (= {:ok :ensure-public-webdata2}
             (api/ensure-public-webdata2!)))
      (is (= {:ok :ensure-public-webdata2}
             (api/ensure-public-webdata2! {:force? true})))

      (is (= {:ok :fetch-asset-selector-markets}
             (api/fetch-asset-selector-markets! store)))
      (is (= {:ok :fetch-asset-selector-markets}
             (api/fetch-asset-selector-markets! store {:phase :bootstrap})))
      (is (= {:ok :request-asset-selector-markets}
             (api/request-asset-selector-markets! store)))
      (is (= {:ok :request-asset-selector-markets}
             (api/request-asset-selector-markets! store {:phase :bootstrap})))

      (is (= {:ok :fetch-spot-clearinghouse-state}
             (api/fetch-spot-clearinghouse-state! store "0xabc")))
      (is (= {:ok :fetch-spot-clearinghouse-state}
             (api/fetch-spot-clearinghouse-state! store "0xabc" {:priority :high})))
      (is (= {:ok :request-spot-clearinghouse-state}
             (api/request-spot-clearinghouse-state! "0xabc")))
      (is (= {:ok :request-spot-clearinghouse-state}
             (api/request-spot-clearinghouse-state! "0xabc" {:priority :high})))

      (is (= {:ok :fetch-user-abstraction}
             (api/fetch-user-abstraction! store "0xabc")))
      (is (= {:ok :fetch-user-abstraction}
             (api/fetch-user-abstraction! store "0xabc" {:priority :high})))
      (is (= {:ok :request-user-abstraction}
             (api/request-user-abstraction! "0xabc")))
      (is (= {:ok :request-user-abstraction}
             (api/request-user-abstraction! "0xabc" {:priority :high})))

      (is (= {:ok :request-clearinghouse-state}
             (api/request-clearinghouse-state! "0xabc" "dex-a")))
      (is (= {:ok :request-clearinghouse-state}
             (api/request-clearinghouse-state! "0xabc" "dex-a" {:priority :high})))
      (is (= {:ok :fetch-clearinghouse-state}
             (api/fetch-clearinghouse-state! store "0xabc" "dex-a")))
      (is (= {:ok :fetch-clearinghouse-state}
             (api/fetch-clearinghouse-state! store "0xabc" "dex-a" {:priority :high})))
      (is (= {:ok :fetch-perp-dex-clearinghouse-states}
             (api/fetch-perp-dex-clearinghouse-states! store "0xabc" ["dex-a" "dex-b"])))
      (is (= {:ok :fetch-perp-dex-clearinghouse-states}
             (api/fetch-perp-dex-clearinghouse-states! store "0xabc" ["dex-a" "dex-b"] {:priority :high})))

      (is (= {:ok :get-request-stats}
             (api/get-request-stats)))
      (is (= {:ok :reset-service}
             (api/reset-request-runtime!)))

      (is (some #(= :request-asset-contexts (first %)) @calls))
      (is (some #(= :request-frontend-open-orders (first %)) @calls))
      (is (some #(= :request-predicted-fundings (first %)) @calls))
      (is (some #(= :build-market-state (first %)) @calls))
      (is (some #(= :fetch-perp-dex-clearinghouse-states (first %)) @calls)))))
