(ns hyperopen.wallet.address-watcher
  "Watches wallet address changes and manages associated subscriptions.
   
   This module follows SOLID principles:
   - Single Responsibility: Only handles address change reactions
   - Open/Closed: Extensible via subscription handlers
   - Liskov Substitution: All handlers follow the same protocol
   - Interface Segregation: Minimal, focused handler interface
   - Dependency Inversion: Depends on abstractions, not concrete implementations")

;; ---------- Address Change Handler Protocol ----------------------------------

(defprotocol IAddressChangeHandler
  "Protocol for handling wallet address changes"
  (on-address-changed [this old-address new-address]
    "Called when wallet address changes. Both addresses can be nil.")
  (get-handler-name [this]
    "Returns a unique name for this handler for logging/debugging"))

;; ---------- WebData2 Subscription Handler -----------------------------------

(defrecord WebData2Handler [unsubscribe-fn subscribe-fn]
  IAddressChangeHandler
  (on-address-changed [_ old-address new-address]
    (when old-address
      (println (str "Unsubscribing WebData2 from old address: " old-address))
      (unsubscribe-fn old-address))
    
    (when new-address
      (println (str "Subscribing WebData2 to new address: " new-address))
      (subscribe-fn new-address)))
  
  (get-handler-name [_]
    "webdata2-subscription-handler"))

;; ---------- Address Watcher State and Management -------------------------

(defonce ^:private address-watcher-state
  (atom {:handlers []
         :current-address nil
         :watching? false}))

(defn add-handler!
  "Add a handler for address changes. Handler must implement IAddressChangeHandler"
  [handler]
  {:pre [(satisfies? IAddressChangeHandler handler)]}
  (swap! address-watcher-state update :handlers conj handler)
  (println (str "Added address change handler: " (get-handler-name handler))))

(defn remove-handler!
  "Remove a handler by name"
  [handler-name]
  (swap! address-watcher-state 
         update :handlers 
         (fn [handlers]
           (remove #(= (get-handler-name %) handler-name) handlers)))
  (println (str "Removed address change handler: " handler-name)))

(defn- notify-handlers!
  "Notify all registered handlers of address change"
  [old-address new-address]
  (let [handlers (get @address-watcher-state :handlers)]
    (doseq [handler handlers]
      (try
        (on-address-changed handler old-address new-address)
        (catch js/Error e
          (println (str "Error in address change handler " 
                       (get-handler-name handler) ": " 
                       (.-message e))))))))

(defn- address-change-listener
  "Watch function that detects address changes"
  [_ _ old-state new-state]
  (let [old-address (get-in old-state [:wallet :address])
        new-address (get-in new-state [:wallet :address])]
    
    ;; Only process if address actually changed and we're watching
    (when (and (get @address-watcher-state :watching?)
               (not= old-address new-address))
      (println (str "Wallet address changed: " old-address " -> " new-address))
      
      ;; Update our tracked address
      (swap! address-watcher-state assoc :current-address new-address)
      
      ;; Notify all handlers
      (notify-handlers! old-address new-address))))

;; ---------- Public API ---------------------------------------------------

(defn start-watching!
  "Start watching for wallet address changes on the given store atom"
  [store]
  (when-not (get @address-watcher-state :watching?)
    (println "Starting wallet address watcher...")
    (add-watch store ::address-watcher address-change-listener)
    (swap! address-watcher-state assoc :watching? true)
    
    ;; Initialize current address
    (let [current-address (get-in @store [:wallet :address])]
      (swap! address-watcher-state assoc :current-address current-address)
      (println (str "Initial wallet address: " current-address)))))

(defn stop-watching!
  "Stop watching for wallet address changes"
  [store]
  (when (get @address-watcher-state :watching?)
    (println "Stopping wallet address watcher...")
    (remove-watch store ::address-watcher)
    (swap! address-watcher-state assoc :watching? false)))

(defn create-webdata2-handler
  "Factory function to create a WebData2 subscription handler"
  [subscribe-fn unsubscribe-fn]
  (->WebData2Handler unsubscribe-fn subscribe-fn))

;; ---------- Initialization Helper ----------------------------------------

(defn init-with-webdata2!
  "Initialize address watcher with WebData2 handler"
  [store subscribe-fn unsubscribe-fn]
  (let [webdata2-handler (create-webdata2-handler subscribe-fn unsubscribe-fn)]
    (add-handler! webdata2-handler)
    (start-watching! store)
    (println "Address watcher initialized with WebData2 handler")))
