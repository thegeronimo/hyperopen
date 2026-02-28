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

(deftest api-submit-vault-transfer-rejects-when-wallet-is-disconnected-test
  (let [store (atom {:wallet {:agent {:status :ready}}
                     :vaults-ui {:vault-transfer-modal {:submitting? true}}})
        toast-calls (atom [])]
    (effects/api-submit-vault-transfer!
     {:store store
      :request {:vault-address "0x1234567890abcdef1234567890abcdef12345678"
                :action {:type "vaultTransfer"
                         :vaultAddress "0x1234567890abcdef1234567890abcdef12345678"
                         :isDeposit true
                         :usd 1000000}}
      :show-toast! (fn [_store kind message]
                     (swap! toast-calls conj [kind message]))})
    (is (= false (get-in @store [:vaults-ui :vault-transfer-modal :submitting?])))
    (is (= "Connect your wallet before submitting a deposit."
           (get-in @store [:vaults-ui :vault-transfer-modal :error])))
    (is (= [[:error "Connect your wallet before submitting a deposit."]]
           @toast-calls))))

(deftest api-submit-vault-transfer-resets-modal-and-refreshes-vault-state-on-success-test
  (async done
    (let [request {:vault-address "0x1234567890abcdef1234567890abcdef12345678"
                   :action {:type "vaultTransfer"
                            :vaultAddress "0x1234567890abcdef1234567890abcdef12345678"
                            :isDeposit false
                            :usd 0}}
          store (atom {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                :agent {:status :ready}}
                       :vaults-ui {:vault-transfer-modal {:open? true
                                                          :submitting? true
                                                          :mode :withdraw
                                                          :vault-address "0x1234567890abcdef1234567890abcdef12345678"}}})
          submit-calls (atom [])
          toast-calls (atom [])
          dispatch-calls (atom [])]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request request
            :submit-vault-transfer! (fn [_store address action]
                                      (swap! submit-calls conj [address action])
                                      (js/Promise.resolve {:status "ok"}))
            :show-toast! (fn [_store kind message]
                           (swap! toast-calls conj [kind message]))
            :dispatch! (fn [store* ctx actions]
                         (swap! dispatch-calls conj [store* ctx actions]))
            :default-vault-transfer-modal-state (fn []
                                                  {:open? false
                                                   :submitting? false
                                                   :mode :deposit
                                                   :vault-address nil
                                                   :amount-input ""
                                                   :withdraw-all? false
                                                   :error nil})})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= [["0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                            {:type "vaultTransfer"
                             :vaultAddress "0x1234567890abcdef1234567890abcdef12345678"
                             :isDeposit false
                             :usd 0}]]
                          @submit-calls))
                   (is (= {:open? false
                           :submitting? false
                           :mode :deposit
                           :vault-address nil
                           :amount-input ""
                           :withdraw-all? false
                           :error nil}
                          (get-in @store [:vaults-ui :vault-transfer-modal])))
                   (is (= [[:success "Withdraw submitted."]]
                          @toast-calls))
                   (is (= [[store nil [[:actions/load-vault-detail "0x1234567890abcdef1234567890abcdef12345678"]]]
                           [store nil [[:actions/load-vaults]]]]
                          @dispatch-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault transfer success-path error")
                    (done)))))))
