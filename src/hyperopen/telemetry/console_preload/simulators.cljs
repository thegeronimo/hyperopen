(ns hyperopen.telemetry.console-preload.simulators
  (:require [clojure.string :as str]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.hyperevm.infrastructure.balance-poller :as hyperevm-balance-poller]
            [hyperopen.runtime.validation :as runtime-validation]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.telemetry.console-preload.wallet-evm :as wallet-evm]
            [hyperopen.wallet.core :as wallet-core]
            [hyperopen.websocket.client :as ws-client]))

(defonce ^:private wallet-simulator-state
  (atom nil))

(defonce ^:private wallet-connected-handler-restore
  (atom nil))

(def ^:private default-simulated-typed-data-signature
  (str "0x"
       (apply str (repeat 64 "1"))
       (apply str (repeat 64 "2"))
       "1b"))

(defn- js-plain-object?
  [value]
  (instance? js/Object value))

(defn- simulator-config-map
  [config]
  (cond
    (map? config) config
    (js-plain-object? config) (js->clj config :keywordize-keys true)
    :else {}))

(defn- aliased-config-value
  [config keys]
  (some #(get config %) keys))

(defn- normalize-string-vector
  [value]
  (vec (map str (or value []))))

(defn- normalize-optional-message
  [config keys]
  (aliased-config-value config keys))

(defn- normalize-wallet-simulator-config
  [config]
  (let [config* (simulator-config-map config)
        accounts (normalize-string-vector (aliased-config-value config* [:accounts]))]
    (merge
     {:accounts accounts
      :request-accounts (normalize-string-vector
                         (or (aliased-config-value config* [:request-accounts :requestAccounts])
                             (aliased-config-value config* [:accounts])))
      :chain-id (or (aliased-config-value config* [:chain-id :chainId])
                    "0xa4b1")
      :accounts-error (normalize-optional-message config* [:accounts-error :accountsError])
      :request-accounts-error (normalize-optional-message config* [:request-accounts-error :requestAccountsError])
      :typed-data-signature (or (aliased-config-value config* [:typed-data-signature :typedDataSignature])
                                default-simulated-typed-data-signature)
      :typed-data-error (normalize-optional-message config* [:typed-data-error :typedDataError])
      :switch-chain-error (normalize-optional-message config* [:switch-chain-error :switchChainError])}
     (wallet-evm/normalize-config config*))))

(defn- listener-counts
  [listeners]
  (into {}
        (map (fn [[event handlers]]
               [(name event) (count handlers)]))
        (or (some-> listeners deref) {})))

(defn- wallet-simulator-state-snapshot
  []
  (let [{:keys [config listeners evm]} @wallet-simulator-state]
    (merge {:installed (boolean @wallet-simulator-state)
            :config config
            :listenerCounts (listener-counts listeners)}
           (wallet-evm/snapshot evm))))

(defn wallet-simulator-snapshot
  "The installed wallet simulator's config and its request log (every
   EIP-1193 request in order, plus the transactions it sent, the chains it
   added and the assets it was asked to watch)."
  []
  (clj->js (wallet-simulator-state-snapshot)))

(defn- restore-global-provider!
  [provider]
  (if (some? provider)
    (aset js/globalThis "ethereum" provider)
    (js-delete js/globalThis "ethereum")))

(defn- restore-window-provider!
  [previous-window previous-window-provider]
  (let [current-window (aget js/globalThis "window")]
    (if (some? previous-window)
      (do
        (aset previous-window "ethereum" previous-window-provider)
        (aset js/globalThis "window" previous-window))
      (do
        (when current-window
          (js-delete current-window "ethereum"))
        (js-delete js/globalThis "window")))))

(defn clear-wallet-simulator!
  []
  (let [{:keys [previous-global-provider previous-window previous-window-provider]} @wallet-simulator-state]
    (restore-global-provider! previous-global-provider)
    (restore-window-provider! previous-window previous-window-provider))
  (reset! wallet-simulator-state nil)
  (wallet-core/clear-provider-override!)
  (wallet-core/reset-provider-listener-state!)
  true)

(defn- wallet-connected-handler-mode-snapshot
  []
  {:mode (if @wallet-connected-handler-restore
           "suppressed"
           "passthrough")
   :suppressed (boolean @wallet-connected-handler-restore)
   :hasHandler (boolean (wallet-core/current-on-connected-handler))})

(defn set-wallet-connected-handler-mode!
  [mode]
  (let [mode* (some-> mode str str/lower-case)]
    (case mode*
      "suppress"
      (do
        (when-not @wallet-connected-handler-restore
          (reset! wallet-connected-handler-restore
                  (wallet-core/current-on-connected-handler)))
        (wallet-core/clear-on-connected-handler!)
        (clj->js (wallet-connected-handler-mode-snapshot)))

      ("restore" "passthrough" nil)
      (do
        (if-let [handler @wallet-connected-handler-restore]
          (wallet-core/set-on-connected-handler! handler)
          (wallet-core/clear-on-connected-handler!))
        (reset! wallet-connected-handler-restore nil)
        (clj->js (wallet-connected-handler-mode-snapshot)))

      (throw (js/Error.
              (str "Unknown wallet connected handler mode: "
                   mode
                   ". Expected 'suppress' or 'passthrough'."))))))

(defn- promise-reject
  [message]
  (js/Promise.reject (js/Error. (str message))))

(defn- promise-resolve
  [value]
  (js/Promise.resolve value))

(defn- simulator-config
  [fallback-config]
  (or (get-in @wallet-simulator-state [:config])
      fallback-config))

(defn- next-chain-id
  [params config]
  (or (some-> params first (aget "chainId"))
      (some-> params first :chainId)
      (:chain-id config)))

(defn- handle-accounts-request
  [config]
  (if-let [message (:accounts-error config)]
    (promise-reject message)
    (promise-resolve (clj->js (:accounts config)))))

(defn- handle-request-accounts-request
  [config]
  (if-let [message (:request-accounts-error config)]
    (promise-reject message)
    (promise-resolve (clj->js (:request-accounts config)))))

(defn- handle-typed-data-request
  [config]
  (if-let [message (:typed-data-error config)]
    (promise-reject message)
    (promise-resolve (:typed-data-signature config))))

(defn- move-to-chain!
  "The wallet is now on `chain-id`: `eth_chainId` answers it and listeners
   hear `chainChanged`."
  [provider chain-id]
  (swap! wallet-simulator-state #(some-> % (assoc-in [:config :chain-id] chain-id)))
  ((aget provider "__emit") "chainChanged" chain-id))

(defn- handle-switch-chain-request
  [provider params config]
  (if-let [message (:switch-chain-error config)]
    (promise-reject message)
    (let [chain-id (next-chain-id params config)
          {:keys [error]} (wallet-evm/switch-chain (:evm @wallet-simulator-state) config chain-id)]
      (if error
        (js/Promise.reject (wallet-evm/js-error error))
        (do
          (move-to-chain! provider chain-id)
          (promise-resolve nil))))))

(defn- run-evm-request!
  "Apply a `wallet-evm` step to the simulator's EVM state and answer with
   its result, or reject with its EIP-1193 error. A step that answers a
   `:chain-id` moves the wallet to that chain right after."
  [provider step]
  (let [{:keys [state result error chain-id]} (step (:evm @wallet-simulator-state))]
    ;; A provider kept past `clear-wallet-simulator!` must not reinstall it.
    (swap! wallet-simulator-state #(some-> % (assoc :evm state)))
    (when chain-id
      (move-to-chain! provider chain-id))
    (if error
      (js/Promise.reject (wallet-evm/js-error error))
      (promise-resolve (if (or (map? result) (vector? result)) (clj->js result) result)))))

(defn- record-request!
  [method params]
  (when @wallet-simulator-state
    (swap! wallet-simulator-state update :evm wallet-evm/record-request method params)))

(defn- dispatch-wallet-request
  [provider fallback-config request]
  (let [{:keys [method params]} (js->clj request :keywordize-keys true)
        _ (record-request! method params)
        config (simulator-config fallback-config)]
    (case method
      "eth_accounts" (handle-accounts-request config)
      "eth_requestAccounts" (handle-request-accounts-request config)
      "eth_chainId" (promise-resolve (:chain-id config))
      ("eth_signTypedData_v4" "eth_signTypedData") (handle-typed-data-request config)
      "wallet_switchEthereumChain" (handle-switch-chain-request provider params config)
      "wallet_addEthereumChain" (run-evm-request! provider #(wallet-evm/add-chain % params))
      "wallet_watchAsset" (run-evm-request! provider #(wallet-evm/watch-asset % config params))
      "eth_sendTransaction" (run-evm-request!
                             provider
                             #(wallet-evm/send-transaction % config (:chain-id config) params))
      (run-evm-request! provider #(wallet-evm/unknown-method % method params)))))

(defn- add-listener!
  [listeners event handler]
  (swap! listeners update (keyword event) (fnil conj []) handler)
  true)

(defn- remove-listener!
  [listeners event handler]
  (swap! listeners update (keyword event)
         (fn [handlers]
           (vec (remove #(identical? % handler) (or handlers [])))))
  true)

(defn- payload->wire-value
  [payload]
  (if (or (map? payload)
          (vector? payload)
          (seq? payload))
    (clj->js payload)
    payload))

(defn- emit-listeners!
  [listeners event payload]
  (doseq [handler (get @listeners (keyword event) [])]
    (handler (payload->wire-value payload)))
  true)

(defn- build-wallet-simulator-provider
  [listeners fallback-config]
  (let [provider #js {}]
    (aset provider "request"
          (fn [request]
            (dispatch-wallet-request provider fallback-config request)))
    (aset provider "on"
          (fn [event handler]
            (add-listener! listeners event handler)))
    (aset provider "removeListener"
          (fn [event handler]
            (remove-listener! listeners event handler)))
    (aset provider "__emit"
          (fn [event payload]
            (emit-listeners! listeners event payload)))
    provider))

(defn- previous-provider-state
  []
  (let [previous-window (aget js/globalThis "window")
        window-object (or previous-window #js {})]
    {:previous-global-provider (aget js/globalThis "ethereum")
     :previous-window previous-window
     :window-object window-object
     :previous-window-provider (aget window-object "ethereum")}))

(defn- remember-wallet-simulator!
  [config listeners provider previous-state]
  (reset! wallet-simulator-state
          {:config config
           :evm (wallet-evm/initial-state (:chain-id config))
           :listeners listeners
           :provider provider
           :previous-global-provider (:previous-global-provider previous-state)
           :previous-window (:previous-window previous-state)
           :previous-window-provider (:previous-window-provider previous-state)}))

(defn- install-wallet-simulator-globals!
  [provider {:keys [previous-window window-object]}]
  (when-not previous-window
    (aset js/globalThis "window" window-object))
  (aset window-object "ethereum" provider)
  (aset js/globalThis "ethereum" provider)
  provider)

(defn- activate-wallet-simulator!
  [provider]
  (wallet-core/set-provider-override! provider)
  (wallet-core/reset-provider-listener-state!)
  (wallet-core/attach-listeners! app-system/store))

(defn install-wallet-simulator!
  [config]
  (let [config* (normalize-wallet-simulator-config config)
        listeners (atom {})
        previous-state (previous-provider-state)
        provider (build-wallet-simulator-provider listeners config*)]
    (remember-wallet-simulator! config* listeners provider previous-state)
    (install-wallet-simulator-globals! provider previous-state)
    (activate-wallet-simulator! provider)
    (clj->js (wallet-simulator-state-snapshot))))

(defn emit-wallet-simulator!
  "Emit a provider event. `chainChanged` also moves the wallet, so
   `eth_chainId` answers the new chain, as a real wallet does."
  [event payload]
  (if-let [provider (:provider @wallet-simulator-state)]
    (do
      (if (= "chainChanged" event)
        (move-to-chain! provider (str payload))
        ((aget provider "__emit") event payload))
      (clj->js (wallet-simulator-state-snapshot)))
    (throw (js/Error. "Wallet simulator is not installed."))))

(defn install-exchange-simulator!
  [config]
  (let [config* (simulator-config-map config)]
    (trading-api/set-debug-exchange-simulator! config*)
    (clj->js (trading-api/debug-exchange-simulator-snapshot))))

(defn exchange-simulator-snapshot
  []
  (clj->js (trading-api/debug-exchange-simulator-snapshot)))

(defn clear-exchange-simulator!
  []
  (trading-api/clear-debug-exchange-simulator!)
  true)

(defn set-hyperevm-poller-enabled!
  "Turn the background HyperEVM balance poller on or off, so browser tests
   decide when HyperEVM reads happen. `qaReset` turns it back on."
  [enabled?]
  (clj->js {:enabled (hyperevm-balance-poller/set-enabled! (true? enabled?))}))

(defn seed-funding-tooltip-fixture!
  ([] (seed-funding-tooltip-fixture! nil))
  ([config]
   (let [config* (simulator-config-map config)
         coin (or (some-> (or (:coin config*)
                              (:asset config*))
                          str
                          str/upper-case
                          not-empty)
                  "BTC")
         mark (or (:mark config*) 64000)
         oracle (or (:oracle config*) 63990)
         funding-rate (or (:funding-rate config*)
                          (:fundingRate config*)
                          0.00015)
         volume-24h (or (:volume-24h config*)
                        (:volume24h config*)
                        1250000)
         open-interest (or (:open-interest config*)
                           (:openInterest config*)
                           250000)
         market {:key (or (:market-key config*)
                          (:marketKey config*)
                          (str "perp:" coin))
                 :coin coin
                 :symbol (or (:symbol config*)
                             (str coin "-USDC"))
                 :base coin
                 :market-type :perp}
         predictability-summary {:mean 0.164527
                                 :next24h 0.00015
                                 :apy 0.05475
                                 :volatility 0.021975
                                 :lower-payment -0.04
                                 :upper-payment -0.03}
         next-state (-> @app-system/store
                        (assoc :active-asset coin)
                        (assoc :active-market market)
                        (assoc-in [:active-assets :contexts coin]
                                  {:coin coin
                                   :mark mark
                                   :markRaw (str mark)
                                   :oracle oracle
                                   :oracleRaw (str oracle)
                                   :change24h 1500
                                   :change24hPct 2.4
                                   :volume24h volume-24h
                                   :openInterest open-interest
                                   :fundingRate funding-rate})
                        (assoc-in [:active-assets :funding-predictability :by-coin coin]
                                  predictability-summary)
                        (assoc-in [:active-assets :funding-predictability :loading-by-coin coin] false)
                        (assoc-in [:active-assets :funding-predictability :error-by-coin coin] nil))]
     (reset! app-system/store next-state)
     (clj->js {:ok true
               :coin coin
               :marketKey (:key market)
               :fundingRate funding-rate}))))

(defn qa-reset!
  []
  (runtime-validation/clear-debug-action-effect-traces!)
  (set-wallet-connected-handler-mode! "passthrough")
  (clear-wallet-simulator!)
  (clear-exchange-simulator!)
  (hyperevm-balance-poller/set-enabled! true)
  (telemetry/clear-events!)
  (ws-client/clear-flight-recording!)
  #js {:ok true})
