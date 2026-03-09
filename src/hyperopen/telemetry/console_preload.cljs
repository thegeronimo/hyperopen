(ns hyperopen.telemetry.console-preload
  (:require [clojure.string :as str]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.runtime.validation :as runtime-validation]
            [nexus.registry :as nxr]
            [hyperopen.platform :as platform]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.system :as app-system]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.views.account-info.vm :as account-info-vm]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]
            [hyperopen.wallet.core :as wallet-core]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.client-compat :as ws-client-compat]
            [hyperopen.websocket.client :as ws-client]))

(def ^:private debug-api-key
  "HYPEROPEN_DEBUG")

(def ^:private debug-dispatch-prefix
  "HYPEROPEN_DEBUG.dispatch")

(declare js-plain-object?)

(defn- action-id->debug-string
  [action-id]
  (str action-id))

(defn- registered-action-id-strings
  []
  (->> (runtime-registry/registered-action-ids)
       (map action-id->debug-string)
       sort
       vec))

(defn- normalize-action-vector-input
  [action-vector]
  (cond
    (vector? action-vector) action-vector
    (sequential? action-vector) (vec action-vector)
    (array? action-vector) (js->clj action-vector :keywordize-keys true)
    :else nil))

(defn- normalize-debug-action-id
  [action-id]
  (cond
    (keyword? action-id) action-id
    (string? action-id) (let [trimmed (str/trim action-id)
                              normalized (if (str/starts-with? trimmed ":")
                                           (subs trimmed 1)
                                           trimmed)]
                          (when (seq normalized)
                            (keyword normalized)))
    :else nil))

(defn- normalize-debug-wire-keyword
  [value]
  (when (string? value)
    (let [trimmed (str/trim value)]
      (when (str/starts-with? trimmed ":")
        (let [normalized (subs trimmed 1)]
          (when (seq normalized)
            (keyword normalized)))))))

(defn- normalize-debug-wire-value
  [value]
  (cond
    (keyword? value) value
    (string? value) (or (normalize-debug-wire-keyword value) value)
    (vector? value) (mapv normalize-debug-wire-value value)
    (sequential? value) (mapv normalize-debug-wire-value value)
    (array? value) (mapv normalize-debug-wire-value (array-seq value))
    (map? value) (into {}
                      (map (fn [[k v]]
                             [(normalize-debug-wire-value k)
                              (normalize-debug-wire-value v)]))
                      value)
    (js-plain-object? value) (normalize-debug-wire-value (js->clj value :keywordize-keys true))
    :else value))

(defn- invalid-dispatch-arg-error
  []
  (js/Error.
   (str debug-dispatch-prefix
        " expected an action vector whose first item is a registered action id string.")))

(defn- unknown-action-id-error
  [action-id]
  (js/Error.
   (str debug-dispatch-prefix
        " received unregistered action id "
        (pr-str action-id)
        ". Call HYPEROPEN_DEBUG.registeredActionIds() for valid ids.")))

(defn- normalize-debug-action-vector
  [action-vector]
  (let [action* (normalize-action-vector-input action-vector)
        action-id (some-> action* first normalize-debug-action-id)
        args (mapv normalize-debug-wire-value (rest (or action* [])))
        registered-action-ids (runtime-registry/registered-action-ids)]
    (when-not (and (vector? action*)
                   (seq action*)
                   (contains? registered-action-ids action-id))
      (if (some? action-id)
        (throw (unknown-action-id-error action-id))
        (throw (invalid-dispatch-arg-error))))
    (into [action-id] args)))

(defn- dispatch-debug-action!
  [action-vector]
  (let [normalized-action (normalize-debug-action-vector action-vector)
        action-id (first normalized-action)
        args (vec (rest normalized-action))]
    (nxr/dispatch app-system/store nil [normalized-action])
    #js {:dispatched true
         :actionId (action-id->debug-string action-id)
         :argCount (count args)}))

(defn- normalize-debug-action-vectors
  [action-vectors]
  (cond
    (array? action-vectors)
    (mapv normalize-debug-action-vector (array-seq action-vectors))

    (sequential? action-vectors)
    (mapv normalize-debug-action-vector action-vectors)

    :else
    (throw (invalid-dispatch-arg-error))))

(defn- dispatch-debug-actions!
  [action-vectors]
  (let [normalized-actions (normalize-debug-action-vectors action-vectors)]
    (nxr/dispatch app-system/store nil normalized-actions)
    #js {:dispatchedCount (count normalized-actions)
         :actionIds (clj->js (mapv (comp action-id->debug-string first) normalized-actions))}))

