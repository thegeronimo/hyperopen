(ns hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
  "Read models for the Equal Risk correlation/decomposition views (the
  CORRELATION and BREAKDOWN tabs of the risk-contribution card, plus the
  Allocation table's P&L-correlation line and row selection). Pure functions
  over the solved result's :risk-structure payload section — persisted
  pre-structure results simply return nil models and the views degrade to the
  original two tabs.

  The position-P&L correlation matrix is DERIVED here from the persisted
  underlying matrix: Corr(s_i r_i, s_j r_j) = s_i * s_j * Corr(r_i, r_j),
  with s the sign of the published target weight. Direction, correlation and
  risk contribution stay three separate concepts: nothing here infers a
  contribution sign from a position side."
  (:require [hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
             :as equal-risk-results]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-diversification
             :as diversification-model]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def ^:private finite-number? coercion/finite-number?)

(def neutral-correlation-threshold
  "|position correlation| below this displays as 0.00, so the tooltip verdict
  says Neutral instead of over-reading float dust as a hedge."
  0.005)

(def cross-effect-neutral-threshold
  "Cross-covariance effects below half of one displayed tenth of a percentage
  point render as neutral, so rounded 0.0 pts is never colored red or green."
  diversification-model/cross-effect-neutral-threshold)

(defn risk-view-radio-name
  "The DOM-state tab radio group for a result's risk card. Suffixed with the
  result's :as-of-ms so co-rendered fixtures don't share a group."
  [result]
  (str "optimizer-risk-view-" (or (:as-of-ms result) "result")))

(defn risk-view-radio-id
  "Deterministic id for one tab's radio input, so out-of-card labels (the
  why-card's Correlation view card) can activate a tab with plain label/for."
  [result view]
  (str (risk-view-radio-name result) "-" view))

(defn format-pct
  ([value] (format-pct value 1))
  ([value decimals]
   (if (finite-number? value)
     (str (.toFixed (* 100 value) decimals) "%")
     "—")))

(defn format-correlation
  [value]
  (if (finite-number? value)
    (.toFixed value 2)
    "—"))

(defn format-signed-correlation
  [value]
  (if (finite-number? value)
    (str (when (>= value 0) "+") (.toFixed value 2))
    "—"))

(defn side-label
  [side]
  (case side
    :long "Long"
    :short "Short"
    nil))

(defn instrument-side
  "Position side implied by the published target weight: :long, :short, or
  nil for an unheld (zero-weight) instrument."
  [result instrument-id]
  (let [weight (get-in result [:target-weights-by-instrument instrument-id])]
    (cond
      (and (finite-number? weight) (pos? weight)) :long
      (and (finite-number? weight) (neg? weight)) :short
      :else nil)))

(defn- label-for
  [result instrument-id]
  (or (get-in result [:labels-by-instrument instrument-id]) instrument-id))

(defn- net-shares-by-instrument
  [result]
  (into {}
        (filter (comp finite-number? val))
        (get-in result [:risk-contributions
                        :relative-contributions-by-instrument])))

(defn default-selected-instrument
  "The asset the breakdown panel explains before any click: the most negative
  net contributor when one exists (the page's headline anomaly — it has its
  own KPI cell), else the largest |net| contributor."
  [result]
  (let [nets (net-shares-by-instrument result)]
    (when (seq nets)
      (let [negatives (filter (comp neg? val) nets)]
        (if (seq negatives)
          (key (apply min-key val negatives))
          (key (apply max-key (comp js/Math.abs val) nets)))))))

(defn selected-instrument
  "The explicitly selected instrument when it exists on this result, else the
  default. A stale selection (re-solve dropped the asset) never renders."
  [result explicit-id]
  (let [nets (net-shares-by-instrument result)]
    (if (and explicit-id (contains? nets explicit-id))
      explicit-id
      (default-selected-instrument result))))

