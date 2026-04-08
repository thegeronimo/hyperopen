(ns hyperopen.runtime.state
  (:require [hyperopen.config :as app-config]
            [hyperopen.startup.runtime :as startup-runtime-lib]))

(def config
  app-config/config)

(def diagnostics-timeline-limit
  (get-in config [:diagnostics :timeline-limit]))

(def reconnect-cooldown-ms
  (get-in config [:cooldowns :reconnect-ms]))

(def reset-subscriptions-cooldown-ms
  (get-in config [:cooldowns :reset-subscriptions-ms]))

(def auto-recover-severe-threshold-ms
  (get-in config [:cooldowns :auto-recover-severe-threshold-ms]))

(def auto-recover-cooldown-ms
  (get-in config [:cooldowns :auto-recover-cooldown-ms]))

(def icon-service-worker-path
  (:icon-service-worker-path config))

(def app-version
  (:app-version config))

(def websocket-url
  (:ws-url config))

(def wallet-copy-feedback-duration-ms
  (get-in config [:ui :wallet-copy-feedback-ms]))

(def order-feedback-toast-duration-ms
  (get-in config [:ui :order-toast-ms]))

(def agent-storage-mode-reset-message
  (get-in config [:messages :agent-storage-mode-reset]))

(def agent-protection-mode-reset-message
  (get-in config [:messages :agent-protection-mode-reset]))

(def agent-expires-after-ms
  (get-in config [:trading :agent-expires-after-ms]))

(def agent-schedule-cancel-ahead-ms
  (get-in config [:trading :agent-schedule-cancel-ahead-ms]))

(def agent-schedule-cancel-refresh-ms
  (get-in config [:trading :agent-schedule-cancel-refresh-ms]))

(def deferred-bootstrap-delay-ms
  (get-in config [:startup :deferred-bootstrap-delay-ms]))

(def per-dex-stagger-ms
  (get-in config [:startup :per-dex-stagger-ms]))

(def startup-stream-backfill-delay-ms
  (get-in config [:startup :stream-backfill-delay-ms]))

(def startup-funding-history-lookback-ms
  (get-in config [:startup :funding-history-lookback-ms]))

(def startup-summary-delay-ms
  (get-in config [:startup :startup-summary-delay-ms]))

(defn default-runtime-state
  []
  {:timeouts {:wallet-copy nil
              :order-toast {}
              :agent-schedule-cancel-refresh nil
              :user-account-surface-refresh nil}
   :asset-icons {:pending {}
                 :flush-handle nil}
   :startup (startup-runtime-lib/default-startup-runtime-state)
   :app-started? false
   :runtime-bootstrapped? false})

(defn make-runtime-state
  []
   (atom (default-runtime-state)))

(defonce runtime
  (make-runtime-state))

(defn runtime-bootstrapped?
  [runtime-state]
  (true? (:runtime-bootstrapped? @runtime-state)))

(defn app-started?
  [runtime-state]
  (true? (:app-started? @runtime-state)))

(defn mark-app-started!
  [runtime-state]
  (let [marked? (atom false)]
    (swap! runtime-state
           (fn [state]
             (if (:app-started? state)
               state
               (do
                 (reset! marked? true)
                 (assoc state :app-started? true)))))
    @marked?))

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
