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
           restore-ui-locale-preference!
           restore-asset-selector-sort-settings!
           restore-chart-options!
           restore-orderbook-ui!
           restore-portfolio-summary-time-range!
           restore-vaults-snapshot-range!
           restore-agent-storage-mode!
           restore-trading-settings!
           restore-spectate-mode-preferences!
           restore-spectate-mode-url!
           restore-trade-route-tab!
           restore-active-asset!
           restore-asset-selector-markets-cache!
           restore-leaderboard-preferences!
           restore-open-orders-sort-settings!
           restore-funding-history-pagination-settings!
           restore-trade-history-pagination-settings!
           restore-order-history-pagination-settings!]}]
  ;; Restore root typography preference (system default, optional Inter override).
  (restore-ui-font-preference!)
  ;; Restore UI locale preference for number/date formatting ownership.
  (restore-ui-locale-preference! store)
  ;; Restore asset selector sort settings from localStorage.
  (restore-asset-selector-sort-settings! store)
  ;; Restore chart options from localStorage.
  (restore-chart-options! store)
  ;; Restore orderbook UI options from localStorage.
  (restore-orderbook-ui! store)
  ;; Restore portfolio summary range selector from localStorage.
  (restore-portfolio-summary-time-range! store)
  ;; Restore vault snapshot range selector from localStorage.
  (restore-vaults-snapshot-range! store)
  ;; Restore agent storage preference from localStorage.
  (restore-agent-storage-mode! store)
  ;; Restore bounded trading settings from localStorage.
  (when (fn? restore-trading-settings!)
    (restore-trading-settings! store))
  ;; Restore Spectate Mode watchlist and last-used modal search preference.
  (restore-spectate-mode-preferences! store)
  ;; Restore Spectate Mode directly from the current URL query when present.
  (restore-spectate-mode-url! store)
  ;; Restore trade account-info tab directly from the current URL query when present.
  (restore-trade-route-tab! store)
  ;; Restore selected asset from localStorage (default to BTC).
  (restore-active-asset! store)
  ;; Restore cached selector market symbols for immediate dropdown population.
  (restore-asset-selector-markets-cache! store)
  ;; Restore leaderboard timeframe, sort, and page-size preferences from IndexedDB.
  (when (fn? restore-leaderboard-preferences!)
    (restore-leaderboard-preferences! store))
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
           restore-vaults-snapshot-range!
           install-asset-selector-shortcuts!
           install-position-tpsl-clickaway!
           register-icon-service-worker!
           initialize-remote-data-streams!
           load-post-render-route-effects!
           kick-render!
           schedule-post-render-startup!]}]
  (set-on-connected-handler! handle-wallet-connected)
  ;; Initialize wallet system.
  (init-wallet! store)
  ;; Initialize router.
  (init-router! store)
  ;; Re-apply vault snapshot range after router init for deep-link refreshes.
  (when (fn? restore-vaults-snapshot-range!)
    (restore-vaults-snapshot-range! store))
  ;; Trigger initial render by updating the store.
  (kick-render! store)
  (let [post-render-startup!
        (fn []
          ;; Install global keyboard shortcuts that should work regardless of focus target.
          (when (fn? install-asset-selector-shortcuts!)
            (install-asset-selector-shortcuts!))
          ;; Install click-away behavior for positioned overlays (TP/SL, Reduce, Margin, Spectate Mode).
          (when (fn? install-position-tpsl-clickaway!)
            (install-position-tpsl-clickaway!))
          ;; Register icon cache service worker for cross-reload symbol icon caching.
          (register-icon-service-worker!)
          ;; Initialize remote data streams.
          (initialize-remote-data-streams!)
          ;; Defer route-specific heavyweight work until after the initial shell paint.
          (when (fn? load-post-render-route-effects!)
            (load-post-render-route-effects! store)))]
    ;; Ensure first render is enqueued before expensive subscriptions/fetch startup work.
    (if (fn? schedule-post-render-startup!)
      (schedule-post-render-startup! post-render-startup!)
      (post-render-startup!))))

(defn init!
  [{:keys [log-fn] :as deps}]
  (log-fn "Initializing Hyperopen...")
  (reset-startup-state! deps)
  (restore-persisted-ui-state! deps)
  (initialize-systems! deps))
