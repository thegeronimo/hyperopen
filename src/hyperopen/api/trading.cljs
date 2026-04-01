(ns hyperopen.api.trading
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.agent-session-crypto :as agent-session-crypto]
            [hyperopen.utils.hl-signing :as signing]))
(def exchange-url "https://api.hyperliquid.xyz/exchange")
(def info-url "https://api.hyperliquid.xyz/info")

(defonce ^:private debug-exchange-simulator
  (atom nil))

(defn set-debug-exchange-simulator!
  [simulator]
  (reset! debug-exchange-simulator simulator)
  true)

(defn clear-debug-exchange-simulator!
  []
  (reset! debug-exchange-simulator nil)
  true)

(defn debug-exchange-simulator-snapshot
  []
  @debug-exchange-simulator)

(defn- queued-simulator-response!
  [path]
  (let [entry (get-in @debug-exchange-simulator path)]
    (cond
      (and (map? entry)
           (sequential? (:responses entry)))
      (let [responses (vec (:responses entry))
            response (first responses)
            remaining (vec (rest responses))]
        (swap! debug-exchange-simulator assoc-in path
               (assoc entry :responses remaining))
        response)

      (map? entry)
      (or (:response entry) entry)

      (sequential? entry)
      (let [responses (vec entry)
            response (first responses)
            remaining (vec (rest responses))]
        (swap! debug-exchange-simulator assoc-in path remaining)
        response)

      :else
      entry)))

(defn- simulator-response-body
  [response]
  (cond
    (and (map? response) (contains? response :body))
    (:body response)

    :else
    response))

(defn- response-like
  [response]
  (let [payload (simulator-response-body response)
        status (or (:http-status response)
                   (:httpStatus response)
                   200)]
    #js {:status status
         :json (fn []
                 (js/Promise.resolve (clj->js payload)))
         :text (fn []
                 (js/Promise.resolve (js/JSON.stringify (clj->js payload))))}))

(defn- simulated-fetch-response
  [paths]
  (when (seq @debug-exchange-simulator)
    (let [response (some queued-simulator-response! paths)]
      (when response
        (if-let [reject-message (or (:reject-message response)
                                    (:rejectMessage response))]
          (js/Promise.reject (js/Error. (str reject-message)))
          (js/Promise.resolve (response-like response)))))))

(defn- json-post! [url body]
  (js/fetch url
            (clj->js {:method "POST"
                      :headers {"Content-Type" "application/json"}
                      :body (js/JSON.stringify (clj->js body))})))

(defn- parse-text-body
  [raw status]
  (let [raw* (some-> raw str str/trim)]
    (if (str/blank? raw*)
      {:status "err"
       :error (str "HTTP " status)}
      (try
        (js->clj (js/JSON.parse raw*) :keywordize-keys true)
        (catch :default _
          {:status "err"
           :error raw*})))))

