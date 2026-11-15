(ns hyperopen.platform.webauthn
  (:require [clojure.string :as str]))

(def ^:private default-rp-name
  "Hyperopen")

(def ^:private default-timeout-ms
  60000)

(def ^:private default-pub-key-cred-params
  [{:type "public-key"
    :alg -7}
   {:type "public-key"
    :alg -257}])

(defn- secure-context?
  []
  (true? (some-> js/globalThis .-isSecureContext)))

(defn passkey-lock-supported?
  []
  (let [credentials-container (some-> js/globalThis .-navigator .-credentials)
        subtle (some-> js/globalThis .-crypto .-subtle)]
    (and (secure-context?)
         (some? (.-PublicKeyCredential js/globalThis))
         (some? credentials-container)
         (fn? (.-create credentials-container))
         (fn? (.-get credentials-container))
         (some? subtle))))

(defn passkey-capability-hint?
  []
  (let [public-key-credential (.-PublicKeyCredential js/globalThis)]
    (boolean
     (and (passkey-lock-supported?)
          public-key-credential
          (or (fn? (.-getClientCapabilities public-key-credential))
              (fn? (.-isUserVerifyingPlatformAuthenticatorAvailable public-key-credential)))))))

(defn- capability-supported?
  [public-key-credential]
  (fn? (some-> public-key-credential
               (aget "getClientCapabilities"))))

(defn- legacy-capability-supported?
  [public-key-credential]
  (fn? (some-> public-key-credential
               (aget "isUserVerifyingPlatformAuthenticatorAvailable"))))

(defn- capability-result
  [caps]
  (let [prf? (true? (aget caps "extension:prf"))
        platform? (or (true? (aget caps "passkeyPlatformAuthenticator"))
                      (true? (aget caps "userVerifyingPlatformAuthenticator")))]
    (and prf? platform?)))

(defn- false-promise
  [& _]
  (js/Promise.resolve false))

(defn- client-capabilities-supported!
  [public-key-credential]
  (-> (js-invoke public-key-credential "getClientCapabilities")
      (.then capability-result)
      (.catch false-promise)))

(defn- legacy-platform-authenticator-supported!
  [public-key-credential]
  (-> (js-invoke public-key-credential "isUserVerifyingPlatformAuthenticatorAvailable")
      (.then boolean)
      (.catch false-promise)))

(defn passkey-capable?
  []
  (let [public-key-credential (.-PublicKeyCredential js/globalThis)]
    (cond
      (not (passkey-lock-supported?))
      (js/Promise.resolve false)

      (capability-supported? public-key-credential)
      (client-capabilities-supported! public-key-credential)

      (legacy-capability-supported? public-key-credential)
      (legacy-platform-authenticator-supported! public-key-credential)

      :else
      (js/Promise.resolve false))))

(defn utf8-bytes
  [value]
  (.encode (js/TextEncoder.) (str (or value ""))))

(defn ensure-uint8-array
  [value]
  (cond
    (nil? value) nil
    (instance? js/Uint8Array value) value
    (instance? js/ArrayBuffer value) (js/Uint8Array. value)
    (string? value) (utf8-bytes value)
    :else nil))

(defn random-bytes
  [size]
  (let [out (js/Uint8Array. size)]
    (.getRandomValues (.-crypto js/globalThis) out)
    out))

(defn bytes->base64url
  [value]
  (when-let [bytes (ensure-uint8-array value)]
    (let [parts (array)]
      (dotimes [idx (.-length bytes)]
        (.push parts (js/String.fromCharCode (aget bytes idx))))
      (-> (apply str parts)
          js/btoa
          (str/replace "+" "-")
          (str/replace "/" "_")
          (str/replace "=" "")))))

(defn base64url->bytes
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (let [normalized (-> text
                           (str/replace "-" "+")
                           (str/replace "_" "/"))
            remainder (mod (count normalized) 4)
            padded (if (zero? remainder)
                     normalized
                     (str normalized (apply str (repeat (- 4 remainder) "="))))
            decoded (js/atob padded)
            out (js/Uint8Array. (count decoded))]
        (dotimes [idx (count decoded)]
          (aset out idx (.charCodeAt decoded idx)))
        out))))

