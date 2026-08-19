(ns hyperopen.portfolio.optimizer.domain.rebalance
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def default-fallback-slippage-bps
  25)

(def max-extrapolated-slippage-bps
  ;; Hard cap (10%) on the penalty charged to the portion of an order that overruns the
  ;; visible book. Beyond the visible levels any point estimate is fiction: the previous
  ;; unbounded fallback-x-overrun penalty produced >10,000 bp "estimates" on thin HIP-3
  ;; books (a sell filling below zero). A capped estimate is an explicit LOWER BOUND --
  ;; the cost map is flagged :estimate-floor? true so surfaces render it as ">= X".
  1000)

(def twap-permanent-impact-fraction
  ;; Fraction of one TWAP clip's book impact assumed PERMANENT (not recovered by book
  ;; replenishment before the next suborder). 0.3 = 30% permanent / 70% temporary. Sets
  ;; the TWAP model's asymptote: an infinitely sliced order still pays the spread plus
  ;; half the permanent share (0.15x) of the one-shot impact.
  0.3)

(def twap-suborder-interval-seconds
  ;; FLOOR on venue suborder spacing, NOT a fixed cadence: since the 2026-08-01 upgrade
  ;; Hyperliquid clips at most this often and stretches the gap rather than let a clip
  ;; fall below min-order-notional-usd. Mirrors trading.core/twap-frequency-seconds;
  ;; kept local (like min-order-notional-usd below) so this domain stays decoupled.
  30)

(def twap-min-runtime-minutes
  ;; Venue floor on TWAP runtime (the submit path clamps to (max 5 twap-min) as well).
  5)

(def ^:private max-favorable-fill-deviation
  ;; A marketable taker fill cannot beat the reference price by a wide margin. When an
  ;; orderbook snapshot implies one (a stale or coin-mismatched book), the favorable
  ;; delta would clamp to a deceptive 0 bp via (max 0 ...); instead we treat the estimate
  ;; as untrusted and fall back. 0.005 = 50 bps.
  0.005)

(def ^:private min-order-notional-usd
  ;; Hyperliquid rejects orders below a $10 notional, and holds every TWAP CLIP to the
  ;; same floor -- it spaces clips wider rather than breach it (twap-venue-suborder-count).
  ;; Mirrors trading.core's scale-min-endpoint-notional / twap-min-suborder-notional.
  10)

(defn- abs-num
  [value]
  (js/Math.abs value))

(def ^:private finite-number? coercion/finite-number?)

(defn- side-for
  [delta-value]
  (cond
    (pos? delta-value) :buy
    (neg? delta-value) :sell
    :else :none))

(def ^:private finite-positive? coercion/positive-number?)

(def ^:private parse-number coercion/parse-float-number)

(defn- level-price
  [level]
  (or (parse-number (:px-num level))
      (parse-number (:px level))
      (parse-number (:price level))))

(defn- level-size
  [level]
  (or (parse-number (:sz-num level))
      (parse-number (:sz level))
      (parse-number (:size level))
      (parse-number (:qty level))))

(defn- snapshot-side-levels
  [context side]
  (case side
    :buy (or (:asks context)
             (:ask-levels context)
             (second (:levels context)))
    :sell (or (:bids context)
              (:bid-levels context)
              (first (:levels context)))
    nil))

(defn- slippage-bps-from-fill-price
  [side reference-price fill-price]
  (when (and (finite-positive? reference-price)
             (finite-positive? fill-price))
    (* 10000
       (/ (max 0
               (case side
                 :buy (- fill-price reference-price)
                 :sell (- reference-price fill-price)
                 0))
          reference-price))))

(defn realized-slippage-bps
  "Public: realized fill slippage in bps versus the reference price the estimate used.
   Reuses the estimate's convention (favorable fills floor at 0) so a realized figure is
   directly comparable to the row's estimated :slippage-bps."
  [side reference-price fill-price]
  (slippage-bps-from-fill-price side reference-price fill-price))

