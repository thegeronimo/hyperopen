(ns hyperopen.api.trading
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.utils.hl-signing :as signing]))

(def exchange-url "https://api.hyperliquid.xyz/exchange")
(def info-url "https://api.hyperliquid.xyz/info")

(defn- json-post! [url body]
  (js/fetch url
            (clj->js {:method "POST"
                      :headers {"Content-Type" "application/json"}
                      :body (js/JSON.stringify (clj->js body))})))

(defn- parse-json! [resp]
  (-> (.json resp)
      (.then (fn [payload]
               (let [parsed (js->clj payload :keywordize-keys true)]
                 (when (contracts/validation-enabled?)
                   (contracts/assert-exchange-response!
                    parsed
                    {:boundary :api-trading/parse-json}))
                 parsed)))))

(defn- nonce-error-response? [resp]
  (let [text (-> (or (:error resp)
                     (:response resp)
                     (:message resp)
                     "")
                 str
                 str/lower-case)]
    (and (or (= "err" (:status resp))
             (seq text))
         (str/includes? text "nonce"))))

(defn- response-error-text
  [resp]
  (-> (or (:error resp)
          (:response resp)
          (:message resp)
          "")
      str))

(defn- missing-api-wallet-response?
  [resp]
  (let [text (-> (response-error-text resp)
                 str/lower-case)]
    (and (str/includes? text "user or api wallet")
         (str/includes? text "does not exist"))))

(def ^:private missing-api-wallet-error-message
  "Agent wallet not recognized by Hyperliquid. Enable Trading again.")

(def ^:private missing-api-wallet-preserved-message
  "Agent wallet lookup was inconclusive. Preserved local trading key.")

(defn- normalize-address
  [address]
  (let [text (some-> address str str/trim)]
    (when (seq text)
      (str/lower-case text))))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- normalize-display-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- named-dex-market?
  [market]
  (seq (normalize-display-text (:dex market))))

(defn- market-asset-id
  [market]
  (let [market* (or market {})
        explicit-asset-id (some parse-int-value
                                [(:asset-id market*)
                                 (:assetId market*)])
        idx (some parse-int-value [(:idx market*)])
        named-dex? (named-dex-market? market*)]
    (or explicit-asset-id
        (when (and (some? idx)
                   (not named-dex?))
          idx))))

(defn- normalize-cancel-order-coin
  [order]
  (let [coin (some-> (or (:coin order)
                         (get-in order [:order :coin]))
                     str
                     str/trim)]
    (when (seq coin) coin)))

(defn resolve-cancel-order-oid
  [order]
  (some parse-int-value
       [(:oid order)
        (:o order)
        (get-in order [:order :oid])
        (get-in order [:order :o])]))

(defn- normalize-cancel-order-dex
  [order]
  (normalize-display-text
   (or (:dex order)
       (get-in order [:order :dex]))))

(defn- resolve-cancel-order-asset-idx
  [state order coin]
  (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
        market (markets/resolve-market-by-coin market-by-key coin)
        resolved-market-asset-id (market-asset-id market)
        named-dex-cancel? (or (seq (normalize-cancel-order-dex order))
                              (named-dex-market? market))
        context-asset-idx (when (and coin (not named-dex-cancel?))
                            (some parse-int-value
                                  [(get-in state [:asset-contexts (keyword coin) :idx])
                                   (get-in state [:asset-contexts coin :idx])]))]
    (some parse-int-value
          [(:asset-id order)
           (:assetId order)
           (:asset-idx order)
           (:assetIdx order)
           (:asset order)
           (:a order)
           (get-in order [:order :asset-id])
           (get-in order [:order :assetId])
           (get-in order [:order :asset-idx])
           (get-in order [:order :assetIdx])
           (get-in order [:order :asset])
           (get-in order [:order :a])
           resolved-market-asset-id
           context-asset-idx])))

(defn build-cancel-order-request
  "Normalize heterogeneous order row payloads into exchange cancel action shape.
   Returns nil when required fields are missing."
  [state order]
  (let [coin (normalize-cancel-order-coin order)
        oid (resolve-cancel-order-oid order)
        asset-idx (resolve-cancel-order-asset-idx state order coin)]
    (when (and (some? asset-idx) (some? oid))
      {:action {:type "cancel"
                :cancels [{:a asset-idx :o oid}]}})))

(defn- safe-private-key->agent-address
  [private-key]
  (try
    (some-> private-key
            agent-session/private-key->agent-address
            normalize-address)
    (catch :default _
      nil)))

(defn- next-nonce [cursor]
  (let [now (platform/now-ms)
        cursor* (when (number? cursor)
                  (js/Math.floor cursor))
        monotonic-candidate (if (number? cursor*)
                              (inc cursor*)
                              now)]
    (max now monotonic-candidate)))

(defn- post-signed-action!
  [action nonce signature & {:keys [vault-address expires-after]}]
  (let [payload (cond-> {:action action
                         :nonce nonce
                         :signature signature}
                  vault-address (assoc :vaultAddress vault-address)
                  expires-after (assoc :expiresAfter expires-after))]
    (when (contracts/validation-enabled?)
      (contracts/assert-signed-exchange-payload!
       payload
       {:boundary :api-trading/post-signed-action
        :action-type (:type action)}))
    (json-post! exchange-url payload)))

(defn- post-info!
  [body]
  (json-post! info-url body))

(defn- fetch-user-role!
  [address]
  (-> (post-info! {:type "userRole"
                   :user address})
      (.then parse-json!)))

