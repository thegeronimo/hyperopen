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

(deftest request-vault-index-normalizes-summary-shape-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url init])
                     (js/Promise.resolve
                      (ok-response
                       [{:apr "0.25"
                         :pnls [["day" ["1.5" "2.5"]]
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
                     (is (= {:day [1.5 2.5]
                             :all-time [3.5]}
                            (:snapshot-by-key row))))
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
                           :dedupe-key :vault-summaries}
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
                            :snapshot-by-key {}}]
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
                           :dedupe-key [:user-vault-equities "0xabc"]}
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
                       :followers "7"
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
                           :dedupe-key [:vault-details "0xvault" "0xuser"]}
                          (second (first @calls))))
                   (is (= "Vault Detail" (:name details)))
                   (is (= "0xvault" (:vault-address details)))
                   (is (= "0xleader" (:leader details)))
                   (is (= "hello" (:description details)))
                   (is (= {:day {:accountValue "10"}
                           :all-time {:accountValue "20"}}
                          (:portfolio details)))
                   (is (= 0.7 (:apr details)))
                   (is (= {:type :child
                           :parent-address "0xparent"}
                          (:relationship details)))
                   (is (true? (:allow-deposits? details)))
                   (is (false? (:always-close-on-withdraw? details)))
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
            :dedupe-key [:vault-webdata2 "0xvault"]}
           (second (first @calls))))))
