(ns hyperopen.core
  (:require-macros [hyperopen.core.macros :refer [def-core-legacy-exports]])
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.core.compat :as compat]
            [hyperopen.asset-selector.settings :as asset-selector-settings]
            [hyperopen.runtime.bootstrap :as runtime-bootstrap]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.runtime.wiring :as runtime-wiring]
            [hyperopen.startup.collaborators :as startup-collaborators]
            [hyperopen.startup.composition :as startup-composition]
            [hyperopen.startup.runtime :as startup-runtime-lib]
            [hyperopen.startup.watchers :as startup-watchers]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.router :as router]
            [hyperopen.state.app-defaults :as app-defaults]
            [hyperopen.state.trading :as trading]))

(def ^:private default-funding-history-state
  account-history-actions/default-funding-history-state)

(def ^:private default-order-history-state
  account-history-actions/default-order-history-state)

(def ^:private default-trade-history-state
  account-history-actions/default-trade-history-state)

;; App state
(defonce store
  (atom
   (app-defaults/default-app-state
    {:websocket-health (ws-client/get-health-snapshot)
     :default-agent-state (agent-session/default-agent-state)
     :default-order-form (trading/default-order-form)
     :default-trade-history (default-trade-history-state)
     :default-funding-history (default-funding-history-state)
     :default-order-history (default-order-history-state)})))

;; Re-export all legacy public vars from `hyperopen.core.compat`.
;; This keeps `hyperopen.core/*` API stable while `hyperopen.core` stays focused
;; on entrypoint and startup/runtime bootstrapping.
(def-core-legacy-exports compat)

(defn- render-app!
  [state]
  (when (exists? js/document)
    (r/render (.getElementById js/document "app")
              (app-view/app-view state))))

(defn- store-cache-watcher-deps
  []
  {:persist-active-market-display! persist-active-market-display!
   :persist-asset-selector-markets-cache! persist-asset-selector-markets-cache!})

(defn- websocket-watcher-deps
  []
  {:store store
   :connection-state ws-client/connection-state
   :stream-runtime ws-client/stream-runtime
   :append-diagnostics-event! append-diagnostics-event!
   :sync-websocket-health! sync-websocket-health!
   :on-websocket-connected! address-watcher/on-websocket-connected!
   :on-websocket-disconnected! address-watcher/on-websocket-disconnected!})

(defn- bootstrap-runtime!
  []
  (runtime-bootstrap/bootstrap-runtime!
   {:register-runtime-deps (runtime-wiring/runtime-registration-deps)
    :render-loop-deps {:store store
                       :render-watch-key ::render
                       :set-dispatch! r/set-dispatch!
                       :dispatch! nxr/dispatch
                       :render! render-app!
                       :document? (exists? js/document)}
    :watchers-deps {:store store
                    :install-store-cache-watchers! startup-watchers/install-store-cache-watchers!
                    :store-cache-watchers-deps (store-cache-watcher-deps)
                    :install-websocket-watchers! startup-watchers/install-websocket-watchers!
                    :websocket-watchers-deps (websocket-watcher-deps)}}))

(defn- ensure-runtime-bootstrapped!
  []
  (when (compare-and-set! runtime-state/runtime-bootstrapped? false true)
    (bootstrap-runtime!)))

(defn reload []
  (ensure-runtime-bootstrapped!)
  (println "Reloading Hyperopen...")
  (wallet/set-on-connected-handler! handle-wallet-connected)
  (render-app! @store))

(defonce ^:private startup-runtime
  (atom (startup-runtime-lib/default-startup-runtime-state)))

(defn- mark-performance!
  [mark-name]
  (startup-runtime-lib/mark-performance! mark-name))

(defn- schedule-idle-or-timeout!
  [f]
  (startup-runtime-lib/schedule-idle-or-timeout! runtime-state/deferred-bootstrap-delay-ms f))

