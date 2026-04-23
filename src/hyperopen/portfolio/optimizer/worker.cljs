(ns hyperopen.portfolio.optimizer.worker)

(defn- post-message!
  [id type payload]
  (.postMessage js/self #js {:id id
                             :type type
                             :payload (clj->js payload)}))

(defn- handle-message
  [^js event]
  (let [data (.-data event)
        id (.-id data)
        type (keyword (.-type data))]
    (case type
      :ping
      (post-message! id "optimizer-pong" {:ready? true})

      :run-optimization
      (post-message! id
                     "optimizer-error"
                     {:code :engine-not-implemented
                      :message "Portfolio Optimizer engine is not implemented yet."})

      (post-message! id
                     "optimizer-error"
                     {:code :unknown-message-type
                      :message (str "Unknown optimizer worker message type: " type)}))))

(defn ^:export init
  []
  (js/console.log "Portfolio Optimizer Web Worker initialized.")
  (.addEventListener js/self "message" handle-message))
