(ns hyperopen.core
  (:require-macros [hyperopen.core.macros :refer [def-core-legacy-exports]])
  (:require [replicant.dom :as r]
            [nexus.registry :as nxr]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.account.history.actions :as account-history-actions]
            [hyperopen.chart.settings :as chart-settings]
            [hyperopen.core.compat :as compat]
            [hyperopen.asset-selector.settings :as asset-selector-settings]
            [hyperopen.orderbook.settings :as orderbook-settings]
            [hyperopen.runtime.action-adapters :as runtime-action-adapters]
            [hyperopen.runtime.bootstrap :as runtime-bootstrap]
            [hyperopen.runtime.effect-adapters :as runtime-effect-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.runtime.wiring :as runtime-wiring]
            [hyperopen.startup.composition :as startup-composition]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.startup.runtime :as startup-runtime-lib]
            [hyperopen.startup.watchers :as startup-watchers]
            [hyperopen.startup.wiring :as startup-wiring]
            [hyperopen.ui.preferences :as ui-preferences]
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

(defn- default-store-state
  []
  (app-defaults/default-app-state
   {:websocket-health (ws-client/get-health-snapshot)
    :default-agent-state (agent-session/default-agent-state)
    :default-order-form (trading/default-order-form)
    :default-trade-history (default-trade-history-state)
    :default-funding-history (default-funding-history-state)
    :default-order-history (default-order-history-state)}))

(defn make-system
  ([] (make-system {}))
  ([{:keys [store runtime]}]
   {:store (or store (atom (default-store-state)))
    :runtime (or runtime (runtime-state/make-runtime-state))}))

(defonce system
  (make-system {:runtime runtime-state/runtime}))

(def store
  (:store system))

(def ^:private runtime
  (:runtime system))

;; Re-export all legacy public vars from `hyperopen.core.compat`.
;; This keeps `hyperopen.core/*` API stable while `hyperopen.core` stays focused
;; on entrypoint and startup/runtime bootstrapping.
;; NOTE: compat is for external legacy callers only, not internal wiring.
(def-core-legacy-exports compat)

(defn- render-app!
  [state]
  (when (exists? js/document)
    (r/render (.getElementById js/document "app")
              (app-view/app-view state))))

(defn- bootstrap-runtime!
  []
  (runtime-bootstrap/bootstrap-runtime!
   {:register-runtime-deps (runtime-wiring/runtime-registration-deps runtime)
    :render-loop-deps {:store store
                       :render-watch-key ::render
                       :set-dispatch! r/set-dispatch!
                       :dispatch! nxr/dispatch
                       :render! render-app!
                       :document? (exists? js/document)}
    :watchers-deps {:store store
                    :install-store-cache-watchers! startup-watchers/install-store-cache-watchers!
                    :store-cache-watchers-deps
                    (startup-wiring/store-cache-watcher-deps
                     {:persist-active-market-display! runtime-effect-adapters/persist-active-market-display!
                      :persist-asset-selector-markets-cache! runtime-effect-adapters/persist-asset-selector-markets-cache!})
                    :install-websocket-watchers! startup-watchers/install-websocket-watchers!
                    :websocket-watchers-deps
                    (startup-wiring/websocket-watcher-deps
                     {:store store
                      :connection-state ws-client/connection-state
                      :stream-runtime ws-client/stream-runtime
                      :append-diagnostics-event! runtime-effect-adapters/append-diagnostics-event!
                      :sync-websocket-health! (fn [runtime-store & {:keys [force?]}]
                                                (runtime-effect-adapters/sync-websocket-health-with-runtime!
                                                 runtime
                                                 runtime-store
                                                 :force? force?))
                      :on-websocket-connected! address-watcher/on-websocket-connected!
                      :on-websocket-disconnected! address-watcher/on-websocket-disconnected!})}}))

(defn- ensure-runtime-bootstrapped!
  []
  (when (runtime-state/mark-runtime-bootstrapped! runtime)
    (bootstrap-runtime!)))

