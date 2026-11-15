(ns hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
  "Read models for the Equal Risk results page: the risk-contribution balance
  chart rows (sorted by deviation, capped with an honest remainder), the KPI
  risk-balance tile, the recommendation verdict copy (including the
  constraint-determined case), allocation-freedom labels, solution-stability
  classification from the solver's deterministic starts, and stop-reason
  labels. Pure functions over the solved result payload; every view branch for
  :equal-risk reads from here so the page can never disagree with itself.
  All views must degrade gracefully on PERSISTED pre-redesign results that
  lack :current-risk-contributions / :allocation-freedom."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def ^:private finite-number? coercion/finite-number?)

(defn equal-risk-result?
  "True when the result is an Equal Risk run carrying its contribution
  section. Section PRESENCE alone stopped implying the objective when the
  covariance-only sibling (:inverse-volatility) started emitting
  :risk-contributions as a diagnostic — the recorded objective kind must
  agree. A result with no recorded kind (persisted pre-kind payloads,
  hand-built fixtures) could only have come from Equal Risk."
  [result]
  (and (some? (:risk-contributions result))
       (let [kind (get-in result [:solver :objective-kind])]
         (or (nil? kind) (= :equal-risk kind)))))

(def display-row-cap
  "Chart rows shown before the remainder line takes over. Universes here reach
  88+ assets; sorting by |deviation| keeps the rows that explain an
  Approximate verdict visible and caps the chart height."
  16)

(defn- pts
  "Fraction-of-total → percentage points, or nil."
  [value]
  (when (finite-number? value)
    (* 100 value)))

(defn format-pts
  [value]
  (if (finite-number? value)
    (str (.toFixed value 1) " pts")
    "—"))

(defn format-signed-pts
  "Signed points with an honest zero: anything below the 0.1-pt display
  resolution renders as unsigned \"0.0 pts\" — a -0.04 that rounds to zero
  must never show as \"-0.0 pts\"."
  [value]
  (if (finite-number? value)
    (if (< (js/Math.abs value) 0.05)
      "0.0 pts"
      (str (when (>= value 0) "+") (.toFixed value 1) " pts"))
    "—"))

(defn- format-share
  [value decimals]
  (if (finite-number? value)
    (str (.toFixed (* 100 value) decimals) "%")
    "—"))

(defn- format-multiple
  [value]
  (if (finite-number? value)
    (str (.toFixed value 2) "x")
    "—"))

(defn deviation-tone
  "Severity of a deviation (in points) against the equal target (in points):
  :good within 20% of the target, :caution within 50%, :bad beyond. Keeps the
  KPI strip honest — a badly unbalanced book can never show green — while
  matching the designer's green for mock-sized deviations. Nil when either
  side is missing."
  [deviation-pts target-pts]
  (when (and (finite-number? deviation-pts)
             (finite-number? target-pts)
             (pos? target-pts))
    (let [fraction (/ (js/Math.abs deviation-pts) target-pts)]
      (cond
        (<= fraction 0.2) :good
        (<= fraction 0.5) :caution
        :else :bad))))

(defn contribution-rows
  "Per-instrument signed-share rows from the result's :risk-contributions /
  :current-risk-contributions sections — the objective-agnostic core the
  Equal Risk balance model and the Risk-weighted sizing contributions view
  both build on. Each row: id/label/weight, the recommended :share, the
  nil-safe :current-share, the per-row :target-share (per-instrument section
  with the uniform-target fallback), signed :deviation-pts / :shift-pts, and
  :negative?. Deliberately NOT kind-gated: a consumer that does not target
  contributions simply ignores the target-derived fields."
  [result]
  (when-let [contributions (:risk-contributions result)]
    (let [{:keys [instrument-ids relative-contributions
                  target-relative-contributions]} contributions
          labels (:labels-by-instrument result)
          weights (:target-weights-by-instrument result)
          targets-by-id (:target-relative-contributions-by-instrument contributions)
          current-by-id (get-in result [:current-risk-contributions
                                        :relative-contributions-by-instrument])
          target-share (first target-relative-contributions)]
      (mapv (fn [instrument-id share]
              (let [row-target (let [target (get targets-by-id instrument-id)]
                                 (if (finite-number? target)
                                   target
                                   target-share))
                    current-share (get current-by-id instrument-id)
                    deviation (when (and (finite-number? share)
                                         (finite-number? row-target))
                                (- share row-target))]
                {:instrument-id instrument-id
                 :label (or (get labels instrument-id) instrument-id)
                 :weight (get weights instrument-id)
                 :share share
                 :current-share current-share
                 :target-share row-target
                 :deviation-pts (pts deviation)
                 :shift-pts (when (and (finite-number? share)
                                       (finite-number? current-share))
                              (pts (- share current-share)))
                 :negative? (and (finite-number? share) (neg? share))}))
            instrument-ids
            relative-contributions))))

(defn balance-model
  "Chart model from the payload's contribution sections, in one of two display
  modes (`:display-mode`). Nil for non-Equal-Risk results — the sibling
  covariance-only objective carries the same sections as diagnostics, and its
  card owns their presentation.

  `:deviation` (default): the `display-row-cap` worst rows by |deviation from
  target| are selected (so the rows that explain an Approximate verdict always
  survive the cap), then DISPLAYED in signed share descending order — longs
  high-to-low with hedges at the bottom, the designer's diverging silhouette.

  `:shift` (an :exact fit whose result carries current shares): every bar ends
  on the target line, so deviations are ties and the story is the MOVE. Each
  row's `:shift-pts` is recommended − current in points, the cap keeps the
  largest |shift| rows, and display order is current share descending — the
  gray current markers form the descending silhouette and risk visibly drains
  from the top rows into the bottom ones.

  The remainder is summarized honestly instead of silently truncated
  (`:hidden-max-pts` is max |deviation| or max |shift| to match the mode).
  Current shares are nil-safe (old persisted results, flat books); per-row
  targets read the per-instrument section and fall back to the uniform equal
  target. `:current` additionally carries `:biggest-shift`, picked over EVERY
  row before capping."
  [result]
  (when-let [contributions (and (equal-risk-result? result)
                                (:risk-contributions result))]
    (let [{:keys [instrument-ids target-relative-contributions quality
                  rms-error max-absolute-error
                  negative-contribution-count]} contributions
          target-share (first target-relative-contributions)
          all-rows (contribution-rows result)
          shift-mode? (and (= :exact quality)
                           (some #(finite-number? (:shift-pts %)) all-rows))
          cap-key (if shift-mode? :shift-pts :deviation-pts)
          rows (sort-by (fn [row]
                          (- (js/Math.abs (or (get row cap-key) 0))))
                        all-rows)
          display-key (if shift-mode?
                        (fn [{:keys [current-share]}]
                          (- (if (finite-number? current-share)
                               current-share
                               ##-Inf)))
                        (fn [{:keys [share]}]
                          (- (or share ##-Inf))))
          visible (->> (take display-row-cap rows)
                       (sort-by display-key)
                       vec)
          hidden (drop display-row-cap rows)]
      {:rows visible
       :display-mode (if shift-mode? :shift :deviation)
       :asset-count (count instrument-ids)
       :hidden-count (count hidden)
       :hidden-max-pts (when (seq hidden)
                         (reduce max 0 (map #(js/Math.abs (or (get % cap-key) 0))
                                            hidden)))
       :largest (when (seq rows)
                  (-> (apply max-key
                             #(if (finite-number? (:share %)) (:share %) ##-Inf)
                             rows)
                      (select-keys [:instrument-id :label :share
                                    :target-share :deviation-pts])))
       :target-share target-share
       :quality quality
       :rms-pts (pts rms-error)
       :max-pts (pts max-absolute-error)
       :negative-count negative-contribution-count
       :current (when-let [current (:current-risk-contributions result)]
                  {:rms-pts (pts (:rms-error current))
                   :max-pts (pts (:max-absolute-error current))
                   :biggest-shift
                   (let [movers (filter #(finite-number? (:shift-pts %))
                                        all-rows)]
                     (when (seq movers)
                       (-> (apply max-key
                                  #(js/Math.abs (:shift-pts %))
                                  movers)
                           (select-keys [:instrument-id :label
                                         :shift-pts]))))})})))

(defn floored-instrument-ids
  "Assets Equal Risk held at 0%: a zero binding bound plus a zero published
  target weight — the side-locked hedges the objective's positive equal target
  can never include. These drive the Risk-weighted sizing cross-link (the
  objective built to keep every selected asset)."
  [result]
  (let [weights (:target-weights-by-instrument result)]
    (->> (get-in result [:diagnostics :binding-constraints])
         (filter (fn [{:keys [instrument-id bound]}]
                   (and (finite-number? bound)
                        (<= (js/Math.abs bound) 1e-10)
                        (let [weight (get weights instrument-id)]
                          (and (finite-number? weight)
                               (<= (js/Math.abs weight) 1e-10))))))
         (keep :instrument-id)
         distinct
         vec)))

(defn kpi-risk-balance
  "Values for the KPI strip's Risk balance tile: current → recommended max
  deviation in points, RMS, and the signed change (negative = tighter =
  good)."
  [result]
  (let [target-max (pts (get-in result [:risk-contributions :max-absolute-error]))
        current-max (pts (get-in result [:current-risk-contributions
                                         :max-absolute-error]))]
    {:target-max-pts target-max
     :current-max-pts current-max
     :rms-pts (pts (get-in result [:risk-contributions :rms-error]))
     :delta-pts (when (and (finite-number? target-max)
                           (finite-number? current-max))
                  (- target-max current-max))}))

(defn allocation-freedom
  [result]
  (get-in result [:equal-risk-solver :allocation-freedom]))

(defn freedom-view
  "Label + status for the allocation-freedom card/rail row. Nil-safe for
  persisted results that predate the field."
  [freedom]
  (case (:status freedom)
    :fully-determined
    {:status :caution
     :label "Fully determined"
     :detail "Gross exposure and fixed limits determine every weight"}

    :limited
    {:status :caution
     :label "Limited by constraints"
     :detail (let [n (:binding-count freedom)]
               (str n (if (= 1 n) " binding cap restricts" " binding caps restrict")
                    " exact equality"))}

    :open
    {:status :ok
     :label "Open"
     :detail "No binding limits on the balance"}

    {:status :unknown
     :label "—"
     :detail "Not recorded on this result (re-run to compute)"}))

(defn freedom-card-view
  "Compact copy for the why-card's Allocation freedom icon card (designer
  spec): value line + one-line explanation + which lock glyph. Nil-safe for
  persisted results that predate the field."
  [freedom]
  (case (:status freedom)
    :fully-determined
    {:locked? true
     :value "Fully determined"
     :sub "Gross target pins every free weight"}

    :limited
    (let [n (or (:binding-count freedom) 0)]
      {:locked? true
       :value (str "Limited · " n " binding cap" (when (not= 1 n) "s"))
       :sub "Caps constrain exact equality"})

    :open
    {:locked? false
     :value "Open"
     :sub "No binding limits on the balance"}

    {:locked? true
     :value "—"
     :sub "Not recorded on this result"}))

(defn solution-stability
  "Classified from the solver's deterministic starts: agreement of the
  completed initializations' objective values (per-start weights are not
  persisted; objective agreement is the honest proxy for reaching the same
  local solution)."
  [result]
  (let [objectives (->> (get-in result [:equal-risk-solver :initializations])
                        (filter #(= :completed (:status %)))
                        (keep :objective)
                        (filter finite-number?))]
    (cond
      (empty? objectives)
      {:status :unknown
       :label "—"
       :detail "No initialization record on this result"}

      (= 1 (count objectives))
      {:status :ok
       :label "Single start"
       :detail "Only one initialization was feasible"}

      :else
      (let [best (reduce min objectives)
            worst (reduce max objectives)
            spread (- worst best)]
        (if (or (< spread 1e-10)
                (< spread (* 0.05 (max (js/Math.abs best) 1e-12))))
          {:status :ok
           :label "High"
           :detail "All starts reached equivalent solutions"}
          {:status :caution
           :label "Caution"
           :detail "Starts reached materially different local solutions"})))))

(defn stop-reason
  "Human copy for the solver's termination, with the constraint-determined
  override (the most useful explanation when the weights had no freedom)."
  [result]
  (let [termination (get-in result [:equal-risk-solver :termination-reason])
        base (case termination
               :step-tolerance "Projected step tolerance reached"
               :objective-improvement "Objective improvement tolerance reached"
               :max-iterations "Iteration limit reached"
               :line-search-exhausted "Line search exhausted"
               :subproblem-failed "QP subproblem failed — best feasible kept"
               :subproblem-infeasible "QP subproblem infeasible — best feasible kept"
               nil)]
    (if (= :fully-determined (:status (allocation-freedom result)))
      {:label "Allocation fully determined by constraints"
       :detail (or base "The gross target and fixed limits pin every weight")}
      {:label (or base "—")
       :detail "Why the current result is where it is."})))

(defn quality-view
  [quality]
  (case quality
    :exact {:status :ok :label "Exact"}
    :approximate {:status :caution :label "Approximate"}
    :not-converged {:status :bad :label "Not converged"}
    {:status :unknown :label "—"}))

(defn verdict-body
  "The recommendation sentence. Constraint-determined books get the honest
  'the optimizer could not change these weights' framing instead of implying
  a choice was made. Kind-gated (see equal-risk-result?) so the verdict
  or-site never speaks Equal Risk over a sibling objective's diagnostic
  contribution section."
  [result]
  (when-let [contributions (and (equal-risk-result? result)
                                (:risk-contributions result))]
    (let [n (count (:instrument-ids contributions))
          target-share (first (:target-relative-contributions contributions))
          max-pts* (pts (:max-absolute-error contributions))
          freedom (allocation-freedom result)
          gross (get-in result [:diagnostics :gross-exposure])
          net (get-in result [:diagnostics :net-exposure])
          deviation-clause (str "Max contribution deviation "
                                (format-pts max-pts*) ".")]
      (if (= :fully-determined (:status freedom))
        (str "The selected " (format-multiple gross)
             " gross target and fixed limits fully determine these "
             "position sizes; Equal Risk reports the resulting "
             (format-multiple net) " net exposure but cannot improve it. "
             deviation-clause)
        (str "Balances " n " selected positions toward "
             (format-share target-share 1) " of portfolio volatility each "
             "at selected " (format-multiple gross) " gross with resulting "
             (format-multiple net) " net exposure. "
             deviation-clause
             (when (and (= :limited (:status freedom))
                        (pos? (or (:binding-count freedom) 0))
                        (not= :exact (:quality contributions)))
               (str " " (:binding-count freedom)
                    (if (= 1 (:binding-count freedom))
                      " binding cap limits"
                      " binding caps limit")
                    " exact equality.")))))))
