(ns hyperopen.utils.hl-signing
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
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

(defn- single-byte [n]
  (doto (js/Uint8Array. 1)
    (aset 0 n)))

(defn- parse-chain-id [signature-chain-id]
  (let [raw (str (or signature-chain-id "0x66eee"))
        base (if (str/starts-with? raw "0x") 16 10)
        source (if (str/starts-with? raw "0x") (subs raw 2) raw)]
    (js/parseInt source base)))

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

(defn split-signature [sig]
  (let [hex (strip-0x sig)
        r (subs hex 0 64)
        s (subs hex 64 128)
        v (subs hex 128 130)]
    {:r (str "0x" r)
     :s (str "0x" s)
     :v (js/parseInt v 16)}))

(defn- sign-typed-data!
  [address typed-data]
  (let [payload (clj->js typed-data)
        msg (js/JSON.stringify payload)]
    (-> (.request (.-ethereum js/window)
                  (clj->js {:method "eth_signTypedData_v4"
                            :params [address msg]}))
        (.then (fn [sig]
                 (let [parts (split-signature sig)]
                   (clj->js (merge {:sig sig}
                                   parts))))))))

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
