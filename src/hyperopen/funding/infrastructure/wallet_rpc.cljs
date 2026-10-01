(ns hyperopen.funding.infrastructure.wallet-rpc
  "EIP-1193 transport: chain switch/add, sends and receipts through the
   connected wallet.

   The deposit flows use the defaults, which keep their original wording and
   behavior. HyperEVM opts in to more through its chain config:

   - `:native-currency` replaces the Ether default in `wallet_addEthereumChain`;
   - `:verify-switch?` re-reads `eth_chainId` after a switch and rejects with
     `{:kind :chain-switch-unsupported}` when the wallet did not move, or
     `{:kind :chain-changed}` when it ended up on some other chain (it can
     switch, so it must not be remembered as unable to).

   A switch refused with 4200, -32601 or a \"not supported\" message rejects
   with an ex-info of `{:kind :chain-switch-unsupported}` that keeps the
   wallet's message and `code`, so existing callers see the same error."
  (:require [clojure.string :as str]))

(defn provider-request!
  [provider method & [params]]
  (if-not provider
    (js/Promise.reject (js/Error. "No wallet provider found. Connect your wallet first."))
    (.request provider
              (clj->js (cond-> {:method method}
                         (some? params) (assoc :params params))))))

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

(def ^:private default-native-currency
  {:name "Ether"
   :symbol "ETH"
   :decimals 18})

(defn- wallet-add-chain-params
  [{:keys [chain-id chain-name rpc-url explorer-url native-currency]}]
  {:chainId chain-id
   :chainName chain-name
   :nativeCurrency (or native-currency default-native-currency)
   :rpcUrls [rpc-url]
   :blockExplorerUrls [explorer-url]})

(defn- js-object?
  "Any JS object a wallet may reject with: an Error, a plain object, a class
   instance that does not extend Error, or one from another realm (an
   iframe or extension context). cljs `object?` accepts only plain objects
   of this realm, and `instance? js/Error` only this realm's Errors."
  [value]
  (= "object" (goog/typeOf value)))

(defn- error-field
  [err field]
  (when (some? err)
    (or (when (js-object? err) (aget err field))
        (get (ex-data err) (keyword field)))))

(defn- error-code
  [err]
  (error-field err "code"))

(defn- nested-error-code
  "MetaMask mobile nests the real code at `err.data.originalError.code`."
  [err]
  (some-> (error-field err "data")
          (as-> data (when (js-object? data) (aget data "originalError")))
          (as-> original (when (js-object? original) (aget original "code")))))

(defn- error-message
  [err]
  (str (or (error-field err "message") "")))

(defn- unknown-chain-error?
  [err]
  (or (= 4902 (error-code err))
      (= 4902 (nested-error-code err))))