(defn reload []
  (ensure-runtime-bootstrapped!)
  (println "Reloading Hyperopen...")
  (wallet/set-on-connected-handler! runtime-action-adapters/handle-wallet-connected)
  (render-app! @store))

(defn- mark-performance!
  [mark-name]
  (startup-runtime-lib/mark-performance! mark-name))

(defn- schedule-idle-or-timeout!
  [f]
  (startup-runtime-lib/schedule-idle-or-timeout! runtime-state/deferred-bootstrap-delay-ms f))

(defn- startup-base-deps
  []
  (startup-wiring/startup-base-deps
   {:runtime runtime
    :store store
    :icon-service-worker-path runtime-state/icon-service-worker-path
    :per-dex-stagger-ms runtime-state/per-dex-stagger-ms
    :schedule-idle-or-timeout! schedule-idle-or-timeout!
    :mark-performance! mark-performance!}))

(defn- schedule-startup-summary-log!
  []
  (startup-wiring/schedule-startup-summary-log!
   (startup-base-deps)
   runtime-state/startup-summary-delay-ms))

(defn- register-icon-service-worker!
  []
  (startup-wiring/register-icon-service-worker!
   (startup-base-deps)))

(defn- stage-b-account-bootstrap!
  [address dexs]
  (startup-wiring/stage-b-account-bootstrap!
   (startup-base-deps)
   address
   dexs))

(defn- bootstrap-account-data!
  [address]
  (startup-wiring/bootstrap-account-data!
   (startup-base-deps)
   address
   stage-b-account-bootstrap!))

(defn- install-address-handlers!
  []
  (startup-wiring/install-address-handlers!
   (startup-base-deps)
   bootstrap-account-data!))

(defn- start-critical-bootstrap!
  []
  (startup-wiring/start-critical-bootstrap!
   (startup-base-deps)))

(defn- run-deferred-bootstrap!
  []
  (startup-wiring/run-deferred-bootstrap!
   (startup-base-deps)))

(defn- schedule-deferred-bootstrap!
  []
  (startup-wiring/schedule-deferred-bootstrap!
   (startup-base-deps)
   run-deferred-bootstrap!))

(defn initialize-remote-data-streams!
  []
  (startup-wiring/initialize-remote-data-streams!
   (startup-base-deps)
   {:install-address-handlers-fn install-address-handlers!
    :start-critical-bootstrap-fn start-critical-bootstrap!
    :schedule-deferred-bootstrap-fn schedule-deferred-bootstrap!}))

(defn init []
  (ensure-runtime-bootstrapped!)
  (startup-composition/init!
   (merge
    (startup-base-deps)
    {:default-startup-runtime-state startup-runtime-lib/default-startup-runtime-state
     :schedule-startup-summary-log! schedule-startup-summary-log!
     :restore-ui-font-preference! ui-preferences/restore-ui-font-preference!
     :restore-asset-selector-sort-settings! asset-selector-settings/restore-asset-selector-sort-settings!
     :restore-chart-options! chart-settings/restore-chart-options!
     :restore-orderbook-ui! orderbook-settings/restore-orderbook-ui!
     :restore-agent-storage-mode! startup-restore/restore-agent-storage-mode!
     :restore-active-asset! runtime-effect-adapters/restore-active-asset!
     :restore-asset-selector-markets-cache! runtime-effect-adapters/restore-asset-selector-markets-cache!
     :restore-open-orders-sort-settings! account-history-actions/restore-open-orders-sort-settings!
     :restore-funding-history-pagination-settings! account-history-actions/restore-funding-history-pagination-settings!
     :restore-trade-history-pagination-settings! account-history-actions/restore-trade-history-pagination-settings!
     :restore-order-history-pagination-settings! account-history-actions/restore-order-history-pagination-settings!
     :set-on-connected-handler! wallet/set-on-connected-handler!
     :handle-wallet-connected runtime-action-adapters/handle-wallet-connected
     :init-wallet! wallet/init-wallet!
     :init-router! router/init!
     :register-icon-service-worker! register-icon-service-worker!
     :initialize-remote-data-streams! initialize-remote-data-streams!
     :kick-render! (fn [runtime-store]
                     (swap! runtime-store identity))})))
