(ns hyperopen.websocket.orderbook
  (:require [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.trading.order-form-context-sync :as order-form-context-sync]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]
            [hyperopen.websocket.orderbook-l2 :as l2]
            [hyperopen.websocket.orderbook-policy :as policy]))

(defn- send-subscribe! [subscription]
  (ws-client/send-message! {:method "subscribe"
                            :subscription subscription}))

(defn- send-unsubscribe! [subscription]
  (ws-client/send-message! {:method "unsubscribe"
                            :subscription subscription}))

(defonce orderbook-state (atom {:subscriptions {}
                                :l2-subscriptions {}
                                :l2-sides {}
                                :books {}}))

(defn- desired-l2-subscription
  "The fast `l2` subscription for this coin, or nil when it cannot serve the request.

   `l2` ignores `nSigFigs`, so the coarse precision modes stay on `l2Book`. It also
   needs raw-DEFLATE decoding, so a runtime without it stays on `l2Book` too. In both
   cases the `l2Book` subscription below is the working fallback -- nothing else has
   to detect the absence."
  [symbol aggregation-config]
  (when (and (l2/eligible? aggregation-config)
             (platform/inflate-raw-base64-supported?))
    (l2/build-subscription symbol)))

(defn- sync-l2-subscription! [symbol desired-l2]
  (let [current-l2 (get-in @orderbook-state [:l2-subscriptions symbol])]
    (when (not= current-l2 desired-l2)
      (when current-l2
        (send-unsubscribe! current-l2))
      (when desired-l2
        (send-subscribe! desired-l2))
      (swap! orderbook-state
             (fn [state]
               (-> state
                   (update :l2-sides dissoc symbol)
                   (update :l2-subscriptions
                           (fn [subscriptions]
                             (if desired-l2
                               (assoc subscriptions symbol desired-l2)
                               (dissoc subscriptions symbol)))))))
      (telemetry/log! "Fast l2 order book subscription for:" symbol desired-l2))))

(defn subscribe-orderbook!
  ([symbol] (subscribe-orderbook! symbol nil))
  ([symbol aggregation-config]
   (when symbol
     (let [desired-subscription (policy/build-subscription symbol aggregation-config)
           current-subscription (get-in @orderbook-state [:subscriptions symbol])]
       (if (= current-subscription desired-subscription)
         (telemetry/log! "Order book subscription unchanged for:" symbol desired-subscription)
         (do
           (when current-subscription
             (send-unsubscribe! current-subscription))
           (send-subscribe! desired-subscription)
           (swap! orderbook-state assoc-in [:subscriptions symbol] desired-subscription)
           (telemetry/log! "Subscribed to order book for:" symbol desired-subscription)))
       (sync-l2-subscription! symbol (desired-l2-subscription symbol aggregation-config))))))

(defn unsubscribe-orderbook! [symbol]
  (let [subscription (or (get-in @orderbook-state [:subscriptions symbol])
                         (policy/build-subscription symbol nil))
        l2-subscription (get-in @orderbook-state [:l2-subscriptions symbol])]
    (when symbol
      (send-unsubscribe! subscription)
      (when l2-subscription
        (send-unsubscribe! l2-subscription))
      (telemetry/log! "Unsubscribed from order book for:" symbol))
    (swap! orderbook-state
           (fn [state]
             (-> state
                 (update :subscriptions dissoc symbol)
                 (update :l2-subscriptions dissoc symbol)
                 (update :l2-sides dissoc symbol)
                 (update :books dissoc symbol))))))

