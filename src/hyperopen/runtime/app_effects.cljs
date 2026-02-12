(ns hyperopen.runtime.app-effects)

(defn save!
  [store path value]
  (swap! store assoc-in path value))

(defn save-many!
  [store path-values]
  (swap! store
         (fn [state]
           (reduce (fn [acc [path value]]
                     (assoc-in acc path value))
                   state
                   path-values))))

(defn local-storage-set!
  [key value]
  (try
    (when (exists? js/localStorage)
      (js/localStorage.setItem key (str value)))
    (catch :default e
      (js/console.warn "Failed to persist localStorage value:" key e))))

(defn local-storage-set-json!
  [key value]
  (try
    (when (exists? js/localStorage)
      (js/localStorage.setItem key (js/JSON.stringify (clj->js value))))
    (catch :default e
      (js/console.warn "Failed to persist localStorage JSON value:" key e))))

(defn push-state!
  [path]
  (.pushState js/history nil "" path))

(defn replace-state!
  [path]
  (.replaceState js/history nil "" path))

(defn fetch-candle-snapshot!
  [{:keys [store
           interval
           bars
           log-fn
           fetch-candle-snapshot-fn]}]
  (let [interval* (or interval :1d)
        bars* (or bars 330)]
    (log-fn "Fetching candle snapshot for active asset...")
    (fetch-candle-snapshot-fn store :interval interval* :bars bars*)))

(defn init-websocket!
  [{:keys [store
           ws-url
           log-fn
           init-connection!]}]
  (log-fn "Initializing WebSocket connection...")
  (init-connection! ws-url)
  (swap! store assoc-in [:websocket :status] :connecting))

(defn reconnect-websocket!
  [{:keys [log-fn force-reconnect!]}]
  (log-fn "Forcing WebSocket reconnect...")
  (force-reconnect!))
