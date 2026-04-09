(ns hyperopen.platform.webauthn
  (:require [clojure.string :as str]))

(def ^:private default-rp-name
  "Hyperopen")

(def ^:private default-timeout-ms
  60000)

(def ^:private default-pub-key-cred-params
  ;; Match Chromium's default ES256 + RS256 set so Windows platform
  ;; authenticators remain eligible and Chrome stops warning.
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

(def webauthn-supported?
  passkey-lock-supported?)

(defn passkey-capability-hint?
  []
  (let [public-key-credential (.-PublicKeyCredential js/globalThis)]
    (boolean
     (and (passkey-lock-supported?)
          public-key-credential
          (or (fn? (.-getClientCapabilities public-key-credential))
              (fn? (.-isUserVerifyingPlatformAuthenticatorAvailable public-key-credential)))))))

(defn passkey-capable?
  []
  (let [public-key-credential (.-PublicKeyCredential js/globalThis)]
    (cond
      (not (passkey-lock-supported?))
      (js/Promise.resolve false)

      (fn? (.-getClientCapabilities public-key-credential))
      (-> (.getClientCapabilities public-key-credential)
          (.then (fn [caps]
                   (let [prf? (true? (aget caps "extension:prf"))
                         platform? (or (true? (aget caps "passkeyPlatformAuthenticator"))
                                       (true? (aget caps "userVerifyingPlatformAuthenticator")))]
                     (and prf? platform?))))
          (.catch (fn [_]
                    (js/Promise.resolve false))))

      (fn? (.-isUserVerifyingPlatformAuthenticatorAvailable public-key-credential))
      (-> (.isUserVerifyingPlatformAuthenticatorAvailable public-key-credential)
          (.then boolean)
          (.catch (fn [_]
                    (js/Promise.resolve false))))

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

(defn create-passkey-credential!
  [{:keys [display-name prf-salt rp-name timeout-ms user-id-bytes user-name]}]
  (if-not (passkey-lock-supported?)
    (js/Promise.reject
     (js/Error. "Passkey locking requires a secure browser with WebAuthn and WebCrypto support."))
    (let [credentials-container (some-> js/globalThis .-navigator .-credentials)
          user-id (or (ensure-uint8-array user-id-bytes)
                      (utf8-bytes (or user-name display-name "hyperopen")))
          prf-salt* (ensure-uint8-array prf-salt)
          public-key
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
            (assoc :extensions {:prf {:eval {:first prf-salt*}}}))]
      (-> (.create credentials-container (clj->js {:publicKey public-key}))
          (.then
           (fn [credential]
             (let [extension-results* (extension-results credential)]
               {:credential-id (some-> credential
                                       (aget "rawId")
                                       js/Uint8Array.
                                       bytes->base64url)
                :prf-first (prf-first-result extension-results*)
                :extension-results extension-results*})))))))

(defn eval-prf!
  [{:keys [credential-id prf-salt timeout-ms]}]
  (if-not (passkey-lock-supported?)
    (js/Promise.reject
     (js/Error. "Passkey locking requires a secure browser with WebAuthn and WebCrypto support."))
    (let [credentials-container (some-> js/globalThis .-navigator .-credentials)
          credential-id* (some-> credential-id str str/trim)
          prf-salt* (ensure-uint8-array prf-salt)]
      (if-not (and (seq credential-id*)
                   prf-salt*)
        (js/Promise.reject (js/Error. "Passkey credential data is unavailable."))
        (let [eval-map (doto (js-obj)
                         (aset credential-id* (clj->js {:first prf-salt*})))
              public-key {:challenge (random-bytes 32)
                          :allowCredentials [{:type "public-key"
                                              :id (base64url->bytes credential-id*)}]
                          :userVerification "required"
                          :timeout (or timeout-ms default-timeout-ms)
                          :extensions {:prf {:evalByCredential eval-map}}}]
          (-> (.get credentials-container (clj->js {:publicKey public-key}))
              (.then
               (fn [credential]
                 (let [extension-results* (extension-results credential)]
                   {:credential-id credential-id*
                    :prf-first (prf-first-result extension-results*)
                    :extension-results extension-results*})))))))))

(defn prf-secret!
  [{:keys [credential-id salt]}]
  (-> (eval-prf! {:credential-id credential-id
                  :prf-salt salt})
      (.then (fn [{:keys [prf-first]}]
               (if prf-first
                 prf-first
                 (js/Promise.reject
                  (js/Error. "The selected passkey could not unlock trading.")))))))
