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

(deftest normalize-snapshot-key-supports-supported-aliases-test
  (let [normalize-snapshot-key @#'hyperopen.api.endpoints.vaults/normalize-snapshot-key]
    (is (= :day (normalize-snapshot-key " day ")))
    (is (= :week (normalize-snapshot-key "Week")))
    (is (= :month (normalize-snapshot-key "month")))
    (is (= :three-month (normalize-snapshot-key "3M")))
    (is (= :three-month (normalize-snapshot-key "quarter")))
    (is (= :six-month (normalize-snapshot-key "half-year")))
    (is (= :one-year (normalize-snapshot-key "1Y")))
    (is (= :two-year (normalize-snapshot-key "2year")))
    (is (= :all-time (normalize-snapshot-key "allTime")))
    (is (nil? (normalize-snapshot-key "unknown")))
    (is (nil? (normalize-snapshot-key nil)))))

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

(deftest merge-vault-index-with-summaries-appends-recent-and-dedupes-by-address-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two"
                            :createTimeMillis 200}}]
                [{:summary {:vaultAddress "0x2"
                            :name "Vault Two New"
                            :createTimeMillis 350}}
                 {:summary {:vaultAddress "0x3"
                            :name "Vault Three"
                            :createTimeMillis 300}}
                 {:summary {:vaultAddress "0x4"
                            :name "Too Old"
                            :createTimeMillis 20}}])]
    (is (= ["0x1" "0x2" "0x3"]
           (mapv :vault-address merged)))
    (is (= "Vault Two New"
           (:name (second merged))))
    (is (= 350
           (:create-time-ms (second merged))))))

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
                        :lockedUntilTimestamp "1700"}
                       {:vaultAddress ""
                        :equity "10"}])]
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

(deftest request-vault-webdata2-builds-request-body-and-dedupe-key-test
  (let [calls (atom [])
        post-info! (api-stubs/post-info-stub calls {:ok true})]
    (vaults/request-vault-webdata2! post-info! "0xVaUlT" {:priority :low})
    (is (= {"type" "webData2"
            "user" "0xvault"}
           (ffirst @calls)))
    (is (= {:priority :low
            :dedupe-key [:vault-webdata2 "0xvault"]
            :cache-ttl-ms 8000}
           (second (first @calls))))))

(deftest cross-origin-browser-request-private-helper-detects-same-origin-and-invalid-url-test
  (let [cross-origin-browser-request? @#'hyperopen.api.endpoints.vaults/cross-origin-browser-request?]
    (let [original-location (.-location js/globalThis)
          original-url (.-URL js/globalThis)]
      (try
        (set! (.-location js/globalThis)
              #js {:origin "https://vaults.test"
                   :href "https://vaults.test/app"})
        (is (false? (cross-origin-browser-request? "https://vaults.test/index")))
        (is (true? (cross-origin-browser-request? "https://example.com/index")))
        (is (false? (cross-origin-browser-request? "not a url")))
        (set! (.-URL js/globalThis)
              (fn [& _]
                (throw (js/Error. "bad-url"))))
        (is (false? (cross-origin-browser-request? "https://example.com/index")))
        (finally
          (set! (.-URL js/globalThis) original-url)
          (set! (.-location js/globalThis) original-location))))))

