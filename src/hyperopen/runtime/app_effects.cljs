(ns hyperopen.runtime.app-effects
  (:require [hyperopen.platform :as platform]))

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
    (platform/local-storage-set! key (str value))
    (catch :default e
      (js/console.warn "Failed to persist localStorage value:" key e))))

(defn local-storage-set-json!
  [key value]
  (try
    (platform/local-storage-set! key (js/JSON.stringify (clj->js value)))
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
           coin
           interval
           bars
           log-fn
           request-candle-snapshot-fn
           apply-candle-snapshot-success
           apply-candle-snapshot-error]}]
  (let [interval* (or interval :1d)
        bars* (or bars 330)
        requested-coin (some-> coin str .trim)
        active-asset (:active-asset @store)
        target-coin (if (seq requested-coin)
                      requested-coin
                      active-asset)]
    (log-fn "Fetching candle snapshot..."
            (clj->js {:coin target-coin
                      :interval interval*
                      :bars bars*}))
    (if-not target-coin
      (js/Promise.resolve nil)
      (-> (request-candle-snapshot-fn target-coin :interval interval* :bars bars*)
          (.then (fn [rows]
                   (swap! store apply-candle-snapshot-success target-coin interval* rows)
                   rows))
          (.catch (fn [err]
                    (swap! store apply-candle-snapshot-error target-coin interval* err)
                    (js/Promise.reject err)))))))

(defn init-websocket!
  [{:keys [ws-url
           log-fn
           init-connection!]}]
  (log-fn "Initializing WebSocket connection...")
  (init-connection! ws-url))

(defn reconnect-websocket!
  [{:keys [log-fn force-reconnect!]}]
  (log-fn "Forcing WebSocket reconnect...")
  (force-reconnect!))