(defn- startup-base-deps
  []
  (startup-collaborators/startup-base-deps
   {:startup-runtime startup-runtime
    :store store
    :icon-service-worker-path runtime-state/icon-service-worker-path
    :per-dex-stagger-ms runtime-state/per-dex-stagger-ms
    :schedule-idle-or-timeout! schedule-idle-or-timeout!
    :mark-performance! mark-performance!}))

(defn- schedule-startup-summary-log!
  []
  (startup-composition/schedule-startup-summary-log!
   (assoc (startup-base-deps)
          :delay-ms runtime-state/startup-summary-delay-ms)))

(defn- register-icon-service-worker!
  []
  (startup-composition/register-icon-service-worker!
   (startup-base-deps)))

(defn- stage-b-account-bootstrap!
  [address dexs]
  (startup-composition/stage-b-account-bootstrap!
   (startup-base-deps)
   address
   dexs))

(defn- bootstrap-account-data!
  [address]
  (startup-composition/bootstrap-account-data!
   (assoc (startup-base-deps)
          :stage-b-account-bootstrap! stage-b-account-bootstrap!)
   address))

(defn- reify-address-handler
  [on-address-changed-fn handler-name]
  (reify address-watcher/IAddressChangeHandler
    (on-address-changed [_ _ new-address]
      (on-address-changed-fn new-address))
    (get-handler-name [_]
      handler-name)))

(defn- install-address-handlers!
  []
  (startup-composition/install-address-handlers!
   (assoc (startup-base-deps)
          :bootstrap-account-data! bootstrap-account-data!
          :address-handler-reify reify-address-handler
          :address-handler-name "startup-account-bootstrap-handler")))

(defn- start-critical-bootstrap!
  []
  (startup-composition/start-critical-bootstrap!
   (startup-base-deps)))

(defn- run-deferred-bootstrap!
  []
  (startup-composition/run-deferred-bootstrap!
   (startup-base-deps)))

(defn- schedule-deferred-bootstrap!
  []
  (startup-composition/schedule-deferred-bootstrap!
   (assoc (startup-base-deps)
          :run-deferred-bootstrap! run-deferred-bootstrap!)))

(defn initialize-remote-data-streams!
  []
  (startup-composition/initialize-remote-data-streams!
   (assoc (startup-base-deps)
          :install-address-handlers! install-address-handlers!
          :start-critical-bootstrap! start-critical-bootstrap!
          :schedule-deferred-bootstrap! schedule-deferred-bootstrap!)))

(defn init []
  (ensure-runtime-bootstrapped!)
  (startup-composition/init!
   (merge
    (startup-base-deps)
    {:default-startup-runtime-state startup-runtime-lib/default-startup-runtime-state
     :schedule-startup-summary-log! schedule-startup-summary-log!
     :restore-ui-font-preference! restore-ui-font-preference!
     :restore-asset-selector-sort-settings! asset-selector-settings/restore-asset-selector-sort-settings!
     :restore-chart-options! restore-chart-options!
     :restore-orderbook-ui! restore-orderbook-ui!
     :restore-agent-storage-mode! restore-agent-storage-mode!
     :restore-active-asset! restore-active-asset!
     :restore-asset-selector-markets-cache! restore-asset-selector-markets-cache!
     :restore-open-orders-sort-settings! restore-open-orders-sort-settings!
     :restore-funding-history-pagination-settings! restore-funding-history-pagination-settings!
     :restore-trade-history-pagination-settings! restore-trade-history-pagination-settings!
     :restore-order-history-pagination-settings! restore-order-history-pagination-settings!
     :set-on-connected-handler! wallet/set-on-connected-handler!
     :handle-wallet-connected handle-wallet-connected
     :init-wallet! wallet/init-wallet!
     :init-router! router/init!
     :register-icon-service-worker! register-icon-service-worker!
     :initialize-remote-data-streams! initialize-remote-data-streams!
     :kick-render! (fn [runtime-store]
                     (swap! runtime-store identity))})))
