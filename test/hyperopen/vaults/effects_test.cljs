(ns hyperopen.vaults.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.vaults.effects :as effects]))

(def vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def wallet-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- route-scoped-request-opts
  [opts]
  (dissoc opts :active?-fn))

(defn- reset-vault-effect-flights!
  []
  (reset! @#'hyperopen.vaults.effects/vault-index-with-cache-flight nil)
  (reset! @#'hyperopen.vaults.effects/vault-summaries-flight nil))

(defn- next-macrotask!
  []
  (js/Promise.
   (fn [resolve _reject]
     (js/setTimeout resolve 0))))

(deftest api-fetch-vault-index-applies-begin-and-success-projections-test
  (async done
    (let [request-calls (atom [])
          persist-calls (atom [])
          store (atom {:router {:path "/vaults"}
                       :vaults {:index-rows [{:vault-address "0xstale"}]
                                :index-cache {:etag "\"etag-0\""
                                              :last-modified "Thu, 20 Mar 2026 11:00:00 GMT"}}})]
      (-> (effects/api-fetch-vault-index!
           {:store store
            :request-vault-index-response! (fn [opts]
                                             (swap! request-calls conj opts)
                                             (js/Promise.resolve
                                              {:status :ok
                                               :rows [{:vault-address "0x1"}]
                                               :etag "\"etag-1\""
                                               :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-success (fn [state response]
                                         (assoc state :index-response response))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))
            :persist-vault-index-cache-record! (fn [rows metadata]
                                                (swap! persist-calls conj [rows metadata])
                                                (js/Promise.resolve true))
            :opts {:priority :high}})
          (.then (fn [response]
                   (is (= {:status :ok
                           :rows [{:vault-address "0x1"}]
                           :etag "\"etag-1\""
                           :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}
                          response))
                   (let [opts (first @request-calls)]
                     (is (= {:priority :high
                             :fetch-opts {:headers {"If-None-Match" "\"etag-0\""
                                                    "If-Modified-Since" "Thu, 20 Mar 2026 11:00:00 GMT"}}}
                            (route-scoped-request-opts opts)))
                     (is (fn? (:active?-fn opts)))
                     (is (true? ((:active?-fn opts))))
                     (swap! store assoc-in [:router :path] (str "/vaults/" vault-address))
                     (is (true? ((:active?-fn opts))))
                     (swap! store assoc-in [:router :path] "/trade")
                     (is (false? ((:active?-fn opts)))))
                   (is (= true (:index-loading? @store)))
                   (is (= response (:index-response @store)))
                   (is (= [[[{:vault-address "0x1"}]
                            {:etag "\"etag-1\""
                             :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}]]
                          @persist-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault index error")
                    (done)))))))

(deftest api-fetch-vault-index-persists-bounded-startup-preview-on-success-test
  (async done
    (let [preview-persist-calls (atom [])
          store (atom {:router {:path "/vaults"}
                       :vaults {:index-rows []}})
          rows (vec (for [idx (range 30)]
                      {:vault-address (str "0x" idx)
                       :name (str "Vault " idx)}))]
      (-> (effects/api-fetch-vault-index!
           {:store store
            :request-vault-index-response! (fn [opts]
                                             (is (= {} (route-scoped-request-opts opts)))
                                             (is (fn? (:active?-fn opts)))
                                             (js/Promise.resolve {:status :ok
                                                                  :rows rows}))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-success (fn [state response]
                                         (assoc-in state [:vaults :index-rows] (:rows response)))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))
            :persist-vault-startup-preview-record! (fn [preview-state]
                                                    (swap! preview-persist-calls conj preview-state)
                                                    true)
            :persist-vault-index-cache-record! (fn [_rows _metadata]
                                                (js/Promise.resolve true))})
          (.then (fn [response]
                   (is (= 30 (count (:rows response))))
                   (is (= 1 (count @preview-persist-calls)))
                   (is (= rows (get-in (first @preview-persist-calls)
                                       [:vaults :index-rows])))
                   (is (= rows (get-in @store [:vaults :index-rows])))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault preview persistence error")
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
            :apply-vault-details-success (fn [state vault-address user-address payload]
                                           (assoc state :detail [vault-address user-address payload]))
            :apply-vault-details-error (fn [state vault-address err]
                                         (assoc state :detail-error [vault-address err]))})
          (.then (fn [_payload]
                   (let [[requested-vault-address opts] (first @request-calls)]
                     (is (= "0x1234567890abcdef1234567890abcdef12345678"
                            requested-vault-address))
                     (is (= {:user "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
                            (route-scoped-request-opts opts)))
                     (is (fn? (:active?-fn opts)))
                     (is (true? ((:active?-fn opts))))
                     (swap! store assoc-in [:router :path] (str "/vaults/" wallet-address))
                     (is (false? ((:active?-fn opts)))))
                   (is (= "0x1234567890abcdef1234567890abcdef12345678"
                          (:begin-vault-address @store)))
                   (is (= ["0x1234567890abcdef1234567890abcdef12345678"
                           "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
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
                                    (:start-time-ms opts))))
                     (is (fn? (:active?-fn opts))))
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
                   (let [[requested-vault-address start-time-ms end-time-ms opts]
                         (first @request-calls)]
                     (is (= "0x1234567890abcdef1234567890abcdef12345678"
                            requested-vault-address))
                     (is (nil? start-time-ms))
                     (is (nil? end-time-ms))
                     (is (= {} (route-scoped-request-opts opts)))
                     (is (fn? (:active?-fn opts))))
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
            :apply-vault-details-success (fn [state _vault-address _user-address _payload]
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
            :request-vault-index-response! (fn [opts]
                                             (swap! request-calls conj opts)
                                             (js/Promise.resolve {:status :ok
                                                                  :rows []}))
            :begin-vault-index-load (fn [state]
                                      (assoc state :portfolio-index-loading? true))
            :apply-vault-index-success (fn [state response]
                                         (assoc state :portfolio-index response))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :portfolio-index-error err))
            :opts {:skip-route-gate? true
                   :cache-ttl-ms 2500}})
          (.then (fn [response]
                   (is (= {:status :ok
                           :rows []}
                          response))
                   (is (= [{:cache-ttl-ms 2500}] @request-calls))
                   (is (= true (:portfolio-index-loading? @store)))
                   (is (= response (:portfolio-index @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected portfolio vault index error")
                    (done)))))))

(deftest api-fetch-vault-index-with-cache-starts-conditional-request-before-full-cache-hydration-test
  (async done
    (reset-vault-effect-flights!)
    (let [request-calls (atom [])
          persist-calls (atom [])
          resolve-cache! (atom nil)
          store (atom {:router {:path "/vaults"}
                       :vaults {:index-rows []}})
          metadata-record {:saved-at-ms 1700000000000
                           :etag "\"etag-cache\""
                           :last-modified "Thu, 20 Mar 2026 10:00:00 GMT"}
          cache-record {:rows [{:vault-address "0xcache"}]
                        :saved-at-ms 1700000000000
                        :etag "\"etag-cache\""
                        :last-modified "Thu, 20 Mar 2026 10:00:00 GMT"}
          result-promise
          (effects/api-fetch-vault-index-with-cache!
           {:store store
            :load-vault-index-cache-metadata! (fn []
                                                (js/Promise.resolve metadata-record))
            :load-vault-index-cache-record! (fn []
                                              (js/Promise.
                                               (fn [resolve _reject]
                                                 (reset! resolve-cache! resolve))))
            :request-vault-index-response! (fn [opts]
                                             (swap! request-calls conj opts)
                                             (js/Promise.resolve
                                              {:status :not-modified
                                               :etag "\"etag-cache-2\""
                                               :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}))
            :persist-vault-index-cache-record! (fn [rows metadata]
                                                (swap! persist-calls conj [rows metadata])
                                                (js/Promise.resolve true))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-cache-hydration (fn [state record]
                                                (-> state
                                                    (assoc :hydrated-cache record)
                                                    (assoc-in [:vaults :index-rows] (:rows record))
                                                    (assoc-in [:vaults :index-cache] {:etag (:etag record)
                                                                                      :last-modified (:last-modified record)})))
            :apply-vault-index-success (fn [state response]
                                         (assoc state :index-response response))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))})]
      (-> (next-macrotask!)
          (.then (fn [_]
                   (is (= 1 (count @request-calls)))
                   (is (nil? (:hydrated-cache @store)))
                   (let [opts (first @request-calls)]
                     (is (= {:fetch-opts {:headers {"If-None-Match" "\"etag-cache\""
                                                   "If-Modified-Since" "Thu, 20 Mar 2026 10:00:00 GMT"}}}
                            (route-scoped-request-opts opts)))
                     (is (fn? (:active?-fn opts))))
                   ((deref resolve-cache!) cache-record)
                   result-promise))
          (.then (fn [response]
                   (is (= cache-record (:hydrated-cache @store)))
                   (is (= {:status :not-modified
                           :etag "\"etag-cache-2\""
                           :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}
                          response))
                   (is (= [{:vault-address "0xcache"}]
                          (get-in @store [:vaults :index-rows])))
                   (is (= [[[{:vault-address "0xcache"}]
                            {:etag "\"etag-cache-2\""
                             :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}]]
                          @persist-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected cache-backed vault index error")
                    (done)))))))

(deftest api-fetch-vault-index-with-cache-skips-full-cache-restore-while-startup-preview-is-visible-test
  (async done
    (reset-vault-effect-flights!)
    (let [request-calls (atom [])
          persist-calls (atom [])
          cache-load-calls (atom 0)
          metadata-record {:saved-at-ms 1700000000000
                           :etag "\"etag-preview\""
                           :last-modified "Thu, 20 Mar 2026 10:00:00 GMT"}
          store (atom {:router {:path "/vaults"}
                       :vaults {:index-rows []
                                :startup-preview {:protocol-rows [{:vault-address "0xpreview"}]
                                                  :user-rows []}}})]
      (-> (effects/api-fetch-vault-index-with-cache!
           {:store store
            :load-vault-index-cache-metadata! (fn []
                                                (js/Promise.resolve metadata-record))
            :load-vault-index-cache-record! (fn []
                                              (swap! cache-load-calls inc)
                                              (js/Promise.resolve nil))
            :request-vault-index-response! (fn [opts]
                                             (swap! request-calls conj opts)
                                             (js/Promise.resolve
                                              {:status :ok
                                               :rows [{:vault-address "0xlive"}]
                                               :etag "\"etag-live\""
                                               :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}))
            :persist-vault-index-cache-record! (fn [rows metadata]
                                                (swap! persist-calls conj [rows metadata])
                                                (js/Promise.resolve true))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-cache-hydration (fn [state record]
                                                (assoc state :hydrated-cache record))
            :apply-vault-index-success (fn [state response]
                                         (assoc-in state [:vaults :index-rows] (:rows response)))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))})
          (.then (fn [response]
                   (is (= 0 @cache-load-calls))
                   (is (nil? (:hydrated-cache @store)))
                   (is (= {:status :ok
                           :rows [{:vault-address "0xlive"}]
                           :etag "\"etag-live\""
                           :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}
                          response))
                   (is (= [{:vault-address "0xlive"}]
                          (get-in @store [:vaults :index-rows])))
                   (let [opts (first @request-calls)]
                     (is (= {:fetch-opts {:headers {"If-None-Match" "\"etag-preview\""
                                                   "If-Modified-Since" "Thu, 20 Mar 2026 10:00:00 GMT"}}}
                            (route-scoped-request-opts opts))))
                   (is (= [[[{:vault-address "0xlive"}]
                            {:etag "\"etag-live\""
                             :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}]]
                          @persist-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected startup-preview vault index error")
                    (done)))))))

(deftest api-fetch-vault-index-with-cache-keeps-live-rows-when-late-cache-hydration-finishes-test
  (async done
    (reset-vault-effect-flights!)
    (let [persist-calls (atom [])
          resolve-cache! (atom nil)
          store (atom {:router {:path "/vaults"}
                       :vaults {:index-rows []}})
          cache-record {:rows [{:vault-address "0xcache"}]
                        :saved-at-ms 1700000000000
                        :etag "\"etag-cache\""
                        :last-modified "Thu, 20 Mar 2026 10:00:00 GMT"}]
      (-> (effects/api-fetch-vault-index-with-cache!
           {:store store
            :load-vault-index-cache-metadata! (fn []
                                                (js/Promise.resolve nil))
            :load-vault-index-cache-record! (fn []
                                              (js/Promise.
                                               (fn [resolve _reject]
                                                 (reset! resolve-cache! resolve))))
            :request-vault-index-response! (fn [_opts]
                                             (js/Promise.resolve
                                              {:status :ok
                                               :rows [{:vault-address "0xlive"}]
                                               :etag "\"etag-live\""
                                               :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}))
            :persist-vault-index-cache-record! (fn [rows metadata]
                                                (swap! persist-calls conj [rows metadata])
                                                (js/Promise.resolve true))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-cache-hydration (fn [state record]
                                                (-> state
                                                    (assoc :hydrated-cache record)
                                                    (assoc-in [:vaults :index-rows] (:rows record))))
            :apply-vault-index-success (fn [state response]
                                         (-> state
                                             (assoc :index-response response)
                                             (assoc-in [:vaults :index-rows] (:rows response))))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))})
          (.then (fn [response]
                   (is (= {:status :ok
                           :rows [{:vault-address "0xlive"}]
                           :etag "\"etag-live\""
                           :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}
                          response))
                   (is (= [{:vault-address "0xlive"}]
                          (get-in @store [:vaults :index-rows])))
                   ((deref resolve-cache!) cache-record)
                   (-> (js/Promise.resolve nil)
                       (.then (fn [_]
                                (is (= [{:vault-address "0xlive"}]
                                       (get-in @store [:vaults :index-rows])))
                                (is (nil? (:hydrated-cache @store)))
                                (is (= [[[{:vault-address "0xlive"}]
                                         {:etag "\"etag-live\""
                                          :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}]]
                                       @persist-calls))
                                (done))))))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected late-cache vault index error")
                    (done)))))))

(deftest api-fetch-vault-index-with-cache-keeps-hydrated-rows-when-request-fails-test
  (async done
    (reset-vault-effect-flights!)
    (let [persist-calls (atom [])
          store (atom {:router {:path "/vaults"}
                       :vaults {:index-rows []}})
          cache-record {:rows [{:vault-address "0xcache"}]
                        :saved-at-ms 1700000000000
                        :etag "\"etag-cache\""
                        :last-modified "Thu, 20 Mar 2026 10:00:00 GMT"}]
      (-> (effects/api-fetch-vault-index-with-cache!
           {:store store
            :load-vault-index-cache-metadata! (fn []
                                                (js/Promise.resolve nil))
            :load-vault-index-cache-record! (fn []
                                              (js/Promise.resolve cache-record))
            :request-vault-index-response! (fn [_opts]
                                             (js/Promise.reject (js/Error. "index-boom")))
            :persist-vault-index-cache-record! (fn [rows metadata]
                                                (swap! persist-calls conj [rows metadata])
                                                (js/Promise.resolve true))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-cache-hydration (fn [state record]
                                                (-> state
                                                    (assoc-in [:vaults :index-rows] (:rows record))
                                                    (assoc-in [:vaults :index-cache] {:etag (:etag record)
                                                                                      :last-modified (:last-modified record)})))
            :apply-vault-index-success (fn [state response]
                                         (assoc state :index-response response))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error (.-message err)))})
          (.then (fn [_]
                   (is false "Expected vault index request to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= "index-boom" (.-message err)))
                    (is (= [{:vault-address "0xcache"}]
                           (get-in @store [:vaults :index-rows])))
                    (is (= "index-boom" (:index-error @store)))
                    (is (= [] @persist-calls))
                    (done)))))))

(deftest api-fetch-vault-index-with-cache-reuses-inflight-request-test
  (async done
    (reset-vault-effect-flights!)
    (let [request-calls (atom 0)
          resolve-request! (atom nil)
          store (atom {:router {:path "/vaults"}
                       :vaults {:index-rows []}})
          deps {:store store
                :load-vault-index-cache-metadata! (fn []
                                                    (js/Promise.resolve nil))
                :load-vault-index-cache-record! (fn []
                                                  (js/Promise.resolve nil))
                :request-vault-index-response! (fn [_opts]
                                                 (swap! request-calls inc)
                                                 (js/Promise.
                                                  (fn [resolve _reject]
                                                    (reset! resolve-request! resolve))))
                :persist-vault-index-cache-record! (fn [_rows _metadata]
                                                    (js/Promise.resolve true))
                :begin-vault-index-load (fn [state]
                                          (assoc state :index-loading? true))
                :apply-vault-index-cache-hydration (fn [state _record]
                                                    state)
                :apply-vault-index-success (fn [state response]
                                             (assoc state :index-response response))
                :apply-vault-index-error (fn [state err]
                                           (assoc state :index-error err))}]
      (let [first-promise (effects/api-fetch-vault-index-with-cache! deps)
            second-promise (effects/api-fetch-vault-index-with-cache! deps)]
        (-> (next-macrotask!)
            (.then (fn [_]
                     (is (= 1 @request-calls))
                     ((deref resolve-request!) {:status :ok
                                                :rows [{:vault-address "0x1"}]
                                                :etag "\"etag-1\""
                                                :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"})
                     (js/Promise.all #js [first-promise second-promise])))
            (.then (fn [responses]
                     (is (= 2 (alength responses)))
                     (is (= 1 @request-calls))
                     (is (= {:status :ok
                             :rows [{:vault-address "0x1"}]
                             :etag "\"etag-1\""
                             :last-modified "Thu, 20 Mar 2026 12:00:00 GMT"}
                            (:index-response @store)))
                     (done)))
            (.catch (fn [err]
                      (js/console.error err)
                      (is false "Unexpected inflight vault index error")
                      (done))))))))

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

(deftest api-fetch-vault-summaries-reuses-inflight-request-test
  (async done
    (reset-vault-effect-flights!)
    (let [request-calls (atom 0)
          resolve-request! (atom nil)
          store (atom {:router {:path "/vaults"}})
          deps {:store store
                :request-vault-summaries! (fn [_opts]
                                            (swap! request-calls inc)
                                            (js/Promise.
                                             (fn [resolve _reject]
                                               (reset! resolve-request! resolve))))
                :begin-vault-summaries-load (fn [state]
                                              (assoc state :summaries-loading? true))
                :apply-vault-summaries-success (fn [state rows]
                                                 (assoc state :summaries rows))
                :apply-vault-summaries-error (fn [state err]
                                               (assoc state :summaries-error err))}]
      (let [first-promise (effects/api-fetch-vault-summaries! deps)
            second-promise (effects/api-fetch-vault-summaries! deps)]
        (-> (next-macrotask!)
            (.then (fn [_]
                     (is (= 1 @request-calls))
                     ((deref resolve-request!) [{:vault-address "0x1"}])
                     (js/Promise.all #js [first-promise second-promise])))
            (.then (fn [_responses]
                     (is (= 1 @request-calls))
                     (is (= [{:vault-address "0x1"}] (:summaries @store)))
                     (done)))
            (.catch (fn [err]
                      (js/console.error err)
                      (is false "Unexpected inflight vault summaries error")
                      (done))))))))

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
            :apply-vault-details-success (fn [state requested-vault-address requested-user-address payload]
                                           (assoc state :override-detail [requested-vault-address requested-user-address payload]))
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
                   (is (= [vault-address nil {:name "Override Detail"}]
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
                   (let [[requested-vault-address opts] (first @request-calls)]
                     (is (= vault-address requested-vault-address))
                     (is (= {:limit 25}
                            (route-scoped-request-opts opts)))
                     (is (fn? (:active?-fn opts))))
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
    (is (= "Unlock trading before submitting a withdraw."
           (get-in @store [:vaults-ui :vault-transfer-modal :error])))
    (is (= [[:error "Unlock trading before submitting a withdraw."]]
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