(defn- implausible-favorable-fill?
  "True when a candidate book fill is favorable beyond the trust band -- a buy filling
   below the reference, or a sell filling above it, by more than max-favorable-fill-deviation.
   Such a fill is non-physical for a marketable taker and signals a stale/mismatched book,
   so the favorable clamp to 0 bp would be deceptive."
  [side reference-price fill-price]
  (and (finite-positive? reference-price)
       (finite-positive? fill-price)
       (let [favorable (case side
                         :buy (- reference-price fill-price)
                         :sell (- fill-price reference-price)
                         0)]
         (> (/ favorable reference-price) max-favorable-fill-deviation))))

(defn- untrusted-fill-cost
  "Cost map for a snapshot whose implied fill is implausibly favorable: report the
   configured flat fallback against the reference price rather than a deceptive 0 bp."
  [metadata reference-price fallback]
  (merge metadata
         {:source :untrusted-snapshot-fill
          :estimated-fill-price reference-price
          :slippage-bps fallback
          :fallback-reason :implausible-favorable-fill}))

(defn- visible-depth-fill
  [levels quantity]
  (when (and (finite-positive? quantity)
             (seq levels))
    (loop [remaining quantity
           notional 0
           filled 0
           worst-price nil
           levels* (seq levels)]
      (if (or (not (pos? remaining))
              (nil? levels*))
        (when (pos? filled)
          (if (not (pos? remaining))
            {:estimated-fill-price (/ notional filled)
             :depth-status :full-visible-depth}
            ;; :worst-price = the marginal price where visibility ended (the last
            ;; consumable level) -- the depth-limited penalty extrapolates from it.
            {:visible-size filled
             :visible-notional notional
             :worst-price worst-price
             :depth-status :insufficient-visible-depth}))
        (let [level (first levels*)
              price (level-price level)
              size (level-size level)]
          (if (and (finite-positive? price)
                   (finite-positive? size))
            (let [fill-size (min remaining size)]
              (recur (- remaining fill-size)
                     (+ notional (* fill-size price))
                     (+ filled fill-size)
                     price
                     (next levels*)))
            (recur remaining
                   notional
                   filled
                   worst-price
                   (next levels*))))))))

(defn- orderbook-fill-price
  [context side]
  (case side
    :buy (level-price (:best-ask context))
    :sell (level-price (:best-bid context))
    nil))

(defn- orderbook-slippage-bps
  [context side reference-price]
  (let [fill-price (orderbook-fill-price context side)]
    (when (and (finite-positive? reference-price)
               (finite-positive? fill-price))
      (slippage-bps-from-fill-price side reference-price fill-price))))

(defn- depth-limited-slippage-bps
  "Bounded, floor-semantics slippage for an order whose size exceeds the visible book.
   The visible (consumable) portion is charged its real VWAP slippage. The unfilled
   remainder is charged from the book-EDGE marginal price (the worst visible level --
   the invisible book cannot be better than where visibility ended), scaled by the
   overrun multiple (on a uniform-density ladder average slippage grows ~linearly with
   size, so the shape is right) and HARD-CAPPED at max-extrapolated-slippage-bps:
   beyond the visible book any point estimate is fiction, so the result is an explicit
   lower bound (:estimate-floor? on the cost map), never a precise-looking blowup --
   the previous uncapped fallback-x-overrun penalty read >100% of notional on thin
   books. Floored at the flat fallback so it never under-charges relative to the prior
   flat assumption. A sell can no longer imply a fill below zero: its visible VWAP
   slippage is physically < 10,000 bp and the remainder is capped at 1,000."
  [side reference-price quantity {:keys [visible-size visible-notional worst-price]} fallback]
  (if (and (finite-positive? visible-size)
           (finite-positive? visible-notional)
           (finite-positive? quantity))
    (let [visible-vwap (/ visible-notional visible-size)
          visible-slip (or (slippage-bps-from-fill-price side
                                                         reference-price
                                                         visible-vwap)
                           fallback)
          edge-slip (or (slippage-bps-from-fill-price side
                                                      reference-price
                                                      worst-price)
                        visible-slip)
          depth-ratio (min 1 (/ visible-size quantity))
          overrun (max 1 (/ quantity visible-size))
          remainder-bps (min (* (max edge-slip fallback) overrun)
                             max-extrapolated-slippage-bps)
          blended (+ (* depth-ratio visible-slip)
                     (* (- 1 depth-ratio) remainder-bps))]
      (max fallback blended))
    fallback))

