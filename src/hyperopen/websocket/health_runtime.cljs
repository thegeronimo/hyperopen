(ns hyperopen.websocket.health-runtime
  (:require [clojure.string :as str]
            [hyperopen.websocket.health-projection :as health-projection]))

(defn effective-now-ms
  [generated-at-ms]
  (let [generated* (or generated-at-ms 0)
        wall-now-ms (.now js/Date)]
    (if (>= generated* 1000000000000)
      (max generated* wall-now-ms)
      generated*)))

(defn auto-recover-enabled? []
  (let [flag (some-> js/globalThis (aget "ENABLE_WS_AUTO_RECOVER"))]
    (cond
      (true? flag) true
      (false? flag) false
      (string? flag) (= "true" (str/lower-case flag))
      :else false)))

(defn- auto-recover-eligible?
  [state health auto-recover-enabled-fn auto-recover-severe-threshold-ms]
  (health-projection/auto-recover-eligible?
   state
   health
   {:enabled? (boolean (auto-recover-enabled-fn))
    :severe-threshold-ms auto-recover-severe-threshold-ms}))

(defn sync-websocket-health!
  [{:keys [store
           force?
           get-health-snapshot
           websocket-health-fingerprint
           runtime
           projection-state
           sync-stats
           auto-recover-enabled-fn
           auto-recover-severe-threshold-ms
           auto-recover-cooldown-ms
           dispatch!
           append-diagnostics-event!
           queue-microtask-fn]}]
  (let [get-health (or get-health-snapshot (fn [] {}))
        queue-microtask (or queue-microtask-fn js/queueMicrotask)
        auto-recover-enabled-fn* (or auto-recover-enabled-fn auto-recover-enabled?)
        health (get-health)
        generated-at-ms (or (:generated-at-ms health) 0)
        prior-fingerprint (if runtime
                            (get-in @runtime [:websocket-health :fingerprint])
                            (:fingerprint @projection-state))
        fingerprint (websocket-health-fingerprint health)
        state* @store
        should-sync? (or force?
                         (not= fingerprint prior-fingerprint))]
    (when (auto-recover-eligible?
           state*
           health
           auto-recover-enabled-fn*
           auto-recover-severe-threshold-ms)
      (swap! store
             (fn [state]
               (-> state
                   (assoc-in [:websocket-ui :auto-recover-cooldown-until-ms]
                             (+ generated-at-ms auto-recover-cooldown-ms))
                   (update-in [:websocket-ui :auto-recover-count] (fnil inc 0)))))
      (dispatch! store nil [[:actions/ws-diagnostics-reset-market-subscriptions :auto-recover]]))
    (when (and append-diagnostics-event!
               (health-projection/gap-detected-transition? prior-fingerprint fingerprint))
      (append-diagnostics-event! store :gap-detected generated-at-ms))
    (when should-sync?
      (if runtime
        (swap! runtime
               (fn [state]
                 (-> state
                     (assoc-in [:websocket-health :fingerprint] fingerprint)
                     (update-in [:websocket-health :writes] (fnil inc 0)))))
        (do
          (reset! projection-state {:fingerprint fingerprint})
          (swap! sync-stats update :writes (fnil inc 0))))
      (queue-microtask
        #(swap! store assoc-in [:websocket :health] health)))))
