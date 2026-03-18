(ns hyperopen.vaults.effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.vaults.domain.identity :as vault-identity]
            [hyperopen.vaults.domain.transfer-policy :as vault-transfer-policy]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

(def ^:private funding-history-lookback-ms
  (* 90 24 60 60 1000))

(defn- vault-list-route-active?
  [store]
  (let [path (get-in @store [:router :path] "")
        {:keys [kind]} (vault-routes/parse-vault-route path)]
    (or (contains? #{:list :detail} kind)
        (str/starts-with? (or path "") "/portfolio"))))

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

(defn api-fetch-vault-index!
  [{:keys [store
           request-vault-index!
           begin-vault-index-load
           apply-vault-index-success
           apply-vault-index-error
           opts]}]
  (if (allow-route? store opts false)
    (let [request-opts* (request-opts opts)]
      (swap! store begin-vault-index-load)
      (-> (request-vault-index! request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-index-success))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-index-error))))
    (js/Promise.resolve nil)))

(defn api-fetch-vault-summaries!
  [{:keys [store
           request-vault-summaries!
           begin-vault-summaries-load
           apply-vault-summaries-success
           apply-vault-summaries-error
           opts]}]
  (if (allow-route? store opts false)
    (let [request-opts* (request-opts opts)]
      (swap! store begin-vault-summaries-load)
      (-> (request-vault-summaries! request-opts*)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-vault-summaries-success))
          (.catch (promise-effects/apply-error-and-reject
                   store
                   apply-vault-summaries-error))))
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
    (let [request-opts* (request-opts opts)]
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
    (let [request-opts* (cond-> (request-opts opts)
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
    (let [request-opts* (request-opts opts)]
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
    (let [request-opts* (request-opts opts)]
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
    (let [request-opts* (request-opts opts)]
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
                               (request-opts opts))]
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
    (let [request-opts* (request-opts opts)]
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
    (let [request-opts* (request-opts opts)]
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
         default-vault-transfer-modal-state vault-transfer-policy/default-vault-transfer-modal-state}}]
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
                                 (str "Enable trading before submitting a " (str/lower-case mode-label) "."))

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
