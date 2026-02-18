(ns hyperopen.views.account-info.projections.orders
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.projections.coins :as coins]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def ^:private default-order-history-status-labels
  {:all "All"
   :open "Open"
   :filled "Filled"
   :canceled "Canceled"
   :rejected "Rejected"
   :triggered "Triggered"})

(defn resolve-open-order-oid
  [order]
  (some parse/normalize-id
        [(:oid order)
         (:o order)
         (get-in order [:order :oid])
         (get-in order [:order :o])]))

(defn order-history-status-key [status]
  (let [text (some-> status str str/trim str/lower-case)]
    (case text
      "open" :open
      "filled" :filled
      "canceled" :canceled
      "cancelled" :canceled
      "rejected" :rejected
      "triggered" :triggered
      nil)))

(defn order-history-status-label
  ([status]
   (order-history-status-label status default-order-history-status-labels))
  ([status labels]
   (let [status-key (order-history-status-key status)]
     (or (get labels status-key)
         (coins/title-case-label status)))))

(defn normalize-open-order [order]
  (let [root (or (:order order) order)
        root-map (if (map? root) root {})
        order-map (if (map? order) order {})
        coin (or (:coin root-map) (:coin order-map))
        oid (or (resolve-open-order-oid root-map)
                (resolve-open-order-oid order-map))
        side (or (:side root-map) (:side order-map))
        sz (or (:sz root-map) (:origSz root-map) (:sz order-map) (:origSz order-map))
        orig-sz (or (:origSz root-map) (:origSz order-map))
        limit-px (or (:limitPx root-map) (:limitPx order-map))
        fallback-px (or (:px root-map) (:px order-map))
        trigger-px (or (:triggerPx root-map) (:triggerPx order-map))
        is-trigger? (true? (parse/boolean-value (or (:isTrigger root-map) (:isTrigger order-map))))
        trigger-condition (or (:triggerCondition root-map) (:triggerCondition order-map)
                              (:triggerCond root-map) (:triggerCond order-map))
        candidate (or limit-px fallback-px)
        px (if (and is-trigger?
                    (zero? (or (parse/parse-optional-num candidate) 0)))
             trigger-px
             candidate)
        time-ms (parse/parse-epoch-ms (or (:timestamp root-map)
                                          (:timestamp order-map)
                                          (:time root-map)
                                          (:time order-map)))
        order-type (or (:orderType root-map)
                       (:orderType order-map)
                       (:type root-map)
                       (:type order-map)
                       (:tif root-map)
                       (:tif order-map))
        reduce-only-value (if (contains? root-map :reduceOnly)
                            (:reduceOnly root-map)
                            (:reduceOnly order-map))
        reduce-only (parse/boolean-value reduce-only-value)
        is-position-tpsl (true? (parse/boolean-value (or (:isPositionTpsl root-map)
                                                         (:isPositionTpsl order-map))))]
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

(defn open-orders-by-dex [orders-by-dex]
  (->> (vals (or orders-by-dex {}))
       (mapcat open-orders-seq)))

(defn open-orders-source [orders snapshot snapshot-by-dex]
  (let [live (open-orders-seq orders)
        fallback (open-orders-seq snapshot)
        dex-orders (open-orders-by-dex snapshot-by-dex)]
    (concat (if (seq live) live fallback) dex-orders)))

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

(defn normalize-order-history-row
  ([row]
   (normalize-order-history-row row nil))
  ([row _order-history-status-labels]
   (let [root (or (:order row) row)
         root-map (if (map? root) root {})
         row-map (if (map? row) row {})
         coin (or (:coin root-map) (:coin row-map))
         oid (some parse/normalize-id
                   [(:oid root-map) (:oid row-map) (:orderId root-map) (:orderId row-map)])
         side (or (:side root-map) (:side row-map))
         size (or (:origSz root-map) (:origSz row-map) (:sz root-map) (:sz row-map))
         remaining-size (or (:remainingSz root-map) (:remainingSz row-map))
         limit-px (or (:limitPx root-map) (:limitPx row-map))
         fallback-px (or (:px root-map) (:px row-map))
         trigger-px (or (:triggerPx root-map) (:triggerPx row-map))
         is-trigger (true? (parse/boolean-value (or (:isTrigger root-map) (:isTrigger row-map))))
         trigger-condition (or (:triggerCondition root-map)
                               (:triggerCondition row-map)
                               (:triggerCond root-map)
                               (:triggerCond row-map))
         reduce-only-value (if (contains? root-map :reduceOnly)
                             (:reduceOnly root-map)
                             (:reduceOnly row-map))
         reduce-only (parse/boolean-value reduce-only-value)
         is-position-tpsl-value (if (contains? root-map :isPositionTpsl)
                                  (:isPositionTpsl root-map)
                                  (:isPositionTpsl row-map))
         is-position-tpsl (true? (parse/boolean-value is-position-tpsl-value))
         order-type (or (:orderType root-map)
                        (:orderType row-map)
                        (:type root-map)
                        (:type row-map)
                        (:tif root-map)
                        (:tif row-map))
         status (or (:status row-map)
                    (:status root-map)
                    (:orderStatus row-map)
                    (:orderStatus root-map))
         status-timestamp (or (:statusTimestamp row-map)
                              (:statusTimestamp root-map)
                              (:statusTime row-map)
                              (:statusTime root-map)
                              (:timestamp root-map)
                              (:timestamp row-map)
                              (:time root-map)
                              (:time row-map))
         size-num (parse/parse-optional-num size)
         remaining-size-num (parse/parse-optional-num remaining-size)
         market? (or (= "market" (some-> order-type str str/trim str/lower-case))
                     (true? (parse/boolean-value (or (:isMarket root-map) (:isMarket row-map))))
                     (zero? (or (parse/parse-optional-num (or limit-px fallback-px)) 0)))
         px (when-not market?
              (or limit-px fallback-px))
         filled-size (when (and (number? size-num)
                                (number? remaining-size-num))
                       (max 0 (- size-num remaining-size-num)))
         order-value (let [price-num (parse/parse-optional-num px)]
                       (when (and (not market?)
                                  (number? size-num)
                                  (number? price-num)
                                  (pos? size-num)
                                  (pos? price-num))
                         (* size-num price-num)))
         status-key (order-history-status-key status)]
     (when (or (some? oid) (some? coin) (some? status-timestamp))
       {:coin coin
        :oid oid
        :side side
        :size size
        :size-num size-num
        :filled-size filled-size
        :order-value order-value
        :px px
        :market? market?
        :type order-type
        :time-ms (parse/parse-epoch-ms status-timestamp)
        :reduce-only reduce-only
        :is-trigger is-trigger
        :trigger-condition trigger-condition
        :trigger-px trigger-px
        :is-position-tpsl is-position-tpsl
        :status status
        :status-key status-key}))))

(defn normalized-order-history
  ([rows]
   (normalized-order-history rows nil))
  ([rows order-history-status-labels]
   (->> (or rows [])
        (map #(normalize-order-history-row % order-history-status-labels))
        (remove nil?)
        vec)))