(defn- parse-json! [resp]
  (let [parse-response-promise
        (if (fn? (.-text resp))
          (-> (.text resp)
              (.then (fn [raw]
                       (parse-text-body raw (.-status resp)))))
          (-> (.json resp)
              (.then (fn [payload]
                       (js->clj payload :keywordize-keys true)))))]
    (-> parse-response-promise
        (.then (fn [parsed]
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

(defn enable-trading-recovery-error?
  [value]
  (let [text (cond
               (map? value) (response-error-text value)
               :else (some-> value str))]
    (= missing-api-wallet-error-message
       (some-> text str str/trim))))

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

(defn build-cancel-orders-request
  "Normalize a sequence of heterogeneous order row payloads into one batched
   exchange cancel action. Returns nil when any order is missing required
   fields, because visible-scope cancel-all must not silently skip rows."
  [state orders]
  (let [requests (mapv #(build-cancel-order-request state %) (or orders []))]
    (when (and (seq requests)
               (every? map? requests))
      (let [cancels (->> requests
                         (mapcat #(get-in % [:action :cancels]))
                         distinct
                         vec)]
        (when (seq cancels)
          {:action {:type "cancel"
                    :cancels cancels}})))))

(defn build-cancel-twap-request
  "Normalize a TWAP row payload into exchange twapCancel action shape.
   Returns nil when the required twap id or asset index is missing."
  [state twap]
  (let [coin (normalize-cancel-order-coin twap)
        twap-id (some parse-int-value
                      [(:twap-id twap)
                       (:twapId twap)
                       (:t twap)
                       (get-in twap [:state :twapId])])
        asset-idx (resolve-cancel-order-asset-idx state twap coin)]
    (when (and (some? twap-id)
               (some? asset-idx))
      {:action {:type "twapCancel"
                :a asset-idx
                :t twap-id}})))

(defn- safe-private-key->agent-address
  [private-key]
  (try
    (some-> private-key
            agent-session-crypto/private-key->agent-address
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

(defn- parse-chain-id-int
  [value]
  (let [raw (some-> value str str/trim)]
    (when (seq raw)
      (let [hex? (str/starts-with? raw "0x")
            source (if hex? (subs raw 2) raw)
            base (if hex? 16 10)
            parsed (js/parseInt source base)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (js/Math.floor parsed))))))

(defn- normalize-signature-chain-id
  [value]
  (cond
    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        text))

    (number? value)
    (str "0x" (.toString (js/Math.floor value) 16))

    :else nil))

(defn- testnet-signature-chain-id-int
  []
  (parse-chain-id-int
   (agent-session/default-signature-chain-id-for-environment false)))

(defn- resolve-user-signing-context
  [store]
  (let [wallet-chain-id (get-in @store [:wallet :chain-id])
        signature-chain-id (or (normalize-signature-chain-id wallet-chain-id)
                               (agent-session/default-signature-chain-id-for-environment true))
        chain-id-int (parse-chain-id-int signature-chain-id)]
    {:signature-chain-id signature-chain-id
     :hyperliquid-chain (if (= chain-id-int (testnet-signature-chain-id-int))
                          "Testnet"
                          "Mainnet")}))

(defn- next-user-signed-nonce!
  [store]
  (let [cursor (get-in @store [:wallet :user-signed-nonce-cursor])
        nonce (next-nonce cursor)]
    (swap! store assoc-in [:wallet :user-signed-nonce-cursor] nonce)
    nonce))

(defn- maybe-assert-signed-exchange-payload! [payload action]
  (when (contracts/validation-enabled?)
    (contracts/assert-signed-exchange-payload!
     payload {:boundary :api-trading/post-signed-action
              :action-type (:type action)})))
(defn- post-signed-action!
  ([action nonce signature]
   (post-signed-action! action nonce signature {}))
  ([action nonce signature options]
   (let [{:keys [vault-address expires-after]} options
         payload (cond-> {:action action
                          :nonce nonce
                          :signature signature}
                   vault-address (assoc :vaultAddress vault-address)
                   expires-after (assoc :expiresAfter expires-after))]
     (maybe-assert-signed-exchange-payload! payload action)
     (or (simulated-fetch-response [[:signedActions (:type action)]
                                    [:signedActions :default]])
         (json-post! exchange-url payload)))))

(defn- post-info!
  [body]
  (or (simulated-fetch-response [[:info (keyword (str (:type body)))]
                                 [:info :default]])
      (json-post! info-url body)))

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

(defn- normalize-agent-action-options
  [{:keys [vault-address expires-after is-mainnet max-nonce-retries]}]
  {:vault-address (some-> vault-address str str/lower-case)
   :expires-after expires-after
   :is-mainnet (if (nil? is-mainnet) true is-mainnet)
   :max-nonce-retries (if (nil? max-nonce-retries) 1 max-nonce-retries)})

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
     :error missing-api-wallet-error-message}
    {:status "err"
     :error missing-api-wallet-preserved-message
     :response (response-error-text resp)}))

(defn- resolve-missing-api-wallet-response!
  [store owner-address session resp invalidate?]
  (when invalidate?
    (invalidate-agent-session! store
                               owner-address
                               session
                               missing-api-wallet-error-message))
  (missing-api-wallet-result resp invalidate?))

(defn- handle-agent-action-response!
  [store owner-address session nonce resp retries-left retry-fn]
  (cond
    (and (pos? retries-left)
         (nonce-error-response? resp))
    (retry-fn)

    (missing-api-wallet-response? resp)
    (-> (should-invalidate-missing-api-wallet-session! owner-address session)
        (.then (fn [invalidate?]
                 (resolve-missing-api-wallet-response!
                  store
                  owner-address
                  session
                  resp
                  invalidate?))))

    :else
    (persist-agent-action-response! store owner-address session nonce resp)))

(defn- sign-agent-action!
  [session action nonce {:keys [vault-address expires-after is-mainnet]}]
  (signing/sign-l1-action-with-private-key!
   (:private-key session)
   action
   nonce
   :vault-address vault-address
   :expires-after expires-after
   :is-mainnet is-mainnet))

(defn- post-signed-agent-action!
  [action nonce sig {:keys [vault-address expires-after]}]
  (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)]
    (-> (post-signed-action! action nonce {:r r :s s :v v}
                             {:vault-address vault-address
                              :expires-after expires-after})
        (.then parse-json!))))
(defn- missing-agent-session-rejection [session]
  (when-not (agent-session-available? session)
    (js/Promise.reject (js/Error. "Agent session unavailable. Enable trading first."))))

(defn- next-retry-callback [attempt! nonce retries-left]
  #(attempt! nonce (dec retries-left)))
(defn- sign-and-post-agent-action!
  ([store owner-address action]
   (sign-and-post-agent-action! store owner-address action {}))
  ([store owner-address action raw-options]
   (let [session (resolve-agent-session store owner-address)
         options (normalize-agent-action-options raw-options)]
     (if-let [rejection (missing-agent-session-rejection session)]
       rejection
       (letfn [(attempt! [cursor retries-left]
                 (let [nonce (next-nonce cursor)]
                   (-> (sign-agent-action! session action nonce options)
                       (.then #(post-signed-agent-action! action nonce % options))
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
                   (:max-nonce-retries options)))))))

(defn submit-order!
  [store address action]
  (sign-and-post-agent-action! store address action))

(defn cancel-order!
  [store address action]
  (sign-and-post-agent-action! store address action))

(defn submit-vault-transfer!
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
                 (or (simulated-fetch-response [[:approveAgent]])
                     (json-post! exchange-url payload)))))))

(defn- sign-and-post-user-action!
  [store address action nonce-field sign-action!]
  (let [{:keys [signature-chain-id hyperliquid-chain]} (resolve-user-signing-context store)
        nonce (next-user-signed-nonce! store)
        action* (-> action
                    (assoc :signatureChainId signature-chain-id
                           :hyperliquidChain hyperliquid-chain)
                    (assoc nonce-field nonce))]
    (-> (sign-action! address action*)
        (.then (fn [sig]
                 (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)
                       signature {:r r
                                  :s s
                                  :v v}]
                   (-> (post-signed-action! action* nonce signature)
                       (.then parse-json!))))))))

(defn submit-usd-class-transfer!
  [store address action]
  (sign-and-post-user-action! store
                              address
                              action
                              :nonce
                              signing/sign-usd-class-transfer-action!))

(defn submit-send-asset!
  [store address action]
  (sign-and-post-user-action! store
                              address
                              action
                              :nonce
                              signing/sign-send-asset-action!))

(defn submit-c-deposit!
  [store address action]
  (sign-and-post-user-action! store
                              address
                              action
                              :nonce
                              signing/sign-c-deposit-action!))

(defn submit-c-withdraw!
  [store address action]
  (sign-and-post-user-action! store
                              address
                              action
                              :nonce
                              signing/sign-c-withdraw-action!))

(defn submit-token-delegate!
  [store address action]
  (sign-and-post-user-action! store
                              address
                              action
                              :nonce
                              signing/sign-token-delegate-action!))

(defn submit-withdraw3!
  [store address action]
  (sign-and-post-user-action! store
                              address
                              action
                              :time
                              signing/sign-withdraw3-action!))
