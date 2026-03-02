(ns hyperopen.funding.effects
  (:require [clojure.string :as str]
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

(def ^:private hyperunit-mainnet-base-url
  "https://api.hyperunit.xyz")

(def ^:private hyperunit-testnet-base-url
  "https://api.hyperunit-testnet.xyz")

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

(defn- resolve-hyperunit-base-url
  [store]
  (let [wallet-chain-id (normalize-chain-id (get-in @store [:wallet :chain-id]))]
    (if (= wallet-chain-id arbitrum-sepolia-chain-id)
      hyperunit-testnet-base-url
      hyperunit-mainnet-base-url)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- hyperunit-gen-url
  [base-url from-chain asset destination-address]
  (let [encode js/encodeURIComponent]
    (str base-url
         "/gen/"
         (encode from-chain)
         "/hyperliquid/"
         (encode asset)
         "/"
         (encode destination-address))))

(defn- hyperunit-error-message
  [payload]
  (or (non-blank-text (:error payload))
      (non-blank-text (:message payload))))

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
    (-> (js/fetch url #js {:method "GET"
                           :headers #js {"Content-Type" "application/json"}})
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

(defn- fetch-hyperunit-deposit-address!
  [base-url from-chain asset destination-address]
  (let [url (hyperunit-gen-url base-url from-chain asset destination-address)]
    (-> (js/fetch url #js {:method "GET"
                           :headers #js {"Content-Type" "application/json"}})
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

(defn- submit-hyperunit-address-deposit-request!
  [store owner-address action]
  (let [destination-address (normalize-address owner-address)
        from-chain (some-> (:fromChain action) str str/trim str/lower-case)
        asset (some-> (:asset action) str str/trim str/lower-case)
        network-label (or (non-blank-text (:network action))
                          (some-> from-chain str/capitalize))
        base-url (resolve-hyperunit-base-url store)]
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
      (-> (fetch-hyperunit-deposit-address! base-url
                                            from-chain
                                            asset
                                            destination-address)
          (.then (fn [{:keys [address signatures]}]
                   {:status "ok"
                    :keep-modal-open? true
                    :network network-label
                    :asset asset
                    :from-chain from-chain
                    :deposit-address address
                    :deposit-signatures signatures}))
          (.catch (fn [err]
                    {:status "err"
                     :error (wallet-error-message err)}))))))

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
           exchange-response-error
           runtime-error-message
           show-toast!
           default-funding-modal-state]
    :or {submit-withdraw3! trading-api/submit-withdraw3!
         exchange-response-error fallback-exchange-response-error
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)
         default-funding-modal-state funding-actions/default-funding-modal-state}}]
  (let [address (get-in @store [:wallet :address])
        action (:action request)]
    (if (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before withdrawing.")
      (-> (submit-withdraw3! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (close-funding-modal! store default-funding-modal-state)
                       (show-toast! store :success "Withdrawal submitted.")
                       (refresh-after-funding-submit! store dispatch! address)
                       resp)
                     (let [error-text (str/trim (str (exchange-response-error resp)))
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
           submit-hyperunit-address-request!
           runtime-error-message
           show-toast!
           default-funding-modal-state]
    :or {submit-usdc-bridge2-deposit! submit-usdc-bridge2-deposit-tx!
         submit-usdt-lifi-deposit! submit-usdt-lifi-bridge2-deposit-tx!
         submit-hyperunit-address-request! submit-hyperunit-address-deposit-request!
         runtime-error-message fallback-runtime-error-message
         show-toast! (fn [_store _kind _message] nil)
         default-funding-modal-state funding-actions/default-funding-modal-state}}]
  (let [address (get-in @store [:wallet :address])
        action (:action request)
        submit-deposit! (case (:type action)
                          "bridge2Deposit" submit-usdc-bridge2-deposit!
                          "lifiUsdtToUsdcBridge2Deposit" submit-usdt-lifi-deposit!
                          "hyperunitGenerateDepositAddress" submit-hyperunit-address-request!
                          (fn [_store _address _action]
                            (js/Promise.resolve {:status "err"
                                                 :error "Deposit action type is not supported."})))]
    (if (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before depositing.")
      (-> (submit-deposit! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (if (true? (:keep-modal-open? resp))
                       (let [asset-key (some-> (:asset resp) str str/lower-case keyword)]
                         (swap! store (fn [state]
                                        (-> state
                                            (assoc-in [:funding-ui :modal :submitting?] false)
                                            (assoc-in [:funding-ui :modal :error] nil)
                                            (assoc-in [:funding-ui :modal :deposit-generated-address] (:deposit-address resp))
                                            (assoc-in [:funding-ui :modal :deposit-generated-signatures] (:deposit-signatures resp))
                                            (assoc-in [:funding-ui :modal :deposit-generated-asset-key] asset-key))))
                         (show-toast! store :success "Deposit address generated.")
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
                      (set-funding-submit-error! store show-toast! message))))))))
