(ns hyperopen.views.vaults.startup-preview-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.vaults.infrastructure.preview-cache :as vault-preview-cache]
            [hyperopen.views.vaults.startup-preview :as vault-startup-preview]))

(deftest restore-startup-preview-loads-into-vault-state-for-list-route-test
  (let [store (atom {:router {:path "/vaults"}
                     :wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
                     :vaults-ui {:snapshot-range :month}
                     :vaults {:index-rows []}})
        preview-record {:id "vault-startup-preview:v1"
                        :version 1
                        :saved-at-ms 1700000000000
                        :snapshot-range :month
                        :wallet-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                        :total-visible-tvl 42
                        :protocol-rows [{:name "Preview Vault"
                                         :vault-address "0xpreview"
                                         :leader "0xleader"
                                         :tvl 42
                                         :apr 12
                                         :your-deposit 0
                                         :age-days 2
                                         :snapshot-series [1 2]}]
                        :user-rows []}
        restored-preview (assoc preview-record :stale? false)
        restore-call (atom nil)]
    (with-redefs [vault-preview-cache/load-vault-startup-preview-record! (fn []
                                                                          preview-record)
                  vault-preview-cache/restore-vault-startup-preview (fn [record opts]
                                                                      (reset! restore-call {:record record
                                                                                            :opts opts})
                                                                      restored-preview)
                  vault-preview-cache/clear-vault-startup-preview! (fn []
                                                                     (throw (js/Error. "should-not-clear")))
                  platform/now-ms (fn [] (+ 1700000000000 (* 5 60 1000)))]
      (vault-startup-preview/restore-startup-preview! store)
      (is (= restored-preview (get-in @store [:vaults :startup-preview])))
      (is (= {:record preview-record
              :opts {:snapshot-range :month
                     :wallet-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                     :now-ms (+ 1700000000000 (* 5 60 1000))}}
             @restore-call)))))

(deftest restore-startup-preview-clears-invalidated-preview-records-test
  (let [store (atom {:router {:path "/vaults"}
                     :vaults-ui {:snapshot-range :month}
                     :vaults {}})
        cleared? (atom false)]
    (with-redefs [vault-preview-cache/load-vault-startup-preview-record! (fn []
                                                                          {:id "vault-startup-preview:v1"
                                                                           :version 1
                                                                           :saved-at-ms 1700000000000
                                                                           :snapshot-range :month
                                                                           :protocol-rows [{:vault-address "0xpreview"}]})
                  vault-preview-cache/restore-vault-startup-preview (fn [_record _opts]
                                                                      nil)
                  vault-preview-cache/clear-vault-startup-preview! (fn []
                                                                     (reset! cleared? true))
                  platform/now-ms (fn [] (+ 1700000000000 (* 2 60 60 1000)))]
      (vault-startup-preview/restore-startup-preview! store)
      (is (true? @cleared?)))))

(deftest restore-startup-preview-skips-non-list-or-warm-vault-state-test
  (let [load-calls (atom 0)]
    (with-redefs [vault-preview-cache/load-vault-startup-preview-record! (fn []
                                                                          (swap! load-calls inc)
                                                                          {:id "vault-startup-preview:v1"})
                  platform/now-ms (fn [] 1700000000000)]
      (vault-startup-preview/restore-startup-preview! (atom {:router {:path "/trade"}
                                                             :vaults {:index-rows []}}))
      (vault-startup-preview/restore-startup-preview! (atom {:router {:path "/vaults"}
                                                             :vaults {:index-rows [{:vault-address "0x1"}]}}))
      (vault-startup-preview/restore-startup-preview! (atom {:router {:path "/vaults"}
                                                             :vaults {:startup-preview {:saved-at-ms 1}}}))
      (is (= 0 @load-calls)))))