(defn- switch-unsupported-error?
  [err]
  (or (contains? #{4200 -32601} (error-code err))
      (str/includes? (str/lower-case (error-message err)) "not supported")))

(defn- chain-switch-unsupported
  "An ex-info marking a wallet that cannot switch chains. It keeps the
   wallet's message and `code`, so a caller that only reads those sees the
   same error as before."
  [err]
  (let [message (let [text (str/trim (error-message err))]
                  (if (seq text) text "The wallet cannot switch networks."))
        error (ex-info message {:kind :chain-switch-unsupported
                                :code (error-code err)
                                :cause err})]
    (when-let [code (error-code err)]
      (aset error "code" code))
    error))

(defn request-chain-id!
  "The wallet's active chain as lowercase hex without leading zeros."
  [provider]
  (-> (provider-request! provider "eth_chainId")
      (.then normalize-chain-id)))

(defn- verify-chain!
  "Re-read the chain after a switch from `before`: some wallets resolve the
   switch without moving (`:chain-switch-unsupported`), and a wallet can
   also end up on another chain (`:chain-changed`)."
  [provider {:keys [chain-id chain-name]} before]
  (-> (request-chain-id! provider)
      (.then (fn [current]
               (cond
                 (= current chain-id)
                 current

                 (or (nil? current) (= current (normalize-chain-id before)))
                 (js/Promise.reject
                  (ex-info (str "Your wallet didn't switch to "
                                (or chain-name chain-id)
                                ".")
                           {:kind :chain-switch-unsupported
                            :chain-id current}))

                 :else
                 (js/Promise.reject
                  (ex-info (str "Your wallet moved to another network instead of "
                                (or chain-name chain-id)
                                ".")
                           {:kind :chain-changed
                            :chain-id current})))))))

(defn- classify-switch-error
  [err]
  (if (switch-unsupported-error? err)
    (chain-switch-unsupported err)
    err))

(defn ensure-wallet-chain!
  "Switch the wallet to `chain-config`'s chain, adding the chain first when
   the wallet does not know it (4902, top level or nested). Resolves with the
   chain id."
  [provider chain-config]
  (let [target-chain-id (:chain-id chain-config)
        switch! (fn []
                  (provider-request! provider
                                     "wallet_switchEthereumChain"
                                     [{:chainId target-chain-id}]))
        confirm (fn [before]
                  (fn [_]
                    (if (true? (:verify-switch? chain-config))
                      (verify-chain! provider chain-config before)
                      target-chain-id)))]
    (-> (provider-request! provider "eth_chainId")
        (.then (fn [current-chain-id]
                 (if (= (normalize-chain-id current-chain-id) target-chain-id)
                   (js/Promise.resolve target-chain-id)
                   (-> (switch!)
                       (.catch (fn [err]
                                 (if (unknown-chain-error? err)
                                   (-> (provider-request! provider
                                                          "wallet_addEthereumChain"
                                                          [(wallet-add-chain-params chain-config)])
                                       (.then (fn [_] (switch!)))
                                       (.catch (fn [err*]
                                                 (js/Promise.reject (classify-switch-error err*)))))
                                   (js/Promise.reject (classify-switch-error err)))))
                       (.then (confirm current-chain-id)))))))))

(def ^:private deposit-receipt-options
  {:poll-ms 1200
   :timeout-ms 120000
   :reverted-message "Deposit transaction reverted on-chain."
   :timeout-message "Timed out waiting for deposit confirmation."})

(defn wait-for-transaction-receipt!
  "Poll the WALLET provider for `tx-hash`'s receipt. `opts` may override
   `:poll-ms`, `:timeout-ms`, `:reverted-message`, `:timeout-message`,
   `:set-timeout-fn` and `:now-ms-fn`; the defaults are the deposit flow's."
  ([provider tx-hash]
   (wait-for-transaction-receipt! provider tx-hash {}))
  ([provider tx-hash opts]
   (let [{:keys [poll-ms timeout-ms reverted-message timeout-message
                 set-timeout-fn now-ms-fn]}
         (merge deposit-receipt-options (into {} (remove (comp nil? val)) opts))
         now-ms (fn [] (if (fn? now-ms-fn) (now-ms-fn) (js/Date.now)))
         schedule! (fn [f]
                     (if (fn? set-timeout-fn)
                       (set-timeout-fn f poll-ms)
                       (js/setTimeout f poll-ms)))
         started-at (now-ms)]
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
                                     (reject (js/Error. reverted-message))))
                                 (if (> (- (now-ms) started-at) timeout-ms)
                                   (reject (js/Error. timeout-message))
                                   (schedule! poll)))))
                      (.catch reject)))]
          (poll)))))))

(defn- transaction-params
  "eth_sendTransaction params: `:data` is left out when nil (a native value
   transfer), and the optional value, chain and fee fields only when set."
  [from-address {:keys [to data value chainId gas maxFeePerGas maxPriorityFeePerGas]}]
  (cond-> {:from from-address
           :to to}
    (some? data) (assoc :data data)
    (seq value) (assoc :value value)
    (seq chainId) (assoc :chainId chainId)
    (seq gas) (assoc :gas gas)
    (seq maxFeePerGas) (assoc :maxFeePerGas maxFeePerGas)
    (seq maxPriorityFeePerGas) (assoc :maxPriorityFeePerGas maxPriorityFeePerGas)))

(defn send-transaction!
  "Ask the wallet to sign and send `tx`; resolves with the transaction hash."
  [provider from-address tx]
  (provider-request! provider
                     "eth_sendTransaction"
                     [(transaction-params from-address tx)]))

(defn send-and-confirm-evm-transaction!
  [provider from-address tx]
  (-> (send-transaction! provider from-address tx)
      (.then (fn [tx-hash]
               (-> (wait-for-transaction-receipt! provider tx-hash)
                   (.then (fn [_]
                            tx-hash)))))))

(defn watch-asset!
  "Ask the wallet to show an ERC-20 (`wallet_watchAsset`). Its params are an
   object, not an array. Resolves true when the wallet added it."
  [provider {:keys [address symbol decimals image]}]
  (provider-request! provider
                     "wallet_watchAsset"
                     {:type "ERC20"
                      :options (cond-> {:address address
                                        :symbol symbol
                                        :decimals decimals}
                                 (seq image) (assoc :image image))}))
