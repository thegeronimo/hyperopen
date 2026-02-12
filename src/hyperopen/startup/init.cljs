(ns hyperopen.startup.init)

(defn reset-startup-state!
  [{:keys [startup-runtime
           runtime
           default-startup-runtime-state
           mark-performance!
           schedule-startup-summary-log!]}]
  (if startup-runtime
    (reset! startup-runtime (default-startup-runtime-state))
    (swap! runtime assoc :startup (default-startup-runtime-state)))
  (mark-performance! "app:init:start")
  (schedule-startup-summary-log!))

(defn restore-persisted-ui-state!
  [{:keys [store
           restore-ui-font-preference!
           restore-asset-selector-sort-settings!
           restore-chart-options!
           restore-orderbook-ui!
           restore-agent-storage-mode!
           restore-active-asset!
           restore-asset-selector-markets-cache!
           restore-open-orders-sort-settings!
           restore-funding-history-pagination-settings!
           restore-trade-history-pagination-settings!
           restore-order-history-pagination-settings!]}]
  ;; Restore root typography preference (system default, optional Inter override).
  (restore-ui-font-preference!)
  ;; Restore asset selector sort settings from localStorage.
  (restore-asset-selector-sort-settings! store)
  ;; Restore chart options from localStorage.
  (restore-chart-options! store)
  ;; Restore orderbook UI options from localStorage.
  (restore-orderbook-ui! store)
  ;; Restore agent storage preference from localStorage.
  (restore-agent-storage-mode! store)
  ;; Restore selected asset from localStorage (default to BTC).
  (restore-active-asset! store)
  ;; Restore cached selector market symbols for immediate dropdown population.
  (restore-asset-selector-markets-cache! store)
  ;; Restore open orders sort settings from localStorage.
  (restore-open-orders-sort-settings! store)
  ;; Restore funding history pagination settings from localStorage.
  (restore-funding-history-pagination-settings! store)
  ;; Restore trade history pagination settings from localStorage.
  (restore-trade-history-pagination-settings! store)
  ;; Restore order history pagination settings from localStorage.
  (restore-order-history-pagination-settings! store))

(defn initialize-systems!
  [{:keys [store
           set-on-connected-handler!
           handle-wallet-connected
           init-wallet!
           init-router!
           register-icon-service-worker!
           initialize-remote-data-streams!
           kick-render!]}]
  (set-on-connected-handler! handle-wallet-connected)
  ;; Initialize wallet system.
  (init-wallet! store)
  ;; Initialize router.
  (init-router! store)
  ;; Register icon cache service worker for cross-reload symbol icon caching.
  (register-icon-service-worker!)
  ;; Initialize remote data streams.
  (initialize-remote-data-streams!)
  ;; Trigger initial render by updating the store.
  (kick-render! store))

(defn init!
  [{:keys [log-fn] :as deps}]
  (log-fn "Initializing Hyperopen...")
  (reset-startup-state! deps)
  (restore-persisted-ui-state! deps)
  (initialize-systems! deps))
