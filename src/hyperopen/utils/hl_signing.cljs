(ns hyperopen.utils.hl-signing
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [hyperopen.platform :as platform]
            ["@noble/secp256k1" :as secp]
            ["../vendor/msgpack" :as msgpack]
            ["../vendor/keccak" :as keccak]))

(def ^:private zero-address
  "0x0000000000000000000000000000000000000000")

(def ^:private l1-domain
  {:name "Exchange"
   :version "1"
   :chainId 1337
   :verifyingContract zero-address})

(def ^:private eip712-domain-fields
  [{:name "name" :type "string"}
   {:name "version" :type "string"}
   {:name "chainId" :type "uint256"}
   {:name "verifyingContract" :type "address"}])

(def ^:private approve-agent-fields
  [{:name "hyperliquidChain" :type "string"}
   {:name "agentAddress" :type "address"}
   {:name "agentName" :type "string"}
   {:name "nonce" :type "uint64"}])

(defn- utf8-bytes [value]
  (.encode (js/TextEncoder.) (str (or value ""))))

(defn- strip-0x [s]
  (if (and s (str/starts-with? s "0x")) (subs s 2) s))

(defn hex->bytes [hex]
  (let [h (strip-0x (or hex ""))
        padded (if (odd? (count h)) (str "0" h) h)
        len (/ (count padded) 2)
        out (js/Uint8Array. len)]
    (dotimes [i len]
      (let [byte-str (subs padded (* i 2) (+ (* i 2) 2))]
        (aset out i (js/parseInt byte-str 16))))
    out))

(defn bytes->hex [bytes]
  (let [parts (array)]
    (dotimes [i (.-length bytes)]
      (let [b (aget bytes i)
            hex (.toString b 16)]
        (.push parts (if (= 1 (count hex)) "0" ""))
        (.push parts hex)))
    (apply str parts)))

