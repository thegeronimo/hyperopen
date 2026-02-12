(ns hyperopen.asset-selector.icon-status-runtime)

(defn flush-queued-asset-icon-statuses!
  [{:keys [store
           runtime
           pending-statuses
           flush-handle
           apply-asset-icon-status-updates-fn
           save-many!]}]
  (let [status-by-market (if runtime
                           (get-in @runtime [:asset-icons :pending] {})
                           @pending-statuses)]
    (if runtime
      (swap! runtime
             (fn [state]
               (-> state
                   (assoc-in [:asset-icons :pending] {})
                   (assoc-in [:asset-icons :flush-handle] nil))))
      (do
        (reset! pending-statuses {})
        (reset! flush-handle nil)))
    (when (seq status-by-market)
      (let [{:keys [loaded-icons missing-icons changed?]}
            (apply-asset-icon-status-updates-fn @store status-by-market)]
        (when changed?
          (save-many! store
                      [[[:asset-selector :loaded-icons] loaded-icons]
                       [[:asset-selector :missing-icons] missing-icons]]))))))

(defn queue-asset-icon-status!
  [{:keys [store
           payload
           runtime
           pending-statuses
           flush-handle
           schedule-animation-frame!
           flush-queued-asset-icon-statuses!]}]
  (let [{:keys [market-key status]} (or payload {})]
    (when (and (seq market-key)
               (contains? #{:loaded :missing} status))
      (if runtime
        (swap! runtime assoc-in [:asset-icons :pending market-key] status)
        (swap! pending-statuses assoc market-key status))
      (let [current-handle (if runtime
                             (get-in @runtime [:asset-icons :flush-handle])
                             @flush-handle)]
        (when-not current-handle
          (let [next-handle (schedule-animation-frame!
                             #(flush-queued-asset-icon-statuses! store))]
            (if runtime
              (swap! runtime assoc-in [:asset-icons :flush-handle] next-handle)
              (reset! flush-handle next-handle))))))))
