(ns hyperopen.views.portfolio.optimize.infeasible-remediation
  "The \"What you can do\" model behind the infeasible banner: which unblock paths
  a presolve payload supports, the copy for each, and the single dispatch that
  performs the ones a click can.

  Split out of `infeasible-panel` (which now owns labels, control highlighting
  and hiccup) because these two concerns fail differently. A rendering bug shows
  up on screen; a bug HERE writes a number that is still infeasible, and the
  trader clicks a fix only to land back on the banner it was offered from.

  THE RULE every builder here follows: a suggestion is derived from the payload,
  and a payload without the number it needs yields NO suggestion rather than a
  blank, an \"N/A\" or a value re-derived from whatever else is at hand. In
  particular the two thresholds a button writes - the largest workable gross
  floor and the smallest workable net band - are SOLVED in
  `domain.exposure-feasibility` against the encoded rows at the gross the run
  realizes. They are not recomputed here from the gross ceiling, which is what
  produced fixes that did not fix anything."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.domain.exposure-policy :as exposure-policy]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn violation-codes
  "Every code the result names, or its run-level :reason when it carries no
  violation payload. Read by the banner and the control highlight too."
  [result]
  (let [violations (get-in result [:details :violations])]
    (cond
      (seq violations) (->> violations
                            (keep :code)
                            distinct
                            vec)
      (:reason result) [(:reason result)]
      :else [])))

(def ^:private finite-number? opt-format/finite-number?)

(defn- positive-number?
  [value]
  (and (finite-number? value) (pos? value)))

(defn- usable?
  "Only an EXPLICIT false suppresses: a payload missing the capability flag
  keeps the previous, more permissive behaviour."
  [value]
  (not (false? value)))

(defn- fmt-x
  [value]
  (opt-format/format-multiple value))

(defn- fmt-signed-x
  "Net multiples carry an explicit sign so long/short bias is unambiguous."
  [value]
  (str (when (positive-number? value) "+")
       (opt-format/format-multiple value)))

(defn- fmt-band-pct
  [value]
  (opt-format/format-pct value {:minimum-fraction-digits 0
                                :maximum-fraction-digits 0}))

;; Nudge before rounding so a value a float hair past a cent boundary
;; (0.6 * 100 = 60.000000000000007) does not jump a whole cent.
(def ^:private cent-epsilon 1.0e-9)

(defn- snap-down
  [value]
  (/ (js/Math.floor (+ (* 100 value) cent-epsilon)) 100))

(defn- fmt-x-down
  "A gross bound the trader SET, quoted at 2 decimals and rounded DOWN. Rounded
  to nearest, a 3.995x floor prints as 4.00x - above the value actually stored -
  so retyping the quoted number tightens the constraint the copy is blaming. The
  domain's own message in the same banner rounds the same way."
  [value]
  (fmt-x (snap-down value)))

