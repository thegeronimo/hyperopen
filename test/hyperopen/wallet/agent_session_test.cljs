(ns hyperopen.wallet.agent-session-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.agent-session-crypto :as agent-session-crypto]))

(def ^:private baseline-load-agent-session-by-mode
  agent-session/load-agent-session-by-mode)

(def ^:private baseline-persist-agent-session-by-mode!
  agent-session/persist-agent-session-by-mode!)

(def ^:private baseline-clear-agent-session-by-mode!
  agent-session/clear-agent-session-by-mode!)

(def ^:private wallet-address
  "0x1234567890abcdef1234567890abcdef12345678")

(use-fixtures
  :each
  {:before (fn []
             (set! agent-session/load-agent-session-by-mode baseline-load-agent-session-by-mode)
             (set! agent-session/persist-agent-session-by-mode! baseline-persist-agent-session-by-mode!)
             (set! agent-session/clear-agent-session-by-mode! baseline-clear-agent-session-by-mode!))
   :after (fn []
            (set! agent-session/load-agent-session-by-mode baseline-load-agent-session-by-mode)
            (set! agent-session/persist-agent-session-by-mode! baseline-persist-agent-session-by-mode!)
            (set! agent-session/clear-agent-session-by-mode! baseline-clear-agent-session-by-mode!))})

(defn- fake-storage []
  (let [store (atom {})]
    #js {:setItem (fn [k v] (swap! store assoc (str k) (str v)))
         :getItem (fn [k] (get @store (str k)))
         :removeItem (fn [k] (swap! store dissoc (str k)))
         :clear (fn [] (reset! store {}))}))

(defn- with-test-local-storage [f]
  (let [original-local-storage (.-localStorage js/globalThis)
        storage (fake-storage)]
    (set! (.-localStorage js/globalThis) storage)
    (try
      (f storage)
      (finally
        (set! (.-localStorage js/globalThis) original-local-storage)))))

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

(deftest default-agent-state-shape-test
  (let [agent (agent-session/default-agent-state)]
    (is (= :not-ready (:status agent)))
    (is (= :local (:storage-mode agent)))
    (is (nil? (:agent-address agent)))
    (is (nil? (:last-approved-at agent)))
    (is (nil? (:error agent)))
    (is (nil? (:nonce-cursor agent)))))

(deftest storage-key-normalizes-wallet-address-test
  (is (= "hyperopen:agent-session:v1:0x1234567890abcdef1234567890abcdef12345678"
         (agent-session/session-storage-key "0x1234567890ABCDEF1234567890ABCDEF12345678"))))

(deftest storage-mode-preference-roundtrip-and-normalization-test
  (with-test-local-storage
    (fn [storage]
      (is (= :local (agent-session/load-storage-mode-preference)))
      (is (true? (agent-session/persist-storage-mode-preference! :local)))
      (is (= "local" (.getItem storage "hyperopen:agent-storage-mode:v1")))
      (is (= :local (agent-session/load-storage-mode-preference)))
      (.setItem storage "hyperopen:agent-storage-mode:v1" "SESSION")
      (is (= :session (agent-session/load-storage-mode-preference)))
      (.setItem storage "hyperopen:agent-storage-mode:v1" "unknown")
      (is (= :local (agent-session/load-storage-mode-preference))))))

(deftest build-approve-agent-action-adds-protocol-fields-test
  (let [action (agent-session/build-approve-agent-action
                "0x1234567890abcdef1234567890abcdef12345678"
                1700000001111)]
    (is (= "approveAgent" (:type action)))
    (is (= "0x1234567890abcdef1234567890abcdef12345678" (:agentAddress action)))
    (is (= 1700000001111 (:nonce action)))
    (is (= "Mainnet" (:hyperliquidChain action)))
    (is (= "0xa4b1" (:signatureChainId action)))
    (is (nil? (:agentName action)))))

(deftest build-approve-agent-action-uses-testnet-chain-id-when-requested-test
  (let [action (agent-session/build-approve-agent-action
                "0x1234567890abcdef1234567890abcdef12345678"
                1700000001111
                :is-mainnet false)]
    (is (= "Testnet" (:hyperliquidChain action)))
    (is (= "0x66eee" (:signatureChainId action)))))

