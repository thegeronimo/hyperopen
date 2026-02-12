(ns hyperopen.websocket.orderbook
  (:require [hyperopen.platform :as platform]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.orderbook-policy :as policy]))

(defn- send-subscribe! [subscription]
  (ws-client/send-message! {:method "subscribe"
                            :subscription subscription}))

(defn- send-unsubscribe! [subscription]
  (ws-client/send-message! {:method "unsubscribe"
                            :subscription subscription}))

;; Order book state
(defonce orderbook-state (atom {:subscriptions {}
                                :books {}})) ; coin -> subscription map, and coin -> book data

;; Subscribe to order book for a symbol with optional aggregation config
(defn subscribe-orderbook!
  ([symbol] (subscribe-orderbook! symbol nil))
  ([symbol aggregation-config]
   (when symbol
     (let [desired-subscription (policy/build-subscription symbol aggregation-config)
           current-subscription (get-in @orderbook-state [:subscriptions symbol])]
       (if (= current-subscription desired-subscription)
         (println "Order book subscription unchanged for:" symbol desired-subscription)
         (do
           (when current-subscription
             (send-unsubscribe! current-subscription))
           (send-subscribe! desired-subscription)
           (swap! orderbook-state assoc-in [:subscriptions symbol] desired-subscription)
           (println "Subscribed to order book for:" symbol desired-subscription)))))))

;; Unsubscribe from order book for a symbol
(defn unsubscribe-orderbook! [symbol]
  (let [subscription (or (get-in @orderbook-state [:subscriptions symbol])
                         (policy/build-subscription symbol nil))]
    (when symbol
      (send-unsubscribe! subscription)
      (println "Unsubscribed from order book for:" symbol))
    (swap! orderbook-state update :subscriptions dissoc symbol)
    (swap! orderbook-state update :books dissoc symbol)))

;; Create a handler function that has access to the store
(defn create-orderbook-data-handler [store]
  (fn [data]
    (when (and (map? data) (= (:channel data) "l2Book"))
      (let [book-data (:data data)
            coin (:coin book-data)
            levels (:levels book-data)]
        (when (and coin levels (>= (count levels) 2))
          (let [bids (first levels)
                asks (second levels)
                next-book {:bids (policy/sort-bids bids)
                           :asks (policy/sort-asks asks)
                           :timestamp (:time book-data)}]
            ;; Update local state
            (swap! orderbook-state assoc-in [:books coin] next-book)
            ;; Update app store
            (when store
              (platform/set-timeout!
               #(swap! store assoc-in [:orderbooks coin] next-book)
               0))))))))

;; Get current subscriptions
(defn get-subscriptions []
  (:subscriptions @orderbook-state))

;; Get order book for a specific symbol
(defn get-orderbook [symbol]
  (get-in @orderbook-state [:books symbol]))

;; Get all order books
(defn get-all-orderbooks []
  (:books @orderbook-state))

;; Get best bid and ask for a symbol
(defn get-best-bid-ask [symbol]
  (when-let [book (get-orderbook symbol)]
    {:best-bid (first (:bids book))
     :best-ask (first (:asks book))}))

;; Clear order book data for a specific symbol
(defn clear-orderbook! [symbol]
  (swap! orderbook-state update :books dissoc symbol))

;; Clear all order book data
(defn clear-all-orderbooks! []
  (swap! orderbook-state assoc :books {}))

;; Initialize order book module
(defn init! [store]
  (println "Order book subscription module initialized")
  ;; Register handler for l2Book channel with store access
  (ws-client/register-handler! "l2Book" (create-orderbook-data-handler store)))
