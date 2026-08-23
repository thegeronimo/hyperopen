(ns hyperopen.portfolio.optimizer.domain.return-plausibility
  "Physical bounds on a single period return, and on an estimated annualized
  volatility.

  Nothing between a served price bar and the covariance matrix used to bound
  the MAGNITUDE of a return - only its finiteness. One corrupt bar therefore
  reached the estimator intact, and under the default Ledoit-Wolf model it did
  not merely inflate its own asset: full shrinkage collapsed the covariance to
  a scaled identity and broadcast the poisoned variance onto every diagonal, so
  a levered crypto book reported 8,697.7% annualized volatility with every
  asset - equities and metals included - reading the same ~7,200%.

  The bounds here are measured, not guessed. Across 60 Hyperliquid perpetuals
  and 51,900 daily observations served by the production history backend on
  2026-08-23:

    median |r| 2.66% | p99 19.49% | p99.9 42.44% | p99.99 86.54% | max 126.1%
    observations over 100%: 4 | over 200%: 0 | over 1000%: 0
    per-asset annualized volatility: min 47%, median 110%, p95 188%, max 390%

  So `rejection-bound` clears every real bar in three years of data, while the
  corruption that prompted this namespace (a return near +300, i.e. +30,000%)
  sits 150x above it. `implausible-volatility-bound` sits 14x below the 7,200%
  that shipped and comfortably above the largest real asset.

  Policy, matching `domain.rebalance`'s treatment of an implausible favorable
  fill: a value past the rejection bound is DISCARDED and named, never rescaled
  or clamped. A clamped return is a plausible-looking number derived from data
  the system knows is wrong - the same dishonesty as the number this namespace
  exists to prevent. Values past `advisory-bound` are real (BOME did move 126%
  in a day) and are KEPT, with disclosure.

  All magnitudes are decimal fractions, matching the engine: 2.0 is +200%."
  (:require [hyperopen.portfolio.optimizer.domain.math :as math]))

(def rejection-bound
  "A single period simple return at or beyond this magnitude is treated as a
  data error and discarded. 2.0 = +/-200% in one bar."
  2.0)

(def advisory-bound
  "A single period simple return at or beyond this magnitude is kept but
  disclosed. 1.0 = +/-100% in one bar."
  1.0)

(def implausible-volatility-bound
  "An estimated ANNUALIZED volatility at or beyond this magnitude cannot be a
  market number and indicates a broken estimate. 5.0 = 500% annualized."
  5.0)

(defn implausible-return?
  "True when `value` is a finite return past `rejection-bound`. A non-finite
  value is NOT implausible here - existing finiteness guards own that case, and
  answering true would silently widen their behavior."
  [value]
  (and (math/finite-number? value)
       (>= (js/Math.abs value) rejection-bound)))

(defn extreme-return?
  "True when `value` is a finite return past `advisory-bound` but still inside
  `rejection-bound` - a real but remarkable move worth disclosing."
  [value]
  (and (math/finite-number? value)
       (>= (js/Math.abs value) advisory-bound)
       (not (implausible-return? value))))

(defn usable-return?
  "True when `value` is finite and inside `rejection-bound`. This is the
  predicate that replaces a bare finiteness check at every seam where returns
  enter a risk or return model."
  [value]
  (and (math/finite-number? value)
       (not (implausible-return? value))))

(defn implausible-volatility?
  "True when `value`, an annualized volatility, is finite and past
  `implausible-volatility-bound`."
  [value]
  (and (math/finite-number? value)
       (>= value implausible-volatility-bound)))

(defn contaminated-return-indices
  "Indices to discard from a sequential return series in which the return at
  index i was computed from the price at i and the price at i-1.

  A single corrupt price produces TWO bad returns, not one: the spike and the
  revert. Only the spike breaks the bound - a price jumping to 400x gives +40000%
  (rejected) and then -99.75% coming back, which is inside any sane bound yet
  just as poisonous to a variance. So an implausible return at i also condemns
  the return at i+1, which shares the same suspect price.

  This is symmetric in direction. A price collapsing to near zero gives -99.99%
  first (bounded, kept on its own) and then +10000% (rejected), so condemning
  i+1 alone would be wrong; the pass below also condemns the neighbour BEFORE a
  rejected return via the two-sided sweep in `contaminated-pair-indices`. For a
  flat return series the price at i is the shared one, so i and i+1 suffice."
  [returns]
  (into #{}
        (mapcat (fn [idx]
                  (when (implausible-return? (nth returns idx nil))
                    [idx (inc idx)])))
        (range (count returns))))

(defn contaminated-pair-indices
  "Indices to discard from a sequence of [previous current] price pairs, where
  pair i spans rows i and i+1.

  An implausible pair implicates BOTH of its rows - we cannot tell which close
  is the bad one, and the direction of the spike decides the answer - so every
  pair touching either row goes: i-1, i and i+1. For a 423-observation series
  one corrupt close therefore costs three returns instead of poisoning all of
  them."
  [pair-returns]
  (into #{}
        (mapcat (fn [idx]
                  (when (implausible-return? (nth pair-returns idx nil))
                    [(dec idx) idx (inc idx)])))
        (range (count pair-returns))))

(defn rejected-observation-warning
  "Warning for one discarded observation. `time-ms` may be nil when the seam
  has no timestamp (a per-instrument series with no shared calendar)."
  [instrument-id time-ms value]
  (cond-> {:code :implausible-return-observation
           :instrument-id instrument-id
           :return value
           :bound rejection-bound
           :message (str "A "
                         (.toFixed (* 100 value) 1)
                         "% single-bar return is not a market move. That bar "
                         "and the ones adjoining it were discarded before "
                         "estimating risk, because a corrupt price poisons "
                         "both the move into it and the move back out. Returns "
                         "beyond +/-"
                         (.toFixed (* 100 rejection-bound) 0)
                         "% are treated as bad data.")}
    (math/finite-number? time-ms)
    (assoc :time-ms time-ms)))

(defn extreme-observation-warning
  "Warning for one kept-but-remarkable observation."
  [instrument-id time-ms value]
  (cond-> {:code :extreme-return-observation
           :instrument-id instrument-id
           :return value
           :bound advisory-bound
           :message (str "A "
                         (.toFixed (* 100 value) 1)
                         "% single-bar return was kept as real, but it "
                         "dominates the risk estimate for this asset.")}
    (math/finite-number? time-ms)
    (assoc :time-ms time-ms)))