(defn pnl-portfolio-correlation
  "Corr(s_i r_i, r_p) for the Allocation table's per-row line; nil when the
  result predates :risk-structure or the instrument holds no position."
  [result instrument-id]
  (get-in result [:risk-structure
                  :pnl-portfolio-correlation-by-instrument
                  instrument-id]))

(defn pnl-correlation-presentation
  "Economic direction and non-color copy for one held-position P&L
  correlation. Negative offsets portfolio risk; positive amplifies it."
  [correlation]
  (when (finite-number? correlation)
    (let [effect (cond
                   (< (js/Math.abs correlation)
                      neutral-correlation-threshold) "neutral"
                   (neg? correlation) "offsets"
                   :else "amplifies")
          formatted (format-signed-correlation correlation)]
      {:correlation formatted
       :effect effect
       :title (str "Held-position P&L correlation " formatted " " effect
                   " portfolio risk")})))

(defn breakdown-asset-options
  "Options for the per-asset breakdown panel's Change-asset select: every
  instrument with a finite net contribution (the full universe — the
  decomposition maps are never capped, unlike the correlation matrix), sorted
  by label so a long book scans alphabetically. A zero-weight instrument with
  a zero contribution is not held and is dropped — there is nothing to
  inspect. Empty when the result carries no contributions."
  [result]
  (->> (net-shares-by-instrument result)
       (keep (fn [[instrument-id net]]
               (let [side (instrument-side result instrument-id)]
                 (when (or side (not (zero? net)))
                   {:instrument-id instrument-id
                    :label (label-for result instrument-id)
                    :side side}))))
       (sort-by :label)
       vec))

(defn- pair-effect
  [position diagonal?]
  (cond
    diagonal? nil
    (not (finite-number? position)) nil
    (< (js/Math.abs position) neutral-correlation-threshold) :neutral
    (neg? position) :diversifying
    :else :amplifying))

(defn cell-title
  "Native-tooltip copy for one heatmap cell: both correlations plus the
  portfolio-risk verdict, so a user never has to infer one matrix from the
  other. `row` and `col` are entries of the correlation model."
  [row col {:keys [underlying position effect diagonal?]}]
  (let [pair (str (:label row)
                  (some->> (side-label (:side row)) (str " "))
                  " × "
                  (:label col)
                  (some->> (side-label (:side col)) (str " ")))]
    (if diagonal?
      (str (:label row) (some->> (side-label (:side row)) (str " "))
           " · correlation with itself is 1.00")
      (str pair
           "\nUnderlying-return correlation " (format-signed-correlation underlying)
           "\nPosition-P&L correlation " (format-signed-correlation position)
           "\nEffect on portfolio risk: "
           (case effect
             :diversifying "Diversifying"
             :amplifying "Amplifying"
             :neutral "Neutral"
             "—")))))

(defn correlation-model
  "The heatmap model: entries (id/label/side, display order persisted by the
  payload — signed net share descending, matching the balance chart), a cell
  matrix carrying BOTH the underlying and the derived position-P&L
  correlation, and the honest hidden-count when the payload cap bit. Nil when
  the result predates :risk-structure or nothing is held."
  [result]
  (let [correlation (get-in result [:risk-structure :correlation])
        ids (:instrument-ids correlation)]
    (when (seq ids)
      (let [entries (mapv (fn [instrument-id]
                            {:instrument-id instrument-id
                             :label (label-for result instrument-id)
                             :side (instrument-side result instrument-id)})
                          ids)
            signs (mapv (fn [{:keys [side]}]
                          (case side :long 1 :short -1 nil))
                        entries)
            matrix (:matrix correlation)
            cells (vec
                   (map-indexed
                    (fn [row-idx row]
                      (vec
                       (map-indexed
                        (fn [col-idx underlying]
                          (let [diagonal? (= row-idx col-idx)
                                sign-row (nth signs row-idx)
                                sign-col (nth signs col-idx)
                                position (when (and (finite-number? underlying)
                                                    sign-row
                                                    sign-col)
                                           (* sign-row sign-col underlying))]
                            {:underlying (when (finite-number? underlying)
                                           underlying)
                             :position position
                             :diagonal? diagonal?
                             :effect (pair-effect position diagonal?)}))
                        row)))
                    matrix))]
        {:entries entries
         :cells cells
         :hidden-count (or (:hidden-count correlation) 0)}))))

