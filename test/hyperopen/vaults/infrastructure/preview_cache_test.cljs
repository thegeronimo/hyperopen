(ns hyperopen.vaults.infrastructure.preview-cache-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.vaults.infrastructure.preview-cache :as preview-cache]))

(deftest build-vault-startup-preview-record-wraps-envelope-test
  (let [state {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :vaults-ui {:search-query ""
                           :filter-leading? true
                           :filter-deposited? true
                           :filter-others? true
                           :filter-closed? false
                           :snapshot-range :month
                           :user-vaults-page-size 10
                           :user-vaults-page 1
                           :sort {:column :tvl
                                  :direction :desc}}
               :vaults {:merged-index-rows [{:name "Hyperliquidity Provider (HLP)"
                                             :vault-address "0x1111111111111111111111111111111111111111"
                                             :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                             :tvl 500
                                             :apr 0.12
                                             :relationship {:type :parent}
                                             :is-closed? false
                                             :create-time-ms (- 1700000000000 (* 2 24 60 60 1000))
                                             :snapshot-by-key {:month [0.01 0.02]}}]
                        :user-equity-by-address {}}}]
    (with-redefs [platform/now-ms (fn []
                                    1700000000000)]
      (let [record (preview-cache/build-vault-startup-preview-record state)]
        (is (= "vault-startup-preview:v1" (:id record)))
        (is (= 1 (:version record)))
        (is (= 1700000000000 (:saved-at-ms record)))
        (is (= :month (:snapshot-range record)))
        (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
               (:wallet-address record)))
        (is (= 1 (count (:protocol-rows record))))
        (is (= [] (:user-rows record)))))))

(deftest vault-startup-preview-cache-roundtrip-and-bounds-on-load-test
  (let [storage (atom nil)
        state {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
               :vaults-ui {:search-query ""
                           :filter-leading? true
                           :filter-deposited? true
                           :filter-others? true
                           :filter-closed? false
                           :snapshot-range :month
                           :user-vaults-page-size 10
                           :user-vaults-page 1
                           :sort {:column :tvl
                                  :direction :desc}}
               :vaults {:merged-index-rows (vec
                                            (concat
                                             [{:name "Hyperliquidity Provider (HLP)"
                                               :vault-address "0x1111111111111111111111111111111111111111"
                                               :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                               :tvl 500
                                               :apr 0.12
                                               :relationship {:type :parent}
                                               :is-closed? false
                                               :create-time-ms (- 1700000000000 (* 2 24 60 60 1000))
                                               :snapshot-by-key {:month [0.01 0.02]}}]
                                             (for [idx (range 12)]
                                               {:name (str "Vault " idx)
                                                :vault-address (str "0x00000000000000000000000000000000000000" idx)
                                                :leader (str "0x10000000000000000000000000000000000000" idx)
                                                :tvl (+ 100 idx)
                                                :apr (+ 0.01 (* idx 0.01))
                                                :relationship {:type :normal}
                                                :is-closed? false
                                                :create-time-ms (- 1700000000000 (* (+ idx 3) 24 60 60 1000))
                                                :snapshot-by-key {:month [0.01 0.02]}})))
                        :user-equity-by-address {}}}]
    (with-redefs [platform/now-ms (fn [] 1700000000000)
                  platform/local-storage-set! (fn [_key value]
                                                (reset! storage value))
                  platform/local-storage-get (fn [_key]
                                               @storage)]
      (is (true? (preview-cache/persist-vault-startup-preview-record! state)))
      (let [record (preview-cache/load-vault-startup-preview-record!)]
        (is (= "vault-startup-preview:v1" (:id record)))
        (is (= 1 (:version record)))
        (is (= 1700000000000 (:saved-at-ms record)))
        (is (= :month (:snapshot-range record)))
        (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
               (:wallet-address record)))
        (is (= 1 (count (:protocol-rows record))))
        (is (= 8 (count (:user-rows record))))
        (is (= "0x1111111111111111111111111111111111111111"
               (get-in record [:protocol-rows 0 :vault-address])))
        (is (= "Vault 11" (get-in record [:user-rows 0 :name])))))))

(deftest vault-startup-preview-cache-rejects-invalid-json-and-write-failures-test
  (let [storage (atom "not-json")
        write-calls (atom 0)]
    (with-redefs [platform/local-storage-get (fn [_key]
                                               @storage)
                  platform/local-storage-set! (fn [_key _value]
                                                (swap! write-calls inc)
                                                (throw (js/Error. "storage-unavailable")))
                  platform/local-storage-remove! (fn [_key]
                                                   nil)]
      (is (nil? (preview-cache/load-vault-startup-preview-record!)))
      (is (false? (preview-cache/persist-vault-startup-preview-record!
                   {:vaults-ui {:snapshot-range :month}
                    :vaults {:merged-index-rows [{:vault-address "0xabc"
                                                 :leader "0xdef"
                                                 :snapshot-by-key {:month [0.01]}}]}})))
      (is (= 1 @write-calls)))))

(deftest vault-startup-preview-restore-rejects-mismatched-or-stale-records-test
  (let [preview-record {:id "vault-startup-preview:v1"
                        :version 1
                        :saved-at-ms 1700000000000
                        :snapshot-range :month
                        :wallet-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                        :total-visible-tvl 600
                        :protocol-rows [{:name "Protocol Vault"
                                         :vault-address "0x1111111111111111111111111111111111111111"
                                         :leader "0x2222222222222222222222222222222222222222"
                                         :tvl 500
                                         :apr 12
                                         :your-deposit 0
                                         :age-days 2
                                         :snapshot-series [1 2]}]
                        :user-rows [{:name "User Vault"
                                     :vault-address "0x3333333333333333333333333333333333333333"
                                     :leader "0x4444444444444444444444444444444444444444"
                                     :tvl 100
                                     :apr 8
                                     :your-deposit 50
                                     :age-days 3
                                     :snapshot-series [1 3]}]}
        wallet-mismatch (preview-cache/restore-vault-startup-preview
                         preview-record
                         {:snapshot-range :month
                          :wallet-address nil
                          :now-ms (+ 1700000000000 (* 5 60 1000))})
        range-mismatch (preview-cache/restore-vault-startup-preview
                        preview-record
                        {:snapshot-range :week
                         :wallet-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                         :now-ms (+ 1700000000000 (* 5 60 1000))})
        stale-preview (preview-cache/restore-vault-startup-preview
                       preview-record
                       {:snapshot-range :month
                        :wallet-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                        :now-ms (+ 1700000000000 (* 2 60 60 1000))})]
    (is (= [] (:user-rows wallet-mismatch)))
    (is (nil? (:wallet-address wallet-mismatch)))
    (is (= 500 (:total-visible-tvl wallet-mismatch)))
    (is (nil? range-mismatch))
    (is (nil? stale-preview))))
