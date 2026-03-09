(ns hyperopen.vaults.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.vaults.effects :as effects]))

(def vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def wallet-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest api-fetch-vault-index-applies-begin-and-success-projections-test
  (async done
    (let [request-calls (atom [])
          store (atom {:router {:path "/vaults"}})]
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
          store (atom {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}})]
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
          store (atom {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}})]
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
          store (atom {:router {:path "/vaults/0x1234567890abcdef1234567890abcdef12345678"}})]
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

(deftest api-fetch-vault-details-skips-when-detail-route-is-inactive-test
  (async done
    (let [calls (atom 0)
          store (atom {:router {:path "/trade"}})]
      (-> (effects/api-fetch-vault-details!
           {:store store
            :vault-address "0x1234567890abcdef1234567890abcdef12345678"
            :request-vault-details! (fn [_vault-address _opts]
                                      (swap! calls inc)
                                      (js/Promise.resolve {:name "ignored"}))
            :begin-vault-details-load (fn [state _vault-address]
                                        (assoc state :begin? true))
            :apply-vault-details-success (fn [state _vault-address _payload]
                                           (assoc state :loaded? true))
            :apply-vault-details-error (fn [state _vault-address err]
                                         (assoc state :error err))})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (is (nil? (:begin? @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected inactive-route rejection")
                    (done)))))))

(deftest api-fetch-vault-index-allows-portfolio-route-and-strips-route-gate-opt-test
  (async done
    (let [request-calls (atom [])
          store (atom {:router {:path "/portfolio?vaults=1"}})]
      (-> (effects/api-fetch-vault-index!
           {:store store
            :request-vault-index! (fn [opts]
                                    (swap! request-calls conj opts)
                                    (js/Promise.resolve []))
            :begin-vault-index-load (fn [state]
                                      (assoc state :portfolio-index-loading? true))
            :apply-vault-index-success (fn [state rows]
                                         (assoc state :portfolio-index rows))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :portfolio-index-error err))
            :opts {:skip-route-gate? true
                   :cache-ttl-ms 2500}})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= [{:cache-ttl-ms 2500}] @request-calls))
                   (is (= true (:portfolio-index-loading? @store)))
                   (is (= [] (:portfolio-index @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected portfolio vault index error")
                    (done)))))))

