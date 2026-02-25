(ns hyperopen.websocket.health-runtime
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.health-projection :as health-projection]))

(defn effective-now-ms
  [generated-at-ms]
  (let [generated* (or generated-at-ms 0)
        wall-now-ms (platform/now-ms)]
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
           projected-fingerprint
           get-health-snapshot
           websocket-health-fingerprint
           projection-state
           auto-recover-enabled-fn
           auto-recover-severe-threshold-ms
           auto-recover-cooldown-ms
           dispatch!
           append-diagnostics-event!
           queue-microtask-fn]}]
  (let [get-health (or get-health-snapshot (fn [] {}))
        queue-microtask (or queue-microtask-fn platform/queue-microtask!)
        auto-recover-enabled-fn* (or auto-recover-enabled-fn auto-recover-enabled?)
        prior-fingerprint (get-in @projection-state [:fingerprint])
        should-check-health? (or force?
                                 (nil? projected-fingerprint)
                                 (not= projected-fingerprint prior-fingerprint))]
    (when should-check-health?
      (let [health (get-health)
            generated-at-ms (or (:generated-at-ms health) 0)
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
          (swap! projection-state
                 (fn [state]
                   (-> (or state {})
                       (assoc :fingerprint fingerprint)
                       (update :writes (fnil inc 0)))))
          (queue-microtask
            #(swap! store assoc-in [:websocket :health] health)))))))
