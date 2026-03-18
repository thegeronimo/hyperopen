(ns hyperopen.websocket.gateway.vaults-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.gateway.vaults :as vault-gateway]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]))

(defn- ok-response
  [payload]
  #js {:ok true
       :status 200
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(deftest request-vault-index-uses-custom-fetch-and-url-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url init])
                     (js/Promise.resolve
                      (ok-response
                       [{:summary {:name "Vault One"
                                   :vaultAddress "0xA1"
                                   :leader "0xB1"
                                   :tvl "10.0"
                                   :createTimeMillis 100}}])))]
      (-> (vault-gateway/request-vault-index! {:fetch-fn fetch-fn
                                               :vault-index-url "https://vaults.test/index"}
                                              {:fetch-opts {:cache "no-store"}})
          (.then (fn [rows]
                   (let [[url init] (first @calls)]
                     (is (= "https://vaults.test/index" url))
                     (is (= {:method "GET"
                             :cache "no-store"}
                            (js->clj init :keywordize-keys true))))
                   (is (= [{:name "Vault One"
                            :vault-address "0xa1"
                            :leader "0xb1"
                            :tvl 10
                            :tvl-raw "10.0"
                            :is-closed? false
                            :relationship {:type :normal}
                            :create-time-ms 100
                            :apr 0
                            :apr-raw nil
                            :snapshot-preview-by-key {}}]
                          rows))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-merged-vault-index-uses-composed-requests-test
  (async done
    (let [index-calls (atom [])
          summary-calls (atom [])]
      (-> (vault-gateway/request-merged-vault-index!
           {:request-vault-index! (fn [opts]
                                    (swap! index-calls conj opts)
                                    (js/Promise.resolve
                                     [{:summary {:vaultAddress "0x1"
                                                 :name "Index One"
                                                 :createTimeMillis 100}}]))
            :request-vault-summaries! (fn [opts]
                                        (swap! summary-calls conj opts)
                                        (js/Promise.resolve
                                         [{:summary {:vaultAddress "0x2"
                                                     :name "Summary Two"
                                                     :createTimeMillis 200}}]))}
           {:priority :low})
          (.then (fn [merged]
                   (is (= [{:priority :low}] @index-calls))
                   (is (= [{:priority :low}] @summary-calls))
                   (is (= ["0x1" "0x2"]
                          (mapv :vault-address merged)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest vault-gateway-wrapper-delegation-coverage-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      (fn [body _opts]
                        (case (get body "type")
                          "vaultSummaries" [{:summary {:vaultAddress "0xA"}}]
                          "userVaultEquities" [{:vaultAddress "0xA"
                                                :equity "5.0"}]
                          "vaultDetails" {:name "Vault"
                                          :vaultAddress "0xA"
                                          :portfolio []}
                          "webData2" {:fills []}
                          nil)))]
      (-> (js/Promise.all
           #js [(vault-gateway/request-vault-summaries! {:post-info! post-info!}
                                                        {:priority :high})
                (vault-gateway/request-user-vault-equities! {:post-info! post-info!}
                                                            "0xabc"
                                                            {:priority :high})
                (vault-gateway/request-vault-details! {:post-info! post-info!}
                                                      "0xvault"
                                                      {:user "0xabc"})
                (vault-gateway/request-vault-webdata2! {:post-info! post-info!}
                                                       "0xvault"
                                                       {:priority :high})])
          (.then (fn [results]
                   (let [[summaries equities details webdata] (vec (array-seq results))]
                     (is (= ["0xa"] (mapv :vault-address summaries)))
                     (is (= ["0xa"] (mapv :vault-address equities)))
                     (is (= "0xa" (:vault-address details)))
                     (is (= {:fills []} webdata))
                     (is (= ["vaultSummaries"
                             "userVaultEquities"
                             "vaultDetails"
                             "webData2"]
                            (mapv (comp #(get % "type") first) @calls))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-merged-vault-index-default-arity-uses-empty-opts-test
  (async done
    (let [index-calls (atom [])
          summary-calls (atom [])]
      (-> (vault-gateway/request-merged-vault-index!
           {:request-vault-index! (fn [opts]
                                    (swap! index-calls conj opts)
                                    (js/Promise.resolve []))
            :request-vault-summaries! (fn [opts]
                                        (swap! summary-calls conj opts)
                                        (js/Promise.resolve []))})
          (.then (fn [merged]
                   (is (= [] merged))
                   (is (= [{}] @index-calls))
                   (is (= [{}] @summary-calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
