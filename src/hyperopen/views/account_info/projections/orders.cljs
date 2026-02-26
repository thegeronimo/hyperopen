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

(def ^:private canceled-status-to-tooltip
  {"margincanceled" "Canceled due to insufficient margin."
   "vaultwithdrawalcanceled" "Canceled due to vault withdrawal."
   "openinterestcapcanceled" "Canceled due to open interest cap."
   "selftradecanceled" "Canceled to prevent self trade."
   "reduceonlycanceled" "Canceled due to reduce only."
   "siblingfilledcanceled" "Canceled because a related order filled."
   "delistedcanceled" "Canceled because the market was delisted."
   "liquidatedcanceled" "Canceled due to liquidation."
   "outcomesettledcanceled" "Canceled because the outcome was settled."
   "scheduledcancel" "Canceled by schedule."
   "internalcancel" "Canceled by internal system logic."})

(def ^:private rejected-status-to-tooltip
  {"tickrejected" "Rejected due to invalid tick."
   "mintradentlrejected" "Rejected because notional was below the minimum trade size."
   "perpmarginrejected" "Rejected due to insufficient perpetual margin."
   "reduceonlyrejected" "Rejected due to reduce only constraints."
   "badalopxrejected" "Rejected due to invalid ALO price."
   "ioccancelrejected" "Rejected because IOC would cancel immediately."
   "badtriggerpxrejected" "Rejected due to invalid trigger price."
   "marketordernoliquidityrejected" "Rejected because there was no market liquidity."
   "positionincreaseatopeninterestcaprejected" "Rejected because position increase would exceed the open interest cap."
   "positionflipatopeninterestcaprejected" "Rejected because position flip would exceed the open interest cap."
   "tooaggressiveatopeninterestcaprejected" "Rejected because order aggression would exceed the open interest cap."
   "openinterestincreaserejected" "Rejected because open interest increase is not allowed."
   "insufficientspotbalancerejected" "Rejected due to insufficient spot balance."
   "oraclerejected" "Rejected by oracle constraints."})

(def ^:private canceled-statuses
  (set (keys canceled-status-to-tooltip)))

(def ^:private rejected-statuses
  (set (keys rejected-status-to-tooltip)))

