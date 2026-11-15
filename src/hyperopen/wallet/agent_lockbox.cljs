(ns hyperopen.wallet.agent-lockbox
  (:require [clojure.string :as str]
            [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.platform.webauthn :as webauthn]
            [hyperopen.wallet.agent-session :as agent-session]))

(def ^:private lockbox-version
  1)

(def ^:private lockbox-info
  "hyperopen:agent-lockbox:v1")

(def ^:private lockbox-timeout-ms
  60000)

(defonce ^:private unlocked-agent-sessions
  (atom {}))

(defn- subtle-crypto
  []
  (some-> js/globalThis .-crypto .-subtle))

(defn- normalized-cache-key
  [wallet-address]
  (agent-session/normalize-wallet-address wallet-address))

(defn passkey-lock-supported?
  []
  (webauthn/passkey-lock-supported?))

(defn load-unlocked-session
  [wallet-address]
  (get @unlocked-agent-sessions (normalized-cache-key wallet-address)))

(defn cache-unlocked-session!
  [wallet-address session]
  (when-let [cache-key (normalized-cache-key wallet-address)]
    (swap! unlocked-agent-sessions assoc cache-key session)
    session))

(defn clear-unlocked-session!
  [wallet-address]
  (when-let [cache-key (normalized-cache-key wallet-address)]
    (swap! unlocked-agent-sessions dissoc cache-key)
    true))

(defn clear-all-unlocked-sessions!
  []
  (reset! unlocked-agent-sessions {})
  true)

(defn- rejection
  [message]
  (js/Promise.reject (js/Error. message)))

(defn- valid-byte-source?
  [value]
  (let [bytes (webauthn/ensure-uint8-array value)]
    (and (some? bytes)
         (pos? (.-length bytes)))))

(defn- resolve-prf-output!
  [credential-id prf-salt create-prf-output]
  (cond
    (valid-byte-source? create-prf-output)
    (js/Promise.resolve (webauthn/ensure-uint8-array create-prf-output))

    :else
    (-> (webauthn/eval-prf! {:credential-id credential-id
                             :prf-salt prf-salt
                             :timeout-ms lockbox-timeout-ms})
        (.then
         (fn [{:keys [prf-first]}]
           (if (valid-byte-source? prf-first)
             (webauthn/ensure-uint8-array prf-first)
             (js/Promise.reject
              (js/Error.
               "This passkey cannot derive a trading unlock secret."))))))))

