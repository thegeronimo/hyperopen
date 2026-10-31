(ns hyperopen.config)

(goog-define APP_VERSION "0.1.0")

;; Vol Range preview capability (HO-SPEC-014 1.2, 15.1, 15.2): the Volatility
;; range ticket, chart overlays and review. On by default, release builds
;; included.
(goog-define VOL_RANGE_PREVIEW true)

;; Vol Range order placement (HO-SPEC-014 15.2, Release A). On by default,
;; release builds included; the test builds set it false in shadow-cljs.edn. A
;; browser can opt out (see hyperopen.vol-range.infrastructure.execution-gate).
(goog-define VOL_RANGE_EXECUTION true)

;; Vol Range reporting (HO-SPEC-014 15.2, Release B): the Portfolio Vol Range
;; tab, ledger backfill and Start tracking. On by default, release builds
;; included. Reporting never implies placement permission.
(goog-define VOL_RANGE_REPORTING true)

(def config
  {:ws-url "wss://api.hyperliquid.xyz/ws"
   :icon-service-worker-path "/sw.js"
   :app-version APP_VERSION
   :cooldowns {:reconnect-ms 5000
               :reset-subscriptions-ms 5000
               :auto-recover-severe-threshold-ms 30000
               :auto-recover-cooldown-ms 300000}
   :ui {:wallet-copy-feedback-ms 1500
        :order-toast-ms 3500}
   :trading {:agent-expires-after-ms 15000
             :agent-schedule-cancel-ahead-ms 60000
             :agent-schedule-cancel-refresh-ms 30000}
   :ws-migration {:order-fill-ws-first? true
                  :startup-bootstrap-ws-first? true
                  :candle-subscriptions? false
                  :auto-fallback-on-health-degrade? true}
   :startup {:deferred-bootstrap-delay-ms 1200
             :stream-backfill-delay-ms 450
             :funding-history-lookback-ms 604800000
             :per-dex-stagger-ms 120
             :startup-summary-delay-ms 5000}
   :optimizer-history-api {:enabled? true
                           :base-url "https://price-history.hyperopen.xyz"
                           :proxy-policy :approved-proxy-allowed
                           :include-aligned-returns? true
                           :fallback-to-legacy? true
                           :legacy-fallback-request-spacing-ms 200
                           ;; Bounds a hung request so the optimizer's Loading
                           ;; status always has a terminal state. Far above the
                           ;; largest measured bundle (6.2s at 51 assets), so it
                           ;; never fires on a merely slow response.
                           :request-timeout-ms 60000}
   :diagnostics {:timeline-limit 50}
   :vol-range {:preview-enabled? VOL_RANGE_PREVIEW
               :execution-built? VOL_RANGE_EXECUTION
               :reporting-enabled? VOL_RANGE_REPORTING}
   :messages {:agent-storage-mode-reset "Trading persistence updated. Enable Trading again."
              :agent-protection-mode-reset "Trading session protection updated. Enable Trading again."}})

(defn vol-range-execution-built?
  "True when this build includes Vol Range placement (still needs arming)."
  []
  (boolean (get-in config [:vol-range :execution-built?])))

(defn vol-range-preview-enabled?
  "True when the Vol Range A0 preview capability is on for this build."
  []
  (boolean (get-in config [:vol-range :preview-enabled?])))

(defn vol-range-reporting-enabled?
  "True when this build includes Vol Range reporting (spec 15.2)."
  []
  (boolean (get-in config [:vol-range :reporting-enabled?])))
