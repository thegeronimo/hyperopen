(ns hyperopen.app.bootstrap
  (:require [nexus.registry :as nxr]
            [replicant.dom :as r]
            [hyperopen.runtime.action-adapters :as runtime-action-adapters]
            [hyperopen.runtime.bootstrap :as runtime-bootstrap]
            [hyperopen.runtime.effect-adapters :as runtime-effect-adapters]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.runtime.wiring :as runtime-wiring]
            [hyperopen.startup.watchers :as startup-watchers]
            [hyperopen.startup.wiring :as startup-wiring]
            [hyperopen.views.app-view :as app-view]
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
                       :document? (exists? js/document)}
    :watchers-deps
    {:store store
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

(defn bootstrap-runtime!
  [{:keys [runtime store]}]
  (bootstrap-runtime-once! runtime store))

(defn ensure-runtime-bootstrapped!
  [runtime bootstrap-fn]
  (when (runtime-state/mark-runtime-bootstrapped! runtime)
    (bootstrap-fn)))

(defn reload!
  [{:keys [runtime store]}]
  (ensure-runtime-bootstrapped!
   runtime
   #(bootstrap-runtime-once! runtime store))
  (println "Reloading Hyperopen...")
  (wallet/set-on-connected-handler! runtime-action-adapters/handle-wallet-connected)
  (render-app! @store))
