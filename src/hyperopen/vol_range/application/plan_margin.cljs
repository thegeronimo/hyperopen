(ns hyperopen.vol-range.application.plan-margin
  "Perp margin for the Vol Range planning core: the margin refusals and the
  sell room the percent shortcuts size from. Pure; split from `plan-core`."
  (:require [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.contracts :as contracts]))

(defn- d [s] (precision/parse-decimal s))
(defn- ds [x] (precision/decimal->string x))
(def ^:private zero (d "0"))
(defn- sum-by [legs k] (reduce precision/add zero (map #(d (get % k)) legs)))

(def ^:private funds-code->side
  {:plan/insufficient-usdc :buy :plan/insufficient-base :sell})

(defn- usd [x] (display/fixed (ds x) 2))

(defn- margin-message
  "Says where the margin is missing: a standard account holds collateral per perp dex."
  [side required-notional {:keys [leverage available]} {:keys [dex quote-symbol base-symbol flip]}]
  (let [quote (or quote-symbol "USDC")
        flip-note (when (and (= :sell side) flip)
                    (str "These sells are larger than your " (:closing flip)
                         (when base-symbol (str " " base-symbol)) " long: they would close it and open a "
                         (:opening flip) (when base-symbol (str " " base-symbol)) " short. "))
        account (if (seq dex) (str "your " dex " perps account") "your perps account")
        lev (d leverage)
        avail (d available)]
    (if (precision/zero-dec? avail)
      (str flip-note "No margin in " account ". Transfer " quote " there to trade this market.")
      (str flip-note
           "Not enough margin for the " (cond (= :buy side) "buys" flip-note "short" :else "sells") ": "
           (when required-notional
             (str "about " (usd (precision/divide required-notional lev 8 :ceil)) " " quote " needed at "
                  (ds lev) "x, "))
           (usd avail) " " quote " available in " account "."))))

(defn margin-errors
  "On a perp the buy and sell capacities are margin capacities, so a funds
  shortfall on either side is reported as one `:plan/insufficient-margin`
  per side, with a message naming the dex account that lacks the margin."
  [margin scope errors]
  (->> errors
       (map (fn [e]
              (if-let [side (funds-code->side (:code e))]
                (let [notional (when (or (= :buy side) (= :quote (get-in e [:data :unit])))
                                 (some-> (get-in e [:data :required]) d))]
                  (contracts/error :plan/insufficient-margin
                                   (cond-> (assoc (:data e) :side side)
                                     margin
                                     (assoc :message (margin-message side notional margin
                                                                     (assoc scope :flip (get-in e [:data :flip])))))))
                e)))
       (reduce (fn [[seen out] e]
                 (let [k (when (= :plan/insufficient-margin (:code e)) (get-in e [:data :side]))]
                   (if (and k (contains? seen k)) [seen out] [(cond-> seen k (conj k)) (conj out e)])))
               [#{} []])
       second))

(defn sell-margin-errors
  "The base-unit sell capacity is priced at the mid, but sells rest above
  it. Check the quote notional of the sell quantity that opens or adds to a
  short (prorated by quantity; closing a long needs no margin), plus the
  quote fee bound, against the margin notional capacity."
  [{:keys [q sell-legs sell-total reserve margin]}]
  (when (and margin (precision/pos-dec? sell-total))
    (let [capacity (d (:notional-capacity margin))
          opening (precision/dec-max zero (precision/sub sell-total (precision/dec-max zero q)))
          notional (precision/divide (precision/mul (sum-by sell-legs :notional) opening) sell-total 8 :ceil)
          fee (if (= :quote (:token reserve)) (d (:amount reserve)) zero)
          required (precision/add notional fee)]
      (when (and (precision/pos-dec? opening) (pos? (precision/cmp required capacity)))
        [(contracts/error :plan/insufficient-base
                          (cond-> {:required (ds required) :available (ds capacity) :unit :quote}
                            (precision/pos-dec? q) (assoc :flip {:closing (ds q) :opening (ds opening)})))]))))

(defn sell-room
  "Base quantity a perp can sell, for the percent shortcuts: the long (closing
  it needs no margin) plus the short the margin can open. The short is priced
  at the highest sell rung plus the quote fee, so a shortcut's own quantity
  always passes the rung-priced margin check; the snapshot's base capacity is
  priced at the mid, which sells normally rest above. Without rung prices only
  the long counts; nil when there is neither."
  [{:keys [q margin sell-prices fee-f]}]
  (when q
    (let [long-q (precision/dec-max zero q)
          short-room (when (and margin (seq sell-prices))
                       (let [top (reduce precision/dec-max sell-prices)
                             fee (precision/dec-max zero (or fee-f zero))
                             per-unit (precision/mul top (precision/add (d "1") fee))]
                         (when (precision/pos-dec? per-unit)
                           (precision/divide (d (:notional-capacity margin)) per-unit 20 :floor))))]
      (cond short-room (precision/add long-q short-room)
            (precision/pos-dec? long-q) long-q))))
