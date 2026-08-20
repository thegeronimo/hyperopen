(ns hyperopen.portfolio.optimizer.application.execution-order-type
  "Pure per-order execution-type policy, shared by the Execution view (the order-type
  column + per-order editor) and the submission layer (apply-order-type-selections in
  application.execution) so the type a user sees can never diverge from the order that
  is actually routed.

  The four user-facing types map onto the order gateway as: :market -> marketable IOC,
  :limit -> resting GTC at mark +/- limit-bps, :passive -> post-only (ALO) limit that
  never crosses, :twap -> twapOrder sliced over twap-min minutes."
  (:require [hyperopen.portfolio.optimizer.domain.rebalance :as rebalance]))

(defn- abs-num [value] (if (number? value) (js/Math.abs value) 0))

(def resting-types
  "Order types that rest at a price instead of crossing the book: they pay no
  spread/impact price cost and earn the maker fee."
  #{:limit :passive})

(def high-cost-crossing-bps
  "Estimated crossing cost (spread + book impact, in bps) at or above which a market
  order is considered expensive: the recommended policy routes such rows passive, and
  the staged surface warns when a user-selected type still crosses one. One constant
  for both so the recommendation and the warning can never disagree."
  20)

(defn crossing-cost-bps
  "The row's estimated cost of CROSSING the book (its market price cost, bps), from the
  rebalance cost model. nil when the row carries no finite estimate."
  [row]
  (let [bps (get-in row [:cost :slippage-bps])]
    (when (and (number? bps) (js/isFinite bps))
      (js/Math.abs bps))))

(defn high-cost-crossing-row?
  "True when executing this row as a crossing (market/TWAP) order is estimated to cost
  at least `high-cost-crossing-bps`. A row with no cost estimate is NOT high-cost —
  the size rules decide it."
  [row]
  (let [bps (crossing-cost-bps row)]
    (boolean (and bps (>= bps high-cost-crossing-bps)))))

(defn recommend-exec-type
  "Algorithm-recommended order type for a row. Cost-aware first, then clip size / side /
  market type (the size/side rules mirror the v4 recommendExecType policy):

  - very large clips slice over time (:twap);
  - spot sells rest as limits;
  - a row whose estimated crossing cost is >= high-cost-crossing-bps posts passively —
    paying 20bp+ of spread/impact for immediacy is never the best default, whatever the
    clip size;
  - small cheap clips cross (:market), medium clips post passively (:passive)."
  [{:keys [instrument-type side delta-notional-usd] :as row}]
  (let [amount (abs-num delta-notional-usd)]
    (cond
      (>= amount 70000) :twap
      (and (= :sell side) (= :spot instrument-type)) :limit
      (high-cost-crossing-row? row) :passive
      (<= amount 22000) :market
      :else :passive)))

(defn effective-type
  "Resolves the effective order type for a row from the staging selections: a per-row
  override wins, else the default order type (:recommended expands via
  recommend-exec-type). Always returns one of :market/:limit/:twap/:passive."
  [{:keys [default-order-type overrides]} row]
  (or (get overrides (:row-id row))
      (if (= :recommended default-order-type)
        (recommend-exec-type row)
        (or default-order-type :market))))

(defn row-params
  "Resolves the per-row execution params (limit-bps price offset + TWAP duration in
  minutes), merging the size/side-derived defaults with any per-row override params."
  [{:keys [params]} row]
  (merge {:limit-bps (if (= :buy (:side row)) -2 2)
          :twap-min (if (>= (abs-num (:delta-notional-usd row)) 70000) 20 10)}
         (get params (:row-id row))))

(defn twap-clip-schedule
  "How the venue will actually work a row's TWAP over `minutes`:
  {:clips n :interval-seconds gap :notional-known? bool}.

  Since the 2026-08-01 upgrade the venue derives the spacing from the order's NOTIONAL as
  well as its runtime — it clips no faster than every 30s, and spaces clips wider rather
  than let one fall below the $10 clip floor. So a row with no usable notional only knows
  the upper bound on the clip count (and the 30s lower bound on the gap), which is what
  :notional-known? false means. The first clip goes out immediately, so the runtime spans
  one gap fewer than there are clips."
  [row minutes]
  (let [notional (or (get-in row [:cost :notional-usd])
                     (abs-num (:delta-notional-usd row)))
        known? (and (number? notional) (pos? notional))
        clips (rebalance/twap-venue-suborder-count minutes (when known? notional))
        runtime-minutes (max rebalance/twap-min-runtime-minutes
                             (if (number? minutes) minutes 0))]
    {:clips clips
     :notional-known? known?
     :interval-seconds (when (> clips 1) (/ (* 60 runtime-minutes) (dec clips)))}))

(defn effective-crossing-cost
  "The price-cost figures the row's EFFECTIVE order type would actually pay, for every
  staged cost projection (KPI strip, strategy tiles, high-cost warning, per-row cost
  equation). A resting type (:limit/:passive) pays no price cost. A :twap row pays the
  sliced TWAP model (rebalance/twap-cost) at its live per-row duration -- this is what
  makes the TWAP strategy project below Market instead of identical to it. Any other
  crossing type pays the row's one-shot figures. Returns
  {:crossing? bool
   :slippage-bps / :estimated-slippage-usd   price-cost total
   :spread-bps/:spread-usd/:impact-bps/:impact-usd   nil when the cost can't be split
   :estimate-floor? bool   lower-bound semantics (depth-overrun estimates)
   :twap-adjusted? / :suborders / :slice-exceeds-visible-depth?   TWAP metadata}.

  The routing policy (recommend-exec-type / high-cost-crossing-row?) intentionally
  keeps using the ONE-SHOT crossing cost: the routing question is \"would crossing be
  expensive?\" -- discounting it by the TWAP model would let huge clips route as cheap
  crossers."
  [selections row]
  (let [order-type (effective-type selections row)
        cost (:cost row)]
    (cond
      (contains? resting-types order-type)
      {:crossing? false}

      (= :twap order-type)
      (merge {:crossing? true}
             (rebalance/twap-cost cost (:twap-min (row-params selections row))))

      :else
      {:crossing? true
       :slippage-bps (:slippage-bps cost)
       :estimated-slippage-usd (:estimated-slippage-usd cost)
       :spread-bps (:spread-bps cost)
       :spread-usd (:spread-usd cost)
       :impact-bps (:impact-bps cost)
       :impact-usd (:impact-usd cost)
       :estimate-floor? (boolean (:estimate-floor? cost))})))
