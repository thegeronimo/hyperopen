(ns hyperopen.config)

(goog-define APP_VERSION "0.1.0")

(def config
  {:ws-url "wss://api.hyperliquid.xyz/ws"
   :icon-service-worker-path "/sw.js"
   :app-version APP_VERSION
   :cooldowns {:reconnect-ms 5000
               :reset-subscriptions-ms 5000
               :auto-recover-severe-threshold-ms 30000
               :auto-recover-cooldown-ms 300000}
   :ui {:wallet-copy-feedback-ms 1500
        :order-toast-ms 3500
        :performance-chart-renderer {:portfolio :d3
                                     :vaults :d3}}
   :ws-migration {:order-fill-ws-first? true
                  :startup-bootstrap-ws-first? true
                  :candle-subscriptions? false
                  :auto-fallback-on-health-degrade? true}
   :startup {:deferred-bootstrap-delay-ms 1200
             :stream-backfill-delay-ms 450
             :funding-history-lookback-ms 604800000
             :per-dex-stagger-ms 120
             :startup-summary-delay-ms 5000}
   :diagnostics {:timeline-limit 50}
   :messages {:agent-storage-mode-reset "Trading persistence updated. Enable Trading again."}})