(deftest normalize-vault-snapshot-return-and-preview-series-helpers-test
  (let [normalize-vault-snapshot-return @#'hyperopen.api.endpoints.vaults/normalize-vault-snapshot-return
        sample-snapshot-preview-series @#'hyperopen.api.endpoints.vaults/sample-snapshot-preview-series]
    (is (= 4000
           (normalize-vault-snapshot-return 2000 50)))
    (is (= 100
           (normalize-vault-snapshot-return 1 10)))
    (is (= 50
           (normalize-vault-snapshot-return 0.5 10)))
    (is (= 2
           (normalize-vault-snapshot-return 2 10)))
    (is (nil? (normalize-vault-snapshot-return "bad" 10)))
    (is (= [0 1 2 3 4 5 6 7]
           (sample-snapshot-preview-series (range 8))))
    (is (= 8
           (count (sample-snapshot-preview-series (range 10)))))
    (is (= [0 1 3 4 5 6 8 9]
           (sample-snapshot-preview-series (range 10))))))

(deftest normalize-vault-pnls-relationship-summary-and-detail-helpers-test
  (let [boolean-value @#'hyperopen.api.endpoints.vaults/boolean-value
        normalize-vault-pnls @#'hyperopen.api.endpoints.vaults/normalize-vault-pnls
        normalize-vault-relationship @#'hyperopen.api.endpoints.vaults/normalize-vault-relationship
        normalize-vault-summary @#'hyperopen.api.endpoints.vaults/normalize-vault-summary
        normalize-user-vault-equity @#'hyperopen.api.endpoints.vaults/normalize-user-vault-equity
        normalize-follower-state @#'hyperopen.api.endpoints.vaults/normalize-follower-state
        followers-count @#'hyperopen.api.endpoints.vaults/followers-count
        normalize-vault-details @#'hyperopen.api.endpoints.vaults/normalize-vault-details]
    (is (true? (boolean-value true)))
    (is (false? (boolean-value false)))
    (is (true? (boolean-value "true")))
    (is (false? (boolean-value "false")))
    (is (nil? (boolean-value "maybe")))
    (is (= {:day [1 2]
            :week []
            :month []
            :all-time [3 4]}
           (normalize-vault-pnls [["day" ["1" "2" "bad"]]
                                  ["week" nil]
                                  ["month" []]
                                  ["allTime" ["3" "4"]]
                                  [:ignored [5]]])))
    (is (= {:type :parent
            :child-addresses ["0x1" "0x2"]}
           (normalize-vault-relationship {:type "parent"
                                          :data {:childAddresses ["0x1" " " "0x2"]}})))
    (is (= {:type :child
            :parent-address "0xparent"}
           (normalize-vault-relationship {:type "child"
                                          :data {:parentAddress "0xParent"}})))
    (is (= {:type :normal}
           (normalize-vault-relationship {:type "other"})))
    (is (= {:name "0xabc"
            :vault-address "0xabc"
            :leader "0xleader"
            :tvl 0
            :tvl-raw nil
            :is-closed? true
            :relationship {:type :normal}
            :create-time-ms 1700}
           (normalize-vault-summary {:vaultAddress "0xABC"
                                     :leader "0xLeader"
                                     :isClosed "true"
                                     :createTimeMillis "1700"})))
    (is (= {:vault-address "0xabc"
            :equity 0
            :equity-raw nil
            :locked-until-ms 1700}
           (normalize-user-vault-equity {:vaultAddress "0xABC"
                                         :lockedUntilTimestamp "1700"})))
    (is (= {:user "0xabc"
            :vault-equity 2.5
            :days-following 8}
           (normalize-follower-state {:user "0xABC"
                                      :vaultEquity "2.5"
                                      :daysFollowing "8"
                                      :vaultEntryTime nil})))
    (is (= 3 (followers-count "3" [])))
    (is (= 2 (followers-count nil [{:user "0x1"} {:user "0x2"}])))
    (is (= {:name "Vault Detail"
            :vault-address "0xvault"
            :leader "0xleader"
            :description "hello"
            :tvl nil
            :tvl-raw nil
            :portfolio {}
            :apr 0
            :follower-state {:user "0xf1"
                             :vault-equity 90.5
                             :days-following 8
                             :vault-entry-time-ms 111
                             :lockup-until-ms 222}
            :leader-fraction 0.1
            :leader-commission 0.2
            :followers []
            :followers-count 3
            :max-distributable 120
            :max-withdrawable 80
            :is-closed? true
            :relationship {:type :child
                           :parent-address "0xparent"}
            :allow-deposits? true
            :always-close-on-withdraw? true}
           (normalize-vault-details {:name "Vault Detail"
                                    :vaultAddress "0xVaUlT"
                                    :leader "0xLEADER"
                                    :description "  hello  "
                                    :portfolio []
                                    :apr nil
                                    :followerState {:user "0xF1"
                                                    :vaultEquity "90.5"
                                                    :daysFollowing "8"
                                                    :vaultEntryTime "111"
                                                    :lockupUntil "222"}
                                    :leaderFraction "0.1"
                                    :leaderCommission "0.2"
                                    :followers "3"
                                    :maxDistributable "120"
                                    :maxWithdrawable "80"
                                    :isClosed "true"
                                    :relationship {:type "child"
                                                   :data {:parentAddress "0xPARENT"}}
                                    :allowDeposits "true"
                                    :alwaysCloseOnWithdraw "true"})))
    (is (= {:apr 0
            :is-closed? false
            :allow-deposits? false
            :always-close-on-withdraw? false}
           (select-keys
            (normalize-vault-details {:vaultAddress "0xVaUlT"
                                      :apr nil
                                      :isClosed "maybe"
                                      :allowDeposits "maybe"
                                      :alwaysCloseOnWithdraw "maybe"})
            [:apr
             :is-closed?
             :allow-deposits?
             :always-close-on-withdraw?])))))

(deftest merge-vault-index-with-summaries-prefers-newer-recent-summary-rows-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two"
                            :createTimeMillis 200}}]
                [{:summary {:vaultAddress "0x2"
                            :name "Vault Two New"
                            :createTimeMillis 250}}
                 {:summary {:vaultAddress "0x3"
                            :name "Vault Three"
                            :createTimeMillis 300}}
                 {:summary {:vaultAddress "0x4"
                            :name "Too Old"
                            :createTimeMillis 20}}])]
    (is (= ["0x1" "0x2" "0x3"]
           (mapv :vault-address merged)))
    (is (= "Vault Two New" (:name (second merged))))
    (is (= 250 (:create-time-ms (second merged))))))

(deftest merge-vault-index-with-summaries-excludes-equal-age-new-addresses-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two"
                            :createTimeMillis 200}}]
                [{:summary {:vaultAddress "0x3"
                            :name "Vault Three Equal"
                            :createTimeMillis 200}}])]
    (is (= ["0x1" "0x2"]
           (mapv :vault-address merged)))))

(deftest merge-vault-index-with-summaries-replaces-equal-age-duplicate-addresses-test
  (let [merged (vaults/merge-vault-index-with-summaries
                [{:summary {:vaultAddress "0x1"
                            :name "Vault One"
                            :createTimeMillis 100}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two Old"
                            :createTimeMillis 200}}
                 {:summary {:vaultAddress "0x2"
                            :name "Vault Two Equal"
                            :createTimeMillis 200}}]
                [])]
    (is (= ["0x1" "0x2"]
           (mapv :vault-address merged)))
    (is (= "Vault Two Equal"
           (:name (second merged))))
    (is (= 200
           (:create-time-ms (second merged))))))
