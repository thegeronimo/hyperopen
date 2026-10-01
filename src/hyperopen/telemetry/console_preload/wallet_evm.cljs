(ns hyperopen.telemetry.console-preload.wallet-evm
  "EVM transaction methods of the debug wallet simulator
   (`hyperopen.telemetry.console-preload.simulators`), which Playwright
   installs in place of a real EIP-1193 wallet.

   Every function here is pure over the simulator's state map and returns
   `{:state state' :result value}` or `{:state state' :error {:code :message}}`;
   the simulator turns an error into a rejected promise whose `Error` carries
   the EIP-1193 `code`, as real wallets do.

   The simulator only signs and sends. Receipts, gas prices and balances come
   from the mocked HyperEVM RPC
   (`tools/playwright/support/hyperevm_fixtures.mjs`), keyed by the
   deterministic hashes `simulated-tx-hash` hands out, never from the wallet.

   Every request is appended to `:requests` in order, so a spec can assert
   the exact wallet log (`HYPEROPEN_DEBUG.walletSimulatorSnapshot()`).")

(def unknown-chain-code
  "EIP-3085: the wallet does not know the chain; add it first."
  4902)

(def unsupported-method-code
  "EIP-1193: the wallet does not support the method."
  4200)

(def user-rejected-code
  "EIP-1193: the user rejected the request."
  4001)

(def ^:private default-messages
  {unknown-chain-code "Unrecognized chain ID. Try adding the chain using wallet_addEthereumChain first."
   unsupported-method-code "The Provider does not support the requested method."
   user-rejected-code "User rejected the request."})

(defn simulated-tx-hash
  "The simulator's `n`th transaction hash (1-based): `0x` + 56 `e`s + `n` as
   8 hex digits. `hyperevm_fixtures.mjs` `simulatedTxHash` builds the same
   string, so the mocked RPC can answer receipts for it."
  [n]
  (let [hex (.toString (js/Math.floor n) 16)]
    (str "0x"
         (apply str (repeat 56 "e"))
         (apply str (repeat (- 8 (count hex)) "0"))
         hex)))

(defn- config-value
  [config keys]
  (some #(get config %) keys))

(defn- error-spec
  "`{:code n :message s}` from a config entry: a number is a code with its
   standard message, a string a message, a map both. nil for no error."
  [entry]
  (cond
    (nil? entry) nil
    (number? entry) {:code entry :message (get default-messages entry "Wallet error.")}
    (string? entry) {:code nil :message entry}
    (map? entry) (let [code (:code entry)]
                   {:code code
                    :message (or (:message entry)
                                 (get default-messages code "Wallet error."))})
    :else nil))

(defn- chain-after-sends
  "`{n chain-id}` with integer `n`, from a map whose keys arrive as keywords
   (`:1`, from a keywordized JS object), strings or numbers."
  [value]
  (into {}
        (keep (fn [[k chain-id]]
                (let [n (js/parseInt (if (keyword? k) (name k) (str k)) 10)]
                  (when (and (not (js/isNaN n)) (some? chain-id))
                    [n (str chain-id)]))))
        (when (map? value) value)))

(defn normalize-config
  "The EVM part of the simulator config, from its camelCase or kebab keys:

   - `:switch-chain-error-code` (`switchChainErrorCode`): 4902 makes every
     chain other than the start chain unknown until `wallet_addEthereumChain`
     adds it; any other code (4200) rejects every switch with it;
   - `:send-transaction-errors` (`sendTransactionErrors`): one entry per
     `eth_sendTransaction`, in order; nil sends, a code/message/map rejects;
   - `:tx-hashes` (`txHashes`): hashes to hand out before the
     `simulated-tx-hash` sequence;
   - `:watch-asset-result` (`watchAssetResult`, default true);
   - `:chain-after-sends` (`chainAfterSends`): `{n chain-id}`, the chain the
     wallet moves to right after its `n`th successful send (as if the user
     switched networks), e.g. `{1 \"0xa4b1\"}` between an approve and a
     deposit."
  [config]
  {:switch-chain-error-code (config-value config [:switch-chain-error-code :switchChainErrorCode])
   :send-transaction-errors (mapv error-spec (or (config-value config [:send-transaction-errors
                                                                       :sendTransactionErrors])
                                                 []))
   :tx-hashes (mapv str (or (config-value config [:tx-hashes :txHashes]) []))
   :watch-asset-result (let [value (config-value config [:watch-asset-result :watchAssetResult])]
                         (if (nil? value) true value))
   :chain-after-sends (chain-after-sends (config-value config [:chain-after-sends :chainAfterSends]))})

(defn initial-state
  "The simulator's EVM state for a config already normalized by
   `normalize-config`, starting on `chain-id`."
  [chain-id]
  {:known-chain-ids #{chain-id}
   :requests []
   :transactions []
   :added-chains []
   :watched-assets []
   :unknown-requests []})

(defn record-request
  "Append `{:method :params}` to the request log."
  [state method params]
  (update state :requests (fnil conj []) (cond-> {:method method}
                                           (some? params) (assoc :params params))))

(defn switch-chain
  "`wallet_switchEthereumChain` to `chain-id`. Rejects with the configured
   code: 4902 only while the chain is unknown, any other code always."
  [state config chain-id]
  (let [code (:switch-chain-error-code config)]
    (cond
      (and (= unknown-chain-code code)
           (not (contains? (:known-chain-ids state) chain-id)))
      {:state state :error (error-spec code)}

      (and (some? code) (not= unknown-chain-code code))
      {:state state :error (error-spec code)}

      :else
      {:state state :result chain-id})))

(defn add-chain
  "`wallet_addEthereumChain`: remember the chain (so a later switch to it
   succeeds) and record its params. It does not switch."
  [state params]
  (let [chain-params (first params)
        chain-id (:chainId chain-params)]
    {:state (-> state
                (update :known-chain-ids (fnil conj #{}) chain-id)
                (update :added-chains (fnil conj []) chain-params))
     :result nil}))

(defn watch-asset
  "`wallet_watchAsset`: record the asset and answer the configured result."
  [state config params]
  {:state (update state :watched-assets (fnil conj []) params)
   :result (:watch-asset-result config)})

(defn send-transaction
  "`eth_sendTransaction`: record the transaction with the chain the wallet
   is on, then answer the next hash, or the configured error for this send
   (a rejected send records nothing). When `:chain-after-sends` names this
   send, the answer also carries `:chain-id`, the chain the wallet moves to
   right after it."
  [state config chain-id params]
  (let [index (count (:transactions state))
        attempt (count (filter #(= "eth_sendTransaction" (:method %)) (:requests state)))
        error (get (:send-transaction-errors config) (dec attempt))]
    (if error
      {:state state :error error}
      (let [hash (or (get (:tx-hashes config) index)
                     (simulated-tx-hash (inc index)))
            tx (first params)
            next-chain-id (get (:chain-after-sends config) (inc index))]
        (cond-> {:state (update state :transactions (fnil conj [])
                                (assoc tx :hash hash :walletChainId chain-id))
                 :result hash}
          next-chain-id (assoc :chain-id next-chain-id))))))

(defn unknown-method
  "A method the simulator does not implement: log it in
   `:unknown-requests` and reject with 4200, as a wallet without the method
   does, so a flow that starts routing a read through the wallet fails
   loudly instead of reading nil."
  [state method params]
  {:state (update state :unknown-requests (fnil conj [])
                  (cond-> {:method method} (some? params) (assoc :params params)))
   :error (error-spec unsupported-method-code)})

(defn js-error
  "An `Error` carrying the EIP-1193 `code`, as wallets reject."
  [{:keys [code message]}]
  (let [error (js/Error. (str message))]
    (when (some? code)
      (aset error "code" code))
    error))

(defn snapshot
  "The EVM state for `walletSimulatorSnapshot`, with the camelCase keys a
   spec reads: `requests`, `transactions`, `addedChains`, `watchedAssets`,
   `unknownRequests`."
  [state]
  (when state
    {:requests (:requests state)
     :transactions (:transactions state)
     :addedChains (:added-chains state)
     :watchedAssets (:watched-assets state)
     :unknownRequests (:unknown-requests state)}))
