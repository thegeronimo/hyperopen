(ns hyperopen.platform.indexed-db-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.test-support.async :as async-support]))

(def ^:private request-error
  @#'hyperopen.platform.indexed-db/request-error)

(def ^:private create-object-stores!
  @#'hyperopen.platform.indexed-db/create-object-stores!)

(def ^:private transact-request!
  @#'hyperopen.platform.indexed-db/transact-request!)

(defn- with-indexed-db-api
  [indexed-db-value f]
  (let [original-indexed-db (.-indexedDB js/globalThis)]
    (set! (.-indexedDB js/globalThis) indexed-db-value)
    (indexed-db/clear-open-db-cache!)
    (let [restore! (fn []
                     (indexed-db/clear-open-db-cache!)
                     (set! (.-indexedDB js/globalThis) original-indexed-db))]
      (try
        (let [result (f)]
          (if (instance? js/Promise result)
            (.finally result restore!)
            (do
              (restore!)
              result)))
        (catch :default e
          (restore!)
          (throw e))))))

(defn- expect-rejection!
  [promise assert-error]
  (-> promise
      (.then (fn [value]
               (is false (str "Expected promise rejection, got: " value))
               nil))
      (.catch (fn [error]
                (assert-error error)
                nil))))

(defn- make-db-and-tx
  ([] (make-db-and-tx true))
  ([store-present?]
   (let [store #js {}
         tx #js {}
         db #js {}]
     (aset tx "objectStore" (fn [_store-name]
                              store))
     (aset db "objectStoreNames" #js {:contains (fn [_store-name]
                                                  store-present?)})
     (aset db "transaction" (fn [_store-names _mode]
                              tx))
     {:db db
      :tx tx
      :store store})))

(deftest indexed-db-json-roundtrip-test
  (async done
    (browser-mocks/with-test-indexed-db
      (fn []
        (-> (indexed-db/put-json! indexed-db/asset-selector-markets-store
                                  "selector-cache"
                                  {:rows [{:coin "BTC"}
                                          {:coin "ETH"}]
                                   :saved-at-ms 123})
            (.then (fn [persisted?]
                     (is (true? persisted?))
                     (-> (indexed-db/get-json! indexed-db/asset-selector-markets-store
                                               "selector-cache")
                         (.then (fn [record]
                                  (is (= {:rows [{:coin "BTC"}
                                                 {:coin "ETH"}]
                                          :saved-at-ms 123}
                                         record))
                                  (-> (indexed-db/delete-key! indexed-db/asset-selector-markets-store
                                                              "selector-cache")
                                      (.then (fn [deleted?]
                                               (is (true? deleted?))
                                               (-> (indexed-db/get-json! indexed-db/asset-selector-markets-store
                                                                         "selector-cache")
                                                   (.then (fn [missing]
                                                            (is (nil? missing))
                                                            (done)))
                                                   (.catch (async-support/unexpected-error done)))))
                                      (.catch (async-support/unexpected-error done)))))
                         (.catch (async-support/unexpected-error done)))))
            (.catch (async-support/unexpected-error done)))))))

(deftest indexed-db-helpers-gracefully-handle-unavailable-browser-api-test
  (async done
    (let [original-indexed-db (.-indexedDB js/globalThis)
          restore! (fn []
                     (set! (.-indexedDB js/globalThis) original-indexed-db)
                     (indexed-db/clear-open-db-cache!))
          fail! (fn [error]
                  (restore!)
                  ((async-support/unexpected-error done) error))]
      (indexed-db/clear-open-db-cache!)
      (set! (.-indexedDB js/globalThis) nil)
      (-> (indexed-db/get-json! indexed-db/funding-history-store "BTC")
          (.then (fn [result]
                   (is (nil? result))
                   (-> (indexed-db/put-json! indexed-db/funding-history-store
                                             "BTC"
                                             {:rows []})
                       (.then (fn [persisted?]
                                (is (false? persisted?))
                                (-> (indexed-db/delete-key! indexed-db/funding-history-store "BTC")
                                    (.then (fn [deleted?]
                                             (is (false? deleted?))
                                             (restore!)
                                             (done)))
                                    (.catch fail!))))
                       (.catch fail!))))
          (.catch fail!)))))

(deftest request-error-prefers-request-error-and-falls-back-test
  (let [expected (js/Error. "request-boom")]
    (is (identical? expected
                    (request-error #js {:error expected}
                                   "fallback")))
    (is (= "fallback"
           (.-message (request-error #js {}
                                     "fallback"))))))

(deftest create-object-stores-creates-only-missing-stores-test
  (let [existing-stores (atom #{"alpha"})
        created-stores (atom [])
        db #js {}]
    (aset db "objectStoreNames" #js {:contains (fn [store-name]
                                                 (contains? @existing-stores store-name))})
    (aset db "createObjectStore" (fn [store-name]
                                   (swap! created-stores conj store-name)
                                   (swap! existing-stores conj store-name)
                                   #js {}))
    (create-object-stores! db ["alpha" "beta"])
    (is (= ["beta"] @created-stores))
    (is (= #{"alpha" "beta"} @existing-stores))))

(deftest open-db-default-call-caches-promise-test
  (async done
    (let [open-count (atom 0)
          db #js {:close (fn [] nil)}
          fail! (async-support/unexpected-error done)]
      (with-indexed-db-api
        #js {:open (fn [_db-name _db-version]
                     (swap! open-count inc)
                     (let [request #js {}]
                       (js/setTimeout
                        (fn []
                          (set! (.-result request) db)
                          (when-let [handler (.-onsuccess request)]
                            (handler #js {:target #js {:result db}})))
                        0)
                       request))}
        (fn []
          (let [first (indexed-db/open-db!)
                second (indexed-db/open-db!)]
            (is (identical? first second))
            (-> first
                (.then (fn [opened-db]
                         (is (identical? db opened-db))
                         (is (= 1 @open-count))
                         (-> second
                             (.then (fn [cached-db]
                                      (is (identical? db cached-db))
                                      (is (= 1 @open-count))
                                      (done)))
                             (.catch fail!))))
                (.catch fail!))))))))

(deftest open-db-evicts-failed-cache-and-allows-retry-test
  (async done
    (let [open-count (atom 0)
          expected (js/Error. "open-boom")
          db #js {:close (fn [] nil)}
          opts {:db-name "retry-db"
                :db-version 2
                :store-names ["alpha"]}
          fail! (async-support/unexpected-error done)]
      (with-indexed-db-api
        #js {:open (fn [_db-name _db-version]
                     (let [attempt (swap! open-count inc)
                           request #js {}]
                       (js/setTimeout
                        (fn []
                          (if (= 1 attempt)
                            (do
                              (set! (.-error request) expected)
                              (when-let [handler (.-onerror request)]
                                (handler #js {})))
                            (do
                              (set! (.-result request) db)
                              (when-let [handler (.-onsuccess request)]
                                (handler #js {:target #js {:result db}})))))
                        0)
                       request))}
        (fn []
          (-> (expect-rejection! (indexed-db/open-db! opts)
                                 (fn [error]
                                   (is (identical? expected error))))
              (.then (fn []
                       (-> (indexed-db/open-db! opts)
                           (.then (fn [opened-db]
                                    (is (identical? db opened-db))
                                    (is (= 2 @open-count))
                                    (done)))
                           (.catch fail!))))
              (.catch fail!)))))))

(deftest open-db-rejects-blocked-requests-test
  (async done
    (let [opts {:db-name "blocked-db"
                :db-version 1
                :store-names ["alpha"]}]
      (with-indexed-db-api
        #js {:open (fn [_db-name _db-version]
                     (let [request #js {}]
                       (js/setTimeout
                        (fn []
                          (when-let [handler (.-onblocked request)]
                            (handler #js {})))
                        0)
                       request))}
        (fn []
          (-> (expect-rejection! (indexed-db/open-db! opts)
                                 (fn [error]
                                   (is (= "IndexedDB open blocked for blocked-db"
                                          (.-message error)))))
              (.then (fn []
                       (done)))))))))

(deftest open-db-propagates-synchronous-open-failures-test
  (async done
    (let [expected (js/Error. "sync-open-boom")
          opts {:db-name "sync-db"
                :db-version 1
                :store-names []}]
      (with-indexed-db-api
        #js {:open (fn [_db-name _db-version]
                     (throw expected))}
        (fn []
          (-> (expect-rejection! (indexed-db/open-db! opts)
                                 (fn [error]
                                   (is (identical? expected error))))
              (.then (fn []
                       (done)))))))))

(deftest transact-request-returns-nil-when-store-is-missing-test
  (async done
    (let [fail! (async-support/unexpected-error done)]
      (-> (transact-request! (:db (make-db-and-tx false))
                             "missing-store"
                             "readonly"
                             (fn [_]
                               (is false "request-fn should not run when the store is missing"))
                             identity)
          (.then (fn [result]
                   (is (nil? result))
                   (done)))
          (.catch fail!)))))

(deftest transact-request-rejects-transaction-abort-and-error-test
  (async done
    (let [fail! (async-support/unexpected-error done)
          expected-abort (js/Error. "tx-abort")]
      (let [{:keys [db tx]} (make-db-and-tx)]
        (-> (transact-request! db
                               "cache"
                               "readonly"
                               (fn [_store]
                                 (let [request #js {}]
                                   (js/setTimeout
                                    (fn []
                                      (set! (.-error tx) expected-abort)
                                      (when-let [handler (.-onabort tx)]
                                        (handler #js {})))
                                    0)
                                   request))
                               identity)
            (.then (fn [_]
                     (is false "Expected transaction abort rejection")
                     (done)))
            (.catch (fn [error]
                      (is (identical? expected-abort error))
                      (let [{:keys [db tx]} (make-db-and-tx)]
                        (-> (transact-request! db
                                               "cache"
                                               "readonly"
                                               (fn [_store]
                                                 (let [request #js {}]
                                                   (js/setTimeout
                                                    (fn []
                                                      (when-let [handler (.-onerror tx)]
                                                        (handler #js {})))
                                                    0)
                                                   request))
                                               identity)
                            (.then (fn [_]
                                     (is false "Expected transaction error rejection")
                                     (done)))
                            (.catch (fn [tx-error]
                                      (is (= "IndexedDB transaction failed for cache"
                                             (.-message tx-error)))
                                      (done)))
                            (.catch fail!))))))))))

(deftest transact-request-rejects-request-errors-and-sync-throws-test
  (async done
    (let [fail! (async-support/unexpected-error done)
          expected-request-error (js/Error. "request-boom")
          expected-sync-error (js/Error. "sync-request-boom")]
      (let [{:keys [db]} (make-db-and-tx)]
        (-> (transact-request! db
                               "cache"
                               "readonly"
                               (fn [_store]
                                 (let [request #js {}]
                                   (js/setTimeout
                                    (fn []
                                      (set! (.-error request) expected-request-error)
                                      (when-let [handler (.-onerror request)]
                                        (handler #js {})))
                                    0)
                                   request))
                               identity)
            (.then (fn [_]
                     (is false "Expected request error rejection")
                     (done)))
            (.catch (fn [error]
                      (is (identical? expected-request-error error))
                      (let [{:keys [db]} (make-db-and-tx)]
                        (-> (transact-request! db
                                               "cache"
                                               "readonly"
                                               (fn [_store]
                                                 (let [request #js {}]
                                                   (js/setTimeout
                                                    (fn []
                                                      (when-let [handler (.-onerror request)]
                                                        (handler #js {})))
                                                    0)
                                                   request))
                                               identity)
                            (.then (fn [_]
                                     (is false "Expected fallback request rejection")
                                     (done)))
                            (.catch (fn [fallback-error]
                                      (is (= "IndexedDB request failed for cache"
                                             (.-message fallback-error)))
                                      (let [{:keys [db]} (make-db-and-tx)]
                                        (-> (transact-request! db
                                                               "cache"
                                                               "readonly"
                                                               (fn [_store]
                                                                 (throw expected-sync-error))
                                                               identity)
                                            (.then (fn [_]
                                                     (is false "Expected sync setup rejection")
                                                     (done)))
                                            (.catch (fn [sync-error]
                                                      (is (identical? expected-sync-error sync-error))
                                                      (done)))
                                            (.catch fail!)))))
                            (.catch fail!))))))))))

(deftest indexed-db-helpers-support-custom-options-and-store-isolation-test
  (async done
    (browser-mocks/with-test-indexed-db
      (fn []
        (let [opts {:db-name "coverage-db"
                    :db-version 7
                    :store-names [indexed-db/asset-selector-markets-store
                                  indexed-db/funding-history-store]}
              asset-store indexed-db/asset-selector-markets-store
              funding-store indexed-db/funding-history-store
              fail! (async-support/unexpected-error done)]
          (-> (js/Promise.all #js [(indexed-db/put-json! asset-store
                                                         "shared-key"
                                                         {:market "BTC"}
                                                         opts)
                                   (indexed-db/put-json! funding-store
                                                         "shared-key"
                                                         {:funding-rate 0.01}
                                                         opts)])
              (.then (fn [results]
                       (is (= [true true]
                              (vec (array-seq results))))
                       (js/Promise.all #js [(indexed-db/get-json! asset-store
                                                                  "shared-key"
                                                                  opts)
                                            (indexed-db/get-json! funding-store
                                                                  "shared-key"
                                                                  opts)])))
              (.then (fn [records]
                       (let [[asset-record funding-record] (vec (array-seq records))]
                         (is (= {:market "BTC"} asset-record))
                         (is (= {:funding-rate 0.01} funding-record))
                         (indexed-db/delete-key! asset-store
                                                 "shared-key"
                                                 opts))))
              (.then (fn [deleted?]
                       (is (true? deleted?))
                       (js/Promise.all #js [(indexed-db/get-json! asset-store
                                                                  "shared-key"
                                                                  opts)
                                            (indexed-db/get-json! funding-store
                                                                  "shared-key"
                                                                  opts)])))
              (.then (fn [records]
                       (let [[deleted-record remaining-record] (vec (array-seq records))]
                         (is (nil? deleted-record))
                         (is (= {:funding-rate 0.01} remaining-record))
                         (done))))
              (.catch fail!)))))))

(deftest indexed-db-helpers-return-nil-when-store-is-missing-test
  (async done
    (browser-mocks/with-test-indexed-db
      (fn []
        (let [opts {:db-name "missing-store-db"
                    :db-version 1
                    :store-names [indexed-db/funding-history-store]}
              fail! (async-support/unexpected-error done)]
          (-> (indexed-db/get-json! indexed-db/leaderboard-cache-store
                                    "BTC"
                                    opts)
              (.then (fn [record]
                       (is (nil? record))
                       (-> (indexed-db/put-json! indexed-db/leaderboard-cache-store
                                                 "BTC"
                                                 {:rows []}
                                                 opts)
                           (.then (fn [persisted?]
                                    (is (nil? persisted?))
                                    (-> (indexed-db/delete-key! indexed-db/leaderboard-cache-store
                                                                "BTC"
                                                                opts)
                                        (.then (fn [deleted?]
                                                 (is (nil? deleted?))
                                                 (done)))
                                        (.catch fail!))))
                           (.catch fail!))))
              (.catch fail!)))))))