(defn- publish-book!
  "Build the book from canonically ordered sides and hand it to the store."
  [store coin bids asks timestamp]
  (let [previous-book (get-in @orderbook-state [:books coin])
        next-book (policy/annotate-level-changes
                   previous-book
                   (assoc (policy/build-book bids asks)
                          :timestamp timestamp))
        render-changed? (not (policy/same-render-book? previous-book next-book))]
    (swap! orderbook-state assoc-in [:books coin] next-book)
    (when (and store render-changed?)
      (market-projection-runtime/queue-market-projection!
       {:store store
        :coalesce-key [:orderbook coin]
        :apply-update-fn (fn [state]
                           (let [next-state (assoc-in state [:orderbooks coin] next-book)]
                             (if (= coin (:active-asset next-state))
                               (order-form-context-sync/reconcile-active-order-form next-state)
                               next-state)))}))))

(defn- stale-snapshot?
  "Whether an incoming snapshot would drag the stored book backwards in time.

   Once `l2` deltas are flowing the stored book is usually newer than the 5s `l2Book`
   snapshot, and replaying the older one would make the ladder stutter every 5s."
  [coin incoming-time]
  (let [stored-time (get-in @orderbook-state [:books coin :timestamp])]
    (and (number? stored-time)
         (number? incoming-time)
         (< incoming-time stored-time))))

(defn create-orderbook-data-handler [store]
  (fn [data]
    (when (and (map? data) (= (:channel data) "l2Book"))
      (let [book-data (:data data)
            coin (:coin book-data)
            levels (:levels book-data)]
        (when (and coin levels (>= (count levels) 2))
          (when-not (stale-snapshot? coin (:time book-data))
            (publish-book! store coin (first levels) (second levels) (:time book-data))))))))

(defonce ^:private l2-decode-chain (atom nil))

(defn reset-l2-decode-chain! []
  (reset! l2-decode-chain nil))

(defn- apply-l2-delta! [store delta]
  (let [coin (:coin delta)
        sides (get-in @orderbook-state [:l2-sides coin])]
    (when (and coin sides)
      (let [next-sides (l2/apply-delta sides delta)]
        (swap! orderbook-state assoc-in [:l2-sides coin] next-sides)
        (publish-book! store coin (first next-sides) (second next-sides) (:time delta))))))

(defn- queue-l2-delta! [store inflate-fn blob]
  (swap! l2-decode-chain
         (fn [chain]
           (-> (or chain (js/Promise.resolve nil))
               (.then (fn [_] (inflate-fn blob)))
               (.then (fn [text]
                        (when-let [delta (l2/parse-delta text)]
                          (apply-l2-delta! store delta))
                        nil))
               (.catch (fn [error]
                         (telemetry/log! "Failed to apply l2 order book delta:" error)
                         nil))))))

(defn- apply-l2-snapshot! [store snapshot]
  (let [coin (:coin snapshot)
        levels (:levels snapshot)
        [bids asks] (l2/snapshot-sides snapshot)]
    (when (and coin levels (>= (count levels) 2))
      (swap! orderbook-state assoc-in [:l2-sides coin] [bids asks])
      (when-not (stale-snapshot? coin (:time snapshot))
        (publish-book! store coin bids asks (:time snapshot))))))

(defn create-l2-data-handler
  "Handler for the fast incremental `l2` channel.

   `inflate-fn` is injected so the decode boundary stays substitutable; it takes the
   base64 raw-DEFLATE blob and returns a Promise of the decompressed text."
  ([store] (create-l2-data-handler store platform/inflate-raw-base64!))
  ([store inflate-fn]
   (fn [data]
     (when (and (map? data) (= (:channel data) l2/channel))
       (let [payload (:data data)]
         (if-let [snapshot (l2/snapshot-payload payload)]
           (apply-l2-snapshot! store snapshot)
           (when-let [blob (l2/delta-blob payload)]
             (queue-l2-delta! store inflate-fn blob))))))))

(defn get-subscriptions []
  (:subscriptions @orderbook-state))

(defn init! [store]
  (telemetry/log! "Order book subscription module initialized")
  (ws-client/register-handler! "l2Book" (create-orderbook-data-handler store))
  (ws-client/register-handler! l2/channel (create-l2-data-handler store)))