(deftest create-agent-credentials-generates-hex-keypair-test
  (let [{:keys [private-key agent-address]} (agent-session-crypto/create-agent-credentials!)]
    (is (re-matches #"0x[0-9a-f]{64}" private-key))
    (is (re-matches #"0x[0-9a-f]{40}" agent-address))))

(deftest persist-load-and-clear-agent-session-test
  (let [storage (fake-storage)
        session {:agent-address "0x9999999999999999999999999999999999999999"
                 :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                 :last-approved-at 1700000002222
                 :nonce-cursor 1700000002222}]
    (agent-session/persist-agent-session! storage wallet-address session)
    (is (= session
           (agent-session/load-agent-session storage wallet-address)))
    (agent-session/clear-agent-session! storage wallet-address)
    (is (nil? (agent-session/load-agent-session storage wallet-address)))))

(deftest signature-chain-id-and-storage-mode-normalization-helpers-test
  (is (= "0xa4b1" (agent-session/default-signature-chain-id-for-environment true)))
  (is (= "0x66eee" (agent-session/default-signature-chain-id-for-environment false)))
  (is (= :session (agent-session/normalize-storage-mode :session)))
  (is (= :session (agent-session/normalize-storage-mode " SESSION ")))
  (is (= :local (agent-session/normalize-storage-mode :local)))
  (is (= :local (agent-session/normalize-storage-mode "unknown")))
  (is (= :local (agent-session/normalize-storage-mode nil))))

(deftest default-agent-state-and-address-normalization-variants-test
  (let [agent (agent-session/default-agent-state :storage-mode "SESSION")]
    (is (= :session (:storage-mode agent))))
  (is (= "0x1234567890abcdef1234567890abcdef12345678"
         (agent-session/normalize-wallet-address
          " 0x1234567890ABCDEF1234567890ABCDEF12345678 ")))
  (is (nil? (agent-session/normalize-wallet-address "0xabc123")))
  (is (nil? (agent-session/normalize-wallet-address nil))))

(deftest build-approve-agent-action-supports-optional-name-and-signature-chain-id-test
  (let [action (agent-session/build-approve-agent-action
                "0x1234567890abcdef1234567890abcdef12345678"
                1700000001111
                :agent-name "Desk Agent"
                :signature-chain-id "0x1234")]
    (is (= "Desk Agent" (:agentName action)))
    (is (= "0x1234" (:signatureChainId action)))))

(deftest storage-mode-preference-fallbacks-when-storage-unavailable-or-throws-test
  (let [original-local-storage (.-localStorage js/globalThis)]
    (try
      (set! (.-localStorage js/globalThis) nil)
      (is (= :local (agent-session/load-storage-mode-preference)))
      (is (false? (agent-session/persist-storage-mode-preference! :session)))
      (set! (.-localStorage js/globalThis)
            #js {:getItem (fn [_]
                            (throw (js/Error. "read boom")))
                 :setItem (fn [_ _]
                            (throw (js/Error. "write boom")))})
      (is (= :local (agent-session/load-storage-mode-preference)))
      (is (false? (agent-session/persist-storage-mode-preference! :session)))
      (finally
        (set! (.-localStorage js/globalThis) original-local-storage)))))

(deftest persist-load-and-clear-agent-session-handle-invalid-and-throwing-storage-test
  (let [throwing-storage #js {:setItem (fn [_ _]
                                         (throw (js/Error. "set boom")))
                              :getItem (fn [_]
                                         (throw (js/Error. "get boom")))
                              :removeItem (fn [_]
                                            (throw (js/Error. "remove boom")))}
        valid-session {:agent-address "0x9999999999999999999999999999999999999999"
                       :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                       :last-approved-at 1700000002222.9
                       :nonce-cursor 1700000003333.4}]
    (is (nil? (agent-session/persist-agent-session! nil wallet-address valid-session)))
    (is (nil? (agent-session/persist-agent-session! (fake-storage) wallet-address {:agent-address "0xabc"})))
    (is (false? (agent-session/persist-agent-session! throwing-storage wallet-address valid-session)))
    (let [storage (fake-storage)]
      (agent-session/persist-agent-session! storage wallet-address valid-session)
      (is (= {:agent-address "0x9999999999999999999999999999999999999999"
              :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :last-approved-at 1700000002222
              :nonce-cursor 1700000003333}
             (agent-session/load-agent-session storage wallet-address))))
    (is (nil? (agent-session/load-agent-session nil wallet-address)))
    (is (nil? (agent-session/load-agent-session throwing-storage wallet-address)))
    (is (false? (agent-session/clear-agent-session! throwing-storage wallet-address)))
    (is (nil? (agent-session/clear-agent-session! nil wallet-address)))))

(deftest load-agent-session-returns-nil-for-empty-or-malformed-json-test
  (let [empty-storage #js {:getItem (fn [_] "")
                           :setItem (fn [_ _] nil)
                           :removeItem (fn [_] nil)}
        malformed-storage #js {:getItem (fn [_] "{")
                               :setItem (fn [_ _] nil)
                               :removeItem (fn [_] nil)}
        invalid-shape-storage #js {:getItem (fn [_]
                                              "{\"agent-address\":123,\"private-key\":\"\"}")
                                   :setItem (fn [_ _] nil)
                                   :removeItem (fn [_] nil)}]
    (is (nil? (agent-session/load-agent-session empty-storage wallet-address)))
    (is (nil? (agent-session/load-agent-session malformed-storage wallet-address)))
    (is (nil? (agent-session/load-agent-session invalid-shape-storage wallet-address)))))

(deftest by-mode-storage-wrappers-target-local-and-session-storage-test
  (with-test-storages
    (fn [{:keys [local]}]
      (let [session-payload {:agent-address "0x9999999999999999999999999999999999999999"
                             :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                             :last-approved-at 1700000002222
                             :nonce-cursor 1700000002222}
            key (agent-session/session-storage-key wallet-address)]
        (is (true? (agent-session/persist-agent-session-by-mode!
                    wallet-address
                    :session
                    session-payload)))
        (is (= session-payload
               (agent-session/load-agent-session-by-mode wallet-address :session)))
        (is (nil? (.getItem local key)))
        (is (true? (agent-session/persist-agent-session-by-mode!
                    wallet-address
                    :local
                    session-payload)))
        (is (= session-payload
               (agent-session/load-agent-session-by-mode wallet-address :local)))
        (is (some? (.getItem local key)))
        (is (true? (agent-session/clear-agent-session-by-mode! wallet-address :session)))
        (is (true? (agent-session/clear-agent-session-by-mode! wallet-address :local)))
        (is (nil? (agent-session/load-agent-session-by-mode wallet-address :session)))
        (is (nil? (agent-session/load-agent-session-by-mode wallet-address :local)))))))
