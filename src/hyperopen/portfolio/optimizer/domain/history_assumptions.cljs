(ns hyperopen.portfolio.optimizer.domain.history-assumptions
  "Pure policy + constants for per-asset history assumptions.

  Some selected optimizer assets have little or no return history. The engine
  cannot estimate covariance for them from realized series, so it drops them and
  the run is blocked. A history assumption lets the user supply a stand-in risk
  treatment for such an asset:

    :conservative - no diversification credit. Use the user's volatility and a
                    correlation floor against the rest of the universe, capped to
                    a small max weight. Engine-backed via
                    `risk/augment-risk-result-with-assumptions`.
    :proxy        - model the asset as a basket of user-selected proxy assets
                    that DO have usable history. The qualitative prior (the
                    selected basket) chooses the exposure; a regularized
                    regression on the asset's short overlap adjusts it;
                    confidence governs the shrinkage between the two; specific
                    risk and a tight cap keep the optimizer honest. Engine-backed
                    via `history-assumption-proxy/augment-risk-result-with-proxy-assumptions`.

  (An earlier :proxy mode - a single related asset at a chosen relationship
  strength - was removed because it was collected but never engine-backed. Its
  persisted entries are still migrated to conservative on load; the migration
  keys on the legacy singular :proxy-instrument-id shape, so the new multi-proxy
  shape passes through. See contracts.migrations.)

  This namespace is the ONE source of truth for the numeric presets so the engine,
  request-builder, readiness, defaults, and view models never disagree. All
  magnitudes are decimals (5% => 0.05)."
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def behaviors
  "Supported history-assumption behaviors."
  #{:conservative :proxy})

(def conservative-correlation-floor
  "Correlation a conservative-mode asset is assumed to have against every other
  asset in the universe (no diversification credit)."
  0.75)

(def default-conservative-max-weight
  "Default max-weight cap for a conservative-mode assumption (3%)."
  0.03)

(def default-proxy-max-weight
  "Default max-weight cap for a proxy-mode assumption (5%). Slightly looser than
  conservative because the basket carries real covariance structure, but still
  tight: the exposure estimate rests on an assumption, not on realized history."
  0.05)

(def default-conservative-volatility
  "Pre-seeded, editable annual-volatility anchor for a no/short-history asset (80%).
  Deliberately high: with no realized history the asset is treated as risky until the
  user revises it. Shared by both behaviors."
  0.8)

(def default-conservative-return
  "Pre-seeded, editable annual expected-return anchor for a no/short-history asset
  (0% - no return edge assumed). Shared by both behaviors."
  0.0)

(def relationship-strengths
  "User-stated qualitative similarity between a proxy-mode asset and its basket."
  #{:low :medium :high})

(def default-relationship-strength
  :medium)

(def proxy-min-specific-risk-share
  "Minimum share of the user's stated variance that is treated as specific
  (idiosyncratic) risk the proxy basket cannot explain, by relationship strength.
  A weaker stated relationship means more of the asset's risk is assumed unique,
  so the optimizer can never treat the basket as a clone."
  {:low 0.5
   :medium 0.25
   :high 0.15})

(def short-history-min-observations
  "User-facing threshold for \"short history\", in native daily observations (~days).
  An asset is \"short\" when it has less than ~a year of returns, independent of the
  optimizer's (larger, ~3-year) request window: this flags genuinely thin-history
  assets, not ones that merely fall short of the full fetch window. The bar sits just
  under a calendar year (~365) to tolerate a few missing daily candles in an
  otherwise-full year. The engine's own minimum is only 1-2 observations, so this is
  the meaningful bar. Tune this single constant to taste."
  360)

(def assumption-required-max-observations
  "Below this many native daily observations an asset's realized covariance is
  indefensible AND (worse) its calendar intersection poisons the shared estimation
  window of every other asset, so readiness blocks the run until the asset carries a
  complete history assumption (proxy or conservative). Only enforced when the
  universe holds at least one asset with `short-history-min-observations` of native
  history - in an all-young universe there is nothing to borrow from, and the
  long-standing thin-history behavior stands."
  30)

(defn default-max-weight
  "Default max-weight cap for the given behavior."
  [behavior]
  (case behavior
    :conservative default-conservative-max-weight
    :proxy default-proxy-max-weight
    nil))

(defn default-assumption
  "Default per-asset history-assumption draft for the given behavior. All fields are
  pre-seeded with editable anchors (a conservative volatility and a 0% expected
  return) so the user can accept or revise them rather than starting from blank.
  Returns nil for an unknown behavior."
  [behavior]
  (case behavior
    :conservative {:behavior :conservative
                   :expected-return default-conservative-return
                   :volatility default-conservative-volatility
                   :max-weight default-conservative-max-weight
                   :correlation-floor conservative-correlation-floor}
    :proxy {:behavior :proxy
            :expected-return default-conservative-return
            :volatility default-conservative-volatility
            :max-weight default-proxy-max-weight
            :proxy {:instrument-ids []
                    :relationship-strength default-relationship-strength
                    :prior-weights nil}}
    nil))

(def objectives-needing-expected-return
  "Objective kinds that score on expected returns, so a no/short-history asset must
  supply an expected return. Minimum-variance and equal-risk are covariance-only
  and stay out of this set."
  #{:max-sharpe :target-return :target-volatility})

(defn return-required-for-objective?
  [objective-kind]
  (contains? objectives-needing-expected-return objective-kind))

(defn conservative?
  [entry]
  (= :conservative (:behavior entry)))

(defn proxy?
  [entry]
  (= :proxy (:behavior entry)))

(defn proxy-instrument-ids
  "Selected proxy ids of a proxy entry, tolerant of both the draft shape
  (nested :proxy submap) and the engine-request shape (flattened)."
  [entry]
  (vec (or (:proxy-instrument-ids entry)
           (get-in entry [:proxy :instrument-ids])
           [])))

(defn relationship-strength
  "Normalized relationship strength of a proxy entry (defaults to :medium)."
  [entry]
  (let [strength (or (:relationship-strength entry)
                     (get-in entry [:proxy :relationship-strength]))]
    (if (contains? relationship-strengths strength)
      strength
      default-relationship-strength)))

(defn proxy-prior-weights
  "Explicit prior weights of a proxy entry when present (draft or engine shape);
  nil means equal weight."
  [entry]
  (or (:proxy-prior-weights entry)
      (get-in entry [:proxy :prior-weights])))

(defn- valid-cap?
  [entry]
  (coercion/positive-number? (:max-weight entry)))

(defn- valid-return?
  [entry return-required?]
  (or (not return-required?)
      (coercion/finite-number? (:expected-return entry))))

(defn conservative-assumption-complete?
  "True when a conservative entry has everything required to optimize. Expected
  return is required only for return-seeking objectives."
  [entry return-required?]
  (and (conservative? entry)
       (coercion/positive-number? (:volatility entry))
       (coercion/finite-number? (:correlation-floor entry))
       (valid-cap? entry)
       (valid-return? entry return-required?)))

(defn unusable-proxy-ids
  "Selected proxy ids that lack usable optimizer risk history. `usable-proxy-ids`
  is the set of instrument ids whose realized history reached the risk model (and
  that do not themselves lean on an assumption); nil means \"unknown here\" and
  skips the check."
  [entry usable-proxy-ids]
  (if (nil? usable-proxy-ids)
    []
    (vec (remove #(contains? usable-proxy-ids %)
                 (proxy-instrument-ids entry)))))

(defn first-missing-proxy-field
  "The first blocking gap of a proxy entry, or nil when the entry is complete.
  ctx keys (all optional - absent context skips that check):
    :self-id           the assumed asset's own instrument id
    :return-required?  objective needs expected returns
    :usable-proxy-ids  set of ids with usable realized risk history
    :max-asset-weight  global cap the proxy cap must not exceed"
  [entry {:keys [self-id return-required? usable-proxy-ids max-asset-weight]}]
  (let [ids (proxy-instrument-ids entry)]
    (cond
      (not (proxy? entry))
      :behavior

      (empty? ids)
      :proxy-instruments

      (and self-id (some #(= self-id %) ids))
      :self-proxy

      (seq (unusable-proxy-ids entry usable-proxy-ids))
      :proxy-history

      (not (coercion/positive-number? (:volatility entry)))
      :volatility

      (not (valid-cap? entry))
      :max-weight

      (and (coercion/finite-number? max-asset-weight)
           (> (:max-weight entry) max-asset-weight))
      :max-weight-exceeds-global

      (not (valid-return? entry (boolean return-required?)))
      :expected-return

      :else nil)))

(defn proxy-assumption-complete?
  "True when a proxy entry has everything the engine needs: non-empty proxies that
  are not the asset itself (and, when the context knows, have usable history), a
  positive volatility, a positive cap no looser than the global max asset weight,
  and an expected return when the objective requires one."
  [entry ctx]
  (and (proxy? entry)
       (nil? (first-missing-proxy-field entry (or ctx {})))))

(defn assumption-complete?
  "Behavior-dispatched completeness. The 2-arity form checks what is knowable from
  the entry alone (view models); the 3-arity form adds validation context
  (readiness, request building) - see `first-missing-proxy-field` for ctx keys."
  ([entry return-required?]
   (assumption-complete? entry return-required? nil))
  ([entry return-required? ctx]
   (cond
     (conservative? entry)
     (conservative-assumption-complete? entry return-required?)

     (proxy? entry)
     (proxy-assumption-complete? entry (assoc (or ctx {})
                                              :return-required? return-required?))

     :else false)))

(defn conservative-engine-inputs
  "Extracts the conservative assumptions the engine can synthesize into the
  covariance model: those whose asset is actually in the request universe and that
  carry the volatility + correlation floor synthesis needs. Keyed by instrument-id."
  [request]
  (let [universe-ids (set (keep :instrument-id (:universe request)))]
    (reduce-kv (fn [acc id entry]
                 (if (and (conservative? entry)
                          (contains? universe-ids id)
                          (coercion/positive-number? (:volatility entry))
                          (coercion/finite-number? (:correlation-floor entry)))
                   (assoc acc id (select-keys entry [:volatility
                                                     :correlation-floor
                                                     :expected-return]))
                   acc))
               {}
               (:history-assumptions request))))

(defn proxy-engine-inputs
  "Extracts the proxy assumptions the engine can synthesize into the covariance
  model: those whose asset is in the request universe and that carry the proxies +
  volatility the synthesis needs. Entries arrive in the flattened engine shape
  produced by the request builder (proxy ids, normalized prior weights,
  relationship strength, and the short-overlap regression series). Keyed by
  instrument-id."
  [request]
  (let [universe-ids (set (keep :instrument-id (:universe request)))]
    (reduce-kv (fn [acc id entry]
                 (if (and (proxy? entry)
                          (contains? universe-ids id)
                          (coercion/positive-number? (:volatility entry))
                          (seq (proxy-instrument-ids entry)))
                   (assoc acc id
                          (-> (select-keys entry [:volatility
                                                  :expected-return
                                                  :max-weight
                                                  :regression-series])
                              (assoc :behavior :proxy
                                     :proxy-instrument-ids (proxy-instrument-ids entry)
                                     :proxy-prior-weights (proxy-prior-weights entry)
                                     :relationship-strength (relationship-strength entry))))
                   acc))
               {}
               (:history-assumptions request))))

(defn history-assumption-engine-inputs
  "Both engine-backed input families, extracted from a built engine request."
  [request]
  {:conservative (conservative-engine-inputs request)
   :proxy (proxy-engine-inputs request)})

(defn augment-expected-returns
  "Overrides the expected return for each engine-backed assumption asset with the
  user's stated value (used by return-seeking objectives; harmless for min-var).
  Works for conservative and proxy inputs alike - never a raw short-history mean."
  [return-result assumptions-by-id]
  (if (empty? assumptions-by-id)
    return-result
    (update return-result :expected-returns-by-instrument
            (fn [by-instrument]
              (reduce-kv (fn [acc id entry]
                           (if (coercion/finite-number? (:expected-return entry))
                             (assoc acc id (:expected-return entry))
                             acc))
                         (or by-instrument {})
                         assumptions-by-id)))))
