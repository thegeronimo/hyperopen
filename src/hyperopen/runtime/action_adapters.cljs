(ns hyperopen.runtime.action-adapters
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.account.spectate-mode-actions :as spectate-mode-actions]
            [hyperopen.api-wallets.actions :as api-wallets-actions]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.leaderboard.actions :as leaderboard-actions]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.funding-comparison.actions :as funding-comparison-actions]
            [hyperopen.route-modules :as route-modules]
            [hyperopen.router :as router]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.trade-modules :as trade-modules]
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
   [:effects/subscribe-trades coin]
   [:effects/sync-active-asset-funding-predictability coin]])

(defn subscribe-to-webdata2 [_state address]
  [[:effects/subscribe-webdata2 address]])

(defn refresh-asset-markets [_state]
  [[:effects/fetch-asset-selector-markets]])

(defn load-user-data [_state address]
  [[:effects/api-load-user-data address]])

(defn set-funding-modal [state modal]
  (funding-actions/set-funding-modal-compat state modal))

(defn open-spectate-mode-modal
  [state & [trigger-bounds]]
  (spectate-mode-actions/open-spectate-mode-modal state trigger-bounds))

(defn close-spectate-mode-modal
  [state]
  (spectate-mode-actions/close-spectate-mode-modal state))

(defn set-spectate-mode-search
  [state value]
  (spectate-mode-actions/set-spectate-mode-search state value))

(defn set-spectate-mode-label
  [state value]
  (spectate-mode-actions/set-spectate-mode-label state value))

(defn start-spectate-mode
  [state & [address]]
  (spectate-mode-actions/start-spectate-mode state address))

(defn stop-spectate-mode
  [state]
  (spectate-mode-actions/stop-spectate-mode state))

(defn add-spectate-mode-watchlist-address
  [state & [address]]
  (spectate-mode-actions/add-spectate-mode-watchlist-address state address))

(defn remove-spectate-mode-watchlist-address
  [state address]
  (spectate-mode-actions/remove-spectate-mode-watchlist-address state address))

(defn edit-spectate-mode-watchlist-address
  [state address]
  (spectate-mode-actions/edit-spectate-mode-watchlist-address state address))

(defn clear-spectate-mode-watchlist-edit
  [state]
  (spectate-mode-actions/clear-spectate-mode-watchlist-edit state))

(defn copy-spectate-mode-watchlist-address
  [state address]
  (spectate-mode-actions/copy-spectate-mode-watchlist-address state address))

(defn copy-spectate-mode-watchlist-link
  [state address]
  (spectate-mode-actions/copy-spectate-mode-watchlist-link state address))

(defn start-spectate-mode-watchlist-address
  [state address]
  (spectate-mode-actions/start-spectate-mode-watchlist-address state address))

(def ^:private projection-effect-ids
  #{:effects/save
    :effects/save-many})

(defn- projection-effect?
  [effect]
  (contains? projection-effect-ids (first effect)))

(defn- split-projection-effects
  [effects]
  (let [effects* (vec (or effects []))]
    {:projection-effects (into [] (filter projection-effect? effects*))
     :follow-up-effects (into [] (remove projection-effect? effects*))}))

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

(defn- navigation-browser-path
  [state normalized-path]
  (spectate-mode-links/spectate-url-path
   normalized-path
   (when (account-context/spectate-mode-active? state)
     (account-context/spectate-address state))))

(defn- trade-chart-module-effect
  [state normalized-path]
  (when (and (router/trade-route? normalized-path)
             (not (trade-modules/trade-chart-ready? state))
             (not (trade-modules/trade-chart-loading? state)))
    [:effects/load-trade-chart-module]))

(defn- route-loader-effects
  [state normalized-path]
  (into []
        (concat (leaderboard-actions/load-leaderboard-route state normalized-path)
                (vault-actions/load-vault-route state normalized-path)
                (funding-comparison-actions/load-funding-comparison-route state normalized-path)
                (staking-actions/load-staking-route state normalized-path)
                (api-wallets-actions/load-api-wallet-route state normalized-path))))

(defn- route-projection-and-follow-up-effects
  [state normalized-path]
  (split-projection-effects
   (into []
         (concat (portfolio-route-effects state normalized-path)
                 (route-loader-effects state normalized-path)))))

(defn- deferred-route-effects
  [state normalized-path]
  (let [module-effect (when-let [_module-id (route-modules/route-module-id normalized-path)]
                        [:effects/load-route-module normalized-path])
        trade-chart-effect (trade-chart-module-effect state normalized-path)]
    (cond-> []
      module-effect (conj module-effect)
      trade-chart-effect (conj trade-chart-effect))))

(defn- browser-navigation-effect
  [browser-path replace?]
  (if replace?
    [:effects/replace-state browser-path]
    [:effects/push-state browser-path]))

(defn navigate
  [state path & [opts]]
  (let [normalized-path (router/normalize-path path)
        browser-path (navigation-browser-path state normalized-path)
        replace? (boolean (:replace? opts))
        {:keys [projection-effects follow-up-effects]}
        (route-projection-and-follow-up-effects state normalized-path)]
    (into [[:effects/save [:router :path] normalized-path]]
          (concat projection-effects
                  [(browser-navigation-effect browser-path replace?)]
                  (deferred-route-effects state normalized-path)
                  follow-up-effects))))

(defn load-vault-route-action
  [state path]
  (vault-actions/load-vault-route state path))

(defn load-leaderboard-route-action
  [state path]
  (leaderboard-actions/load-leaderboard-route state path))

(defn load-funding-comparison-route-action
  [state path]
  (funding-comparison-actions/load-funding-comparison-route state path))

(defn load-staking-route-action
  [state path]
  (staking-actions/load-staking-route state path))

(defn load-api-wallet-route-action
  [state path]
  (api-wallets-actions/load-api-wallet-route state path))

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
    (when (staking-actions/staking-route? route)
      (nxr/dispatch store nil [[:actions/load-staking-route route]]))
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
    :format-agent-name-with-valid-until agent-session/format-agent-name-with-valid-until
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
