(ns hyperopen.startup.watchers
  (:require [hyperopen.platform :as platform]))

(def ^:private active-market-display-watch-key
  ::active-market-display-cache)

(def ^:private asset-selector-markets-watch-key
  ::asset-selector-markets-cache)

(def ^:private websocket-runtime-view-watch-key
  ::ws-runtime-view)

(defn install-store-cache-watchers!
  [store {:keys [persist-active-market-display!
                 persist-asset-selector-markets-cache!]}]
  ;; Keep startup display metadata fresh whenever active-market becomes available.
  (add-watch store active-market-display-watch-key
    (fn [_ _ old-state new-state]
      (let [old-market (:active-market old-state)
            new-market (:active-market new-state)]
        (when (not= old-market new-market)
          (persist-active-market-display! new-market)))))

  ;; Persist non-empty selector market lists so symbols are available on next reload.
  (add-watch store asset-selector-markets-watch-key
    (fn [_ _ old-state new-state]
      (let [old-markets (get-in old-state [:asset-selector :markets] [])
            new-markets (get-in new-state [:asset-selector :markets] [])]
        (when (not= old-markets new-markets)
          (persist-asset-selector-markets-cache! new-markets new-state))))))

(defn- status->diagnostics-event
  [status]
  (case status
    :connected :connected
    :reconnecting :reconnecting
    :disconnected :offline
    nil))

(defn- projected-health-fingerprint
  [runtime-view]
  (get-in runtime-view [:stream :health-fingerprint]))

(defn install-websocket-watchers!
  [{:keys [store
           runtime-view
           append-diagnostics-event!
           sync-websocket-health!
           on-websocket-connected!
           on-websocket-disconnected!]}]
  ;; Watch websocket runtime view projection changes and keep store/health sync updated.
  (remove-watch runtime-view websocket-runtime-view-watch-key)
  (add-watch runtime-view websocket-runtime-view-watch-key
    (fn [_ _ old-view new-view]
      (let [old-connection (get old-view :connection {})
            new-connection (get new-view :connection {})
            old-status (:status old-connection)
            new-status (:status new-connection)
            status-transition? (not= old-status new-status)
            old-fingerprint (projected-health-fingerprint old-view)
            new-fingerprint (projected-health-fingerprint new-view)
            transition-event (status->diagnostics-event new-status)
            transition-at-ms (or (:now-ms new-connection) (platform/now-ms))]
        (when status-transition?
          ;; Defer store update to next tick to avoid nested renders.
          (platform/queue-microtask!
           #(do
              (when (= :reconnecting new-status)
                (swap! store update-in [:websocket-ui :reconnect-count] (fnil inc 0)))
              (when transition-event
                (append-diagnostics-event! store transition-event transition-at-ms)))))
        (when status-transition?
          (sync-websocket-health! store :force? true))
        ;; Notify address watcher only on status transitions.
        (when status-transition?
          (if (= new-status :connected)
            (on-websocket-connected!)
            (on-websocket-disconnected!)))
        (when (not= old-fingerprint new-fingerprint)
          (sync-websocket-health! store
                                  :projected-fingerprint new-fingerprint))))))