(defn- concat-bytes [& byte-arrays]
  (let [arrays (remove nil? byte-arrays)
        total-len (reduce + (map #(.-length %) arrays))
        combined (js/Uint8Array. total-len)]
    (loop [offset 0
           remaining arrays]
      (if-let [arr (first remaining)]
        (do
          (.set combined arr offset)
          (recur (+ offset (.-length arr)) (rest remaining)))
        combined))))

(defn- keccak-bytes [bytes]
  (hex->bytes (.keccak256 keccak bytes)))

(defn- keccak-text [value]
  (keccak-bytes (utf8-bytes value)))

(defn- left-pad-bytes [bytes len]
  (let [src-len (.-length bytes)
        copy-len (min src-len len)
        out (js/Uint8Array. len)
        source (.slice bytes (- src-len copy-len))]
    (.set out source (- len copy-len))
    out))

(defn- single-byte [n]
  (doto (js/Uint8Array. 1)
    (aset 0 n)))

(defn- parse-chain-id [signature-chain-id]
  (let [raw (str (or signature-chain-id "0xa4b1"))
        base (if (str/starts-with? raw "0x") 16 10)
        source (if (str/starts-with? raw "0x") (subs raw 2) raw)]
    (js/parseInt source base)))

(defn- uint256-bytes [n]
  (left-pad-bytes (hex->bytes (.toString (js/BigInt n) 16)) 32))

(defn- bytes32-bytes [value]
  (left-pad-bytes (hex->bytes value) 32))

(defn- address-bytes32 [address]
  (left-pad-bytes (hex->bytes address) 32))

(defn- bigint-u64-bytes [n]
  (let [hex (-> n (js/BigInt) (.toString 16))
        pad-count (max 0 (- 16 (count hex)))
        padded (str/join "" (repeat pad-count "0"))]
    (hex->bytes (str padded hex))))

(defn- clj->js-clean [x]
  (walk/postwalk
    (fn [v]
      (cond
        (keyword? v) (name v)
        (map? v) (clj->js v)
        :else v))
    x))

(defn compute-connection-id
  "Compute keccak256(msgpack(action) || nonce-u64 || vault-flag || vault || expiresAfter-flag+u64).
   Returns 0x-prefixed hex string."
  [action nonce & {:keys [vault-address expires-after]
                   :or {vault-address nil
                        expires-after nil}}]
  (let [action-js (clj->js-clean action)
        action-bytes (.encode msgpack action-js)
        nonce-bytes (bigint-u64-bytes nonce)
        has-vault? (and (some? vault-address) (not (str/blank? vault-address)))
        vault-flag (single-byte (if has-vault? 1 0))
        vault-bytes (when has-vault? (hex->bytes vault-address))
        expires-bytes (when (some? expires-after)
                        (concat-bytes (single-byte 0) (bigint-u64-bytes expires-after)))
        combined (concat-bytes action-bytes nonce-bytes vault-flag vault-bytes expires-bytes)]
    (str "0x" (.keccak256 keccak combined))))

(defn build-typed-data
  [connection-id & {:keys [is-mainnet] :or {is-mainnet true}}]
  {:types {:Agent [{:name "source" :type "string"}
                   {:name "connectionId" :type "bytes32"}]}
   :domain l1-domain
   :primaryType "Agent"
   :message {:source (if is-mainnet "a" "b")
             :connectionId connection-id}})

(defn build-approve-agent-typed-data
  [{:keys [hyperliquidChain signatureChainId agentAddress agentName nonce]}]
  {:types {"HyperliquidTransaction:ApproveAgent" approve-agent-fields
           "EIP712Domain" eip712-domain-fields}
   :domain {:name "HyperliquidSignTransaction"
            :version "1"
            :chainId (parse-chain-id signatureChainId)
            :verifyingContract zero-address}
   :primaryType "HyperliquidTransaction:ApproveAgent"
   :message {:hyperliquidChain hyperliquidChain
             :agentAddress agentAddress
             :agentName (or agentName "")
             :nonce nonce}})

(def ^:private eip712-domain-type-hash
  (keccak-text "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"))

(def ^:private l1-agent-type-hash
  (keccak-text "Agent(string source,bytes32 connectionId)"))

(defn- l1-domain-separator []
  (keccak-bytes
   (concat-bytes
    eip712-domain-type-hash
    (keccak-text (:name l1-domain))
    (keccak-text (:version l1-domain))
    (uint256-bytes (:chainId l1-domain))
    (address-bytes32 (:verifyingContract l1-domain)))))

(defn- l1-agent-struct-hash [source connection-id]
  (keccak-bytes
   (concat-bytes
    l1-agent-type-hash
    (keccak-text source)
    (bytes32-bytes connection-id))))

(defn- l1-agent-digest-bytes [connection-id is-mainnet]
  (let [source (if is-mainnet "a" "b")
        prefix (doto (js/Uint8Array. 2)
                 (aset 0 0x19)
                 (aset 1 0x01))]
    (keccak-bytes
     (concat-bytes
      prefix
      (l1-domain-separator)
      (l1-agent-struct-hash source connection-id)))))

(defn split-signature [sig]
  (let [hex (strip-0x sig)
        r (subs hex 0 64)
        s (subs hex 64 128)
        v (subs hex 128 130)]
    {:r (str "0x" r)
     :s (str "0x" s)
     :v (js/parseInt v 16)}))

(defn- promise-race
  [promises]
  (js/Promise.
   (fn [resolve reject]
     (doseq [p promises]
       (-> p
           (.then resolve)
           (.catch reject))))))

(def ^:private typed-data-timeout-ms
  300000)

(defn- typed-data-timeout-promise
  []
  (js/Promise.
   (fn [_ reject]
     (platform/set-timeout!
      (fn []
        (reject
         (js/Error.
          "Signature request timed out. Open your wallet and approve the request, then try again.")))
      typed-data-timeout-ms))))

(defn- request-signature!
  [provider method params]
  (js/Promise.resolve
   (.request provider
             (clj->js {:method method
                       :params params}))))

(defn- signing-error-message
  [err]
  (or (some-> err .-message str)
      (some-> err (aget "message") str)
      (some-> err (aget "data") (aget "message") str)
      (some-> err (aget "error") (aget "message") str)
      (str err)))

(defn- fallback-compatible-signing-error?
  [err]
  (let [message (-> (signing-error-message err)
                    str
                    str/lower-case)]
    (or (str/includes? message "method not found")
        (str/includes? message "unsupported")
        (str/includes? message "not supported")
        (str/includes? message "invalid parameters")
        (str/includes? message "must provide an ethereum address")
        (str/includes? message "invalid message")
        (str/includes? message "invalid params"))))

(defn- try-sign-typed-data!
  [provider address typed-data]
  (let [typed-data-json (js/JSON.stringify (clj->js typed-data))
        typed-data-js (clj->js typed-data)
        attempts [{:method "eth_signTypedData_v4"
                   :params [address typed-data-json]}
                  {:method "eth_signTypedData_v4"
                   :params [address typed-data-js]}
                  {:method "eth_signTypedData"
                   :params [address typed-data-js]}]]
    (letfn [(attempt [remaining]
              (if-let [{:keys [method params]} (first remaining)]
                (-> (request-signature! provider method params)
                    (.catch (fn [err]
                              (if (and (next remaining)
                                       (fallback-compatible-signing-error? err))
                                (attempt (next remaining))
                                (js/Promise.reject
                                 (js/Error. (signing-error-message err)))))))
                (js/Promise.reject (js/Error. "Unable to sign typed data with the current wallet provider."))))]
      (attempt attempts))))

(defn- sign-typed-data!
  [address typed-data]
  (let [provider (.-ethereum js/globalThis)]
    (if-not provider
      (js/Promise.reject (js/Error. "No wallet provider found. Connect your wallet first."))
      (-> (promise-race [(try-sign-typed-data! provider address typed-data)
                         (typed-data-timeout-promise)])
          (.then (fn [sig]
                   (let [parts (split-signature sig)]
                     (clj->js (merge {:sig sig}
                                     parts)))))))))

(defn sign-approve-agent-action!
  [address action]
  (sign-typed-data! address (build-approve-agent-typed-data action)))

(defn sign-l1-action-with-private-key!
  "Signs an L1 action digest locally using an agent private key (no wallet prompt)."
  [private-key action nonce & {:keys [vault-address expires-after is-mainnet]
                               :or {vault-address nil
                                    expires-after nil
                                    is-mainnet true}}]
  (let [connection-id (compute-connection-id action
                                             nonce
                                             :vault-address vault-address
                                             :expires-after expires-after)
        digest (l1-agent-digest-bytes connection-id is-mainnet)
        private-key-bytes (hex->bytes private-key)]
    (-> (.signAsync secp
                    digest
                    private-key-bytes
                    (clj->js {:prehash false
                              :format "recovered"}))
        (.then
         (fn [sig-bytes]
           (let [recovery (bit-and (aget sig-bytes 0) 1)
                 r-bytes (.slice sig-bytes 1 33)
                 s-bytes (.slice sig-bytes 33 65)]
             (clj->js {:connectionId connection-id
                       :r (str "0x" (bytes->hex r-bytes))
                       :s (str "0x" (bytes->hex s-bytes))
                       :v (+ 27 recovery)})))))))

(defn sign-l1-action!
  "Uses window.ethereum to sign typed data. Returns a promise resolving
   to {:connectionId :r :s :v :sig}."
  [address action nonce & {:keys [vault-address expires-after is-mainnet]
                           :or {vault-address nil
                                expires-after nil
                                is-mainnet true}}]
  (let [connection-id (compute-connection-id action
                                             nonce
                                             :vault-address vault-address
                                             :expires-after expires-after)
        typed-data (build-typed-data connection-id :is-mainnet is-mainnet)]
    (-> (sign-typed-data! address typed-data)
        (.then (fn [sig]
                 (clj->js (merge {:connectionId connection-id}
                                 (js->clj sig :keywordize-keys true))))))))
