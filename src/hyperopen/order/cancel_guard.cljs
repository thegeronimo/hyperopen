(ns hyperopen.order.cancel-guard
  (:require [clojure.string :as str]
            [hyperopen.api.trading :as trading-api]))

(def ^:private rich-guard-path
  [:orders :recently-canceled-order-keys])

(defn normalize-oid
  [value]
  (cond
    (map? value)
    (trading-api/resolve-cancel-order-oid value)

    (some? value)
    (trading-api/resolve-cancel-order-oid {:oid value})

    :else
    nil))

(defn oid-set
  [oids]
  (->> (cond
         (nil? oids) []
         (set? oids) oids
         (sequential? oids) oids
         :else [oids])
       (keep normalize-oid)
       set))

(defn- normalize-asset-id
  [value]
  (trading-api/resolve-cancel-order-oid {:oid value}))

(defn- normalize-dex
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- order-value
  [m k]
  (or (get m k)
      (get-in m [:order k])))

(defn- guard-entry
  [value]
  (when-let [oid (normalize-oid value)]
    (let [asset-id (when (map? value)
                     (some normalize-asset-id
                           [(order-value value :asset-id)
                            (order-value value :assetId)
                            (order-value value :asset-idx)
                            (order-value value :assetIdx)
                            (order-value value :asset)
                            (order-value value :a)]))
          dex (when (map? value)
                (some normalize-dex
                      [(order-value value :dex)]))]
      (cond-> {:oid oid}
        (some? asset-id) (assoc :asset-id asset-id)
        (some? dex) (assoc :dex dex)))))

(defn guard-entry-set
  [entries]
  (->> (cond
         (nil? entries) []
         (set? entries) entries
         (sequential? entries) entries
         :else [entries])
       (keep guard-entry)
       set))

(defn cancel-request-oids
  [request]
  (oid-set (get-in request [:action :cancels] [])))

(defn cancel-request-guard-entries
  [request]
  (guard-entry-set (get-in request [:action :cancels] [])))

(defn state-guard-entries
  [state]
  (let [rich-entries (guard-entry-set (get-in state rich-guard-path))
        rich-oids (set (keep :oid rich-entries))
        summary-entries (->> (guard-entry-set (get-in state [:orders :recently-canceled-oids]))
                             (remove #(contains? rich-oids (:oid %)))
                             set)]
    (into rich-entries summary-entries)))

(defn record-canceled-oids
  [state oids]
  (let [entries (guard-entry-set oids)
        oids* (set (keep :oid entries))]
    (if (seq oids*)
      (-> state
          (update-in [:orders :recently-canceled-oids]
                     (fn [existing]
                       (into (oid-set existing) oids*)))
          (update-in rich-guard-path
                     (fn [existing]
                       (into (guard-entry-set existing) entries))))
      state)))

(defn- matching-identity?
  [guard-entry order-entry k]
  (let [guard-value (get guard-entry k)
        order-value (get order-entry k)]
    (or (nil? guard-value)
        (nil? order-value)
        (= guard-value order-value))))

(defn- guard-entry-matches?
  [guard-entry order-entry]
  (and (= (:oid guard-entry) (:oid order-entry))
       (matching-identity? guard-entry order-entry :asset-id)
       (matching-identity? guard-entry order-entry :dex)))

(defn- guarded-open-order?
  [guarded-entries order]
  (when-let [order-entry (guard-entry order)]
    (some #(guard-entry-matches? % order-entry) guarded-entries)))

(defn- prune-open-order-rows
  [rows guarded-entries]
  (->> (or rows [])
       (remove #(guarded-open-order? guarded-entries %))
       vec))

(defn prune-open-order-payload
  [payload guarded-entries]
  (let [guarded-entries* (guard-entry-set guarded-entries)]
    (cond
      (not (seq guarded-entries*))
      payload

      (sequential? payload)
      (prune-open-order-rows payload guarded-entries*)

      (map? payload)
      (cond-> payload
        (sequential? (:orders payload))
        (update :orders prune-open-order-rows guarded-entries*)

        (sequential? (:openOrders payload))
        (update :openOrders prune-open-order-rows guarded-entries*)

        (sequential? (:data payload))
        (update :data prune-open-order-rows guarded-entries*))

      :else
      payload)))

(defn prune-open-order-sources
  [state guarded-entries]
  (let [guarded-entries* (guard-entry-set guarded-entries)]
    (if (seq guarded-entries*)
      (-> state
          (update-in [:orders :open-orders]
                     prune-open-order-payload
                     guarded-entries*)
          (update-in [:orders :open-orders-snapshot]
                     prune-open-order-payload
                     guarded-entries*)
          (update-in [:orders :open-orders-snapshot-by-dex]
                     (fn [orders-by-dex]
                       (let [orders-by-dex* (if (map? orders-by-dex)
                                              orders-by-dex
                                              {})]
                         (reduce-kv
                          (fn [acc dex dex-orders]
                            (assoc acc
                                   dex
                                   (prune-open-order-payload dex-orders guarded-entries*)))
                          (empty orders-by-dex*)
                          orders-by-dex*)))))
      state)))
