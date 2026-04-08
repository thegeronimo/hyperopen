(ns hyperopen.websocket.agent-session-coverage-test
  (:require [cljs.test :refer-macros [deftest is]]
            ["@noble/secp256k1" :as secp]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.agent-session-crypto :as agent-session-crypto]))

(def ^:private wallet-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private valid-session
  {:agent-address "0x9999999999999999999999999999999999999999"
   :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :last-approved-at 1700000002222.9
   :nonce-cursor 1700000003333.4})

(defn- fake-storage []
  (let [store (atom {})]
    #js {:setItem (fn [k v] (swap! store assoc (str k) (str v)))
         :getItem (fn [k] (get @store (str k)))
         :removeItem (fn [k] (swap! store dissoc (str k)))}))

(defn- with-test-storages [f]
  (let [original-local-storage (.-localStorage js/globalThis)
        original-session-storage (.-sessionStorage js/globalThis)
        local-storage (fake-storage)
        session-storage (fake-storage)]
    (set! (.-localStorage js/globalThis) local-storage)
    (set! (.-sessionStorage js/globalThis) session-storage)
    (try
      (f {:local local-storage
          :session session-storage})
      (finally
        (set! (.-localStorage js/globalThis) original-local-storage)
        (set! (.-sessionStorage js/globalThis) original-session-storage)))))

(defn- uint8-array [values]
  (js/Uint8Array. (clj->js values)))

(deftest ws-agent-session-defaults-and-name-helpers-coverage-test
  (is (= agent-session/zero-address
         "0x0000000000000000000000000000000000000000"))
  (is (= "0xa4b1" (agent-session/default-signature-chain-id-for-environment true)))
  (is (= "0x66eee" (agent-session/default-signature-chain-id-for-environment false)))
  (is (= :session (agent-session/normalize-storage-mode :session)))
  (is (= :session (agent-session/normalize-storage-mode " SESSION ")))
  (is (= :local (agent-session/normalize-storage-mode "unknown")))
  (is (= :local (agent-session/normalize-storage-mode nil)))
  (is (= :session (:storage-mode (agent-session/default-agent-state :storage-mode "SESSION"))))
  (is (nil? (:agent-address (agent-session/default-agent-state))))
  (is (= 14 (agent-session/normalize-agent-valid-days 14.8)))
  (is (= 21 (agent-session/normalize-agent-valid-days " 21 ")))
  (is (= agent-session/max-agent-valid-days
         (agent-session/normalize-agent-valid-days 999)))
  (is (nil? (agent-session/normalize-agent-valid-days 0)))
  (is (nil? (agent-session/normalize-agent-valid-days "nan")))
  (is (= {:name nil
          :valid-until-ms nil}
         (agent-session/parse-agent-name-valid-until "  ")))
  (is (= {:name "Desk Agent"
          :valid-until-ms 1700000005555}
         (agent-session/parse-agent-name-valid-until
          " Desk Agent valid_until 1700000005555 ")))
  (is (= {:name "Desk Agent valid_until nope"
          :valid-until-ms nil}
         (agent-session/parse-agent-name-valid-until "Desk Agent valid_until nope")))
  (is (= "Hyperopen abc123"
         (agent-session/normalize-device-label " Hyperopen Device abc123 ")))
  (is (= "1234567890abcdef"
         (agent-session/normalize-device-label "1234567890abcdefXYZ")))
  (is (nil? (agent-session/format-agent-name-with-valid-until nil 1700000000000 30)))
  (is (= "Desk Agent"
         (agent-session/format-agent-name-with-valid-until " Desk Agent " nil 30)))
  (is (= "Desk Agent"
         (agent-session/format-agent-name-with-valid-until " Desk Agent " 1700000000000 "nan")))
  (is (= "Desk Agent valid_until 1702592000000"
         (agent-session/format-agent-name-with-valid-until " Desk Agent " 1700000000000 30))))

