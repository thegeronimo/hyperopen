(ns hyperopen.portfolio.optimizer.domain.exposure-policy
  "Pure conversion between the canonical flat constraint model (gross-min/gross-max,
  net-min/net-max) and the trader-facing 'exposure policy' representation used by the 2D
  exposure map (gross/net TARGET plus a symmetric BAND). This namespace owns every number in
  the redesigned Positioning control so the view, the action handlers, and the tests share one
  source of truth.

  Why target/band instead of min/max: a trader thinks 'keep me around this leverage and this
  directional bias', not 'edit a lower and an upper bound'. Target+band is purely a view
  representation; it always round-trips back to the canonical keys before the request builder
  renames them for the solver, so nothing here changes solver behavior.

  Honest nil-floor semantics: `gross-min` is absent by default (no leverage floor). A positive
  gross band means 'keep leverage within +/- of the target' and writes a floor. A zero gross band
  means 'cap leverage at the target, no floor' and DISSOCs `:gross-min` — UNLESS the policy being
  edited already PINNED gross (gross-min = gross-max), which is the degenerate band 'hold exactly
  this leverage' is made of (:conservative ships one); a pin survives editing and travels with
  the target. The request builder keys gross-floor on `(contains? constraints :gross-min)`, so
  callers must write the WHOLE constraints map this namespace returns (it dissocs the key) rather
  than assoc'ing a nil value, which would wrongly register a nil floor.")

;; --- Axis scales (display domain for the pad) --------------------------------------------
;;
;; The pad plots gross on Y (0 at the bottom, `gross-axis-max` at the top) and net on X
;; (-`net-axis-extent` at the left, +`net-axis-extent` at the right). Targets snap to
;; `target-snap` for clean values. Advanced raw fields may exceed these bounds; the pad simply
;; clamps the plotted marker.

;; The pad scale is FIXED while the trader interacts with it. The earlier adaptive axis grew
;; with 1.12 headroom on every render, so holding the pointer at the top edge remapped the pad
;; under the cursor and ratcheted gross 3×→5×→10×→… without bound. Instead the view renders one
;; of these paired zoom levels; dragging clamps to the visible scale and NEVER changes it, an
;; explicit zoom control steps both axes together, and only discontinuous policy changes
;; (preset / saved profile / reset / advanced raw fields) may re-fit the level. Gross ≈ leverage
;; (the request builder renames :gross-max → :gross-leverage), so the Y axis is surfaced as
;; "Gross leverage".
(def axis-levels
  [{:gross-max 3.0 :net-extent 2.0}
   {:gross-max 5.0 :net-extent 3.0}
   {:gross-max 10.0 :net-extent 5.0}
   {:gross-max 20.0 :net-extent 10.0}
   {:gross-max 40.0 :net-extent 20.0}])
(def max-zoom-level (dec (count axis-levels)))
(def gross-axis-floor (:gross-max (first axis-levels)))
(def net-axis-floor (:net-extent (first axis-levels)))
(def target-snap 0.05)
(def band-eps 1e-6)
(def max-band 0.5)
;; The net band is a PERCENTAGE OF REALIZED GROSS (stored as a decimal: 5% = 0.05),
;; not a leverage amount: the solver permits net within target ± pct·gross(w).
;; 1.0 (100%) adds no restriction beyond |net| ≤ gross, so it is the hard ceiling.
(def max-net-band-pct 1.0)
;; The slider stops at 50%; direct numeric entry may go to max-net-band-pct.
(def net-band-pct-slider-max 0.5)

(def preset-keys [:conservative :balanced :high-gross :long-bias])

;; --- Small numeric helpers ----------------------------------------------------------------

(defn- finite-number?
  [x]
  (and (number? x) (js/isFinite x)))

(defn- round4
  [x]
  (when (finite-number? x)
    (/ (js/Math.round (* x 10000)) 10000)))

(defn- clamp
  [x lo hi]
  (max lo (min hi x)))

(defn- snap
  [x]
  (* target-snap (js/Math.round (/ x target-snap))))

(defn- approx=
  [a b]
  (and (finite-number? a) (finite-number? b)
       (< (js/Math.abs (- a b)) 1e-4)))

;; --- Fixed zoom levels --------------------------------------------------------------------

(def default-axis (first axis-levels))

