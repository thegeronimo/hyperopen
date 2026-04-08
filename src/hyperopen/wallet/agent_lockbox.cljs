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

(defn passkey-unlock-supported!
  []
  (webauthn/passkey-capable?))

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

(def clear-signer!
  clear-unlocked-session!)

(def install-signer!
  cache-unlocked-session!)

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

(defn create-locked-session!
  [{:keys [now-ms-fn session wallet-address]
    :or {now-ms-fn #(.now js/Date)}}]
  (let [wallet-address* (normalized-cache-key wallet-address)
        {:keys [agent-address last-approved-at nonce-cursor private-key]} session]
    (cond
      (not (seq wallet-address*))
      (rejection "Connect your wallet before enabling passkey lock.")

      (not (and (string? agent-address)
                (seq agent-address)
                (string? private-key)
                (seq private-key)))
      (rejection "Trading session data is unavailable for passkey lock.")

      (not (passkey-lock-supported?))
      (rejection "Passkey locking is unavailable in this browser.")

      :else
      (let [prf-salt (webauthn/random-bytes 32)
            credential-user-name (str "hyperopen-trading:" wallet-address*)
            saved-at-ms (js/Math.floor (now-ms-fn))]
        (-> (webauthn/create-passkey-credential!
             {:display-name (str "Hyperopen Trading " (short-wallet-label wallet-address*))
              :prf-salt prf-salt
              :timeout-ms lockbox-timeout-ms
              :user-id-bytes (webauthn/utf8-bytes credential-user-name)
              :user-name credential-user-name})
            (.then
             (fn [{:keys [credential-id prf-first]}]
               (if-not (seq (some-> credential-id str str/trim))
                 (js/Promise.reject
                  (js/Error. "Unable to create a passkey credential for trading unlock."))
                 (-> (resolve-prf-output! credential-id prf-salt prf-first)
                     (.then
                      (fn [prf-output]
                        (-> (encrypt-private-key! wallet-address* private-key prf-output)
                            (.then
                             (fn [lockbox-record]
                               (-> (indexed-db/put-json!
                                    indexed-db/agent-locked-session-store
                                    wallet-address*
                                    (assoc lockbox-record
                                           :saved-at-ms saved-at-ms))
                                   (.then
                                    (fn [persisted?]
                                      (if persisted?
                                        {:metadata {:record-version lockbox-version
                                                    :record-kind :locked
                                                    :agent-address agent-address
                                                    :credential-id credential-id
                                                    :prf-salt (webauthn/bytes->base64url prf-salt)
                                                    :last-approved-at last-approved-at
                                                    :nonce-cursor nonce-cursor
                                                    :saved-at-ms saved-at-ms}
                                         :session session}
                                        (js/Promise.reject
                                         (js/Error.
                                          "Unable to persist the passkey trading lockbox."))))))))))))))))))))

(defn unlock-locked-session!
  [{:keys [metadata wallet-address]}]
  (let [wallet-address* (normalized-cache-key wallet-address)
        credential-id (some-> (:credential-id metadata) str str/trim)
        prf-salt (webauthn/base64url->bytes (:prf-salt metadata))]
    (cond
      (not (seq wallet-address*))
      (rejection "Connect your wallet before unlocking trading.")

      (not (seq credential-id))
      (rejection "Passkey credential data is unavailable.")

      (nil? prf-salt)
      (rejection "Passkey lockbox data is unavailable.")

      :else
      (-> (webauthn/eval-prf! {:credential-id credential-id
                               :prf-salt prf-salt
                               :timeout-ms lockbox-timeout-ms})
          (.then
           (fn [{:keys [prf-first]}]
             (if-not (valid-byte-source? prf-first)
               (js/Promise.reject
                (js/Error. "Unable to derive the passkey trading unlock secret."))
               (-> (indexed-db/get-json!
                    indexed-db/agent-locked-session-store
                    wallet-address*)
                   (.then
                    (fn [lockbox-record]
                      (if-not (map? lockbox-record)
                        (js/Promise.reject
                         (js/Error. "No passkey lockbox was found for this wallet."))
                        (-> (decrypt-private-key! wallet-address*
                                                  prf-first
                                                  lockbox-record)
                            (.then
                             (fn [private-key]
                               (let [session {:agent-address (:agent-address metadata)
                                              :private-key private-key
                                              :last-approved-at (:last-approved-at metadata)
                                              :nonce-cursor (:nonce-cursor metadata)}]
                                 (cache-unlocked-session! wallet-address* session)
                                 session)))))))))))))))

(defn delete-locked-session!
  [wallet-address]
  (let [wallet-address* (normalized-cache-key wallet-address)]
    (clear-unlocked-session! wallet-address*)
    (if-not (seq wallet-address*)
      (js/Promise.resolve false)
      (indexed-db/delete-key!
       indexed-db/agent-locked-session-store
       wallet-address*))))

(def clear-lockbox!
  delete-locked-session!)
