(ns hyperopen.portfolio.optimizer.domain.exposure-reachability
  "Gross/net exposure ENCODING helpers, plus the joint net/gross reachability
  presolve that the per-constraint checks in `domain.constraints` cannot see.

  WHY A JOINT CHECK EXISTS. `constraints/violations` tests the net band and the
  gross window INDEPENDENTLY, and every one of those tests can pass while no
  weight vector satisfies both at once. The infeasible request then reaches the
  solver, and the post-solve validator blames the solver for a request that was
  never solvable.

  THE MATH. Write L(w) = sum max(0, w_i) (long notional) and
  S(w) = sum max(0, -w_i) (short notional). Then, with no assumption about
  signs, net(w) = L - S and gross(w) = L + S, hence the two identities

      net = gross - 2*S        net = 2*L - gross

  Because max(0, x) is non-decreasing in x and max(0, -x) is non-increasing, the
  box [l_i, u_i] pins L into [L_min, L_max] = [sum max(0,l_i), sum max(0,u_i)]
  and S into [S_min, S_max] = [sum max(0,-u_i), sum max(0,-l_i)] - note the l/u
  CROSSOVER, a more negative lower bound buys MORE short notional. Feeding those
  and the encoded gross window [Glo, Ghi] through the identities bounds net from
  both ends; an interval disjoint from the net band is provably infeasible.

  The bug this was written for: a single-signed book whose only short-side assets
  carry 3% and 5% history-assumption caps has S_max = 0.08, so a 3.995x gross
  floor forces net >= 3.835x while the net band allowed at most ~1.36x. Each
  check passed alone; jointly nothing was reachable.

  SOUNDNESS. Every bound in the DETECTION is an OUTER (relaxing) one - the
  product box [L_min,L_max] x [S_min,S_max] is a superset of the reachable (L,S)
  set, and the band widening uses the largest gross the window permits. The check
  can only UNDER-detect; it never calls a feasible request infeasible. It is a
  necessary condition, never a sufficient one, so remediation copy must say a
  change 'clears this check', never that it makes the run feasible.

  REMEDIATION CARRIES THE OPPOSITE BURDEN, and therefore does NOT reuse the
  relaxed bound: a value the panel writes has to leave a portfolio the solver can
  reach, or the trader clicks a fix and lands on the same banner. Every such
  number here comes from `domain.exposure-feasibility`, which solves the encoded
  rows exactly on the fixed-sign box the gross floor and net band both require."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.exposure-feasibility
             :as feasibility]
            [hyperopen.portfolio.optimizer.domain.exposure-policy
             :as exposure-policy]))

(def ^:private finite-number? coercion/finite-number?)

;; ---------------------------------------------------------------------------
;; Gross-floor / net-band encoding helpers, extracted from domain.constraints
;; (whose size exception names this move) so the reachability math below can
;; share them without a circular require.
;; ---------------------------------------------------------------------------

(defn straddles-zero?
  "True when an encoded bound pair leaves the asset free to sit on EITHER side of
   zero: the pairs `gross-floor-signs` refuses to sign."
  [lower upper]
  (not (or (and (number? lower) (>= lower 0))
           (and (number? upper) (<= upper 0)))))

(defn gross-floor-signs
  "Per-asset gross sign (+1 long-side, -1 short-side) when every encoded bound
   pair is single-signed. Returns nil when any asset straddles zero (lower < 0
   and upper > 0): on a two-sided bound, gross = sum |w_i| is NOT a linear
   function of the weights, so a gross floor would be non-convex/unsound and must
   be dropped.

   Through the production path this never returns nil. `contracts.migrations`
   stamps :position-side :long or :short on EVERY universe instrument, and
   `request_builder/build-engine-request` runs migrate-draft before it builds the
   universe that reaches `encode-constraints`, so `bounds-for` always produces a
   single-signed box. The nil arm is a SOUNDNESS guard on this function's own
   contract (a caller-built box may straddle), not a state the app can reach."
  [lower-bounds upper-bounds]
  (reduce (fn [signs [lower upper]]
            (cond
              (straddles-zero? lower upper) (reduced nil)
              (and (number? lower) (>= lower 0)) (conj signs 1)
              :else (conj signs -1)))
          []
          (map vector lower-bounds upper-bounds)))

