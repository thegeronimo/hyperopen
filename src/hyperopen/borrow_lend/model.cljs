(ns hyperopen.borrow-lend.model
  "Pure HyperCore borrow/lend policy: the pool rate model, deposit and
  withdrawal rate previews, and the borrow-health maths (borrow limit, health
  factor, liquidation prices).

  Every rate and ratio here is a fraction (0.05 = 5%). Amounts are in token
  units unless a name ends in `-usd`. Inputs are the normalized reserve and
  position maps produced by `hyperopen.borrow-lend.normalize`; nothing here
  reads app state or performs I/O.")

;; The documented stablecoin rate model. Below the kink utilization the borrow
;; rate is flat; above it every extra point of utilization adds `kink-slope`
;; points of borrow APY. Suppliers receive borrowers' interest pro rata on the
;; borrowed share of the pool, minus the reserve kept for liquidations. These
;; constants reproduce the live `allBorrowLendReserveStates` rates exactly
;; (see the ExecPlan's Surprises section).
(def base-borrow-rate 0.05)
(def kink-utilization 0.8)
(def kink-slope 4.75)
(def reserve-factor 0.1)

(defn- finite? [x]
  (and (number? x) (js/isFinite x)))

(defn- clamp01 [x]
  (cond (not (finite? x)) 0
        (< x 0) 0
        (> x 1) 1
        :else x))

(defn borrow-rate
  "Borrow APY at utilization `u` (0..1)."
  [u]
  (+ base-borrow-rate
     (* kink-slope (max 0 (- (clamp01 u) kink-utilization)))))

(defn supply-rate
  "Supply APY at utilization `u`, given the borrow APY at that utilization."
  ([u] (supply-rate u (borrow-rate u)))
  ([u borrow-apy]
   (* (- 1 reserve-factor) (clamp01 u) borrow-apy)))

(defn utilization
  [total-supplied total-borrowed]
  (if (and (finite? total-supplied) (pos? total-supplied) (finite? total-borrowed))
    (clamp01 (/ total-borrowed total-supplied))
    0))

(defn above-kink?
  [u]
  (and (finite? u) (> u kink-utilization)))

(defn- rates-at
  [u]
  (let [rb (borrow-rate u)]
    {:utilization u
     :borrow-apy rb
     :supply-apy (supply-rate u rb)}))

(defn supply-preview
  "Pool rates after `amount` tokens are added to (`:supply`) or removed from
  (`:withdraw`) the reserve. Uses the documented curve, so the result is an
  estimate: other suppliers and borrowers move the pool too."
  [{:keys [total-supplied total-borrowed]} operation amount]
  (let [amount* (if (finite? amount) (max 0 amount) 0)
        supplied (or total-supplied 0)
        borrowed (or total-borrowed 0)
        ;; A withdrawal can only take available liquidity, so the pool never
        ;; shrinks below what is currently borrowed.
        supplied* (case operation
                    :withdraw (max borrowed (- supplied amount*))
                    (+ supplied amount*))]
    (rates-at (utilization supplied* borrowed))))

(defn liquidation-threshold
  "Partial-liquidation threshold for a collateral asset with loan-to-value
  `ltv`: halfway between the LTV and 100%."
  [ltv]
  (if (finite? ltv) (/ (+ 1 ltv) 2) 0))

(defn collateral-rows
  "Collateral contributions for a user's supplied positions. `positions` are
  normalized positions; `reserves-by-token` maps token index to a normalized
  reserve. Only reserves with a positive LTV count as collateral."
  [positions reserves-by-token]
  (->> positions
       (keep (fn [{:keys [token supplied]}]
               (let [{:keys [ltv oracle-px]} (get reserves-by-token token)]
                 (when (and (finite? ltv) (pos? ltv)
                            (finite? supplied) (pos? supplied)
                            (finite? oracle-px))
                   (let [value-usd (* supplied oracle-px)]
                     {:token token
                      :amount supplied
                      :oracle-px oracle-px
                      :ltv ltv
                      :threshold (liquidation-threshold ltv)
                      :value-usd value-usd
                      :limit-usd (* value-usd ltv)
                      :liquidation-usd (* value-usd (liquidation-threshold ltv))})))))
       vec))

(defn borrowed-usd
  [positions reserves-by-token]
  (reduce (fn [acc {:keys [token borrowed]}]
            (let [px (get-in reserves-by-token [token :oracle-px])]
              (if (and (finite? borrowed) (pos? borrowed) (finite? px))
                (+ acc (* borrowed px))
                acc)))
          0
          positions))

(defn health
  "Borrow health from collateral rows and the outstanding borrow in USD.
  `:health-factor` is borrow limit / borrowed (nil with no debt). At or below
  1.0 the account cannot borrow more; liquidation starts later, when the
  borrow exceeds the threshold-weighted collateral."
  [collateral borrowed-usd*]
  (let [limit (reduce + 0 (map :limit-usd collateral))
        liq-capacity (reduce + 0 (map :liquidation-usd collateral))
        debt (if (finite? borrowed-usd*) (max 0 borrowed-usd*) 0)]
    {:borrow-limit-usd limit
     :borrowed-usd debt
     :available-to-borrow-usd (max 0 (- limit debt))
     :limit-used (when (pos? limit) (/ debt limit))
     :health-factor (when (pos? debt) (/ limit debt))
     :liquidatable? (and (pos? debt) (> debt liq-capacity))}))

(defn- price-where
  "Collateral price at which the threshold-weighted sum (using `weight-key`)
  of all collateral equals `debt`, moving only `row`'s price."
  [collateral row debt weight-key]
  (let [others (reduce + 0 (map (fn [r]
                                  (if (= (:token r) (:token row))
                                    0
                                    (* (:value-usd r) (get r weight-key))))
                                collateral))
        per-unit (* (:amount row) (get row weight-key))]
    (when (and (pos? debt) (pos? per-unit))
      (max 0 (/ (- debt others) per-unit)))))

(defn liquidation-prices
  "Per collateral token: the price at which borrowing stops (health factor
  reaches 100%) and the price at which partial liquidation starts, holding
  every other collateral price fixed."
  [collateral debt-usd]
  (into {}
        (map (fn [row]
               [(:token row)
                {:borrow-stops-px (price-where collateral row debt-usd :ltv)
                 :liquidation-px (price-where collateral row debt-usd :threshold)}]))
        collateral))

(defn health-after
  "Health after changing the stablecoin borrow by `delta-usd` (positive to
  borrow, negative to repay)."
  [collateral borrowed-usd* delta-usd]
  (let [debt (max 0 (+ (or borrowed-usd* 0) (or delta-usd 0)))]
    (assoc (health collateral debt)
           :liquidation-prices (liquidation-prices collateral debt))))

(defn health-status
  "Coarse label for a health factor: :none (no debt), :safe, :caution, :risk."
  [health-factor]
  (cond (nil? health-factor) :none
        (>= health-factor 1.5) :safe
        (>= health-factor 1.15) :caution
        :else :risk))

(defn net-apy
  "Interest earned minus interest paid, per year, over supplied value.
  `supply-legs` / `borrow-legs` are seqs of {:value-usd :apy}."
  [supply-legs borrow-legs]
  (let [earned (reduce + 0 (map #(* (:value-usd %) (:apy %)) supply-legs))
        paid (reduce + 0 (map #(* (:value-usd %) (:apy %)) borrow-legs))
        base (reduce + 0 (map :value-usd supply-legs))]
    (when (pos? base)
      (/ (- earned paid) base))))
