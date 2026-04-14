(ns hyperopen.views.account-info.projections.orders
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections.coins :as coins]
            [hyperopen.views.account-info.projections.parse :as parse]))

(defn resolve-open-order-oid
  [order]
  (some parse/normalize-id
        [(:oid order)
         (:o order)
         (get-in order [:order :oid])
         (get-in order [:order :o])]))

(defn- open-order-map
  [value]
  (if (map? value) value {}))

(defn- open-order-root-map
  [order]
  (open-order-map (or (:order order) order)))

(defn- open-order-row-map
  [order]
  (open-order-map order))

(defn- order-trigger-map
  [order-map]
  (when (map? order-map)
    (or (get-in order-map [:t :trigger])
        (get-in order-map [:trigger]))))

(defn- open-order-field
  [root-map order-map & keys]
  (some #(or (get root-map %) (get order-map %)) keys))

(defn- open-order-trigger-field
  [root-trigger order-trigger & keys]
  (some #(or (get root-trigger %) (get order-trigger %)) keys))

(defn- open-order-identity-fields [root-map order-map]
  {:coin (open-order-field root-map order-map :coin)
   :oid (or (resolve-open-order-oid root-map) (resolve-open-order-oid order-map))
   :side (open-order-field root-map order-map :side)
   :asset-id (open-order-field root-map order-map :asset-id :assetId :asset-idx :assetIdx :asset :a)
   :dex (open-order-field root-map order-map :dex)})

(defn- open-order-size-fields
  [root-map order-map]
  {:sz (or (:sz root-map)
           (:origSz root-map)
           (:sz order-map)
           (:origSz order-map))
   :orig-sz (open-order-field root-map order-map :origSz)})

(defn- open-order-trigger-fields
  [root-map order-map root-trigger order-trigger]
  (let [is-trigger-value (parse/boolean-value (open-order-field root-map order-map :isTrigger))]
    {:trigger-px (or (open-order-field root-map order-map :triggerPx)
                     (open-order-trigger-field root-trigger order-trigger :triggerPx))
     :is-trigger? (if (some? is-trigger-value)
                    (true? is-trigger-value)
                    (or (map? root-trigger)
                        (map? order-trigger)))
     :trigger-condition (or (open-order-field root-map order-map :triggerCondition :triggerCond)
                            (open-order-trigger-field root-trigger order-trigger :triggerCondition :triggerCond))
     :tpsl (or (open-order-field root-map order-map :tpsl)
               (open-order-trigger-field root-trigger order-trigger :tpsl))}))

(defn- open-order-price
  [root-map order-map {:keys [is-trigger? trigger-px]}]
  (let [candidate (open-order-field root-map order-map :limitPx :px)]
    (if (and is-trigger?
             (zero? (or (parse/parse-optional-num candidate) 0)))
      trigger-px
      candidate)))

(defn- open-order-time-ms
  [root-map order-map]
  (parse/parse-epoch-ms (open-order-field root-map order-map :timestamp :time)))

(defn- open-order-type
  [root-map order-map]
  (open-order-field root-map order-map :orderType :type :tif))

(defn- open-order-reduce-only
  [root-map order-map]
  (parse/boolean-value
   (if (contains? root-map :reduceOnly)
     (:reduceOnly root-map)
     (:reduceOnly order-map))))

(defn- open-order-is-position-tpsl
  [root-map order-map]
  (true? (parse/boolean-value (open-order-field root-map order-map :isPositionTpsl))))

(defn normalize-open-order [order]
  (let [root-map (open-order-root-map order)
        order-map (open-order-row-map order)
        root-trigger (order-trigger-map root-map)
        order-trigger (order-trigger-map order-map)
        {:keys [coin oid side asset-id dex]} (open-order-identity-fields root-map order-map)
        {:keys [sz orig-sz]} (open-order-size-fields root-map order-map)
        {:keys [trigger-px is-trigger? trigger-condition tpsl]}
        (open-order-trigger-fields root-map order-map root-trigger order-trigger)
        px (open-order-price root-map order-map {:is-trigger? is-trigger?
                                                 :trigger-px trigger-px})
        time-ms (open-order-time-ms root-map order-map)
        order-type (open-order-type root-map order-map)
        reduce-only (open-order-reduce-only root-map order-map)
        is-position-tpsl (open-order-is-position-tpsl root-map order-map)]
    (when (or coin oid)
      {:coin coin
       :oid oid
       :side side
       :sz sz
       :orig-sz orig-sz
       :px px
       :type order-type
       :time time-ms
       :time-ms time-ms
       :reduce-only reduce-only
       :is-trigger is-trigger?
       :trigger-condition trigger-condition
       :trigger-px trigger-px
       :tpsl tpsl
       :asset-id asset-id
       :dex dex
       :is-position-tpsl is-position-tpsl})))

(defn open-orders-seq [orders]
  (cond
    (nil? orders) []
    (sequential? orders) orders
    (map? orders) (let [nested (or (:orders orders) (:openOrders orders) (:data orders))]
                    (cond
                      (sequential? nested) nested
                      (:order orders) [orders]
                      :else []))
    :else []))

(defn- attach-order-dex
  [order dex]
  (cond
    (not (map? order))
    order

    (and (map? (:order order))
         (nil? (get-in order [:order :dex]))
         (nil? (:dex order)))
    (assoc-in order [:order :dex] dex)

    (nil? (:dex order))
    (assoc order :dex dex)

    :else
    order))

(defn open-orders-by-dex [orders-by-dex]
  (->> (or orders-by-dex {})
       (mapcat (fn [[dex orders]]
                 (->> (open-orders-seq orders)
                      (map #(attach-order-dex % dex)))))))

(defn- live-open-orders-present?
  [orders]
  (cond
    (nil? orders) false
    (sequential? orders) true
    (map? orders) (or (contains? orders :orders)
                      (contains? orders :openOrders)
                      (contains? orders :data)
                      (contains? orders :order))
    :else false))

(defn open-orders-source [orders snapshot snapshot-by-dex]
  (let [live (open-orders-seq orders)
        live-present? (live-open-orders-present? orders)
        fallback (open-orders-seq snapshot)
        dex-orders (open-orders-by-dex snapshot-by-dex)]
    (concat (if live-present? live fallback) dex-orders)))

(defn pending-cancel-oid-set
  [pending-cancel-oids]
  (->> (cond
         (nil? pending-cancel-oids) []
         (set? pending-cancel-oids) pending-cancel-oids
         (sequential? pending-cancel-oids) pending-cancel-oids
         :else [pending-cancel-oids])
       (keep parse/normalize-id)
       set))

(defn order-pending-cancel?
  [order pending-cancel-oids]
  (let [pending-set (if (set? pending-cancel-oids)
                      pending-cancel-oids
                      (pending-cancel-oid-set pending-cancel-oids))
        oid (resolve-open-order-oid order)]
    (and (seq pending-set)
         (some? oid)
         (contains? pending-set oid))))

(defn normalized-open-orders
  ([orders snapshot snapshot-by-dex]
   (normalized-open-orders orders snapshot snapshot-by-dex nil))
  ([orders snapshot snapshot-by-dex pending-cancel-oids]
   (let [pending-set (pending-cancel-oid-set pending-cancel-oids)]
     (->> (open-orders-source orders snapshot snapshot-by-dex)
          (remove #(order-pending-cancel? % pending-set))
          (map normalize-open-order)
          (remove nil?)
          (filter (fn [o] (and (:coin o) (:oid o))))
          vec))))

(defn open-order-for-active-asset?
  [active-asset order]
  (let [active-token (coins/normalized-coin-token active-asset)
        order-token (coins/normalized-coin-token (:coin order))
        active-base (coins/base-coin-token active-asset)
        order-base (coins/base-coin-token (:coin order))]
    (boolean
     (and (seq active-token)
          (seq order-token)
          (or (= active-token order-token)
              (and (seq active-base) (= active-base order-token))
              (and (seq order-base) (= order-base active-token))
              (and (seq active-base)
                   (seq order-base)
                   (= active-base order-base)))))))

(defn- dedupe-open-orders-by-identity [orders]
  (->> (or orders [])
       (reduce (fn [{:keys [seen rows] :as acc} order]
                 (let [identity-key (when (and (:coin order) (:oid order))
                                      [(coins/normalized-coin-token (:coin order))
                                       (parse/normalize-id (:oid order))])]
                   (if (and identity-key (contains? seen identity-key))
                     acc
                     {:seen (if identity-key
                              (conj seen identity-key)
                              seen)
                      :rows (conj rows order)})))
               {:seen #{}
                :rows []})
       :rows
       vec))

(defn normalized-open-orders-for-active-asset
  ([orders snapshot snapshot-by-dex active-asset]
   (normalized-open-orders-for-active-asset orders snapshot snapshot-by-dex active-asset nil))
  ([orders snapshot snapshot-by-dex active-asset pending-cancel-oids]
   (if (seq (parse/non-blank-text active-asset))
     (->> (normalized-open-orders orders snapshot snapshot-by-dex pending-cancel-oids)
          (filter #(open-order-for-active-asset? active-asset %))
          dedupe-open-orders-by-identity)
     [])))