(defn- derive-lockbox-key!
  [wallet-address prf-output]
  (let [subtle (subtle-crypto)
        prf-output* (webauthn/ensure-uint8-array prf-output)
        salt (webauthn/utf8-bytes (or (normalized-cache-key wallet-address) ""))
        info (webauthn/utf8-bytes lockbox-info)]
    (if-not (and subtle
                 (valid-byte-source? prf-output*))
      (rejection "Unable to derive a passkey lockbox key.")
      (-> (.importKey subtle
                      "raw"
                      prf-output*
                      "HKDF"
                      false
                      #js ["deriveKey"])
          (.then
           (fn [key-material]
             (.deriveKey subtle
                         (clj->js {:name "HKDF"
                                   :hash "SHA-256"
                                   :salt salt
                                   :info info})
                         key-material
                         (clj->js {:name "AES-GCM"
                                   :length 256})
                         false
                         #js ["encrypt" "decrypt"])))))))

(defn- encrypt-private-key!
  [wallet-address private-key prf-output]
  (let [subtle (subtle-crypto)
        plaintext (webauthn/utf8-bytes private-key)
        iv (webauthn/random-bytes 12)]
    (-> (derive-lockbox-key! wallet-address prf-output)
        (.then
         (fn [crypto-key]
           (.encrypt subtle
                     (clj->js {:name "AES-GCM"
                               :iv iv})
                     crypto-key
                     plaintext)))
        (.then
         (fn [ciphertext]
           {:version lockbox-version
            :ciphertext (webauthn/bytes->base64url (js/Uint8Array. ciphertext))
            :iv (webauthn/bytes->base64url iv)})))))

(defn- decrypt-private-key!
  [wallet-address prf-output {:keys [ciphertext iv]}]
  (let [subtle (subtle-crypto)
        ciphertext* (webauthn/base64url->bytes ciphertext)
        iv* (webauthn/base64url->bytes iv)]
    (if-not (and ciphertext* iv*)
      (rejection "Passkey lockbox data is unavailable.")
      (-> (derive-lockbox-key! wallet-address prf-output)
          (.then
           (fn [crypto-key]
             (.decrypt subtle
                       (clj->js {:name "AES-GCM"
                                 :iv iv*})
                       crypto-key
                       ciphertext*)))
          (.then
           (fn [plaintext]
             (.decode (js/TextDecoder.) plaintext)))))))

(defn- short-wallet-label
  [wallet-address]
  (let [wallet-address* (or (normalized-cache-key wallet-address) "this wallet")]
    (if (>= (count wallet-address*) 10)
      (str (subs wallet-address* 0 6)
           "..."
           (subs wallet-address* (- (count wallet-address*) 4)))
      wallet-address*)))

(defn- lockable-session
  [session]
  (let [{:keys [agent-address last-approved-at nonce-cursor private-key]} session]
    (when (and (string? agent-address)
               (seq agent-address)
               (string? private-key)
               (seq private-key))
      {:agent-address agent-address
       :last-approved-at last-approved-at
       :nonce-cursor nonce-cursor
       :private-key private-key})))

(defn- credential-user-name
  [wallet-address]
  (str "hyperopen-trading:" wallet-address))

(defn- create-passkey-request
  [wallet-address prf-salt]
  (let [user-name (credential-user-name wallet-address)]
    {:display-name (str "Hyperopen Trading " (short-wallet-label wallet-address))
     :prf-salt prf-salt
     :timeout-ms lockbox-timeout-ms
     :user-id-bytes (webauthn/utf8-bytes user-name)
     :user-name user-name}))

(defn- blank-credential-id?
  [credential-id]
  (not (seq (some-> credential-id str str/trim))))

(defn- ensure-credential-id!
  [credential-id]
  (if (blank-credential-id? credential-id)
    (rejection "Unable to create a passkey credential for trading unlock.")
    (js/Promise.resolve credential-id)))

(defn- locked-session-metadata
  [session credential-id prf-salt saved-at-ms]
  {:record-version lockbox-version
   :record-kind :locked
   :agent-address (:agent-address session)
   :credential-id credential-id
   :prf-salt (webauthn/bytes->base64url prf-salt)
   :last-approved-at (:last-approved-at session)
   :nonce-cursor (:nonce-cursor session)
   :saved-at-ms saved-at-ms})

(defn- persist-lockbox-record!
  [wallet-address saved-at-ms lockbox-record]
  (-> (indexed-db/put-json!
       indexed-db/agent-locked-session-store
       wallet-address
       (assoc lockbox-record
              :saved-at-ms saved-at-ms))
      (.then
       (fn [persisted?]
         (if persisted?
           lockbox-record
           (js/Promise.reject
            (js/Error.
             "Unable to persist the passkey trading lockbox.")))))))

(defn- create-lockbox-from-credential!
  [wallet-address session saved-at-ms prf-salt {:keys [credential-id prf-first]}]
  (-> (ensure-credential-id! credential-id)
      (.then
       (fn [_]
         (-> (resolve-prf-output! credential-id prf-salt prf-first)
             (.then
              (fn [prf-output]
                (-> (encrypt-private-key! wallet-address
                                          (:private-key session)
                                          prf-output)
                    (.then
                     (fn [lockbox-record]
                       (-> (persist-lockbox-record! wallet-address
                                                    saved-at-ms
                                                    lockbox-record)
                           (.then
                            (fn [_]
                              {:metadata (locked-session-metadata session
                                                                  credential-id
                                                                  prf-salt
                                                                  saved-at-ms)
                               :session session})))))))))))))

(defn create-locked-session!
  [{:keys [now-ms-fn session wallet-address]
    :or {now-ms-fn #(.now js/Date)}}]
  (let [wallet-address* (normalized-cache-key wallet-address)
        session* (lockable-session session)]
    (cond
      (not (seq wallet-address*))
      (rejection "Connect your wallet before enabling passkey lock.")

      (nil? session*)
      (rejection "Trading session data is unavailable for passkey lock.")

      (not (passkey-lock-supported?))
      (rejection "Passkey locking is unavailable in this browser.")

      :else
      (let [prf-salt (webauthn/random-bytes 32)
            saved-at-ms (js/Math.floor (now-ms-fn))]
        (-> (webauthn/create-passkey-credential!
             (create-passkey-request wallet-address* prf-salt))
            (.then
             (fn [credential]
               (create-lockbox-from-credential! wallet-address*
                                                session*
                                                saved-at-ms
                                                prf-salt
                                                credential))))))))

(defn- valid-unlock-request
  [wallet-address metadata]
  (let [wallet-address* (normalized-cache-key wallet-address)
        credential-id (some-> (:credential-id metadata) str str/trim)
        prf-salt (webauthn/base64url->bytes (:prf-salt metadata))]
    (cond
      (not (seq wallet-address*))
      {:error "Connect your wallet before unlocking trading."}

      (not (seq credential-id))
      {:error "Passkey credential data is unavailable."}

      (nil? prf-salt)
      {:error "Passkey lockbox data is unavailable."}

      :else
      {:wallet-address wallet-address*
       :credential-id credential-id
       :prf-salt prf-salt})))

(defn- load-lockbox-record!
  [wallet-address]
  (-> (indexed-db/get-json!
       indexed-db/agent-locked-session-store
       wallet-address)
      (.then
       (fn [lockbox-record]
         (if (map? lockbox-record)
           lockbox-record
           (js/Promise.reject
            (js/Error. "No passkey lockbox was found for this wallet.")))))))

(defn- unlocked-session
  [metadata private-key]
  {:agent-address (:agent-address metadata)
   :private-key private-key
   :last-approved-at (:last-approved-at metadata)
   :nonce-cursor (:nonce-cursor metadata)})

(defn unlock-locked-session!
  [{:keys [cache-session? metadata wallet-address]
    :or {cache-session? true}}]
  (let [{:keys [credential-id error prf-salt wallet-address]
         :as unlock-request}
        (valid-unlock-request wallet-address metadata)]
    (if error
      (rejection error)
      (-> (webauthn/eval-prf! {:credential-id credential-id
                               :prf-salt prf-salt
                               :timeout-ms lockbox-timeout-ms})
          (.then
           (fn [{:keys [prf-first]}]
             (if-not (valid-byte-source? prf-first)
               (js/Promise.reject
                (js/Error. "Unable to derive the passkey trading unlock secret."))
               (-> (load-lockbox-record! wallet-address)
                   (.then
                    (fn [lockbox-record]
                      (-> (decrypt-private-key! wallet-address
                                                prf-first
                                                lockbox-record)
                          (.then
                           (fn [private-key]
                             (let [session (unlocked-session metadata private-key)]
                               (when cache-session?
                                 (cache-unlocked-session! wallet-address session))
                               session))))))))))))))

(defn delete-locked-session!
  [wallet-address]
  (let [wallet-address* (normalized-cache-key wallet-address)]
    (clear-unlocked-session! wallet-address*)
    (if-not (seq wallet-address*)
      (js/Promise.resolve false)
      (indexed-db/delete-key!
       indexed-db/agent-locked-session-store
       wallet-address*))))
