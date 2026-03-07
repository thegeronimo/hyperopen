(ns hyperopen.wallet.agent-session
  (:require [clojure.string :as str]
            ["@noble/secp256k1" :as secp]
            ["../vendor/keccak" :as keccak]))

(def ^:private session-storage-prefix
  "hyperopen:agent-session:v1:")

(def ^:private storage-mode-preference-key
  "hyperopen:agent-storage-mode:v1")

(def ^:private default-signature-chain-id
  "0xa4b1")

(def ^:private default-testnet-signature-chain-id
  "0x66eee")

(def zero-address
  "0x0000000000000000000000000000000000000000")

(def max-agent-valid-days
  180)

(def ^:private agent-valid-until-suffix-regex
  #"^(.*?)(?:\s+valid_until\s+(\d+))?$")

(def ^:private wallet-address-regex
  #"^0x[0-9a-f]{40}$")

(defn default-signature-chain-id-for-environment
  [is-mainnet]
  (if is-mainnet
    default-signature-chain-id
    default-testnet-signature-chain-id))

(defn normalize-storage-mode
  [storage-mode]
  (let [mode (cond
               (keyword? storage-mode) storage-mode
               (string? storage-mode) (keyword (str/lower-case (str/trim storage-mode)))
               :else :local)]
    (if (= :session mode) :session :local)))

(defn load-storage-mode-preference
  []
  (let [storage (some-> js/globalThis .-localStorage)]
    (if-not storage
      :local
      (try
        (normalize-storage-mode (.getItem storage storage-mode-preference-key))
        (catch :default _
          :local)))))

(defn persist-storage-mode-preference!
  [storage-mode]
  (let [storage (some-> js/globalThis .-localStorage)
        mode (name (normalize-storage-mode storage-mode))]
    (if-not storage
      false
      (try
        (.setItem storage storage-mode-preference-key mode)
        true
        (catch :default _
          false)))))

(defn normalize-wallet-address
  [wallet-address]
  (let [normalized (some-> wallet-address
                           str
                           str/trim
                           str/lower-case)]
    (when (and (seq normalized)
               (re-matches wallet-address-regex normalized))
      normalized)))

(defn normalize-agent-valid-days
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt (str/trim value) 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed))
               (pos? parsed))
      (-> parsed
          js/Math.floor
          (min max-agent-valid-days)))))

(defn parse-agent-name-valid-until
  [agent-name]
  (let [text (some-> agent-name str str/trim)]
    (if-not (seq text)
      {:name nil
       :valid-until-ms nil}
      (let [[_ base-name valid-until-text] (re-matches agent-valid-until-suffix-regex text)
            parsed-valid-until (when (seq valid-until-text)
                                 (js/parseInt valid-until-text 10))
            valid-until-ms (when (and (number? parsed-valid-until)
                                      (not (js/isNaN parsed-valid-until)))
                             (js/Math.floor parsed-valid-until))
            display-name (some-> (or base-name text)
                                 str
                                 str/trim
                                 not-empty)]
        {:name display-name
         :valid-until-ms valid-until-ms}))))

(defn format-agent-name-with-valid-until
  [agent-name server-time-ms days-valid]
  (let [name* (some-> agent-name str str/trim not-empty)
        days* (normalize-agent-valid-days days-valid)
        server-time* (when (number? server-time-ms)
                       (js/Math.floor server-time-ms))]
    (cond
      (nil? name*) nil
      (or (nil? days*)
          (nil? server-time*)) name*
      :else
      (str name*
           " valid_until "
           (+ server-time*
              (* days* 24 60 60 1000))))))

(defn session-storage-key
  [wallet-address]
  (when-let [address (normalize-wallet-address wallet-address)]
    (str session-storage-prefix address)))

(defn default-agent-state
  [& {:keys [storage-mode]
      :or {storage-mode :local}}]
  {:status :not-ready
   :agent-address nil
   :storage-mode (normalize-storage-mode storage-mode)
   :last-approved-at nil
   :error nil
   :nonce-cursor nil})

(defn build-approve-agent-action
  [agent-address nonce & {:keys [agent-name is-mainnet signature-chain-id]
                          :or {agent-name nil
                               is-mainnet true
                               signature-chain-id nil}}]
  (let [signature-chain-id* (or signature-chain-id
                                (default-signature-chain-id-for-environment is-mainnet))]
    (cond-> {:type "approveAgent"
             :agentAddress agent-address
             :nonce nonce
             :hyperliquidChain (if is-mainnet "Mainnet" "Testnet")
             :signatureChainId signature-chain-id*}
      (some? agent-name) (assoc :agentName agent-name))))

