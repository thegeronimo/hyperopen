(ns hyperopen.portfolio.optimizer.domain.constraints
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as exposure-policy]
            [hyperopen.portfolio.optimizer.domain.history-series :as history-series]))

(def default-max-asset-weight
  1)

(def unknown-sparse-history-max-weight
  0.2)

(def ^:private finite-number? coercion/finite-number?)

(defn- finite-nonnegative?
  [value]
  (and (finite-number? value)
       (not (neg? value))))

(defn- sparse-safety-max-weight
  [interval-count]
  (cond
    (not (number? interval-count)) nil
    (< interval-count 2) 0
    (< interval-count 8) 0.05
    (< interval-count 30) 0.1
    (< interval-count 60) 0.2
    :else nil))

(defn- instrument-id
  [instrument]
  (:instrument-id instrument))

(defn- normalized-market-type
  [instrument]
  (coercion/normalize-keyword-like
   (or (:instrument-type instrument)
       (:market-type instrument)
       (:optimizer-history/instrument-kind instrument))))

(defn- spot-instrument?
  [instrument]
  (let [id (instrument-id instrument)]
    (or (= :spot (normalized-market-type instrument))
        (and (string? id)
             (or (.startsWith id "spot:")
                 (.startsWith id "hl:spot:"))))))

(defn- vault-like-instrument?
  [instrument]
  (let [id (instrument-id instrument)
        market-type (normalized-market-type instrument)]
    (or (= :vault market-type)
        (some? (:vault-address instrument))
        (and (string? id)
             (.startsWith id "vault:")))))