(deftest ws-agent-session-storage-and-preference-coverage-test
  (with-test-storages
    (fn [{:keys [local session]}]
      (is (= "0x1234567890abcdef1234567890abcdef12345678"
             (agent-session/normalize-wallet-address
              " 0x1234567890ABCDEF1234567890ABCDEF12345678 ")))
      (is (nil? (agent-session/normalize-wallet-address "0xabc123")))
      (is (= "hyperopen:agent-session:v1:0x1234567890abcdef1234567890abcdef12345678"
             (agent-session/session-storage-key wallet-address)))
      (is (nil? (agent-session/session-storage-key "0xabc123")))

      (is (= :local (agent-session/load-storage-mode-preference)))
      (is (true? (agent-session/persist-storage-mode-preference! :session)))
      (is (= :session (agent-session/load-storage-mode-preference)))
      (.setItem local "hyperopen:agent-storage-mode:v1" "unknown")
      (is (= :local (agent-session/load-storage-mode-preference)))
      (.setItem local "hyperopen:agent-device-label:v1" "Hyperopen Device 72905e")
      (is (= "Hyperopen 72905e"
             (agent-session/load-device-label)))
      (is (= "Hyperopen 72905e"
             (.getItem local "hyperopen:agent-device-label:v1")))

      (is (true? (agent-session/persist-agent-session-by-mode!
                  wallet-address
                  :session
                  valid-session)))
      (is (nil? (.getItem local (agent-session/session-storage-key wallet-address))))
      (is (= {:agent-address "0x9999999999999999999999999999999999999999"
              :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :last-approved-at 1700000002222
              :nonce-cursor 1700000003333}
             (agent-session/load-agent-session-by-mode wallet-address :session)))
      (is (true? (agent-session/persist-agent-session-by-mode!
                  wallet-address
                  :local
                  valid-session)))
      (is (some? (.getItem local (agent-session/session-storage-key wallet-address))))
      (is (= {:agent-address "0x9999999999999999999999999999999999999999"
              :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :last-approved-at 1700000002222
              :nonce-cursor 1700000003333}
             (agent-session/load-agent-session-by-mode wallet-address :local)))
      (is (true? (agent-session/clear-agent-session-by-mode! wallet-address :session)))
      (is (true? (agent-session/clear-agent-session-by-mode! wallet-address :local)))
      (is (nil? (agent-session/load-agent-session-by-mode wallet-address :session)))
      (is (nil? (agent-session/load-agent-session-by-mode wallet-address :local)))
      (is (nil? (agent-session/persist-agent-session! nil wallet-address valid-session)))
      (is (nil? (agent-session/load-agent-session nil wallet-address)))
      (is (nil? (agent-session/clear-agent-session! nil wallet-address)))

      (let [invalid-shape-storage #js {:getItem (fn [_]
                                                  "{\"agent-address\":123,\"private-key\":\"\"}")}
            malformed-storage #js {:getItem (fn [_] "{")}
            throwing-storage #js {:getItem (fn [_]
                                             (throw (js/Error. "get boom")))
                                  :setItem (fn [_ _]
                                             (throw (js/Error. "set boom")))
                                  :removeItem (fn [_]
                                                (throw (js/Error. "remove boom")))}]
        (is (nil? (agent-session/load-agent-session invalid-shape-storage wallet-address)))
        (is (nil? (agent-session/load-agent-session malformed-storage wallet-address)))
        (is (false? (agent-session/persist-agent-session! throwing-storage wallet-address valid-session)))
        (is (nil? (agent-session/load-agent-session throwing-storage wallet-address)))
        (is (false? (agent-session/clear-agent-session! throwing-storage wallet-address)))))))