(deftest api-fetch-vault-summaries-applies-error-projection-test
  (async done
    (let [store (atom {:router {:path "/vaults"}})]
      (-> (effects/api-fetch-vault-summaries!
           {:store store
            :request-vault-summaries! (fn [_opts]
                                        (js/Promise.reject (js/Error. "summary-boom")))
            :begin-vault-summaries-load (fn [state]
                                          (assoc state :summaries-loading? true))
            :apply-vault-summaries-success (fn [state rows]
                                             (assoc state :summaries rows))
            :apply-vault-summaries-error (fn [state err]
                                           (assoc state :summaries-error (.-message err)))})
          (.then (fn [_]
                   (is false "Expected vault summaries request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= "summary-boom" (.-message err)))
                    (is (= true (:summaries-loading? @store)))
                    (is (= "summary-boom" (:summaries-error @store)))
                    (done)))))))

(deftest api-fetch-vault-summaries-skips-when-list-route-is-inactive-test
  (async done
    (let [calls (atom 0)
          store (atom {:router {:path "/trade"}})]
      (-> (effects/api-fetch-vault-summaries!
           {:store store
            :request-vault-summaries! (fn [_opts]
                                        (swap! calls inc)
                                        (js/Promise.resolve []))
            :begin-vault-summaries-load (fn [state]
                                          (assoc state :summaries-loading? true))
            :apply-vault-summaries-success (fn [state rows]
                                             (assoc state :summaries rows))
            :apply-vault-summaries-error (fn [state err]
                                           (assoc state :summaries-error err))})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (is (nil? (:summaries-loading? @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected inactive list-route rejection")
                    (done)))))))

(deftest api-fetch-user-vault-equities-allows-skip-route-override-test
  (async done
    (let [request-calls (atom [])
          store (atom {:router {:path "/trade"}})]
      (-> (effects/api-fetch-user-vault-equities!
           {:store store
            :address wallet-address
            :request-user-vault-equities! (fn [address opts]
                                            (swap! request-calls conj [address opts])
                                            (js/Promise.resolve [{:vault-address vault-address
                                                                  :equity-usd 42}]))
            :begin-user-vault-equities-load (fn [state]
                                              (assoc state :equities-loading? true))
            :apply-user-vault-equities-success (fn [state rows]
                                                 (assoc state :equities rows))
            :apply-user-vault-equities-error (fn [state err]
                                               (assoc state :equities-error err))
            :opts {:skip-route-gate? true
                   :priority :high}})
          (.then (fn [rows]
                   (is (= [{:vault-address vault-address
                            :equity-usd 42}]
                          rows))
                   (is (= [[wallet-address {:priority :high}]]
                          @request-calls))
                   (is (= true (:equities-loading? @store)))
                   (is (= rows (:equities @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault equities error")
                    (done)))))))

(deftest api-fetch-vault-details-omits-user-address-and-allows-route-override-test
  (async done
    (let [request-calls (atom [])
          store (atom {:router {:path "/trade"}})]
      (-> (effects/api-fetch-vault-details!
           {:store store
            :vault-address vault-address
            :request-vault-details! (fn [requested-vault-address opts]
                                      (swap! request-calls conj [requested-vault-address opts])
                                      (js/Promise.resolve {:name "Override Detail"}))
            :begin-vault-details-load (fn [state requested-vault-address]
                                        (assoc state :override-detail-begin requested-vault-address))
            :apply-vault-details-success (fn [state requested-vault-address payload]
                                           (assoc state :override-detail [requested-vault-address payload]))
            :apply-vault-details-error (fn [state requested-vault-address err]
                                         (assoc state :override-detail-error [requested-vault-address err]))
            :opts {:skip-route-gate? true
                   :priority :low}})
          (.then (fn [payload]
                   (is (= {:name "Override Detail"} payload))
                   (is (= [[vault-address {:priority :low}]]
                          @request-calls))
                   (is (= vault-address
                          (:override-detail-begin @store)))
                   (is (= [vault-address {:name "Override Detail"}]
                          (:override-detail @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected route-override detail error")
                    (done)))))))

(deftest api-fetch-vault-webdata2-applies-error-projection-test
  (async done
    (let [store (atom {:router {:path (str "/vaults/" vault-address)}})]
      (-> (effects/api-fetch-vault-webdata2!
           {:store store
            :vault-address vault-address
            :request-vault-webdata2! (fn [_vault-address _opts]
                                       (js/Promise.reject (js/Error. "webdata-boom")))
            :begin-vault-webdata2-load (fn [state requested-vault-address]
                                         (assoc state :webdata-begin requested-vault-address))
            :apply-vault-webdata2-success (fn [state requested-vault-address payload]
                                            (assoc state :webdata-success [requested-vault-address payload]))
            :apply-vault-webdata2-error (fn [state requested-vault-address err]
                                          (assoc state :webdata-error [requested-vault-address (.-message err)]))})
          (.then (fn [_]
                   (is false "Expected vault webdata request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= "webdata-boom" (.-message err)))
                    (is (= vault-address (:webdata-begin @store)))
                    (is (= [vault-address "webdata-boom"]
                           (:webdata-error @store)))
                    (done)))))))

(deftest api-fetch-vault-fills-applies-success-projections-test
  (async done
    (let [request-calls (atom [])
          store (atom {:router {:path (str "/vaults/" vault-address)}})]
      (-> (effects/api-fetch-vault-fills!
           {:store store
            :vault-address vault-address
            :request-user-fills! (fn [requested-vault-address opts]
                                   (swap! request-calls conj [requested-vault-address opts])
                                   (js/Promise.resolve [{:coin "ETH"}]))
            :begin-vault-fills-load (fn [state requested-vault-address]
                                      (assoc state :fills-begin requested-vault-address))
            :apply-vault-fills-success (fn [state requested-vault-address rows]
                                         (assoc state :fills-success [requested-vault-address rows]))
            :apply-vault-fills-error (fn [state requested-vault-address err]
                                       (assoc state :fills-error [requested-vault-address err]))
            :opts {:limit 25}})
          (.then (fn [rows]
                   (is (= [{:coin "ETH"}] rows))
                   (is (= [[vault-address {:limit 25}]]
                          @request-calls))
                   (is (= vault-address (:fills-begin @store)))
                   (is (= [vault-address [{:coin "ETH"}]]
                          (:fills-success @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault fills error")
                    (done)))))))

(deftest api-fetch-vault-order-history-applies-error-projection-test
  (async done
    (let [store (atom {:router {:path (str "/vaults/" vault-address)}})]
      (-> (effects/api-fetch-vault-order-history!
           {:store store
            :vault-address vault-address
            :request-historical-orders! (fn [_vault-address _opts]
                                          (js/Promise.reject (js/Error. "order-history-boom")))
            :begin-vault-order-history-load (fn [state requested-vault-address]
                                              (assoc state :order-history-begin requested-vault-address))
            :apply-vault-order-history-success (fn [state requested-vault-address rows]
                                                 (assoc state :order-history-success [requested-vault-address rows]))
            :apply-vault-order-history-error (fn [state requested-vault-address err]
                                               (assoc state :order-history-error [requested-vault-address (.-message err)]))})
          (.then (fn [_]
                   (is false "Expected vault order history request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= "order-history-boom" (.-message err)))
                    (is (= vault-address (:order-history-begin @store)))
                    (is (= [vault-address "order-history-boom"]
                           (:order-history-error @store)))
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

(deftest api-submit-vault-transfer-blocks-mutations-while-spectate-mode-active-test
  (let [store (atom {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                              :agent {:status :ready}}
                     :account-context {:spectate-mode {:active? true
                                                    :address "0x1234567890abcdef1234567890abcdef12345678"}}
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
    (is (= account-context/spectate-mode-read-only-message
           (get-in @store [:vaults-ui :vault-transfer-modal :error])))
    (is (= [[:error account-context/spectate-mode-read-only-message]]
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

(deftest api-submit-vault-transfer-rejects-when-agent-is-not-ready-test
  (let [store (atom {:wallet {:address wallet-address
                              :agent {:status :locked}}
                     :vaults-ui {:vault-transfer-modal {:submitting? true}}})
        toast-calls (atom [])]
    (effects/api-submit-vault-transfer!
     {:store store
      :request {:vault-address vault-address
                :action {:type "vaultTransfer"
                         :vaultAddress vault-address
                         :isDeposit false
                         :usd 1000000}}
      :show-toast! (fn [_store kind message]
                     (swap! toast-calls conj [kind message]))})
    (is (= false (get-in @store [:vaults-ui :vault-transfer-modal :submitting?])))
    (is (= "Enable trading before submitting a withdraw."
           (get-in @store [:vaults-ui :vault-transfer-modal :error])))
    (is (= [[:error "Enable trading before submitting a withdraw."]]
           @toast-calls))))

(deftest api-submit-vault-transfer-surfaces-exchange-response-errors-test
  (async done
    (let [store (atom {:wallet {:address wallet-address
                                :agent {:status :ready}}
                       :vaults-ui {:vault-transfer-modal {:submitting? true}}})
          toast-calls (atom [])]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request {:vault-address vault-address
                      :action {:type "vaultTransfer"
                               :vaultAddress vault-address
                               :isDeposit true
                               :usd 1000000}}
            :submit-vault-transfer! (fn [_store _address _action]
                                      (js/Promise.resolve {:status "error"
                                                           :response " exchange said no "}))
            :show-toast! (fn [_store kind message]
                           (swap! toast-calls conj [kind message]))})
          (.then (fn [resp]
                   (is (= {:status "error"
                           :response " exchange said no "}
                          resp))
                   (is (= false (get-in @store [:vaults-ui :vault-transfer-modal :submitting?])))
                   (is (= "Deposit failed: exchange said no"
                          (get-in @store [:vaults-ui :vault-transfer-modal :error])))
                   (is (= [[:error "Deposit failed: exchange said no"]]
                          @toast-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false (str "Unexpected exchange-response failure: " err))
                    (done)))))))

(deftest api-submit-vault-transfer-falls-back-to-unknown-exchange-error-when-message-is-blank-test
  (async done
    (let [store (atom {:wallet {:address wallet-address
                                :agent {:status :ready}}
                       :vaults-ui {:vault-transfer-modal {:submitting? true}}})
          toast-calls (atom [])]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request {:vault-address vault-address
                      :action {:type "vaultTransfer"
                               :vaultAddress vault-address
                               :isDeposit false
                               :usd 1000000}}
            :submit-vault-transfer! (fn [_store _address _action]
                                      (js/Promise.resolve {:status "error"
                                                           :message "   "}))
            :show-toast! (fn [_store kind message]
                           (swap! toast-calls conj [kind message]))})
          (.then (fn [resp]
                   (is (= {:status "error"
                           :message "   "}
                          resp))
                   (is (= "Withdraw failed: Unknown exchange error"
                          (get-in @store [:vaults-ui :vault-transfer-modal :error])))
                   (is (= [[:error "Withdraw failed: Unknown exchange error"]]
                          @toast-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false (str "Unexpected blank-exchange-message failure: " err))
                    (done)))))))

(deftest api-submit-vault-transfer-surfaces-runtime-errors-test
  (async done
    (let [store (atom {:wallet {:address wallet-address
                                :agent {:status :ready}}
                       :vaults-ui {:vault-transfer-modal {:submitting? true}}})
          toast-calls (atom [])]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request {:vault-address vault-address
                      :action {:type "vaultTransfer"
                               :vaultAddress vault-address
                               :isDeposit true
                               :usd 1000000}}
            :submit-vault-transfer! (fn [_store _address _action]
                                      (js/Promise.reject "runtime-boom"))
            :show-toast! (fn [_store kind message]
                           (swap! toast-calls conj [kind message])
                           nil)})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= false (get-in @store [:vaults-ui :vault-transfer-modal :submitting?])))
                   (is (= "Deposit failed: runtime-boom"
                          (get-in @store [:vaults-ui :vault-transfer-modal :error])))
                   (is (= [[:error "Deposit failed: runtime-boom"]]
                          @toast-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false (str "Unexpected runtime rejection: " err))
                    (done)))))))

(deftest api-submit-vault-transfer-falls-back-to-unknown-runtime-error-when-message-is-blank-test
  (async done
    (let [store (atom {:wallet {:address wallet-address
                                :agent {:status :ready}}
                       :vaults-ui {:vault-transfer-modal {:submitting? true}}})
          toast-calls (atom [])]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request {:vault-address vault-address
                      :action {:type "vaultTransfer"
                               :vaultAddress vault-address
                               :isDeposit false
                               :usd 1000000}}
            :submit-vault-transfer! (fn [_store _address _action]
                                      (js/Promise.reject (js/Error. "ignored")))
            :runtime-error-message (fn [_err]
                                     "   ")
            :show-toast! (fn [_store kind message]
                           (swap! toast-calls conj [kind message])
                           nil)})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= "Withdraw failed: Unknown runtime error"
                          (get-in @store [:vaults-ui :vault-transfer-modal :error])))
                   (is (= [[:error "Withdraw failed: Unknown runtime error"]]
                          @toast-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false (str "Unexpected blank-runtime-message rejection: " err))
                    (done)))))))

(deftest api-submit-vault-transfer-success-skips-dispatch-when-vault-address-is-invalid-test
  (async done
    (let [store (atom {:wallet {:address wallet-address
                                :agent {:status :ready}}
                       :vaults-ui {:vault-transfer-modal {:submitting? true}}})
          dispatch-calls (atom [])
          toast-calls (atom [])]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request {:vault-address "not-a-vault"
                      :action {:type "vaultTransfer"
                               :vaultAddress "also-not-a-vault"
                               :isDeposit true
                               :usd 1000000}}
            :submit-vault-transfer! (fn [_store _address _action]
                                      (js/Promise.resolve {:status "ok"}))
            :dispatch! (fn [store* ctx actions]
                         (swap! dispatch-calls conj [store* ctx actions]))
            :show-toast! (fn [_store kind message]
                           (swap! toast-calls conj [kind message]))
            :default-vault-transfer-modal-state (fn []
                                                  {:open? false
                                                   :mode :deposit
                                                   :vault-address nil
                                                   :amount-input ""
                                                   :withdraw-all? false
                                                   :submitting? false
                                                   :error nil})})
          (.then (fn [resp]
                   (is (= {:status "ok"} resp))
                   (is (= {:open? false
                           :mode :deposit
                           :vault-address nil
                           :amount-input ""
                           :withdraw-all? false
                           :submitting? false
                           :error nil}
                          (get-in @store [:vaults-ui :vault-transfer-modal])))
                   (is (= [[:success "Deposit submitted."]]
                          @toast-calls))
                   (is (= [] @dispatch-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false (str "Unexpected invalid-address success-path error: " err))
                    (done)))))))