(defn- id-set
  [values]
  (cond
    (nil? values) nil
    (set? values) values
    (sequential? values) (set values)
    :else #{values}))

(defn normalize-universe
  [universe constraints]
  (let [allowlist (id-set (:allowlist constraints))
        blocklist (or (id-set (:blocklist constraints)) #{})
        include-spot? (true? (:include-spot? constraints))]
    (->> universe
         (filter (fn [instrument]
                   (let [id (instrument-id instrument)]
                     (and (or (nil? allowlist)
                              (contains? allowlist id))
                          (not (contains? blocklist id))
                          (or include-spot?
                              (not (spot-instrument? instrument)))))))
         vec)))

(defn- cap-value
  [value]
  (when (finite-nonnegative? value)
    value))

(defn- cap-from
  [m specific-key fallback-keys]
  (some cap-value
        (map #(get m %) (cons specific-key fallback-keys))))

(defn- global-cap
  [constraints specific-key]
  (or (cap-from constraints specific-key [:max-weight :max-asset-weight])
      default-max-asset-weight))

(defn- scoped-cap
  [m specific-key]
  (cap-from m specific-key [:max-weight :max-asset-weight]))

(defn- min-caps
  [& caps]
  (apply min (filter finite-nonnegative? caps)))

(defn- max-long-weight-for
  [constraints instrument-id]
  (min-caps (global-cap constraints :max-long-weight)
            (scoped-cap (get-in constraints [:per-asset-overrides instrument-id])
                        :max-long-weight)
            (scoped-cap (get-in constraints [:per-perp-leverage-caps instrument-id])
                        :max-long-weight)))

(defn- max-short-weight-for
  [constraints instrument-id]
  (min-caps (global-cap constraints :max-short-weight)
            (scoped-cap (get-in constraints [:per-asset-overrides instrument-id])
                        :max-short-weight)
            (scoped-cap (get-in constraints [:per-perp-leverage-caps instrument-id])
                        :max-short-weight)))

(defn- instrument-shortable?
  [constraints instrument]
  (let [id (instrument-id instrument)
        override (get-in constraints [:per-asset-overrides id])]
    (cond
      (contains? (or override {}) :shortable?)
      (true? (:shortable? override))

      (contains? instrument :shortable?)
      (true? (:shortable? instrument))

      (vault-like-instrument? instrument)
      false

      (= :perp (normalized-market-type instrument))
      true

      :else
      false)))

(defn- position-side
  [instrument]
  (when (contains? instrument :position-side)
    (case (coercion/normalize-keyword-like (:position-side instrument))
      :short :short
      :long)))

(defn- native-cadence-for
  [history instrument-id]
  (or (get-in history [:cadence-by-instrument instrument-id])
      (when-let [rows (seq (get-in history [:raw-price-series-by-instrument instrument-id]))]
        (history-series/cadence-summary rows))))

(defn- aligned-cadence-for
  [history instrument-id]
  (when-let [rows (seq (get-in history [:price-series-by-instrument instrument-id]))]
    (history-series/cadence-summary rows)))

(defn- cadence-for
  [history instrument]
  (let [instrument-id (instrument-id instrument)]
    (or (native-cadence-for history instrument-id)
        (when (vault-like-instrument? instrument)
          {:kind :sparse
           :sparse? true
           :reason :missing-native-history-metadata})
        (aligned-cadence-for history instrument-id))))

(defn merge-max-weight-override
  [override max-weight]
  (let [override* (or override {})
        merge-cap (fn [existing]
                    (if (finite-nonnegative? existing)
                      (min existing max-weight)
                      max-weight))]
    (cond-> (assoc override*
                   :max-weight (merge-cap (:max-weight override*)))
      (contains? override* :max-long-weight)
      (update :max-long-weight merge-cap)

      (contains? override* :max-short-weight)
      (update :max-short-weight merge-cap))))

(defn- sparse-cap-warning
  [{:keys [instrument-id interval-count max-weight reason off-calendar?]}]
  {:code :sparse-history-weight-cap-applied
   :instrument-id instrument-id
   :interval-count interval-count
   :max-weight max-weight
   :message (cond
              (= :missing-native-history-metadata reason)
              (str "sparse history weight cap applied at "
                   (js/Math.round (* 100 max-weight))
                   "% because native cadence metadata is unavailable.")

              off-calendar?
              (str "sparse history weight cap applied at "
                   (js/Math.round (* 100 max-weight))
                   "% because its samples are too far apart to join the shared "
                   "daily window.")

              :else
              (str "sparse history weight cap applied at "
                   (js/Math.round (* 100 max-weight))
                   "% based on "
                   interval-count
                   " native intervals."))})

(defn- sparse-max-weight
  "Cap for one sparse member. `sparse-safety-max-weight`'s ladder terminates in
  nil at >= 60 intervals, which is correct for an on-calendar member (it shares
  the dense window, so its covariance is measured against everyone) but wrong for
  an OFF-CALENDAR one: that member's covariance comes from pairwise interval
  aggregation, and `risk-mixed-frequency/pair-estimate` returns exactly 0 for any
  pair with fewer than 2 shared intervals. Uncapped plus apparently-uncorrelated
  is what a max-Sharpe objective concentrates into, so an off-calendar member
  always carries at least the unknown-cadence cap."
  [{:keys [reason interval-count off-calendar?]}]
  (if (= :missing-native-history-metadata reason)
    unknown-sparse-history-max-weight
    (let [ladder (sparse-safety-max-weight interval-count)]
      (if (and off-calendar? (not (number? ladder)))
        unknown-sparse-history-max-weight
        ladder))))

(defn- runtime-sparse-caps
  [history universe]
  (->> universe
       (keep (fn [instrument]
               (let [instrument-id (instrument-id instrument)
                     cadence (cadence-for history instrument)
                     max-weight (when (:sparse? cadence)
                                  (sparse-max-weight cadence))]
                 (when (number? max-weight)
                   {:instrument-id instrument-id
                    :interval-count (:interval-count cadence)
                    :max-weight max-weight
                    :reason (:reason cadence)
                    :off-calendar? (boolean (:off-calendar? cadence))}))))
       vec))

(defn- locked?
  [constraints instrument-id]
  (contains? (or (id-set (:held-position-locks constraints)) #{})
             instrument-id))

(defn- current-weight
  [current-weights instrument-id]
  (or (get current-weights instrument-id) 0))

(defn- bounds-for
  [constraints current-weights instrument]
  (let [id (instrument-id instrument)
        max-long-weight (max-long-weight-for constraints id)
        max-short-weight (max-short-weight-for constraints id)
        shortable? (instrument-shortable? constraints instrument)
        side (position-side instrument)]
    (if (locked? constraints id)
      (let [weight (current-weight current-weights id)]
        {:lower weight
         :upper weight
         :locked {:instrument-id id
                  :weight weight}
         :locked-validation {:instrument-id id
                             :weight weight
                             :shortable? shortable?}})
      (if (:long-only? constraints)
        {:lower 0
         :upper max-long-weight}
        (case side
          :long
          {:lower 0
           :upper max-long-weight}

          :short
          (if shortable?
            {:lower (- max-short-weight)
             :upper 0}
            {:lower 0
             :upper max-long-weight})

          {:lower (if shortable? (- max-short-weight) 0)
           :upper max-long-weight})))))

(defn- target-net
  [constraints]
  (if (:long-only? constraints)
    1
    nil))

(defn- finite-net-limits
  [constraints]
  (let [target-net* (target-net constraints)
        net-exposure (:net-exposure constraints)
        net-min (:min net-exposure)
        net-max (:max net-exposure)]
    (if (finite-number? target-net*)
      {:min target-net*
       :max target-net*
       :target target-net*}
      (cond-> {}
        (finite-number? net-min)
        (assoc :min net-min)

        (finite-number? net-max)
        (assoc :max net-max)))))

(defn- minimum-required-gross
  [{net-min :min net-max :max target :target}]
  (cond
    (finite-number? target)
    (js/Math.abs target)

    (and (finite-number? net-min)
         (finite-number? net-max)
         (<= net-min 0 net-max))
    0

    (and (finite-number? net-min)
         (finite-number? net-max))
    (min (js/Math.abs net-min)
         (js/Math.abs net-max))

    (and (finite-number? net-min)
         (pos? net-min))
    net-min

    (and (finite-number? net-max)
         (neg? net-max))
    (js/Math.abs net-max)

    :else
    nil))

(defn- locked-gross
  [locked-weights]
  (reduce + 0 (map #(js/Math.abs (:weight %)) locked-weights)))

(defn- invalid-cap-violation
  [scope instrument-id field value]
  (cond-> {:code :invalid-weight-cap
           :scope scope
           :field field
           :value value}
    instrument-id
    (assoc :instrument-id instrument-id)))

(defn- invalid-cap-violations-for-map
  [scope instrument-id m]
  (->> [:max-long-weight :max-short-weight :max-weight :max-asset-weight]
       (keep (fn [field]
               (let [value (get m field)]
                 (when (and (contains? (or m {}) field)
                            (not (finite-nonnegative? value)))
                   (invalid-cap-violation scope instrument-id field value)))))
       vec))

(defn- invalid-cap-violations
  [constraints]
  (vec (concat
        (invalid-cap-violations-for-map :constraints nil constraints)
        (mapcat (fn [[instrument-id override]]
                  (invalid-cap-violations-for-map :per-asset-overrides
                                                  instrument-id
                                                  override))
                (:per-asset-overrides constraints))
        (mapcat (fn [[instrument-id cap]]
                  (invalid-cap-violations-for-map :per-perp-leverage-caps
                                                  instrument-id
                                                  cap))
                (:per-perp-leverage-caps constraints)))))

(defn- locked-short-violations
  [locked-weights]
  (->> locked-weights
       (keep (fn [{:keys [instrument-id weight shortable?]}]
               (when (and (finite-number? weight)
                          (neg? weight)
                          (not shortable?))
                 {:code :locked-short-non-shortable
                  :instrument-id instrument-id
                  :weight weight})))
       vec))

(defn- gross-floor-signs
  "Per-asset gross sign (+1 long-side, -1 short-side) when every encoded bound
   pair is single-signed. Returns nil when any asset straddles zero (lower < 0
   and upper > 0): on a two-sided bound, gross = sum |w_i| is NOT a linear
   function of the weights, so a gross floor would be non-convex/unsound and must
   be dropped. Seed-from-current pins every asset's :position-side, so every
   bound is single-signed and this returns a full sign vector."
  [lower-bounds upper-bounds]
  (reduce (fn [signs [lower upper]]
            (cond
              (and (number? lower) (>= lower 0)) (conj signs 1)
              (and (number? upper) (<= upper 0)) (conj signs -1)
              :else (reduced nil)))
          []
          (map vector lower-bounds upper-bounds)))

(defn- gross-floor-spec
  "Encoded gross floor {:min G :signs [...]} when a finite :gross-floor is
   requested AND every asset is single-signed; nil otherwise (floor dropped). The
   signed coefficients turn the floor into the convex linear inequality
   sum(sign_i * w_i) >= G, which equals true gross only on the fixed-sign region
   each asset's bounds carve out."
  [constraints signs]
  (let [floor (:gross-floor constraints)]
    (when (and (finite-number? floor) signs)
      {:min floor :signs signs})))

(defn- net-band-pct-value
  "The active net-band percentage (decimal fraction of realized gross), or nil
   when unset/zero. Values above 1 add nothing beyond |net| ≤ gross; cap there."
  [constraints]
  (let [pct (:net-band-pct constraints)]
    (when (and (finite-number? pct) (pos? pct))
      (min pct 1.0))))

(defn- net-band-spec
  "Encoded percentage net band {:pct q :signs [...] :min nmin :max nmax}: the
   solver permits net-min − q·gross(w) ≤ net(w) ≤ net-max + q·gross(w) using the
   SAME signed-linear gross representation as the gross floor (realized gross,
   never the gross target). Requires every asset single-signed — with mixed-sign
   bounds gross is not linear and the coupled rows would be unsound — and a
   finite net bound to widen. nil (band not applied; the exact net bounds stay
   in force, which is strictly conservative) otherwise."
  [constraints signs]
  (let [pct (net-band-pct-value constraints)
        net-exposure (:net-exposure constraints)
        nmin (:min net-exposure)
        nmax (:max net-exposure)]
    (when (and pct
               signs
               (not (:long-only? constraints))
               (or (finite-number? nmin) (finite-number? nmax)))
      (cond-> {:pct pct :signs signs}
        (finite-number? nmin) (assoc :min nmin)
        (finite-number? nmax) (assoc :max nmax)))))

(defn- net-band-warnings
  "Explains a requested-but-unapplied percentage net band: with mixed-sign
   bounds the tolerance cannot be encoded, so the solver holds the exact net
   target instead (never a violation of the band, but tighter than asked)."
  [constraints signs]
  (if (and (net-band-pct-value constraints)
           (nil? signs)
           (not (:long-only? constraints)))
    [{:code :net-band-requires-fixed-sides
      :message (str "The net band (% of gross) needs every asset assigned to a "
                    "long or short side; holding the exact net target instead.")}]
    []))

(defn- gross-capacity
  "Largest gross the box bounds can physically reach: sum of per-asset
   max(|lower|, |upper|)."
  [lower-bounds upper-bounds]
  (reduce + 0
          (map (fn [lower upper]
                 (max (if (number? lower) (js/Math.abs lower) 0)
                      (if (number? upper) (js/Math.abs upper) 0)))
               lower-bounds
               upper-bounds)))

(defn- violations
  [lower-bounds upper-bounds bounds constraints floor-spec band-spec]
  (let [target-net* (target-net constraints)
        net-limits (finite-net-limits constraints)
        ;; A percentage net band widens the reachable net range by pct·gross;
        ;; feasibility checks use the widest case (pct·capacity) so a band that
        ;; makes the target reachable is not flagged infeasible, and the minimum
        ;; gross the target forces shrinks to |target|/(1+pct).
        band-pct (or (:pct band-spec) 0.0)
        band-slack (* band-pct (gross-capacity lower-bounds upper-bounds))
        net-min (when (finite-number? (:min net-limits))
                  (- (:min net-limits) band-slack))
        net-max (when (finite-number? (:max net-limits))
                  (+ (:max net-limits) band-slack))
        sum-lower (reduce + 0 lower-bounds)
        sum-upper (reduce + 0 upper-bounds)
        gross-max (:gross-leverage constraints)
        min-required-gross (when-let [g (minimum-required-gross net-limits)]
                             (/ g (+ 1.0 band-pct)))
        locked-weights (vec (keep :locked-validation bounds))
        locked-gross* (locked-gross locked-weights)]
    (vec (concat
          (invalid-cap-violations constraints)
          (when (and (number? target-net*)
                     (> sum-lower target-net*))
            [{:code :sum-lower-above-target
              :sum-lower sum-lower
              :target-net target-net*}])
          (when (and (number? target-net*)
                     (< sum-upper target-net*))
            [{:code :sum-upper-below-target
              :sum-upper sum-upper
              :target-net target-net*}])
          (when (and (not (number? target-net*))
                     (finite-number? net-max)
                     (> sum-lower net-max))
            [{:code :sum-lower-above-net-max
              :sum-lower sum-lower
              :net-max net-max}])
          (when (and (not (number? target-net*))
                     (finite-number? net-min)
                     (< sum-upper net-min))
            [{:code :sum-upper-below-net-min
              :sum-upper sum-upper
              :net-min net-min}])
          (when (and (contains? constraints :gross-leverage)
                     (not (finite-number? gross-max)))
            [{:code :invalid-gross-max
              :gross-max gross-max}])
          (when (and (finite-number? gross-max)
                     (neg? gross-max))
            [{:code :gross-max-negative
              :gross-max gross-max}])
          (when (and (finite-number? gross-max)
                     (finite-number? min-required-gross)
                     (< gross-max min-required-gross))
            [{:code :gross-below-required-net
              :gross-max gross-max
              :minimum-required-gross min-required-gross
              :net-min net-min
              :net-max net-max}])
          (when (and (finite-number? gross-max)
                     (> locked-gross* gross-max))
            [{:code :locked-gross-above-gross-max
              :locked-gross locked-gross*
              :gross-max gross-max}])
          (when (and floor-spec
                     (finite-number? gross-max)
                     (> (:min floor-spec) gross-max))
            [{:code :gross-floor-above-gross-max
              :gross-floor (:min floor-spec)
              :gross-max gross-max}])
          (when (and floor-spec
                     (> (:min floor-spec)
                        (gross-capacity lower-bounds upper-bounds)))
            [{:code :gross-floor-exceeds-capacity
              :gross-floor (:min floor-spec)
              :gross-capacity (gross-capacity lower-bounds upper-bounds)}])
          (locked-short-violations locked-weights)))))

(defn- apply-runtime-caps
  [constraints sparse-caps]
  (if (seq sparse-caps)
    (update constraints
            :per-asset-overrides
            (fn [overrides]
              (reduce (fn [acc {:keys [instrument-id max-weight]}]
                        (update acc
                                instrument-id
                                merge-max-weight-override
                                max-weight))
                      (or overrides {})
                      sparse-caps)))
    constraints))

(defn- side-metadata
  "Requested side + shortability per instrument, in universe order. Equal Risk
  presolve rejects short requests `bounds-for` silently long-flipped."
  [constraints universe]
  (mapv (fn [instrument]
          {:instrument-id (instrument-id instrument)
           :requested-side (position-side instrument)
           :shortable? (instrument-shortable? constraints instrument)})
        universe))

(defn- encoded-result
  [constraints universe current-weights]
  (let [ids (mapv instrument-id universe)
        bounds (mapv (partial bounds-for constraints current-weights)
                     universe)
        lower-bounds (mapv :lower bounds)
        upper-bounds (mapv :upper bounds)
        signs (gross-floor-signs lower-bounds upper-bounds)
        floor-spec (gross-floor-spec constraints signs)
        band-spec (net-band-spec constraints signs)
        violations* (violations lower-bounds upper-bounds bounds constraints
                                floor-spec band-spec)]
    {:status (if (seq violations*) :infeasible :ok)
     :long-only? (:long-only? constraints)
     :net-target (target-net constraints)
     :instrument-ids ids
     :current-weights (mapv #(current-weight current-weights %) ids)
     :lower-bounds lower-bounds
     :upper-bounds upper-bounds
     :locked-weights (vec (keep :locked bounds))
     :gross-exposure {:max (:gross-leverage constraints)}
     :gross-floor floor-spec
     :net-exposure (:net-exposure constraints)
     ;; Percentage-of-realized-gross net tolerance (see net-band-spec).
     :net-band-spec band-spec
     :net-band-warnings (net-band-warnings constraints signs)
     ;; Canonical gross/net TARGETS (exposure-policy midpoints) for objectives
     ;; that hit exposures exactly rather than treating band edges as limits.
     ;; Derived HERE, not on the request, so draft signatures stay unchanged.
     :exposure-targets (exposure-policy/engine-constraints->policy constraints)
     :side-metadata (side-metadata constraints universe)
     :max-turnover (:max-turnover constraints)
     :rebalance-tolerance (:rebalance-tolerance constraints)
     :violations violations*}))

(defn encode-constraints
  [{:keys [universe current-weights constraints history]}]
  (let [base-constraints (merge {:long-only? false
                                 :include-spot? false}
                                (or constraints {}))
        universe* (normalize-universe (or universe []) base-constraints)
        current-weights* (or current-weights {})
        sparse-caps (runtime-sparse-caps history universe*)
        base-encoded (encoded-result base-constraints
                                     universe*
                                     current-weights*)
        capped-constraints (apply-runtime-caps base-constraints sparse-caps)
        capped-encoded (encoded-result capped-constraints
                                       universe*
                                       current-weights*)]
    (cond
      (empty? sparse-caps)
      (assoc base-encoded :warnings (vec (:net-band-warnings base-encoded)))

      :else
      (assoc capped-encoded
             :warnings (into (mapv sparse-cap-warning sparse-caps)
                             (:net-band-warnings capped-encoded))))))