(defn breakdown-rows
  "Balance-chart rows (same cap, same signed-share display order) enriched
  with the standalone/diversification split, for the BREAKDOWN tab. Rows
  without structure data (should not happen on same-run payloads) carry nils
  and render as em-dashes."
  [result balance-rows]
  (let [structure (:risk-structure result)]
    (when structure
      (mapv (fn [{:keys [instrument-id] :as row}]
              (assoc row
                     :side (instrument-side result instrument-id)
                     :standalone (get-in structure
                                         [:standalone-share-by-instrument
                                          instrument-id])
                     :diversification (get-in structure
                                              [:diversification-share-by-instrument
                                               instrument-id])))
            balance-rows))))

(defn- correlation-direction
  [effect]
  (diversification-model/cross-effect-direction effect))

(defn diversification-comparison-model
  "Current/recommended portfolio benchmark cards on one absolute volatility
  scale. Invalid or unavailable entries are omitted instead of becoming
  zero-position markers; nil means no valid persisted summary exists."
  [result]
  (diversification-model/comparison-model result))

(defn selected-breakdown
  "The CONTRIBUTION BREAKDOWN panel model for the selected (or defaulted)
  instrument: label, side, and the standalone + diversification = net story.
  Nil when the result carries no structure section or no contributions."
  [result explicit-id]
  (when-let [structure (:risk-structure result)]
    (when-let [instrument-id (selected-instrument result explicit-id)]
      (let [net (get (net-shares-by-instrument result) instrument-id)]
        {:instrument-id instrument-id
         :label (label-for result instrument-id)
         :side (instrument-side result instrument-id)
         :standalone (get-in structure [:standalone-share-by-instrument
                                        instrument-id])
         :diversification (get-in structure [:diversification-share-by-instrument
                                             instrument-id])
         :net net
         :negative? (and (finite-number? net) (neg? net))
         :target-share (first (get-in result [:risk-contributions
                                              :target-relative-contributions]))}))))

(defn- summary-tile
  [rms-pts target-share]
  (let [target-pts (when (finite-number? target-share) (* 100 target-share))
        tone (equal-risk-results/deviation-tone rms-pts target-pts)]
    {:key :summary
     :icon :shield
     :icon-tone (case tone :good "info" :caution "warn" :bad "warn" "info")
     :label "Equal-risk summary"
     :value (str "RMS deviation " (equal-risk-results/format-pts rms-pts))
     :sub (if (finite-number? target-share)
            (case tone
              :good (str "All assets near " (format-pct target-share)
                         " target")
              :caution (str "Assets spread around " (format-pct target-share)
                            " target")
              :bad (str "Contributions far from " (format-pct target-share)
                        " target")
              (str "Target " (format-pct target-share) " per asset"))
            "—")}))

(defn- diversification-tile
  [label diversification]
  (let [div-pts (when (finite-number? diversification)
                  (* 100 diversification))
        direction (when (finite-number? diversification)
                    (correlation-direction diversification))]
    {:key :diversification
     :icon :arrows
     :icon-tone (case direction :offsets "long" :amplifies "short" "info")
     :label (str label " cross covariance")
     :value (case direction
              :offsets (str (equal-risk-results/format-signed-pts div-pts)
                            " offsets")
              :amplifies (str (equal-risk-results/format-signed-pts div-pts)
                              " amplifies")
              :neutral "0.0 pts"
              "—")
     :sub (case direction
            :offsets "Offsets risk at final weights"
            :amplifies "Amplifies risk at final weights"
            :neutral "Neutral at final weights"
            "—")}))

