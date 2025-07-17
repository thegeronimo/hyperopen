(ns hyperopen.websocket.active-asset-ctx
  (:require [hyperopen.websocket.client :as ws-client]))

;; Active asset context state
(defonce active-asset-ctx-state (atom {:subscriptions #{}
                                       :contexts {}})) ; Map of coin -> WsActiveAssetCtx or WsActiveSpotAssetCtx

;; Subscribe to active asset context for a coin
(defn subscribe-active-asset-ctx! [coin]
  (when (ws-client/connected?)
    (let [subscription-msg {:method "subscribe"
                            :subscription {:type "activeAssetCtx"
                                           :coin coin}}]
      (ws-client/send-message! subscription-msg)
      (swap! active-asset-ctx-state update :subscriptions conj coin)
      (println "Subscribed to active asset context for:" coin))))

;; Unsubscribe from active asset context for a coin
(defn unsubscribe-active-asset-ctx! [coin]
  (when (ws-client/connected?)
    (let [unsubscription-msg {:type "unsubscribe"
                              :subscription {:type "activeAssetCtx"
                                             :coin coin}}]
      (ws-client/send-message! unsubscription-msg)
      (swap! active-asset-ctx-state update :subscriptions disj coin)
      (swap! active-asset-ctx-state update :contexts dissoc coin)
      (println "Unsubscribed from active asset context for:" coin))))

;; Handle incoming active asset context data
(defn handle-active-asset-ctx-data! [data]
  (println "Processing active asset context data:" data)
  (when (and (map? data) (= (:channel data) "activeAssetCtx"))
    (let [ctx-data (:data data)
          coin (:coin ctx-data)]
      (when coin
        (swap! active-asset-ctx-state assoc-in [:contexts coin] ctx-data)
        (println "Updated active asset context for" coin ":" ctx-data)))))

;; Get current subscriptions
(defn get-subscriptions []
  (:subscriptions @active-asset-ctx-state))

;; Get active asset context for a specific coin
(defn get-active-asset-ctx [coin]
  (get-in @active-asset-ctx-state [:contexts coin]))

;; Get all active asset contexts
(defn get-all-active-asset-ctxs []
  (:contexts @active-asset-ctx-state))

;; Check if a coin has active asset context
(defn has-active-asset-ctx? [coin]
  (contains? (:contexts @active-asset-ctx-state) coin))

;; Clear active asset context for a specific coin
(defn clear-active-asset-ctx! [coin]
  (swap! active-asset-ctx-state update :contexts dissoc coin))

;; Clear all active asset contexts
(defn clear-all-active-asset-ctxs! []
  (swap! active-asset-ctx-state assoc :contexts {}))

;; Get list of all subscribed coins
(defn get-subscribed-coins []
  (vec (:subscriptions @active-asset-ctx-state)))

;; Initialize active asset context module
(defn init! []
  (println "Active asset context subscription module initialized")
  ;; Register handler for activeAssetCtx channel
  (ws-client/register-handler! "activeAssetCtx" handle-active-asset-ctx-data!)) 