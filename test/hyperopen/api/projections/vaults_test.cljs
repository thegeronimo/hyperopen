(ns hyperopen.api.projections.vaults-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.projections.vaults :as vaults]))

(deftest vault-list-projections-update-loading-success-and-error-paths-test
  (let [state {:vaults {:index-rows []
                        :recent-summaries []
                        :merged-index-rows []
                        :index-cache {:hydrated? false
                                      :saved-at-ms nil
                                      :etag nil
                                      :last-modified nil
                                      :live-response-status nil}
                        :loading {:index? false
                                  :summaries? false}
                        :errors {:index "stale-index"
                                 :summaries "stale-summaries"}
                        :loaded-at-ms {:index nil
                                       :summaries nil}}}
        index-loading (vaults/begin-vault-index-load state)
        index-success (vaults/apply-vault-index-success
                       index-loading
                       {:status :ok
                        :rows [{:vault-address "0x1"
                                :name "Index One"}
                               {:vault-address "0x2"
                                :name "Index Two"}]
                        :etag "\"etag-1\""
                        :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"})
        summaries-loading (vaults/begin-vault-summaries-load index-success)
        summaries-success (vaults/apply-vault-summaries-success
                           summaries-loading
                           [{:vault-address "0x2"
                             :name "Summary Two"}
                            {:vault-address "0x3"
                             :name "Summary Three"}])
        index-error (vaults/apply-vault-index-error index-loading (js/Error. "index-fail"))
        summaries-error (vaults/apply-vault-summaries-error summaries-loading "summary-fail")]
    (is (= true (get-in index-loading [:vaults :loading :index?])))
    (is (= true (get-in index-loading [:vaults-ui :list-loading?])))
    (is (= nil (get-in index-loading [:vaults :errors :index])))
    (is (= ["0x1" "0x2"] (mapv :vault-address (get-in index-success [:vaults :index-rows]))))
    (is (= ["0x1" "0x2"] (mapv :vault-address (get-in index-success [:vaults :merged-index-rows]))))
    (is (= false (get-in index-success [:vaults :loading :index?])))
    (is (= {:hydrated? false
            :saved-at-ms (get-in index-success [:vaults :loaded-at-ms :index])
            :etag "\"etag-1\""
            :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"
            :live-response-status :ok}
           (get-in index-success [:vaults :index-cache])))
    (is (number? (get-in index-success [:vaults :loaded-at-ms :index])))
    (is (= true (get-in summaries-loading [:vaults :loading :summaries?])))
    (is (= ["0x1" "0x2" "0x3"]
           (mapv :vault-address (get-in summaries-success [:vaults :merged-index-rows]))))
    (is (= "Summary Two"
           (:name (second (get-in summaries-success [:vaults :merged-index-rows])))))
    (is (= false (get-in summaries-success [:vaults :loading :summaries?])))
    (is (= false (get-in summaries-success [:vaults-ui :list-loading?])))
    (is (number? (get-in summaries-success [:vaults :loaded-at-ms :summaries])))
    (is (= "Error: index-fail" (get-in index-error [:vaults :errors :index])))
    (is (= false (get-in index-error [:vaults :loading :index?])))
    (is (= "summary-fail" (get-in summaries-error [:vaults :errors :summaries])))
    (is (= false (get-in summaries-error [:vaults :loading :summaries?])))
    (is (= false (get-in summaries-error [:vaults-ui :list-loading?])))))

(deftest vault-index-cache-hydration-and-not-modified-keep-baseline-rows-test
  (let [state {:vaults {:index-rows []
                        :recent-summaries [{:vault-address "0xsummary"
                                            :name "Summary"}]
                        :merged-index-rows []
                        :index-cache {:hydrated? false
                                      :saved-at-ms nil
                                      :etag nil
                                      :last-modified nil}
                        :loading {:index? true
                                  :summaries? false}
                        :errors {:index nil
                                 :summaries nil}
                        :loaded-at-ms {:index nil
                                       :summaries nil}}}
        hydrated (vaults/apply-vault-index-cache-hydration
                  state
                  {:rows [{:vault-address "0xcache"
                           :name "Cached"}]
                   :saved-at-ms 1700000000000
                   :etag "\"etag-cache\""
                   :last-modified "Thu, 20 Mar 2026 11:00:00 GMT"})
        not-modified (vaults/apply-vault-index-success
                      hydrated
                      {:status :not-modified
                       :etag "\"etag-cache-2\""
                       :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"})]
    (is (= ["0xcache"] (mapv :vault-address (get-in hydrated [:vaults :index-rows]))))
    (is (= ["0xcache" "0xsummary"]
           (mapv :vault-address (get-in hydrated [:vaults :merged-index-rows]))))
    (is (= {:hydrated? true
            :saved-at-ms 1700000000000
            :etag "\"etag-cache\""
            :last-modified "Thu, 20 Mar 2026 11:00:00 GMT"
            :live-response-status nil}
           (get-in hydrated [:vaults :index-cache])))
    (is (= ["0xcache"] (mapv :vault-address (get-in not-modified [:vaults :index-rows]))))
    (is (= ["0xcache" "0xsummary"]
           (mapv :vault-address (get-in not-modified [:vaults :merged-index-rows]))))
    (is (true? (get-in not-modified [:vaults :index-cache :hydrated?])))
    (is (= "\"etag-cache-2\"" (get-in not-modified [:vaults :index-cache :etag])))
    (is (= "Thu, 20 Mar 2026 12:00:00 GMT"
           (get-in not-modified [:vaults :index-cache :last-modified])))
    (is (number? (get-in not-modified [:vaults :index-cache :saved-at-ms])))
    (is (= :not-modified (get-in not-modified [:vaults :index-cache :live-response-status])))
    (is (= false (get-in not-modified [:vaults :loading :index?])))))

(deftest vault-index-cache-hydration-does-not-overwrite-fresher-live-rows-test
  (let [live-success (vaults/apply-vault-index-success
                      {:vaults {:index-rows []
                                :recent-summaries []
                                :merged-index-rows []
                                :index-cache {:hydrated? false
                                              :saved-at-ms nil
                                              :etag nil
                                              :last-modified nil
                                              :live-response-status nil}
                                :loading {:index? true
                                          :summaries? false}
                                :errors {:index nil
                                         :summaries nil}
                                :loaded-at-ms {:index nil
                                               :summaries nil}}}
                      {:status :ok
                       :rows [{:vault-address "0xlive"
                               :name "Live"}]
                       :etag "\"etag-live\""
                       :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"})
        late-cache (vaults/apply-vault-index-cache-hydration
                    live-success
                    {:rows [{:vault-address "0xcache"
                             :name "Cached"}]
                     :saved-at-ms 1700000000000
                     :etag "\"etag-cache\""
                     :last-modified "Thu, 20 Mar 2026 11:00:00 GMT"})]
    (is (= ["0xlive"] (mapv :vault-address (get-in late-cache [:vaults :index-rows]))))
    (is (= ["0xlive"] (mapv :vault-address (get-in late-cache [:vaults :merged-index-rows]))))
    (is (= "\"etag-live\"" (get-in late-cache [:vaults :index-cache :etag])))
    (is (= "Thu, 20 Mar 2026 12:00:00 GMT"
           (get-in late-cache [:vaults :index-cache :last-modified])))
    (is (= :ok (get-in late-cache [:vaults :index-cache :live-response-status])))))

(deftest vault-index-error-preserves-existing-baseline-rows-test
  (let [state {:vaults {:index-rows [{:vault-address "0xcache"}]
                        :merged-index-rows [{:vault-address "0xcache"}
                                            {:vault-address "0xsummary"}]
                        :index-cache {:hydrated? true
                                      :saved-at-ms 1700000000000
                                      :etag "\"etag-cache\""
                                      :last-modified "Thu, 20 Mar 2026 10:00:00 GMT"
                                      :live-response-status nil}
                        :loading {:index? true}
                        :errors {:index nil}}}
        failed (vaults/apply-vault-index-error state (js/Error. "index-fail"))]
    (is (= ["0xcache"] (mapv :vault-address (get-in failed [:vaults :index-rows]))))
    (is (= ["0xcache" "0xsummary"] (mapv :vault-address (get-in failed [:vaults :merged-index-rows]))))
    (is (= false (get-in failed [:vaults :loading :index?])))
    (is (= "Error: index-fail" (get-in failed [:vaults :errors :index])))
    (is (= :error (get-in failed [:vaults :index-cache :live-response-status])))))

(deftest vault-index-cache-hydration-after-live-error-restores-cache-without-clearing-live-error-test
  (let [state {:vaults {:index-rows [{:vault-address "0xold"}]
                        :recent-summaries [{:vault-address "0xsummary"
                                            :name "Summary"}]
                        :merged-index-rows [{:vault-address "0xold"}]
                        :index-cache {:hydrated? true
                                      :saved-at-ms 1
                                      :etag "\"old\""
                                      :last-modified "old"
                                      :live-response-status nil}
                        :loading {:index? true
                                  :summaries? false}
                        :errors {:index nil}}}
        failed (vaults/apply-vault-index-error state (js/Error. "index-fail"))
        hydrated (vaults/apply-vault-index-cache-hydration
                  failed
                  {:rows [{:vault-address "0xcache"
                           :name "Cached"}]
                   :saved-at-ms 1700000000000
                   :etag "\"etag-cache\""
                   :last-modified "Thu, 20 Mar 2026 11:00:00 GMT"})]
    (is (= ["0xcache"] (mapv :vault-address (get-in hydrated [:vaults :index-rows]))))
    (is (= ["0xcache" "0xsummary"]
           (mapv :vault-address (get-in hydrated [:vaults :merged-index-rows]))))
    (is (= "Error: index-fail" (get-in hydrated [:vaults :errors :index])))
    (is (= :error (get-in hydrated [:vaults :index-cache :live-response-status])))
    (is (= "\"etag-cache\"" (get-in hydrated [:vaults :index-cache :etag])))
    (is (= 1700000000000 (get-in hydrated [:vaults :index-cache :saved-at-ms])))))

(deftest vault-index-and-summary-merge-dedupes-mixed-case-addresses-with-summary-wins-test
  (let [state {:vaults {:index-rows [{:vault-address "0xABC"
                                      :name "Index"}]
                        :recent-summaries []
                        :merged-index-rows []
                        :index-cache {}
                        :loading {:summaries? true}
                        :errors {:summaries nil}}}
        next-state (vaults/apply-vault-summaries-success
                    state
                    [{:vault-address "0xabc"
                      :name "Summary"
                      :tvl 100}])]
    (is (= [{:vault-address "0xabc"
             :name "Summary"
             :tvl 100}]
           (get-in next-state [:vaults :merged-index-rows])))))

(deftest vault-equities-and-detail-projections-track-per-address-state-test
  (let [state {:vaults {:user-equities []
                        :user-equity-by-address {}
                        :details-by-address {}
                        :viewer-details-by-address {}
                        :webdata-by-vault {}
                        :loading {:user-equities? false
                                  :details-by-address {}
                                  :webdata-by-vault {}}
                        :errors {:user-equities nil
                                 :details-by-address {}
                                 :webdata-by-vault {}}
                        :loaded-at-ms {:user-equities nil
                                       :details-by-address {}
                                       :webdata-by-vault {}}}}
        equities-loading (vaults/begin-user-vault-equities-load state)
        equities-success (vaults/apply-user-vault-equities-success
                          equities-loading
                          [{:vault-address "0xA"
                            :equity 10}
                           {:vault-address "0xB"
                            :equity 20}])
        equities-error (vaults/apply-user-vault-equities-error equities-loading (js/Error. "equity-fail"))
        details-loading (vaults/begin-vault-details-load state "0xA" "0xViewer")
        details-success (vaults/apply-vault-details-success details-loading
                                                            "0xA"
                                                            "0xViewer"
                                                            {:name "Vault A"
                                                             :follower-state {:vault-equity 10}
                                                             :max-withdrawable 5})
        details-error (vaults/apply-vault-details-error details-loading "0xA" "details-fail")
        webdata-loading (vaults/begin-vault-webdata2-load state "0xA")
        webdata-success (vaults/apply-vault-webdata2-success webdata-loading "0xA" {:fills [1]})
        webdata-error (vaults/apply-vault-webdata2-error webdata-loading "0xA" (js/Error. "webdata-fail"))]
    (is (= true (get-in equities-loading [:vaults :loading :user-equities?])))
    (is (= [{:vault-address "0xA" :equity 10}
            {:vault-address "0xB" :equity 20}]
           (get-in equities-success [:vaults :user-equities])))
    (is (= {:vault-address "0xA" :equity 10}
           (get-in equities-success [:vaults :user-equity-by-address "0xa"])))
    (is (= false (get-in equities-success [:vaults :loading :user-equities?])))
    (is (number? (get-in equities-success [:vaults :loaded-at-ms :user-equities])))
    (is (= "Error: equity-fail" (get-in equities-error [:vaults :errors :user-equities])))
    (is (= true (get-in details-loading [:vaults :loading :details-by-address "0xa"])))
    (is (= true (get-in details-loading [:vaults-ui :detail-loading?])))
    (is (= {:name "Vault A"}
           (get-in details-success [:vaults :details-by-address "0xa"])))
    (is (= {:name "Vault A"
            :follower-state {:vault-equity 10}
            :max-withdrawable 5}
           (get-in details-success [:vaults :viewer-details-by-address "0xa" "0xviewer"])))
    (is (= false (get-in details-success [:vaults :loading :details-by-address "0xa"])))
    (is (= false (get-in details-success [:vaults-ui :detail-loading?])))
    (is (number? (get-in details-success [:vaults :loaded-at-ms :details-by-address "0xa"])))
    (is (= "details-fail" (get-in details-error [:vaults :errors :details-by-address "0xa"])))
    (is (= true (get-in webdata-loading [:vaults :loading :webdata-by-vault "0xa"])))
    (is (= true (get-in webdata-loading [:vaults-ui :detail-loading?])))
    (is (= {:fills [1]} (get-in webdata-success [:vaults :webdata-by-vault "0xa"])))
    (is (= false (get-in webdata-success [:vaults :loading :webdata-by-vault "0xa"])))
    (is (= false (get-in webdata-success [:vaults-ui :detail-loading?])))
    (is (number? (get-in webdata-success [:vaults :loaded-at-ms :webdata-by-vault "0xa"])))
    (is (= "Error: webdata-fail"
           (get-in webdata-error [:vaults :errors :webdata-by-vault "0xa"])))
    (is (= false (get-in webdata-error [:vaults-ui :detail-loading?])))))

(deftest vault-activity-history-projections-track-vault-scoped-loading-and-results-test
  (let [state {:vaults {:fills-by-vault {}
                        :funding-history-by-vault {}
                        :order-history-by-vault {}
                        :ledger-updates-by-vault {}
                        :loading {:fills-by-vault {}
                                  :funding-history-by-vault {}
                                  :order-history-by-vault {}
                                  :ledger-updates-by-vault {}}
                        :errors {:fills-by-vault {}
                                 :funding-history-by-vault {}
                                 :order-history-by-vault {}
                                 :ledger-updates-by-vault {}}
                        :loaded-at-ms {:fills-by-vault {}
                                       :funding-history-by-vault {}
                                       :order-history-by-vault {}
                                       :ledger-updates-by-vault {}}}}
        fills-loading (vaults/begin-vault-fills-load state "0xA")
        fills-success (vaults/apply-vault-fills-success fills-loading "0xA" [{:coin "BTC"}])
        fills-error (vaults/apply-vault-fills-error fills-loading "0xA" (js/Error. "fills-fail"))
        funding-loading (vaults/begin-vault-funding-history-load state "0xA")
        funding-success (vaults/apply-vault-funding-history-success funding-loading "0xA" [{:coin "ETH"}])
        funding-error (vaults/apply-vault-funding-history-error funding-loading "0xA" "funding-fail")
        order-loading (vaults/begin-vault-order-history-load state "0xA")
        order-success (vaults/apply-vault-order-history-success order-loading "0xA" [{:status "filled"}])
        order-error (vaults/apply-vault-order-history-error order-loading "0xA" (js/Error. "orders-fail"))
        ledger-loading (vaults/begin-vault-ledger-updates-load state "0xA")
        ledger-success (vaults/apply-vault-ledger-updates-success ledger-loading "0xA" [{:delta {:type "vaultDeposit"}}])
        ledger-error (vaults/apply-vault-ledger-updates-error ledger-loading "0xA" "ledger-fail")]
    (is (= true (get-in fills-loading [:vaults :loading :fills-by-vault "0xa"])))
    (is (= [{:coin "BTC"}] (get-in fills-success [:vaults :fills-by-vault "0xa"])))
    (is (number? (get-in fills-success [:vaults :loaded-at-ms :fills-by-vault "0xa"])))
    (is (= "Error: fills-fail" (get-in fills-error [:vaults :errors :fills-by-vault "0xa"])))
    (is (= true (get-in funding-loading [:vaults :loading :funding-history-by-vault "0xa"])))
    (is (= [{:coin "ETH"}] (get-in funding-success [:vaults :funding-history-by-vault "0xa"])))
    (is (number? (get-in funding-success [:vaults :loaded-at-ms :funding-history-by-vault "0xa"])))
    (is (= "funding-fail" (get-in funding-error [:vaults :errors :funding-history-by-vault "0xa"])))
    (is (= true (get-in order-loading [:vaults :loading :order-history-by-vault "0xa"])))
    (is (= [{:status "filled"}] (get-in order-success [:vaults :order-history-by-vault "0xa"])))
    (is (number? (get-in order-success [:vaults :loaded-at-ms :order-history-by-vault "0xa"])))
    (is (= "Error: orders-fail" (get-in order-error [:vaults :errors :order-history-by-vault "0xa"])))
    (is (= true (get-in ledger-loading [:vaults :loading :ledger-updates-by-vault "0xa"])))
    (is (= [{:delta {:type "vaultDeposit"}}] (get-in ledger-success [:vaults :ledger-updates-by-vault "0xa"])))
    (is (number? (get-in ledger-success [:vaults :loaded-at-ms :ledger-updates-by-vault "0xa"])))
    (is (= "ledger-fail" (get-in ledger-error [:vaults :errors :ledger-updates-by-vault "0xa"])))))

(deftest vault-viewer-ledger-projections-key-by-vault-and-viewer-test
  (let [loading (vaults/begin-vault-viewer-ledger-load {} "0xA" "0xB")
        success (vaults/apply-vault-viewer-ledger-success loading "0xA" "0xB" [{:time 1}])
        error (vaults/apply-vault-viewer-ledger-error loading "0xA" "0xB" "ledger-fail")]
    (is (= true (get-in loading [:vaults :loading :viewer-ledger-by-address "0xa" "0xb"])))
    (is (nil? (get-in loading [:vaults :errors :viewer-ledger-by-address "0xa" "0xb"])))
    (is (= [{:time 1}] (get-in success [:vaults :viewer-ledger-by-address "0xa" "0xb"])))
    (is (= false (get-in success [:vaults :loading :viewer-ledger-by-address "0xa" "0xb"])))
    (is (= false (get-in error [:vaults :loading :viewer-ledger-by-address "0xa" "0xb"])))
    (is (= "ledger-fail" (get-in error [:vaults :errors :viewer-ledger-by-address "0xa" "0xb"])))
    (is (= {} (vaults/begin-vault-viewer-ledger-load {} "0xA" nil)))))
