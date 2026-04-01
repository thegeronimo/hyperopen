(ns hyperopen.wallet.agent-session-crypto
  (:require [clojure.string :as str]
            ["@noble/secp256k1" :as secp]
            ["../vendor/keccak" :as keccak]))

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
