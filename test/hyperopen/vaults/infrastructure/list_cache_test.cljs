(ns hyperopen.vaults.infrastructure.list-cache-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.platform :as platform]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.vaults.infrastructure.list-cache :as list-cache]))

(deftest normalize-vault-index-cache-record-preserves-preview-row-shape-test
  (let [record (list-cache/normalize-vault-index-cache-record
                {:id "custom-cache-id"
                 :version "3"
                 :saved-at-ms "1700000000000"
                 :etag " \"etag-1\" "
                 :last-modified " Thu, 20 Mar 2026 12:00:00 GMT "
                 :rows [{:name "Alpha Vault"
                         :vault-address "0xABc"
                         :leader "0xDEF"
                         :tvl "12.5"
                         :tvl-raw "12.5"
                         :is-closed? false
                         :relationship {:type :parent
                                        :child-addresses ["0xC1" "  "]}
                         :create-time-ms "1700"
                         :apr "0.25"
                         :apr-raw "0.25"
                         :snapshot-preview-by-key {:day {:series [1 "2.5" nil]
                                                         :last-value "2.5"}
                                                   :week {:series []}}}]})]
    (is (= {:id "custom-cache-id"
            :version 3
            :saved-at-ms 1700000000000
            :etag "\"etag-1\""
            :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"
            :rows [{:name "Alpha Vault"
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
                    :snapshot-preview-by-key {:day {:series [1 2.5]
                                                    :last-value 2.5}}}]}
           record))))

(deftest normalize-vault-index-cache-record-rejects-invalid-shapes-test
  (is (nil? (list-cache/normalize-vault-index-cache-record
             {:rows [{:vault-address "0xabc"}]})))
  (is (nil? (list-cache/normalize-vault-index-cache-record
             {:saved-at-ms "1700"
              :rows "not-a-seq"})))
  (is (nil? (list-cache/normalize-vault-index-cache-record nil))))

(deftest normalize-vault-index-cache-metadata-trims-and-rejects-invalid-shapes-test
  (is (= {:id "vault-index-cache:metadata"
          :version 2
          :saved-at-ms 1700000000000
          :etag "\"etag-2\""
          :last-modified "Thu, 20 Mar 2026 13:00:00 GMT"}
         (list-cache/normalize-vault-index-cache-metadata
          {:version "2"
           :saved-at-ms "1700000000000"
           :etag " \"etag-2\" "
           :last-modified " Thu, 20 Mar 2026 13:00:00 GMT "})))
  (is (nil? (list-cache/normalize-vault-index-cache-metadata {:etag "\"etag\""})))
  (is (nil? (list-cache/normalize-vault-index-cache-metadata nil))))

(deftest persist-and-load-vault-index-cache-roundtrip-test
  (async done
    (browser-mocks/with-test-indexed-db
      (fn []
        (with-redefs [platform/now-ms (fn []
                                        1700000000000)]
          (-> (list-cache/persist-vault-index-cache-record!
               [{:name "Alpha Vault"
                 :vault-address "0xAbC"
                 :leader "0xDEF"
                 :tvl 12.5
                 :tvl-raw "12.5"
                 :is-closed? false
                 :relationship {:type :child
                                :parent-address "0xPARENT"}
                 :create-time-ms 1700
                 :apr 0.25
                 :apr-raw "0.25"
                 :snapshot-preview-by-key {:day {:series [1 2.5]
                                                 :last-value 2.5}}}]
               {:etag "\"etag-1\""
                :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"})
              (.then (fn [persisted?]
                       (is (true? persisted?))
                       (-> (list-cache/load-vault-index-cache-record!)
                           (.then (fn [record]
                                    (is (= {:id "vault-index-cache"
                                            :version 1
                                            :saved-at-ms 1700000000000
                                            :etag "\"etag-1\""
                                            :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"
                                            :rows [{:name "Alpha Vault"
                                                    :vault-address "0xabc"
                                                    :leader "0xdef"
                                                    :tvl 12.5
                                                    :tvl-raw 12.5
                                                    :is-closed? false
                                                    :relationship {:type :child
                                                                   :parent-address "0xparent"}
                                                    :create-time-ms 1700
                                                    :apr 0.25
                                                    :apr-raw 0.25
                                                    :snapshot-preview-by-key {:day {:series [1 2.5]
                                                                                    :last-value 2.5}}}]}
                                           record))
                                    (-> (list-cache/load-vault-index-cache-metadata!)
                                        (.then (fn [metadata-record]
                                                 (is (= {:id "vault-index-cache:metadata"
                                                         :version 1
                                                         :saved-at-ms 1700000000000
                                                         :etag "\"etag-1\""
                                                         :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}
                                                        metadata-record))
                                                 (done)))
                                        (.catch (async-support/unexpected-error done)))))
                           (.catch (async-support/unexpected-error done)))))
              (.catch (async-support/unexpected-error done))))))))

(deftest vault-index-cache-helpers-gracefully-handle-unavailable-indexed-db-test
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
      (-> (list-cache/load-vault-index-cache-record!)
          (.then (fn [record]
                   (is (nil? record))
                   (-> (list-cache/load-vault-index-cache-metadata!)
                       (.then (fn [metadata-record]
                                (is (nil? metadata-record))
                                (-> (list-cache/persist-vault-index-cache-record!
                                     [{:vault-address "0xabc"}]
                                     {:etag "\"etag-1\""})
                                    (.then (fn [persisted?]
                                             (is (false? persisted?))
                                             (restore!)
                                             (done)))
                                    (.catch fail!))))
                       (.catch fail!))))
          (.catch fail!)))))
