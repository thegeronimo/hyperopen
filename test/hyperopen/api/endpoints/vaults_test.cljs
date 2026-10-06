(ns hyperopen.api.endpoints.vaults-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.vaults :as vaults]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]))

(defn- ok-response
  [payload]
  #js {:ok true
       :status 200
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(defn- response-headers
  [headers]
  #js {:get (fn [header-name]
              (get headers header-name))})

(defn- ok-response-with-headers
  [payload headers]
  #js {:ok true
       :status 200
       :headers (response-headers headers)
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(defn- not-modified-response
  [headers]
  #js {:ok false
       :status 304
       :headers (response-headers headers)})


(deftest request-vault-index-response-normalizes-shape-and-preserves-validators-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url init])
                     (js/Promise.resolve
                      (ok-response-with-headers
                       [{:apr "0.25"
                         :summary {:name "Alpha Vault"
                                   :vaultAddress "0xABc"
                                   :leader "0xDEF"
                                   :tvl "12.5"
                                   :isClosed "false"
                                   :relationship {:type "parent"
                                                  :data {:childAddresses ["0xC1"]}}
                                   :createTimeMillis "1700"}}]
                       {"ETag" "\"etag-1\""
                        "Last-Modified" "Thu, 20 Mar 2026 12:00:00 GMT"})))]
      (-> (vaults/request-vault-index-response! fetch-fn
                                                "https://vaults.test/index"
                                                {:fetch-opts {:headers {"If-None-Match" "\"etag-0\""}}})
          (.then (fn [response]
                   (let [[called-url init] (first @calls)
                         init* (js->clj init)]
                     (is (= "https://vaults.test/index" called-url))
                     (is (= "GET" (get init* "method")))
                     (is (= "\"etag-0\"" (get-in init* ["headers" "If-None-Match"]))))
                   (is (= :ok (:status response)))
                   (is (= "\"etag-1\"" (:etag response)))
                   (is (= "Thu, 20 Mar 2026 12:00:00 GMT" (:last-modified response)))
                   (is (= [{:name "Alpha Vault"
                            :vault-address "0xabc"
                            :leader "0xdef"
                            :tvl 12.5
                            :tvl-raw "12.5"
                            :is-closed? false
                            :relationship {:type :parent
                                           :child-addresses ["0xc1"]}
                            :create-time-ms 1700
                            :apr 0.25
                            :apr-raw "0.25"
                            :snapshot-preview-by-key {}}]
                          (:rows response)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-index-response-handles-not-modified-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve
                      (not-modified-response
                       {"ETag" "\"etag-2\""
                        "Last-Modified" "Thu, 20 Mar 2026 13:00:00 GMT"})))]
      (-> (vaults/request-vault-index-response! fetch-fn "https://vaults.test/index" {})
          (.then (fn [response]
                   (is (= {:status :not-modified
                           :rows []
                           :etag "\"etag-2\""
                           :last-modified "Thu, 20 Mar 2026 13:00:00 GMT"}
                          response))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-index-response-avoids-cors-preflight-validator-headers-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url init])
                     (js/Promise.resolve
                      (ok-response [])))]
      (with-redefs [hyperopen.api.endpoints.vaults/cross-origin-browser-request? (fn [_]
                                                                                   true)]
        (-> (vaults/request-vault-index-response! fetch-fn
                                                  "https://vaults.test/index"
                                                  {:fetch-opts {:headers {"If-None-Match" "\"etag-0\""
                                                                          "If-Modified-Since" "Thu, 20 Mar 2026 12:00:00 GMT"
                                                                          "X-Test" "kept"}}})
            (.then (fn [response]
                     (let [[called-url init] (first @calls)
                           init* (js->clj init)]
                       (is (= "https://vaults.test/index" called-url))
                       (is (= "GET" (get init* "method")))
                       (is (= "no-cache" (get init* "cache")))
                       (is (= {"X-Test" "kept"}
                              (get init* "headers"))))
                     (is (= {:status :ok
                             :rows []
                             :etag nil
                             :last-modified nil}
                            response))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest request-vault-index-normalizes-summary-shape-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url init])
                     (js/Promise.resolve
                      (ok-response
                       [{:apr "0.25"
                         :pnls [["day" ["1.5" "2.5"]]
                                ["3M" ["4.5"]]
                                ["1Y" ["5.5"]]
                                ["allTime" ["3.5"]]]
                         :summary {:name "Alpha Vault"
                                   :vaultAddress "0xABc"
                                   :leader "0xDEF"
                                   :tvl "12.5"
                                   :isClosed "false"
                                   :relationship {:type "parent"
                                                  :data {:childAddresses ["0xC1" "  "]}}
                                   :createTimeMillis "1700"}}
                        {:summary {:vaultAddress " "}}])))]
      (-> (vaults/request-vault-index! fetch-fn "https://vaults.test/index" {:fetch-opts {:cache "no-store"}})
          (.then (fn [rows]
                   (let [[called-url init] (first @calls)]
                     (is (= "https://vaults.test/index" called-url))
                     (is (= {:method "GET"
                             :cache "no-store"}
                            (js->clj init :keywordize-keys true))))
                   (is (= 1 (count rows)))
                   (let [row (first rows)]
                     (is (= "0xabc" (:vault-address row)))
                     (is (= "0xdef" (:leader row)))
                     (is (= 12.5 (:tvl row)))
                     (is (= 0.25 (:apr row)))
                     (is (= {:type :parent
                             :child-addresses ["0xc1"]}
                            (:relationship row)))
                     (is (= {:day {:series [1.5 2.5]
                                   :last-value 2.5}
                             :all-time {:series [3.5]
                                        :last-value 3.5}}
                            (:snapshot-preview-by-key row))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-index-rejects-non-ok-response-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve #js {:ok false
                                              :status 503}))]
      (-> (vaults/request-vault-index! fetch-fn "https://vaults.test/index" {})
          (.then (fn [_]
                   (is false "Expected non-ok response to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= 503 (aget err "status")))
                    (done)))))))


