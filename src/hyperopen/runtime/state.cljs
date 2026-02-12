(ns hyperopen.runtime.state
  (:require [hyperopen.startup.runtime :as startup-runtime-lib]))

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

(def websocket-url
  "wss://api.hyperliquid.xyz/ws")

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

(defn default-runtime-state
  []
  {:websocket-health {:fingerprint nil
                      :writes 0}
   :timeouts {:wallet-copy nil
              :order-toast nil}
   :asset-icons {:pending {}
                 :flush-handle nil}
   :startup (startup-runtime-lib/default-startup-runtime-state)
   :runtime-bootstrapped? false})

(defn make-runtime-state
  []
  (atom (default-runtime-state)))

(defonce runtime
  (make-runtime-state))

(defn runtime-bootstrapped?
  [runtime-state]
  (true? (:runtime-bootstrapped? @runtime-state)))

(defn mark-runtime-bootstrapped!
  [runtime-state]
  (let [marked? (atom false)]
    (swap! runtime-state
           (fn [state]
             (if (:runtime-bootstrapped? state)
               state
               (do
                 (reset! marked? true)
                 (assoc state :runtime-bootstrapped? true)))))
    @marked?))

(defn reset-runtime-state!
  [runtime-state]
  (reset! runtime-state (default-runtime-state)))
