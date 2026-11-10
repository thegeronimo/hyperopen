(ns hyperopen.outcomes.domain.fees
  "What leaving an outcome position costs, measured on venue `out`.

   Buying is free. A sale pays 2 x the account's spot rate (taker when it
   crosses the book, maker when it rests). A winning settlement and a merged
   pair pay 2 x the spot taker rate on the dollars returned; a losing side pays
   nothing. Other venues (skew, txyz, fox) are unconfirmed: every function
   returns `{:fee-known? false}` for them so a view can say so instead of
   printing a number. Pure: rates come in as arguments.")

(def base-spot-rates
  "Base-tier spot rates as fractions: taker 0.070%, maker 0.040%."
  {:taker 0.0007 :maker 0.0004})

(def exit-multiplier 2)

(defn- num*
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n)) n)))

(defn- rate*
  [value]
  (let [n (num* value)]
    (when (and n (>= n 0)) n)))

(defn spot-rates
  "The account's spot rates as fractions from a userFees payload
   (`:userSpotCrossRate`, `:userSpotAddRate`, after `:activeReferralDiscount`),
   falling back to base tier. {:taker :maker :source :user-fees|:base-tier}."
  [user-fees]
  (let [taker (when (map? user-fees) (rate* (:userSpotCrossRate user-fees)))
        maker (when (map? user-fees) (rate* (:userSpotAddRate user-fees)))]
    (if (and taker maker)
      (let [discount (-> (or (num* (:activeReferralDiscount user-fees)) 0)
                         (max 0)
                         (min 1))
            factor (- 1 discount)]
        {:taker (* taker factor)
         :maker (* maker factor)
         :source :user-fees})
      (assoc base-spot-rates :source :base-tier))))

(defn rates-from-state
  "Spot rates for the signed-in account held in app state."
  [state]
  (spot-rates (get-in state [:portfolio :user-fees])))

(defn venue-known?
  "True when the exit-fee rule has been measured on `venue`. A missing venue
   is the default Hyperliquid book (`out`)."
  [venue]
  (let [v (-> (or venue "") str .trim .toLowerCase)]
    (contains? #{"" "out" "hyperliquid"} v)))

(defn exit-rate
  "Fee rate (a fraction) on dollars leaving: taker-based unless `:resting?`."
  ([rates] (exit-rate rates nil))
  ([rates {:keys [resting?]}]
   (let [{:keys [taker maker]} (or rates base-spot-rates)]
     (* exit-multiplier (if resting? maker taker)))))

(defn- unknown
  [venue]
  {:fee-known? false :venue venue})

(defn- result
  [gross rate]
  (let [fee (* gross rate)]
    {:fee-known? true
     :gross gross
     :rate rate
     :fee fee
     :net (- gross fee)}))

(defn buy
  "Buying costs the shares and nothing more.
   {:shares :price :venue} -> {:fee-known? :cost :fee :rate :total}."
  [{:keys [shares price venue]}]
  (let [cost (* (or (num* shares) 0) (or (num* price) 0))]
    (if (venue-known? venue)
      {:fee-known? true :cost cost :fee 0 :rate 0 :total cost}
      (assoc (unknown venue) :cost cost))))

(defn sell
  "Selling shares at `price`; :resting? true when the order waits to be hit.
   Pass :cost to also get `:result` against what was paid.
   -> {:fee-known? :gross :rate :fee :net :result}"
  [{:keys [shares price venue rates resting? cost]}]
  (if-not (venue-known? venue)
    (unknown venue)
    (let [gross (* (or (num* shares) 0) (or (num* price) 0))
          out (result gross (exit-rate rates {:resting? resting?}))]
      (cond-> out
        (num* cost) (assoc :result (- (:net out) (num* cost)))))))

(defn settlement
  "A market settling. :payout-per-share is $1.00 for a winning side, 0 for a
   losing side, and the settled fraction for a split result (default 1). The
   fee is 2 x spot taker on the payout; nothing is charged on a zero payout.
   -> {:fee-known? :gross :rate :fee :net :profit}"
  [{:keys [shares payout-per-share venue rates cost]}]
  (if-not (venue-known? venue)
    (unknown venue)
    (let [pps (if (nil? payout-per-share) 1 (or (num* payout-per-share) 0))
          gross (* (or (num* shares) 0) pps)
          out (result gross (if (pos? gross) (exit-rate rates) 0))]
      (cond-> out
        (num* cost) (assoc :profit (- (:net out) (num* cost)))))))

(defn merge-pairs
  "Merging `pairs` of Yes+No returns $1.00 each, charged like a sale at taker.
   -> {:fee-known? :gross :rate :fee :net :pairs}"
  [{:keys [pairs venue rates]}]
  (if-not (venue-known? venue)
    (unknown venue)
    (let [pairs* (js/Math.floor (or (num* pairs) 0))]
      (assoc (result pairs* (exit-rate rates)) :pairs pairs*))))

(defn rate-text
  "\"0.14%\" for a fraction rate."
  [rate]
  (when-let [r (num* rate)]
    (let [text (-> (.toFixed (* 100 r) 3)
                   (.replace (js/RegExp. "0+$") "")
                   (.replace (js/RegExp. "\\.$") ""))
          text (if (re-find #"\.\d$" text) (str text "0") text)]
      (str text "%"))))
