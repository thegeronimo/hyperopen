(ns hyperopen.websocket.client)

;; WebSocket connection state
(defonce connection-state (atom {:status :disconnected
                                 :ws nil}))

;; Message handlers registry
(defonce message-handlers (atom {}))

;; Register a message handler
(defn register-handler! [message-type handler-fn]
  (swap! message-handlers assoc message-type handler-fn))

;; Handle incoming WebSocket messages
(defn handle-message! [event]
  (try
    (let [data (js/JSON.parse (.-data event))
          js-data (js->clj data :keywordize-keys true)]
      ;;(println "Received WebSocket message:" js-data)
      ;; Route to registered handlers based on channel
      (when-let [channel (:channel js-data)]
        (when-let [handler (get @message-handlers channel)]
          (handler js-data))))
    (catch js/Error e
      (println "Error parsing WebSocket message:" e))))

;; Initialize WebSocket connection
(defn init-connection! [ws-url]
  (try
    (let [ws (js/WebSocket. ws-url)]
      
      ;; Set up event handlers
      (set! (.-onopen ws)
            (fn [event]
              (swap! connection-state assoc :status :connected :ws ws)
              (println "WebSocket connected to:" ws-url)))
      
      (set! (.-onmessage ws) handle-message!)
      
      (set! (.-onclose ws)
            (fn [event]
              (swap! connection-state assoc :status :disconnected :ws nil)
              (println "WebSocket disconnected. Code:" (.-code event) "Reason:" (.-reason event))))
      
      (set! (.-onerror ws)
            (fn [event]
              (println "WebSocket error:" event)))
      
      (swap! connection-state assoc :status :connecting :ws ws)
      (println "Connecting to WebSocket:" ws-url))
    
    (catch js/Error e
      (println "Failed to create WebSocket connection:" e))))

;; Send message function
(defn send-message! [data]
  (when-let [ws (:ws @connection-state)]
    (when (= (:status @connection-state) :connected)
      (try
        (let [json-data (js/JSON.stringify (clj->js data))]
          (.send ws json-data)
          (println "Sent message:" data))
        (catch js/Error e
          (println "Error sending message:" e))))))

;; Connection status helpers
(defn connected? []
  (= (:status @connection-state) :connected))

(defn get-connection-status []
  (:status @connection-state)) 