(defn- net-tile
  [net target-share]
  (let [deviation-pts (when (and (finite-number? net)
                                 (finite-number? target-share))
                        (* 100 (- net target-share)))
        target-pts (when (finite-number? target-share) (* 100 target-share))]
    {:key :net
     :icon :scale
     :icon-tone (case (equal-risk-results/deviation-tone
                       deviation-pts target-pts)
                  :good "info"
                  :caution "warn"
                  :bad "warn"
                  "info")
     :label "Net contribution"
     :value (if (finite-number? net)
              (str (format-pct net) " of total risk")
              "—")
     :sub (if (finite-number? deviation-pts)
            (str (equal-risk-results/format-signed-pts deviation-pts)
                 " vs " (format-pct target-share) " target")
            "—")}))

(defn- freedom-tile
  [result]
  (let [freedom (equal-risk-results/freedom-card-view
                 (equal-risk-results/allocation-freedom result))]
    {:key :freedom
     :icon (if (:locked? freedom) :lock :lock-open)
     :icon-tone (if (:locked? freedom) "warn" "long")
     :label "Allocation freedom"
     :value (:value freedom)
     :sub (:sub freedom)}))

(defn asset-breakdown-tiles
  "The four summary tiles under the per-asset CONTRIBUTION BREAKDOWN panel
  (designer spec 2026-07-11, per-asset breakdown): the book-level equal-risk
  fit, the selected asset's diversification benefit/cost, its net share vs the
  equal target, and allocation freedom (same copy as the why-card's fact
  card). Data only — the view maps :icon/:icon-tone to markup. `selected` is
  a `selected-breakdown` map; nil in, nil out."
  [result {:keys [label diversification net target-share] :as selected}]
  (when selected
    (let [rms-pts (let [rms (get-in result [:risk-contributions :rms-error])]
                    (when (finite-number? rms) (* 100 rms)))]
      [(summary-tile rms-pts target-share)
       (diversification-tile label diversification)
       (net-tile net target-share)
       (freedom-tile result)])))

(defn fit-scale
  "One shared axis for decomposition lanes: fits zero plus every finite value,
  pads 8% (at least 2 pts), and rounds outward to clean 5% ticks. Returns
  {:lo :hi :x (fraction -> 0..100 CSS percent)} — the same contract as the
  balance chart's lane scale, without its current-marker special cases."
  [values]
  (let [finite (cons 0 (filter finite-number? values))
        lo0 (reduce min 0 finite)
        hi0 (reduce max 0 finite)
        pad (max 0.02 (* 0.08 (- hi0 lo0)))
        lo (* 0.05 (js/Math.floor (/ (- lo0 pad) 0.05)))
        hi (* 0.05 (js/Math.ceil (/ (+ hi0 pad) 0.05)))
        span (max 1e-9 (- hi lo))]
    {:lo lo
     :hi hi
     :x (fn [value]
          (-> (* 100 (/ (- value lo) span))
              (max 0)
              (min 100)))}))

(defn breakdown-bridge-model
  "Anchored own -> cross-covariance -> net geometry for all-assets rows.
  The shared scale fits cumulative endpoints (zero, own, and net), never the
  cross component as if it started from zero."
  [rows]
  (diversification-model/bridge-model fit-scale rows))

(defn scale-ticks
  "Clean tick values for a fit-scale, capped at `max-labels` (default 8 —
  narrow lanes like the correlation tab's breakdown block pass fewer)."
  ([scale] (scale-ticks scale 8))
  ([{:keys [lo hi]} max-labels]
   (let [span (- hi lo)
         step (or (first (filter #(<= (/ span %) max-labels)
                                 [0.05 0.1 0.2 0.25 0.5 1 2 5]))
                  5)]
     (->> (range (js/Math.ceil (/ lo step)) (inc (js/Math.floor (/ hi step))))
          (map #(* step %))))))
