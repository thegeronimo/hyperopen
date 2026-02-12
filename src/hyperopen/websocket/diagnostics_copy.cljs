(ns hyperopen.websocket.diagnostics-copy)

(defn- resolve-clipboard
  [clipboard]
  (or clipboard
      (some-> js/globalThis .-navigator .-clipboard)))

(defn- default-stringify
  [payload]
  (.stringify js/JSON (clj->js payload) nil 2))

(defn copy-websocket-diagnostics!
  [{:keys [store
           diagnostics-copy-payload
           sanitize-value
           set-copy-status!
           copy-success-status
           copy-error-status
           log-fn
           clipboard
           stringify-fn]}]
  (let [state @store
        health (get-in state [:websocket :health] {})
        payload (sanitize-value :redact (diagnostics-copy-payload state health))
        diagnostics-json ((or stringify-fn default-stringify) payload)
        clipboard* (resolve-clipboard clipboard)
        write-text-fn (some-> clipboard* .-writeText)]
    (set-copy-status! store nil)
    (if (and clipboard* write-text-fn)
      (try
        (-> (.writeText clipboard* diagnostics-json)
            (.then (fn []
                     (set-copy-status! store (copy-success-status health))))
            (.catch (fn [err]
                      (log-fn "Copy diagnostics failed:" err)
                      (set-copy-status! store (copy-error-status health diagnostics-json)))))
        (catch :default err
          (log-fn "Copy diagnostics failed:" err)
          (set-copy-status! store (copy-error-status health diagnostics-json))))
      (do
        (log-fn "Clipboard API unavailable for websocket diagnostics copy")
        (set-copy-status! store (copy-error-status health diagnostics-json))))))
