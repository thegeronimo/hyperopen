(ns hyperopen.websocket.webdata2
  (:require [hyperopen.websocket.client :as ws-client]))

;; WebData2 state
(defonce webdata2-state (atom {:subscriptions #{}
                               :data {}})) ; Map of address -> webdata2 data

;; Subscribe to WebData2 for an address
(defn subscribe-webdata2! [address]
  (println "Attempting to subscribe to WebData2 for address:" address)
  (println "WebSocket connected?" (ws-client/connected?))
  (when (ws-client/connected?)
    (let [subscription-msg {:method "subscribe"
                            :subscription {:type "webData2"
                                           :user address}}]
      (println "Sending subscription message:" subscription-msg)
      (ws-client/send-message! subscription-msg)
      (swap! webdata2-state update :subscriptions conj address)
      (println "Subscribed to WebData2 for address:" address))
    (println "WebSocket not connected, cannot subscribe")))

;; Unsubscribe from WebData2 for an address
(defn unsubscribe-webdata2! [address]
  (when (ws-client/connected?)
    (let [unsubscription-msg {:method "unsubscribe"
                              :subscription {:type "webData2"
                                             :user address}}]
      (ws-client/send-message! unsubscription-msg)
      (swap! webdata2-state update :subscriptions disj address)
      (swap! webdata2-state update :data dissoc address)
      (println "Unsubscribed from WebData2 for address:" address))))

;; Create a handler function that has access to the store
(defn create-webdata2-handler [store]
  (fn [data]
    (when (map? data)
      (when (= (:channel data) "webData2")
        (let [webdata2-data (:data data)]
          ;; Update local state
          (swap! webdata2-state assoc :data webdata2-data)
          ;; Update app store
          (when store
            (js/setTimeout 
             #(swap! store assoc :webdata2 webdata2-data)
             0)))))))

;; Get current subscriptions
(defn get-subscriptions []
  (:subscriptions @webdata2-state))

;; Get WebData2 data
(defn get-webdata2 []
  (:data @webdata2-state))

;; Get all WebData2 (alias for get-webdata2)
(defn get-all-webdata2 []
  (get-webdata2))

;; Clear WebData2 data
(defn clear-webdata2! []
  (swap! webdata2-state assoc :data nil))

;; Clear all WebData2 data (alias for clear-webdata2!)
(defn clear-all-webdata2! []
  (clear-webdata2!))

;; Initialize WebData2 module
(defn init! [store]
  (println "WebData2 subscription module initialized")
  (println "Registering WebData2 handler with store:" store)
  ;; Register handler for webData2 channel with store access
  (ws-client/register-handler! "webData2" (create-webdata2-handler store))
  (println "WebData2 handler registered successfully")) 