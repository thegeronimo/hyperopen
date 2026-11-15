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
           restore-ui-theme-preference!
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
  (restore-ui-font-preference!)
  (when (fn? restore-ui-theme-preference!)
    (restore-ui-theme-preference! store))
  (restore-ui-locale-preference! store)
  (restore-chart-options! store)
  (restore-orderbook-ui! store)
  (restore-vaults-snapshot-range! store)
  (restore-agent-storage-mode! store)
  (when (fn? restore-agent-passkey-capability!)
    (restore-agent-passkey-capability! store))
  (when (fn? restore-trading-settings!)
    (restore-trading-settings! store))
  (restore-spectate-mode-preferences! store)
  (restore-spectate-mode-url! store)
  (restore-trade-route-tab! store)
  (when (fn? restore-route-query-state!)
    (restore-route-query-state! store))
  (restore-active-asset! store)
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
  (restore-asset-selector-sort-settings! store)
  (restore-portfolio-summary-time-range! store)
  (when (fn? restore-route-query-state!)
    (restore-route-query-state! store))
  (when (fn? restore-leaderboard-preferences!)
    (restore-leaderboard-preferences! store))
  (restore-open-orders-sort-settings! store)
  (restore-funding-history-pagination-settings! store)
  (restore-trade-history-pagination-settings! store)
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
  (init-wallet! store)
  (init-router! store)
  (when (fn? restore-vaults-snapshot-range!)
    (restore-vaults-snapshot-range! store))
  (when (fn? restore-route-query-state!)
    (restore-route-query-state! store))
  (kick-render! store)
  (let [post-render-startup!
        (fn []
          (-> (js/Promise.resolve nil)
              (.then (fn [_]
                       (when (fn? install-asset-selector-shortcuts!)
                         (install-asset-selector-shortcuts!))
                       (when (fn? install-position-tpsl-clickaway!)
                         (install-position-tpsl-clickaway!))
                       (when (fn? mark-post-render-trade-secondary-panels-ready!)
                         (mark-post-render-trade-secondary-panels-ready! store))
                       (when (fn? load-post-render-route-effects!)
                         (load-post-render-route-effects! store))
                       (initialize-remote-data-streams!)))
              (.then (fn [_]
                       (if (fn? yield-to-main!)
                         (yield-to-main!)
                         (js/Promise.resolve nil))))
              (.then (fn [_]
                       (when (fn? restore-deferred-ui-state!)
                         (restore-deferred-ui-state!))
                       (register-icon-service-worker!)))))]
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