(defn- normalized-order-history-status [status]
  (some-> status
          str
          str/trim
          str/lower-case
          (str/replace #"[\s_\-]" "")))

(defn resolve-open-order-oid
  [order]
  (some parse/normalize-id
        [(:oid order)
         (:o order)
         (get-in order [:order :oid])
         (get-in order [:order :o])]))

(defn order-history-status-key [status]
  (let [text (normalized-order-history-status status)]
    (cond
      (= text "open") :open
      (= text "filled") :filled
      (or (= text "canceled")
          (= text "cancelled")
          (contains? canceled-statuses text))
      :canceled

      (or (= text "rejected")
          (contains? rejected-statuses text))
      :rejected

      (= text "triggered") :triggered
      :else nil)))

(defn order-history-status-label
  ([status]
   (order-history-status-label status nil default-order-history-status-labels))
  ([status labels]
   (order-history-status-label status nil labels))
  ([status remaining-size-num labels]
   (let [status-key (order-history-status-key status)
         labels* (or labels default-order-history-status-labels)]
     (cond
       (= status-key :filled)
       (if (and (number? remaining-size-num)
                (pos? remaining-size-num))
         "Partially Filled"
         (get labels* :filled "Filled"))

       (some? status-key)
       (or (get labels* status-key)
           (coins/title-case-label status))

       :else
       (coins/title-case-label status)))))

(defn- order-history-status-tooltip [status]
  (let [text (normalized-order-history-status status)]
    (or (get canceled-status-to-tooltip text)
        (get rejected-status-to-tooltip text))))

(defn- order-trigger-map
  [order-map]
  (when (map? order-map)
    (or (get-in order-map [:t :trigger])
        (get-in order-map [:trigger]))))

(defn normalize-open-order [order]
  (let [root (or (:order order) order)
        root-map (if (map? root) root {})
        order-map (if (map? order) order {})
        root-trigger (order-trigger-map root-map)
        order-trigger (order-trigger-map order-map)
        coin (or (:coin root-map) (:coin order-map))
        oid (or (resolve-open-order-oid root-map)
                (resolve-open-order-oid order-map))
        side (or (:side root-map) (:side order-map))
        sz (or (:sz root-map) (:origSz root-map) (:sz order-map) (:origSz order-map))
        orig-sz (or (:origSz root-map) (:origSz order-map))
        limit-px (or (:limitPx root-map) (:limitPx order-map))
        fallback-px (or (:px root-map) (:px order-map))
        trigger-px (or (:triggerPx root-map)
                       (:triggerPx order-map)
                       (:triggerPx root-trigger)
                       (:triggerPx order-trigger))
        is-trigger-value (parse/boolean-value (or (:isTrigger root-map)
                                                  (:isTrigger order-map)))
        is-trigger? (if (some? is-trigger-value)
                      (true? is-trigger-value)
                      (or (map? root-trigger)
                          (map? order-trigger)))
        trigger-condition (or (:triggerCondition root-map) (:triggerCondition order-map)
                              (:triggerCond root-map) (:triggerCond order-map)
                              (:triggerCondition root-trigger) (:triggerCondition order-trigger)
                              (:triggerCond root-trigger) (:triggerCond order-trigger))
        tpsl (or (:tpsl root-map)
                 (:tpsl order-map)
                 (:tpsl root-trigger)
                 (:tpsl order-trigger))
        dex (or (:dex root-map) (:dex order-map))
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
       :tpsl tpsl
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
         direction (or (:dir root-map)
                       (:dir row-map)
                       (:direction root-map)
                       (:direction row-map))
         size (or (:origSz root-map) (:origSz row-map) (:sz root-map) (:sz row-map))
         remaining-size (or (:remainingSz root-map)
                            (:remainingSz row-map)
                            (:sz root-map)
                            (:sz row-map))
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
         status-key (order-history-status-key status)
         status-label (order-history-status-label status
                                                 remaining-size-num
                                                 default-order-history-status-labels)
         status-tooltip (order-history-status-tooltip status)]
     (when (or (some? oid) (some? coin) (some? status-timestamp))
       {:coin coin
        :oid oid
        :side side
        :direction direction
        :size size
        :size-num size-num
        :remaining-size remaining-size
        :remaining-size-num remaining-size-num
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
        :status-key status-key
        :status-label status-label
        :status-tooltip status-tooltip}))))

(def ^:private unknown-order-history-time-ms -1)
(def ^:private unknown-order-history-filled-size -1)

(defn- order-history-status-rank [status-key]
  (case status-key
    :filled 5
    :canceled 4
    :rejected 4
    :triggered 3
    :open 1
    0))

(defn- order-history-row-preferred?
  [candidate incumbent]
  (let [candidate-rank (order-history-status-rank (:status-key candidate))
        incumbent-rank (order-history-status-rank (:status-key incumbent))]
    (cond
      (> candidate-rank incumbent-rank) true
      (< candidate-rank incumbent-rank) false
      :else
      (let [candidate-time (or (:time-ms candidate) unknown-order-history-time-ms)
            incumbent-time (or (:time-ms incumbent) unknown-order-history-time-ms)]
        (cond
          (> candidate-time incumbent-time) true
          (< candidate-time incumbent-time) false
          :else
          (> (or (:filled-size candidate) unknown-order-history-filled-size)
             (or (:filled-size incumbent) unknown-order-history-filled-size)))))))

(defn- order-history-identity-key [row]
  (let [oid (parse/normalize-id (:oid row))
        coin-token (coins/normalized-coin-token (:coin row))]
    (cond
      (and (seq coin-token) (some? oid))
      [coin-token oid]

      (some? oid)
      [:oid oid]

      :else
      nil)))

(defn- dedupe-order-history-by-identity [rows]
  (let [{:keys [rows-by-key key-order rows-without-id]}
        (reduce (fn [{:keys [rows-by-key key-order] :as acc} row]
                  (if-let [identity-key (order-history-identity-key row)]
                    (let [existing (get rows-by-key identity-key)]
                      (cond
                        (nil? existing)
                        (-> acc
                            (assoc :rows-by-key (assoc rows-by-key identity-key row))
                            (assoc :key-order (conj key-order identity-key)))

                        (order-history-row-preferred? row existing)
                        (assoc acc :rows-by-key (assoc rows-by-key identity-key row))

                        :else
                        acc))
                    (update acc :rows-without-id conj row)))
                {:rows-by-key {}
                 :key-order []
                 :rows-without-id []}
                (or rows []))]
    (->> key-order
         (map #(get rows-by-key %))
         (concat rows-without-id)
         vec)))

(defn normalized-order-history
  ([rows]
   (normalized-order-history rows nil))
  ([rows order-history-status-labels]
   (->> (or rows [])
        (map #(normalize-order-history-row % order-history-status-labels))
        (remove nil?)
        dedupe-order-history-by-identity
        vec)))
