(ns hyperopen.funding.effects
  (:require [clojure.string :as str]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.application.deposit-submit :as deposit-submit]
            [hyperopen.funding.application.lifecycle-guards :as lifecycle-guards]
            [hyperopen.funding.application.hyperunit-submit :as hyperunit-submit]
            [hyperopen.funding.application.hyperunit-query :as hyperunit-query]
            [hyperopen.funding.application.lifecycle-polling :as lifecycle-polling]
            [hyperopen.funding.application.submit-effects :as submit-effects]
            [hyperopen.funding.domain.lifecycle :as funding-lifecycle]
            [hyperopen.funding.domain.lifecycle-operations :as lifecycle-ops]
            [hyperopen.funding.infrastructure.erc20-rpc :as erc20-rpc]
            [hyperopen.funding.infrastructure.hyperunit-address-client :as hyperunit-address-client]
            [hyperopen.funding.infrastructure.hyperunit-client :as hyperunit-client]
            [hyperopen.funding.infrastructure.route-clients :as route-clients]
            [hyperopen.funding.infrastructure.wallet-rpc :as wallet-rpc]
            [hyperopen.wallet.core :as wallet]))

(def ^:private arbitrum-mainnet-chain-id
  "0xa4b1")

(def ^:private arbitrum-sepolia-chain-id
  "0x66eee")

(def ^:private default-deposit-chain-id
  arbitrum-mainnet-chain-id)

(def ^:private arbitrum-mainnet-chain-id-decimal
  "42161")

(def ^:private arbitrum-usdt-address
  "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9")

(def ^:private hypercore-chain-id-decimal
  "1337")

(def ^:private hypercore-usdh-address
  "0x2000000000000000000000000000000000000168")

(def ^:private across-swap-approval-base-url
  "https://app.across.to/api/swap/approval")

(def ^:private hyperunit-mainnet-base-url
  "https://api.hyperunit.xyz")

(def ^:private hyperunit-testnet-base-url
  "https://api.hyperunit-testnet.xyz")

(def ^:private hyperunit-operations-poll-default-delay-ms
  3000)

(def ^:private hyperunit-operations-poll-min-delay-ms
  1000)

(def ^:private hyperunit-operations-poll-max-delay-ms
  60000)

(def ^:private chain-config-by-id
  {arbitrum-mainnet-chain-id {:chain-id arbitrum-mainnet-chain-id
                              :chain-name "Arbitrum One"
                              :network-label "Arbitrum"
                              :rpc-url "https://arb1.arbitrum.io/rpc"
                              :explorer-url "https://arbiscan.io"
                              :usdc-address "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
                              :bridge-address "0x2df1c51e09aecf9cacb7bc98cb1742757f163df7"}
   arbitrum-sepolia-chain-id {:chain-id arbitrum-sepolia-chain-id
                              :chain-name "Arbitrum Sepolia"
                              :network-label "Arbitrum Sepolia"
                              :rpc-url "https://sepolia-rollup.arbitrum.io/rpc"
                              :explorer-url "https://sepolia.arbiscan.io"
                              :usdc-address "0x75faf114eafb1bdbe2f0316df893fd58ce46aa4d"
                              :bridge-address "0xccd552b49b4383aa0a4f45689de3e29f142fa3ad"}})

(defn- fallback-exchange-response-error
  [resp]
  (or (:error resp)
      (:message resp)
      (:response resp)
      "Unknown exchange error"))

(defn- fallback-runtime-error-message
  [err]
  (or (some-> err .-message)
      (str err)))

(defn- update-funding-submit-error
  [state error-text]
  (-> state
      (assoc-in [:funding-ui :modal :submitting?] false)
      (assoc-in [:funding-ui :modal :error] error-text)))

(defn- set-funding-submit-error!
  [store show-toast! error-text]
  (swap! store update-funding-submit-error error-text)
  (show-toast! store :error error-text))

(defn- close-funding-modal!
  [store default-funding-modal-state]
  (swap! store assoc-in [:funding-ui :modal] (default-funding-modal-state)))

(defn- refresh-after-funding-submit!
  [store dispatch! address]
  (when (and (fn? dispatch!)
             (string? address))
    (dispatch! store nil [[:actions/load-user-data address]])))