(defn- finite-nonzero?
  [value]
  (and (finite-number? value)
       (not (zero? value))))

(defn- size-decimals
  [instrument]
  (some (fn [k]
          (let [value (get instrument k)]
            (cond
              (and (integer? value) (not (neg? value))) value
              (and (number? value) (not (neg? value))) (js/Math.floor value)
              :else nil)))
        [:szDecimals :sz-decimals :size-decimals :quantity-decimals]))

(defn- min-quantity
  [instrument]
  (some (fn [k]
          (let [value (get instrument k)]
            (when (finite-positive? value)
              value)))
        [:min-size :minSize :minSz :min-quantity]))

(defn- round-down-quantity
  [quantity decimals]
  (if (and (finite-positive? quantity)
           (integer? decimals))
    (let [scale (js/Math.pow 10 decimals)]
      (/ (js/Math.floor (* quantity scale)) scale))
    quantity))

(defn- executable-quantity
  [instrument price delta-notional-usd]
  (when (and (finite-positive? price)
             (finite-nonzero? delta-notional-usd))
    (let [raw-quantity (/ (abs-num delta-notional-usd) price)
          rounded (round-down-quantity raw-quantity (size-decimals instrument))
          min-size (min-quantity instrument)]
      (if (and (finite-positive? min-size)
               (< rounded min-size))
        0
        rounded))))

(defn- cost-metadata
  [context]
  (select-keys context [:age-ms :stale? :observed-at-ms :loaded-at-ms :received-at-ms]))

(defn- side-touch-price
  "Best (touch) price on the fill side of a snapshot/orderbook context: the first ask level
   for a buy, the first bid level for a sell, falling back to best-ask/best-bid."
  [context side]
  (or (level-price (first (snapshot-side-levels context side)))
      (orderbook-fill-price context side)))

(defn- cost-split
  "Decompose a total price cost (bps) into spread crossing (the touch vs the reference mark)
   and book impact (the residual of walking past the touch). Both floor at 0 and spread is
   capped at the total, so spread + impact = total. Returns nil when there is no real touch
   to split against (flat fallback / no book)."
  [side reference-price total-bps touch-price]
  (when (and (finite-number? total-bps)
             (finite-positive? touch-price))
    (let [spread (min (or (slippage-bps-from-fill-price side reference-price touch-price) 0)
                      total-bps)
          impact (max 0 (- total-bps spread))]
      {:spread-bps spread
       :impact-bps impact})))

(defn- cost-context
  [opts instrument-id side reference-price quantity]
  (let [fallback (or (:fallback-slippage-bps opts)
                     default-fallback-slippage-bps)
        context (get-in opts [:cost-contexts-by-id instrument-id])
        source (case (:source context)
                 :fallback-cost-assumption :fallback-bps
                 nil :fallback-bps
                 (:source context))
        snapshot-fill (visible-depth-fill (snapshot-side-levels context side) quantity)
        fill-price (orderbook-fill-price context side)
        metadata (cost-metadata context)]
    (cond
      (:slippage-bps context)
      (merge metadata
             {:source source
              :estimated-fill-price (or fill-price reference-price)
              :slippage-bps (:slippage-bps context)})

      (= :full-visible-depth (:depth-status snapshot-fill))
      (let [estimated-fill-price (:estimated-fill-price snapshot-fill)]
        (if (implausible-favorable-fill? side reference-price estimated-fill-price)
          (untrusted-fill-cost metadata reference-price fallback)
          (merge metadata
                 {:source source
                  :estimated-fill-price estimated-fill-price
                  :touch-price (side-touch-price context side)
                  :slippage-bps (or (slippage-bps-from-fill-price side
                                                                   reference-price
                                                                   estimated-fill-price)
                                    fallback)
                  :depth-status :full-visible-depth})))

      (= :insufficient-visible-depth (:depth-status snapshot-fill))
      ;; Overrun estimates are FLOORS, not point estimates: the penalty on the portion
      ;; beyond the visible book is capped (see depth-limited-slippage-bps), so the
      ;; number is a lower bound. :depth-overrun/:depth-coverage let surfaces disclose
      ;; how much of the order the visible book actually covers.
      (let [{:keys [visible-size visible-notional]} snapshot-fill
            overrun (when (and (finite-positive? quantity)
                               (finite-positive? visible-size))
                      (/ quantity visible-size))]
        (merge metadata
               {:source :depth-extrapolated
                :estimated-fill-price reference-price
                :touch-price (side-touch-price context side)
                :slippage-bps (depth-limited-slippage-bps side
                                                          reference-price
                                                          quantity
                                                          snapshot-fill
                                                          fallback)
                :depth-status :insufficient-visible-depth
                :fallback-reason :snapshot-depth-limited
                :estimate-floor? true
                :visible-notional-usd visible-notional
                :depth-overrun overrun
                :depth-coverage (when overrun (/ 1 overrun))}))

      (implausible-favorable-fill? side reference-price fill-price)
      (untrusted-fill-cost metadata reference-price fallback)

      :else
      (merge metadata
             {:source source
              :estimated-fill-price (or fill-price reference-price)
              :touch-price fill-price
              :slippage-bps (or (orderbook-slippage-bps context side reference-price)
                                fallback)}))))

