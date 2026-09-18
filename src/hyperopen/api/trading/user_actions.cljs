(ns hyperopen.api.trading.user-actions
  (:require [clojure.string :as str]
            [hyperopen.api.trading.debug-exchange-simulator :as debug-exchange-simulator]
            [hyperopen.api.trading.http :as http]
            [hyperopen.trading-crypto-modules :as trading-crypto-modules]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.provider-registry :as provider-registry]))

(defn parse-chain-id-int
  [value]
  (let [raw (some-> value str str/trim)]
    (when (seq raw)
      (let [hex? (str/starts-with? raw "0x")
            source (if hex? (subs raw 2) raw)
            base (if hex? 16 10)
            parsed (js/parseInt source base)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (js/Math.floor parsed))))))

(defn- normalize-signature-chain-id
  [value]
  (cond
    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        text))

    (number? value)
    (str "0x" (.toString (js/Math.floor value) 16))

    :else nil))

(defn- testnet-signature-chain-id-int
  []
  (parse-chain-id-int
   (agent-session/default-signature-chain-id-for-environment false)))

(defn- wallet-signature-chain-id
  "Wallets such as Rabby reject typed data whose domain chainId differs from
   the wallet's active chain, and Hyperliquid accepts any signatureChainId, so
   sign with whatever chain the wallet is on."
  [wallet-chain-id]
  (let [candidate (normalize-signature-chain-id wallet-chain-id)
        candidate-int (parse-chain-id-int candidate)]
    (when (and (number? candidate-int)
               (pos? candidate-int))
      (str "0x" (.toString candidate-int 16)))))

(defn- signing-context-for-chain-id
  [wallet-chain-id]
  (let [signature-chain-id (or (wallet-signature-chain-id wallet-chain-id)
                               (agent-session/default-signature-chain-id-for-environment true))
        chain-id-int (parse-chain-id-int signature-chain-id)]
    {:signature-chain-id signature-chain-id
     :hyperliquid-chain (if (= chain-id-int (testnet-signature-chain-id-int))
                          "Testnet"
                          "Mainnet")}))

(defn resolve-user-signing-context
  [store]
  (signing-context-for-chain-id (get-in @store [:wallet :chain-id])))

(defn- request-wallet-chain-id!
  "Asks the provider for its active chain right before signing; the stored
   chain id can be stale if a chainChanged event was missed."
  [store]
  (let [stored (get-in @store [:wallet :chain-id])]
    (try
      (let [provider (provider-registry/provider)]
        (if (and (some? provider)
                 (fn? (.-request provider)))
          (-> (.request provider (clj->js {:method "eth_chainId"}))
              (.then (fn [chain-id]
                       (or (wallet-signature-chain-id chain-id) stored)))
              (.catch (fn [_] stored)))
          (js/Promise.resolve stored)))
      (catch :default _
        (js/Promise.resolve stored)))))

(defn- next-user-signed-nonce!
  [store]
  (let [cursor (get-in @store [:wallet :user-signed-nonce-cursor])
        nonce (http/next-nonce cursor)]
    (swap! store assoc-in [:wallet :user-signed-nonce-cursor] nonce)
    nonce))

(defn approve-agent!
  [store address action]
  (let [action* (atom action)
        crypto-promise (trading-crypto-modules/load-trading-crypto-module!)]
    (-> (request-wallet-chain-id! store)
        (.then (fn [wallet-chain-id]
                 (when-let [signature-chain-id (wallet-signature-chain-id wallet-chain-id)]
                   (swap! action* assoc :signatureChainId signature-chain-id))
                 crypto-promise))
        (.then (fn [crypto]
                 ((:sign-approve-agent-action! crypto) address @action*)))
        (.then (fn [sig]
                 (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)
                       action @action*
                       payload {:action action
                                :nonce (:nonce action)
                                :signature {:r r
                                            :s s
                                            :v v}}]
                   (or (debug-exchange-simulator/simulated-fetch-response [[:approveAgent]])
                       (http/json-post! http/exchange-url payload))))))))

(defn- sign-and-post-user-action!
  [store address action nonce-field sign-action-key]
  (let [nonce (next-user-signed-nonce! store)
        action* (atom nil)
        crypto-promise (trading-crypto-modules/load-trading-crypto-module!)]
    (-> (request-wallet-chain-id! store)
        (.then (fn [wallet-chain-id]
                 (let [{:keys [signature-chain-id hyperliquid-chain]}
                       (signing-context-for-chain-id wallet-chain-id)]
                   (reset! action* (-> action
                                       (assoc :signatureChainId signature-chain-id
                                              :hyperliquidChain hyperliquid-chain)
                                       (assoc nonce-field nonce))))
                 crypto-promise))
        (.then (fn [crypto]
                 (when-not (contains? crypto sign-action-key)
                   (throw (js/Error.
                           (str "Missing trading crypto signer: " sign-action-key))))
                 ((get crypto sign-action-key) address @action*)))
        (.then (fn [sig]
                 (let [{:keys [r s v]} (js->clj sig :keywordize-keys true)
                       signature {:r r
                                  :s s
                                  :v v}]
                   (-> (http/post-signed-action! @action* nonce signature)
                       (.then http/parse-json!))))))))

(defn submit-usd-class-transfer! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-usd-class-transfer-action!))

(defn submit-send-asset! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-send-asset-action!))

(defn submit-c-deposit! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-c-deposit-action!))

(defn submit-c-withdraw! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-c-withdraw-action!))

(defn submit-token-delegate! [store address action]
  (sign-and-post-user-action! store address action :nonce :sign-token-delegate-action!))

(defn submit-withdraw3! [store address action]
  (sign-and-post-user-action! store address action :time :sign-withdraw3-action!))