(deftest ws-agent-session-private-helpers-and-approve-action-coverage-test
  (let [bytes (uint8-array [0 1 15 16 255])]
    (is (= "00010f10ff"
           (@#'hyperopen.wallet.agent-session-crypto/bytes->hex bytes)))
    (is (= [0 1 15 16 255]
           (vec (@#'hyperopen.wallet.agent-session-crypto/hex->bytes "0x00010f10ff"))))
    (is (= [0 1 15 16 255]
           (vec (@#'hyperopen.wallet.agent-session-crypto/hex->bytes "00010f10ff")))))
  (is (= "0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a"
         (agent-session-crypto/private-key->agent-address
          "0x1111111111111111111111111111111111111111111111111111111111111111")))
  (is (= {:agent-address "0x9999999999999999999999999999999999999999"
          :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          :last-approved-at 1700000002222
          :nonce-cursor 1700000003333}
         (@#'hyperopen.wallet.agent-session/sanitize-agent-session valid-session)))
  (is (nil? (@#'hyperopen.wallet.agent-session/sanitize-agent-session
             {:agent-address ""
              :private-key "0xabc"})))
  (is (= {:type "approveAgent"
          :agentAddress "0x1234567890abcdef1234567890abcdef12345678"
          :nonce 1700000001111
          :hyperliquidChain "Mainnet"
          :signatureChainId "0xa4b1"}
         (agent-session/build-approve-agent-action wallet-address 1700000001111)))
  (is (= {:type "approveAgent"
          :agentAddress "0x1234567890abcdef1234567890abcdef12345678"
          :nonce 1700000001111
          :hyperliquidChain "Testnet"
          :signatureChainId "0x1234"
          :agentName "Desk Agent"}
         (agent-session/build-approve-agent-action
          wallet-address
          1700000001111
          :agent-name "Desk Agent"
          :is-mainnet false
          :signature-chain-id "0x1234"))))

(deftest ws-agent-session-random-private-key-source-selection-coverage-test
  (let [utils (.-utils secp)
        crypto (.-crypto js/globalThis)
        original-random-secret-key (some-> utils .-randomSecretKey)
        original-random-private-key (some-> utils .-randomPrivateKey)
        original-valid-secret-key (some-> utils .-isValidSecretKey)
        original-valid-private-key (some-> utils .-isValidPrivateKey)
        original-get-random-values (some-> crypto .-getRandomValues)]
    (try
      (set! (.-randomSecretKey utils)
            (fn []
              (uint8-array (repeat 32 17))))
      (set! (.-randomPrivateKey utils) nil)
      (is (= (vec (repeat 32 17))
             (vec (@#'hyperopen.wallet.agent-session-crypto/random-private-key-bytes))))

      (set! (.-randomSecretKey utils) nil)
      (set! (.-randomPrivateKey utils)
            (fn []
              (uint8-array (repeat 32 34))))
      (is (= (vec (repeat 32 34))
             (vec (@#'hyperopen.wallet.agent-session-crypto/random-private-key-bytes))))

      (let [calls (atom 0)]
        (set! (.-randomPrivateKey utils) nil)
        (set! (.-isValidSecretKey utils)
              (fn [_candidate]
                (= 2 (swap! calls inc))))
        (set! (.-getRandomValues crypto)
              (fn [candidate]
                (dotimes [idx (.-length candidate)]
                  (aset candidate idx (inc idx)))
                candidate))
        (is (= (vec (range 1 33))
               (vec (@#'hyperopen.wallet.agent-session-crypto/random-private-key-bytes))))
        (is (= 2 @calls)))

      (set! (.-isValidSecretKey utils) nil)
      (set! (.-isValidPrivateKey utils)
            (fn [_candidate] true))
      (set! (.-getRandomValues crypto)
            (fn [candidate]
              (dotimes [idx (.-length candidate)]
                (aset candidate idx 255))
              candidate))
      (is (= (vec (repeat 32 255))
             (vec (@#'hyperopen.wallet.agent-session-crypto/random-private-key-bytes))))

      (set! (.-randomSecretKey utils) nil)
      (set! (.-randomPrivateKey utils) nil)
      (set! (.-isValidSecretKey utils) nil)
      (set! (.-isValidPrivateKey utils) nil)
      (set! (.-getRandomValues crypto) nil)
      (is (thrown-with-msg?
           js/Error
           #"Secure random unavailable"
           (@#'hyperopen.wallet.agent-session-crypto/random-private-key-bytes)))
      (finally
        (set! (.-randomSecretKey utils) original-random-secret-key)
        (set! (.-randomPrivateKey utils) original-random-private-key)
        (set! (.-isValidSecretKey utils) original-valid-secret-key)
        (set! (.-isValidPrivateKey utils) original-valid-private-key)
        (set! (.-getRandomValues crypto) original-get-random-values)))))

(deftest ws-agent-session-create-agent-credentials-coverage-test
  (with-redefs [hyperopen.wallet.agent-session-crypto/random-private-key-bytes
                (fn []
                  (uint8-array (concat [0 1 15 16]
                                       (repeat 28 255))))
                hyperopen.wallet.agent-session-crypto/private-key->agent-address
                (fn [private-key]
                  (is (= "0x00010f10ffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                         private-key))
                  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")]
    (is (= {:private-key "0x00010f10ffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
            :agent-address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
           (agent-session-crypto/create-agent-credentials!)))))