(defn- cost-estimate
  [opts instrument-id side reference-price delta-notional-usd quantity]
  (let [{:keys [source slippage-bps estimated-fill-price touch-price]
         :as context} (cost-context opts
                                    instrument-id
                                    side
                                    reference-price
                                    quantity)
        fee-bps (or (get-in opts [:fee-bps-by-id instrument-id])
                    (:default-fee-bps opts)
                    0)
        ;; Maker fee for the same notional: a resting (limit/passive) order fills as a
        ;; maker, so the Execution tab can show the lower fee when a row is routed that way.
        ;; A missing assumption is UNKNOWN, never 0: a resting row pays no spread or impact,
        ;; so coercing its fee to zero would render a confident "$0.00 all-in" for a real
        ;; order. Callers pass contracts.constants/maker-fee-bps; the keys stay off the cost
        ;; map otherwise and the surfaces render "—".
        maker-fee-bps (:maker-fee-bps opts)
        notional (abs-num delta-notional-usd)
        ;; Price cost (:slippage-bps) already bundles crossing the spread and walking the
        ;; book, so split it at the touch: spread = touch vs mark, impact = the residual.
        ;; nil when there is no real book to split against (flat fallback / prebaked).
        {:keys [spread-bps impact-bps]} (cost-split side reference-price slippage-bps touch-price)]
    (cond-> (merge context
                   {:source source
                    :estimated-fill-price estimated-fill-price
                    :notional-usd notional
                    :slippage-bps slippage-bps
                    :estimated-slippage-usd (* notional (/ slippage-bps 10000))
                    :fee-bps fee-bps
                    :estimated-fee-usd (* notional (/ fee-bps 10000))})
      (finite-number? maker-fee-bps)
      (assoc :maker-fee-bps maker-fee-bps
             :maker-fee-usd (* notional (/ maker-fee-bps 10000)))

      (some? spread-bps) (assoc :spread-bps spread-bps
                                :impact-bps impact-bps
                                :spread-usd (* notional (/ spread-bps 10000))
                                :impact-usd (* notional (/ impact-bps 10000))))))

;; ── TWAP cost model ───────────────────────────────────────────────────────
;; A TWAP order slices a parent order into suborders spaced over time, letting the book
;; refill between clips -- so its estimated book impact must sit well below one full-size
;; market order. Pure estimate-side counterparts of the venue mechanics in trading.core,
;; whose clip spacing derives from BOTH the runtime and the notional since 2026-08-01.

