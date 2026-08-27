(ns hyperopen.portfolio.optimizer.domain.exposure-feasibility
  "The EXACT set of realized gross values the encoded exposure rows leave open,
  and the remediation thresholds read off it.

  WHY THIS IS SEPARATE FROM THE PRESOLVE. `domain.exposure-reachability` answers
  \"is this request provably unreachable?\" and may answer with a RELAXED bound:
  it widens the net band by q*Ghi (the largest gross the window permits), which
  is a sound NECESSARY condition - it can only under-detect, never call a
  feasible request infeasible. A REMEDIATION value has the opposite burden. It
  must be SUFFICIENT: the number written into the control has to leave a
  portfolio the solver can actually reach, or the trader clicks a fix and lands
  on the same banner. Computing a fix against the relaxed bound is what shipped
  a 1.52x gross floor for a request whose true ceiling was 1.45x.

  THE GEOMETRY. On a fixed-sign box (every asset's bounds sit wholly on one side
  of zero, which `gross-floor-signs` requires before a gross floor or a net band
  is encoded at all) write L = sum of the long weights and S = sum of the short
  notionals. L and S depend on DISJOINT assets, so the reachable (L, S) set is
  exactly the rectangle [L-min, L-max] x [S-min, S-max] - no relaxation. With
  gross g = L + S and net n = L - S, a point exists at a given g exactly when

      max(2*L-min - g, g - 2*S-max) <= n <= min(2*L-max - g, g - 2*S-min)

  and the encoded rows the solver receives are

      g >= gross floor          (signed-linear row, sum(sign_i*w_i) >= G)
      g <= gross ceiling        (the L1 gross cap, and what the box can reach)
      n - q*g <= net-max        (upper net-band row; q = 0 is the plain net row)
      n + q*g >= net-min        (lower net-band row)

  Eliminating n leaves four linear conditions on g alone, so the feasible gross
  values form one interval. `feasible-gross-window` returns it. Everything else
  here is read off that interval:

    - the largest gross FLOOR that still leaves a reachable portfolio is the
      interval's upper end (a floor only removes gross values below itself);
    - a floor is reachable exactly when it is at or below that end;
    - the smallest net BAND that rescues a given floor is found by walking the
      whole-percent grid the panel quotes, because feasibility is monotone in q
      (every one of the four conditions relaxes as q grows) and because a value
      verified on the grid is the value actually written.

  SOUNDNESS ON A MIXED-SIGN BOX. When some asset straddles zero the rectangle is
  an OUTER bound on (L, S), so the window returned here is a superset of the
  truth: an empty window still proves infeasibility, but a non-empty one proves
  nothing. That is why every consumer gates its suggestion on an ENCODED gross
  floor or an encodable net band - both of which require single-signed bounds,
  where the rectangle is exact and the answers here are sufficient."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def ^:private finite-number? coercion/finite-number?)

(def ^:private feasibility-epsilon
  "Float slack, matched to the presolve's own. Encodings land exactly on a bound
  (a 0.0 net pin, a gross floor equal to the capacity), so rounding dust must not
  read as a real gap."
  1e-9)

(def band-pct-display-step
  "The grid the net band is entered and rendered on: whole percent. A suggested
  band is searched ON this grid so the number verified is the number written."
  0.01)

(defn- usable-bounds?
  [lower-bounds upper-bounds]
  (and (seq lower-bounds)
       (= (count lower-bounds) (count upper-bounds))
       (every? identity
               (map (fn [lower upper]
                      (and (finite-number? lower)
                           (finite-number? upper)
                           (<= lower upper)))
                    lower-bounds
                    upper-bounds))))

(defn side-capacities
  "[L-min L-max S-min S-max] for the encoded box. L rides the bounds directly; S
  rides them REVERSED - a more negative lower bound buys MORE short notional."
  [lower-bounds upper-bounds]
  (reduce (fn [[l-min l-max s-min s-max] [lower upper]]
            [(+ l-min (max 0 lower))
             (+ l-max (max 0 upper))
             (+ s-min (max 0 (- upper)))
             (+ s-max (max 0 (- lower)))])
          [0 0 0 0]
          (map vector lower-bounds upper-bounds)))

(defn band-pct
  "Active net-band fraction, normalized the way the encoder normalizes it: absent,
  non-finite or non-positive reads as 0, and anything above 1 adds nothing beyond
  |net| <= gross so it caps there."
  [value]
  (if (and (finite-number? value) (pos? value))
    (min value 1.0)
    0.0))

(defn exposure-geometry
  "The box's exposure geometry, computed ONCE so a search over band values does
  not re-walk the bounds: {:l-min :l-max :s-min :s-max :gross-ceiling}. nil when
  the bounds are unusable (empty, ragged, non-finite, or inverted), which is the
  same guard the presolve uses - no geometry, no claims.

  :gross-ceiling is the smallest of what the box can physically hold, the caller's
  own capacity figure when it carries one, and the encoded gross cap."
  [{:keys [lower-bounds upper-bounds gross-max gross-capacity]}]
  (when (usable-bounds? lower-bounds upper-bounds)
    (let [[l-min l-max s-min s-max] (side-capacities lower-bounds upper-bounds)
          ceiling (cond-> (+ l-max s-max)
                    (finite-number? gross-capacity) (min gross-capacity)
                    (and (finite-number? gross-max) (not (neg? gross-max)))
                    (min gross-max))]
      {:l-min l-min
       :l-max l-max
       :s-min s-min
       :s-max s-max
       :gross-ceiling ceiling})))

(defn feasible-gross-window
  "{:min g :max g}: every realized gross the encoded net rows leave open, given
  `geometry` and the net window {:min :max :pct}. nil when no gross value works at
  all - the net rows and the box conflict on their own, and no gross control can
  resolve it.

  The gross FLOOR is deliberately not an input: a floor only removes values below
  itself, so it is compared against this interval rather than baked into it."
  [{:keys [l-min l-max s-min s-max gross-ceiling] :as geometry}
   {net-min :min net-max :max pct :pct}]
  (when geometry
    (let [q (band-pct pct)
          ;; The coupled rows read n -/+ q*g against the net bounds; solving each
          ;; for g gives (1 + q) on the lower side and (1 - q) on the upper one.
          coupled (- 1 q)
          bounded-above? (> coupled feasibility-epsilon)
          lower-terms (cond-> [(+ l-min s-min)]
                        (finite-number? net-max)
                        (conj (/ (- (* 2 l-min) net-max) (+ 1 q)))

                        (finite-number? net-min)
                        (conj (/ (+ net-min (* 2 s-min)) (+ 1 q))))
          upper-terms (cond-> [gross-ceiling]
                        (and (finite-number? net-max) bounded-above?)
                        (conj (/ (+ net-max (* 2 s-max)) coupled))

                        (and (finite-number? net-min) bounded-above?)
                        (conj (/ (- (* 2 l-max) net-min) coupled)))
          ;; At q = 1 the upper rows stop scaling with gross entirely: each one
          ;; reduces to 0 <= (its right-hand side), which no gross value can
          ;; rescue when that side is negative.
          unsatisfiable? (and (not bounded-above?)
                              (or (and (finite-number? net-max)
                                       (neg? (+ net-max (* 2 s-max))))
                                  (and (finite-number? net-min)
                                       (neg? (- (* 2 l-max) net-min)))))
          lo (apply max lower-terms)
          hi (apply min upper-terms)]
      (when (and (not unsatisfiable?)
                 (<= lo (+ hi feasibility-epsilon)))
        {:min lo :max hi}))))

(defn max-feasible-gross-floor
  "The largest gross floor that still leaves a portfolio satisfying every encoded
  net row - the upper end of `feasible-gross-window`, which is also the ceiling on
  any floor a remediation may WRITE. nil when no floor works, including zero:
  clearing the floor is then no fix either."
  [geometry net-window]
  (:max (feasible-gross-window geometry net-window)))

(defn gross-floor-reachable?
  "True when `gross-floor` leaves a reachable portfolio. A missing or non-positive
  floor reads as 0 (no floor), which is still a question worth asking: the net
  rows alone can rule out every gross value."
  [geometry net-window gross-floor]
  (let [ceiling (max-feasible-gross-floor geometry net-window)
        floor (if (and (finite-number? gross-floor) (pos? gross-floor))
                gross-floor
                0)]
    (and (number? ceiling)
         (<= floor (+ ceiling feasibility-epsilon)))))

(defn min-feasible-net-band-pct
  "The smallest net band, on the whole-percent grid the panel quotes and writes,
  that makes `gross-floor` reachable; nil when nothing up to `ceiling` does.

  Searched rather than solved in closed form on purpose. Feasibility is monotone
  in the band (every row in `feasible-gross-window` relaxes as q grows), so the
  first grid value that verifies is the smallest one that can be written - and it
  is verified at the value that will actually be stored, not at an exact crossing
  point a rounding step then moves off."
  [geometry net-window gross-floor ceiling]
  (when geometry
    (let [cap (band-pct (if (finite-number? ceiling) ceiling 1.0))
          steps (js/Math.round (/ cap band-pct-display-step))]
      (loop [step 0]
        (when (<= step steps)
          (let [pct (/ step 100)]
            (if (gross-floor-reachable? geometry
                                        (assoc net-window :pct pct)
                                        gross-floor)
              pct
              (recur (inc step)))))))))
