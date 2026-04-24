(ns hyperopen.runtime.bootstrap
  (:require [clojure.set :as set]
            [hyperopen.platform :as platform]))

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
           document?
           request-animation-frame!
           emit-fn
           now-ms-fn]
    :or {now-ms-fn platform/now-ms}}]
  (set-dispatch! #(dispatch! store %1 %2))
  (when (if (some? document?)
          document?
          (exists? js/document))
    (let [request-frame! (or request-animation-frame!
                             platform/request-animation-frame!)
          pending-state (atom nil)
          pending-root-keys (atom #{})
          frame-pending? (atom false)
          changed-root-keys (fn [old-state new-state]
                              (if (and (map? old-state)
                                       (map? new-state))
                                (reduce (fn [acc key]
                                          (if (= (get old-state key) (get new-state key))
                                            acc
                                            (conj acc key)))
                                        #{}
                                        (set/union (set (keys old-state))
                                                   (set (keys new-state))))
                                #{}))
          emit-render-flush! (fn [changed-root-keys* render-duration-ms]
                               (when (fn? emit-fn)
                                 (emit-fn :ui/app-render-flush
                                          {:changed-root-keys changed-root-keys*
                                           :changed-root-key-count (count changed-root-keys*)
                                           :render-duration-ms render-duration-ms})))
          schedule-frame! (fn schedule-frame! []
                            (when-not @frame-pending?
                              (reset! frame-pending? true)
                              (request-frame!
                               (fn [_]
                                 (let [state-to-render @pending-state
                                       changed-root-keys* (vec (sort-by str @pending-root-keys))]
                                   (reset! pending-state nil)
                                   (reset! pending-root-keys #{})
                                   (try
                                     (when (some? state-to-render)
                                       (let [render-start-ms (now-ms-fn)]
                                         (render! state-to-render)
                                         (let [render-end-ms (now-ms-fn)]
                                           (emit-render-flush!
                                            changed-root-keys*
                                            (max 0 (- render-end-ms render-start-ms))))))
                                     (finally
                                       (reset! frame-pending? false)
                                       (when (some? @pending-state)
                                         (schedule-frame!)))))))))]
      (remove-watch store render-watch-key)
      (add-watch store
                 render-watch-key
                 (fn [_ _ old-state new-state]
                   (when (not= old-state new-state)
                     (reset! pending-state new-state)
                     (swap! pending-root-keys into (changed-root-keys old-state new-state))
                     (schedule-frame!)))))))

(defn install-runtime-watchers!
  [{:keys [store
           install-store-cache-watchers!
           store-cache-watchers-deps
           install-agent-safety-watch!
           agent-safety-watch-deps
           install-websocket-watchers!
           websocket-watchers-deps]}]
  (install-store-cache-watchers!
   store
   store-cache-watchers-deps)
  (when (fn? install-agent-safety-watch!)
    (install-agent-safety-watch! agent-safety-watch-deps))
  (install-websocket-watchers!
   websocket-watchers-deps))

(defn install-state-validation!
  [{:keys [store
           install-store-state-validation!]}]
  (when (fn? install-store-state-validation!)
    (install-store-state-validation! store)))

(defn bootstrap-runtime!
  [{:keys [register-runtime-deps
           render-loop-deps
           watchers-deps
           validation-deps]}]
  (register-runtime! register-runtime-deps)
  (install-render-loop! render-loop-deps)
  (install-runtime-watchers! watchers-deps)
  (install-state-validation! validation-deps))