(defn twap-suborder-count
  "UPPER BOUND on a TWAP's clips: what the venue runs when every clip can sit at the
   twap-suborder-interval-seconds floor (runtime floored at the venue minimum). Blind to
   notional -- see twap-venue-suborder-count. Mirrors trading.core/twap-suborder-count."
  [minutes]
  (let [m (max twap-min-runtime-minutes
               (js/Math.floor (if (finite-positive? minutes) minutes 0)))]
    (inc (js/Math.round (/ (* 60 m) twap-suborder-interval-seconds)))))

(defn twap-venue-suborder-count
  "Clips the venue ACTUALLY works a TWAP of this runtime and notional as: as often as the
   spacing floor allows, but never so often that a clip falls below min-order-notional-usd
   (and never fewer than 2). Fails open to the notional-blind bound when the notional is
   unusable, so a cost map without one prices as before. Mirrors trading.core's namesake."
  [minutes total-notional]
  (let [spacing-bound (twap-suborder-count minutes)]
    (if (finite-positive? total-notional)
      (max 2 (min spacing-bound (js/Math.floor (/ total-notional min-order-notional-usd))))
      spacing-bound)))

(defn twap-cost
  "Sliced-execution price-cost estimate for working a row's order as a venue TWAP over
   `minutes`, from the row's one-shot cost map (the full-size book-walk stamped by
   cost-estimate). The order splits into n = twap-venue-suborder-count clips and the book
   REFILLS between them, so each clip pays ~1/n of the one-shot impact (linear ladder),
   but phi = twap-permanent-impact-fraction of every clip's impact is permanent and
   accumulates against later clips (a clip absorbs (n-1)/2 prior shifts on average):

     twap-impact = impact/n + phi * impact * (n-1) / (2n)   (never above the one-shot)
     twap-slip   = spread + twap-impact                     (every clip crosses the spread)

   Continuity: n=1 reproduces the one-shot cost; the n->inf asymptote is spread +
   phi/2 * impact. A cost with no spread/impact split (flat fallback, prebaked bps,
   untrusted snapshot) returns unchanged with :twap-adjusted? false -- a flat assumption
   has no impact component to slice, and discounting it would fabricate precision. Floor
   semantics (:estimate-floor?) are inherited, and a clip that still exceeds the visible
   book additionally flags :slice-exceeds-visible-depth? true."
  [cost minutes]
  (let [{:keys [spread-bps impact-bps notional-usd visible-notional-usd
                slippage-bps estimated-slippage-usd estimate-floor?]} cost
        n (twap-venue-suborder-count minutes notional-usd)]
    (if-not (and (finite-number? spread-bps)
                 (finite-number? impact-bps))
      {:twap-adjusted? false
       :suborders n
       :slippage-bps slippage-bps
       :estimated-slippage-usd estimated-slippage-usd
       :estimate-floor? (boolean estimate-floor?)}
      (let [phi twap-permanent-impact-fraction
            twap-impact (min impact-bps
                             (+ (/ impact-bps n)
                                (/ (* phi impact-bps (dec n)) (* 2 n))))
            twap-slip (+ spread-bps twap-impact)
            notional (or notional-usd 0)
            slice-notional (/ notional n)
            slice-overrun? (boolean (and (finite-positive? visible-notional-usd)
                                         (finite-positive? slice-notional)
                                         (> slice-notional visible-notional-usd)))]
        {:twap-adjusted? true
         :suborders n
         :slippage-bps twap-slip
         :estimated-slippage-usd (* notional (/ twap-slip 10000))
         :spread-bps spread-bps
         :spread-usd (* notional (/ spread-bps 10000))
         :impact-bps twap-impact
         :impact-usd (* notional (/ twap-impact 10000))
         :estimate-floor? (boolean estimate-floor?)
         :slice-exceeds-visible-depth? slice-overrun?}))))