(deftest request-vault-summaries-builds-body-and-normalizes-rows-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      [{:summary {:name "Summary Vault"
                                  :vaultAddress "0xA1"
                                  :leader "0xB2"
                                  :tvl "9.0"
                                  :createTimeMillis 22}}])]
      (-> (vaults/request-vault-summaries! post-info! {:priority :low})
          (.then (fn [rows]
                   (is (= {"type" "vaultSummaries"}
                          (ffirst @calls)))
                   (is (= {:priority :low
                           :dedupe-key :vault-summaries
                           :cache-ttl-ms 15000}
                          (second (first @calls))))
                     (is (= [{:name "Summary Vault"
                              :vault-address "0xa1"
                              :leader "0xb2"
                              :tvl 9
                              :tvl-raw "9.0"
                              :is-closed? false
                              :relationship {:type :normal}
                              :create-time-ms 22
                              :apr 0
                              :apr-raw nil
                              :snapshot-preview-by-key {}}]
                            rows))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-vault-equities-short-circuits-when-address-missing-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        []))]
      (-> (vaults/request-user-vault-equities! post-info! nil {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-vault-equities-normalizes-rows-and-dedupe-key-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      [{:vaultAddress "0xA1"
                        :equity "120.5"
                        :lockedUntilTimestamp "1700"}])]
      (-> (vaults/request-user-vault-equities! post-info! "0xAbC" {:priority :low})
          (.then (fn [rows]
                   (is (= {"type" "userVaultEquities"
                           "user" "0xabc"}
                          (ffirst @calls)))
                   (is (= {:priority :low
                           :dedupe-key [:user-vault-equities "0xabc"]
                           :cache-ttl-ms 5000}
                          (second (first @calls))))
                   (is (= [{:vault-address "0xa1"
                            :equity 120.5
                            :equity-raw "120.5"
                            :locked-until-ms 1700}]
                          rows))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-user-vault-equities-opt-in-replaces-the-cache-only-for-forced-refresh-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls [])]
      (-> (vaults/request-user-vault-equities! post-info!
                                                "0xAbC"
                                                {:priority :high
                                                 :force-refresh? true})
          (.then (fn [_]
                   (is (= [{"type" "userVaultEquities"
                            "user" "0xabc"}
                           {:priority :high
                            :force-refresh? true
                            :replace-response-cache-on-force? true
                            :dedupe-key [:user-vault-equities "0xabc"]
                            :cache-ttl-ms 5000}]
                          (first @calls)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest user-vault-equity-normalization-preserves-invalid-raw-values-and-rejects-invalid-payload-shape-test
  ;; An invalid response cannot become a successful empty vector: [] means a
  ;; confirmed zero vault balance, while a malformed response means unknown.
  (is (nil? (vaults/normalize-user-vault-equities {:unexpected "object"})))
  (is (= [{:vault-address "0xa1"
           :equity nil
           :equity-raw "12bad"
           :locked-until-ms nil}]
         (vaults/normalize-user-vault-equities
          [{:vaultAddress "0xA1" :equity "12bad"}])))
  (is (= [{:vault-address "0xa1"
           :equity nil
           :equity-raw "  "
           :locked-until-ms nil}]
         (vaults/normalize-user-vault-equities
          [{:vaultAddress "0xA1" :equity "  "}]))))

(deftest user-vault-equity-normalization-does-not-confirm-an-incomplete-member-as-zero-test
  ;; A raw [] response is the only confirmed zero. Dropping malformed members
  ;; would turn both a malformed response and an undercounted mixed response
  ;; into a trusted vault total.
  (is (= [] (vaults/normalize-user-vault-equities [])))
  (doseq [payload [[nil]
                   [{}]
                   [{:vaultAddress " "}]
                   [{:vaultAddress "0xA1" :equity "2"} nil]]]
    (is (nil? (vaults/normalize-user-vault-equities payload))
        (pr-str payload))))

(deftest request-vault-details-builds-body-and-normalizes-portfolio-tuples-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub
                      calls
                      {:name "Vault Detail"
                       :vaultAddress "0xVaUlT"
                       :leader "0xLEADER"
                       :description "  hello  "
                       :portfolio [["day" {:accountValue "10"}]
                                   ["allTime" {:accountValue "20"}]]
                       :apr "0.7"
                       :followerState {:user "0xF1"
                                       :vaultEquity "90.5"
                                       :daysFollowing "8"
                                       :vaultEntryTime "111"
                                       :lockupUntil "222"}
                       :leaderFraction "0.1"
                       :leaderCommission "0.2"
                       :followers [{:user "0xA"} {:user "0xB"}]
                       :maxDistributable "120"
                       :maxWithdrawable "80"
                       :isClosed "false"
                       :relationship {:type "child"
                                      :data {:parentAddress "0xPARENT"}}
                       :allowDeposits "true"
                       :alwaysCloseOnWithdraw false})]
      (-> (vaults/request-vault-details! post-info! "0xVaUlT" {:user "0xUsEr"
                                                                :priority :low})
          (.then (fn [details]
                   (is (= {"type" "vaultDetails"
                           "vaultAddress" "0xvault"
                           "user" "0xuser"}
                          (ffirst @calls)))
                   (is (= {:priority :low
                           :dedupe-key [:vault-details "0xvault" "0xuser"]
                           :cache-ttl-ms 8000}
                          (second (first @calls))))
                   (is (= "Vault Detail" (:name details)))
                   (is (= "0xvault" (:vault-address details)))
                   (is (= "0xleader" (:leader details)))
                   (is (= "hello" (:description details)))
                   (is (nil? (:tvl details)))
                   (is (nil? (:tvl-raw details)))
                   (is (= {:day {:accountValue "10"}
                           :all-time {:accountValue "20"}}
                          (:portfolio details)))
                   (is (= 0.7 (:apr details)))
                   (is (= {:type :child
                           :parent-address "0xparent"}
                          (:relationship details)))
                   (is (= [{:user "0xa"} {:user "0xb"}]
                          (:followers details)))
                   (is (= 2 (:followers-count details)))
                   (is (true? (:allow-deposits? details)))
                   (is (false? (:always-close-on-withdraw? details)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-details-parses-tvl-when-payload-includes-it-test
  (async done
    (let [post-info! (api-stubs/post-info-stub
                      (atom [])
                      {:name "Vault Detail"
                       :vaultAddress "0xVaUlT"
                       :tvl "321.5"})]
      (-> (vaults/request-vault-details! post-info! "0xVaUlT" {})
          (.then (fn [details]
                   (is (= 321.5 (:tvl details)))
                   (is (= "321.5" (:tvl-raw details)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-webdata2-short-circuits-without-vault-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        {}))]
      (-> (vaults/request-vault-webdata2! post-info! nil {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-webdata2-fans-out-and-merges-supported-endpoints-test
  (async done
    (let [calls (atom [])
          clearinghouse {:assetPositions [{:position {:coin "ETH"}}]}
          open-orders [{:coin "ETH" :oid 1}]
          spot-state {:balances [{:coin "USDC" :total "5"}]}
          twap-rows [{:state {:coin "ETH"} :status {:status "activated"}}
                     {:state {:coin "BTC"} :status {:status "finished"}}
                     {:state {:coin "SOL"} :status "running"}]
          post-info! (api-stubs/post-info-stub
                      calls
                      (fn [body _opts]
                        (case (get body "type")
                          "vaultDetails" {:name "Vault" :relationship {:type "normal"}}
                          "clearinghouseState" clearinghouse
                          "frontendOpenOrders" open-orders
                          "spotClearinghouseState" spot-state
                          "twapHistory" twap-rows)))]
      (-> (vaults/request-vault-webdata2! post-info! "0xVaUlT" {:priority :low})
          (.then (fn [merged]
                   (is (= #{["vaultDetails" [:vault-details "0xvault" nil]]
                            ["clearinghouseState" [:vault-clearinghouse-state "0xvault"]]
                            ["frontendOpenOrders" [:vault-open-orders "0xvault"]]
                            ["spotClearinghouseState" [:vault-spot-clearinghouse-state "0xvault"]]
                            ["twapHistory" [:vault-twap-history "0xvault"]]}
                          (set (map (fn [[body opts]]
                                      [(get body "type") (:dedupe-key opts)])
                                    @calls))))
                   (is (every? (fn [[body _]]
                                 (= "0xvault" (or (get body "user")
                                                  (get body "vaultAddress"))))
                               @calls))
                   (is (= clearinghouse (:clearinghouseState merged)))
                   (is (= open-orders (:openOrders merged)))
                   (is (= spot-state (:spotState merged)))
                   (is (= [{:state {:coin "ETH"} :status {:status "activated"}}
                           {:state {:coin "SOL"} :status "running"}]
                          (:twapStates merged)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-webdata2-aggregates-parent-vault-children-test
  (async done
    (let [positions-by-user {"0xparent" []
                             "0xchild1" [{:position {:coin "ETH"}}]
                             "0xchild2" [{:position {:coin "BTC"}}]}
          orders-by-user {"0xparent" []
                          "0xchild1" [{:coin "ETH" :oid 1}]
                          "0xchild2" [{:coin "BTC" :oid 2}]}
          post-info! (api-stubs/post-info-stub
                      (fn [body _opts]
                        (let [user (get body "user")]
                          (case (get body "type")
                            "vaultDetails" {:relationship {:type "parent"
                                                           :data {:childAddresses
                                                                  ["0xChIlD1" "0xchild2"]}}}
                            "clearinghouseState" {:marginSummary {:accountValue "1.0"}
                                                  :assetPositions (get positions-by-user user)}
                            "frontendOpenOrders" (get orders-by-user user)
                            "spotClearinghouseState" {:balances []}
                            "twapHistory" []))))]
      (-> (vaults/request-vault-webdata2! post-info! "0xPaReNt" {})
          (.then (fn [merged]
                   (is (= [{:position {:coin "ETH"}} {:position {:coin "BTC"}}]
                          (get-in merged [:clearinghouseState :assetPositions])))
                   (is (= {:accountValue "1.0"}
                          (get-in merged [:clearinghouseState :marginSummary])))
                   (is (= [{:coin "ETH" :oid 1} {:coin "BTC" :oid 2}]
                          (:openOrders merged)))
                   (is (= {:balances []} (:spotState merged)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-vault-webdata2-degrades-non-core-slices-but-rejects-on-clearinghouse-failure-test
  (async done
    (let [soft-failure-post-info!
          (api-stubs/post-info-stub
           (fn [body _opts]
             (if (= "clearinghouseState" (get body "type"))
               {:assetPositions []}
               (js/Promise.reject (js/Error. "endpoint removed")))))
          core-failure-post-info!
          (api-stubs/post-info-stub
           (fn [body _opts]
             (if (= "clearinghouseState" (get body "type"))
               (js/Promise.reject (js/Error. "clearinghouse down"))
               [])))]
      (-> (vaults/request-vault-webdata2! soft-failure-post-info! "0xvault" {})
          (.then (fn [merged]
                   (is (= {:assetPositions []} (:clearinghouseState merged)))
                   (is (= [] (:openOrders merged)))
                   (is (nil? (:spotState merged)))
                   (is (= [] (:twapStates merged)))
                   (vaults/request-vault-webdata2! core-failure-post-info! "0xvault" {})))
          (.then (fn [_merged]
                   (is false "clearinghouseState failure should reject")
                   (done))
                 (fn [err]
                   (is (= "clearinghouse down" (.-message err)))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