(defn- parity-id-selector
  [parity-id]
  (str "[data-parity-id=\"" parity-id "\"]"))

(defn- selector-nodes
  [selector]
  (when-let [document (some-> js/globalThis .-document)]
    (try
      (array-seq (.querySelectorAll document selector))
      (catch :default _
        []))))

(defn- node-rect
  [node]
  (when (and node (fn? (.-getBoundingClientRect node)))
    (let [rect (.getBoundingClientRect node)]
      {:left (.-left rect)
       :right (.-right rect)
       :top (.-top rect)
       :bottom (.-bottom rect)
       :width (.-width rect)
       :height (.-height rect)})))

(defn- parity-element-info
  [parity-id]
  (let [nodes (selector-nodes (parity-id-selector parity-id))
        first-node (first nodes)]
    {:parity-id parity-id
     :present (boolean first-node)
     :count (count nodes)
     :rect (node-rect first-node)}))

(defn- keyword->wire-string
  [value]
  (when (keyword? value)
    (str value)))

(defn- keyword->name-string
  [value]
  (when (keyword? value)
    (name value)))

(defn- value->name-string
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    :else nil))

(defn- js-plain-object?
  [value]
  (instance? js/Object value))

(defn- funding-modal-oracle
  []
  (let [state @app-system/store
        view-model (funding-actions/funding-modal-view-model state)
        modal (:modal view-model)
        selected-deposit-asset-key (or (some-> view-model :deposit-selected-asset :key)
                                       (some-> view-model :deposit :selected-asset :key)
                                       (:deposit-selected-asset-key modal))
        modal-nodes (selector-nodes "[data-role='funding-modal']")
        mobile-sheet-nodes (selector-nodes "[data-funding-mobile-sheet-surface='true']")]
    {:open (boolean (:open? modal))
     :title (:title modal)
     :contentKind (some-> view-model :content :kind keyword->wire-string)
     :selectedDepositAssetKey (value->name-string selected-deposit-asset-key)
     :modalCount (count modal-nodes)
     :mobileSheetCount (count mobile-sheet-nodes)
     :presentationMode (cond
                         (pos? (count mobile-sheet-nodes)) "mobile-sheet"
                         (pos? (count modal-nodes)) "anchored-popover"
                         :else "closed")}))

(defn- wallet-status-oracle
  []
  (let [wallet (get @app-system/store :wallet {})]
    {:connected (boolean (:connected? wallet))
     :address (:address wallet)
     :chainId (:chain-id wallet)
     :connecting (boolean (:connecting? wallet))
     :error (:error wallet)
     :agentStatus (some-> wallet :agent :status keyword->name-string)
     :agentAddress (get-in wallet [:agent :agent-address])
     :agentError (get-in wallet [:agent :error])}))

(defn- account-surface-oracle
  []
  (let [state @app-system/store]
    {:mobileSurface (value->name-string (get-in state [:trade-ui :mobile-surface]))
     :selectedTab (value->name-string (get-in state [:account-info :selected-tab]))
     :mobileAccountPanelPresent (:present (parity-element-info "trade-mobile-account-panel"))
     :mobileSurfaceTabsPresent (:present (parity-element-info "trade-mobile-surface-tabs"))}))

(defn- asset-selector-oracle
  []
  (let [state @app-system/store
        dropdown (get-in state [:asset-selector :visible-dropdown])]
    {:visibleDropdown (value->name-string dropdown)
     :activeAsset (:active-asset state)
     :selectedMarketKey (get-in state [:asset-selector :selected-market-key])
     :searchTerm (get-in state [:asset-selector :search-term])
     :desktopPresent (:present (parity-element-info "asset-selector-desktop"))
     :mobilePresent (:present (parity-element-info "asset-selector-mobile"))}))

(defn- order-form-oracle
  []
  (let [state @app-system/store
        view-model (order-form-vm/order-form-vm state)
        submit (:submit view-model)]
    {:activeAsset (:active-asset state)
     :side (some-> (:side view-model) keyword->name-string)
     :type (some-> (:type view-model) keyword->name-string)
     :entryMode (some-> (:entry-mode view-model) keyword->name-string)
     :sizeDisplay (:size-display view-model)
     :runtimeError (:error view-model)
     :cancelError (get-in state [:orders :cancel-error])
     :submitDisabled (boolean (:disabled? submit))
     :submitReason (some-> (:reason submit) keyword->name-string)
     :submitError (:error-message submit)
     :submitTooltip (:tooltip submit)
     :orderFormPresent (:present (parity-element-info "order-form"))
     :submitButtonPresent (:present (parity-element-info "trade-submit-order-button"))}))

