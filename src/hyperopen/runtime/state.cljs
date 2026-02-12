(ns hyperopen.runtime.state)

(def diagnostics-timeline-limit
  50)

(def reconnect-cooldown-ms
  5000)

(def reset-subscriptions-cooldown-ms
  5000)

(def auto-recover-severe-threshold-ms
  30000)

(def auto-recover-cooldown-ms
  300000)

(def icon-service-worker-path
  "/sw.js")

(def app-version
  "0.1.0")

(def wallet-copy-feedback-duration-ms
  1500)

(def order-feedback-toast-duration-ms
  3500)

(def agent-storage-mode-reset-message
  "Trading persistence updated. Enable Trading again.")

(def deferred-bootstrap-delay-ms
  1200)

(def per-dex-stagger-ms
  120)

(def startup-summary-delay-ms
  5000)

(defonce websocket-health-projection-state
  (atom {:fingerprint nil}))

(defonce websocket-health-sync-stats
  (atom {:writes 0}))

(defonce wallet-copy-feedback-timeout-id
  (atom nil))

(defonce order-feedback-toast-timeout-id
  (atom nil))

(defonce pending-asset-icon-statuses
  (atom {}))

(defonce asset-icon-status-flush-handle
  (atom nil))
