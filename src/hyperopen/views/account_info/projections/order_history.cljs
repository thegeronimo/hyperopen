(ns hyperopen.views.account-info.projections.order-history
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

(defn- first-some
  [& values]
  (some identity values))

(defn- map-or-empty
  [value]
  (if (map? value) value {}))

(defn- order-history-root-map
  [row]
  (map-or-empty (or (:order row) row)))

(defn- order-history-row-map
  [row]
  (map-or-empty row))

(defn- order-history-field
  [root-map row-map & keys]
  (some #(first-some (get root-map %) (get row-map %)) keys))

(defn- order-history-prefer-root-flag
  [root-map row-map key]
  (if (contains? root-map key)
    (get root-map key)
    (get row-map key)))

(defn- order-history-identity-fields
  [root-map row-map]
  {:coin (first-some (:coin root-map) (:coin row-map))
   :oid (some parse/normalize-id
              [(:oid root-map) (:oid row-map) (:orderId root-map) (:orderId row-map)])
   :side (first-some (:side root-map) (:side row-map))
   :direction (order-history-field root-map row-map :dir :direction)})

(defn- order-history-size-fields
  [root-map row-map]
  (let [size (order-history-field root-map row-map :origSz :sz)
        remaining-size (first-some (:remainingSz root-map)
                                   (:remainingSz row-map)
                                   (:sz root-map)
                                   (:sz row-map))
        size-num (parse/parse-optional-num size)
        remaining-size-num (parse/parse-optional-num remaining-size)
        filled-size (when (and (number? size-num)
                               (number? remaining-size-num))
                      (max 0 (- size-num remaining-size-num)))]
    {:size size
     :remaining-size remaining-size
     :size-num size-num
     :remaining-size-num remaining-size-num
     :filled-size filled-size}))

(defn- order-history-trigger-fields
  [root-map row-map]
  {:trigger-px (order-history-field root-map row-map :triggerPx)
   :is-trigger (true? (parse/boolean-value (order-history-field root-map row-map :isTrigger)))
   :trigger-condition (order-history-field root-map row-map :triggerCondition :triggerCond)})

(defn- order-history-status-fields
  [root-map row-map]
  {:status (first-some (:status row-map)
                       (:status root-map)
                       (:orderStatus row-map)
                       (:orderStatus root-map))
   :status-timestamp (first-some (:statusTimestamp row-map)
                                 (:statusTimestamp root-map)
                                 (:statusTime row-map)
                                 (:statusTime root-map)
                                 (:timestamp root-map)
                                 (:timestamp row-map)
                                 (:time root-map)
                                 (:time row-map))})

(defn- order-history-order-type
  [root-map row-map]
  (order-history-field root-map row-map :orderType :type :tif))

(defn- order-history-market?
  [order-type root-map row-map limit-px fallback-px]
  (or (= "market" (some-> order-type str str/trim str/lower-case))
      (true? (parse/boolean-value (order-history-field root-map row-map :isMarket)))
      (zero? (or (parse/parse-optional-num (first-some limit-px fallback-px)) 0))))

(defn- order-history-price-fields
  [order-type root-map row-map]
  (let [limit-px (order-history-field root-map row-map :limitPx)
        fallback-px (order-history-field root-map row-map :px)
        market? (order-history-market? order-type root-map row-map limit-px fallback-px)]
    {:px (when-not market?
           (first-some limit-px fallback-px))
     :market? market?}))

(defn- order-history-flag-fields
  [root-map row-map]
  {:reduce-only (parse/boolean-value (order-history-prefer-root-flag root-map row-map :reduceOnly))
   :is-position-tpsl (true? (parse/boolean-value
                             (order-history-prefer-root-flag root-map row-map :isPositionTpsl)))})

(defn- order-history-order-value
  [market? size-num px]
  (let [price-num (parse/parse-optional-num px)]
    (when (and (not market?)
               (number? size-num)
               (number? price-num)
               (pos? size-num)
               (pos? price-num))
      (* size-num price-num))))

(defn normalize-order-history-row
  ([row]
   (normalize-order-history-row row nil))
  ([row _order-history-status-labels]
   (let [root-map (order-history-root-map row)
         row-map (order-history-row-map row)
         {:keys [coin oid side direction]} (order-history-identity-fields root-map row-map)
         {:keys [size remaining-size size-num remaining-size-num filled-size]}
         (order-history-size-fields root-map row-map)
         {:keys [trigger-px is-trigger trigger-condition]}
         (order-history-trigger-fields root-map row-map)
         {:keys [status status-timestamp]} (order-history-status-fields root-map row-map)
         order-type (order-history-order-type root-map row-map)
         {:keys [px market?]} (order-history-price-fields order-type root-map row-map)
         {:keys [reduce-only is-position-tpsl]} (order-history-flag-fields root-map row-map)
         order-value (order-history-order-value market? size-num px)
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