(defn- extension-results
  [credential]
  (let [get-results (when credential
                      (aget credential "getClientExtensionResults"))]
    (when (fn? get-results)
      (js-invoke credential "getClientExtensionResults"))))

(defn- prf-first-result
  [extension-results]
  (some-> extension-results
          (aget "prf")
          (aget "results")
          (aget "first")
          js/Uint8Array.))

(defn- unsupported-passkey-promise
  []
  (js/Promise.reject
   (js/Error. "Passkey locking requires a secure browser with WebAuthn and WebCrypto support.")))

(defn- credential-user-id
  [{:keys [display-name user-id-bytes user-name]}]
  (or (ensure-uint8-array user-id-bytes)
      (utf8-bytes (or user-name display-name "hyperopen"))))

(defn- credential-public-key-options
  [{:keys [display-name prf-salt rp-name timeout-ms user-id-bytes user-name]}]
  (let [user-id (credential-user-id {:display-name display-name
                                     :user-id-bytes user-id-bytes
                                     :user-name user-name})
        prf-salt* (ensure-uint8-array prf-salt)]
    (cond-> {:challenge (random-bytes 32)
             :rp {:name (or rp-name default-rp-name)}
             :user {:id user-id
                    :name (or user-name "hyperopen")
                    :displayName (or display-name "Hyperopen Trading Unlock")}
             :pubKeyCredParams default-pub-key-cred-params
             :authenticatorSelection {:residentKey "preferred"
                                      :userVerification "required"}
             :attestation "none"
             :timeout (or timeout-ms default-timeout-ms)}
      prf-salt*
      (assoc :extensions {:prf {:eval {:first prf-salt*}}}))))

(defn- credential-result
  [credential]
  (let [extension-results* (extension-results credential)]
    {:credential-id (some-> credential
                            (aget "rawId")
                            js/Uint8Array.
                            bytes->base64url)
     :prf-first (prf-first-result extension-results*)
     :extension-results extension-results*}))

(defn create-passkey-credential!
  [{:keys [display-name prf-salt rp-name timeout-ms user-id-bytes user-name]}]
  (if-not (passkey-lock-supported?)
    (unsupported-passkey-promise)
    (let [credentials-container (some-> js/globalThis .-navigator .-credentials)
          public-key (credential-public-key-options
                      {:display-name display-name
                       :prf-salt prf-salt
                       :rp-name rp-name
                       :timeout-ms timeout-ms
                       :user-id-bytes user-id-bytes
                       :user-name user-name})]
      (-> (.create credentials-container (clj->js {:publicKey public-key}))
          (.then credential-result)))))

(defn- eval-prf-public-key-options
  [{:keys [credential-id prf-salt timeout-ms]}]
  (let [credential-id* (some-> credential-id str str/trim)
        prf-salt* (ensure-uint8-array prf-salt)]
    (when (and (seq credential-id*)
               prf-salt*)
      (let [eval-map (doto (js-obj)
                       (aset credential-id* (clj->js {:first prf-salt*})))]
        {:challenge (random-bytes 32)
         :allowCredentials [{:type "public-key"
                             :id (base64url->bytes credential-id*)}]
         :userVerification "required"
         :timeout (or timeout-ms default-timeout-ms)
         :extensions {:prf {:evalByCredential eval-map}}}))))

(defn eval-prf!
  [{:keys [credential-id prf-salt timeout-ms]}]
  (if-not (passkey-lock-supported?)
    (unsupported-passkey-promise)
    (let [credentials-container (some-> js/globalThis .-navigator .-credentials)
          public-key (eval-prf-public-key-options {:credential-id credential-id
                                                   :prf-salt prf-salt
                                                   :timeout-ms timeout-ms})]
      (if-not public-key
        (js/Promise.reject (js/Error. "Passkey credential data is unavailable."))
        (-> (.get credentials-container (clj->js {:publicKey public-key}))
            (.then
             (fn [credential]
               (assoc (credential-result credential)
                      :credential-id (some-> credential-id str str/trim)))))))))