(defn- axis-needs
  "The gross/net magnitudes the view must contain: the far band edges of the policy plus the
  current portfolio exposure (so the 'current' dot is always framed). The net band is a
  fraction of gross, so its widest absolute extent sits at the policy's max gross."
  [{:keys [gross-target gross-band net-target net-band-pct current-gross current-net]}]
  (let [gross-hi (+ (max 0.0 (or gross-target 0.0)) (max 0.0 (or gross-band 0.0)))
        pct (max 0.0 (or net-band-pct 0.0))]
    {:gross (max gross-hi (or current-gross 0.0))
     :net (max (+ (js/Math.abs (or net-target 0.0)) (* pct gross-hi))
               (js/Math.abs (or current-net 0.0)))}))

(defn- overflow-axis
  "Scale for values beyond the largest zoom level (only reachable through the advanced raw
  fields): round each axis up to the next multiple of 20, and disable zooming."
  [{:keys [gross net]}]
  {:gross-max (max (:gross-max (peek axis-levels)) (* 20.0 (js/Math.ceil (/ gross 20.0))))
   :net-extent (max (:net-extent (peek axis-levels)) (* 20.0 (js/Math.ceil (/ net 20.0))))})

(defn fit-level
  "Index of the smallest zoom level whose paired axes contain the policy band and the current
  exposure, or nil when even the largest level cannot (the overflow case). No headroom: a target
  dragged exactly to the visible max still fits its own level, so dragging can never re-fit."
  [inputs]
  (let [{:keys [gross net]} (axis-needs inputs)]
    (->> (map-indexed vector axis-levels)
         (some (fn [[i {:keys [gross-max net-extent]}]]
                 (when (and (<= gross gross-max) (<= net net-extent)) i))))))

(defn render-axis
  "Resolve the pad's display scale from the policy (+ current exposure) and the trader's stored
  zoom level. The stored level only ever WIDENS the view (zooming in below what the policy needs
  would clip the band box), and an unfittable policy falls back to a computed overflow scale.
  Returns {:axis {:gross-max :net-extent} :level int|nil :fit-level int|nil
           :zoom-in-level int|nil :zoom-out-level int|nil}; the view bakes the neighbour levels
  into the zoom-button dispatches, so the action layer never re-derives them."
  [inputs stored-level]
  (let [fit (fit-level inputs)]
    (if (nil? fit)
      {:axis (overflow-axis (axis-needs inputs))
       :level nil :fit-level nil :zoom-in-level nil :zoom-out-level nil}
      (let [stored (when (and (number? stored-level) (js/isFinite stored-level))
                     (clamp (js/Math.round stored-level) 0 max-zoom-level))
            level (max fit (or stored fit))]
        {:axis (nth axis-levels level)
         :level level
         :fit-level fit
         :zoom-in-level (when (> level fit) (dec level))
         :zoom-out-level (when (< level max-zoom-level) (inc level))}))))

;; --- Forward: canonical constraints -> exposure policy ------------------------------------

(defn clamp-net-band-pct
  "Coerce a net-band percentage (decimal: 5% = 0.05) to [0, max-net-band-pct],
  rounded to 1e-6 so persisted values stay clean."
  [value]
  (let [v (clamp (if (finite-number? value) value 0.0) 0.0 max-net-band-pct)]
    (/ (js/Math.round (* v 1000000)) 1000000)))

(defn constraints->policy
  "Derive {:gross-target :gross-band :net-target :net-band-pct} from the canonical constraint
  map. The net target is the min/max midpoint (the advanced raw fields may still hold a spread);
  the net band is the :net-band-pct fraction-of-gross tolerance around that target. gross-min is
  nilable (nil ⇒ ceiling-only ⇒ band 0, target = gross-max)."
  [constraints]
  (let [gmax (when (finite-number? (:gross-max constraints)) (:gross-max constraints))
        gmin (when (finite-number? (:gross-min constraints)) (:gross-min constraints))
        nmin (when (finite-number? (:net-min constraints)) (:net-min constraints))
        nmax (when (finite-number? (:net-max constraints)) (:net-max constraints))
        net-target (cond
                     (and nmin nmax) (/ (+ nmin nmax) 2)
                     nmax nmax
                     nmin nmin
                     :else 0.0)
        gross-target (cond
                       (and gmin gmax) (/ (+ gmin gmax) 2)
                       gmax gmax
                       gmin gmin
                       :else 0.0)
        gross-band (if (and gmin gmax) (/ (- gmax gmin) 2) 0.0)]
    {:gross-target (round4 gross-target)
     :gross-band (round4 (max 0.0 gross-band))
     :net-target (round4 net-target)
     :net-band-pct (clamp-net-band-pct (:net-band-pct constraints))}))