(defn- row-status
  [{:keys [rebalance-tolerance]} instrument price capital-usd delta-weight delta-notional-usd quantity]
  (cond
    (<= (abs-num delta-weight) (or rebalance-tolerance 0))
    ;; Carry the band so downstream surfaces can say WHY the leg is skipped
    ;; ("within 3.0 pp band") instead of a bare unexplained "skipped".
    {:status :within-tolerance
     :tolerance (or rebalance-tolerance 0)}

    (nil? instrument)
    {:status :blocked
     :reason :market-metadata-missing}

    (not (finite-positive? capital-usd))
    {:status :blocked
     :reason :missing-capital-base}

    (not (finite-positive? price))
    {:status :blocked
     :reason :missing-price}

    (not (finite-nonzero? delta-notional-usd))
    {:status :blocked
     :reason :zero-delta-notional}

    (< (abs-num delta-notional-usd) min-order-notional-usd)
    {:status :blocked
     :reason :below-min-notional}

    (not (finite-positive? quantity))
    {:status :blocked
     :reason :quantity-below-lot}

    :else
    {:status :ready}))

(defn- excluded-row
  ;; The optimizer expressed NO target for this held instrument (out of the optimized
  ;; universe, dropped by the allocator, or spot excluded from the run). A missing
  ;; target is not a 0% target: the row is held as-is, never staged as a sell-to-zero.
  [opts instrument-id current-weight]
  (let [instrument (get-in opts [:instruments-by-id instrument-id])]
    {:instrument-id instrument-id
     :instrument-type (:instrument-type instrument)
     :coin (:coin instrument)
     :current-weight (or current-weight 0)
     :target-weight nil
     :delta-weight 0
     :delta-notional-usd 0
     :side :none
     :price (get-in opts [:prices-by-id instrument-id])
     :quantity nil
     :status :excluded
     :reason :excluded-from-optimization}))

(defn- rebalance-row
  [opts instrument-id current-weight target-weight]
  (if (nil? target-weight)
    (excluded-row opts instrument-id current-weight)
    (let [instrument (get-in opts [:instruments-by-id instrument-id])
          price (get-in opts [:prices-by-id instrument-id])
          capital-usd (or (:capital-usd opts) 0)
          delta-weight (- target-weight current-weight)
          delta-notional-usd (* capital-usd delta-weight)
          quantity (executable-quantity instrument price delta-notional-usd)
          status (row-status opts instrument price capital-usd delta-weight delta-notional-usd quantity)
          side (side-for (if (finite-nonzero? delta-notional-usd)
                           delta-notional-usd
                           delta-weight))]
      (merge {:instrument-id instrument-id
              :instrument-type (:instrument-type instrument)
              :coin (:coin instrument)
              :current-weight current-weight
              :target-weight target-weight
              :delta-weight delta-weight
              :delta-notional-usd delta-notional-usd
              :side side
              :price price
              :quantity quantity}
             status
             (when (= :ready (:status status))
               {:cost (cost-estimate opts
                                     instrument-id
                                     side
                                     price
                                     delta-notional-usd
                                     quantity)})))))

