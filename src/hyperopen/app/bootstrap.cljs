(ns hyperopen.app.bootstrap
  (:require [nexus.registry :as nxr]
            [replicant.dom :as r]
            [hyperopen.app.startup :as app-startup]
            [hyperopen.runtime.action-adapters :as runtime-action-adapters]
            [hyperopen.runtime.bootstrap :as runtime-bootstrap]
            [hyperopen.runtime.effect-adapters :as runtime-effect-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.runtime.validation :as runtime-validation]
            [hyperopen.runtime.wiring :as runtime-wiring]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.startup.watchers :as startup-watchers]
            [hyperopen.views.app-view :as app-view]
            [hyperopen.wallet.agent-safety :as agent-safety]
            [hyperopen.wallet.address-watcher :as address-watcher]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.websocket.client :as ws-client]))

(defn render-app!
  [state]
  (when (exists? js/document)
    (r/render (.getElementById js/document "app")
              (app-view/app-view state))))

(defn- bootstrap-runtime-once!
  [runtime store]
  (runtime-bootstrap/bootstrap-runtime!
   {:register-runtime-deps (runtime-wiring/runtime-registration-deps runtime)
    :render-loop-deps {:store store
                       :render-watch-key ::render
                       :set-dispatch! r/set-dispatch!
                       :dispatch! nxr/dispatch
                       :render! render-app!
                       :document? (exists? js/document)
                       :request-animation-frame! platform/request-animation-frame!
                       :emit-fn telemetry/emit!
                       :now-ms-fn platform/now-ms}
    :watchers-deps
    {:store store
     :install-store-cache-watchers! startup-watchers/install-store-cache-watchers!
     :store-cache-watchers-deps
     {:persist-active-market-display! runtime-effect-adapters/persist-active-market-display!
      :persist-asset-selector-markets-cache! runtime-effect-adapters/persist-asset-selector-markets-cache!}
     :install-agent-safety-watch! agent-safety/install-agent-safety-watch!
     :agent-safety-watch-deps
     {:store store
      :runtime runtime
      :ahead-ms runtime-state/agent-schedule-cancel-ahead-ms
      :refresh-ms runtime-state/agent-schedule-cancel-refresh-ms
      :now-ms-fn platform/now-ms
      :set-timeout-fn platform/set-timeout!}
     :install-websocket-watchers! startup-watchers/install-websocket-watchers!
     :websocket-watchers-deps
     {:store store
      :runtime-view ws-client/runtime-view
      :append-diagnostics-event! runtime-effect-adapters/append-diagnostics-event!
      :sync-websocket-health! (fn [runtime-store & {:keys [force? projected-fingerprint]}]
                                (runtime-effect-adapters/sync-websocket-health-with-runtime!
                                 runtime
                                 runtime-store
                                 :force? force?
                                 :projected-fingerprint projected-fingerprint))
      :on-websocket-connected! address-watcher/on-websocket-connected!
      :on-websocket-disconnected! address-watcher/on-websocket-disconnected!}}
    :validation-deps
    {:store store
     :install-store-state-validation! runtime-validation/install-store-state-validation!}}))

(defn bootstrap-runtime!
  [{:keys [runtime store]}]
  (bootstrap-runtime-once! runtime store))

(defn ensure-runtime-bootstrapped!
  [runtime bootstrap-fn]
  (when (runtime-state/mark-runtime-bootstrapped! runtime)
    (bootstrap-fn)))

(defn reload!
  [{:keys [runtime store]}]
  ;; Re-register registry-held handlers and reload-safe listeners on every dev reload.
  (runtime-state/mark-runtime-bootstrapped! runtime)
  (bootstrap-runtime! {:runtime runtime
                       :store store})
  (app-startup/reload-runtime-bindings!
   {:runtime runtime
    :store store})
  (telemetry/log! "Reloading Hyperopen...")
  (wallet/set-on-connected-handler! runtime-action-adapters/handle-wallet-connected)
  (render-app! @store))
