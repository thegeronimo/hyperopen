(ns hyperopen.websocket.webdata2
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.utils.data-normalization :as data-normalization]))

;; WebData2 state
(defonce webdata2-state (atom {:subscriptions #{}
                               :data {}})) ; Map of address -> webdata2 data

;; Subscribe to WebData2 for an address
(defn subscribe-webdata2! [address]
  (when address
    (let [subscription-msg {:method "subscribe"
                            :subscription {:type "webData2"
                                           :user address}}]
      (swap! webdata2-state update :subscriptions conj address)
      (ws-client/send-message! subscription-msg)
      (telemetry/log! "Subscribed to WebData2 for address:" address))))

;; Unsubscribe from WebData2 for an address
(defn unsubscribe-webdata2! [address]
  (when address
    (let [unsubscription-msg {:method "unsubscribe"
                              :subscription {:type "webData2"
                                             :user address}}]
      (swap! webdata2-state update :subscriptions disj address)
      (swap! webdata2-state update :data dissoc address)
      (ws-client/send-message! unsubscription-msg)
      (telemetry/log! "Unsubscribed from WebData2 for address:" address))))

(defn- meta-index-signature
  [meta]
  (let [universe (vec (or (:universe meta) []))
        margin-tables (vec (or (:marginTables meta) []))]
    {:universe-count (count universe)
     :margin-table-count (count margin-tables)
     :meta-hash (hash [universe margin-tables])}))

(defn- normalized-address
  [value]
  (account-context/normalize-address value))

(defn- active-effective-address
  [store]
  (some-> (account-context/effective-account-address @store)
          normalized-address))

(defn- message-address
  [msg]
  (or (normalized-address (:user msg))
      (normalized-address (:address msg))
      (normalized-address (:walletAddress msg))
      (normalized-address (get-in msg [:data :user]))
      (normalized-address (get-in msg [:data :address]))
      (normalized-address (get-in msg [:data :walletAddress]))
      (normalized-address (get-in msg [:data :wallet]))))

(defn- message-for-active-address?
  [store msg]
  (let [msg-address (message-address msg)
        active-address (active-effective-address store)]
    (if msg-address
      (and active-address
           (= msg-address active-address))
      true)))

;; Create a handler function that has access to the store
(defn create-webdata2-handler [store]
  (let [meta-index-cache (atom nil)]
    (fn [data]
      (when (and (map? data)
                 (= "webData2" (:channel data))
                 (message-for-active-address? store data))
        (let [webdata2-data (:data data)]
          (if (and (map? webdata2-data)
                   (:meta webdata2-data)
                   (:assetCtxs webdata2-data))
            (let [meta (:meta webdata2-data)
                  asset-ctxs (:assetCtxs webdata2-data)
                  signature (meta-index-signature meta)
                  cached @meta-index-cache
                  rebuild-index? (or (nil? cached)
                                     (not= signature (:signature cached)))
                  meta-index (if rebuild-index?
                               (let [next-index (data-normalization/build-asset-context-meta-index meta)]
                                 (reset! meta-index-cache {:signature signature
                                                           :meta-index next-index})
                                 next-index)
                               (:meta-index cached))]
              (swap! store
                     (fn [state]
                       (let [seed-contexts (if rebuild-index? {} (:asset-contexts state))
                             next-contexts (data-normalization/patch-asset-contexts
                                            seed-contexts
                                            meta-index
                                            asset-ctxs)]
                         (-> state
                             (assoc :webdata2 webdata2-data)
                             (assoc :asset-contexts next-contexts))))))
            (swap! store assoc :webdata2 webdata2-data)))))))

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
  (telemetry/log! "WebData2 subscription module initialized")
  (telemetry/log! "Registering WebData2 handler with store:" store)
  ;; Register handler for webData2 channel with store access
  (ws-client/register-handler! "webData2" (create-webdata2-handler store))
  (telemetry/log! "WebData2 handler registered successfully"))