(defn- preview-status
  [rows]
  (let [ready? (boolean (some #(= :ready (:status %)) rows))
        blocked? (boolean (some #(= :blocked (:status %)) rows))]
    (cond
      (and ready? blocked?) :partially-blocked
      blocked? :blocked
      ready? :ready
      :else :no-op)))

(defn- leverage-for-row
  [opts row]
  (or (let [value (get-in opts [:leverage-by-id (:instrument-id row)])]
        (when (finite-positive? value) value))
      ;; No explicit cap: charge at the account's real average leverage, not 1x.
      (:default-leverage opts)
      1))

(defn- margin-impact-usd
  [opts row]
  (if (and (= :ready (:status row))
           (= :perp (:instrument-type row)))
    (/ (abs-num (:delta-notional-usd row))
       (leverage-for-row opts row))
    0))

(defn- utilization
  [used total]
  (when (finite-positive? total)
    (/ (or used 0) total)))

(defn- margin-warning
  [after-utilization]
  (cond
    (and (finite-number? after-utilization)
         (> after-utilization 1)) :exceeds-equity
    (and (finite-number? after-utilization)
         (> after-utilization 0.8)) :high-utilization
    :else nil))

(defn- account-effective-leverage
  ;; Account average leverage; nil for an all-spot / no-position book (caller → 1x).
  [gross-usd margin-used-usd]
  (when (and (finite-positive? gross-usd) (finite-positive? margin-used-usd))
    (/ gross-usd margin-used-usd)))

(defn- gross-leverage
  [gross-usd capital-usd]
  (when (finite-positive? capital-usd)
    (/ (or gross-usd 0) capital-usd)))

(defn- ready-gross-delta-usd ;; Δgross from the ready (will-trade) rows.
  [capital-usd ready-rows]
  (reduce + 0 (map (fn [{:keys [current-weight target-weight]}]
                     (* capital-usd (- (abs-num target-weight) (abs-num current-weight))))
                   ready-rows)))

(defn- venue-leverage
  ;; Venue lens (the trade page's Unified Account Leverage): perp-only notional ÷
  ;; collateral, vs the portfolio lens above. nil when inputs are missing.
  [perp-gross-usd collateral-usd]
  (when (and (finite-number? perp-gross-usd)
             (finite-positive? collateral-usd))
    (/ (max 0 perp-gross-usd) collateral-usd)))

(defn- margin-summary
  [opts ready-rows current-gross-usd]
  (let [capital-usd (:capital-usd opts)
        current-used (or (:current-margin-used-usdc opts) 0)
        impact (reduce + 0 (map #(margin-impact-usd opts %) ready-rows))
        after-used (+ current-used impact)
        after-gross-usd (+ (or current-gross-usd 0)
                           (ready-gross-delta-usd capital-usd ready-rows))
        after-utilization (utilization after-used capital-usd)
        current-perp-gross-usd (:current-perp-gross-usdc opts)
        venue-collateral-usd (:venue-collateral-usd opts)
        perp-ready-rows (filter #(= :perp (:instrument-type %)) ready-rows)
        after-perp-gross-usd (when (finite-number? current-perp-gross-usd)
                               (+ current-perp-gross-usd
                                  (ready-gross-delta-usd capital-usd perp-ready-rows)))]
    {:capital-usd capital-usd
     :current-used-usd current-used
     :estimated-impact-usd impact
     :after-used-usd after-used
     :free-margin-usd (- capital-usd after-used)
     :before-utilization (utilization current-used capital-usd)
     :after-utilization after-utilization
     :before-gross-leverage (gross-leverage current-gross-usd capital-usd)
     :after-gross-leverage (gross-leverage after-gross-usd capital-usd)
     :before-venue-leverage (venue-leverage current-perp-gross-usd venue-collateral-usd)
     :after-venue-leverage (venue-leverage after-perp-gross-usd venue-collateral-usd)
     :warning (margin-warning after-utilization)}))

(defn build-rebalance-preview
  [{:keys [instrument-ids current-weights target-weights] :as opts}]
  (let [rows (mapv (fn [instrument-id current-weight target-weight]
                     (rebalance-row opts instrument-id current-weight target-weight))
                   instrument-ids
                   current-weights
                   target-weights)
        ready-rows (filter #(= :ready (:status %)) rows)
        capital-usd (:capital-usd opts)
        ;; Account-wide current gross when supplied; else the universe rows' own gross.
        current-gross-usd (or (:current-gross-exposure-usdc opts)
                              (reduce + 0 (map #(* capital-usd (abs-num (:current-weight %))) rows)))
        opts (assoc opts :default-leverage
                    (account-effective-leverage current-gross-usd
                                                (:current-margin-used-usdc opts)))]
    {:status (preview-status rows)
     :capital-usd capital-usd
     :rows rows
     :summary {:ready-count (count (filter #(= :ready (:status %)) rows))
               :blocked-count (count (filter #(= :blocked (:status %)) rows))
               :within-tolerance-count (count (filter #(= :within-tolerance (:status %)) rows))
               :excluded-count (count (filter #(= :excluded (:status %)) rows))
               :gross-trade-notional-usd (reduce + 0 (map #(abs-num (:delta-notional-usd %)) ready-rows))
               :estimated-fees-usd (reduce + 0 (map #(or (get-in % [:cost :estimated-fee-usd]) 0) ready-rows))
               :estimated-slippage-usd (reduce + 0 (map #(or (get-in % [:cost :estimated-slippage-usd]) 0) ready-rows))
               :margin (margin-summary opts ready-rows current-gross-usd)}}))