(defn engine-constraints->policy
  "Canonical gross/net TARGETS from the ENGINE-side constraint keys. The request
  builder renames the draft keys before the solver sees them (:gross-max →
  :gross-leverage, :gross-min → :gross-floor, :net-min/:net-max →
  :net-exposure {:min :max}); this delegates to `constraints->policy` so the
  midpoint semantics live in exactly one place. Objectives that treat gross and
  net as exact TARGETS (Equal Risk) read this instead of re-deriving targets
  from bands — in particular gross-max alone is only the target when no floor
  (band) is present."
  [constraints]
  (constraints->policy {:gross-max (:gross-leverage constraints)
                        :gross-min (:gross-floor constraints)
                        :net-min (get-in constraints [:net-exposure :min])
                        :net-max (get-in constraints [:net-exposure :max])
                        :net-band-pct (:net-band-pct constraints)}))

;; --- Reverse: exposure policy -> canonical constraints ------------------------------------

(defn- pinned-gross?
  "True when `constraints` already express gross as a PIN rather than a ceiling: a finite
  :gross-min under a zero-width gross band (gross-min = gross-max). The explicit nil a
  deliberate CLEAR writes is not finite, so a cleared floor never reads as a pin."
  [constraints]
  (and (finite-number? (:gross-min constraints))
       (<= (:gross-band (constraints->policy constraints)) band-eps)))

