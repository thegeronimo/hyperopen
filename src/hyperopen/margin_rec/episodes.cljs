(ns hyperopen.margin-rec.episodes
  "Intervention-horizon inference from the user's own fills.

  Fills are reconstructed into position episodes per coin (an episode starts
  when net exposure leaves zero and ends at zero or on a direction flip).
  Risk-reducing fills, closes, and flips are interventions; the gaps between
  consecutive interventions (episode start included) form the sample. The
  horizon is an upper quantile of those gaps — how long this user plausibly
  leaves a position unattended — not their average trade frequency.")

(def ^:private zero-size-epsilon 1e-9)

(defn- parse-num
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n))
      n)))

(defn normalize-fill
  "Raw userFills row -> {:coin :time-ms :delta :start-position :order-id} or nil.
  `delta` is the signed size change (B buys increase net, A/S sells decrease);
  `start-position` is the exchange-reported net before the fill when present;
  `order-id` (the exchange `oid`) is what lets partial fills of one order be
  recognised as a single intervention (see coalesce-fills)."
  [row]
  (let [coin (let [c (:coin row)]
               (when (and (string? c) (seq c)) c))
        time-ms (some parse-num [(:time row) (:timestamp row) (:ts row)])
        size (some parse-num [(:sz row) (:size row)])
        side (let [s (:side row)]
               (cond
                 (contains? #{"B" "b" :buy "buy"} s) :buy
                 (contains? #{"A" "a" "S" "s" :sell "sell"} s) :sell
                 :else nil))
        start-position (parse-num (:startPosition row))
        order-id (some parse-num [(:oid row) (:orderId row) (:order-id row)])]
    (when (and coin time-ms size (pos? size) side)
      {:coin coin
       :time-ms time-ms
       :delta (if (= :buy side) size (- size))
       :start-position start-position
       :order-id order-id})))

(defn- zeroish?
  [value]
  (< (js/Math.abs value) zero-size-epsilon))

(def ^:private coalesce-window-ms
  "When fills carry no order id, consecutive same-direction fills within this
  window are treated as one order. Long enough to absorb an order that sweeps
  the book (or a short TWAP), short enough not to merge distinct decisions."
  60000)

(defn- continues-order?
  "Does `fill` continue the same order/action as the run's most recent fill?
  True when they share an exchange order id (however long the order takes to
  fill), or — for the same coin and direction — when they land within
  `coalesce-window-ms` (covers missing ids, split child orders, and rapid
  same-direction fills that are not a meaningful unattended gap). Opposite
  directions never merge."
  [latest fill]
  (boolean
   (and latest
        (= (:coin latest) (:coin fill))
        (or (and (:order-id latest) (:order-id fill)
                 (= (:order-id latest) (:order-id fill)))
            (and (= (neg? (:delta latest)) (neg? (:delta fill)))
                 (<= (- (:time-ms fill) (:time-ms latest)) coalesce-window-ms))))))

(defn coalesce-fills
  "Collapse each order's partial fills into one logical fill so that an order
  which executes in many pieces counts as a single intervention rather than a
  burst of near-simultaneous ones. Without this, a multi-fill order (a TWAP, or
  any order sweeping several book levels) contributes a run of seconds-apart
  gaps that dominate the horizon quantile and collapse it to the floor.

  Consecutive fills are merged when they share an order id (or, lacking ids,
  the same coin and direction within `coalesce-window-ms`). The merged fill
  keeps the earliest time and start-position (the state before the order began)
  and the summed delta. Call this per coin: an order belongs to one coin, and
  merging across coins would break on interleaved fills."
  [fills]
  (->> (sort-by :time-ms fills)
       (reduce (fn [runs fill]
                 (let [current (peek runs)]
                   (if (continues-order? (:latest current) fill)
                     (conj (pop runs)
                           (-> current
                               (update :delta + (:delta fill))
                               (assoc :latest fill)))
                     (conj runs (assoc fill :latest fill)))))
               [])
       (mapv #(dissoc % :latest))))

(defn intervention-gaps-for-coin
  "Gap durations (ms) between consecutive interventions across this coin's
  episodes, in fill order. Expects fills already coalesced per order
  (see coalesce-fills)."
  [fills]
  (let [ordered (sort-by :time-ms fills)]
    (loop [remaining ordered
           net 0.0
           anchor nil
           gaps []]
      (if-let [{:keys [time-ms delta start-position]} (first remaining)]
        (let [net-before (if (some? start-position) start-position net)
              net-after (+ net-before delta)
              opening? (and (zeroish? net-before) (not (zeroish? net-after)))
              closing? (and (not (zeroish? net-before)) (zeroish? net-after))
              flip? (and (not (zeroish? net-before))
                         (not (zeroish? net-after))
                         (not= (pos? net-before) (pos? net-after)))
              reducing? (and (not (zeroish? net-before))
                             (< (js/Math.abs net-after) (js/Math.abs net-before)))
              intervention? (or closing? flip? reducing?)
              gaps* (if (and intervention? (some? anchor) (> time-ms anchor))
                      (conj gaps (- time-ms anchor))
                      gaps)
              anchor* (cond
                        closing? nil
                        (or opening? flip? intervention?) time-ms
                        :else anchor)]
          (recur (next remaining) net-after anchor* gaps*))
        gaps))))

(defn- quantile
  [sorted p]
  (let [n (count sorted)]
    (when (pos? n)
      (let [pos (* p (dec n))
            lo (js/Math.floor pos)
            hi (js/Math.ceil pos)
            frac (- pos lo)]
        (if (== lo hi)
          (nth sorted lo)
          (+ (* (- 1 frac) (nth sorted lo))
             (* frac (nth sorted hi))))))))

(def default-horizon-hours 72)
(def min-horizon-hours 6)
(def max-horizon-hours 720)
(def ^:private min-gap-samples 8)
(def ^:private min-account-coins
  "Minimum distinct markets required before estimating a thin position's
  horizon from the user's other markets."
  3)
(def ^:private horizon-quantile 0.8)

(defn- ms->hours [ms] (/ ms 3600000))

(defn- coin-horizon-hours
  "A single market's horizon (hours): the upper quantile of the gaps between
  its coalesced interventions, or nil if it has none."
  [coalesced-fills]
  (let [gaps (intervention-gaps-for-coin coalesced-fills)]
    (when (seq gaps)
      (ms->hours (quantile (vec (sort gaps)) horizon-quantile)))))

(defn horizon-hours
  "Estimated intervention horizon for `coin` from raw fill rows.
  Returns {:hours :source (:per-coin | :account | :default) :samples n}.

  When the coin itself has enough interventions, its own upper-quantile gap is
  used. Otherwise the estimate comes from the user's other markets — but as the
  MEDIAN of each market's own horizon (one vote per market), not a pool of
  every gap. Pooling would weight by fill count, so a single hyperactively
  traded market (hundreds of scalps seconds apart) would dominate and collapse
  the horizon to the floor even though the user holds this position for days.
  For `:per-coin` `:samples` is the gap count; for `:account` it is the number
  of contributing markets."
  [fill-rows coin]
  (let [fills (keep normalize-fill fill-rows)
        by-coin (into {}
                      (map (fn [[c fs]] [c (coalesce-fills fs)]))
                      (group-by :coin fills))
        coin-gaps (when coin
                    (intervention-gaps-for-coin (get by-coin coin [])))
        market-horizons (when (< (count coin-gaps) min-gap-samples)
                          (vec (keep coin-horizon-hours (vals by-coin))))
        [hours source samples]
        (cond
          (>= (count coin-gaps) min-gap-samples)
          [(ms->hours (quantile (vec (sort coin-gaps)) horizon-quantile))
           :per-coin (count coin-gaps)]

          (>= (count market-horizons) min-account-coins)
          [(quantile (vec (sort market-horizons)) 0.5)
           :account (count market-horizons)]

          :else [default-horizon-hours :default 0])]
    {:hours (-> hours
                (max min-horizon-hours)
                (min max-horizon-hours))
     :source source
     :samples samples}))
