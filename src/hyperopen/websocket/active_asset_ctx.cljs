(ns hyperopen.websocket.active-asset-ctx
  (:require [clojure.set :as set]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.asset-selector.market-live-projection :as market-live-projection]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]))

(def ^:private default-owner
  :active-asset)

(def ^:private default-active-asset-ctx-state
  {:subscriptions #{}
   :owners-by-coin {}
   :coins-by-owner {}
   :contexts {}})

;; Active asset context state
(defonce active-asset-ctx-state
  (atom default-active-asset-ctx-state)) ; Map of coin -> WsActiveAssetCtx or WsActiveSpotAssetCtx

(defn- normalize-owner [owner]
  (if (keyword? owner)
    owner
    default-owner))

(defn- normalized-state
  [state]
  (let [state* (merge default-active-asset-ctx-state (or state {}))]
    (assoc state*
           :subscriptions (set (or (:subscriptions state*) #{}))
           :owners-by-coin (or (:owners-by-coin state*) {})
           :coins-by-owner (or (:coins-by-owner state*) {})
           :contexts (or (:contexts state*) {}))))

(defn- parse-number [value]
  (cond
    (number? value) (when-not (js/isNaN value) value)
    (string? value) (let [num (js/parseFloat value)]
                      (when-not (js/isNaN num) num))
    :else nil))

(defn- active-asset-ctx-channel?
  [channel]
  (contains? #{"activeAssetCtx" "activeSpotAssetCtx"} channel))

;; Create a handler function that has access to the store
(defn create-active-asset-data-handler [store]
  (fn [data]
    (when (and (map? data) (active-asset-ctx-channel? (:channel data)))
      (let [data-payload (:data data)
            coin (:coin data-payload)
            ctx (:ctx data-payload)]
        (when (and coin ctx)
          ;; Transform the data to match our expected format
          (let [mark-raw (:markPx ctx)
                oracle-raw (:oraclePx ctx)
                prev-day-raw (:prevDayPx ctx)
                mark (parse-number mark-raw)
                oracle (parse-number oracle-raw)
                prev-day (parse-number prev-day-raw)
                funding (parse-number (:funding ctx))
                change (when (and (number? mark) (number? prev-day))
                         (- mark prev-day))
                change-pct (when (and (number? change)
                                      (number? prev-day)
                                      (not= prev-day 0))
                             (* 100 (/ change prev-day)))
                formatted-data {:coin coin
                                :mark mark
                                :markRaw mark-raw
                                :oracle oracle
                                :oracleRaw oracle-raw
                                :prevDayRaw prev-day-raw
                                :change24h change
                                :change24hPct change-pct
                                :volume24h (parse-number (:dayNtlVlm ctx))
                                :openInterest (or (parse-number (:openInterest ctx))
                                                  (parse-number (:circulatingSupply ctx)))
                                :fundingRate (when (number? funding)
                                               (* 100 funding))}]
            ;; Coalesce market projections so one frame emits one store write.
            (when store
              (market-projection-runtime/queue-market-projection!
               {:store store
                :coalesce-key [:active-asset-ctx coin]
                :apply-update-fn
                (fn [state]
                  (-> state
                      (assoc-in [:active-assets :contexts coin] formatted-data)
                      (assoc-in [:active-assets :loading] false)
                      (market-live-projection/apply-active-asset-ctx-update coin ctx)))}))))))))

;; Subscribe to active asset context for a coin
(defn subscribe-active-asset-ctx!
  ([coin]
   (subscribe-active-asset-ctx! coin default-owner))
  ([coin owner]
   (when coin
     (let [owner* (normalize-owner owner)
           should-subscribe? (volatile! false)]
       (swap! active-asset-ctx-state
              (fn [state]
                (let [{:keys [subscriptions owners-by-coin coins-by-owner] :as state*} (normalized-state state)
                      owners (get owners-by-coin coin #{})
                      already-owned? (contains? owners owner*)
                      first-owner? (empty? owners)
                      next-owners (conj owners owner*)
                      owner-coins (get coins-by-owner owner* #{})
                      next-owner-coins (conj owner-coins coin)]
                  (when (and first-owner?
                             (not already-owned?))
                    (vreset! should-subscribe? true))
                  (-> state*
                      (assoc :subscriptions (conj subscriptions coin))
                      (assoc-in [:owners-by-coin coin] next-owners)
                      (assoc-in [:coins-by-owner owner*] next-owner-coins)))))
       (when @should-subscribe?
         (ws-client/send-message! {:method "subscribe"
                                   :subscription {:type "activeAssetCtx"
                                                  :coin coin}}))
       (telemetry/log! "Subscribed to active asset context for:" coin "owner:" owner*)))))

;; Unsubscribe from active asset context for a coin
(defn unsubscribe-active-asset-ctx!
  ([coin]
   (unsubscribe-active-asset-ctx! coin default-owner))
  ([coin owner]
   (when coin
     (let [owner* (normalize-owner owner)
           should-unsubscribe? (volatile! false)]
       (swap! active-asset-ctx-state
              (fn [state]
                (let [{:keys [subscriptions owners-by-coin coins-by-owner contexts] :as state*} (normalized-state state)
                      owners (get owners-by-coin coin #{})
                      owner-present? (contains? owners owner*)
                      next-owners (disj owners owner*)
                      owner-coins (get coins-by-owner owner* #{})
                      next-owner-coins (disj owner-coins coin)]
                  (when (and owner-present?
                             (empty? next-owners))
                    (vreset! should-unsubscribe? true))
                  (cond-> state*
                    true
                    (assoc :coins-by-owner
                           (if (seq next-owner-coins)
                             (assoc coins-by-owner owner* next-owner-coins)
                             (dissoc coins-by-owner owner*)))

                    (seq next-owners)
                    (assoc-in [:owners-by-coin coin] next-owners)

                    (empty? next-owners)
                    (-> (assoc :subscriptions (disj subscriptions coin))
                        (assoc :owners-by-coin (dissoc owners-by-coin coin))
                        (assoc :contexts (dissoc contexts coin)))))))
       (when @should-unsubscribe?
         (ws-client/send-message! {:method "unsubscribe"
                                   :subscription {:type "activeAssetCtx"
                                                  :coin coin}}))
       (telemetry/log! "Unsubscribed from active asset context for:" coin "owner:" owner*)))))

;; Handle incoming active asset context data
(defn handle-active-asset-ctx-data! [data]
  (telemetry/log! "Processing active asset context data:" data)
  (when (and (map? data) (active-asset-ctx-channel? (:channel data)))
    (let [ctx-data (:data data)
          coin (:coin ctx-data)]
      (when coin
        (swap! active-asset-ctx-state assoc-in [:contexts coin] ctx-data)
        (telemetry/log! "Updated active asset context for" coin ":" ctx-data)))))

;; Get current subscriptions
(defn get-subscriptions []
  (:subscriptions (normalized-state @active-asset-ctx-state)))

;; Get subscribed coins for an owner
(defn get-subscribed-coins-by-owner [owner]
  (get-in (normalized-state @active-asset-ctx-state)
          [:coins-by-owner (normalize-owner owner)]
          #{}))

(defn sync-owner-subscriptions!
  [owner desired-coins]
  (let [owner* (normalize-owner owner)
        desired-coins* (set (remove nil? desired-coins))
        owned-coins (get-subscribed-coins-by-owner owner*)
        subscribe-coins (sort (set/difference desired-coins* owned-coins))
        unsubscribe-coins (sort (set/difference owned-coins desired-coins*))]
    (doseq [coin subscribe-coins]
      (subscribe-active-asset-ctx! coin owner*))
    (doseq [coin unsubscribe-coins]
      (unsubscribe-active-asset-ctx! coin owner*))))

;; Get active asset context for a specific coin
(defn get-active-asset-ctx [coin]
  (get-in @active-asset-ctx-state [:contexts coin]))

;; Get all active asset contexts
(defn get-all-active-asset-ctxs []
  (:contexts (normalized-state @active-asset-ctx-state)))

;; Check if a coin has active asset context
(defn has-active-asset-ctx? [coin]
  (contains? (:contexts (normalized-state @active-asset-ctx-state)) coin))

;; Clear active asset context for a specific coin
(defn clear-active-asset-ctx! [coin]
  (swap! active-asset-ctx-state update :contexts dissoc coin))

;; Clear all active asset contexts
(defn clear-all-active-asset-ctxs! []
  (swap! active-asset-ctx-state assoc :contexts {}))

;; Get list of all subscribed coins
(defn get-subscribed-coins []
  (vec (:subscriptions (normalized-state @active-asset-ctx-state))))

;; Initialize active asset context module
(defn init! [store]
  (telemetry/log! "Active asset context subscription module initialized")
  ;; Register handler for activeAssetCtx channel with store access
  (let [handler (create-active-asset-data-handler store)]
    (ws-client/register-handler! "activeAssetCtx" handler)
    (ws-client/register-handler! "activeSpotAssetCtx" handler))) 
