(ns hyperopen.websocket.orderbook
  (:require [hyperopen.websocket.client :as ws-client]))

;; Order book state
(defonce orderbook-state (atom {:subscriptions #{}
                                :books {}})) ; Map of coin -> {:bids [...] :asks [...]}

;; Subscribe to order book for a symbol
(defn subscribe-orderbook! [symbol]
  (when (ws-client/connected?)
    (let [subscription-msg {:method "subscribe"
                            :subscription {:type "l2Book"
                                           :coin symbol}}]
      (ws-client/send-message! subscription-msg)
      (swap! orderbook-state update :subscriptions conj symbol)
      (println "Subscribed to order book for:" symbol))))

;; Unsubscribe from order book for a symbol
(defn unsubscribe-orderbook! [symbol]
  (when (ws-client/connected?)
    (let [unsubscription-msg {:method "unsubscribe"
                              :subscription {:type "l2Book"
                                             :coin symbol}}]
      (ws-client/send-message! unsubscription-msg)
      (swap! orderbook-state update :subscriptions disj symbol)
      (swap! orderbook-state update :books dissoc symbol)
      (println "Unsubscribed from order book for:" symbol))))

;; Create a handler function that has access to the store
(defn create-orderbook-data-handler [store]
  (fn [data]
    (println "Processing order book data:" data)
    (when (and (map? data) (= (:channel data) "l2Book"))
      (let [book-data (:data data)
            coin (:coin book-data)
            levels (:levels book-data)]
        (when (and coin levels (>= (count levels) 2))
          ;; Hyperliquid l2Book format: levels = [bids_array, asks_array]
          (let [bids (first levels)   ; First array is bids
                asks (second levels)] ; Second array is asks
            ;; Update local state
            (swap! orderbook-state assoc-in [:books coin] 
                   {:bids (vec (sort-by :px > bids))  ; Sort bids highest to lowest
                    :asks (vec (sort-by :px < asks))  ; Sort asks lowest to highest
                    :timestamp (:time book-data)})
            ;; Update app store
            (when store
              (js/setTimeout 
                #(swap! store assoc-in [:orderbooks coin] 
                        {:bids (vec (sort-by :px > bids))
                         :asks (vec (sort-by :px < asks))
                         :timestamp (:time book-data)})
                0))
            (println "Updated order book for" coin 
                     "- Bids:" (count bids) "Asks:" (count asks))))))))

;; Handle incoming order book data (legacy function)
(defn handle-orderbook-data! [data]
  (println "Processing order book data:" data)
  (when (and (map? data) (= (:channel data) "l2Book"))
    (let [book-data (:data data)
          coin (:coin book-data)
          levels (:levels book-data)]
      (when (and coin levels (>= (count levels) 2))
        ;; Hyperliquid l2Book format: levels = [bids_array, asks_array]
        (let [bids (first levels)   ; First array is bids
              asks (second levels)] ; Second array is asks
          (swap! orderbook-state assoc-in [:books coin] 
                 {:bids (vec (sort-by :px > bids))  ; Sort bids highest to lowest
                  :asks (vec (sort-by :px < asks))  ; Sort asks lowest to highest
                  :timestamp (:time book-data)})
          (println "Updated order book for" coin 
                   "- Bids:" (count bids) "Asks:" (count asks)))))))

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