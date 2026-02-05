(ns hyperopen.websocket.active-asset-ctx
  (:require [hyperopen.websocket.client :as ws-client]))

;; Active asset context state
(defonce active-asset-ctx-state (atom {:subscriptions #{}
                                       :contexts {}})) ; Map of coin -> WsActiveAssetCtx or WsActiveSpotAssetCtx

(defn- parse-number [value]
  (cond
    (number? value) (when-not (js/isNaN value) value)
    (string? value) (let [num (js/parseFloat value)]
                      (when-not (js/isNaN num) num))
    :else nil))

;; Create a handler function that has access to the store
(defn create-active-asset-data-handler [store]
  (fn [data]
    ;;(println "Processing active asset context data:" data)
    (when (and (map? data) (= (:channel data) "activeAssetCtx"))
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
                                :openInterest (parse-number (:openInterest ctx))
                                :fundingRate (when (number? funding)
                                               (* 100 funding))}]
            ;;(println "Formatted data for" coin ":" formatted-data)
            ;; Use setTimeout to avoid nested render issues
            (js/setTimeout 
              #(do
                 (swap! store assoc-in [:active-assets :contexts coin] formatted-data)
                 (swap! store assoc-in [:active-assets :loading] false))
              0)))))))

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
(defn init! [store]
  (println "Active asset context subscription module initialized")
  ;; Register handler for activeAssetCtx channel with store access
  (ws-client/register-handler! "activeAssetCtx" (create-active-asset-data-handler store))) 
