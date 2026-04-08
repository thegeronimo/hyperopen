(ns hyperopen.vaults.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.vaults.application.transfer-state :as vault-transfer-state]
            [hyperopen.vaults.domain.identity :as vault-identity]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

(def ^:private funding-history-lookback-ms
  (* 90 24 60 60 1000))

(def ^:private preview-route-cache-hydration-delay-ms
  250)

(defonce ^:private vault-index-with-cache-flight
  (atom nil))

(defonce ^:private vault-summaries-flight
  (atom nil))

(defn- vault-list-route-active?
  [store]
  (let [path (get-in @store [:router :path] "")
        {:keys [kind]} (vault-routes/parse-vault-route path)]
    (or (contains? #{:list :detail} kind)
        (str/starts-with? (or path "") "/portfolio"))))

(defn- vault-startup-preview-route-active?
  [store]
  (= :list
     (:kind (vault-routes/parse-vault-route
             (get-in @store [:router :path] "")))))

(defn- vault-detail-route-active?
  [store]
  (= :detail
     (:kind (vault-routes/parse-vault-route
             (get-in @store [:router :path] "")))))

(defn- allow-route?
  [store opts detail-route?]
  (or (true? (:skip-route-gate? (or opts {})))
      (if detail-route?
        (vault-detail-route-active? store)
        (vault-list-route-active? store))))

(defn- request-opts
  [opts]
  (dissoc (or opts {}) :skip-route-gate?))

(defn- detail-route-active-for-vault?
  [store vault-address]
  (let [{:keys [kind current-vault-address]}
        (let [route (vault-routes/parse-vault-route
                     (get-in @store [:router :path] ""))]
          (assoc route :current-vault-address (:vault-address route)))
        requested-address (vault-identity/normalize-vault-address vault-address)
        route-address (vault-identity/normalize-vault-address current-vault-address)]
    (and (= :detail kind)
         (= requested-address route-address))))

(defn- route-active?-fn
  [store opts detail-route? vault-address]
  (when-not (true? (:skip-route-gate? (or opts {})))
    (if detail-route?
      (fn []
        (if vault-address
          (detail-route-active-for-vault? store vault-address)
          (vault-detail-route-active? store)))
      (fn []
        (vault-list-route-active? store)))))

(defn- route-scoped-request-opts
  ([store opts detail-route?]
   (route-scoped-request-opts store opts detail-route? nil))
  ([store opts detail-route? vault-address]
   (let [request-opts* (request-opts opts)]
     (if-let [active?-fn (route-active?-fn store opts detail-route? vault-address)]
       (assoc request-opts* :active?-fn active?-fn)
       request-opts*))))

(defn- ->promise
  [result]
  (if (instance? js/Promise result)
    result
    (js/Promise.resolve result)))

(defn- warn-cache-error!
  [message error]
  (js/console.warn message error))

(defn- with-single-flight!
  [flight-atom promise-fn]
  (if-let [existing @flight-atom]
    existing
    (let [promise (try
                    (->promise (promise-fn))
                    (catch :default error
                      (js/Promise.reject error)))]
      (reset! flight-atom promise)
      (.finally promise
                (fn []
                  (when (identical? @flight-atom promise)
                    (reset! flight-atom nil)))))))

(defn- assoc-fetch-header
  [opts header-name value]
  (if (seq value)
    (assoc-in opts [:fetch-opts :headers header-name] value)
    opts))

(defn- with-vault-index-validators
  [state-or-metadata opts]
  (let [{:keys [etag last-modified]}
        (if (contains? state-or-metadata :vaults)
          (get-in state-or-metadata [:vaults :index-cache] {})
          (or state-or-metadata {}))]
    (-> (or opts {})
        (assoc-fetch-header "If-None-Match" etag)
        (assoc-fetch-header "If-Modified-Since" last-modified))))

(defn- live-vault-index-rows-present?
  [store]
  (seq (get-in @store [:vaults :index-rows])))

(defn- begin-vault-index-load-if-needed!
  [store begin-vault-index-load]
  (when-not (true? (get-in @store [:vaults :loading :index?]))
    (swap! store begin-vault-index-load)))

(defn- begin-vault-summaries-load-if-needed!
  [store begin-vault-summaries-load]
  (when-not (true? (get-in @store [:vaults :loading :summaries?]))
    (swap! store begin-vault-summaries-load)))

(defn- startup-preview-present?
  [store]
  (let [preview (get-in @store [:vaults :startup-preview])]
    (or (seq (:protocol-rows preview))
        (seq (:user-rows preview)))))

(defn- load-cache-with-warning!
  [loader warn-message]
  (-> (->promise (when (fn? loader)
                   (loader)))
      (.catch (fn [error]
                (warn-cache-error! warn-message error)
                nil))))

(defn- persist-vault-index-cache-after-success!
  [{:keys [store
           persist-vault-index-cache-record!]}
   response]
  (if-not (fn? persist-vault-index-cache-record!)
    (js/Promise.resolve response)
    (let [rows (case (:status response)
                 :ok (:rows response)
                 :not-modified (get-in @store [:vaults :index-rows])
                 [])]
      (if (seq rows)
        (-> (->promise (persist-vault-index-cache-record! rows
                                                          {:etag (:etag response)
                                                           :last-modified (:last-modified response)}))
            (.catch (fn [error]
                      (warn-cache-error! "Failed to persist vault index cache:" error)
                      false))
            (.then (fn [_]
                     response)))
        (js/Promise.resolve response)))))

(defn- persist-vault-startup-preview-from-store!
  [{:keys [store
           persist-vault-startup-preview-record!]}
   result]
  (when (and (vault-startup-preview-route-active? store)
             (fn? persist-vault-startup-preview-record!))
    (let [rows (or (get-in @store [:vaults :merged-index-rows])
                   (get-in @store [:vaults :index-rows]))]
      (when (seq rows)
        (try
          (persist-vault-startup-preview-record! @store)
          (catch :default error
            (warn-cache-error! "Failed to persist vault startup preview cache:" error)))))
  result))

(defn- perform-vault-index-request!
  [{:keys [store
           request-vault-index-response!
           apply-vault-index-success
           apply-vault-index-error
           persist-vault-startup-preview-record!
           persist-vault-index-cache-record!]
    :as deps}
   validator-source
   opts]
  (-> (request-vault-index-response! (with-vault-index-validators validator-source opts))
      (.then (fn [response]
               (swap! store apply-vault-index-success response)
               response))
      (.then (fn [response]
               (persist-vault-startup-preview-from-store!
                {:store store
                 :persist-vault-startup-preview-record! persist-vault-startup-preview-record!}
                response)
               (persist-vault-index-cache-after-success!
                {:store store
                 :persist-vault-index-cache-record! persist-vault-index-cache-record!}
                response)))
      (.catch (promise-effects/apply-error-and-reject
               store
               apply-vault-index-error))))

(defn- hydrate-vault-index-cache-record!
  [{:keys [store
           apply-vault-index-cache-hydration]}
   live-status
   cache-hydrated?
   cache-record]
  (when (and cache-record
             (not @cache-hydrated?)
             (not= :ok @live-status))
    (swap! store apply-vault-index-cache-hydration cache-record)
    (reset! cache-hydrated? true))
  cache-record)

(defn- delay-promise!
  [ms]
  (js/Promise.
   (fn [resolve _reject]
     (platform/set-timeout! resolve ms))))

(defn api-fetch-vault-index!
  [{:keys [store
           request-vault-index-response!
           begin-vault-index-load
           apply-vault-index-success
           apply-vault-index-error
           persist-vault-startup-preview-record!
           persist-vault-index-cache-record!
           opts]}]
  (if (allow-route? store opts false)
    (let [request-opts* (route-scoped-request-opts store opts false)]
      (swap! store begin-vault-index-load)
      (perform-vault-index-request!
       {:store store
        :request-vault-index-response! request-vault-index-response!
        :apply-vault-index-success apply-vault-index-success
        :apply-vault-index-error apply-vault-index-error
        :persist-vault-startup-preview-record! persist-vault-startup-preview-record!
        :persist-vault-index-cache-record! persist-vault-index-cache-record!}
       @store
       request-opts*))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-index-with-cache!
  [{:keys [store
           request-vault-index-response!
           load-vault-index-cache-metadata!
           load-vault-index-cache-record!
           persist-vault-index-cache-record!
           persist-vault-startup-preview-record!
           begin-vault-index-load
           apply-vault-index-cache-hydration
           apply-vault-index-success
           apply-vault-index-error
           opts]}]
  (if (allow-route? store opts false)
    (let [request-opts* (route-scoped-request-opts store opts false)
          deps {:store store
                :apply-vault-index-cache-hydration apply-vault-index-cache-hydration
                :persist-vault-startup-preview-record! persist-vault-startup-preview-record!
                :persist-vault-index-cache-record! persist-vault-index-cache-record!}
          attach-success! (fn [response]
                            (-> (js/Promise.resolve response)
                                (.then (fn [response*]
                                         (swap! store apply-vault-index-success response*)
                                         response*))
                                (.then (fn [response*]
                                         (persist-vault-startup-preview-from-store!
                                          {:store store
                                           :persist-vault-startup-preview-record! persist-vault-startup-preview-record!}
                                          response*)
                                         (persist-vault-index-cache-after-success!
                                          {:store store
                                           :persist-vault-index-cache-record! persist-vault-index-cache-record!}
                                          response*)))))
          request-response! (fn [validator-source]
                              (request-vault-index-response!
                               (with-vault-index-validators validator-source request-opts*)))
          request! (fn [validator-source]
                     (-> (request-response! validator-source)
                         (.then attach-success!)))]
      (begin-vault-index-load-if-needed! store begin-vault-index-load)
      (with-single-flight!
       vault-index-with-cache-flight
       (fn []
         (if (live-vault-index-rows-present? store)
           (request! @store)
           (let [preview-present? (startup-preview-present? store)
                 live-status (atom :pending)
                 cache-hydrated? (atom false)
                 metadata-promise (load-cache-with-warning!
                                   load-vault-index-cache-metadata!
                                   "Failed to load vault index cache metadata:")
                request-promise (-> metadata-promise
                                    (.then (fn [cache-metadata]
                                             (request-response!
                                              (or cache-metadata
                                                   (get-in @store [:vaults :index-cache] {})
                                                   {})))))
                 full-cache-promise (when-not preview-present?
                                      (load-cache-with-warning!
                                       load-vault-index-cache-record!
                                       "Failed to load vault index cache:"))
                 hydrate-then-attach! (fn [cache-promise response]
                                        (-> (or cache-promise
                                                (js/Promise.resolve nil))
                                            (.then (fn [cache-record]
                                                     (hydrate-vault-index-cache-record!
                                                      deps
                                                      live-status
                                                      cache-hydrated?
                                                      cache-record)))
                                            (.then (fn [_]
                                                     (attach-success! response)))))]
             (when full-cache-promise
               (-> full-cache-promise
                   (.then (fn [cache-record]
                            (hydrate-vault-index-cache-record!
                             deps
                             live-status
                             cache-hydrated?
                             cache-record)))))
             (-> request-promise
                 (.then (fn [response]
                          (reset! live-status (:status response))
                          (case (:status response)
                           :not-modified
                            (if preview-present?
                              (-> (delay-promise! preview-route-cache-hydration-delay-ms)
                                  (.then (fn [_]
                                           (if (and (vault-startup-preview-route-active? store)
                                                    (not (live-vault-index-rows-present? store)))
                                             (load-cache-with-warning!
                                              load-vault-index-cache-record!
                                              "Failed to load vault index cache:")
                                             nil)))
                                  (.then (fn [cache-record]
                                           (hydrate-vault-index-cache-record!
                                            deps
                                            live-status
                                            cache-hydrated?
                                            cache-record)))
                                  (.then (fn [_]
                                           (attach-success! response))))
                              (hydrate-then-attach! full-cache-promise response))

                            (attach-success! response))))
                 (.catch (promise-effects/apply-error-and-reject
                          store
                          apply-vault-index-error))))))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-summaries!
  [{:keys [store
           request-vault-summaries!
           begin-vault-summaries-load
           apply-vault-summaries-success
           apply-vault-summaries-error
           opts]}]
  (if (allow-route? store opts false)
    (let [request-opts* (route-scoped-request-opts store opts false)]
      (begin-vault-summaries-load-if-needed! store begin-vault-summaries-load)
      (with-single-flight!
        vault-summaries-flight
        (fn []
          (-> (request-vault-summaries! request-opts*)
              (.then (promise-effects/apply-success-and-return
                      store
                      apply-vault-summaries-success))
              (.catch (promise-effects/apply-error-and-reject
                       store
                       apply-vault-summaries-error))))))
    (js/Promise.resolve nil)))

(defn api-fetch-user-vault-equities!
  [{:keys [store
           address
           request-user-vault-equities!
           begin-user-vault-equities-load
           apply-user-vault-equities-success
           apply-user-vault-equities-error
           opts]}]
  (if (allow-route? store opts false)
    (let [request-opts* (route-scoped-request-opts store opts false)]
      (swap! store begin-user-vault-equities-load)
      (-> (request-user-vault-equities! address request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-user-vault-equities-success))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-user-vault-equities-error))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-details!
  [{:keys [store
           vault-address
           user-address
           request-vault-details!
           begin-vault-details-load
           apply-vault-details-success
           apply-vault-details-error
           opts]}]
  (if (allow-route? store opts true)
    (let [request-opts* (cond-> (route-scoped-request-opts store opts true vault-address)
                          user-address (assoc :user user-address))]
      (swap! store begin-vault-details-load vault-address)
      (-> (request-vault-details! vault-address request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-details-success
                  vault-address
                  user-address))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-details-error
                   vault-address))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-benchmark-details!
  [{:keys [store
           vault-address
           request-vault-details!
           begin-vault-benchmark-details-load
           apply-vault-benchmark-details-success
           apply-vault-benchmark-details-error
           opts]}]
  (if (allow-route? store opts false)
    (let [request-opts* (route-scoped-request-opts store opts false)]
      (swap! store begin-vault-benchmark-details-load vault-address)
      (-> (request-vault-details! vault-address request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-benchmark-details-success
                  vault-address))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-benchmark-details-error
                   vault-address))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-webdata2!
  [{:keys [store
           vault-address
           request-vault-webdata2!
           begin-vault-webdata2-load
           apply-vault-webdata2-success
           apply-vault-webdata2-error
           opts]}]
  (if (allow-route? store opts true)
    (let [request-opts* (route-scoped-request-opts store opts true vault-address)]
      (swap! store begin-vault-webdata2-load vault-address)
      (-> (request-vault-webdata2! vault-address request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-webdata2-success
                  vault-address))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-webdata2-error
                   vault-address))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-fills!
  [{:keys [store
           vault-address
           request-user-fills!
           begin-vault-fills-load
           apply-vault-fills-success
           apply-vault-fills-error
           opts]}]
  (if (allow-route? store opts true)
    (let [request-opts* (route-scoped-request-opts store opts true vault-address)]
      (swap! store begin-vault-fills-load vault-address)
      (-> (request-user-fills! vault-address request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-fills-success
                  vault-address))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-fills-error
                   vault-address))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-funding-history!
  [{:keys [store
           vault-address
           request-user-funding-history!
           begin-vault-funding-history-load
           apply-vault-funding-history-success
           apply-vault-funding-history-error
           now-ms-fn
           opts]}]
  (if (allow-route? store opts true)
    (let [now-ms ((or now-ms-fn (fn []
                                  (.now js/Date))))
          start-time-ms (max 0 (- now-ms funding-history-lookback-ms))
          request-opts* (merge {:start-time-ms start-time-ms
                                :end-time-ms now-ms}
                               (route-scoped-request-opts store opts true vault-address))]
      (swap! store begin-vault-funding-history-load vault-address)
      (-> (request-user-funding-history! vault-address request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-funding-history-success
                  vault-address))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-funding-history-error
                   vault-address))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-order-history!
  [{:keys [store
           vault-address
           request-historical-orders!
           begin-vault-order-history-load
           apply-vault-order-history-success
           apply-vault-order-history-error
           opts]}]
  (if (allow-route? store opts true)
    (let [request-opts* (route-scoped-request-opts store opts true vault-address)]
      (swap! store begin-vault-order-history-load vault-address)
      (-> (request-historical-orders! vault-address request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-order-history-success
                  vault-address))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-order-history-error
                   vault-address))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-ledger-updates!
  [{:keys [store
           vault-address
           request-user-non-funding-ledger-updates!
           begin-vault-ledger-updates-load
           apply-vault-ledger-updates-success
           apply-vault-ledger-updates-error
           opts]}]
  (if (allow-route? store opts true)
    (let [request-opts* (route-scoped-request-opts store opts true vault-address)]
      (swap! store begin-vault-ledger-updates-load vault-address)
      (-> (request-user-non-funding-ledger-updates! vault-address nil nil request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-ledger-updates-success
                  vault-address))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-ledger-updates-error
                   vault-address))))
    (js/Promise.resolve nil)))

(defn- fallback-exchange-response-error
  [resp]
  (or (:error resp)
      (:message resp)
      (:response resp)
      "Unknown exchange error"))

(defn- fallback-runtime-error-message
  [err]
  (or (some-> err .-message)
      (str err)))

(defn- update-vault-transfer-error
  [state error-text]
  (-> state
      (assoc-in [:vaults-ui :vault-transfer-modal :submitting?] false)
      (assoc-in [:vaults-ui :vault-transfer-modal :error] error-text)))

(defn- set-vault-transfer-error!
  [store show-toast! error-text]
  (swap! store update-vault-transfer-error error-text)
  (show-toast! store :error error-text))

(defn- submit-mode-label
  [is-deposit?]
  (if is-deposit?
    "Deposit"
    "Withdraw"))

(defn api-submit-vault-transfer!
  [{:keys [store
           request
           dispatch!
           submit-vault-transfer!
           exchange-response-error
           runtime-error-message
           show-toast!
           default-vault-transfer-modal-state]
    :or {submit-vault-transfer! trading-api/submit-vault-transfer!
         exchange-response-error fallback-exchange-response-error
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)
         default-vault-transfer-modal-state vault-transfer-state/default-vault-transfer-modal-state}}]
  (let [state @store
        spectate-mode-message (account-context/mutations-blocked-message state)
        address (get-in state [:wallet :address])
        agent-status (get-in state [:wallet :agent :status])
        vault-address (or (vault-identity/normalize-vault-address (:vault-address request))
                          (vault-identity/normalize-vault-address (get-in request [:action :vaultAddress])))
        action (:action request)
        is-deposit? (true? (:isDeposit action))
        mode-label (submit-mode-label is-deposit?)]
    (cond
      (seq spectate-mode-message)
      (set-vault-transfer-error! store
                                 show-toast!
                                 spectate-mode-message)

      (nil? address)
      (set-vault-transfer-error! store
                                 show-toast!
                                 (str "Connect your wallet before submitting a " (str/lower-case mode-label) "."))

      (not= :ready agent-status)
      (set-vault-transfer-error! store
                                 show-toast!
                                 (case agent-status
                                   :locked (str "Unlock trading before submitting a " (str/lower-case mode-label) ".")
                                   :unlocking (str "Awaiting passkey before submitting a " (str/lower-case mode-label) ".")
                                   (str "Enable trading before submitting a " (str/lower-case mode-label) ".")))

      :else
      (-> (submit-vault-transfer! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (swap! store assoc-in [:vaults-ui :vault-transfer-modal]
                              (default-vault-transfer-modal-state))
                       (show-toast! store :success (str mode-label " submitted."))
                       (when (and (fn? dispatch!)
                                  (string? vault-address))
                         (dispatch! store nil [[:actions/load-vault-detail vault-address]])
                         (dispatch! store nil [[:actions/load-vaults]]))
                       resp)
                     (let [error-text (str/trim (str (exchange-response-error resp)))
                           message (str mode-label " failed: "
                                        (if (seq error-text) error-text "Unknown exchange error"))]
                       (set-vault-transfer-error! store show-toast! message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str mode-label " failed: "
                                       (if (seq error-text) error-text "Unknown runtime error"))]
                      (set-vault-transfer-error! store show-toast! message))))))))
