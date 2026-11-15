(ns hyperopen.margin-rec.tiers
  "Maintenance-margin model for isolated positions.

  Hyperliquid maintenance margin is piecewise-linear in notional: within a
  tier, mm(N) = rate*N - deduction, where the deduction keeps the curve
  continuous across tier boundaries. Tier tables arrive on perp meta
  (`marginTables`) and are resolved per asset at [:asset-contexts <coin>
  :margin]; assets without a resolved table fall back to the flat rate
  1/(2*maxLeverage). The exchange's own liquidationPx is treated as ground
  truth for the current position and used to calibrate a flat rate when the
  configured model disagrees with it.")

(defn- parse-num
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n)
               (not (js/isNaN n))
               (js/isFinite n))
      n)))

(defn- normalize-tier
  [tier]
  (let [lower (parse-num (or (:lowerBound tier) (:lower-bound tier)))
        max-lev (parse-num (or (:maxLeverage tier) (:max-leverage tier)))]
    (when (and (some? lower)
               (some? max-lev)
               (>= lower 0)
               (pos? max-lev))
      {:lower-bound lower
       :max-leverage max-lev})))

(defn normalize-margin-table
  "Raw resolved margin table ({:description .. :marginTiers [..]}) to a sorted
  tier vector, or nil when the table is absent/unusable."
  [table]
  (let [tiers (->> (or (:marginTiers table) (:margin-tiers table))
                   (keep normalize-tier)
                   (sort-by :lower-bound)
                   vec)]
    (when (and (seq tiers)
               (zero? (:lower-bound (first tiers))))
      tiers)))

(defn tier-schedule
  "Tiers -> [{:lower-bound :rate :deduction}] with continuity deductions.
  rate_k = 1/(2*maxLeverage_k); d_1 = 0; d_k = d_(k-1) + b_k*(rate_k - rate_(k-1))."
  [tiers]
  (loop [remaining tiers
         prev-rate nil
         deduction 0
         acc []]
    (if-let [{:keys [lower-bound max-leverage]} (first remaining)]
      (let [rate (/ 1 (* 2 max-leverage))
            deduction* (if (nil? prev-rate)
                         0
                         (+ deduction (* lower-bound (- rate prev-rate))))]
        (recur (next remaining)
               rate
               deduction*
               (conj acc {:lower-bound lower-bound
                          :rate rate
                          :deduction deduction*})))
      acc)))

(defn flat-schedule
  "Single-tier schedule from a scalar max leverage."
  [max-leverage]
  (let [lev (parse-num max-leverage)]
    (when (and (some? lev) (pos? lev))
      [{:lower-bound 0
        :rate (/ 1 (* 2 lev))
        :deduction 0}])))

(defn flat-rate-schedule
  "Single-tier schedule from an explicit maintenance rate."
  [rate]
  (let [rate* (parse-num rate)]
    (when (and (some? rate*)
               (pos? rate*)
               (< rate* 0.5))
      [{:lower-bound 0
        :rate rate*
        :deduction 0}])))

(defn schedule-for
  "Preferred maintenance schedule: resolved margin table when present, else
  flat 1/(2*maxLeverage)."
  [margin-table max-leverage]
  (or (some-> (normalize-margin-table margin-table) tier-schedule)
      (flat-schedule max-leverage)))

(defn rate-at-notional
  "Applicable [rate deduction] for a notional under `schedule`."
  [schedule notional]
  (let [n (max 0 (or notional 0))]
    (loop [remaining schedule
           current (first schedule)]
      (if-let [{:keys [lower-bound] :as tier} (first remaining)]
        (if (<= lower-bound n)
          (recur (next remaining) tier)
          [(:rate current) (:deduction current)])
        [(:rate current) (:deduction current)]))))

(defn maintenance-margin
  "Maintenance requirement in USD at `notional` under `schedule`."
  [schedule notional]
  (let [n (max 0 (or notional 0))
        [rate deduction] (rate-at-notional schedule n)]
    (max 0 (- (* rate n) deduction))))

(defn maintenance-fn
  "Compile `schedule` into a fast (fn [notional] mm-usd) for the simulation
  hot loop. Single-tier schedules skip the tier scan."
  [schedule]
  (if (= 1 (count schedule))
    (let [rate (:rate (first schedule))]
      (fn [notional]
        (let [n (if (pos? notional) notional 0)]
          (* rate n))))
    (let [bounds (into-array (map :lower-bound schedule))
          rates (into-array (map :rate schedule))
          deductions (into-array (map :deduction schedule))
          n-tiers (alength bounds)]
      (fn [notional]
        (let [n (if (pos? notional) notional 0)]
          (loop [i (dec n-tiers)]
            (if (neg? i)
              0
              (if (<= (aget bounds i) n)
                (let [mm (- (* (aget rates i) n) (aget deductions i))]
                  (if (pos? mm) mm 0))
                (recur (dec i))))))))))

(defn liquidation-price
  "Estimated liquidation price for signed size `q`, reference price `p0`, and
  isolated equity `e` (equity marks to `p0`): solves
  e + q*(P - p0) = rate*|q|*P - deduction piecewise per tier. Returns nil when
  no positive self-consistent solution exists (e.g. equity covers the position
  to zero for longs)."
  [schedule q p0 e]
  (when (and (number? q) (not (zero? q))
             (number? p0) (pos? p0)
             (number? e))
    (let [abs-q (js/Math.abs q)
          candidates
          (keep (fn [{:keys [rate deduction]}]
                  (let [denom (- q (* rate abs-q))
                        p (when-not (zero? denom)
                            (/ (- (* q p0) e deduction) denom))]
                    (when (and (some? p)
                               (pos? p)
                               (let [n (* abs-q p)
                                     [r d] (rate-at-notional schedule n)]
                                 (and (== r rate) (== d deduction)))
                               (if (pos? q) (< p p0) (> p p0)))
                      p)))
                schedule)]
      (first (sort-by #(js/Math.abs (- % p0)) candidates)))))

(defn calibrate-flat-rate
  "Flat maintenance rate implied by the exchange-provided liquidation price for
  the current state: from e + q*(L - p0) = m*|q|*L. Returns nil when the
  inputs do not produce a sane rate."
  [q p0 e liq-px]
  (when (and (number? q) (not (zero? q))
             (number? p0) (pos? p0)
             (number? e)
             (number? liq-px) (pos? liq-px))
    (let [m (/ (+ e (* q (- liq-px p0)))
               (* (js/Math.abs q) liq-px))]
      (when (and (js/isFinite m)
                 (pos? m)
                 (< m 0.5))
        m))))

(defn relative-mismatch
  "Relative disagreement between a modeled and exchange liquidation price,
  scaled by distance-to-liquidation (so tiny absolute gaps near the mark do
  not read as huge mismatches). Returns nil when either input is missing."
  [modeled exchange p0]
  (when (and (number? modeled) (pos? modeled)
             (number? exchange) (pos? exchange)
             (number? p0) (pos? p0))
    (let [distance (js/Math.abs (- p0 exchange))]
      (if (pos? distance)
        (/ (js/Math.abs (- modeled exchange)) distance)
        1))))