(defn- first-position-oracle
  []
  (let [view-model (account-info-vm/account-info-vm @app-system/store)
        position-data (first (:positions view-model))]
    {:present (boolean position-data)
     :positionData position-data}))

(defn- position-overlay-selectors
  [surface]
  (case (keyword (str/lower-case (str surface)))
    :margin {:surface-selector "[data-position-margin-surface='true']"
             :layer-selector "[data-role='position-margin-mobile-sheet-layer']"}
    :reduce {:surface-selector "[data-position-reduce-surface='true']"
             :layer-selector "[data-role='position-reduce-mobile-sheet-layer']"}
    :tpsl {:surface-selector "[data-position-tpsl-surface='true']"
           :layer-selector "[data-role='position-tpsl-mobile-sheet-layer']"}
    nil))

(defn- position-overlay-oracle
  [{:keys [surface]}]
  (if-let [{:keys [surface-selector layer-selector]} (position-overlay-selectors surface)]
    (let [surface-count (count (selector-nodes surface-selector))
          layer-count (count (selector-nodes layer-selector))]
      {:surface (some-> surface str str/lower-case)
       :open (pos? surface-count)
       :surfaceCount surface-count
       :mobileSheetLayerCount layer-count
       :presentationMode (cond
                           (pos? layer-count) "mobile-sheet"
                           (pos? surface-count) "anchored-popover"
                           :else "closed")})
    {:surface (some-> surface str)
     :open false
     :surfaceCount 0
     :mobileSheetLayerCount 0
     :presentationMode "closed"}))

