(ns hyperopen.runtime.action-adapters
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.router :as router]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.vaults.actions :as vault-actions]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.actions :as wallet-actions]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.connection-runtime :as wallet-connection-runtime]
            [hyperopen.websocket.diagnostics-actions :as diagnostics-actions]
            [hyperopen.websocket.health-runtime :as health-runtime]))

(defn init-websockets [_state]
  [[:effects/init-websocket]])

(defn subscribe-to-asset [_state coin]
  [[:effects/subscribe-active-asset coin]
   [:effects/subscribe-orderbook coin]
   [:effects/subscribe-trades coin]])

(defn subscribe-to-webdata2 [_state address]
  [[:effects/subscribe-webdata2 address]])

(defn refresh-asset-markets [_state]
  [[:effects/fetch-asset-selector-markets]])

(defn load-user-data [_state address]
  [[:effects/api-load-user-data address]])

(defn set-funding-modal [_state modal]
  [[:effects/save [:funding-ui :modal] modal]])

(def ^:private projection-effect-ids
  #{:effects/save
    :effects/save-many})

(defn- projection-effect?
  [effect]
  (contains? projection-effect-ids (first effect)))

(defn- projection-first-effects
  [effects]
  (let [effects* (vec (or effects []))]
    (into []
          (concat (filter projection-effect? effects*)
                  (remove projection-effect? effects*)))))

(defn- entering-portfolio-route?
  [state normalized-path]
  (let [current-route (router/normalize-path (get-in state [:router :path]))]
    (and (str/starts-with? normalized-path "/portfolio")
         (not (str/starts-with? current-route "/portfolio")))))

(defn- portfolio-route-effects
  [state normalized-path]
  (if (entering-portfolio-route? state normalized-path)
    (portfolio-actions/select-portfolio-chart-tab
     state
     (get-in state [:portfolio-ui :chart-tab]))
    []))

(defn navigate
  [state path & [opts]]
  (let [p (router/normalize-path path)
        replace? (boolean (:replace? opts))
        base-effects (cond-> [[:effects/save [:router :path] p]]
                       replace? (conj [:effects/replace-state p])
                       (not replace?) (conj [:effects/push-state p]))
        route-effects (into []
                            (concat (vault-actions/load-vault-route state p)
                                    (funding-comparison-actions/load-funding-comparison-route state p)))
        portfolio-effects (portfolio-route-effects state p)
        route-entry-effects (projection-first-effects
                             (into []
                                   (concat portfolio-effects
                                           route-effects)))]
    (into base-effects route-entry-effects)))

(defn load-vault-route-action
  [state path]
  (vault-actions/load-vault-route state path))

(defn load-funding-comparison-route-action
  [state path]
  (funding-comparison-actions/load-funding-comparison-route state path))

(defn connect-wallet-action [state]
  (wallet-actions/connect-wallet-action state))

(defn disconnect-wallet-action [_state]
  (wallet-actions/disconnect-wallet-action nil))

(defn should-auto-enable-agent-trading?
  [state connected-address]
  (wallet-connection-runtime/should-auto-enable-agent-trading?
   state
   connected-address))

(defn handle-wallet-connected
  [store connected-address]
  (let [result (wallet-connection-runtime/handle-wallet-connected!
                {:store store
                 :connected-address connected-address
                 :should-auto-enable-agent-trading? should-auto-enable-agent-trading?
                 :dispatch! nxr/dispatch})
        route (get-in @store [:router :path])]
    (when (str/starts-with? (or route "") "/vaults")
      (nxr/dispatch store nil [[:actions/load-vault-route route]]))
    result))

(defn- exchange-response-error
  [resp]
  (agent-runtime/exchange-response-error resp))

(defn- runtime-error-message
  [err]
  (agent-runtime/runtime-error-message err))

(defn enable-agent-trading
  [_ store {:keys [storage-mode is-mainnet agent-name signature-chain-id]
            :or {storage-mode :local
                 is-mainnet true
                 agent-name nil
                 signature-chain-id nil}}]
  (agent-runtime/enable-agent-trading!
   {:store store
    :options {:storage-mode storage-mode
              :is-mainnet is-mainnet
              :agent-name agent-name
              :signature-chain-id signature-chain-id}
    :create-agent-credentials! agent-session/create-agent-credentials!
    :now-ms-fn platform/now-ms
    :normalize-storage-mode agent-session/normalize-storage-mode
    :default-signature-chain-id-for-environment agent-session/default-signature-chain-id-for-environment
    :build-approve-agent-action agent-session/build-approve-agent-action
    :approve-agent! trading-api/approve-agent!
    :persist-agent-session-by-mode! agent-session/persist-agent-session-by-mode!
    :runtime-error-message runtime-error-message
    :exchange-response-error exchange-response-error}))

(defn enable-agent-trading-action
  [state]
  (wallet-actions/enable-agent-trading-action
   state
   agent-session/normalize-storage-mode))

(defn set-agent-storage-mode-action
  [state storage-mode]
  (wallet-actions/set-agent-storage-mode-action
   state
   storage-mode
   agent-session/normalize-storage-mode))

(defn copy-wallet-address-action [state]
  (wallet-actions/copy-wallet-address-action state))

(defn reconnect-websocket-action [_state]
  [[:effects/reconnect-websocket]])

(defn- effective-now-ms
  [generated-at-ms]
  (health-runtime/effective-now-ms generated-at-ms))

(defn- ws-diagnostics-action-deps []
  {:effective-now-ms effective-now-ms
   :reconnect-cooldown-ms runtime-state/reconnect-cooldown-ms})

(defn toggle-ws-diagnostics [state]
  (diagnostics-actions/toggle-ws-diagnostics state))

(defn close-ws-diagnostics [_]
  (diagnostics-actions/close-ws-diagnostics nil))

(defn toggle-ws-diagnostics-sensitive [state]
  (diagnostics-actions/toggle-ws-diagnostics-sensitive state))

(defn ws-diagnostics-reconnect-now [state]
  (diagnostics-actions/ws-diagnostics-reconnect-now
   state
   (ws-diagnostics-action-deps)))

(defn ws-diagnostics-copy [_]
  (diagnostics-actions/ws-diagnostics-copy nil))

(defn set-show-surface-freshness-cues [_ checked]
  (diagnostics-actions/set-show-surface-freshness-cues nil checked))

(defn toggle-show-surface-freshness-cues [state]
  (diagnostics-actions/toggle-show-surface-freshness-cues state))

(defn ws-diagnostics-reset-market-subscriptions
  ([state]
   (ws-diagnostics-reset-market-subscriptions state :manual))
  ([state source]
   (diagnostics-actions/ws-diagnostics-reset-market-subscriptions
    state
    source
    (ws-diagnostics-action-deps))))

(defn ws-diagnostics-reset-orders-subscriptions
  ([state]
   (ws-diagnostics-reset-orders-subscriptions state :manual))
  ([state source]
   (diagnostics-actions/ws-diagnostics-reset-orders-subscriptions
    state
    source
    (ws-diagnostics-action-deps))))

(defn ws-diagnostics-reset-all-subscriptions
  ([state]
   (ws-diagnostics-reset-all-subscriptions state :manual))
  ([state source]
   (diagnostics-actions/ws-diagnostics-reset-all-subscriptions
    state
    source
    (ws-diagnostics-action-deps))))
