(ns hyperopen.vaults.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.vaults.effects :as effects]))

(deftest api-fetch-vault-index-applies-begin-and-success-projections-test
  (async done
    (let [request-calls (atom [])
          store (atom {})]
      (-> (effects/api-fetch-vault-index!
           {:store store
            :request-vault-index! (fn [opts]
                                    (swap! request-calls conj opts)
                                    (js/Promise.resolve [{:vault-address "0x1"}]))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-success (fn [state rows]
                                         (assoc state :index-rows rows))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))
            :opts {:priority :high}})
          (.then (fn [rows]
                   (is (= [{:vault-address "0x1"}] rows))
                   (is (= [{:priority :high}] @request-calls))
                   (is (= true (:index-loading? @store)))
                   (is (= [{:vault-address "0x1"}] (:index-rows @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault index error")
                    (done)))))))

(deftest api-fetch-vault-details-passes-vault-and-user-address-to-request-and-projections-test
  (async done
    (let [request-calls (atom [])
          store (atom {})]
      (-> (effects/api-fetch-vault-details!
           {:store store
            :vault-address "0x1234567890abcdef1234567890abcdef12345678"
            :user-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
            :request-vault-details! (fn [vault-address opts]
                                      (swap! request-calls conj [vault-address opts])
                                      (js/Promise.resolve {:name "Detail"}))
            :begin-vault-details-load (fn [state vault-address]
                                        (assoc state :begin-vault-address vault-address))
            :apply-vault-details-success (fn [state vault-address payload]
                                           (assoc state :detail [vault-address payload]))
            :apply-vault-details-error (fn [state vault-address err]
                                         (assoc state :detail-error [vault-address err]))})
          (.then (fn [_payload]
                   (is (= [["0x1234567890abcdef1234567890abcdef12345678"
                            {:user "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}]]
                          @request-calls))
                   (is (= "0x1234567890abcdef1234567890abcdef12345678"
                          (:begin-vault-address @store)))
                   (is (= ["0x1234567890abcdef1234567890abcdef12345678"
                           {:name "Detail"}]
                          (:detail @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault details error")
                    (done)))))))

(deftest api-fetch-vault-funding-history-uses-lookback-window-and-projections-test
  (async done
    (let [request-calls (atom [])
          store (atom {})]
      (-> (effects/api-fetch-vault-funding-history!
           {:store store
            :vault-address "0x1234567890abcdef1234567890abcdef12345678"
            :request-user-funding-history! (fn [vault-address opts]
                                             (swap! request-calls conj [vault-address opts])
                                             (js/Promise.resolve [{:coin "BTC"}]))
            :begin-vault-funding-history-load (fn [state vault-address]
                                                (assoc state :funding-begin vault-address))
            :apply-vault-funding-history-success (fn [state vault-address rows]
                                                   (assoc state :funding-success [vault-address rows]))
            :apply-vault-funding-history-error (fn [state vault-address err]
                                                 (assoc state :funding-error [vault-address err]))
            :now-ms-fn (fn [] 1000)})
          (.then (fn [_rows]
                   (let [[vault-address opts] (first @request-calls)]
                     (is (= "0x1234567890abcdef1234567890abcdef12345678" vault-address))
                     (is (= 1000 (:end-time-ms opts)))
                     (is (<= 0 (:start-time-ms opts)))
                     (is (= 1000 (- (:end-time-ms opts)
                                    (:start-time-ms opts)))))
                   (is (= "0x1234567890abcdef1234567890abcdef12345678"
                          (:funding-begin @store)))
                   (is (= ["0x1234567890abcdef1234567890abcdef12345678"
                           [{:coin "BTC"}]]
                          (:funding-success @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault funding history error")
                    (done)))))))

(deftest api-fetch-vault-ledger-updates-requests-non-funding-ledger-for-vault-test
  (async done
    (let [request-calls (atom [])
          store (atom {})]
      (-> (effects/api-fetch-vault-ledger-updates!
           {:store store
            :vault-address "0x1234567890abcdef1234567890abcdef12345678"
            :request-user-non-funding-ledger-updates! (fn [vault-address start-time-ms end-time-ms opts]
                                                        (swap! request-calls conj [vault-address start-time-ms end-time-ms opts])
                                                        (js/Promise.resolve [{:delta {:type "vaultDeposit"}}]))
            :begin-vault-ledger-updates-load (fn [state vault-address]
                                               (assoc state :ledger-begin vault-address))
            :apply-vault-ledger-updates-success (fn [state vault-address rows]
                                                  (assoc state :ledger-success [vault-address rows]))
            :apply-vault-ledger-updates-error (fn [state vault-address err]
                                                (assoc state :ledger-error [vault-address err]))})
          (.then (fn [_rows]
                   (is (= [["0x1234567890abcdef1234567890abcdef12345678" nil nil {}]]
                          @request-calls))
                   (is (= "0x1234567890abcdef1234567890abcdef12345678"
                          (:ledger-begin @store)))
                   (is (= ["0x1234567890abcdef1234567890abcdef12345678"
                           [{:delta {:type "vaultDeposit"}}]]
                          (:ledger-success @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault ledger updates error")
                    (done)))))))