(defn gross-floor-spec
  "Encoded gross floor {:min G :signs [...]} when a finite :gross-floor is
   requested AND every asset is single-signed; nil otherwise (floor dropped). The
   signed coefficients make the floor the convex linear row sum(sign_i*w_i) >= G,
   which equals true gross only on the fixed-sign region the bounds carve out."
  [constraints signs]
  (let [floor (:gross-floor constraints)]
    (when (and (finite-number? floor) signs)
      {:min floor :signs signs})))

(defn net-band-pct-value
  "Active net-band percentage (decimal fraction of realized gross), or nil when
  unset/zero. Above 1 adds nothing beyond |net| <= gross, so cap there."
  [constraints]
  (let [pct (:net-band-pct constraints)]
    (when (and (finite-number? pct) (pos? pct))
      (min pct 1.0))))

(defn net-band-encodable?
  "Whether a percentage net band CAN be encoded for this request, independent of
   the pct currently set. This is a CAPABILITY, not a reading of the stored value:
   `defaults.cljs` ships :net-band-pct 0.0, so keying the panel's widen-the-band
   remediation on `(some? band-spec)` hid it from every trader who had not already
   set a band - which is almost everyone, and exactly the traders the suggestion
   exists for. The encoding itself still requires a positive pct (see
   `net-band-spec`); this only says the control is live."
  [constraints signs]
  (and (some? signs)
       (not (:long-only? constraints))))

(defn net-band-spec
  "Encoded percentage net band {:pct q :signs [...] :min nmin :max nmax}: the
   solver permits net-min - q*gross(w) <= net(w) <= net-max + q*gross(w) using
   the SAME signed-linear gross representation as the gross floor (realized
   gross, never the gross target). Requires every asset single-signed (mixed-sign
   bounds make gross non-linear and the coupled rows unsound), a finite net bound
   to widen, and NOT long-only. nil otherwise - the band is then dropped and the
   exact net bounds stay in force, which is strictly conservative."
  [constraints signs]
  (let [pct (net-band-pct-value constraints)
        net-exposure (:net-exposure constraints)
        nmin (:min net-exposure)
        nmax (:max net-exposure)]
    (when (and pct
               (net-band-encodable? constraints signs)
               (or (finite-number? nmin) (finite-number? nmax)))
      (cond-> {:pct pct :signs signs}
        (finite-number? nmin) (assoc :min nmin)
        (finite-number? nmax) (assoc :max nmax)))))

(defn net-band-warnings
  "Explains a requested-but-unapplied percentage net band: with mixed-sign bounds
   the tolerance cannot be encoded, so the solver holds the exact net target
   instead - never a band violation, but tighter than asked."
  [constraints signs]
  (if (and (net-band-pct-value constraints)
           (nil? signs)
           (not (:long-only? constraints)))
    [{:code :net-band-requires-fixed-sides
      :message (str "The net band (% of gross) needs every asset assigned to a "
                    "long or short side; holding the exact net target instead.")}]
    []))

(defn gross-capacity
  "Largest gross the box can physically reach: sum of max(|lower|, |upper|)."
  [lower-bounds upper-bounds]
  (reduce + 0
          (map (fn [lower upper]
                 (max (if (number? lower) (js/Math.abs lower) 0)
                      (if (number? upper) (js/Math.abs upper) 0)))
               lower-bounds
               upper-bounds)))

;; --- Joint net/gross reachability presolve ---------------------------------

(def ^:private reachability-epsilon
  "Float slack. Encodings land EXACTLY on the band edge (0.55 + 0.25 + 0.2 versus
  a net target of 1); a cap mix summing to 0.9999999999999999 is not infeasible."
  1e-9)

(def ^:private binding-capacity-limit 5)

(def ^:private display-scale
  "Panel precision: leverage renders to 2 decimals, so that is the grid any
  number a trader can TYPE BACK has to sit on."
  100)

(defn- snap-down
  [value]
  (/ (js/Math.floor (+ (* value display-scale) reachability-epsilon))
     display-scale))

(defn- snap-up
  [value]
  (/ (js/Math.ceil (- (* value display-scale) reachability-epsilon))
     display-scale))

