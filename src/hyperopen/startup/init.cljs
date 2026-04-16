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

(defn restore-critical-ui-state!
  [{:keys [store
           restore-ui-font-preference!
           restore-ui-locale-preference!
           restore-chart-options!
           restore-orderbook-ui!
           restore-vaults-snapshot-range!
           restore-agent-storage-mode!
           restore-agent-passkey-capability!
           restore-trading-settings!
           restore-spectate-mode-preferences!
           restore-spectate-mode-url!
           restore-trade-route-tab!
           restore-route-query-state!
           restore-active-asset!
           restore-asset-selector-markets-cache!]}]
  ;; Restore root typography preference (system default, optional Inter override).
  (restore-ui-font-preference!)
  ;; Restore UI locale preference for number/date formatting ownership.
  (restore-ui-locale-preference! store)
  ;; Restore chart options from localStorage.
  (restore-chart-options! store)
  ;; Restore orderbook UI options from localStorage.
  (restore-orderbook-ui! store)
  ;; Restore vault snapshot range selector from localStorage.
  (restore-vaults-snapshot-range! store)
  ;; Restore agent storage preference from localStorage.
  (restore-agent-storage-mode! store)
  ;; Restore current browser support for passkey-locked trading.
  (when (fn? restore-agent-passkey-capability!)
    (restore-agent-passkey-capability! store))
  ;; Restore bounded trading settings from localStorage.
  (when (fn? restore-trading-settings!)
    (restore-trading-settings! store))
  ;; Restore Spectate Mode watchlist and last-used modal search preference.
  (restore-spectate-mode-preferences! store)
  ;; Restore Spectate Mode directly from the current URL query when present.
  (restore-spectate-mode-url! store)
  ;; Restore trade account-info tab directly from the current URL query when present.
  (restore-trade-route-tab! store)
  ;; Restore portfolio/vault shareable view params after localStorage-backed preferences.
  (when (fn? restore-route-query-state!)
    (restore-route-query-state! store))
  ;; Restore selected asset from localStorage (default to BTC).
  (restore-active-asset! store)
  ;; Restore cached selector market symbols for immediate dropdown population.
  (restore-asset-selector-markets-cache! store))

(defn restore-deferred-ui-state!
  [{:keys [store
           restore-asset-selector-sort-settings!
           restore-portfolio-summary-time-range!
           restore-route-query-state!
           restore-leaderboard-preferences!
           restore-open-orders-sort-settings!
           restore-funding-history-pagination-settings!
           restore-trade-history-pagination-settings!
           restore-order-history-pagination-settings!]}]
  ;; Restore asset selector sort settings from localStorage.
  (restore-asset-selector-sort-settings! store)
  ;; Restore portfolio summary range selector from localStorage.
  (restore-portfolio-summary-time-range! store)
  ;; Re-apply route query state so shareable links override deferred localStorage restore.
  (when (fn? restore-route-query-state!)
    (restore-route-query-state! store))
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
           restore-route-query-state!
           install-asset-selector-shortcuts!
           install-position-tpsl-clickaway!
           register-icon-service-worker!
           mark-post-render-trade-secondary-panels-ready!
           initialize-remote-data-streams!
           load-post-render-route-effects!
           restore-deferred-ui-state!
           yield-to-main!
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
  ;; Re-apply route query state so shareable links override the post-router vault restore.
  (when (fn? restore-route-query-state!)
    (restore-route-query-state! store))
  ;; Trigger initial render by updating the store.
  (kick-render! store)
  (let [post-render-startup!
        (fn []
          (-> (js/Promise.resolve nil)
              (.then (fn [_]
                       ;; Install global keyboard shortcuts that should work regardless of focus target.
                       (when (fn? install-asset-selector-shortcuts!)
                         (install-asset-selector-shortcuts!))
                       ;; Install click-away behavior for positioned overlays (TP/SL, Reduce, Margin, Spectate Mode).
                       (when (fn? install-position-tpsl-clickaway!)
                         (install-position-tpsl-clickaway!))
                       ;; Reveal lower desktop trade surfaces after the first paint has landed.
                       (when (fn? mark-post-render-trade-secondary-panels-ready!)
                         (mark-post-render-trade-secondary-panels-ready! store))
                       ;; Defer route-specific heavyweight work until after the initial shell paint.
                       (when (fn? load-post-render-route-effects!)
                         (load-post-render-route-effects! store))
                       ;; Initialize remote data streams.
                       (initialize-remote-data-streams!)))
              (.then (fn [_]
                       (if (fn? yield-to-main!)
                         (yield-to-main!)
                         (js/Promise.resolve nil))))
              (.then (fn [_]
                       ;; Restore clearly non-visible UI state after first paint has settled.
                       (when (fn? restore-deferred-ui-state!)
                         (restore-deferred-ui-state!))
                       ;; Register icon cache service worker for cross-reload symbol icon caching.
                       (register-icon-service-worker!)))))]
    ;; Ensure first render is enqueued before expensive subscriptions/fetch startup work.
    (if (fn? schedule-post-render-startup!)
      (schedule-post-render-startup! post-render-startup!)
      (post-render-startup!))))

(defn init!
  [{:keys [log-fn] :as deps}]
  (log-fn "Initializing Hyperopen...")
  (reset-startup-state! deps)
  (restore-critical-ui-state! deps)
  (initialize-systems!
   (assoc deps
          :restore-deferred-ui-state!
          (fn []
            (restore-deferred-ui-state! deps)))))
