(ns hyperopen.websocket.diagnostics-payload)

(defn copy-status-at-ms
  [health]
  (or (:generated-at-ms health)
      0))

(defn copy-success-status
  [health]
  {:kind :success
   :at-ms (copy-status-at-ms health)
   :message "Copied (redacted)"})

(defn copy-error-status
  [health diagnostics-json]
  {:kind :error
   :at-ms (copy-status-at-ms health)
   :message "Couldn't access clipboard. Copy the redacted JSON below."
   :fallback-json diagnostics-json})

(defn diagnostics-stream-rows
  [health]
  (->> (get health :streams {})
       (sort-by (fn [[sub-key stream]]
                  [(name (or (:group stream) :account))
                   (str (:topic stream))
                   (pr-str sub-key)]))
       (mapv (fn [[sub-key stream]]
               {:sub-key sub-key
                :group (:group stream)
                :topic (:topic stream)
                :status (:status stream)
                :last-payload-at-ms (:last-payload-at-ms stream)
                :stale-threshold-ms (:stale-threshold-ms stream)
                :message-count (:message-count stream)
                :descriptor (:descriptor stream)}))))

(defn app-build-id
  []
  (some-> js/globalThis
          (aget "HYPEROPEN_BUILD_ID")
          str))

(defn diagnostics-copy-payload
  [state health app-version]
  {:app {:version app-version
         :build-id (app-build-id)}
   :generated-at-ms (:generated-at-ms health)
   :transport (:transport health)
   :groups (:groups health)
   :counters {:reconnect-count (or (get-in state [:websocket-ui :reconnect-count]) 0)
              :reset-counts (merge {:market_data 0 :orders_oms 0 :all 0}
                                   (get-in state [:websocket-ui :reset-counts]))
              :auto-recover-count (or (get-in state [:websocket-ui :auto-recover-count]) 0)}
   :timeline (vec (get-in state [:websocket-ui :diagnostics-timeline] []))
   :streams (diagnostics-stream-rows health)})