(defn- latest-action-trace
  [action-id]
  (let [action-id* (normalize-debug-action-id action-id)]
    (->> (runtime-validation/debug-action-effect-traces-snapshot)
         (filter #(= action-id* (:action-id %)))
         last)))

(defn- effect-order-oracle
  [{:keys [actionId action-id]}]
  (if-let [trace (latest-action-trace (or actionId action-id))]
    {:present true
     :actionId (keyword->wire-string (:action-id trace))
     :covered (boolean (:covered? trace))
     :heavyEffectCount (:heavy-effect-count trace)
     :projectionEffectCount (:projection-effect-count trace)
     :projectionBeforeHeavy (:projection-before-heavy trace)
     :phaseOrderValid (:phase-order-valid trace)
     :duplicateHeavyEffectIds (mapv action-id->debug-string
                                    (:duplicate-heavy-effect-ids trace))
     :effectIds (mapv action-id->debug-string
                      (:effect-ids trace))
     :capturedAtMs (:captured-at-ms trace)}
    {:present false
     :actionId (some-> (or actionId action-id) normalize-debug-action-id keyword->wire-string)
     :covered false
     :heavyEffectCount 0
     :projectionEffectCount 0
     :projectionBeforeHeavy false
     :phaseOrderValid false
     :duplicateHeavyEffectIds []
     :effectIds []}))

(defn- named-oracle
  [name args]
  (let [name* (some-> name str str/lower-case)]
    (case name*
      "parity-element" (parity-element-info (:parityId args))
      "funding-modal" (funding-modal-oracle)
      "wallet-status" (wallet-status-oracle)
      "account-surface" (account-surface-oracle)
      "asset-selector" (asset-selector-oracle)
      "order-form" (order-form-oracle)
      "first-position" (first-position-oracle)
      "position-overlay" (position-overlay-oracle args)
      "effect-order" (effect-order-oracle args)
      (throw (js/Error. (str "Unknown QA oracle: " name))))))

(defn- wait-for-idle-digest
  []
  (let [state @app-system/store
        wallet (wallet-status-oracle)
        funding (funding-modal-oracle)]
    {:route (get-in state [:router :path])
     :wallet wallet
     :funding funding
     :trade-mobile-surface (get-in state [:trade-ui :mobile-surface])
     :account-tab (get-in state [:account-info :selected-tab])
     :action-trace-count (count (runtime-validation/debug-action-effect-traces-snapshot))
     :telemetry-event-count (count (telemetry/events))}))

(defn- wait-for-idle
  [opts]
  (let [opts* (cond
                (map? opts) opts
                (js-plain-object? opts) (js->clj opts :keywordize-keys true)
                :else {})
        quiet-ms (max 0 (or (:quiet-ms opts*)
                            (:quietMs opts*)
                            250))
        timeout-ms (max quiet-ms (or (:timeout-ms opts*)
                                     (:timeoutMs opts*)
                                     6000))
        poll-ms (max 10 (or (:poll-ms opts*)
                            (:pollMs opts*)
                            50))
        started-at (platform/now-ms)
        last-digest (atom nil)
        stable-since (atom started-at)]
    (js/Promise.
     (fn [resolve _reject]
       (letfn [(tick []
                 (let [now (platform/now-ms)
                       digest (wait-for-idle-digest)
                       stable? (= digest @last-digest)]
                   (when-not stable?
                     (reset! stable-since now)
                     (reset! last-digest digest))
                   (let [quiet-for-ms (- now @stable-since)
                         elapsed-ms (- now started-at)
                         settled? (>= quiet-for-ms quiet-ms)]
                     (if (or settled? (>= elapsed-ms timeout-ms))
                       (resolve
                        (clj->js {:settled settled?
                                  :elapsedMs elapsed-ms
                                  :quietForMs quiet-for-ms
                                  :digest digest}))
                       (js/setTimeout tick poll-ms)))))]
         (tick))))))

(defonce ^:private wallet-simulator-state
  (atom nil))

(defonce ^:private wallet-connected-handler-restore
  (atom nil))

(def ^:private default-simulated-typed-data-signature
  (str "0x"
       (apply str (repeat 64 "1"))
       (apply str (repeat 64 "2"))
       "1b"))

(defn- normalize-wallet-simulator-config
  [config]
  (let [config* (cond
                  (map? config) config
                  (js-plain-object? config) (js->clj config :keywordize-keys true)
                  :else {})]
    {:accounts (vec (map str (or (:accounts config*) [])))
     :request-accounts (vec (map str (or (:request-accounts config*)
                                         (:requestAccounts config*)
                                         (:accounts config*)
                                         [])))
     :chain-id (or (:chain-id config*)
                   (:chainId config*)
                   "0xa4b1")
     :accounts-error (or (:accounts-error config*)
                         (:accountsError config*))
     :request-accounts-error (or (:request-accounts-error config*)
                                 (:requestAccountsError config*))
     :typed-data-signature (or (:typed-data-signature config*)
                               (:typedDataSignature config*)
                               default-simulated-typed-data-signature)
     :typed-data-error (or (:typed-data-error config*)
                           (:typedDataError config*))
     :switch-chain-error (or (:switch-chain-error config*)
                             (:switchChainError config*))}))

(defn- wallet-simulator-state-snapshot
  []
  (let [{:keys [config listeners]} @wallet-simulator-state]
    {:installed (boolean @wallet-simulator-state)
     :config config
     :listenerCounts (into {}
                           (map (fn [[event handlers]]
                                  [(name event) (count handlers)]))
                           (or (some-> listeners deref) {}))}))

(defn- clear-wallet-simulator!
  []
  (let [{:keys [previous-global-provider previous-window previous-window-provider]} @wallet-simulator-state
        current-window (aget js/globalThis "window")]
    (if (some? previous-global-provider)
      (aset js/globalThis "ethereum" previous-global-provider)
      (js-delete js/globalThis "ethereum"))
    (if (some? previous-window)
      (do
        (aset previous-window "ethereum" previous-window-provider)
        (aset js/globalThis "window" previous-window))
      (do
        (when current-window
          (js-delete current-window "ethereum"))
        (js-delete js/globalThis "window"))))
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

(defn- set-wallet-connected-handler-mode!
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

(defn- install-wallet-simulator!
  [config]
  (let [config* (normalize-wallet-simulator-config config)
        listeners (atom {})
        previous-window (aget js/globalThis "window")
        window-object (or previous-window #js {})
        previous-window-provider (aget window-object "ethereum")
        previous-global-provider (aget js/globalThis "ethereum")
        provider #js {}]
    (when-not previous-window
      (aset js/globalThis "window" window-object))
    (aset provider "request"
          (fn [request]
            (let [{:keys [method params]} (js->clj request :keywordize-keys true)
                  simulator-config (or (get-in @wallet-simulator-state [:config]) config*)]
              (case method
                "eth_accounts"
                (if-let [message (:accounts-error simulator-config)]
                  (js/Promise.reject (js/Error. (str message)))
                  (js/Promise.resolve (clj->js (:accounts simulator-config))))

                "eth_requestAccounts"
                (if-let [message (:request-accounts-error simulator-config)]
                  (js/Promise.reject (js/Error. (str message)))
                  (js/Promise.resolve (clj->js (:request-accounts simulator-config))))

                ("eth_signTypedData_v4" "eth_signTypedData")
                (if-let [message (:typed-data-error simulator-config)]
                  (js/Promise.reject (js/Error. (str message)))
                  (js/Promise.resolve (:typed-data-signature simulator-config)))

                "wallet_switchEthereumChain"
                (if-let [message (:switch-chain-error simulator-config)]
                  (js/Promise.reject (js/Error. (str message)))
                  (let [next-chain-id (or (some-> params first (aget "chainId"))
                                          (some-> params first :chainId)
                                          (:chain-id simulator-config))]
                    (swap! wallet-simulator-state assoc-in [:config :chain-id] next-chain-id)
                    ((aget provider "__emit") "chainChanged" next-chain-id)
                    (js/Promise.resolve nil)))

                (js/Promise.resolve nil)))))
    (aset provider "on"
          (fn [event handler]
            (swap! listeners update (keyword event) (fnil conj []) handler)
            true))
    (aset provider "removeListener"
          (fn [event handler]
            (swap! listeners update (keyword event)
                   (fn [handlers]
                     (vec (remove #(identical? % handler) (or handlers [])))))
            true))
    (aset provider "__emit"
          (fn [event payload]
            (doseq [handler (get @listeners (keyword event) [])]
              (handler (if (or (map? payload)
                               (vector? payload)
                               (seq? payload))
                         (clj->js payload)
                         payload)))
            true))
    (reset! wallet-simulator-state {:config config*
                                    :listeners listeners
                                    :provider provider
                                    :previous-global-provider previous-global-provider
                                    :previous-window previous-window
                                    :previous-window-provider previous-window-provider})
    (aset window-object "ethereum" provider)
    (aset js/globalThis "ethereum" provider)
    (wallet-core/set-provider-override! provider)
    (wallet-core/reset-provider-listener-state!)
    (wallet-core/attach-listeners! app-system/store)
    (clj->js (wallet-simulator-state-snapshot))))

(defn- emit-wallet-simulator!
  [event payload]
  (if-let [provider (:provider @wallet-simulator-state)]
    (do
      ((aget provider "__emit") event payload)
      (clj->js (wallet-simulator-state-snapshot)))
    (throw (js/Error. "Wallet simulator is not installed."))))

(defn- install-exchange-simulator!
  [config]
  (let [config* (cond
                  (map? config) config
                  (js-plain-object? config) (js->clj config :keywordize-keys true)
                  :else {})]
    (trading-api/set-debug-exchange-simulator! config*)
    (clj->js (trading-api/debug-exchange-simulator-snapshot))))

(defn- clear-exchange-simulator!
  []
  (trading-api/clear-debug-exchange-simulator!)
  true)

(defn- qa-reset!
  []
  (runtime-validation/clear-debug-action-effect-traces!)
  (set-wallet-connected-handler-mode! "passthrough")
  (clear-wallet-simulator!)
  (clear-exchange-simulator!)
  (telemetry/clear-events!)
  (ws-client/clear-flight-recording!)
  #js {:ok true})

(defn- take-last-vec
  [limit coll]
  (->> (or coll [])
       (take-last limit)
       vec))

(defn- snapshot-map
  []
  {:captured-at-ms (platform/now-ms)
   :app-state @app-system/store
   :runtime-state @app-system/runtime
   :websocket {:runtime-view @ws-client/runtime-view
               :compat-projections (ws-client-compat/compat-projections)
               :client-runtime-state @ws-client/runtime-state
               :market-projection-telemetry (market-projection-runtime/market-projection-telemetry-snapshot)
               :flight-recording (ws-client/get-flight-recording-redacted)}
   :telemetry {:event-count (count (telemetry/events))
               :events (telemetry/events)}})

(defn- qa-snapshot-map
  []
  (let [state @app-system/store
        action-traces (runtime-validation/debug-action-effect-traces-snapshot)
        events (telemetry/events)
        flight-recording (ws-client/get-flight-recording-redacted)]
    {:captured-at-ms (platform/now-ms)
     :route (get-in state [:router :path])
     :activeAsset (:active-asset state)
     :wallet (wallet-status-oracle)
     :funding (funding-modal-oracle)
     :accountSurface (account-surface-oracle)
     :assetSelector (asset-selector-oracle)
     :orderForm (order-form-oracle)
     :recentActionEffectTraces (take-last-vec 25 action-traces)
     :websocket {:flightRecordingCount (count flight-recording)
                 :recentFlightRecording (take-last-vec 25 flight-recording)}
     :telemetry {:event-count (count events)
                 :recent-events (take-last-vec 50 events)}}))

(defn- snapshot-js
  []
  (clj->js (snapshot-map)))

(defn- qa-snapshot-js
  []
  (clj->js (qa-snapshot-map)))

(defn- snapshot-json
  []
  (js/JSON.stringify (snapshot-js) nil 2))

(defn- qa-snapshot-json
  []
  (js/JSON.stringify (qa-snapshot-js) nil 2))

(defn- download-snapshot!
  []
  (when-let [document (some-> js/globalThis .-document)]
    (let [payload (snapshot-json)
          blob (js/Blob. #js [payload] #js {:type "application/json"})
          object-url (js/URL.createObjectURL blob)
          link (.createElement document "a")
          timestamp (platform/now-ms)]
      (set! (.-href link) object-url)
      (set! (.-download link) (str "hyperopen-debug-snapshot-" timestamp ".json"))
      (.appendChild (.-body document) link)
      (.click link)
      (.remove link)
      (js/URL.revokeObjectURL object-url))
    true))

(defn- download-flight-recording!
  []
  (when-let [document (some-> js/globalThis .-document)]
    (let [recording (ws-client/get-flight-recording-redacted)
          payload (js/JSON.stringify (clj->js recording) nil 2)
          blob (js/Blob. #js [payload] #js {:type "application/json"})
          object-url (js/URL.createObjectURL blob)
          link (.createElement document "a")
          timestamp (platform/now-ms)]
      (set! (.-href link) object-url)
      (set! (.-download link) (str "hyperopen-flight-recording-" timestamp ".json"))
      (.appendChild (.-body document) link)
      (.click link)
      (.remove link)
      (js/URL.revokeObjectURL object-url))
    true))

(defn- debug-api
  []
  #js {:snapshot snapshot-js
       :qaSnapshot qa-snapshot-js
       :snapshotJson snapshot-json
       :qaSnapshotJson qa-snapshot-json
       :downloadSnapshot download-snapshot!
       :registeredActionIds (fn []
                              (clj->js (registered-action-id-strings)))
       :dispatch dispatch-debug-action!
       :dispatchMany dispatch-debug-actions!
       :waitForIdle wait-for-idle
       :elementRect (fn [parity-id]
                      (clj->js (parity-element-info (str parity-id))))
       :oracle (fn [name args]
                 (clj->js (named-oracle name (cond
                                              (map? args) args
                                              (js-plain-object? args) (js->clj args :keywordize-keys true)
                                              :else {}))))
       :qaReset qa-reset!
       :setWalletConnectedHandlerMode set-wallet-connected-handler-mode!
       :installWalletSimulator install-wallet-simulator!
       :walletSimulatorEmit emit-wallet-simulator!
       :clearWalletSimulator clear-wallet-simulator!
       :installExchangeSimulator install-exchange-simulator!
       :clearExchangeSimulator clear-exchange-simulator!
       :flightRecording (fn []
                          (clj->js (ws-client/get-flight-recording)))
       :flightRecordingRedacted (fn []
                                  (clj->js (ws-client/get-flight-recording-redacted)))
       :clearFlightRecording ws-client/clear-flight-recording!
       :replayFlightRecording (fn []
                               (clj->js (ws-client/replay-flight-recording)))
       :downloadFlightRecording download-flight-recording!
       :events (fn []
                 (clj->js (telemetry/events)))
       :eventsJson telemetry/events-json
       :clearEvents telemetry/clear-events!})

(when ^boolean goog.DEBUG
  (let [global js/globalThis
        api (debug-api)]
    (aset global debug-api-key api)
    ;; Convenience aliases for direct console use.
    (aset global "hyperopenSnapshot" (aget api "snapshot"))
    (aset global "hyperopenQaSnapshot" (aget api "qaSnapshot"))
    (aset global "hyperopenSnapshotJson" (aget api "snapshotJson"))
    (aset global "hyperopenQaSnapshotJson" (aget api "qaSnapshotJson"))
    (aset global "hyperopenDownloadSnapshot" (aget api "downloadSnapshot"))))