(defn- user-role-agent-for-owner?
  [owner-address role-response]
  (let [owner* (normalize-address owner-address)
        role (some-> (:role role-response)
                     str
                     str/lower-case)
        linked-user* (normalize-address (or (get-in role-response [:data :user])
                                            (:user role-response)))]
    (and (= role "agent")
         (seq owner*)
         (= owner* linked-user*))))

(defn- should-invalidate-missing-api-wallet-session!
  [owner-address session]
  (let [agent-address* (normalize-address (:agent-address session))]
    (if-not (seq agent-address*)
      (js/Promise.resolve true)
      (-> (fetch-user-role! agent-address*)
          (.then (fn [role-response]
                   (not (user-role-agent-for-owner? owner-address role-response))))
          ;; Preserve local key if lookup itself fails (network/rate-limit),
          ;; because we could not prove the session is invalid.
          (.catch (fn [_]
                    (js/Promise.resolve false)))))))

(defn- reconcile-session-agent-address!
  [store owner-address storage-mode session]
  (let [stored-address* (normalize-address (:agent-address session))
        derived-address* (safe-private-key->agent-address (:private-key session))
        needs-update? (and (seq derived-address*)
                           (not= stored-address* derived-address*))
        session* (cond-> (assoc session :storage-mode storage-mode)
                   (seq derived-address*) (assoc :agent-address derived-address*))]
    (when (and needs-update?
               (seq owner-address))
      (agent-session/persist-agent-session-by-mode! owner-address storage-mode session*)
      (swap! store update-in [:wallet :agent] merge {:agent-address derived-address*}))
    session*))

(defn- resolve-agent-session
  [store owner-address]
  (let [agent-state (get-in @store [:wallet :agent] {})
        storage-mode (agent-session/normalize-storage-mode (:storage-mode agent-state))
        session (agent-session/load-agent-session-by-mode owner-address storage-mode)]
    (when (map? session)
      (reconcile-session-agent-address! store owner-address storage-mode session))))

(defn- persist-agent-nonce-cursor!
  [store owner-address session nonce]
  (let [storage-mode (:storage-mode session)
        updated-session (assoc session :nonce-cursor nonce)]
    (agent-session/persist-agent-session-by-mode! owner-address storage-mode updated-session)
    (swap! store update-in [:wallet :agent] merge {:status :ready
                                                   :agent-address (:agent-address session)
                                                   :storage-mode storage-mode
                                                   :nonce-cursor nonce})))

(defn- invalidate-agent-session!
  [store owner-address session message]
  (let [storage-mode (:storage-mode session)]
    (agent-session/clear-agent-session-by-mode! owner-address storage-mode)
    (swap! store assoc-in [:wallet :agent]
           (assoc (agent-session/default-agent-state :storage-mode storage-mode)
                  :status :error
                  :error message))))

(defn- sign-and-post-agent-action!
  [store owner-address action & {:keys [vault-address expires-after is-mainnet max-nonce-retries]
                                 :or {vault-address nil
                                      expires-after nil
                                      is-mainnet true
                                      max-nonce-retries 1}}]
  (let [session (resolve-agent-session store owner-address)
        vault-address* (some-> vault-address str str/lower-case)]
    (if-not (and (map? session)
                 (seq (:private-key session)))
      (js/Promise.reject (js/Error. "Agent session unavailable. Enable trading first."))
      (letfn [(handle-response! [resp nonce retries-left]
                (cond
                  (and (pos? retries-left)
                       (nonce-error-response? resp))
                  (attempt! nonce (dec retries-left))

                  (missing-api-wallet-response? resp)
                  (-> (should-invalidate-missing-api-wallet-session! owner-address session)
                      (.then (fn [invalidate?]
                               (when invalidate?
                                 (invalidate-agent-session! store
                                                            owner-address
                                                            session
                                                            missing-api-wallet-error-message))
                               (if invalidate?
                                 {:status "err"
                                  :error missing-api-wallet-error-message}
                                 {:status "err"
                                  :error missing-api-wallet-preserved-message
                                  :response (response-error-text resp)}))))

                  :else
                  (do
                    (persist-agent-nonce-cursor! store owner-address session nonce)
                    resp)))
              (post-signed! [nonce retries-left sig]
                (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)]
                  (-> (post-signed-action! action nonce {:r r :s s :v v}
                                           :vault-address vault-address*
                                           :expires-after expires-after)
                      (.then parse-json!)
                      (.then #(handle-response! % nonce retries-left)))))
              (attempt! [cursor retries-left]
                (let [nonce (next-nonce cursor)]
                  (-> (signing/sign-l1-action-with-private-key!
                       (:private-key session)
                       action
                       nonce
                       :vault-address vault-address*
                       :expires-after expires-after
                       :is-mainnet is-mainnet)
                      (.then #(post-signed! nonce retries-left %)))))]
        (attempt! (or (:nonce-cursor session)
                      (get-in @store [:wallet :agent :nonce-cursor]))
                  max-nonce-retries)))))

(defn submit-order!
  [store address action]
  (sign-and-post-agent-action! store address action))

(defn cancel-order!
  [store address action]
  (sign-and-post-agent-action! store address action))

(defn approve-agent!
  [store address action]
  (-> (signing/sign-approve-agent-action! address action)
      (.then (fn [sig]
               (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)
                     payload {:action action
                              :nonce (:nonce action)
                              :signature {:r r
                                          :s s
                                          :v v}}]
                 (json-post! exchange-url payload))))))