(defn- fmt-x
  [value]
  (str (.toFixed value 2) "x"))

;; Every leverage the messages quote is rounded so the printed number cannot
;; overstate the trader's room: capacity and ceilings DOWN, forced minimums UP,
;; and a gross floor DOWN so the quoted floor is never higher than the one
;; actually set (retyping it can then only loosen the request, never tighten it).
(defn- fmt-x-down
  [value]
  (fmt-x (snap-down value)))

(defn- fmt-x-up
  [value]
  (fmt-x (snap-up value)))

(defn- driver-for
  "First candidate [driver value] that attains `extreme`. Ordered so a tie names
  the box bounds, not a gross term that only coincidentally matches."
  [candidates extreme]
  (or (some (fn [[driver value]]
              (when (<= (js/Math.abs (- value extreme)) reachability-epsilon)
                driver))
            candidates)
      (ffirst candidates)))

(defn- gross-driven?
  [driver]
  (not (contains? #{:box-lower-bounds :box-upper-bounds} driver)))

(defn- binding-capacity
  "Ranked per-asset capacity on the scarce side: how much short notional each
  asset's lower bound can supply (:short), or long notional its upper bound can
  (:long). Zero-capacity members are counted, not listed. Ids are padded so a
  short/absent id list cannot truncate the bounds and undercount them."
  [instrument-ids lower-bounds upper-bounds side]
  (let [rows (mapv (fn [id lower upper]
                     {:instrument-id id
                      :capacity (if (= :short side)
                                  (max 0 (- lower))
                                  (max 0 upper))})
                   (concat instrument-ids (repeat nil))
                   lower-bounds
                   upper-bounds)]
    {:rows (->> rows
                (filter #(pos? (:capacity %)))
                (sort-by :capacity >)
                (take binding-capacity-limit)
                vec)
     :zero-count (count (filterv #(not (pos? (:capacity %))) rows))}))

(defn- positive-or-nil
  [value]
  (when (and (finite-number? value) (pos? value))
    value))

(defn- above-band-message
  [driver {:keys [glo ghi l-min s-max widened-max reachable-display]}]
  (str (case driver
         :gross-floor-vs-short-capacity
         (str "A " (fmt-x-down glo) " gross floor with only " (fmt-x-down s-max)
              " of short capacity forces")

         :forced-long-vs-gross-max
         (str "Required long positions of " (fmt-x-up l-min) " under a "
              (fmt-x-down ghi) " gross cap force")

         "The selected assets' minimum weights force")
       ;; The reachable end is quoted from the INWARD-snapped interval, never
       ;; from the exact one: rounded to nearest, a lower bound rounds outward
       ;; and a trader typing the quoted number re-fires this very check.
       (if-let [lo (:min reachable-display)]
         (str " net to at least " (fmt-x lo) ", above the ")
         " net above the ")
       (fmt-x-down widened-max) " the net band allows."))

(defn- below-band-message
  [driver {:keys [glo ghi l-max s-min widened-min reachable-display]}]
  (str (case driver
         :gross-max-vs-short-floor
         (str "A " (fmt-x-down ghi) " gross cap against " (fmt-x-up s-min)
              " of forced short exposure holds")

         :gross-floor-vs-long-capacity
         (str "A " (fmt-x-down glo) " gross floor with only " (fmt-x-down l-max)
              " of long capacity holds")

         "The selected assets' maximum weights hold")
       (if-let [hi (:max reachable-display)]
         (str " net at or below " (fmt-x hi) ", under the ")
         " net below the ")
       (fmt-x-up widened-min) " the net band requires."))

(defn- snap-inward
  "[lo, hi] re-expressed at display precision and moved INWARD (min up, max
  down). The panel quotes this interval and offers 'set net to <end>'; an
  OUTWARD-rounded end sits back outside the reachable set, so typing the quoted
  number re-fires this very check. nil when no 2-decimal value lies inside at
  all (a window narrower than 0.01x), so the panel can fall back to copy."
  [lo hi]
  (when (and (finite-number? lo) (finite-number? hi))
    (let [lo* (snap-up lo)
          hi* (snap-down hi)]
      (when (<= lo* hi*)
        {:min lo* :max hi*}))))

(defn- common-fields
  "The payload keys EVERY consumer reads. Nothing else is emitted: a diagnostic
  nobody reads still crosses the worker boundary on every infeasible run, and
  the slack-derived ones (the q*Ghi-widened net bounds, a 'suggested' net edge)
  carried the relaxed bound into numbers that read like remediation."
  [{:keys [glo ghi q net-min net-max net-lo net-hi gross-floor
           long-only? net-band-encodable? reachable-display]}]
  {:code :net-unreachable-given-sides
   :reachable-net {:min net-lo :max net-hi}
   ;; Quote THIS one to the trader; :reachable-net is the exact math.
   :reachable-net-display reachable-display
   ;; Which remediations can actually move anything: long-only? pins net at 1
   ;; (constraints/target-net) so finite-net-limits ignores :net-exposure, and
   ;; an unencodable band is silently dropped. Offering those controls
   ;; regardless would ship guaranteed no-op buttons.
   :long-only? (boolean long-only?)
   :net-band-encodable? (boolean net-band-encodable?)
   :net-bounds (cond-> {}
                 (finite-number? net-min) (assoc :min net-min)
                 (finite-number? net-max) (assoc :max net-max))
   :net-band-pct q
   :gross-floor gross-floor
   :gross-window {:min glo :max ghi}})

(defn- remediation-thresholds
  "The two WRITEABLE numbers, both solved against the exact encoded rows rather
  than the presolve's relaxed band.

  :max-feasible-gross-floor is the largest floor that still leaves a reachable
  portfolio - and, when the whole window is empty, absent, which tells the panel
  that lowering OR clearing the floor fixes nothing. It is emitted only when a
  floor is actually ENCODED: on a mixed-sign box the floor is dropped, the
  rectangle bound is no longer exact, and no sufficient claim can be made.

  :min-feasible-net-band-pct is the smallest whole-percent band that rescues the
  floor as it stands, emitted only where the band can be encoded at all."
  [{:keys [geometry net-window glo gross-floor net-band-encodable?]}]
  (cond-> {}
    (some? gross-floor)
    (assoc :max-feasible-gross-floor
           (feasibility/max-feasible-gross-floor geometry net-window))

    net-band-encodable?
    (assoc :min-feasible-net-band-pct
           (feasibility/min-feasible-net-band-pct
            geometry
            net-window
            glo
            exposure-policy/max-net-band-pct))))

(defn- above-band-violation
  "Net forced ABOVE the band: net = gross - 2*S, and short capacity is too small
  to pull a mandated gross down to the requested net."
  [{:keys [instrument-ids lower-bounds upper-bounds sum-lower
           glo ghi l-min s-max net-lo widened-max]
    :as ctx}]
  (let [driver (driver-for [[:box-lower-bounds sum-lower]
                            [:gross-floor-vs-short-capacity (- glo (* 2 s-max))]
                            [:forced-long-vs-gross-max (- (* 2 l-min) ghi)]]
                           net-lo)
        {:keys [rows zero-count]} (binding-capacity instrument-ids
                                                    lower-bounds
                                                    upper-bounds
                                                    :short)]
    (merge (common-fields ctx)
           (remediation-thresholds ctx)
           {:constraint-code (if (gross-driven? driver) :gross-exposure :net-exposure)
            :direction :net-above-band
            ;; Not consumed in src: kept as the one field that names WHICH of the
            ;; three bounds produced the message, which is what an incident starts
            ;; from when the copy reads wrong.
            :driver driver
            :required-short-notional (positive-or-nil (/ (- glo widened-max) 2))
            :available-short-notional s-max
            :binding-capacity rows
            :zero-capacity-count zero-count
            :message (above-band-message driver ctx)})))

(defn- below-band-violation
  "Net forced BELOW the band: net = 2*L - gross, and long capacity cannot carry
  the requested net against the gross window."
  [{:keys [instrument-ids lower-bounds upper-bounds sum-upper
           glo ghi l-max s-min net-hi widened-min]
    :as ctx}]
  (let [driver (driver-for [[:box-upper-bounds sum-upper]
                            [:gross-max-vs-short-floor (- ghi (* 2 s-min))]
                            [:gross-floor-vs-long-capacity (- (* 2 l-max) glo)]]
                           net-hi)
        {:keys [rows zero-count]} (binding-capacity instrument-ids
                                                    lower-bounds
                                                    upper-bounds
                                                    :long)]
    (merge (common-fields ctx)
           (remediation-thresholds ctx)
           {:constraint-code (if (gross-driven? driver) :gross-exposure :net-exposure)
            :direction :net-below-band
            :driver driver
            :required-long-notional (positive-or-nil (/ (+ glo widened-min) 2))
            :available-long-notional l-max
            :binding-capacity rows
            :zero-capacity-count zero-count
            :message (below-band-message driver ctx)})))

(defn violations
  "Presolve violations for a net band no point of the box can reach given the
  encoded gross window. Returns [] (never a false positive) when the box is
  unusable, the gross window is empty, the reachable net interval is itself empty
  (a box/gross conflict the coarser codes own), or the band is inverted.

  `:gross-floor` MUST be the ENCODED floor (`(:min gross-floor-spec)`), not the
  requested `:gross-floor`: the floor is silently dropped for any universe with a
  straddling asset, and assuming it reaches the solver manufactures false
  infeasibles on every two-sided book. `:long-only?` and `:net-band-encodable?`
  pass through untouched - they steer remediation only, never the math."
  [{:keys [instrument-ids lower-bounds upper-bounds net-limits net-band-pct
           gross-floor gross-max long-only? net-band-encodable?]
    gross-capacity* :gross-capacity}]
  (let [capacity (if (finite-number? gross-capacity*)
                   gross-capacity*
                   (gross-capacity lower-bounds upper-bounds))
        geometry (feasibility/exposure-geometry {:lower-bounds lower-bounds
                                                 :upper-bounds upper-bounds
                                                 :gross-max gross-max
                                                 :gross-capacity capacity})]
    (if-not geometry
      []
      (let [{:keys [l-min l-max s-min s-max]} geometry
            ghi (:gross-ceiling geometry)
            sum-lower (reduce + 0 lower-bounds)
            sum-upper (reduce + 0 upper-bounds)
            ;; gross >= 0 is vacuous, so a dropped/absent floor relaxes to 0.
            glo (if (and (finite-number? gross-floor) (pos? gross-floor))
                  gross-floor
                  0)
            ;; DETECTION rides the widest slack the gross window permits, which
            ;; keeps the check a sound necessary condition. Remediation does not
            ;; reuse it; see `remediation-thresholds`.
            q (feasibility/band-pct net-band-pct)
            slack (* q ghi)
            net-min (:min net-limits)
            net-max (:max net-limits)
            widened-min (when (finite-number? net-min) (- net-min slack))
            widened-max (when (finite-number? net-max) (+ net-max slack))
            net-lo (max sum-lower (- glo (* 2 s-max)) (- (* 2 l-min) ghi))
            net-hi (min sum-upper (- ghi (* 2 s-min)) (- (* 2 l-max) glo))
            ctx {:instrument-ids (vec instrument-ids)
                 :lower-bounds lower-bounds
                 :upper-bounds upper-bounds
                 :geometry geometry
                 :net-window {:min net-min :max net-max :pct q}
                 :sum-lower sum-lower
                 :sum-upper sum-upper
                 :gross-floor gross-floor
                 :glo glo
                 :ghi ghi
                 :q q
                 :l-min l-min
                 :l-max l-max
                 :s-min s-min
                 :s-max s-max
                 :net-min net-min
                 :net-max net-max
                 :net-lo net-lo
                 :net-hi net-hi
                 :reachable-display (snap-inward net-lo net-hi)
                 :widened-min widened-min
                 :widened-max widened-max
                 :long-only? long-only?
                 :net-band-encodable? net-band-encodable?}]
        (cond
          (or (> glo (+ ghi reachability-epsilon))
              (> net-lo (+ net-hi reachability-epsilon)))
          []

          (and (some? widened-min)
               (some? widened-max)
               (> widened-min (+ widened-max reachability-epsilon)))
          []

          (and (some? widened-max)
               (> net-lo (+ widened-max reachability-epsilon)))
          [(above-band-violation ctx)]

          (and (some? widened-min)
               (< net-hi (- widened-min reachability-epsilon)))
          [(below-band-violation ctx)]

          :else
          [])))))
