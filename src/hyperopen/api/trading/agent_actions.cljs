(ns hyperopen.api.trading.agent-actions
  (:require [clojure.string :as str]
            [hyperopen.api.trading.http :as http]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.trading-crypto-modules :as trading-crypto-modules]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn safe-private-key->agent-address
  ([private-key]
   (when-let [crypto (trading-crypto-modules/resolved-trading-crypto)]
     (safe-private-key->agent-address crypto private-key)))
  ([crypto private-key]
    (try
      (some-> private-key
              ((:private-key->agent-address crypto))
              http/normalize-address)
      (catch :default _
        nil))))

(defn- missing-api-wallet-session-disposition!
  [owner-address session]
  (let [agent-address* (http/normalize-address (:agent-address session))]
    (if-not (seq agent-address*)
      (js/Promise.resolve :invalidate)
      (-> (http/fetch-user-role! agent-address*)
          (.then (fn [role-response]
                   (if (http/user-role-agent-for-owner? owner-address role-response)
                     :verified-agent
                     :invalidate)))
          ;; Preserve local key if lookup itself fails (network/rate-limit),
          ;; because we could not prove the session is invalid.
          (.catch (fn [_]
                    (js/Promise.resolve :inconclusive)))))))

(defn should-invalidate-missing-api-wallet-session!
  [owner-address session]
  (-> (missing-api-wallet-session-disposition! owner-address session)
      (.then #(= :invalidate %))))

(defn- reconcile-session-agent-address!
  [store owner-address storage-mode local-protection-mode session crypto]
  (let [stored-address* (http/normalize-address (:agent-address session))
        derived-address* (safe-private-key->agent-address crypto (:private-key session))
        needs-update? (and (seq derived-address*)
                           (not= stored-address* derived-address*))
        local-protection-mode* (agent-session/normalize-local-protection-mode
                                local-protection-mode)
        session* (cond-> (assoc session
                                :storage-mode storage-mode
                                :local-protection-mode local-protection-mode*)
                   (seq derived-address*) (assoc :agent-address derived-address*))]
    (when (and needs-update?
               (seq owner-address))
      (when-not (and (= :local storage-mode)
                     (= :passkey local-protection-mode*))
        (agent-session/persist-agent-session-by-mode! owner-address storage-mode session*))
      (swap! store update-in [:wallet :agent] merge {:agent-address derived-address*}))
    session*))

(defn- current-local-protection-mode
  [agent-state]
  (agent-session/normalize-local-protection-mode
   (:local-protection-mode agent-state)))

(defn- resolve-agent-session
  ([store owner-address]
   (resolve-agent-session store owner-address nil))
  ([store owner-address crypto]
   (let [agent-state (get-in @store [:wallet :agent] {})
         storage-mode (agent-session/normalize-storage-mode (:storage-mode agent-state))
         local-protection-mode (current-local-protection-mode agent-state)]
     (cond
       (and (= :local storage-mode)
            (= :passkey local-protection-mode))
       (when-let [metadata (agent-session/load-passkey-session-metadata owner-address)]
         (when-let [session (agent-lockbox/load-unlocked-session owner-address)]
           (merge metadata
                  session
                  {:storage-mode storage-mode
                   :local-protection-mode local-protection-mode})))

       :else
       (let [session (agent-session/load-agent-session-by-mode owner-address storage-mode)]
         (when (map? session)
           (let [session* (if crypto
                            (reconcile-session-agent-address! store
                                                              owner-address
                                                              storage-mode
                                                              local-protection-mode
                                                              session
                                                              crypto)
                            session)]
             (agent-lockbox/cache-unlocked-session! owner-address session*)
             (assoc session*
                    :storage-mode storage-mode
                     :local-protection-mode local-protection-mode))))))))

(defn- persist-agent-nonce-cursor!
  [store owner-address session nonce]
  (let [storage-mode (:storage-mode session)
        local-protection-mode (agent-session/normalize-local-protection-mode
                               (:local-protection-mode session))
        updated-session (assoc session :nonce-cursor nonce)]
    (if (and (= :local storage-mode)
             (= :passkey local-protection-mode))
      (when-let [metadata (agent-session/load-passkey-session-metadata owner-address)]
        (agent-session/persist-passkey-session-metadata!
         owner-address
         (assoc metadata
                :agent-address (:agent-address session)
                :last-approved-at (:last-approved-at session)
                :nonce-cursor nonce
                :saved-at-ms (platform/now-ms))))
      (agent-session/persist-agent-session-by-mode! owner-address storage-mode updated-session))
    (agent-lockbox/cache-unlocked-session! owner-address updated-session)
    (swap! store update-in [:wallet :agent] merge {:status :ready
                                                   :agent-address (:agent-address session)
                                                   :storage-mode storage-mode
                                                   :local-protection-mode local-protection-mode
                                                   :nonce-cursor nonce})))

(defn- invalidate-agent-session!
  [store owner-address session message]
  (let [storage-mode (:storage-mode session)
        local-protection-mode (agent-session/normalize-local-protection-mode
                               (:local-protection-mode session))
        passkey-supported? (true? (get-in @store [:wallet :agent :passkey-supported?]))]
    (agent-session/clear-persisted-agent-session! owner-address storage-mode local-protection-mode)
    (when (and (= :local storage-mode)
               (= :passkey local-protection-mode))
      (agent-lockbox/delete-locked-session! owner-address))
    (agent-lockbox/clear-unlocked-session! owner-address)
    (swap! store assoc-in [:wallet :agent]
           (assoc (agent-session/default-agent-state :storage-mode storage-mode
                                                     :local-protection-mode local-protection-mode
                                                     :passkey-supported? passkey-supported?)
                  :status :error
                  :error message))))

(defn- normalize-agent-action-options
  [options]
  (let [{:keys [vault-address expires-after is-mainnet max-nonce-retries]} (or options {})]
  {:vault-address (some-> vault-address str str/lower-case)
   :expires-after (if (contains? (or options {}) :expires-after)
                    expires-after
                    (+ (platform/now-ms)
                       runtime-state/agent-expires-after-ms))
   :is-mainnet (if (nil? is-mainnet) true is-mainnet)
   :max-nonce-retries (if (nil? max-nonce-retries) 1 max-nonce-retries)}))

(defn- agent-session-available?
  [session]
  (and (map? session)
       (seq (:private-key session))))

(defn- persist-agent-action-response!
  [store owner-address session nonce resp]
  (persist-agent-nonce-cursor! store owner-address session nonce)
  resp)

(defn- missing-api-wallet-result
  [resp invalidate?]
  (if invalidate?
    {:status "err"
     :error http/missing-api-wallet-error-message}
    {:status "err"
     :error (http/response-error-text resp)}))

(defn- resolve-missing-api-wallet-response!
  [store owner-address session resp disposition]
  (if (= :invalidate disposition)
    (invalidate-agent-session! store
                               owner-address
                               session
                               http/missing-api-wallet-error-message))
  (missing-api-wallet-result resp (= :invalidate disposition)))

(defn- handle-agent-action-response!
  [store owner-address session nonce resp retries-left retry-fn]
  (cond
    (and (pos? retries-left)
         (http/nonce-error-response? resp))
    (retry-fn)

    (http/missing-api-wallet-response? resp)
    (-> (missing-api-wallet-session-disposition! owner-address session)
        (.then (fn [disposition]
                 (resolve-missing-api-wallet-response!
                  store
                  owner-address
                  session
                  resp
                  disposition))))

    :else
    (persist-agent-action-response! store owner-address session nonce resp)))

(defn- sign-agent-action!
  [crypto session action nonce {:keys [vault-address expires-after is-mainnet]}]
  ((:sign-l1-action-with-private-key! crypto)
   (:private-key session)
   action
   nonce
   {:vault-address vault-address
    :expires-after expires-after
    :is-mainnet is-mainnet}))

(defn- post-signed-agent-action!
  [action nonce sig {:keys [vault-address expires-after]}]
  (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)]
    (-> (http/post-signed-action! action nonce {:r r :s s :v v}
                                  {:vault-address vault-address
                                   :expires-after expires-after})
        (.then http/parse-json!))))

(defn- missing-agent-session-rejection
  [store session]
  (when-not (agent-session-available? session)
    (let [agent-state (get-in @store [:wallet :agent] {})
          storage-mode (agent-session/normalize-storage-mode (:storage-mode agent-state))
          local-protection-mode (current-local-protection-mode agent-state)
          message (if (and (= :local storage-mode)
                           (= :passkey local-protection-mode))
                    "Trading is locked. Unlock Trading first."
                    "Agent session unavailable. Enable trading first.")]
      (js/Promise.reject (js/Error. message)))))

(defn- next-retry-callback [attempt! nonce retries-left]
  #(attempt! nonce (dec retries-left)))
(defn sign-and-post-agent-action!
  ([store owner-address action]
   (sign-and-post-agent-action! store owner-address action {}))
  ([store owner-address action raw-options]
   (let [options (normalize-agent-action-options raw-options)]
     (-> (trading-crypto-modules/load-trading-crypto-module!)
         (.then
          (fn [crypto]
            (let [session (resolve-agent-session store owner-address crypto)]
              (if-let [rejection (missing-agent-session-rejection store session)]
                rejection
                (letfn [(attempt! [cursor retries-left]
                          (let [nonce (http/next-nonce cursor)]
                            (-> (sign-agent-action! crypto session action nonce options)
                                (.then (fn [sig]
                                         (post-signed-agent-action! action nonce sig options)))
                                (.then (fn [resp]
                                         (handle-agent-action-response!
                                          store
                                          owner-address
                                          session
                                          nonce
                                          resp
                                          retries-left
                                          (next-retry-callback attempt! nonce retries-left)))))))]
                  (attempt! (or (:nonce-cursor session)
                                (get-in @store [:wallet :agent :nonce-cursor]))
                            (:max-nonce-retries options)))))))))))

(defn submit-order! [store address action] (sign-and-post-agent-action! store address action))
(defn cancel-order! [store address action] (sign-and-post-agent-action! store address action))
(defn submit-vault-transfer! [store address action] (sign-and-post-agent-action! store address action))
(defn schedule-cancel!
  [store address cancel-at-ms]
  (sign-and-post-agent-action! store
                               address
                               {:type "scheduleCancel"
                                :time cancel-at-ms}))
