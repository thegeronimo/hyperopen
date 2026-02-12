(ns hyperopen.asset-selector.icon-status-runtime)

(defn flush-queued-asset-icon-statuses!
  [{:keys [store
           pending-statuses
           flush-handle
           apply-asset-icon-status-updates-fn
           save-many!]}]
  (let [status-by-market @pending-statuses]
    (reset! pending-statuses {})
    (reset! flush-handle nil)
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
           pending-statuses
           flush-handle
           schedule-animation-frame!
           flush-queued-asset-icon-statuses!]}]
  (let [{:keys [market-key status]} (or payload {})]
    (when (and (seq market-key)
               (contains? #{:loaded :missing} status))
      (swap! pending-statuses assoc market-key status)
      (when-not @flush-handle
        (reset! flush-handle
                (schedule-animation-frame!
                 #(flush-queued-asset-icon-statuses! store)))))))