(defn- capacity-row-label
  "The engine's resolved display symbol when the payload carries one, else the
  id tail (\"perp:UNITREE\" -> \"UNITREE\"). The banner renders without the
  universe, so a spot tail reads as \"@142\" and a vault's as a raw address."
  [{:keys [display-symbol instrument-id]}]
  (let [resolved (some-> display-symbol str str/trim)]
    (if-not (str/blank? resolved)
      resolved
      (let [text (some-> instrument-id str str/trim)]
        (when-not (str/blank? text)
          (let [tail (last (str/split text #":"))]
            (if (str/blank? tail) text tail)))))))

(defn- binding-capacity-clause
  "\" (UNITREE 0.05x, SPCX 0.03x)\" — the assets that cap the binding side."
  [rows]
  (let [parts (keep (fn [{:keys [capacity] :as row}]
                      (when-let [label (capacity-row-label row)]
                        (when (positive-number? capacity)
                          (str label " " (fmt-x capacity)))))
                    rows)]
    (when (seq parts)
      (str " (" (str/join ", " parts) ")"))))

(defn- net-target-suggestion
  "Quotes :reachable-net-display, never :reachable-net: the domain snaps that
  interval INWARD, so both ends are inside the reachable set at the two decimals
  the label shows -- rounding the exact interval outward produced a button that
  re-fired this very check. Omitted under Long Only, where constraints/target-net
  pins net at gross and finite-net-limits ignores :net-exposure outright."
  [{:keys [reachable-net-display long-only?]} above?]
  (let [lo (:min reachable-net-display)
        hi (:max reachable-net-display)]
    (when (and (not (true? long-only?))
               (finite-number? lo)
               (finite-number? hi)
               (<= lo hi))
      (let [value (if above? lo hi)]
        {:id (if above? :raise-net-target :lower-net-target)
         :copy (str "Inside the current gross window this universe can only reach a net of "
                    (fmt-signed-x lo) " to " (fmt-signed-x hi)
                    ". Pinning the net target inside that range clears this check.")
         :label (str "Set net to " (fmt-signed-x value))
         ;; Two actions in one dispatch is safe only because
         ;; set-portfolio-optimizer-constraint ignores state: nexus expands every
         ;; action in a click list against ONE pre-dispatch snapshot.
         :actions [[:actions/set-portfolio-optimizer-constraint :net-min value]
                   [:actions/set-portfolio-optimizer-constraint :net-max value]]}))))

(defn- lower-gross-floor-suggestion
  "`:max-feasible-gross-floor` is solved against the EXACT encoded rows at the
  gross floor, which is where the coupled net-band row binds. Derived instead
  from the band widened at the gross CEILING, it named a floor that was still
  infeasible - a fix that returned the trader to this banner."
  [{:keys [gross-floor max-feasible-gross-floor]}]
  (when (and (positive-number? gross-floor)
             (finite-number? max-feasible-gross-floor)
             (< max-feasible-gross-floor gross-floor))
    ;; Snapping DOWN is monotonically safe: a lower floor is strictly more
    ;; permissive, so the rounded label and the value written always agree. A
    ;; ceiling under a cent leaves no positive floor to write, and a 0.00x floor
    ;; keeps nothing invested - clear-gross-floor owns that case.
    (let [value (snap-down max-feasible-gross-floor)]
      (when (positive-number? value)
        {:id :lower-gross-floor
         :copy (str "The " (fmt-x-down gross-floor)
                    " gross floor is what forces net out of range. Lowering Gross Exposure Min to "
                    (fmt-x value) " clears this check and still keeps the run invested.")
         :label (str "Lower gross floor to " (fmt-x value))
         :actions [[:actions/set-portfolio-optimizer-constraint :gross-min value]]}))))

(defn- clear-gross-floor-suggestion
  "Offered only where clearing actually clears the check. The domain publishes
  :max-feasible-gross-floor exactly when SOME gross value satisfies the net rows,
  and a floor of zero is then reachable by construction; when it is absent no
  floor at all unblocks the run and this button would be a round trip."
  [{:keys [gross-floor max-feasible-gross-floor]}]
  (when (and (positive-number? gross-floor)
             (finite-number? max-feasible-gross-floor))
    {:id :clear-gross-floor
     :copy (str "Or drop the " (fmt-x-down gross-floor)
                " floor entirely, so the optimizer is free to hold less total exposure.")
     :label "Clear gross floor"
     ;; :gross-min is one of the clearable numeric constraint keys, so a literal
     ;; nil is accepted by the action and by ::portfolio-optimizer-constraint-args.
     :actions [[:actions/set-portfolio-optimizer-constraint :gross-min nil]]}))

(defn- zero-capacity-clause
  [zero-count above?]
  (when (positive-number? zero-count)
    (str " " zero-count
         (if (= 1 zero-count) " selected asset carries no " " selected assets carry no ")
         (if above? "short" "long") " capacity at all.")))

(defn- side-capacity-suggestion
  "Copy only. Raising a side's capacity means flipping assets to that side or
  loosening their weight caps (including history-assumption caps) — judgement
  calls with no single action behind them."
  [{:keys [net-bounds gross-window binding-capacity zero-capacity-count
           required-short-notional available-short-notional
           required-long-notional available-long-notional]}
   above?]
  (let [required (if above? required-short-notional required-long-notional)
        available (if above? available-short-notional available-long-notional)
        net-bound (if above? (:max net-bounds) (:min net-bounds))
        ;; BOTH directions' required notionals are derived from the gross FLOOR
        ;; (exposure-reachability computes (glo - widened-max)/2 above the band
        ;; and (glo + widened-min)/2 below it), so the floor is the figure this
        ;; sentence has to quote. Naming the ceiling below the band blamed a
        ;; control the number had nothing to do with, and contradicted the
        ;; violation message sitting directly above it in the same banner.
        gross-bound (:min gross-window)
        clause (binding-capacity-clause binding-capacity)]
    ;; Naming no asset AND reporting no capacity leaves "this universe offers
    ;; 0.00x" with nothing behind it; say nothing rather than that.
    (when (and (positive-number? required)
               (finite-number? available)
               (or (positive-number? available) (some? clause)))
      {:id (if above? :add-short-capacity :add-long-capacity)
       :copy (str (if (and (finite-number? net-bound) (positive-number? gross-bound))
                    (str "Reaching " (fmt-signed-x net-bound) " net against a "
                         (fmt-x-down gross-bound) " gross floor"
                         " needs ")
                    "This setup needs ")
                  (fmt-x required) (if above? " of shorts" " of longs")
                  "; this universe offers " (fmt-x available)
                  clause
                  (if above?
                    ". Flip more assets to the short side, or raise those assets' weight caps."
                    ". Add long-side assets, or raise those assets' weight caps.")
                  (zero-capacity-clause zero-capacity-count above?))})))

(defn- long-only-net-suggestion
  "Replaces the flip-to-short copy under Long Only, which forbids exactly the
  move that copy asks for: with every weight >= 0 the short book is empty, so
  net(w) = gross(w) identically."
  [{:keys [gross-window]}]
  (let [floor (:min gross-window)]
    {:id :long-only-forces-net
     :copy (str "Long Only forbids short exposure, so net always equals gross here"
                (when (positive-number? floor)
                  (str " — a " (fmt-x-down floor) " gross floor is a "
                       (fmt-x-down floor) " net"))
                ". Turn Long Only off to allow the shorts that pull net down, or lower "
                "Gross Exposure Min.")}))

(defn- widen-net-band-suggestion
  "Quotes the band the DOMAIN solved for, never one re-derived here. The band
  scales with REALIZED gross, so the coupled row binds at the gross FLOOR, not at
  the ceiling; dividing the gap by the ceiling (the only gross figure this
  payload used to carry) understated the band and shipped a fix that left the
  request infeasible. The panel does not carry the box that threshold is measured
  against, so a payload without it offers nothing."
  [{:keys [reachable-net-display net-band-pct min-feasible-net-band-pct
           net-band-encodable?]}
   above?]
  (let [shown (if above? (:min reachable-net-display) (:max reachable-net-display))
        current (if (finite-number? net-band-pct) net-band-pct 0)]
    ;; The band needs a sign vector and is dropped outright under Long Only
    ;; (exposure-reachability/net-band-encodable?), so widening it is then inert.
    (when (and (usable? net-band-encodable?)
               (finite-number? min-feasible-net-band-pct)
               (finite-number? shown)
               (> min-feasible-net-band-pct current)
               ;; Values above the ceiling are silently clamped on read, which
               ;; would leave the input and the solver disagreeing. The SLIDER
               ;; stops at half this, deliberately; a suggestion above that still
               ;; writes and still holds (setup_exposure_map renders it pinned
               ;; and says so), and capping here would drop every fix in the
               ;; upper half of the legal range.
               (<= min-feasible-net-band-pct exposure-policy/max-net-band-pct))
      {:id :widen-net-band
       :copy (str "The net band decides how far net may drift from the target, as a "
                  "share of the gross the run actually holds. At "
                  (fmt-band-pct min-feasible-net-band-pct)
                  " of gross it stretches far enough to reach " (fmt-signed-x shown) ".")
       :label (str "Widen net band to " (fmt-band-pct min-feasible-net-band-pct))
       :actions [[:actions/set-portfolio-optimizer-constraint
                  :net-band-pct
                  min-feasible-net-band-pct]]})))

(defn- net-unreachable-suggestions
  [violation]
  (let [direction (:direction violation)
        above? (= :net-above-band direction)]
    (if-not (or above? (= :net-below-band direction))
      []
      (into []
            (remove nil?)
            [(net-target-suggestion violation above?)
             (lower-gross-floor-suggestion violation)
             (clear-gross-floor-suggestion violation)
             (if (and above? (true? (:long-only? violation)))
               (long-only-net-suggestion violation)
               (side-capacity-suggestion violation above?))
             (widen-net-band-suggestion violation above?)]))))

(defn- gross-floor-suggestion
  "The domain clamps :suggested-gross-floor to the largest floor this setup can
  actually hold -- the net rows included, not just the gross cap -- so it is NOT
  always the current book, and the copy says which it is. That comparison reads
  the UNSNAPPED suggestion: snap-down moves a value by up to 0.00999, so
  comparing the snapped one against the book claimed 'the most this setup allows'
  for any book whose gross was simply not a 2-decimal number, contradicting the
  domain's own message in the same banner."
  [{:keys [suggested-gross-floor current-gross max-feasible-gross-floor]}]
  (let [snapped (when (positive-number? suggested-gross-floor)
                  (snap-down suggested-gross-floor))
        value (when (positive-number? snapped) snapped)]
    (if value
      {:id :set-gross-floor
       :copy (str "Minimum risk needs a reason to hold anything. A floor at "
                  (if (and (finite-number? current-gross)
                           (>= suggested-gross-floor (- current-gross cent-epsilon)))
                    (str "the " (fmt-x value) " your current book already carries")
                    (str (fmt-x value) ", the most this setup allows"))
                  " keeps the run invested instead of answering with cash.")
       :label (str "Set Gross Exposure Min to " (fmt-x value))
       :actions [[:actions/set-portfolio-optimizer-constraint :gross-min value]]}
      {:id :set-any-gross-floor
       :copy (cond
               (positive-number? max-feasible-gross-floor)
               (str "Set Gross Exposure Min above zero so the run has to hold a position. "
                    "Anywhere up to the " (fmt-x (snap-down max-feasible-gross-floor))
                    " this setup can hold works.")

               ;; A published-but-non-positive ceiling is a PROOF that no floor
               ;; fits; only an absent one leaves the question open.
               (some? max-feasible-gross-floor)
               (str "A gross floor cannot help here: no positive floor fits inside this "
                    "setup's net exposure window.")

               :else
               (str "Set Gross Exposure Min above zero so the run has to hold a position. "
                    "There is no current book to size it from, so choose the exposure "
                    "you want the result to carry."))})))

(defn- minimum-risk-suggestions
  [violation]
  [(gross-floor-suggestion violation)
   {:id :pin-net-target
    :copy (str "Or set a non-zero Net Exposure Min, which rules out an all-cash answer "
               "the same way.")}])

(defn- suggestions-for-violation
  [violation]
  (case (:code violation)
    :net-unreachable-given-sides (net-unreachable-suggestions violation)
    :minimum-risk-without-exposure-floor (minimum-risk-suggestions violation)
    []))

(defn- distinct-by-id
  [suggestions]
  (:items (reduce (fn [{:keys [seen] :as acc} suggestion]
                    (if (contains? seen (:id suggestion))
                      acc
                      (-> acc
                          (update :seen conj (:id suggestion))
                          (update :items conj suggestion))))
                  {:seen #{} :items []}
                  suggestions)))

(defn suggestions
  "Ordered, payload-derived unblock paths for `result`. Each entry is
  {:id :copy}, plus {:label :actions} when a single in-app dispatch performs it."
  [result]
  (->> (get-in result [:details :violations])
       (mapcat suggestions-for-violation)
       distinct-by-id))

(def ^:private violation-rationale
  {:net-unreachable-given-sides
   (str "Net exposure equals gross minus twice the short book, so a gross floor the short "
        "side cannot offset pins net high no matter how the weights are arranged.")
   :long-only-net-unreachable
   (str "Long Only rules out short positions, so net exposure always equals gross here and "
        "the gross window alone decides the net the optimizer can reach.")
   :minimum-risk-without-exposure-floor
   (str "Minimum risk with no exposure floor has an all-cash optimum, so the answer would "
        "be zero in every asset.")})

(defn- rationale-key
  [violation]
  (if (and (= :net-unreachable-given-sides (:code violation))
           (true? (:long-only? violation)))
    :long-only-net-unreachable
    (:code violation)))

(defn rationale
  "One sentence explaining why the request is blocked, or nil when the codes
  present have no standing explanation."
  [result]
  (or (some (comp violation-rationale rationale-key)
            (get-in result [:details :violations]))
      (some violation-rationale (violation-codes result))))
