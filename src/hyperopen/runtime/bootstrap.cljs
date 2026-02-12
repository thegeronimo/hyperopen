(ns hyperopen.runtime.bootstrap)

(defn register-runtime!
  [{:keys [register-effects!
           effect-handlers
           register-actions!
           action-handlers
           register-system-state!
           register-placeholders!]}]
  (register-effects! effect-handlers)
  (register-actions! action-handlers)
  (register-system-state!)
  (register-placeholders!))

(defn install-render-loop!
  [{:keys [store
           render-watch-key
           set-dispatch!
           dispatch!
           render!
           document?]}]
  (set-dispatch! #(dispatch! store %1 %2))
  (when (if (some? document?)
          document?
          (exists? js/document))
    (remove-watch store render-watch-key)
    (add-watch store
               render-watch-key
               (fn [_ _ _ new-state]
                 (render! new-state)))))

(defn install-runtime-watchers!
  [{:keys [store
           install-store-cache-watchers!
           store-cache-watchers-deps
           install-websocket-watchers!
           websocket-watchers-deps]}]
  (install-store-cache-watchers!
   store
   store-cache-watchers-deps)
  (install-websocket-watchers!
   websocket-watchers-deps))

(defn bootstrap-runtime!
  [{:keys [register-runtime-deps
           render-loop-deps
           watchers-deps]}]
  (register-runtime! register-runtime-deps)
  (install-render-loop! render-loop-deps)
  (install-runtime-watchers! watchers-deps))