(defn- storage-by-mode
  [storage-mode]
  (let [global js/globalThis
        mode (normalize-storage-mode storage-mode)]
    (case mode
      :local (.-localStorage global)
      :session (.-sessionStorage global)
      nil)))

(defn- bytes->hex
  [bytes]
  (let [parts (array)]
    (dotimes [i (.-length bytes)]
      (let [b (aget bytes i)
            hex (.toString b 16)]
        (.push parts (if (= 1 (count hex)) "0" ""))
        (.push parts hex)))
    (apply str parts)))

(defn- hex->bytes
  [hex]
  (let [value (str (or hex ""))
        stripped (if (str/starts-with? value "0x") (subs value 2) value)
        len (/ (count stripped) 2)
        out (js/Uint8Array. len)]
    (dotimes [idx len]
      (let [offset (* idx 2)
            byte-str (subs stripped offset (+ offset 2))]
        (aset out idx (js/parseInt byte-str 16))))
    out))

(defn- random-private-key-bytes
  []
  (let [utils (.-utils secp)
        random-secret-key-fn (some-> utils .-randomSecretKey)
        random-private-key-fn (some-> utils .-randomPrivateKey)
        is-valid-secret-key-fn (some-> utils .-isValidSecretKey)
        is-valid-private-key-fn (some-> utils .-isValidPrivateKey)
        crypto (.-crypto js/globalThis)]
    (cond
      (fn? random-secret-key-fn)
      (.randomSecretKey utils)

      (fn? random-private-key-fn)
      (.randomPrivateKey utils)

      (and crypto (.-getRandomValues crypto))
      (loop []
        (let [candidate (js/Uint8Array. 32)]
          (.getRandomValues crypto candidate)
          (let [valid? (cond
                         (fn? is-valid-secret-key-fn) (.isValidSecretKey utils candidate)
                         (fn? is-valid-private-key-fn) (.isValidPrivateKey utils candidate)
                         :else false)]
          (if valid?
            candidate
            (recur)))))

      :else
      (throw
       (js/Error.
        "Secure random unavailable for agent key generation")))))

(defn private-key->agent-address
  [private-key]
  (let [public-key (.getPublicKey secp (hex->bytes private-key) false)
        uncompressed (.slice public-key 1)
        digest-hex (.keccak256 keccak uncompressed)]
    (str "0x" (subs digest-hex (- (count digest-hex) 40)))))

(defn create-agent-credentials!
  []
  (let [private-key-bytes (random-private-key-bytes)
        private-key (str "0x" (bytes->hex private-key-bytes))]
    {:private-key private-key
     :agent-address (private-key->agent-address private-key)}))

(defn- sanitize-agent-session
  [session]
  (let [agent-address (:agent-address session)
        private-key (:private-key session)
        last-approved-at (:last-approved-at session)
        nonce-cursor (:nonce-cursor session)]
    (when (and (string? agent-address)
               (seq agent-address)
               (string? private-key)
               (seq private-key))
      {:agent-address agent-address
       :private-key private-key
       :last-approved-at (when (number? last-approved-at) (js/Math.floor last-approved-at))
       :nonce-cursor (when (number? nonce-cursor) (js/Math.floor nonce-cursor))})))

(defn persist-agent-session!
  [storage wallet-address session]
  (let [key (session-storage-key wallet-address)
        normalized (sanitize-agent-session session)]
    (when (and storage (seq key) normalized)
      (try
        (.setItem storage key (js/JSON.stringify (clj->js normalized)))
        true
        (catch :default _
          false)))))

(defn load-agent-session
  [storage wallet-address]
  (let [key (session-storage-key wallet-address)]
    (when (and storage (seq key))
      (try
        (let [raw (.getItem storage key)]
          (when (seq raw)
            (sanitize-agent-session (js->clj (js/JSON.parse raw) :keywordize-keys true))))
        (catch :default _
          nil)))))

(defn clear-agent-session!
  [storage wallet-address]
  (let [key (session-storage-key wallet-address)]
    (when (and storage (seq key))
      (try
        (.removeItem storage key)
        true
        (catch :default _
          false)))))

(defn persist-agent-session-by-mode!
  [wallet-address storage-mode session]
  (persist-agent-session! (storage-by-mode storage-mode) wallet-address session))

(defn load-agent-session-by-mode
  [wallet-address storage-mode]
  (load-agent-session (storage-by-mode storage-mode) wallet-address))

(defn clear-agent-session-by-mode!
  [wallet-address storage-mode]
  (clear-agent-session! (storage-by-mode storage-mode) wallet-address))