(defn- normalize-chain-id
  [value]
  (let [raw (some-> value str str/trim)]
    (when (seq raw)
      (let [hex? (str/starts-with? raw "0x")
            source (if hex? (subs raw 2) raw)
            base (if hex? 16 10)
            parsed (js/parseInt source base)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (str "0x" (.toString (js/Math.floor parsed) 16)))))))

(defn- normalize-address
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (string? text)
               (re-matches #"^0x[0-9a-f]{40}$" text))
      text)))

(defn- parse-usdc-units
  [amount]
  (let [text (some-> amount str str/trim)]
    (when (and (seq text)
               (re-matches #"^(?:0|[1-9]\d*)(?:\.\d{1,6})?$" text))
      (let [[whole fract] (str/split text #"\.")
            whole* (or whole "0")
            fract* (subs (str (or fract "") "000000") 0 6)
            whole-units (* (js/BigInt whole*) (js/BigInt "1000000"))
            fract-units (js/BigInt fract*)]
        (+ whole-units fract-units)))))

(defn- parse-usdh-units
  [amount]
  (let [text (some-> amount str str/trim)]
    (when (and (seq text)
               (re-matches #"^(?:0|[1-9]\d*)(?:\.\d{1,8})?$" text))
      (let [[whole fract] (str/split text #"\.")
            whole* (or whole "0")
            fract* (subs (str (or fract "") "00000000") 0 8)
            whole-units (* (js/BigInt whole*) (js/BigInt "100000000"))
            fract-units (js/BigInt fract*)]
        (+ whole-units fract-units)))))

(def ^:private usdh-route-max-units
  (parse-usdh-units "1000000"))

(def ^:private encode-erc20-transfer-call-data erc20-rpc/encode-erc20-transfer-call-data)
(def ^:private encode-erc20-approve-call-data erc20-rpc/encode-erc20-approve-call-data)

(defn- usdc-units->amount-text
  [value]
  (let [units (or value (js/BigInt "0"))
        raw (.toString units)
        padded (if (<= (count raw) 6)
                 (str (apply str (repeat (- 7 (count raw)) "0")) raw)
                 raw)
        split-idx (- (count padded) 6)
        whole (subs padded 0 split-idx)
        fract (subs padded split-idx)
        fract-trimmed (str/replace fract #"0+$" "")]
    (if (seq fract-trimmed)
      (str whole "." fract-trimmed)
      whole)))

(defn- wallet-error-message
  [err]
  (let [message (-> (or (some-> err .-message)
                        (some-> err (aget "message"))
                        (str err))
                    str
                    str/trim)
        code (or (some-> err .-code)
                 (some-> err (aget "code")))]
    (cond
      (= code 4001)
      "Deposit transaction rejected in wallet."

      (str/includes? (str/lower-case message) "user rejected")
      "Deposit transaction rejected in wallet."

      (seq message)
      message

      :else
      "Unknown wallet error")))

(def ^:private provider-request! wallet-rpc/provider-request!)

(defn- resolve-deposit-chain-config
  [store action]
  (let [action-chain-id (normalize-chain-id (:chainId action))
        wallet-chain-id (normalize-chain-id (get-in @store [:wallet :chain-id]))
        chain-id (or action-chain-id
                     (when (contains? chain-config-by-id wallet-chain-id)
                       wallet-chain-id)
                     default-deposit-chain-id)]
    (or (get chain-config-by-id chain-id)
        (get chain-config-by-id default-deposit-chain-id))))

(defn- resolve-hyperunit-base-urls
  [store]
  (let [wallet-chain-id (normalize-chain-id (get-in @store [:wallet :chain-id]))]
    (vec
     (distinct
      (if (= wallet-chain-id arbitrum-sepolia-chain-id)
        [hyperunit-testnet-base-url]
        [hyperunit-mainnet-base-url])))))

(defn- resolve-hyperunit-base-url
  [store]
  (or (first (resolve-hyperunit-base-urls store))
      hyperunit-mainnet-base-url))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defonce ^:private hyperunit-lifecycle-poll-tokens
  (atom {}))

(def ^:private with-hyperunit-base-url-fallbacks!
  hyperunit-client/with-hyperunit-base-url-fallbacks!)

(defn- canonical-token
  [value]
  (some-> value str str/trim str/lower-case))

(def ^:private chain-token-aliases
  {"arb" "arbitrum"
   "arbitrum" "arbitrum"
   "btc" "bitcoin"
   "bitcoin" "bitcoin"
   "eth" "ethereum"
   "ethereum" "ethereum"
   "hl" "hyperliquid"
   "hyperliquid" "hyperliquid"
   "sol" "solana"
   "solana" "solana"})

(def ^:private known-source-chain-tokens
  #{"arbitrum"
    "bitcoin"
    "ethereum"
    "hyperliquid"
    "solana"
    "monad"
    "plasma"})

(def ^:private hyperunit-source-chain-candidates-by-canonical
  {"bitcoin" ["bitcoin" "btc"]
   "ethereum" ["ethereum" "eth"]
   "solana" ["solana" "sol"]})

(defn- canonical-chain-token
  [value]
  (let [token (canonical-token value)]
    (when (seq token)
      (get chain-token-aliases token token))))

(defn- same-chain-token?
  [left right]
  (let [left* (canonical-chain-token left)
        right* (canonical-chain-token right)]
    (and (seq left*)
         (seq right*)
         (= left* right*))))

(defn- bitcoin-protocol-address?
  [address address-lower]
  (boolean
   (and (seq address-lower)
        (or (re-matches #"^bc1[a-z0-9]{20,}$" address-lower)
            (re-matches #"^tb1[a-z0-9]{20,}$" address-lower)
            (re-matches #"^[13][a-km-zA-HJ-NP-Z1-9]{20,}$" address)))))

(defn- evm-protocol-address?
  [address-lower]
  (boolean
   (and (seq address-lower)
        (re-matches #"^0x[0-9a-f]{40}$" address-lower))))

(defn- solana-protocol-address?
  [address]
  (boolean
   (and (seq address)
        (re-matches #"^[1-9A-HJ-NP-Za-km-z]{32,44}$" address))))

(def ^:private evm-protocol-source-chains
  ;; Monad and Plasma currently use EVM-style protocol addresses.
  #{"ethereum"
    "monad"
    "plasma"})

(defn- protocol-address-matches-source-chain?
  [source-chain address]
  (let [source* (canonical-chain-token source-chain)
        address* (non-blank-text address)
        address-lower (some-> address* str/lower-case)]
    (cond
      (= source* "bitcoin")
      (bitcoin-protocol-address? address* address-lower)

      (contains? evm-protocol-source-chains source*)
      (evm-protocol-address? address-lower)

      (= source* "solana")
      (solana-protocol-address? address*)

      :else false)))

(defn- hyperunit-source-chain-candidates
  [value]
  (let [canonical (canonical-chain-token value)]
    (vec (distinct
          (if-let [known (get hyperunit-source-chain-candidates-by-canonical canonical)]
            known
            (when (seq canonical)
              [canonical]))))))

(defn- normalize-asset-key
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value canonical-token keyword)
    :else nil))

(def request-hyperunit-operations!
  hyperunit-client/request-hyperunit-operations!)

(def request-hyperunit-estimate-fees!
  hyperunit-client/request-hyperunit-estimate-fees!)

(def request-hyperunit-withdrawal-queue!
  hyperunit-client/request-hyperunit-withdrawal-queue!)

(def request-hyperunit-generate-address!
  hyperunit-client/request-hyperunit-generate-address!)

(def ^:private lifecycle-poll-key
  lifecycle-guards/lifecycle-poll-key)

(defn- install-lifecycle-poll-token!
  [poll-key token]
  (lifecycle-guards/install-lifecycle-poll-token!
   hyperunit-lifecycle-poll-tokens
   poll-key
   token))

(defn- clear-lifecycle-poll-token!
  [poll-key token]
  (lifecycle-guards/clear-lifecycle-poll-token!
   hyperunit-lifecycle-poll-tokens
   poll-key
   token))

(defn- lifecycle-poll-token-active?
  [poll-key token]
  (lifecycle-guards/lifecycle-poll-token-active?
   hyperunit-lifecycle-poll-tokens
   poll-key
   token))

(def ^:private modal-active-for-lifecycle?
  lifecycle-guards/modal-active-for-lifecycle?)

(def ^:private modal-active-for-fee-estimate?
  lifecycle-guards/modal-active-for-fee-estimate?)

(defn- modal-active-for-withdraw-queue?
  ([store]
   (lifecycle-guards/modal-active-for-withdraw-queue? store))
  ([store expected-asset-key]
   (lifecycle-guards/modal-active-for-withdraw-queue? store expected-asset-key)))

(def ^:private op-sort-ms lifecycle-ops/op-sort-ms)

(def ^:private select-operation lifecycle-ops/select-operation)

(defn- select-existing-hyperunit-deposit-address
  [operations-response source-chain asset destination-address]
  (hyperunit-query/select-existing-hyperunit-deposit-address
   {:canonical-chain-token canonical-chain-token
    :canonical-token canonical-token
    :same-chain-token? same-chain-token?
    :op-sort-ms-fn op-sort-ms
    :non-blank-text non-blank-text
    :protocol-address-matches-source-chain? protocol-address-matches-source-chain?
    :known-source-chain-tokens known-source-chain-tokens}
   operations-response
   source-chain
   asset
   destination-address))

(defn- request-existing-hyperunit-deposit-address!
  [base-url base-urls destination-address source-chain asset]
  (hyperunit-query/request-existing-hyperunit-deposit-address!
   {:request-hyperunit-operations! request-hyperunit-operations!
    :select-existing-hyperunit-deposit-address select-existing-hyperunit-deposit-address}
   base-url
   base-urls
   destination-address
   source-chain
   asset))

(defn- prefetch-selected-hyperunit-deposit-address!
  [store]
  (hyperunit-query/prefetch-selected-hyperunit-deposit-address!
   {:funding-modal-view-model-fn funding-actions/funding-modal-view-model
    :normalize-asset-key normalize-asset-key
    :non-blank-text non-blank-text
    :canonical-chain-token canonical-chain-token
    :normalize-address normalize-address
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :request-existing-hyperunit-deposit-address! request-existing-hyperunit-deposit-address!}
   store))

(defn- lifecycle-next-delay-ms
  [now-ms lifecycle]
  (lifecycle-ops/lifecycle-next-delay-ms
   {:default-delay-ms hyperunit-operations-poll-default-delay-ms
    :min-delay-ms hyperunit-operations-poll-min-delay-ms
    :max-delay-ms hyperunit-operations-poll-max-delay-ms}
   now-ms
   lifecycle))

(defn- operation->lifecycle
  [operation direction asset-key now-ms]
  (lifecycle-ops/operation->lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   operation
   direction
   asset-key
   now-ms))

(defn- awaiting-lifecycle
  [direction asset-key now-ms]
  (lifecycle-ops/awaiting-lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   direction
   asset-key
   now-ms))

(defn- awaiting-deposit-lifecycle
  [asset-key now-ms]
  (lifecycle-ops/awaiting-deposit-lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   asset-key
   now-ms))

(defn- awaiting-withdraw-lifecycle
  [asset-key now-ms]
  (lifecycle-ops/awaiting-withdraw-lifecycle
   funding-lifecycle/normalize-hyperunit-lifecycle
   asset-key
   now-ms))

(defn- fetch-hyperunit-withdrawal-queue!
  [opts]
  (hyperunit-query/fetch-hyperunit-withdrawal-queue!
   {:modal-active-for-withdraw-queue? modal-active-for-withdraw-queue?
    :normalize-hyperunit-withdrawal-queue funding-lifecycle/normalize-hyperunit-withdrawal-queue
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :non-blank-text non-blank-text
    :fallback-runtime-error-message fallback-runtime-error-message}
   opts))

(defn- start-hyperunit-lifecycle-polling!
  [opts]
  (lifecycle-polling/start-hyperunit-lifecycle-polling!
   (merge {:lifecycle-poll-key-fn lifecycle-poll-key
           :install-lifecycle-poll-token! install-lifecycle-poll-token!
           :clear-lifecycle-poll-token! clear-lifecycle-poll-token!
           :lifecycle-poll-token-active? lifecycle-poll-token-active?
           :modal-active-for-lifecycle? modal-active-for-lifecycle?
           :normalize-hyperunit-lifecycle funding-lifecycle/normalize-hyperunit-lifecycle
           :select-operation select-operation
           :operation->lifecycle operation->lifecycle
           :awaiting-lifecycle awaiting-lifecycle
           :lifecycle-next-delay-ms lifecycle-next-delay-ms
           :hyperunit-lifecycle-terminal? funding-lifecycle/hyperunit-lifecycle-terminal?
           :fetch-hyperunit-withdrawal-queue! fetch-hyperunit-withdrawal-queue!
           :non-blank-text non-blank-text
           :default-poll-delay-ms hyperunit-operations-poll-default-delay-ms
           :runtime-error-message fallback-runtime-error-message}
          opts)))

(defn- start-hyperunit-deposit-lifecycle-polling!
  [opts]
  (lifecycle-polling/start-hyperunit-deposit-lifecycle-polling!
   (merge {:lifecycle-poll-key-fn lifecycle-poll-key
           :install-lifecycle-poll-token! install-lifecycle-poll-token!
           :clear-lifecycle-poll-token! clear-lifecycle-poll-token!
           :lifecycle-poll-token-active? lifecycle-poll-token-active?
           :modal-active-for-lifecycle? modal-active-for-lifecycle?
           :normalize-hyperunit-lifecycle funding-lifecycle/normalize-hyperunit-lifecycle
           :select-operation select-operation
           :operation->lifecycle operation->lifecycle
           :awaiting-lifecycle awaiting-lifecycle
           :lifecycle-next-delay-ms lifecycle-next-delay-ms
           :hyperunit-lifecycle-terminal? funding-lifecycle/hyperunit-lifecycle-terminal?
           :fetch-hyperunit-withdrawal-queue! fetch-hyperunit-withdrawal-queue!
           :non-blank-text non-blank-text
           :default-poll-delay-ms hyperunit-operations-poll-default-delay-ms
           :runtime-error-message fallback-runtime-error-message}
          opts)))

(defn- start-hyperunit-withdraw-lifecycle-polling!
  [opts]
  (lifecycle-polling/start-hyperunit-withdraw-lifecycle-polling!
   (merge {:lifecycle-poll-key-fn lifecycle-poll-key
           :install-lifecycle-poll-token! install-lifecycle-poll-token!
           :clear-lifecycle-poll-token! clear-lifecycle-poll-token!
           :lifecycle-poll-token-active? lifecycle-poll-token-active?
           :modal-active-for-lifecycle? modal-active-for-lifecycle?
           :normalize-hyperunit-lifecycle funding-lifecycle/normalize-hyperunit-lifecycle
           :select-operation select-operation
           :operation->lifecycle operation->lifecycle
           :awaiting-lifecycle awaiting-lifecycle
           :lifecycle-next-delay-ms lifecycle-next-delay-ms
           :hyperunit-lifecycle-terminal? funding-lifecycle/hyperunit-lifecycle-terminal?
           :fetch-hyperunit-withdrawal-queue! fetch-hyperunit-withdrawal-queue!
           :non-blank-text non-blank-text
           :default-poll-delay-ms hyperunit-operations-poll-default-delay-ms
           :runtime-error-message fallback-runtime-error-message}
          opts)))

(defn- hyperunit-request-error-message
  [err {:keys [asset source-chain]}]
  (hyperunit-address-client/hyperunit-request-error-message err
                                                            {:asset asset
                                                             :source-chain source-chain}))

(def ^:private ensure-wallet-chain! wallet-rpc/ensure-wallet-chain!)
(def ^:private wait-for-transaction-receipt! wallet-rpc/wait-for-transaction-receipt!)

(defn- read-erc20-balance-units!
  [provider token-address owner-address]
  (erc20-rpc/read-erc20-balance-units! provider-request!
                                       provider
                                       token-address
                                       owner-address))

(defn- read-erc20-allowance-units!
  [provider token-address owner-address spender-address]
  (erc20-rpc/read-erc20-allowance-units! provider-request!
                                         provider
                                         token-address
                                         owner-address
                                         spender-address))

(defn- fetch-lifi-quote!
  [from-address amount-units to-token-address]
  (route-clients/fetch-lifi-quote! {:from-address from-address
                                    :amount-units amount-units
                                    :to-token-address to-token-address
                                    :from-chain-id arbitrum-mainnet-chain-id-decimal
                                    :to-chain-id arbitrum-mainnet-chain-id-decimal
                                    :from-token-address arbitrum-usdt-address
                                    :integrator "hyperopen"}))

(defn- fetch-across-approval!
  [from-address amount-units usdc-address]
  (route-clients/fetch-across-approval! {:base-url across-swap-approval-base-url
                                         :from-address from-address
                                         :amount-units amount-units
                                         :input-token-address usdc-address
                                         :origin-chain-id arbitrum-mainnet-chain-id-decimal
                                         :output-token-address hypercore-usdh-address
                                         :destination-chain-id hypercore-chain-id-decimal}))

(def ^:private across-approval->swap-config route-clients/across-approval->swap-config)

(defn- fetch-hyperunit-address!
  [base-url source-chain destination-chain asset destination-address]
  (hyperunit-address-client/fetch-hyperunit-address! base-url
                                                     source-chain
                                                     destination-chain
                                                     asset
                                                     destination-address))

(defn- fetch-hyperunit-address-with-source-fallbacks!
  [base-url base-urls source-chain destination-chain asset destination-address]
  (hyperunit-address-client/fetch-hyperunit-address-with-source-fallbacks!
   {:base-url base-url
    :base-urls base-urls
    :source-chain source-chain
    :destination-chain destination-chain
    :asset asset
    :destination-address destination-address
    :with-base-url-fallbacks! with-hyperunit-base-url-fallbacks!
    :source-chain-candidates (hyperunit-source-chain-candidates source-chain)
    :canonical-chain-token canonical-chain-token
    :canonical-token canonical-token}))

(defn- submit-hyperunit-address-deposit-request!
  [store owner-address action]
  (hyperunit-submit/submit-hyperunit-address-deposit-request!
   {:normalize-address normalize-address
    :non-blank-text non-blank-text
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :request-existing-hyperunit-deposit-address! request-existing-hyperunit-deposit-address!
    :fetch-hyperunit-address-with-source-fallbacks! fetch-hyperunit-address-with-source-fallbacks!
    :hyperunit-request-error-message hyperunit-request-error-message}
   store
   owner-address
   action))

(defn- submit-hyperunit-send-asset-withdraw-request!
  [store owner-address action submit-send-asset!]
  (hyperunit-submit/submit-hyperunit-send-asset-withdraw-request!
   {:normalize-address normalize-address
    :non-blank-text non-blank-text
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :fetch-hyperunit-address-with-source-fallbacks! fetch-hyperunit-address-with-source-fallbacks!
    :fallback-exchange-response-error fallback-exchange-response-error
    :hyperunit-request-error-message hyperunit-request-error-message}
   store
   owner-address
   action
   submit-send-asset!))

(defn- submit-usdc-bridge2-deposit-tx!
  [store owner-address action]
  (deposit-submit/submit-usdc-bridge2-deposit-tx!
   {:wallet-provider-fn wallet/provider
    :normalize-address normalize-address
    :resolve-deposit-chain-config resolve-deposit-chain-config
    :parse-usdc-units parse-usdc-units
    :ensure-wallet-chain! ensure-wallet-chain!
    :provider-request! provider-request!
    :wait-for-transaction-receipt! wait-for-transaction-receipt!
    :encode-erc20-transfer-call-data encode-erc20-transfer-call-data
    :wallet-error-message wallet-error-message}
   store
   owner-address
   action))

(def ^:private lifi-quote->swap-config route-clients/lifi-quote->swap-config)

(def ^:private send-and-confirm-evm-transaction! wallet-rpc/send-and-confirm-evm-transaction!)

(defn- submit-usdh-across-deposit-tx!
  [_store owner-address action]
  (deposit-submit/submit-usdh-across-deposit-tx!
   {:wallet-provider-fn wallet/provider
    :normalize-address normalize-address
    :parse-usdh-units parse-usdh-units
    :usdh-route-max-units usdh-route-max-units
    :chain-config (get chain-config-by-id arbitrum-mainnet-chain-id)
    :ensure-wallet-chain! ensure-wallet-chain!
    :fetch-across-approval! fetch-across-approval!
    :across-approval->swap-config across-approval->swap-config
    :send-and-confirm-evm-transaction! send-and-confirm-evm-transaction!
    :wallet-error-message wallet-error-message}
   _store
   owner-address
   action))

(defn- submit-usdt-lifi-bridge2-deposit-tx!
  [store owner-address action]
  (deposit-submit/submit-usdt-lifi-bridge2-deposit-tx!
   {:wallet-provider-fn wallet/provider
    :normalize-address normalize-address
    :parse-usdc-units parse-usdc-units
    :chain-config (get chain-config-by-id arbitrum-mainnet-chain-id)
    :ensure-wallet-chain! ensure-wallet-chain!
    :fetch-lifi-quote! fetch-lifi-quote!
    :lifi-quote->swap-config lifi-quote->swap-config
    :read-erc20-allowance-units! read-erc20-allowance-units!
    :encode-erc20-approve-call-data encode-erc20-approve-call-data
    :provider-request! provider-request!
    :wait-for-transaction-receipt! wait-for-transaction-receipt!
    :read-erc20-balance-units! read-erc20-balance-units!
    :submit-usdc-bridge2-deposit! submit-usdc-bridge2-deposit-tx!
    :usdc-units->amount-text usdc-units->amount-text
    :bridge-chain-id arbitrum-mainnet-chain-id
    :wallet-error-message wallet-error-message}
   store
   owner-address
   action))

(defn api-fetch-hyperunit-fee-estimate!
  [{:keys [store
           request-hyperunit-estimate-fees!
           now-ms-fn
           runtime-error-message]
    :or {request-hyperunit-estimate-fees! request-hyperunit-estimate-fees!
         now-ms-fn (fn [] (js/Date.now))
         runtime-error-message fallback-runtime-error-message}}]
  (hyperunit-query/api-fetch-hyperunit-fee-estimate!
   {:modal-active-for-fee-estimate? modal-active-for-fee-estimate?
    :normalize-hyperunit-fee-estimate funding-lifecycle/normalize-hyperunit-fee-estimate
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :prefetch-selected-hyperunit-deposit-address! prefetch-selected-hyperunit-deposit-address!
    :non-blank-text non-blank-text
    :fallback-runtime-error-message fallback-runtime-error-message}
   {:store store
    :request-hyperunit-estimate-fees! request-hyperunit-estimate-fees!
    :now-ms-fn now-ms-fn
    :runtime-error-message runtime-error-message}))

(defn api-fetch-hyperunit-withdrawal-queue!
  [{:keys [store
           request-hyperunit-withdrawal-queue!
           now-ms-fn
           runtime-error-message]
    :or {request-hyperunit-withdrawal-queue! request-hyperunit-withdrawal-queue!
         now-ms-fn (fn [] (js/Date.now))
         runtime-error-message fallback-runtime-error-message}}]
  (fetch-hyperunit-withdrawal-queue!
   {:store store
    :base-url (resolve-hyperunit-base-url store)
    :base-urls (resolve-hyperunit-base-urls store)
    :request-hyperunit-withdrawal-queue! request-hyperunit-withdrawal-queue!
    :now-ms-fn now-ms-fn
    :runtime-error-message runtime-error-message
    :transition-loading? true}))

(defn api-submit-funding-transfer!
  [{:keys [store
           request
           dispatch!
           submit-usd-class-transfer!
           exchange-response-error
           runtime-error-message
           show-toast!
           default-funding-modal-state]
    :or {submit-usd-class-transfer! trading-api/submit-usd-class-transfer!
         exchange-response-error fallback-exchange-response-error
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)
         default-funding-modal-state funding-actions/default-funding-modal-state}}]
  (submit-effects/api-submit-funding-transfer!
   {:store store
    :request request
    :dispatch! dispatch!
    :submit-usd-class-transfer! submit-usd-class-transfer!
    :exchange-response-error exchange-response-error
    :runtime-error-message runtime-error-message
    :show-toast! show-toast!
    :default-funding-modal-state default-funding-modal-state
    :set-funding-submit-error! set-funding-submit-error!
    :close-funding-modal! close-funding-modal!
    :refresh-after-funding-submit! refresh-after-funding-submit!}))

(defn api-submit-funding-send!
  [{:keys [store
           request
           dispatch!
           submit-send-asset!
           exchange-response-error
           runtime-error-message
           show-toast!
           default-funding-modal-state]
    :or {submit-send-asset! trading-api/submit-send-asset!
         exchange-response-error fallback-exchange-response-error
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)
         default-funding-modal-state funding-actions/default-funding-modal-state}}]
  (submit-effects/api-submit-funding-send!
   {:store store
    :request request
    :dispatch! dispatch!
    :submit-send-asset! submit-send-asset!
    :exchange-response-error exchange-response-error
    :runtime-error-message runtime-error-message
    :show-toast! show-toast!
    :default-funding-modal-state default-funding-modal-state
    :set-funding-submit-error! set-funding-submit-error!
    :close-funding-modal! close-funding-modal!
    :refresh-after-funding-submit! refresh-after-funding-submit!}))

(defn api-submit-funding-withdraw!
  [{:keys [store
           request
           dispatch!
           submit-withdraw3!
           submit-send-asset!
           submit-hyperunit-send-asset-withdraw-request-fn
           request-hyperunit-operations!
           request-hyperunit-withdrawal-queue!
           set-timeout-fn
           now-ms-fn
           exchange-response-error
           runtime-error-message
           show-toast!
           default-funding-modal-state]
    :or {submit-withdraw3! trading-api/submit-withdraw3!
         submit-send-asset! trading-api/submit-send-asset!
         submit-hyperunit-send-asset-withdraw-request-fn submit-hyperunit-send-asset-withdraw-request!
         request-hyperunit-operations! nil
         request-hyperunit-withdrawal-queue! nil
         set-timeout-fn nil
         now-ms-fn nil
         exchange-response-error fallback-exchange-response-error
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)
         default-funding-modal-state funding-actions/default-funding-modal-state}}]
  (submit-effects/api-submit-funding-withdraw!
   {:store store
    :request request
    :dispatch! dispatch!
    :submit-withdraw3! submit-withdraw3!
    :submit-send-asset! submit-send-asset!
    :submit-hyperunit-send-asset-withdraw-request-fn submit-hyperunit-send-asset-withdraw-request-fn
    :request-hyperunit-operations! request-hyperunit-operations!
    :request-hyperunit-withdrawal-queue! request-hyperunit-withdrawal-queue!
    :set-timeout-fn set-timeout-fn
    :now-ms-fn now-ms-fn
    :exchange-response-error exchange-response-error
    :runtime-error-message runtime-error-message
    :show-toast! show-toast!
    :default-funding-modal-state default-funding-modal-state
    :set-funding-submit-error! set-funding-submit-error!
    :close-funding-modal! close-funding-modal!
    :refresh-after-funding-submit! refresh-after-funding-submit!
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :awaiting-withdraw-lifecycle awaiting-withdraw-lifecycle
    :start-hyperunit-withdraw-lifecycle-polling! start-hyperunit-withdraw-lifecycle-polling!}))

(defn api-submit-funding-deposit!
  [{:keys [store
           request
           dispatch!
           submit-usdc-bridge2-deposit!
           submit-usdt-lifi-deposit!
           submit-usdh-across-deposit!
           submit-hyperunit-address-request!
           request-hyperunit-operations!
           set-timeout-fn
           now-ms-fn
           runtime-error-message
           show-toast!
           default-funding-modal-state]
    :or {submit-usdc-bridge2-deposit! submit-usdc-bridge2-deposit-tx!
         submit-usdt-lifi-deposit! submit-usdt-lifi-bridge2-deposit-tx!
         submit-usdh-across-deposit! submit-usdh-across-deposit-tx!
         submit-hyperunit-address-request! submit-hyperunit-address-deposit-request!
         request-hyperunit-operations! nil
         set-timeout-fn nil
         now-ms-fn nil
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)
         default-funding-modal-state funding-actions/default-funding-modal-state}}]
  (submit-effects/api-submit-funding-deposit!
   {:store store
    :request request
    :dispatch! dispatch!
    :submit-usdc-bridge2-deposit! submit-usdc-bridge2-deposit!
    :submit-usdt-lifi-deposit! submit-usdt-lifi-deposit!
    :submit-usdh-across-deposit! submit-usdh-across-deposit!
    :submit-hyperunit-address-request! submit-hyperunit-address-request!
    :request-hyperunit-operations! request-hyperunit-operations!
    :set-timeout-fn set-timeout-fn
    :now-ms-fn now-ms-fn
    :runtime-error-message runtime-error-message
    :show-toast! show-toast!
    :default-funding-modal-state default-funding-modal-state
    :set-funding-submit-error! set-funding-submit-error!
    :close-funding-modal! close-funding-modal!
    :refresh-after-funding-submit! refresh-after-funding-submit!
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :awaiting-deposit-lifecycle awaiting-deposit-lifecycle
    :start-hyperunit-deposit-lifecycle-polling! start-hyperunit-deposit-lifecycle-polling!}))
