(ns hyperopen.funding.effects
  (:require [clojure.string :as str]
            [hyperopen.api.gateway.funding-hyperunit :as funding-hyperunit-gateway]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.funding.actions :as funding-actions]
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

(def ^:private hyperunit-mainnet-proxy-base-url
  "/api/hyperunit/mainnet")

(def ^:private hyperunit-testnet-proxy-base-url
  "/api/hyperunit/testnet")

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

(defn- left-pad-hex
  [hex-value width]
  (let [value (or hex-value "")
        value-len (count value)]
    (if (>= value-len width)
      value
      (str (apply str (repeat (- width value-len) "0")) value))))

(defn- parse-usdc-units
  [amount]
  (let [text (some-> amount str str/trim)]
    (when (and (seq text)
               (re-matches #"^(?:0|[1-9]\d*)(?:\.\d{1,6})?$" text))
      (let [[whole fract] (str/split text #"\\.")
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
      (let [[whole fract] (str/split text #"\\.")
            whole* (or whole "0")
            fract* (subs (str (or fract "") "00000000") 0 8)
            whole-units (* (js/BigInt whole*) (js/BigInt "100000000"))
            fract-units (js/BigInt fract*)]
        (+ whole-units fract-units)))))

(def ^:private usdh-route-max-units
  (parse-usdh-units "1000000"))

(defn- bigint-from-hex
  [value]
  (let [text (-> (or value "0x0")
                 str
                 str/trim
                 str/lower-case)]
    (if (re-matches #"^0x[0-9a-f]+$" text)
      (js/BigInt text)
      (js/BigInt "0"))))

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

(defn- encode-erc20-transfer-call-data
  [to-address amount-units]
  (let [to* (normalize-address to-address)
        to-param (left-pad-hex (subs to* 2) 64)
        amount-param (left-pad-hex (.toString amount-units 16) 64)]
    (str "0xa9059cbb" to-param amount-param)))

(defn- encode-erc20-approve-call-data
  [spender-address amount-units]
  (let [spender* (normalize-address spender-address)
        spender-param (left-pad-hex (subs spender* 2) 64)
        amount-param (left-pad-hex (.toString amount-units 16) 64)]
    (str "0x095ea7b3" spender-param amount-param)))

(defn- encode-erc20-balance-of-call-data
  [owner-address]
  (let [owner* (normalize-address owner-address)
        owner-param (left-pad-hex (subs owner* 2) 64)]
    (str "0x70a08231" owner-param)))

(defn- encode-erc20-allowance-call-data
  [owner-address spender-address]
  (let [owner* (normalize-address owner-address)
        spender* (normalize-address spender-address)
        owner-param (left-pad-hex (subs owner* 2) 64)
        spender-param (left-pad-hex (subs spender* 2) 64)]
    (str "0xdd62ed3e" owner-param spender-param)))

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

(defn- provider-request!
  [provider method & [params]]
  (if-not provider
    (js/Promise.reject (js/Error. "No wallet provider found. Connect your wallet first."))
    (.request provider
              (clj->js (cond-> {:method method}
                         (some? params) (assoc :params params))))))

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
        [hyperunit-testnet-proxy-base-url
         hyperunit-testnet-base-url]
        [hyperunit-mainnet-proxy-base-url
         hyperunit-mainnet-base-url])))))

(defn- resolve-hyperunit-base-url
  [store]
  (or (first (resolve-hyperunit-base-urls store))
      hyperunit-mainnet-base-url))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- with-hyperunit-base-url-fallbacks!
  [{:keys [base-url
           base-urls
           request-fn
           error-message]
    :or {error-message "HyperUnit request failed."}}]
  (let [candidates (vec (distinct
                         (keep non-blank-text
                               (concat [(non-blank-text base-url)]
                                       (or base-urls [])))))]
    (letfn [(attempt! [remaining last-error]
              (if-let [candidate (first remaining)]
                (let [request-result (try
                                       (request-fn candidate)
                                       (catch :default err
                                         (js/Promise.reject err)))]
                  (-> request-result
                      (.catch (fn [err]
                                (attempt! (rest remaining)
                                          (or err last-error))))))
                (js/Promise.reject
                 (or last-error
                     (js/Error. error-message)))))]
      (attempt! candidates nil))))

(defonce ^:private hyperunit-lifecycle-poll-tokens
  (atom {}))

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

(defn- protocol-address-matches-source-chain?
  [source-chain address]
  (let [source* (canonical-chain-token source-chain)
        address* (non-blank-text address)
        address-lower (some-> address* str/lower-case)]
    (case source*
      "bitcoin"
      (boolean (and (seq address-lower)
                    (or (re-matches #"^bc1[a-z0-9]{20,}$" address-lower)
                        (re-matches #"^tb1[a-z0-9]{20,}$" address-lower)
                        (re-matches #"^[13][a-km-zA-HJ-NP-Z1-9]{20,}$" address*))))

      "ethereum"
      (boolean (and (seq address-lower)
                    (re-matches #"^0x[0-9a-f]{40}$" address-lower)))

      "solana"
      (boolean (and (seq address*)
                    (re-matches #"^[1-9A-HJ-NP-Za-km-z]{32,44}$" address*)))

      ;; Monad and Plasma currently use EVM-style protocol addresses.
      "monad"
      (boolean (and (seq address-lower)
                    (re-matches #"^0x[0-9a-f]{40}$" address-lower)))

      "plasma"
      (boolean (and (seq address-lower)
                    (re-matches #"^0x[0-9a-f]{40}$" address-lower)))

      false)))

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

(defn- token->keyword
  [value]
  (let [text (some-> value
                     canonical-token
                     (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                     (str/replace #"[^a-z0-9]+" "-")
                     (str/replace #"(^-+)|(-+$)" ""))]
    (when (seq text)
      (keyword text))))

(defn- timestamp->ms
  [value]
  (let [text (non-blank-text value)
        parsed (if text (js/Date.parse text) js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn request-hyperunit-operations!
  [{:keys [base-url base-urls address]}]
  (with-hyperunit-base-url-fallbacks!
   {:base-url base-url
    :base-urls base-urls
    :error-message "Unable to load HyperUnit operations."
    :request-fn (fn [candidate-base-url]
                  (funding-hyperunit-gateway/request-hyperunit-operations!
                   {:hyperunit-base-url candidate-base-url
                    :fetch-fn js/fetch}
                   {:address address}))}))

(defn request-hyperunit-estimate-fees!
  [{:keys [base-url base-urls]}]
  (with-hyperunit-base-url-fallbacks!
   {:base-url base-url
    :base-urls base-urls
    :error-message "Unable to load HyperUnit fee estimates."
    :request-fn (fn [candidate-base-url]
                  (funding-hyperunit-gateway/request-hyperunit-estimate-fees!
                   {:hyperunit-base-url candidate-base-url
                    :fetch-fn js/fetch}
                   {}))}))

(defn request-hyperunit-withdrawal-queue!
  [{:keys [base-url base-urls]}]
  (with-hyperunit-base-url-fallbacks!
   {:base-url base-url
    :base-urls base-urls
    :error-message "Unable to load HyperUnit withdrawal queue."
    :request-fn (fn [candidate-base-url]
                  (funding-hyperunit-gateway/request-hyperunit-withdrawal-queue!
                   {:hyperunit-base-url candidate-base-url
                    :fetch-fn js/fetch}
                   {}))}))

(defn request-hyperunit-generate-address!
  [{:keys [base-url
           base-urls
           source-chain
           destination-chain
           asset
           destination-address]}]
  (with-hyperunit-base-url-fallbacks!
   {:base-url base-url
    :base-urls base-urls
    :error-message "Unable to generate HyperUnit address."
    :request-fn (fn [candidate-base-url]
                  (funding-hyperunit-gateway/request-hyperunit-generate-address!
                   {:hyperunit-base-url candidate-base-url
                    :fetch-fn js/fetch}
                   {:source-chain source-chain
                    :destination-chain destination-chain
                    :asset asset
                    :destination-address destination-address}))}))

(defn- lifecycle-poll-key
  [store direction asset-key]
  [store direction asset-key])

(defn- install-lifecycle-poll-token!
  [poll-key token]
  (swap! hyperunit-lifecycle-poll-tokens assoc poll-key token))

(defn- clear-lifecycle-poll-token!
  [poll-key token]
  (swap! hyperunit-lifecycle-poll-tokens
         (fn [tokens]
           (if (= token (get tokens poll-key))
             (dissoc tokens poll-key)
             tokens))))

(defn- lifecycle-poll-token-active?
  [poll-key token]
  (= token (get @hyperunit-lifecycle-poll-tokens poll-key)))

(defn- modal-active-for-lifecycle?
  [store direction asset-key protocol-address]
  (let [modal (get-in @store [:funding-ui :modal])
        lifecycle (:hyperunit-lifecycle modal)
        mode (:mode modal)
        selected-asset-key (case direction
                             :deposit (:deposit-selected-asset-key modal)
                             :withdraw (:withdraw-selected-asset-key modal)
                             nil)
        generated-address (case direction
                            :deposit (:deposit-generated-address modal)
                            :withdraw (:withdraw-generated-address modal)
                            nil)]
    (and (true? (:open? modal))
         (= mode direction)
         (or (not= direction :deposit)
             (= :amount-entry (:deposit-step modal)))
         (= asset-key selected-asset-key)
         (= direction (:direction lifecycle))
         (= asset-key (:asset-key lifecycle))
         (or (not (seq (canonical-token protocol-address)))
             (= (canonical-token protocol-address)
                (canonical-token generated-address))))))

(defn- modal-active-for-fee-estimate?
  [store]
  (let [modal (get-in @store [:funding-ui :modal])]
    (and (true? (:open? modal))
         (contains? #{:deposit :withdraw} (:mode modal)))))

(defn- normalize-modal-asset-key
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn- modal-active-for-withdraw-queue?
  ([store]
   (modal-active-for-withdraw-queue? store nil))
  ([store expected-asset-key]
   (let [modal (get-in @store [:funding-ui :modal])
         selected-asset-key (normalize-modal-asset-key
                             (:withdraw-selected-asset-key modal))
         expected-asset-key* (normalize-modal-asset-key expected-asset-key)]
     (and (true? (:open? modal))
          (= :withdraw (:mode modal))
          (keyword? selected-asset-key)
          (not= :usdc selected-asset-key)
          (or (nil? expected-asset-key*)
              (= expected-asset-key* selected-asset-key))))))

(defn- op-sort-ms
  [op]
  (or (timestamp->ms (:state-updated-at op))
      (timestamp->ms (:state-started-at op))
      (timestamp->ms (:op-created-at op))
      0))

(defn- select-operation
  [operations {:keys [asset-key protocol-address source-address destination-address]}]
  (let [asset-token (some-> asset-key name canonical-token)
        protocol-token (canonical-token protocol-address)
        source-token (canonical-token source-address)
        destination-token (canonical-token destination-address)
        for-asset (->> (or operations [])
                       (filter (fn [op]
                                 (= asset-token
                                    (canonical-token (:asset op)))))
                       vec)
        protocol-matches (if (seq protocol-token)
                           (filterv #(= protocol-token
                                        (canonical-token (:protocol-address %)))
                                    for-asset)
                           [])
        source-matches (if (seq source-token)
                         (filterv #(= source-token
                                      (canonical-token (:source-address %)))
                                  for-asset)
                         [])
        destination-matches (if (seq destination-token)
                              (filterv #(= destination-token
                                           (canonical-token (:destination-address %)))
                                       for-asset)
                              [])
        source-and-destination-matches (if (and (seq source-token)
                                                (seq destination-token))
                                         (filterv #(and (= source-token
                                                         (canonical-token (:source-address %)))
                                                        (= destination-token
                                                           (canonical-token (:destination-address %))))
                                                  for-asset)
                                         [])
        candidates (cond
                     (seq protocol-matches) protocol-matches
                     (seq source-and-destination-matches) source-and-destination-matches
                     (seq source-matches) source-matches
                     (seq destination-matches) destination-matches
                     :else for-asset)]
    (last (sort-by op-sort-ms candidates))))

(defn- select-existing-hyperunit-deposit-address
  [operations-response source-chain asset destination-address]
  (let [source-token (canonical-chain-token source-chain)
        asset-token (canonical-token asset)
        destination-chain-token (canonical-chain-token "hyperliquid")
        destination-address-token (canonical-token destination-address)
        operations (if (sequential? (:operations operations-response))
                     (:operations operations-response)
                     [])
        addresses (if (sequential? (:addresses operations-response))
                    (:addresses operations-response)
                    [])
        matching-ops (->> operations
                          (filter (fn [op]
                                    (and (= asset-token
                                            (canonical-token (:asset op)))
                                         (same-chain-token? source-token
                                                            (:source-chain op))
                                         (same-chain-token? destination-chain-token
                                                            (:destination-chain op))
                                         (or (not (seq destination-address-token))
                                             (= destination-address-token
                                                (canonical-token (:destination-address op))))
                                         (seq (canonical-token (:protocol-address op))))))
                          vec)
        op-address (some->> matching-ops
                            (sort-by op-sort-ms)
                            last
                            :protocol-address
                            non-blank-text)
        address-entry-by-op (when (seq op-address)
                              (some (fn [entry]
                                      (when (= (canonical-token op-address)
                                               (canonical-token (:address entry)))
                                        entry))
                                    addresses))
        hyperliquid-address-entries (->> addresses
                                         (filter (fn [entry]
                                                   (and (same-chain-token? destination-chain-token
                                                                           (:destination-chain entry))
                                                        (seq (canonical-token (:address entry))))))
                                         vec)
        direct-address-entry (some (fn [entry]
                                     (when (and (same-chain-token? source-token
                                                                   (or (:source-coin-type entry)
                                                                       (:source-chain entry)))
                                                (same-chain-token? destination-chain-token
                                                                   (:destination-chain entry))
                                                (seq (canonical-token (:address entry))))
                                       entry))
                                   hyperliquid-address-entries)
        source-format-address-entry (some (fn [entry]
                                            (let [entry-source (or (:source-coin-type entry)
                                                                   (:source-chain entry))
                                                  entry-source-token (canonical-chain-token entry-source)
                                                  entry-source-conflicts? (and (seq entry-source-token)
                                                                               (contains? known-source-chain-tokens
                                                                                          entry-source-token)
                                                                               (not (same-chain-token? source-token
                                                                                                       entry-source-token)))
                                                  address* (:address entry)]
                                              (when (and (same-chain-token? destination-chain-token
                                                                            (:destination-chain entry))
                                                         (not entry-source-conflicts?)
                                                         (protocol-address-matches-source-chain?
                                                          source-token
                                                          address*))
                                                entry)))
                                          hyperliquid-address-entries)
        chosen-entry (or address-entry-by-op
                         direct-address-entry
                         source-format-address-entry)
        chosen-address (or op-address
                          (some-> (:address chosen-entry) non-blank-text))]
    (when (seq chosen-address)
      {:address chosen-address
       :signatures (:signatures chosen-entry)})))

(defn- request-existing-hyperunit-deposit-address!
  [base-url base-urls destination-address source-chain asset]
  (-> (request-hyperunit-operations! {:base-url base-url
                                      :base-urls base-urls
                                      :address destination-address})
      (.then (fn [operations-response]
               (select-existing-hyperunit-deposit-address operations-response
                                                          source-chain
                                                          asset
                                                          destination-address)))
      (.catch (fn [_err]
                nil))))

(defn- prefetch-selected-hyperunit-deposit-address!
  [store]
  (let [state @store
        modal (get-in state [:funding-ui :modal])
        view-model (funding-actions/funding-modal-view-model state)
        selected-asset (:deposit-selected-asset view-model)
        selected-asset-key (normalize-asset-key (:key selected-asset))
        selected-source-chain (some-> (:hyperunit-source-chain selected-asset)
                                      non-blank-text
                                      canonical-chain-token)
        generated-address (non-blank-text (:deposit-generated-address modal))
        generated-asset-key (normalize-asset-key (:deposit-generated-asset-key modal))
        wallet-address (normalize-address (get-in state [:wallet :address]))
        should-prefetch? (and (= :deposit (:mode modal))
                              (= :amount-entry (:deposit-step modal))
                              (= :hyperunit-address (:flow-kind selected-asset))
                              (keyword? selected-asset-key)
                              (seq selected-source-chain)
                              (seq wallet-address)
                              (not (and (= selected-asset-key generated-asset-key)
                                        (seq generated-address))))]
    (when should-prefetch?
      (let [base-urls (resolve-hyperunit-base-urls store)
            base-url (first base-urls)
            asset-token (name selected-asset-key)]
        (-> (request-existing-hyperunit-deposit-address! base-url
                                                         base-urls
                                                         wallet-address
                                                         selected-source-chain
                                                         asset-token)
            (.then (fn [existing-address]
                     (when (map? existing-address)
                       (swap! store
                              (fn [state*]
                                (let [modal* (get-in state* [:funding-ui :modal])
                                      active-asset-key (normalize-asset-key (:deposit-selected-asset-key modal*))
                                      active-generated-key (normalize-asset-key (:deposit-generated-asset-key modal*))
                                      active-generated-address (non-blank-text (:deposit-generated-address modal*))
                                      still-active? (and (= :deposit (:mode modal*))
                                                         (= :amount-entry (:deposit-step modal*))
                                                         (= selected-asset-key active-asset-key))
                                      already-populated? (and (= selected-asset-key active-generated-key)
                                                              (seq active-generated-address))]
                                  (if (and still-active?
                                           (not already-populated?))
                                    (-> state*
                                        (assoc-in [:funding-ui :modal :error] nil)
                                        (assoc-in [:funding-ui :modal :deposit-generated-address] (:address existing-address))
                                        (assoc-in [:funding-ui :modal :deposit-generated-signatures] (:signatures existing-address))
                                        (assoc-in [:funding-ui :modal :deposit-generated-asset-key] selected-asset-key))
                                    state*)))))
                     existing-address))
            (.catch (fn [_err]
                      nil)))))))

(defn- lifecycle-next-delay-ms
  [now-ms lifecycle]
  (let [state-next-at (:state-next-at lifecycle)
        next-delay (when (number? state-next-at)
                     (max 0 (- state-next-at now-ms)))
        base-delay (if (number? next-delay)
                     next-delay
                     hyperunit-operations-poll-default-delay-ms)]
    (-> base-delay
        (max hyperunit-operations-poll-min-delay-ms)
        (min hyperunit-operations-poll-max-delay-ms)
        js/Math.floor)))

(defn- operation->lifecycle
  [operation direction asset-key now-ms]
  (funding-actions/normalize-hyperunit-lifecycle
   {:direction direction
    :asset-key asset-key
    :operation-id (:operation-id operation)
    :state (or (:state-key operation)
               (token->keyword (:state operation)))
    :status (token->keyword (:status operation))
    :source-tx-confirmations (:source-tx-confirmations operation)
    :destination-tx-confirmations (:destination-tx-confirmations operation)
    :position-in-withdraw-queue (:position-in-withdraw-queue operation)
    :destination-tx-hash (:destination-tx-hash operation)
    :state-next-at (timestamp->ms (:state-next-attempt-at operation))
    :last-updated-ms now-ms
    :error (:error operation)}))

(defn- awaiting-lifecycle
  [direction asset-key now-ms]
  (funding-actions/normalize-hyperunit-lifecycle
   {:direction direction
    :asset-key asset-key
    :status :pending
    :state (if (= direction :withdraw)
             :awaiting-hyperliquid-send
             :awaiting-source-transfer)
    :last-updated-ms now-ms
    :error nil}))

(defn- awaiting-deposit-lifecycle
  [asset-key now-ms]
  (awaiting-lifecycle :deposit asset-key now-ms))

(defn- awaiting-withdraw-lifecycle
  [asset-key now-ms]
  (awaiting-lifecycle :withdraw asset-key now-ms))

(defn- fetch-hyperunit-withdrawal-queue!
  [{:keys [store
           base-url
           base-urls
           request-hyperunit-withdrawal-queue!
           now-ms-fn
           runtime-error-message
           expected-asset-key
           transition-loading?]
    :or {transition-loading? true
         runtime-error-message fallback-runtime-error-message}}]
  (let [request-queue! (when (fn? request-hyperunit-withdrawal-queue!)
                         request-hyperunit-withdrawal-queue!)
        now-ms!* (or now-ms-fn
                     (fn [] (js/Date.now)))
        resolved-base-urls (or (seq base-urls)
                               (resolve-hyperunit-base-urls store))
        base-url* (or (non-blank-text base-url)
                      (first resolved-base-urls))]
    (when (and request-queue!
               (modal-active-for-withdraw-queue? store expected-asset-key))
      (let [requested-at (now-ms!*)]
        (when transition-loading?
          (swap! store update-in
                 [:funding-ui :modal :hyperunit-withdrawal-queue]
                 (fn [current]
                   (-> (funding-actions/normalize-hyperunit-withdrawal-queue current)
                       (assoc :status :loading
                              :requested-at-ms requested-at
                              :error nil)))))
        (-> (request-queue! {:base-url base-url*
                             :base-urls resolved-base-urls})
            (.then (fn [resp]
                     (when (modal-active-for-withdraw-queue? store expected-asset-key)
                       (let [timestamp (now-ms!*)
                             by-chain (if (map? (:by-chain resp))
                                        (:by-chain resp)
                                        {})
                             error-text (non-blank-text (:error resp))]
                         (swap! store update-in
                                [:funding-ui :modal :hyperunit-withdrawal-queue]
                                (fn [current]
                                  (let [prev (funding-actions/normalize-hyperunit-withdrawal-queue current)]
                                    (if (seq error-text)
                                      (assoc prev
                                             :status :error
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error error-text)
                                      (assoc prev
                                             :status :ready
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error nil)))))))
                     resp))
            (.catch (fn [err]
                      (when (modal-active-for-withdraw-queue? store expected-asset-key)
                        (let [timestamp (now-ms!*)
                              message (or (non-blank-text (runtime-error-message err))
                                          "Unable to load HyperUnit withdrawal queue.")]
                          (swap! store update-in
                                 [:funding-ui :modal :hyperunit-withdrawal-queue]
                                 (fn [current]
                                   (-> (funding-actions/normalize-hyperunit-withdrawal-queue current)
                                       (assoc :status :error
                                              :updated-at-ms timestamp
                                              :error message)))))))))))))

(defn- start-hyperunit-lifecycle-polling!
  [{:keys [store
           direction
           wallet-address
           asset-key
           protocol-address
           destination-address
           base-url
           base-urls
           request-hyperunit-operations!
           request-hyperunit-withdrawal-queue!
           set-timeout-fn
           now-ms-fn
           runtime-error-message
           on-terminal-lifecycle!]}]
  (let [request-ops! (when (fn? request-hyperunit-operations!)
                       request-hyperunit-operations!)
        request-queue! (when (fn? request-hyperunit-withdrawal-queue!)
                         request-hyperunit-withdrawal-queue!)
        terminal-callback! (when (fn? on-terminal-lifecycle!)
                             on-terminal-lifecycle!)
        timeout! (or set-timeout-fn
                     (fn [f delay-ms]
                       (js/setTimeout f delay-ms)))
        now-ms!* (or now-ms-fn
                     (fn [] (js/Date.now)))]
    (when (and request-ops!
               (contains? #{:deposit :withdraw} direction)
               (seq (non-blank-text wallet-address))
               (keyword? asset-key))
      (let [poll-key (lifecycle-poll-key store direction asset-key)
            token (str (random-uuid))
            should-continue? (fn []
                               (and (lifecycle-poll-token-active? poll-key token)
                                    (modal-active-for-lifecycle? store direction asset-key protocol-address)))
            update-lifecycle! (fn [lifecycle]
                                (when (should-continue?)
                                  (swap! store assoc-in
                                         [:funding-ui :modal :hyperunit-lifecycle]
                                         (funding-actions/normalize-hyperunit-lifecycle lifecycle))))
            refresh-withdraw-queue! (fn []
                                      (when (and (= direction :withdraw)
                                                 request-queue!
                                                 (should-continue?))
                                        (fetch-hyperunit-withdrawal-queue!
                                         {:store store
                                          :base-url base-url
                                          :base-urls base-urls
                                          :request-hyperunit-withdrawal-queue! request-queue!
                                          :now-ms-fn now-ms!*
                                          :runtime-error-message (or runtime-error-message
                                                                     fallback-runtime-error-message)
                                          :expected-asset-key asset-key
                                          :transition-loading? false})))
            schedule-next! (fn [delay-ms poll-fn]
                             (when (should-continue?)
                               (timeout! poll-fn delay-ms)))
            notify-terminal! (fn [lifecycle]
                               (when terminal-callback!
                                 (try
                                   (terminal-callback! lifecycle)
                                   (catch :default _ nil))))]
        (install-lifecycle-poll-token! poll-key token)
        (letfn [(poll! []
                  (if-not (should-continue?)
                    (clear-lifecycle-poll-token! poll-key token)
                    (-> (request-ops! {:base-url base-url
                                       :base-urls base-urls
                                       :address wallet-address})
                        (.then (fn [response]
                                 (when (should-continue?)
                                   (let [operations (:operations (or response {}))
                                         now-ms (now-ms!*)
                                         operation (select-operation operations
                                                                    {:asset-key asset-key
                                                                     :protocol-address protocol-address
                                                                     :source-address (when (= direction :withdraw)
                                                                                       wallet-address)
                                         :destination-address (if (= direction :withdraw)
                                                                    destination-address
                                                                    wallet-address)})
                                         lifecycle (if operation
                                                     (operation->lifecycle operation direction asset-key now-ms)
                                                     (awaiting-lifecycle direction asset-key now-ms))]
                                     (update-lifecycle! lifecycle)
                                     (refresh-withdraw-queue!)
                                     (if (funding-actions/hyperunit-lifecycle-terminal? lifecycle)
                                       (do
                                         (clear-lifecycle-poll-token! poll-key token)
                                         (notify-terminal! lifecycle))
                                       (schedule-next! (lifecycle-next-delay-ms now-ms lifecycle) poll!))))))
                        (.catch (fn [err]
                                  (when (should-continue?)
                                    (let [now-ms (now-ms!*)
                                          message (or (non-blank-text (some-> err .-message))
                                                      "Unable to refresh lifecycle status right now.")
                                          previous (get-in @store [:funding-ui :modal :hyperunit-lifecycle])]
                                      (update-lifecycle!
                                       (assoc (merge (awaiting-lifecycle direction asset-key now-ms)
                                                     (if (map? previous) previous {}))
                                               :direction direction
                                               :asset-key asset-key
                                               :last-updated-ms now-ms
                                               :error message))
                                      (refresh-withdraw-queue!)
                                      (schedule-next! hyperunit-operations-poll-default-delay-ms poll!))))))))]
          (poll!))))))

(defn- start-hyperunit-deposit-lifecycle-polling!
  [opts]
  (start-hyperunit-lifecycle-polling!
   (assoc opts :direction :deposit)))

(defn- start-hyperunit-withdraw-lifecycle-polling!
  [opts]
  (start-hyperunit-lifecycle-polling!
   (assoc opts :direction :withdraw)))

(defn- hyperunit-gen-url
  [base-url source-chain destination-chain asset destination-address]
  (let [encode js/encodeURIComponent]
    (str base-url
         "/gen/"
         (encode source-chain)
         "/"
         (encode destination-chain)
         "/"
         (encode asset)
         "/"
         (encode destination-address))))

(defn- hyperunit-error-message
  [payload]
  (or (non-blank-text (:error payload))
      (non-blank-text (:message payload))))

(defn- hyperunit-request-error-message
  [err {:keys [asset source-chain]}]
  (let [message (or (non-blank-text (some-> err .-message))
                    (non-blank-text (str err))
                    "Unknown HyperUnit error.")
        message-lower (str/lower-case message)
        asset-label (or (some-> asset str str/upper-case)
                        "asset")
        network-label (or (some-> source-chain str str/capitalize)
                          "selected network")]
    (cond
      (or (str/includes? message-lower "failed to fetch")
          (str/includes? message-lower "networkerror")
          (str/includes? message-lower "network request failed"))
      (str "Unable to reach HyperUnit address service for "
           asset-label
           " on "
           network-label
           ". Check your network and retry. If this persists in local dev, route HyperUnit through a same-origin proxy.")

      :else
      message)))

(defn- wallet-add-chain-params
  [{:keys [chain-id chain-name rpc-url explorer-url]}]
  {:chainId chain-id
   :chainName chain-name
   :nativeCurrency {:name "Ether"
                    :symbol "ETH"
                    :decimals 18}
   :rpcUrls [rpc-url]
   :blockExplorerUrls [explorer-url]})

(defn- ensure-wallet-chain!
  [provider chain-config]
  (let [target-chain-id (:chain-id chain-config)]
    (-> (provider-request! provider "eth_chainId")
        (.then (fn [current-chain-id]
                 (if (= (normalize-chain-id current-chain-id) target-chain-id)
                   (js/Promise.resolve target-chain-id)
                   (-> (provider-request! provider
                                           "wallet_switchEthereumChain"
                                           [{:chainId target-chain-id}])
                       (.catch (fn [err]
                                 (let [code (or (some-> err .-code)
                                                (some-> err (aget "code")))]
                                   (if (= code 4902)
                                     (-> (provider-request! provider
                                                             "wallet_addEthereumChain"
                                                             [(wallet-add-chain-params chain-config)])
                                         (.then (fn [_]
                                                  (provider-request! provider
                                                                     "wallet_switchEthereumChain"
                                                                     [{:chainId target-chain-id}])))
                                         (.then (fn [_]
                                                  target-chain-id)))
                                     (js/Promise.reject err))))))))))))

(defn- wait-for-transaction-receipt!
  [provider tx-hash]
  (let [poll-ms 1200
        timeout-ms 120000
        started-at (js/Date.now)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(poll []
                 (-> (provider-request! provider "eth_getTransactionReceipt" [tx-hash])
                     (.then (fn [receipt]
                              (if receipt
                                (let [status (-> (or (aget receipt "status") "")
                                                 str
                                                 str/lower-case)]
                                  (if (= status "0x1")
                                    (resolve receipt)
                                    (reject (js/Error. "Deposit transaction reverted on-chain."))))
                                (if (> (- (js/Date.now) started-at) timeout-ms)
                                  (reject (js/Error. "Timed out waiting for deposit confirmation."))
                                  (js/setTimeout poll poll-ms)))))
                     (.catch reject)))]
         (poll))))))

(defn- read-erc20-balance-units!
  [provider token-address owner-address]
  (-> (provider-request! provider
                         "eth_call"
                         [{:to token-address
                           :data (encode-erc20-balance-of-call-data owner-address)}
                          "latest"])
      (.then bigint-from-hex)))

(defn- read-erc20-allowance-units!
  [provider token-address owner-address spender-address]
  (-> (provider-request! provider
                         "eth_call"
                         [{:to token-address
                           :data (encode-erc20-allowance-call-data owner-address spender-address)}
                          "latest"])
      (.then bigint-from-hex)))

(defn- maybe-value-field
  [value]
  (let [text (some-> value str str/trim)]
    (when (and (seq text)
               (not= text "0x")
               (not= text "0x0")
               (not= text "0"))
      text)))

(defn- lifi-quote-url
  [from-address amount-units to-token-address]
  (let [encode js/encodeURIComponent]
    (str "https://li.quest/v1/quote?"
         "fromChain=" arbitrum-mainnet-chain-id-decimal
         "&toChain=" arbitrum-mainnet-chain-id-decimal
         "&fromToken=" (encode arbitrum-usdt-address)
         "&toToken=" (encode to-token-address)
         "&fromAddress=" (encode from-address)
         "&fromAmount=" (encode (.toString amount-units))
         "&slippage=0.005"
         "&integrator=hyperopen")))

(defn- fetch-lifi-quote!
  [from-address amount-units to-token-address]
  (let [url (lifi-quote-url from-address amount-units to-token-address)]
    (-> (js/fetch url #js {:method "GET"})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (.then (.text resp)
                          (fn [text]
                            (throw (js/Error.
                                    (str "LiFi quote request failed ("
                                         (.-status resp)
                                         "): "
                                         (or text "Unknown response"))))))))
        (.then (fn [payload]
                 (js->clj payload :keywordize-keys true)))))))

(defn- across-approval-url
  [from-address amount-units usdc-address]
  (let [encode js/encodeURIComponent]
    (str across-swap-approval-base-url
         "?tradeType=minOutput"
         "&amount=" (encode (.toString amount-units))
         "&inputToken=" (encode usdc-address)
         "&originChainId=" (encode arbitrum-mainnet-chain-id-decimal)
         "&outputToken=" (encode hypercore-usdh-address)
         "&destinationChainId=" (encode hypercore-chain-id-decimal)
         "&depositor=" (encode from-address))))

(defn- fetch-across-approval!
  [from-address amount-units usdc-address]
  (let [url (across-approval-url from-address amount-units usdc-address)]
    (-> (js/fetch url #js {:method "GET"})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (.then (.text resp)
                          (fn [text]
                            (throw (js/Error.
                                    (str "Across approval request failed ("
                                         (.-status resp)
                                         "): "
                                         (or text "Unknown response"))))))))
        (.then (fn [payload]
                 (js->clj payload :keywordize-keys true)))))))

(defn- normalize-hex-data
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (when (and (seq text)
               (re-matches #"^0x[0-9a-f]+$" text))
      text)))

(defn- normalize-hex-quantity
  [value]
  (let [text (some-> value str str/trim str/lower-case)]
    (cond
      (not (seq text))
      nil

      (or (= text "0")
          (= text "0x")
          (= text "0x0"))
      "0x0"

      (re-matches #"^0x[0-9a-f]+$" text)
      text

      (re-matches #"^\d+$" text)
      (str "0x" (.toString (js/BigInt text) 16))

      :else
      nil)))

(defn- parse-across-transaction
  [tx]
  (let [to (normalize-address (:to tx))
        data (normalize-hex-data (:data tx))
        value (normalize-hex-quantity (:value tx))]
    (when (and to data)
      (cond-> {:to to
               :data data}
        (and (seq value)
             (not= value "0x0")) (assoc :value value)))))

(defn- across-approval->swap-config
  [approval]
  (let [swap-tx (parse-across-transaction (:swapTx approval))
        approval-txs (->> (or (:approvalTxns approval) [])
                          (keep parse-across-transaction)
                          vec)]
    (when swap-tx
      {:swap-tx swap-tx
       :approval-txs approval-txs})))

(defn- fetch-hyperunit-address!
  [base-url source-chain destination-chain asset destination-address]
  (let [url (hyperunit-gen-url base-url
                               source-chain
                               destination-chain
                               asset
                               destination-address)]
    (-> (js/fetch url #js {:method "GET"})
        (.then (fn [resp]
                 (if (.-ok resp)
                   (.json resp)
                   (.then (.text resp)
                          (fn [text]
                            (throw (js/Error.
                                    (str "HyperUnit address request failed ("
                                         (.-status resp)
                                         "): "
                                         (or (non-blank-text text) "Unknown response")))))))))
        (.then (fn [payload]
                 (let [payload* (js->clj payload :keywordize-keys true)
                       address (non-blank-text (:address payload*))
                       error-message (hyperunit-error-message payload*)
                       status (some-> (:status payload*) str str/trim)]
                   (cond
                     (seq address)
                     {:address address
                      :status status
                      :signatures (:signatures payload*)}

                     (seq error-message)
                     (throw (js/Error. error-message))

                     :else
                     (throw (js/Error. "HyperUnit address response missing deposit address.")))))))))

(defn- fetch-hyperunit-address-with-source-fallbacks!
  [base-url base-urls source-chain destination-chain asset destination-address]
  (let [source-candidates (or (seq (hyperunit-source-chain-candidates source-chain))
                              [(or (canonical-chain-token source-chain)
                                   (canonical-token source-chain)
                                   source-chain)])
        attempt-for-base-url! (fn [candidate-base-url]
                                (letfn [(attempt-source! [remaining last-error]
                                          (if-let [candidate-source (first remaining)]
                                            (-> (fetch-hyperunit-address! candidate-base-url
                                                                          candidate-source
                                                                          destination-chain
                                                                          asset
                                                                          destination-address)
                                                (.catch (fn [err]
                                                          (attempt-source! (rest remaining)
                                                                           (or err last-error)))))
                                            (js/Promise.reject
                                             (or last-error
                                                 (js/Error. "Unable to generate HyperUnit deposit address.")))))]
                                  (attempt-source! source-candidates nil)))]
    (with-hyperunit-base-url-fallbacks!
     {:base-url base-url
      :base-urls base-urls
      :error-message "Unable to generate HyperUnit deposit address."
      :request-fn attempt-for-base-url!})))

(defn- submit-hyperunit-address-deposit-request!
  [store owner-address action]
  (let [destination-address (normalize-address owner-address)
        from-chain (some-> (:fromChain action) str str/trim str/lower-case)
        asset (some-> (:asset action) str str/trim str/lower-case)
        network-label (or (non-blank-text (:network action))
                          (some-> from-chain str/capitalize))
        base-urls (resolve-hyperunit-base-urls store)
        base-url (first base-urls)]
    (cond
      (nil? destination-address)
      (js/Promise.resolve {:status "err"
                           :error "Connect your wallet before generating a deposit address."})

      (not (seq from-chain))
      (js/Promise.resolve {:status "err"
                           :error "Deposit source chain is missing for this asset."})

      (not (seq asset))
      (js/Promise.resolve {:status "err"
                           :error "Deposit asset is missing for this request."})

      :else
      (let [to-success-response (fn [{:keys [address signatures]} reused-address?]
                                  {:status "ok"
                                   :keep-modal-open? true
                                   :network network-label
                                   :asset asset
                                   :from-chain from-chain
                                   :deposit-address address
                                  :deposit-signatures signatures
                                  :reused-address? (true? reused-address?)})
            generate-address! (fn []
                                (-> (fetch-hyperunit-address-with-source-fallbacks! base-url
                                                                                    base-urls
                                                                                    from-chain
                                                                                    "hyperliquid"
                                                                                    asset
                                                                                    destination-address)
                                    (.then (fn [{:keys [address signatures]}]
                                             (to-success-response {:address address
                                                                   :signatures signatures}
                                                                  false)))))
            lookup-existing-address! (fn []
                                       (request-existing-hyperunit-deposit-address!
                                        base-url
                                        base-urls
                                        destination-address
                                        from-chain
                                        asset))
            fallback-after-generate-error!
            (fn [generate-error]
              (-> (lookup-existing-address!)
                  (.then (fn [fallback-address]
                           (if (map? fallback-address)
                             (to-success-response fallback-address true)
                             (js/Promise.reject generate-error))))))]
        (-> (lookup-existing-address!)
            (.then (fn [existing-address]
                     (if (map? existing-address)
                       (js/Promise.resolve (to-success-response existing-address true))
                       (.catch (generate-address!) fallback-after-generate-error!))))
            (.catch (fn [err]
                      {:status "err"
                       :error (hyperunit-request-error-message err
                                                               {:asset asset
                                                                :source-chain from-chain})})))))))

(defn- submit-hyperunit-send-asset-withdraw-request!
  [store owner-address action submit-send-asset!]
  (let [source-address (normalize-address owner-address)
        destination-address (non-blank-text (:destination action))
        destination-chain (some-> (:destinationChain action) str str/trim str/lower-case)
        asset (some-> (:asset action) str str/trim str/lower-case)
        token (non-blank-text (:token action))
        amount (some-> (:amount action) str str/trim)
        network-label (or (non-blank-text (:network action))
                          (some-> destination-chain str/capitalize))
        base-urls (resolve-hyperunit-base-urls store)
        base-url (first base-urls)]
    (cond
      (nil? source-address)
      (js/Promise.resolve {:status "err"
                           :error "Connect your wallet before withdrawing."})

      (not (seq destination-address))
      (js/Promise.resolve {:status "err"
                           :error "Enter a valid destination address."})

      (not (seq destination-chain))
      (js/Promise.resolve {:status "err"
                           :error "Withdrawal destination chain is missing for this asset."})

      (not (seq asset))
      (js/Promise.resolve {:status "err"
                           :error "Withdrawal asset is missing for this request."})

      (not (seq token))
      (js/Promise.resolve {:status "err"
                           :error "Withdrawal token symbol is missing for this request."})

      (not (seq amount))
      (js/Promise.resolve {:status "err"
                           :error "Enter a valid withdrawal amount."})

      :else
      (-> (fetch-hyperunit-address-with-source-fallbacks! base-url
                                                          base-urls
                                                          "hyperliquid"
                                                          destination-chain
                                                          asset
                                                          destination-address)
          (.then (fn [{:keys [address]}]
                   (-> (submit-send-asset! store
                                           source-address
                                           {:type "sendAsset"
                                            :destination address
                                            :sourceDex "spot"
                                            :destinationDex "spot"
                                            :token token
                                            :amount amount
                                            :fromSubAccount ""})
                       (.then (fn [exchange-resp]
                                (if (= "ok" (:status exchange-resp))
                                  {:status "ok"
                                   :keep-modal-open? true
                                   :network network-label
                                   :asset asset
                                   :token token
                                   :destination destination-address
                                   :destination-chain destination-chain
                                   :protocol-address address}
                                  (let [error-text (str/trim (str (fallback-exchange-response-error exchange-resp)))]
                                    {:status "err"
                                     :error (if (seq error-text)
                                              error-text
                                              "Unknown exchange error")})))))))
          (.catch (fn [err]
                    {:status "err"
                     :error (hyperunit-request-error-message err
                                                             {:asset asset
                                                              :source-chain destination-chain})}))))))

(defn- submit-usdc-bridge2-deposit-tx!
  [store owner-address action]
  (let [provider (wallet/provider)
        from-address (normalize-address owner-address)
        chain-config (resolve-deposit-chain-config store action)
        amount-units (parse-usdc-units (:amount action))
        usdc-address (:usdc-address chain-config)
        bridge-address (:bridge-address chain-config)]
    (cond
      (nil? provider)
      (js/Promise.resolve {:status "err"
                           :error "No wallet provider found. Connect your wallet first."})

      (nil? from-address)
      (js/Promise.resolve {:status "err"
                           :error "Connect your wallet before depositing."})

      (nil? amount-units)
      (js/Promise.resolve {:status "err"
                           :error "Enter a valid deposit amount."})

      (<= amount-units (js/BigInt "0"))
      (js/Promise.resolve {:status "err"
                           :error "Enter an amount greater than 0."})

      :else
      (-> (ensure-wallet-chain! provider chain-config)
          (.then (fn [_]
                   (provider-request! provider
                                      "eth_sendTransaction"
                                      [{:from from-address
                                        :to usdc-address
                                        :data (encode-erc20-transfer-call-data bridge-address amount-units)}])))
          (.then (fn [tx-hash]
                   (-> (wait-for-transaction-receipt! provider tx-hash)
                       (.then (fn [_]
                                {:status "ok"
                                 :txHash tx-hash
                                 :network (:network-label chain-config)})))))
          (.catch (fn [err]
                    {:status "err"
                     :error (wallet-error-message err)}))))))

(defn- parse-positive-bigint
  [value]
  (let [text (some-> value str str/trim)]
    (when (and (seq text)
               (re-matches #"^\d+$" text))
      (let [parsed (js/BigInt text)]
        (when (> parsed (js/BigInt "0"))
          parsed)))))

(defn- lifi-quote->swap-config
  [{:keys [action estimate transactionRequest]}]
  (let [approval-address (normalize-address (:approvalAddress estimate))
        from-amount-units (parse-positive-bigint (:fromAmount estimate))
        swap-token-address (normalize-address (get-in action [:fromToken :address]))
        swap-to-address (normalize-address (:to transactionRequest))
        swap-data (some-> (:data transactionRequest) str str/trim)
        swap-value (maybe-value-field (:value transactionRequest))]
    (when (and approval-address
               from-amount-units
               swap-token-address
               swap-to-address
               (seq swap-data))
      {:approval-address approval-address
       :from-amount-units from-amount-units
       :swap-token-address swap-token-address
       :swap-to-address swap-to-address
       :swap-data swap-data
       :swap-value swap-value})))

(defn- approve-lifi-swap-token-if-needed!
  [provider from-address {:keys [swap-token-address approval-address from-amount-units]}]
  (-> (read-erc20-allowance-units! provider
                                   swap-token-address
                                   from-address
                                   approval-address)
      (.then (fn [allowance-units]
               (if (< allowance-units from-amount-units)
                 (-> (provider-request! provider
                                        "eth_sendTransaction"
                                        [{:from from-address
                                          :to swap-token-address
                                          :data (encode-erc20-approve-call-data approval-address
                                                                                from-amount-units)
                                          :value "0x0"}])
                     (.then (fn [approve-tx-hash]
                              (wait-for-transaction-receipt! provider approve-tx-hash))))
                 (js/Promise.resolve nil))))))

(defn- submit-usdc-delta-bridge2-deposit!
  [store owner-address delta-units]
  (if (<= delta-units (js/BigInt "0"))
    (js/Promise.reject
     (js/Error. "Swap completed but no USDC was received for deposit."))
    (submit-usdc-bridge2-deposit-tx! store
                                     owner-address
                                     {:amount (usdc-units->amount-text delta-units)
                                      :chainId arbitrum-mainnet-chain-id})))

(defn- execute-lifi-swap-and-bridge2-deposit!
  [store owner-address provider from-address usdc-address {:keys [swap-to-address swap-data swap-value]}]
  (let [swap-transaction (cond-> {:from from-address
                                  :to swap-to-address
                                  :data swap-data}
                           (seq swap-value) (assoc :value swap-value))]
    (-> (read-erc20-balance-units! provider usdc-address from-address)
        (.then (fn [before-balance]
                 (-> (provider-request! provider
                                        "eth_sendTransaction"
                                        [swap-transaction])
                     (.then (fn [swap-tx-hash]
                              (wait-for-transaction-receipt! provider swap-tx-hash)))
                     (.then (fn [_]
                              (read-erc20-balance-units! provider usdc-address from-address)))
                     (.then (fn [after-balance]
                              (submit-usdc-delta-bridge2-deposit!
                               store
                               owner-address
                               (- after-balance before-balance))))))))))

(defn- send-and-confirm-evm-transaction!
  [provider from-address {:keys [to data value]}]
  (let [tx (cond-> {:from from-address
                    :to to
                    :data data}
             (seq value) (assoc :value value))]
    (-> (provider-request! provider
                           "eth_sendTransaction"
                           [tx])
        (.then (fn [tx-hash]
                 (-> (wait-for-transaction-receipt! provider tx-hash)
                     (.then (fn [_]
                              tx-hash))))))))

(defn- send-across-approval-transactions!
  [provider from-address approval-txs]
  (reduce (fn [promise approval-tx]
            (.then promise
                   (fn [_]
                     (send-and-confirm-evm-transaction! provider
                                                        from-address
                                                        approval-tx))))
          (js/Promise.resolve nil)
          approval-txs))

(defn- submit-usdh-across-deposit-tx!
  [_store owner-address action]
  (let [provider (wallet/provider)
        from-address (normalize-address owner-address)
        amount-units (parse-usdh-units (:amount action))
        chain-config (get chain-config-by-id arbitrum-mainnet-chain-id)
        usdc-address (:usdc-address chain-config)]
    (cond
      (nil? provider)
      (js/Promise.resolve {:status "err"
                           :error "No wallet provider found. Connect your wallet first."})

      (nil? from-address)
      (js/Promise.resolve {:status "err"
                           :error "Connect your wallet before depositing."})

      (nil? amount-units)
      (js/Promise.resolve {:status "err"
                           :error "Enter a valid deposit amount."})

      (<= amount-units (js/BigInt "0"))
      (js/Promise.resolve {:status "err"
                           :error "Enter an amount greater than 0."})

      (> amount-units usdh-route-max-units)
      (js/Promise.resolve {:status "err"
                           :error "Maximum deposit is 1000000 USDH."})

      :else
      (-> (ensure-wallet-chain! provider chain-config)
          (.then (fn [_]
                   (fetch-across-approval! from-address amount-units usdc-address)))
          (.then (fn [approval]
                   (let [{:keys [swap-tx approval-txs]} (across-approval->swap-config approval)]
                     (if (nil? swap-tx)
                       (js/Promise.reject
                        (js/Error. "Across approval response missing swap transaction fields."))
                       (-> (send-across-approval-transactions! provider
                                                               from-address
                                                               approval-txs)
                           (.then (fn [_]
                                    (send-and-confirm-evm-transaction! provider
                                                                       from-address
                                                                       swap-tx))))))))
          (.then (fn [tx-hash]
                   {:status "ok"
                    :txHash tx-hash
                    :network (:network-label chain-config)}))
          (.catch (fn [err]
                    {:status "err"
                     :error (wallet-error-message err)}))))))

(defn- submit-usdt-lifi-bridge2-deposit-tx!
  [store owner-address action]
  (let [provider (wallet/provider)
        from-address (normalize-address owner-address)
        amount-units (parse-usdc-units (:amount action))
        chain-config (get chain-config-by-id arbitrum-mainnet-chain-id)
        usdc-address (:usdc-address chain-config)]
    (cond
      (nil? provider)
      (js/Promise.resolve {:status "err"
                           :error "No wallet provider found. Connect your wallet first."})

      (nil? from-address)
      (js/Promise.resolve {:status "err"
                           :error "Connect your wallet before depositing."})

      (nil? amount-units)
      (js/Promise.resolve {:status "err"
                           :error "Enter a valid deposit amount."})

      (<= amount-units (js/BigInt "0"))
      (js/Promise.resolve {:status "err"
                           :error "Enter an amount greater than 0."})

      :else
      (-> (ensure-wallet-chain! provider chain-config)
          (.then (fn [_]
                   (fetch-lifi-quote! from-address amount-units usdc-address)))
          (.then (fn [quote]
                   (let [swap-config (lifi-quote->swap-config quote)]
                     (if (nil? swap-config)
                       (js/Promise.reject
                        (js/Error. "LiFi quote response missing required transaction fields."))
                       (-> (approve-lifi-swap-token-if-needed! provider from-address swap-config)
                           (.then (fn [_]
                                    (execute-lifi-swap-and-bridge2-deposit!
                                     store
                                     owner-address
                                     provider
                                     from-address
                                     usdc-address
                                     swap-config))))))))
          (.catch (fn [err]
                    {:status "err"
                     :error (wallet-error-message err)}))))))

(defn api-fetch-hyperunit-fee-estimate!
  [{:keys [store
           request-hyperunit-estimate-fees!
           now-ms-fn
           runtime-error-message]
    :or {request-hyperunit-estimate-fees! request-hyperunit-estimate-fees!
         now-ms-fn (fn [] (js/Date.now))
         runtime-error-message fallback-runtime-error-message}}]
  (let [request-estimate! (when (fn? request-hyperunit-estimate-fees!)
                            request-hyperunit-estimate-fees!)]
    (when (and request-estimate!
               (modal-active-for-fee-estimate? store))
      (let [base-urls (resolve-hyperunit-base-urls store)
            base-url (first base-urls)
            now-ms (now-ms-fn)]
        (swap! store update-in
               [:funding-ui :modal :hyperunit-fee-estimate]
               (fn [current]
                 (-> (funding-actions/normalize-hyperunit-fee-estimate current)
                     (assoc :status :loading
                            :requested-at-ms now-ms
                            :error nil))))
        (prefetch-selected-hyperunit-deposit-address! store)
        (-> (request-estimate! {:base-url base-url
                                :base-urls base-urls})
            (.then (fn [resp]
                     (when (modal-active-for-fee-estimate? store)
                       (let [timestamp (now-ms-fn)
                             by-chain (if (map? (:by-chain resp))
                                        (:by-chain resp)
                                        {})
                             error-text (non-blank-text (:error resp))]
                         (swap! store update-in
                                [:funding-ui :modal :hyperunit-fee-estimate]
                                (fn [current]
                                  (let [prev (funding-actions/normalize-hyperunit-fee-estimate current)]
                                    (if (seq error-text)
                                      (assoc prev
                                             :status :error
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error error-text)
                                      (assoc prev
                                             :status :ready
                                             :by-chain by-chain
                                             :updated-at-ms timestamp
                                             :error nil)))))))
                     resp))
            (.catch (fn [err]
                      (when (modal-active-for-fee-estimate? store)
                        (let [timestamp (now-ms-fn)
                              message (or (non-blank-text (runtime-error-message err))
                                          "Unable to load HyperUnit fee estimates.")]
                          (swap! store update-in
                                 [:funding-ui :modal :hyperunit-fee-estimate]
                                 (fn [current]
                                   (-> (funding-actions/normalize-hyperunit-fee-estimate current)
                                       (assoc :status :error
                                              :updated-at-ms timestamp
                                              :error message)))))))))))))

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
  (let [address (get-in @store [:wallet :address])
        action (:action request)]
    (if (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before transferring funds.")
      (-> (submit-usd-class-transfer! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (close-funding-modal! store default-funding-modal-state)
                       (show-toast! store :success "Transfer submitted.")
                       (refresh-after-funding-submit! store dispatch! address)
                       resp)
                     (let [error-text (str/trim (str (exchange-response-error resp)))
                           message (str "Transfer failed: "
                                        (if (seq error-text) error-text "Unknown exchange error"))]
                       (set-funding-submit-error! store show-toast! message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str "Transfer failed: "
                                       (if (seq error-text) error-text "Unknown runtime error"))]
                      (set-funding-submit-error! store show-toast! message))))))))

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
  (let [address (get-in @store [:wallet :address])
        action (:action request)
        submit-withdraw! (case (:type action)
                           "withdraw3" submit-withdraw3!
                           "hyperunitSendAssetWithdraw"
                           (fn [store* owner-address action*]
                             (submit-hyperunit-send-asset-withdraw-request-fn
                              store*
                              owner-address
                              action*
                              submit-send-asset!))
                           (fn [_store _address _action]
                             (js/Promise.resolve {:status "err"
                                                  :error "Withdrawal action type is not supported."})))]
    (if (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before withdrawing.")
      (-> (submit-withdraw! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (if (true? (:keep-modal-open? resp))
                       (let [asset-key (some-> (:asset resp) str str/lower-case keyword)
                             base-urls (resolve-hyperunit-base-urls store)
                             base-url (first base-urls)
                             now-ms (if (fn? now-ms-fn)
                                      (now-ms-fn)
                                      (js/Date.now))]
                         (swap! store (fn [state]
                                        (-> state
                                            (assoc-in [:funding-ui :modal :submitting?] false)
                                            (assoc-in [:funding-ui :modal :error] nil)
                                            (assoc-in [:funding-ui :modal :withdraw-generated-address] (:protocol-address resp))
                                            (assoc-in [:funding-ui :modal :hyperunit-lifecycle]
                                                      (awaiting-withdraw-lifecycle asset-key now-ms)))))
                         (start-hyperunit-withdraw-lifecycle-polling!
                          {:store store
                           :wallet-address address
                           :asset-key asset-key
                           :protocol-address (:protocol-address resp)
                           :destination-address (:destination resp)
                           :base-url base-url
                           :base-urls base-urls
                           :request-hyperunit-operations! request-hyperunit-operations!
                           :request-hyperunit-withdrawal-queue! request-hyperunit-withdrawal-queue!
                           :set-timeout-fn set-timeout-fn
                           :now-ms-fn now-ms-fn
                           :runtime-error-message runtime-error-message
                           :on-terminal-lifecycle! (fn [_lifecycle]
                                                    (refresh-after-funding-submit! store
                                                                                   dispatch!
                                                                                   address))})
                         (show-toast! store :success "Withdrawal submitted.")
                         resp)
                       (do
                         (close-funding-modal! store default-funding-modal-state)
                         (show-toast! store :success "Withdrawal submitted.")
                         (refresh-after-funding-submit! store dispatch! address)
                         resp))
                     (let [error-text (str/trim (str (or (:error resp)
                                                        (exchange-response-error resp))))
                           message (str "Withdrawal failed: "
                                        (if (seq error-text) error-text "Unknown exchange error"))]
                       (set-funding-submit-error! store show-toast! message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str "Withdrawal failed: "
                                       (if (seq error-text) error-text "Unknown runtime error"))]
                      (set-funding-submit-error! store show-toast! message))))))))

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
  (let [address (get-in @store [:wallet :address])
        action (:action request)
        submit-deposit! (case (:type action)
                          "bridge2Deposit" submit-usdc-bridge2-deposit!
                          "lifiUsdtToUsdcBridge2Deposit" submit-usdt-lifi-deposit!
                          "acrossUsdcToUsdhDeposit" submit-usdh-across-deposit!
                          "hyperunitGenerateDepositAddress" submit-hyperunit-address-request!
                          (fn [_store _address _action]
                            (js/Promise.resolve {:status "err"
                                                 :error "Deposit action type is not supported."})))]
    (if (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before depositing.")
      (let [submit-result (try
                            (submit-deposit! store address action)
                            (catch :default err
                              (js/Promise.reject err)))
            submit-promise (if (fn? (some-> submit-result .-then))
                             submit-result
                             (js/Promise.resolve submit-result))]
        (-> submit-promise
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                    (if (true? (:keep-modal-open? resp))
                       (let [asset-key (some-> (:asset resp) str str/lower-case keyword)
                             base-urls (resolve-hyperunit-base-urls store)
                             base-url (first base-urls)
                             now-ms (if (fn? now-ms-fn)
                                      (now-ms-fn)
                                      (js/Date.now))]
                         (swap! store (fn [state]
                                        (-> state
                                            (assoc-in [:funding-ui :modal :submitting?] false)
                                            (assoc-in [:funding-ui :modal :error] nil)
                                            (assoc-in [:funding-ui :modal :deposit-generated-address] (:deposit-address resp))
                                            (assoc-in [:funding-ui :modal :deposit-generated-signatures] (:deposit-signatures resp))
                                            (assoc-in [:funding-ui :modal :deposit-generated-asset-key] asset-key)
                                            (assoc-in [:funding-ui :modal :hyperunit-lifecycle]
                                                      (awaiting-deposit-lifecycle asset-key now-ms)))))
                         (start-hyperunit-deposit-lifecycle-polling!
                          {:store store
                           :wallet-address address
                           :asset-key asset-key
                           :protocol-address (:deposit-address resp)
                           :base-url base-url
                           :base-urls base-urls
                           :request-hyperunit-operations! request-hyperunit-operations!
                           :set-timeout-fn set-timeout-fn
                           :now-ms-fn now-ms-fn
                           :on-terminal-lifecycle! (fn [_lifecycle]
                                                    (refresh-after-funding-submit! store
                                                                                   dispatch!
                                                                                   address))})
                         (show-toast! store
                                      :success
                                      (if (true? (:reused-address? resp))
                                        "Using existing deposit address."
                                        "Deposit address generated."))
                         resp)
                       (let [network (or (:network resp) "Arbitrum")]
                         (close-funding-modal! store default-funding-modal-state)
                         (show-toast! store :success (str "Deposit submitted on " network "."))
                         (refresh-after-funding-submit! store dispatch! address)
                         resp))
                     (let [error-text (str/trim (str (or (:error resp)
                                                        (runtime-error-message resp))))
                           message (str "Deposit failed: "
                                        (if (seq error-text) error-text "Unknown runtime error"))]
                       (set-funding-submit-error! store show-toast! message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str "Deposit failed: "
                                       (if (seq error-text) error-text "Unknown runtime error"))]
                      (set-funding-submit-error! store show-toast! message)))))))))
