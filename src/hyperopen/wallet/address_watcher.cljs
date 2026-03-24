(ns hyperopen.wallet.address-watcher
  "Watches wallet address changes and manages associated subscriptions.
   
   This module follows SOLID principles:
   - Single Responsibility: Only handles address change reactions
   - Open/Closed: Extensible via subscription handlers
   - Liskov Substitution: All handlers follow the same protocol
   - Interface Segregation: Minimal, focused handler interface
   - Dependency Inversion: Depends on abstractions, not concrete implementations"
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.telemetry :as telemetry]))

;; ---------- Address Change Handler Protocol ----------------------------------

(defprotocol IAddressChangeHandler
  "Protocol for handling wallet address changes"
  (on-address-changed [this old-address new-address]
    "Called when wallet address changes. Both addresses can be nil.")
  (get-handler-name [this]
    "Returns a unique name for this handler for logging/debugging"))

(defprotocol IWatchedValueHandler
  "Optional protocol for handlers that need to react to a state-derived value
   other than the plain effective account address."
  (watched-value [this state]
    "Returns the comparable watched value for the given state. State can be nil."))

;; ---------- WebData2 Subscription Handler -----------------------------------

(defrecord WebData2Handler [unsubscribe-fn subscribe-fn]
  IAddressChangeHandler
  (on-address-changed [_ old-address new-address]
    (when old-address
      (telemetry/log! (str "Unsubscribing WebData2 from old address: " old-address))
      (unsubscribe-fn old-address))
    
    (when new-address
      (telemetry/log! (str "Subscribing WebData2 to new address: " new-address))
      (subscribe-fn new-address)))
  
  (get-handler-name [_]
    "webdata2-subscription-handler"))

;; ---------- Address Watcher State and Management -------------------------

(defonce ^:private address-watcher-state
  (atom {:handlers []
         :current-address nil
         :watching? false
         :pending-subscription nil
         :ws-connected? false}))

(defn- default-watch-value
  [state]
  (account-context/effective-account-address state))

(defn- handler-watch-value
  [handler state]
  (if (satisfies? IWatchedValueHandler handler)
    (watched-value handler state)
    (default-watch-value state)))

(defn- handler-change-events
  [handlers old-state new-state]
  (reduce (fn [acc handler]
            (let [old-value (handler-watch-value handler old-state)
                  new-value (handler-watch-value handler new-state)]
              (if (not= old-value new-value)
                (conj acc {:handler handler
                           :old-value old-value
                           :new-value new-value})
                acc)))
          []
          handlers))

(defn add-handler!
  "Add a handler for address changes. Handler must implement IAddressChangeHandler"
  [handler]
  {:pre [(satisfies? IAddressChangeHandler handler)]}
  (swap! address-watcher-state update :handlers conj handler)
  (telemetry/log! (str "Added address change handler: " (get-handler-name handler))))

(defn remove-handler!
  "Remove a handler by name"
  [handler-name]
  (swap! address-watcher-state 
         update :handlers 
         (fn [handlers]
           (remove #(= (get-handler-name %) handler-name) handlers)))
  (telemetry/log! (str "Removed address change handler: " handler-name)))

(defn- notify-handlers!
  "Notify all registered handlers of address change"
  [old-state new-state]
  (let [{:keys [handlers ws-connected?]} @address-watcher-state
        events (handler-change-events handlers old-state new-state)
        old-address (default-watch-value old-state)
        new-address (default-watch-value new-state)]
    (when (seq events)
      (if ws-connected?
        ;; WebSocket is connected, notify handlers immediately
        (doseq [{:keys [handler old-value new-value]} events]
          (try
            (on-address-changed handler old-value new-value)
            (catch js/Error e
              (telemetry/log! (str "Error in address change handler "
                                   (get-handler-name handler) ": "
                                   (.-message e))))))
        ;; WebSocket not connected, store the latest observed state transition
        (do
          (telemetry/log! "WebSocket not connected, storing pending subscription for address:" new-address)
          (swap! address-watcher-state assoc :pending-subscription {:old-state old-state
                                                                     :new-state new-state
                                                                     :old-address old-address
                                                                     :new-address new-address}))))))

(defn- address-change-listener
  "Watch function that detects address changes"
  [_ _ old-state new-state]
  (let [old-address (default-watch-value old-state)
        new-address (default-watch-value new-state)
        handlers (get @address-watcher-state :handlers)
        events (handler-change-events handlers old-state new-state)]
    
    ;; Only process if a handler-observed value actually changed and we're watching
    (when (and (get @address-watcher-state :watching?)
               (seq events))
      (when (not= old-address new-address)
        (telemetry/log! (str "Wallet address changed: " old-address " -> " new-address)))

      ;; Update our tracked address
      (swap! address-watcher-state assoc :current-address new-address)
      
      ;; Notify all handlers
      (notify-handlers! old-state new-state))))

(defn- process-pending-subscription!
  "Process any pending subscription when WebSocket connects"
  []
  (when-let [pending (get @address-watcher-state :pending-subscription)]
    (let [{:keys [old-state new-state new-address]} pending
          handlers (get @address-watcher-state :handlers)
          events (handler-change-events handlers old-state new-state)]
      (telemetry/log! "Processing pending subscription for address:" new-address)
      (doseq [{:keys [handler old-value new-value]} events]
        (try
          (on-address-changed handler old-value new-value)
          (catch js/Error e
            (telemetry/log! (str "Error in pending subscription handler "
                                 (get-handler-name handler) ": "
                                 (.-message e))))))
      ;; Clear pending subscription
      (swap! address-watcher-state assoc :pending-subscription nil))))

(defn on-websocket-connected!
  "Called when WebSocket connection is established"
  []
  (telemetry/log! "Address watcher: WebSocket connected")
  (swap! address-watcher-state assoc :ws-connected? true)
  (process-pending-subscription!))

(defn on-websocket-disconnected!
  "Called when WebSocket connection is lost"
  []
  (telemetry/log! "Address watcher: WebSocket disconnected")
  (swap! address-watcher-state assoc :ws-connected? false))

;; ---------- Public API ---------------------------------------------------

(defn start-watching!
  "Start watching for wallet address changes on the given store atom"
  [store]
  (when-not (get @address-watcher-state :watching?)
    (telemetry/log! "Starting wallet address watcher...")
    (add-watch store ::address-watcher address-change-listener)
    (swap! address-watcher-state assoc :watching? true)
    
    ;; Initialize current effective account address
    (let [current-address (default-watch-value @store)]
      (swap! address-watcher-state assoc :current-address current-address)
      (telemetry/log! (str "Initial effective account address: " current-address)))))

(defn stop-watching!
  "Stop watching for wallet address changes"
  [store]
  (when (get @address-watcher-state :watching?)
    (telemetry/log! "Stopping wallet address watcher...")
    (remove-watch store ::address-watcher)
    (swap! address-watcher-state assoc :watching? false)))

(defn sync-current-address!
  "Process the current wallet address after handlers are registered.
   This ensures an already-connected wallet is bootstrapped even without
   a fresh `accountsChanged` event."
  [store]
  (let [current-address (default-watch-value @store)]
    (swap! address-watcher-state assoc :current-address current-address)
    (notify-handlers! nil @store)))

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
    (telemetry/log! "Address watcher initialized with WebData2 handler")))
