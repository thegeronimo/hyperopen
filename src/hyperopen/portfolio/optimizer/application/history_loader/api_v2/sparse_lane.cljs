(ns hyperopen.portfolio.optimizer.application.history-loader.api-v2.sparse-lane
  "Which universe members leave the SHARED DAILY CALENDAR while staying in the
  optimized universe.

  Background. The shared calendar (`history-loader.calendar/common-calendar`) is a
  set intersection of every member's timestamps, so one member sampled far apart
  shrinks the estimation window for everybody. Hyperliquid downsamples every vault
  history window to a fixed ~35-66 point budget, so a two-year-old vault arrives
  with samples ~12-14 days apart. Live 2026-08-25: admitting one such vault to the
  calendar alongside a 20-bar young listing left THREE shared timestamps - two
  return observations for the entire universe, down from ~1095.

  The member itself is not the problem: `domain.risk-mixed-frequency` already
  estimates a sparse member's covariance from its own sample times via
  `row-at-or-before`, needing no timestamp agreement at all. So such a member is
  moved OFF the calendar rather than excluded from the run: it keeps its place in
  `:eligible-instruments` and is fed to the mixed-frequency estimator from its
  native series, while the dense members keep their full daily window."
  (:require [hyperopen.portfolio.metrics.history :as metrics-history]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.domain.history-series :as history-series]
            [hyperopen.portfolio.optimizer.domain.math :as math]))

(def off-calendar-min-observations
  "Minimum native samples before a sparse member may ride the off-calendar lane.

  PINNED to the readiness assumption gate on purpose. `setup-readiness`'s
  `short-history-assumption-warnings` and `view-model.universe`'s
  `assumption-required-ids` both BLOCK the run below
  `assumption-required-max-observations`. A lower floor here would admit a member
  natively and then block the run demanding a proxy for that same member - a
  contradiction the user cannot resolve. Raising the readiness gate must raise
  this with it, which is why it is a reference and not a literal."
  history-assumptions/assumption-required-max-observations)

(def off-calendar-min-elapsed-days
  "Minimum span the samples must cover. A handful of scattered RECENT points is a
  thin-history asset (the existing assumption flow is right for it); the lane is
  for series that genuinely reach back, they are just published far apart."
  90)

(defn- elapsed-days
  [points]
  (let [first-ms (:time-ms (first points))
        last-ms (:time-ms (peek points))]
    (when (and (math/finite-number? first-ms)
               (math/finite-number? last-ms))
      (/ (- last-ms first-ms) metrics-history/day-ms))))

(defn possibly-sparse?
  "Cheap prefilter so the O(n) `cadence-summary` runs only for plausible
  candidates.

  It tests EXACTLY the two floors `off-calendar-ids` requires anyway, and nothing
  else, so it can never produce a false negative. A density prefilter derived from
  the raw point count would NOT be safe: `cadence-summary` counts intervals AFTER
  return-plausibility contamination is discarded, so a series with many points can
  still report a low interval count and classify sparse."
  [points]
  (let [points* (vec points)]
    (and (>= (count points*) off-calendar-min-observations)
         (let [span (elapsed-days points*)]
           (and (math/finite-number? span)
                (>= span off-calendar-min-elapsed-days))))))

(defn- lane-cadence
  "The cadence summary for a row that qualifies for the lane, or nil."
  [points]
  (when (possibly-sparse? points)
    (let [cadence (history-series/cadence-summary (vec points))]
      (when (and (:sparse? cadence)
                 (>= (or (:observations cadence) 0) off-calendar-min-observations)
                 (>= (or (:elapsed-days cadence) 0) off-calendar-min-elapsed-days))
        cadence))))

(defn cadence-by-id
  "instrument-id -> cadence summary, for every row that qualifies for the lane.
  Rows the backend already serves on its own aligned calendar are skipped: a
  member the backend can carry stays exactly where it is, on that calendar.

  DEGENERATE GUARD: when EVERY candidate qualifies there is no dense member whose
  window needs protecting, so the lane is empty and today's behaviour stands (an
  all-vault universe keeps aligning against itself)."
  [base-candidates aligned-usable?]
  (let [qualifying (into {}
                         (keep (fn [{:keys [instrument-id series]}]
                                 (when (and instrument-id
                                            (seq (:points series))
                                            (not (aligned-usable? instrument-id)))
                                   (when-let [cadence (lane-cadence (:points series))]
                                     [instrument-id cadence]))))
                         base-candidates)]
    (if (every? #(contains? qualifying (:instrument-id %)) base-candidates)
      {}
      qualifying)))

(defn off-calendar-ids
  [base-candidates aligned-usable?]
  (set (keys (cadence-by-id base-candidates aligned-usable?))))

(defn warning
  "Informational, NEVER blocking: it explains how the member was estimated, it
  does not report a defect."
  [instrument-id cadence]
  {:code :sparse-native-history
   :instrument-id instrument-id
   :observations (:observations cadence)
   :interval-count (:interval-count cadence)
   :elapsed-days (:elapsed-days cadence)
   :median-dt-days (:median-dt-days cadence)
   :policy :pairwise-interval-aggregation})
