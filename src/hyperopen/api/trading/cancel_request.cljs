(ns hyperopen.api.trading.cancel-request
  (:require [clojure.string :as str]
            [hyperopen.api.trading.http :as http]
            [hyperopen.asset-selector.markets :as markets]))

(defn- named-dex-market?
  [market]
  (seq (http/normalize-display-text (:dex market))))

(defn- market-asset-id
  [market]
  (let [market* (or market {})
        explicit-asset-id (some http/parse-int-value
                                [(:asset-id market*)
                                 (:assetId market*)])
        idx (some http/parse-int-value [(:idx market*)])
        named-dex? (named-dex-market? market*)]
    (or explicit-asset-id
        (when (and (some? idx)
                   (not named-dex?))
          idx))))

(defn- normalize-cancel-order-coin
  [order]
  (let [coin (some-> (or (:coin order)
                         (get-in order [:order :coin]))
                     str
                     str/trim)]
    (when (seq coin) coin)))

(defn resolve-cancel-order-oid
  [order]
  (some http/parse-int-value
       [(:oid order)
        (:o order)
        (get-in order [:order :oid])
        (get-in order [:order :o])]))

(defn- normalize-cancel-order-dex
  [order]
  (http/normalize-display-text
   (or (:dex order)
       (get-in order [:order :dex]))))

(defn- namespaced-coin?
  [coin]
  (let [coin* (http/normalize-display-text coin)]
    (boolean
     (and coin*
          (str/includes? coin* ":")))))

(defn- namespaced-cancel-order-coin
  [coin dex]
  (let [coin* (http/normalize-display-text coin)
        dex* (http/normalize-display-text dex)]
    (when (and coin*
               dex*
               (not (namespaced-coin? coin*)))
      (str dex* ":" coin*))))

(defn- namespace-prefix
  [coin]
  (let [coin* (http/normalize-display-text coin)]
    (when (and coin*
               (str/includes? coin* ":"))
      (http/normalize-display-text (first (str/split coin* #":" 2))))))

(defn- normalize-lookup-token
  [value]
  (some-> value http/normalize-display-text str/lower-case))

(defn- market-dex-token
  [market]
  (or (normalize-lookup-token (:dex market))
      (normalize-lookup-token (namespace-prefix (:coin market)))))

(defn- resolve-cancel-order-market-by-dex
  [market-by-key coin dex]
  (let [dex-token (normalize-lookup-token dex)]
    (when (and (map? market-by-key)
               dex-token)
      (some (fn [market]
              (when (and (= dex-token (market-dex-token market))
                         (markets/market-matches-coin? market coin))
                market))
            (vals market-by-key)))))

(defn- resolve-cancel-order-market
  [market-by-key coin dex]
  (let [direct-market (markets/resolve-market-by-coin market-by-key coin)]
    (if (and (seq (http/normalize-display-text dex))
             (not (namespaced-coin? coin)))
      (or (some->> (namespaced-cancel-order-coin coin dex)
                   (markets/resolve-market-by-coin market-by-key))
          (resolve-cancel-order-market-by-dex market-by-key coin dex))
      direct-market)))

(defn- resolve-cancel-order-asset-idx
  [state order coin]
  (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
        market (resolve-cancel-order-market market-by-key
                                            coin
                                            (normalize-cancel-order-dex order))
        resolved-market-asset-id (market-asset-id market)
        named-dex-cancel? (or (seq (normalize-cancel-order-dex order))
                              (named-dex-market? market))
        context-asset-idx (when (and coin (not named-dex-cancel?))
                            (some http/parse-int-value
                                  [(get-in state [:asset-contexts (keyword coin) :idx])
                                   (get-in state [:asset-contexts coin :idx])]))]
    (some http/parse-int-value
          [(:asset-id order)
           (:assetId order)
           (:asset-idx order)
           (:assetIdx order)
           (:asset order)
           (:a order)
           (get-in order [:order :asset-id])
           (get-in order [:order :assetId])
           (get-in order [:order :asset-idx])
           (get-in order [:order :assetIdx])
           (get-in order [:order :asset])
           (get-in order [:order :a])
           resolved-market-asset-id
           context-asset-idx])))

(defn build-cancel-order-request
  "Normalize heterogeneous order row payloads into exchange cancel action shape.
   Returns nil when required fields are missing."
  [state order]
  (let [coin (normalize-cancel-order-coin order)
        oid (resolve-cancel-order-oid order)
        asset-idx (resolve-cancel-order-asset-idx state order coin)]
    (when (and (some? asset-idx) (some? oid))
      {:action {:type "cancel"
                :cancels [{:a asset-idx :o oid}]}})))

(defn build-cancel-orders-request
  "Normalize a sequence of heterogeneous order row payloads into one batched
   exchange cancel action. Returns nil when any order is missing required
   fields, because visible-scope cancel-all must not silently skip rows."
  [state orders]
  (let [requests (mapv #(build-cancel-order-request state %) (or orders []))]
    (when (and (seq requests)
               (every? map? requests))
      (let [cancels (->> requests
                         (mapcat #(get-in % [:action :cancels]))
                         distinct
                         vec)]
        (when (seq cancels)
          {:action {:type "cancel"
                    :cancels cancels}})))))

(defn build-cancel-twap-request
  "Normalize a TWAP row payload into exchange twapCancel action shape.
   Returns nil when the required twap id or asset index is missing."
  [state twap]
  (let [coin (normalize-cancel-order-coin twap)
        twap-id (some http/parse-int-value
                      [(:twap-id twap)
                       (:twapId twap)
                       (:t twap)
                       (get-in twap [:state :twapId])])
        asset-idx (resolve-cancel-order-asset-idx state twap coin)]
    (when (and (some? twap-id)
               (some? asset-idx))
      {:action {:type "twapCancel"
                :a asset-idx
                :t twap-id}})))