(defn policy->constraints
  "Return `constraints` with gross/net bounds recomputed from `policy`. Writes gross-max,
  net-min, net-max, net-band-pct always; writes gross-min when the gross band is positive OR the
  incoming policy already pinned gross, otherwise DISSOCs it to preserve the no-floor default
  (see the `floored?` comment below). Any pre-existing net min/max SPREAD
  (set through the advanced raw fields) is preserved around the moved target, so the pad never
  silently collapses an advanced absolute range. Returns a full constraints map so callers
  persist it whole."
  [constraints {:keys [gross-target gross-band net-target net-band-pct]}]
  (let [gt (max 0.0 (or gross-target 0.0))
        gb (max 0.0 (or gross-band 0.0))
        nt (or net-target 0.0)
        nmin (:net-min constraints)
        nmax (:net-max constraints)
        spread (if (and (finite-number? nmin) (finite-number? nmax) (> nmax nmin))
                 (- nmax nmin)
                 0.0)
        half (/ spread 2)
        ;; A positive band always writes a floor. A ZERO band keeps one only when the incoming
        ;; policy was already PINNED (gross-min = gross-max): a pin IS the degenerate band, so
        ;; its floor tracks the moved target exactly as a positive band's floor does. Keying the
        ;; dissoc on band width alone (the original rule) meant :conservative — which ships
        ;; {:gross-min 1.0 :gross-max 1.0} precisely so Minimum risk cannot answer all-cash —
        ;; lost that floor to any pad click, drag or band edit, including a no-op one. Comparing
        ;; the OLD floor against the NEW target instead would spare only motionless drags: a
        ;; gesture that moves out and back kills the pin on its first sample and can never
        ;; restore it. Clearing still works by both routes that mean it — the advanced
        ;; :gross-min field writes nil (not finite ⇒ not a pin ⇒ dissoc'd here), and collapsing
        ;; a POSITIVE gross band to zero on the slider is not a pin either, so it still clears.
        floored? (or (> gb band-eps) (pinned-gross? constraints))]
    (cond-> (assoc constraints
                   :gross-max (round4 (+ gt gb))
                   :net-min (round4 (- nt half))
                   :net-max (round4 (+ nt half))
                   :net-band-pct (clamp-net-band-pct net-band-pct))
      floored? (assoc :gross-min (round4 (max 0.0 (- gt gb))))
      (not floored?) (dissoc :gross-min))))

;; --- Pure transforms applied by the action handlers --------------------------------------

(defn apply-point
  "Move the target point to `{:gross-target :net-target}` while preserving both bands."
  [constraints {:keys [gross-target net-target]}]
  (let [{:keys [gross-band net-band-pct]} (constraints->policy constraints)]
    (policy->constraints constraints
                         {:gross-target gross-target
                          :gross-band gross-band
                          :net-target net-target
                          :net-band-pct net-band-pct})))

(defn apply-band
  "Set the `axis` band, preserving targets. :gross is an absolute leverage half-width clamped
  to [0, max-band]; :net is a PERCENTAGE OF GROSS (decimal) clamped to [0, max-net-band-pct]."
  [constraints axis value]
  (let [policy (constraints->policy constraints)
        policy' (case axis
                  :gross (assoc policy :gross-band
                                (clamp (max 0.0 (or value 0.0)) 0.0 max-band))
                  :net (assoc policy :net-band-pct (clamp-net-band-pct value))
                  policy)]
    (policy->constraints constraints policy')))

;; --- Named presets ------------------------------------------------------------------------
;;
;; Each preset is a partial constraint map merged over the current constraints. A preset is
;; ceiling-only on gross UNLESS it names its own :gross-min; applying one that does not still
;; dissocs :gross-min. Values are starting points a trader can then nudge; see the ExecPlan
;; Decision Log for the rationale.
;;
;; Why :conservative alone carries a floor. Its net is pinned at 0.0, and a zero net with only a
;; gross CEILING does not forbid w = 0 - so under the default objective (:minimum-variance) cash
;; is feasible AND globally optimal at zero variance, and Conservative had always silently
;; returned an all-cash book (domain.objectives' cash-collapse gate simply made that visible).
;; The floor equals the preset's own advertised gross figure, which turns "1x gross, market
;; neutral" into an instruction rather than a ceiling. The other three presets pin a NON-ZERO
;; net (1.0 / 1.0 / 1.5), which already forbids cash, so they stay ceiling-only.

(def presets
  {:conservative {:gross-max 1.0 :gross-min 1.0 :net-min 0.0 :net-max 0.0
                  :net-band-pct 0.0 :max-asset-weight 0.25}
   :balanced     {:gross-max 2.0 :net-min 1.0 :net-max 1.0 :net-band-pct 0.0
                  :max-asset-weight 0.5}
   :high-gross   {:gross-max 3.0 :net-min 1.0 :net-max 1.0 :net-band-pct 0.0
                  :max-asset-weight 0.5}
   ;; Long Bias: net target 1.5x with a ±12.5%-of-gross tolerance (the old ±0.25x
   ;; absolute band at the preset's 2.0x gross target).
   :long-bias    {:gross-max 2.0 :net-min 1.5 :net-max 1.5 :net-band-pct 0.125
                  :max-asset-weight 0.5}})

(def preset-labels
  {:conservative "Conservative"
   :balanced "Balanced"
   :high-gross "High Gross"
   :long-bias "Long Bias"
   :custom "Custom"})

(defn apply-preset
  "Merge the named preset over `constraints`. A preset that names its own :gross-min applies it;
  every other preset is ceiling-only and CLEARS any floor the trader had (a stale floor from a
  previous policy would silently outlive the preset that replaced it)."
  [constraints preset-key]
  (if-let [partial (get presets preset-key)]
    (let [merged (merge constraints partial)]
      (if (contains? partial :gross-min)
        merged
        (dissoc merged :gross-min)))
    constraints))

(defn active-preset
  "Return the preset key whose gross/net/cap values match `constraints`, else :custom. The gross
  FLOOR is part of the match: a preset that ships one is only active while it is present, and a
  ceiling-only preset is only active while there is none."
  [constraints]
  (or (some (fn [k]
              (let [{:keys [gross-min gross-max net-min net-max net-band-pct max-asset-weight]}
                    (get presets k)]
                (when (and (if (some? gross-min)
                             (approx= (:gross-min constraints) gross-min)
                             (nil? (:gross-min constraints)))
                           (approx= (:gross-max constraints) gross-max)
                           (approx= (:net-min constraints) net-min)
                           (approx= (:net-max constraints) net-max)
                           (approx= (or (:net-band-pct constraints) 0.0)
                                    (or net-band-pct 0.0))
                           (approx= (:max-asset-weight constraints) max-asset-weight))
                  k)))
            preset-keys)
      :custom))

;; --- Pad pointer math (pure; receives plain numbers, never the DOM) ----------------------

(defn point->targets
  "Convert a pad pointer event to snapped gross/net targets, or nil when this is not an active
  drag (no pressed button) or the pad bounds are degenerate. `bounds` is the pad's bounding rect
  resolved by :event.currentTarget/bounds; `gross-axis-max`/`net-axis-extent` are the pad's
  current fixed scale (baked into the dispatch) so the pointer maps to exactly the values the
  axis shows. They default to the floor scale. `gross-band`/`net-band` (default 0) shrink the
  reachable range so the WHOLE band box stays inside the view: `target + band ≤ axis max` is the
  fixpoint that keeps a drag from ever forcing a re-fit of the scale mid-gesture."
  [{:keys [client-x client-y bounds buttons gross-axis-max net-axis-extent
           gross-band net-band-pct]}]
  (let [{:keys [left top width height]} bounds
        g-max (if (finite-number? gross-axis-max) gross-axis-max gross-axis-floor)
        n-ext (if (finite-number? net-axis-extent) net-axis-extent net-axis-floor)
        g-band (if (finite-number? gross-band) (max 0.0 gross-band) 0.0)
        n-pct (clamp-net-band-pct net-band-pct)
        pressed? (and (number? buttons) (pos? buttons))]
    (when (and pressed?
               (finite-number? client-x) (finite-number? client-y)
               (finite-number? left) (finite-number? top)
               (finite-number? width) (finite-number? height)
               (pos? width) (pos? height))
      (let [fx (clamp (/ (- client-x left) width) 0.0 1.0)
            fy (clamp (/ (- client-y top) height) 0.0 1.0)
            gross-reach (max 0.0 (- g-max g-band))
            gross-target (min (snap (* g-max (- 1.0 fy))) gross-reach)
            ;; Net reach also respects the gross reach: gross ≥ |net| lifts the gross target to
            ;; |net|, so a net target beyond gross-reach would push gross-target + gross-band
            ;; past the axis max (reachable via advanced raw fields, where the gross band is not
            ;; capped at max-band) and force a mid-drag re-fit. The net band's absolute extent
            ;; is pct·(gross-target + gross-band); the (1+pct) division covers the case where
            ;; |net| itself lifts the gross target (gross* = |net|).
            net-reach (max 0.0 (min (- n-ext (* n-pct (+ gross-target g-band)))
                                    (/ (- n-ext (* n-pct g-band)) (+ 1.0 n-pct))
                                    gross-reach))
            net-target (clamp (snap (+ (- n-ext) (* fx (* 2 n-ext))))
                              (- net-reach) net-reach)
            ;; Gross is total absolute exposure, so it can never be below the absolute net
            ;; target; clamp to keep the box physically meaningful.
            gross-target* (max gross-target (js/Math.abs net-target))]
        {:gross-target (round4 gross-target*)
         :net-target (round4 net-target)}))))

;; --- Plotting helpers for the view (0..1 fractions; y grows downward like SVG) ------------
;;
;; Each takes the render axis {:gross-max :net-extent}; the 1-arity overloads use the floor
;; level so callers that don't care about the zoomed framing (and tests) stay simple.

(defn- net->fraction
  [net n-ext]
  (clamp (/ (+ net n-ext) (* 2 n-ext)) 0.0 1.0))

(defn- gross->fraction
  [gross g-max]
  (clamp (- 1.0 (/ gross g-max)) 0.0 1.0))

(defn target-marker
  "Fractional {:x :y} (0..1) position of the policy target point on the pad."
  ([policy] (target-marker policy default-axis))
  ([{:keys [gross-target net-target]} {:keys [gross-max net-extent]}]
   {:x (net->fraction (or net-target 0.0) net-extent)
    :y (gross->fraction (or gross-target 0.0) gross-max)}))

(defn net-band-edges
  "Allowed net range at realized gross `g`: target ± pct·g, clipped to the natural |net| ≤ g
  relationship. When the target itself is unreachable at this gross the range collapses onto
  the clamped target so the geometry stays degenerate rather than inverted."
  [net-target pct g]
  (let [g* (max 0.0 (or g 0.0))
        nt (or net-target 0.0)
        p (clamp-net-band-pct pct)
        lo (max (- nt (* p g*)) (- g*))
        hi (min (+ nt (* p g*)) g*)]
    (if (<= lo hi)
      {:left lo :right hi}
      (let [c (clamp nt (- g*) g*)]
        {:left c :right c}))))

(defn- wedge-breakpoints
  "Gross values in (g0, g1) where a band edge meets the |net| ≤ gross clip and the boundary
  slope changes (the wedge polygon needs a vertex there)."
  [nt pct g0 g1]
  (if (>= pct 1.0)
    []
    (->> [(/ nt (- 1.0 pct)) (/ (- nt) (- 1.0 pct))]
         (filter #(and (finite-number? %) (< g0 %) (< % g1)))
         sort
         vec)))

(defn- wedge-points
  "Fractional polygon vertices (counter-clockwise in pad space) of the region
  gross ∈ [g0, g1] × net ∈ [target − pct·gross, target + pct·gross] ∩ |net| ≤ gross."
  [nt pct g0 g1 {:keys [gross-max net-extent]}]
  (let [gs (into [g0] (conj (wedge-breakpoints nt pct g0 g1) g1))
        point (fn [net g]
                [(net->fraction net net-extent) (gross->fraction g gross-max)])
        right-side (mapv (fn [g] (point (:right (net-band-edges nt pct g)) g)) gs)
        left-side (mapv (fn [g] (point (:left (net-band-edges nt pct g)) g)) (rseq gs))]
    (into right-side left-side)))

(defn band-wedge
  "Fractional polygon {:points [[x y] ...]} of the allowed exposure region: the gross band
  crossed with the percentage net band, whose absolute width scales with gross — a sloped
  wedge, not a fixed-width vertical stripe."
  ([policy] (band-wedge policy default-axis))
  ([{:keys [gross-target gross-band net-target net-band-pct]} axis]
   (let [gt (max 0.0 (or gross-target 0.0))
         gb (max 0.0 (or gross-band 0.0))
         g0 (max 0.0 (- gt gb))
         g1 (+ gt gb)]
     {:points (wedge-points (or net-target 0.0)
                            (clamp-net-band-pct net-band-pct)
                            g0 g1 axis)})))

(defn band-wedge-stripe
  "Fractional polygon of the percentage net band swept across the FULL gross axis (0..max) —
  the full-height analogue of the old vertical net stripe, now sloped."
  ([policy] (band-wedge-stripe policy default-axis))
  ([{:keys [net-target net-band-pct]} {:keys [gross-max] :as axis}]
   {:points (wedge-points (or net-target 0.0)
                          (clamp-net-band-pct net-band-pct)
                          0.0 gross-max axis)}))

(defn band-rect
  "Fractional {:x :y :w :h} (0..1) rectangle of the allowed exposure region evaluated at the
  policy's MAX gross (the wedge's widest extent). Kept for framing math; the pad renders
  `band-wedge` so the sloped percentage boundaries are visible."
  ([policy] (band-rect policy default-axis))
  ([{:keys [gross-target gross-band net-target net-band-pct]} {:keys [gross-max net-extent]}]
   (let [gt (or gross-target 0.0)
         gb (max 0.0 (or gross-band 0.0))
         nt (or net-target 0.0)
         nb (* (clamp-net-band-pct net-band-pct) (+ gt gb))
         x0 (net->fraction (- nt nb) net-extent)
         x1 (net->fraction (+ nt nb) net-extent)
         y-top (gross->fraction (+ gt gb) gross-max)
         y-bot (gross->fraction (- gt gb) gross-max)]
     {:x x0 :y y-top :w (max 0.0 (- x1 x0)) :h (max 0.0 (- y-bot y-top))})))

(defn current-exposure-marker
  "Fractional {:x :y} position of the current portfolio's {:gross :net} exposure, or nil when
  either value is missing."
  ([exposure] (current-exposure-marker exposure default-axis))
  ([{:keys [gross net]} {:keys [gross-max net-extent]}]
   (when (and (finite-number? gross) (finite-number? net))
     {:x (net->fraction net net-extent)
      :y (gross->fraction gross gross-max)})))
