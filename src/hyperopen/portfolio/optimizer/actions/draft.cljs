(ns hyperopen.portfolio.optimizer.actions.draft
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.actions.draft-options :as draft-options]
            [hyperopen.portfolio.optimizer.application.assumption-library :as assumption-library]
            [hyperopen.portfolio.optimizer.application.black-litterman-editor-model :as bl-model]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as history-api-v2]
            [hyperopen.portfolio.optimizer.application.history-prefetch :as history-prefetch]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.return-inputs :as return-inputs]
            [hyperopen.portfolio.optimizer.application.return-views :as return-views]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.view-library :as view-library]
            [hyperopen.portfolio.optimizer.actions.run :as run-actions]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(defn- set-draft-model
  [path models value]
  (if-let [model (get models (common/normalize-keyword-like value))]
    (common/save-draft-path-values [[path model]])
    []))

(defn- current-objective-menu-option
  ;; The menu key follows the OBJECTIVE only: return views are an input policy,
  ;; not an objective, so a Black-Litterman return model no longer claims its own
  ;; menu entry.
  [state]
  (let [objective-kind (get-in state (conj contracts/draft-objective-path :kind))]
    (cond
      (= :minimum-variance objective-kind) :minimum-volatility
      (= :equal-risk objective-kind) :equal-risk
      (= :inverse-volatility objective-kind) :inverse-volatility
      (= :target-volatility objective-kind) :target-volatility
      (= :target-return objective-kind) :maximum-return
      :else :max-sharpe)))

(defn- objective-menu-model
  [value]
  (get draft-options/objective-menu-options (common/normalize-keyword-like value)))

(defn- black-litterman-view-confidence-level
  [view]
  (bl-model/normalize-confidence-level
   (or (:confidence-level view)
       (bl-model/confidence-level-from-view view))))

(defn- absolute-view?
  [view]
  (= :absolute (common/normalize-keyword-like (:kind view))))

(defn- existing-absolute-view-by-instrument
  [views instrument-id]
  (some (fn [view]
          (when (and (absolute-view? view)
                     (= instrument-id (:instrument-id view)))
            view))
        views))

(defn- objective-menu-inline-order
  [state]
  (let [ui-order (vec (keep common/non-blank-text
                            (get-in state contracts/ui-objective-menu-view-order-path)))
        views (vec (or (get-in state contracts/draft-return-model-views-path) []))
        existing-order (vec (keep (fn [view]
                                    (when (absolute-view? view)
                                      (common/non-blank-text (:instrument-id view))))
                                  views))
        universe-order (vec (keep (comp common/non-blank-text :instrument-id)
                                  (get-in state contracts/draft-universe-path)))]
    (vec (distinct (concat universe-order existing-order ui-order)))))

(defn- implied-return-inputs
  ;; Baseline (prior) expected returns only — never the last run's posterior.
  ;; The implied value a row shows (and the value a confidence click adopts) must
  ;; be exactly the number the solver uses when the user has no view there.
  [state]
  (return-inputs/readiness-inputs-by-instrument
   (setup-readiness/build-readiness state)))

(defn- black-litterman-return-model?
  [state]
  (= :black-litterman
     (get-in state (conj contracts/draft-return-model-path :kind))))

(defn- draft-views
  [state]
  (vec (or (get-in state contracts/draft-return-model-views-path) [])))

(defn- ui-view-return-text
  [state instrument-id]
  (get-in state (conj contracts/ui-objective-menu-view-drafts-path
                      (keyword instrument-id)
                      :return-text)))

(defn- effective-return-text
  ;; What the row currently displays: the typing buffer wins, then the authored
  ;; view, then the implied baseline.
  [state views implied-by-instrument instrument-id]
  (let [view (existing-absolute-view-by-instrument views instrument-id)
        ui-text (ui-view-return-text state instrument-id)]
    (cond
      (some? ui-text) (str ui-text)

      (some? (:return view))
      (bl-model/decimal->percent-text (:return view))

      (contains? implied-by-instrument instrument-id)
      (bl-model/decimal->percent-text (get implied-by-instrument instrument-id))

      :else "")))

(defn- upsert-absolute-view
  ;; Author or update the user's absolute view for an instrument. Returns
  ;; {:views ... :view ...}; the caller persists the view to the wallet library.
  [views instrument-id return-value confidence-level]
  (let [existing (existing-absolute-view-by-instrument views instrument-id)
        level (bl-model/normalize-confidence-level
               (or confidence-level
                   (when existing (black-litterman-view-confidence-level existing))))
        confidence (bl-model/confidence-weight level)
        view (if existing
               (assoc existing
                      :return return-value
                      :confidence-level level
                      :confidence confidence
                      :confidence-variance (bl-model/confidence-variance confidence))
               {:id (bl-model/next-view-id views)
                :kind :absolute
                :instrument-id instrument-id
                :return return-value
                :confidence-level level
                :confidence confidence
                :confidence-variance (bl-model/confidence-variance confidence)
                :horizon :3m
                :weights {instrument-id 1}})]
    {:views (if existing
              (mapv (fn [candidate]
                      (if (= (:id existing) (:id candidate)) view candidate))
                    views)
              (conj views view))
     :view view}))

(defn- remove-absolute-view
  [views instrument-id]
  (vec (remove (fn [view]
                 (and (absolute-view? view)
                      (= instrument-id (:instrument-id view))))
               views)))

(defn- view-library-sync-effect
  [{:keys [upserts removes]}]
  [:effects/sync-portfolio-optimizer-view-library
   {:upserts (vec (or upserts []))
    :removes (vec (or removes []))}])

(defn- hydrated-black-litterman-return-model
  ;; The views-aware return model: the draft's authored views, plus remembered
  ;; library views for universe instruments that have none yet. Zero views is a
  ;; valid state (the posterior equals the baseline).
  [state]
  {:kind :black-litterman
   :views (view-library/hydrate-views
           (draft-views state)
           (get-in state contracts/view-library-path)
           (get-in state contracts/draft-universe-path))})

(defn- preserve-objective-parameters
  ;; Menu models carry preset parameter values (e.g. target-volatility 0.12);
  ;; applying an option must not clobber a user-chosen value. The chosen sigma
  ;; additionally rides along as a dormant key across kind switches so picking
  ;; target-volatility again remembers the level (per the designer spec).
  [state objective]
  (let [current (get-in state contracts/draft-objective-path)
        preserved-keys (if (= (:kind current) (:kind objective))
                         draft-options/numeric-objective-parameter-keys
                         [:target-volatility])]
    (if (map? objective)
      (merge objective (select-keys current preserved-keys))
      objective)))

(defn- with-objective-menu-target-sigma
  ;; The menu's inline sigma editor stages a pending value; applying the
  ;; target-volatility option commits it over the preset/preserved sigma.
  [state objective]
  (let [pending (get-in state contracts/ui-objective-menu-target-sigma-path)]
    (if (and (map? objective)
             (= :target-volatility (:kind objective))
             (number? pending))
      (assoc objective :target-volatility pending)
      objective)))

(defn- objective-menu-model-for-state
  ;; Objective and return model are orthogonal now: picking a non-Sharpe
  ;; objective leaves the return model (and any authored views) alone instead of
  ;; downgrading to historical-mean.
  [state value]
  (when-let [model (objective-menu-model value)]
    (let [model (update model :objective
                        (fn [objective]
                          (->> objective
                               (preserve-objective-parameters state)
                               (with-objective-menu-target-sigma state))))]
      (if (= :black-litterman (:return-model-kind model))
        (-> model
            (dissoc :return-model-kind)
            (assoc :return-model (hydrated-black-litterman-return-model state)))
        model))))

(defn open-portfolio-optimizer-objective-menu
  [state]
  [[:effects/save-many
    [[contracts/ui-objective-menu-open-path true]
     [contracts/ui-objective-menu-selection-path
      (current-objective-menu-option state)]
     [contracts/ui-objective-menu-target-sigma-path nil]]]])

(defn close-portfolio-optimizer-objective-menu
  [_state]
  [[:effects/save-many
    [[contracts/ui-objective-menu-open-path false]
     [contracts/ui-objective-menu-selection-path nil]
     [contracts/ui-objective-menu-target-sigma-path nil]]]])

(defn handle-portfolio-optimizer-objective-menu-keydown
  [state key]
  (if (= "Escape" (some-> key str))
    (close-portfolio-optimizer-objective-menu state)
    []))

(defn select-portfolio-optimizer-objective-menu-option
  [_state option-key]
  (if (objective-menu-model option-key)
    [[:effects/save
      contracts/ui-objective-menu-selection-path
      (common/normalize-keyword-like option-key)]]
    []))

(defn set-portfolio-optimizer-objective-menu-view-return
  "Typing in a row's return input. The text buffer always updates; when the text
  parses the row is authored (or updated) as the user's view immediately and
  synced to the wallet's view library. Blank text clears the view — the row
  resets to implied. Unparseable mid-typing text (\"-\", \"1.\") keeps the
  existing view until the number completes."
  [state instrument-id value]
  (if-let [instrument-id* (common/non-blank-text instrument-id)]
    (let [text (str (or value ""))
          ui-path (conj contracts/ui-objective-menu-view-drafts-path
                        (keyword instrument-id*)
                        :return-text)
          buffer-only [[:effects/save ui-path text]]
          views (draft-views state)
          existing (existing-absolute-view-by-instrument views instrument-id*)
          parsed (bl-model/parse-percent-text text)]
      (cond
        (not (black-litterman-return-model? state))
        buffer-only

        (str/blank? text)
        (if existing
          ;; The library REMOVE must hit the state mirror BEFORE the draft
          ;; write: the view-library hydrate watcher fires on the draft change,
          ;; and a mirror that still holds the entry would gap-fill the
          ;; just-cleared view right back (same ordering as assumption clears).
          (into [(view-library-sync-effect {:removes [instrument-id*]})]
                (common/save-draft-path-values
                 [[contracts/draft-return-model-views-path
                   (remove-absolute-view views instrument-id*)]
                  [ui-path text]]))
          buffer-only)

        (some? parsed)
        (let [{:keys [views view]} (upsert-absolute-view views instrument-id* parsed nil)]
          (conj (common/save-draft-path-values
                 [[contracts/draft-return-model-views-path views]
                  [ui-path text]])
                (view-library-sync-effect
                 {:upserts [(view-library/view->upsert view)]})))

        :else
        buffer-only))
    []))

(def ^:private inline-return-step-decimal
  0.005)

(defn- objective-menu-return-step-direction
  [direction]
  (case (common/normalize-keyword-like direction)
    :up 1
    :arrow-up 1
    :down -1
    :arrow-down -1
    nil))

(defn step-portfolio-optimizer-objective-menu-view-return
  "Stepper/arrow-key nudge (±0.5 pp) from the row's effective value (buffer, then
  authored view, then implied). The result always parses, so the step authors the
  view immediately."
  [state instrument-id direction]
  (if-let [instrument-id* (common/non-blank-text instrument-id)]
    (if-let [step-direction (objective-menu-return-step-direction direction)]
      (let [ui-path (conj contracts/ui-objective-menu-view-drafts-path
                          (keyword instrument-id*)
                          :return-text)
            views (draft-views state)
            current (or (bl-model/parse-percent-text
                         (effective-return-text state
                                                views
                                                (implied-return-inputs state)
                                                instrument-id*))
                        0)
            next-text (bl-model/decimal->percent-text
                       (+ current (* step-direction inline-return-step-decimal)))
            ;; Store the value the DISPLAYED text parses back to, so the authored
            ;; view and the input can never drift by a float ulp.
            next-value (bl-model/parse-percent-text next-text)]
        (if (black-litterman-return-model? state)
          (let [{:keys [views view]} (upsert-absolute-view views instrument-id* next-value nil)]
            (conj (common/save-draft-path-values
                   [[contracts/draft-return-model-views-path views]
                    [ui-path next-text]])
                  (view-library-sync-effect
                   {:upserts [(view-library/view->upsert view)]})))
          [[:effects/save ui-path next-text]]))
      [])
    []))

(defn set-portfolio-optimizer-objective-menu-view-confidence
  "Confidence click. On an authored row it re-weights the view; on an implied row
  it ADOPTS the shown value as the user's view at that confidence (confidence
  only exists on views — adopting is the honest interpretation of \"trust this
  estimate\"). No-op while the implied value is still unknown (history loading)."
  [state instrument-id confidence]
  (if-let [instrument-id* (common/non-blank-text instrument-id)]
    (let [level (bl-model/normalize-confidence-level confidence)
          views (draft-views state)
          existing (existing-absolute-view-by-instrument views instrument-id*)
          return-value (or (:return existing)
                           (bl-model/parse-percent-text
                            (ui-view-return-text state instrument-id*))
                           (get (implied-return-inputs state) instrument-id*))]
      (if (and (black-litterman-return-model? state)
               (coercion/finite-number? return-value))
        (let [{:keys [views view]} (upsert-absolute-view views instrument-id* return-value level)]
          (conj (common/save-draft-path-values
                 [[contracts/draft-return-model-views-path views]])
                (view-library-sync-effect
                 {:upserts [(view-library/view->upsert view)]})))
        []))
    []))

(defn remove-portfolio-optimizer-objective-menu-view
  "Reset a row to implied: drop the authored view, clear the typing buffer, and
  remove the wallet-library entry."
  [state instrument-id]
  (if-let [instrument-id* (common/non-blank-text instrument-id)]
    (let [order (vec (remove #(= instrument-id* %)
                             (objective-menu-inline-order state)))
          drafts (dissoc (or (get-in state contracts/ui-objective-menu-view-drafts-path)
                             {})
                         (keyword instrument-id*))
          ui-path-values [[contracts/ui-objective-menu-view-order-path order]
                          [contracts/ui-objective-menu-view-drafts-path drafts]]
          views (draft-views state)
          existing (when (black-litterman-return-model? state)
                     (existing-absolute-view-by-instrument views instrument-id*))]
      (if existing
        ;; Library remove BEFORE the draft write — see the ordering note in
        ;; set-portfolio-optimizer-objective-menu-view-return's blank branch.
        (into [(view-library-sync-effect {:removes [instrument-id*]})]
              (common/save-draft-path-values
               (into [[contracts/draft-return-model-views-path
                       (remove-absolute-view views instrument-id*)]]
                     ui-path-values)))
        [[:effects/save-many ui-path-values]]))
    []))

(defn add-portfolio-optimizer-objective-menu-view
  [state]
  (let [order (objective-menu-inline-order state)
        present (set order)
        next-id (some (fn [instrument]
                        (let [instrument-id (:instrument-id instrument)]
                          (when (and (common/non-blank-text instrument-id)
                                     (not (contains? present instrument-id)))
                            instrument-id)))
                      (get-in state contracts/draft-universe-path))]
    (if next-id
      [[:effects/save contracts/ui-objective-menu-view-order-path
        (conj (vec order) next-id)]]
      [])))

(defn- state-after-save-effect
  [state effect]
  (case (first effect)
    :effects/save
    (assoc-in state (second effect) (nth effect 2))

    :effects/save-many
    (reduce (fn [state* [path value]]
              (assoc-in state* path value))
            state
            (second effect))

    state))

(defn- projected-state-after-save-effects
  [state effects]
  (reduce state-after-save-effect state effects))

(defn apply-portfolio-optimizer-objective-menu-selection-and-run
  [state]
  (let [selection (or (get-in state contracts/ui-objective-menu-selection-path)
                      (current-objective-menu-option state))]
    (if-let [{:keys [objective return-model]} (objective-menu-model-for-state state selection)]
      (let [path-values (cond-> [[contracts/draft-objective-path objective]]
                          return-model
                          (conj [contracts/draft-return-model-path return-model])

                          :always
                          (conj [contracts/ui-objective-menu-open-path false]
                                [contracts/ui-objective-menu-selection-path nil]
                                [contracts/ui-objective-menu-target-sigma-path nil]
                                [contracts/ui-target-sigma-draft-path nil]))
            effects (common/save-draft-path-values path-values)
            state* (projected-state-after-save-effects state effects)]
        (into effects
              (run-actions/run-portfolio-optimizer-from-draft state*)))
      [])))

(defn switch-portfolio-optimizer-objective-and-run
  "Argument-taking objective switch for cross-links (the floored-state nudge in
  the Risk Balance card): takes the objective MENU-OPTION key, saves the
  option's objective (and return model when the option carries one), and reruns
  from the updated draft. Unlike the apply action above, it never reads the
  pending menu selection from UI state, so it works with no menu open."
  [state option-key]
  (if-let [{:keys [objective return-model]} (objective-menu-model-for-state state option-key)]
    (let [path-values (cond-> [[contracts/draft-objective-path objective]]
                        return-model
                        (conj [contracts/draft-return-model-path return-model]))
          effects (common/save-draft-path-values path-values)
          state* (projected-state-after-save-effects state effects)]
      (into effects
            (run-actions/run-portfolio-optimizer-from-draft state*)))
    []))

(defn set-portfolio-optimizer-objective-kind
  [_state kind]
  (set-draft-model contracts/draft-objective-path
                   draft-options/objective-models
                   kind))

(defn set-portfolio-optimizer-return-model-kind
  [state kind]
  (if (= :black-litterman (common/normalize-keyword-like kind))
    ;; Switching the estimator to the views-aware model restores the user's
    ;; remembered views for this universe instead of starting from zero.
    (common/save-draft-path-values
     [[contracts/draft-return-model-path
       (hydrated-black-litterman-return-model state)]])
    (set-draft-model contracts/draft-return-model-path
                     draft-options/return-models
                     kind)))

(defn set-portfolio-optimizer-return-views-filter
  [_state filter-key]
  [[:effects/save
    contracts/ui-return-views-filter-path
    (return-views/normalize-filter filter-key)]])

(defn set-portfolio-optimizer-risk-model-kind
  [_state kind]
  (set-draft-model contracts/draft-risk-model-path
                   draft-options/risk-models
                   kind))

(defn apply-portfolio-optimizer-setup-preset
  [state preset]
  (if-let [{:keys [objective return-model]} (get draft-options/setup-presets
                                                 (common/normalize-keyword-like preset))]
    (common/save-draft-path-values
     [[contracts/draft-objective-path objective]
      [contracts/draft-return-model-path
       ;; The Maximum Sharpe preset consumes return views; restore the wallet's
       ;; remembered views for this universe rather than the preset's empty
       ;; vector, so switching presets never loses authored views.
       (if (= :black-litterman (:kind return-model))
         (hydrated-black-litterman-return-model state)
         return-model)]])
    []))

(defn set-portfolio-optimizer-constraint
  [_state constraint-key value]
  (let [constraint-key* (common/normalize-keyword-like constraint-key)
        clear? (and (nil? value)
                    (contains? draft-options/clearable-numeric-constraint-keys
                               constraint-key*))
        value* (cond
                 clear?
                 nil

                 (contains? draft-options/numeric-constraint-keys constraint-key*)
                 (common/parse-number-value value)

                 (contains? draft-options/boolean-constraint-keys constraint-key*)
                 (common/parse-boolean-value value)

                 :else nil)]
    (if (or clear? (some? value*))
      (common/save-draft-path-values
       [[(conj contracts/draft-constraints-path constraint-key*) value*]])
      [])))

(defn set-portfolio-optimizer-objective-parameter
  [_state parameter-key value]
  (let [parameter-key* (common/normalize-keyword-like parameter-key)
        value* (when (contains? draft-options/numeric-objective-parameter-keys
                                parameter-key*)
                 (common/parse-number-value value))]
    (if (some? value*)
      (common/save-draft-path-values
       [[(conj contracts/draft-objective-path parameter-key*) value*]])
      [])))

(defn set-portfolio-optimizer-execution-assumption
  [_state assumption-key value]
  (let [assumption-key* (common/normalize-keyword-like assumption-key)
        manual-capital-clear? (and (= :manual-capital-usdc assumption-key*)
                                   (nil? (common/non-blank-text value)))
        value* (cond
                 manual-capital-clear?
                 nil

                 (contains? draft-options/numeric-execution-assumption-keys
                            assumption-key*)
                 (common/parse-number-value value)

                 (contains? draft-options/keyword-execution-assumption-keys
                            assumption-key*)
                 (common/normalize-keyword-like value)

                 :else nil)]
    (if (or (some? value*)
            manual-capital-clear?)
      (common/save-draft-path-values
       [[(conj contracts/draft-execution-assumptions-path assumption-key*) value*]])
      [])))

(defn set-portfolio-optimizer-instrument-filter
  [state filter-key instrument-id enabled?]
  (let [filter-key* (common/normalize-keyword-like filter-key)
        instrument-id* (common/non-blank-text instrument-id)
        enabled?* (common/parse-boolean-value enabled?)]
    (if (and (contains? draft-options/instrument-filter-keys filter-key*)
             instrument-id*
             (some? enabled?*))
      (common/save-draft-path-values
       [[(conj contracts/draft-constraints-path filter-key*)
         (common/set-membership
          (common/constraint-list state filter-key*)
          instrument-id*
          enabled?*)]])
      [])))

(defn- instrument-cap-map
  ;; The save-many effect contract only accepts keyword paths, so instrument
  ;; keyed maps must be saved whole rather than assoc'ed via a string segment.
  [state map-key instrument-id max-weight]
  (-> (or (get-in state (conj contracts/draft-constraints-path map-key)) {})
      (assoc-in [instrument-id :max-weight] max-weight)))

(defn set-portfolio-optimizer-asset-override
  [state override-key instrument-id value]
  (let [override-key* (common/normalize-keyword-like override-key)
        instrument-id* (common/non-blank-text instrument-id)
        numeric-value (when (contains? draft-options/numeric-asset-override-keys
                                       override-key*)
                        (common/parse-number-value value))
        boolean-value (when (contains? draft-options/boolean-asset-override-keys
                                       override-key*)
                        (common/parse-boolean-value value))]
    (cond
      (and instrument-id*
           (= :max-weight override-key*)
           (some? numeric-value))
      (common/save-draft-path-values
       [[(conj contracts/draft-constraints-path :asset-overrides)
         (instrument-cap-map state :asset-overrides instrument-id* numeric-value)]])

      (and instrument-id*
           (= :perp-max-weight override-key*)
           (= :perp (common/instrument-market-type state instrument-id*))
           (some? numeric-value))
      (common/save-draft-path-values
       [[(conj contracts/draft-constraints-path :perp-leverage)
         (instrument-cap-map state :perp-leverage instrument-id* numeric-value)]])

      (and instrument-id*
           (= :held-lock? override-key*)
           (some? boolean-value))
      (common/save-draft-path-values
       [[(conj contracts/draft-constraints-path :held-locks)
         (common/set-membership
          (common/constraint-list state :held-locks)
          instrument-id*
          boolean-value)]])

      :else [])))

;; --- History assumptions (per-asset, instrument-keyed) ----------------------
;;
;; The :history-assumptions map is keyed by instrument-id (a string), so every
;; mutation saves the WHOLE map under the single keyword path -- :effects/save-many
;; only accepts keyword path segments (same constraint as instrument-cap-map).
;; A behavior (mode) must be chosen first, which seeds the entry; the field
;; setters operate on an existing entry so partial behavior-less entries never
;; reach the draft.

(defn- history-assumptions-map
  [state]
  (or (get-in state contracts/draft-history-assumptions-path) {}))

(defn- assumption-library-sync-effect
  "Per-wallet assumption-library sync for ONE changed entry: upsert when the
  entry is present (carrying the reference instruments it references, so a
  later hydration can restore out-of-universe proxies), remove when absent
  (explicit Clear — the only gesture that forgets an asset)."
  [refs assumptions instrument-id]
  (if-let [entry (get assumptions instrument-id)]
    [:effects/sync-portfolio-optimizer-assumption-library
     {:upserts [{:instrument-id instrument-id
                 :assumption entry
                 :reference-instruments (assumption-library/entry-reference-instruments
                                         refs entry)}]}]
    [:effects/sync-portfolio-optimizer-assumption-library
     {:removes [instrument-id]}]))

;; --- Reference-only proxy instruments ---------------------------------------
;;
;; A proxy can be ANY catalog asset, not just one already in the universe. A
;; proxy that is NOT a universe member is a "reference-only" instrument: its
;; history is loaded (so the engine can synthesize the thin asset's covariance
;; from it) but it is never allocated. Its instrument metadata (coin,
;; market-type) is stored in the draft's :proxy-reference-instruments so history
;; loading and alignment can find it; the universe list stays untouched.

(defn- universe-instrument-ids
  [state]
  (into #{} (keep :instrument-id) (get-in state contracts/draft-universe-path)))

(defn- referenced-proxy-ids
  "Every proxy id referenced by SOME proxy-behavior assumption."
  [assumptions]
  (into #{}
        (mapcat (fn [[_ entry]]
                  (when (history-assumptions/proxy? entry)
                    (history-assumptions/proxy-instrument-ids entry))))
        assumptions))

(defn- resolve-catalog-instrument
  "Resolve a proxy id (a candidate market-key) to a universe-instrument map the
  same way the universe add does: the shared catalog resolver (markets + vaults)
  plus backend history-identity decoration from discovery. Without the discovery
  metadata, alignment cannot map the stored instrument to its backend series and
  rejects it as identity-ambiguous. Nil when the catalog doesn't know the id."
  [state proxy-id]
  (when-let [market (universe-candidates/resolve-market state proxy-id)]
    (common/market->universe-instrument
     (history-api-v2/with-discovery-metadata
      market
      (get-in state contracts/history-discovery-path)))))

(defn- reconcile-reference-instruments
  "Reference-only proxy instruments = every referenced proxy id that is NOT a
  universe member, resolved from the known pool (existing reference-instruments
  plus any freshly-resolved instrument). Deterministic (sorted by id), deduped;
  drops instruments no assumption references anymore."
  [state assumptions extra-instruments]
  (let [universe-ids (universe-instrument-ids state)
        known (reduce (fn [m i] (assoc m (:instrument-id i) i))
                      {}
                      (concat (get-in state contracts/draft-proxy-reference-instruments-path)
                              extra-instruments))]
    (->> (referenced-proxy-ids assumptions)
         (remove universe-ids)
         sort
         (keep known)
         vec)))

(defn- save-assumptions+refs
  "Persist updated assumptions and the reconciled reference-only proxy
  instruments in one draft save, and mirror the changed entry into the wallet's
  assumption library. When a newly-referenced out-of-universe proxy is
  supplied, also enqueue a history prefetch so its covariance history loads."
  ([state assumptions* instrument-id]
   (save-assumptions+refs state assumptions* instrument-id nil))
  ([state assumptions* instrument-id new-instrument]
   (let [refs (reconcile-reference-instruments state
                                               assumptions*
                                               (when new-instrument [new-instrument]))
         existing-refs (vec (get-in state contracts/draft-proxy-reference-instruments-path))
         prefetch-plan (when new-instrument
                         (history-prefetch/enqueue-missing-instruments state
                                                                       [new-instrument]))
         path-values (cond-> [[contracts/draft-history-assumptions-path assumptions*]]
                       ;; Only rewrite the reference-instruments when they change,
                       ;; so seeding a fresh assumption stays a pure assumptions save.
                       (not= refs existing-refs)
                       (conj [contracts/draft-proxy-reference-instruments-path refs])

                       (:changed? prefetch-plan)
                       (conj [contracts/history-prefetch-path (:state prefetch-plan)]))
         sync-effect (assumption-library-sync-effect refs assumptions* instrument-id)
         draft-save (common/save-draft-path-values path-values)
         ;; A library REMOVE (Clear) must hit the state mirror BEFORE the draft
         ;; write: the hydrate watcher fires on the draft change, and a mirror
         ;; that still holds the entry would gap-fill the just-cleared card
         ;; right back. Upserts can never create a gap, so they follow the save.
         effects (if (contains? assumptions* instrument-id)
                   (conj draft-save sync-effect)
                   (into [sync-effect] draft-save))]
     (cond-> effects
       (:start? prefetch-plan)
       (conj history-prefetch/selection-prefetch-effect)))))

(declare unacknowledged)

(defn set-portfolio-optimizer-history-assumption-active
  "Selects the asset the History-assumptions queue is asking about; the rail
  pills, Prev and Skip dispatch it. A stale id (the asset left the universe, or
  its history arrived and retired the card) needs no cleanup — the queue
  view-model falls back to the first unsettled asset."
  [_state instrument-id]
  [[:effects/save
    contracts/ui-history-assumption-active-path
    (common/non-blank-text instrument-id)]])

(defn- update-history-assumption-field
  [state instrument-id field value*]
  (let [instrument-id* (common/non-blank-text instrument-id)
        assumptions (history-assumptions-map state)]
    (if (and instrument-id*
             (some? value*)
             (contains? assumptions instrument-id*))
      ;; Every assumption save reconciles the reference-only proxies, so a
      ;; stale stored reference (live 2026-07-09: an emptied basket left
      ;; BTC/ETH behind) self-heals on the next edit of any card.
      (save-assumptions+refs
       state
       (update assumptions instrument-id*
               #(unacknowledged (assoc % field value*)))
       instrument-id*)
      [])))

(defn set-portfolio-optimizer-history-assumption-mode
  [state instrument-id behavior]
  (let [instrument-id* (common/non-blank-text instrument-id)
        behavior* (common/normalize-keyword-like behavior)]
    (if (and instrument-id*
             (contains? history-assumptions/behaviors behavior*))
      (let [assumptions (history-assumptions-map state)
            existing (get assumptions instrument-id*)]
        (if (= behavior* (:behavior existing))
          ;; already in this mode; preserve the user's input untouched.
          []
          ;; switching modes preserves expected return/volatility and reseeds only
          ;; the mode-specific fields.
          (let [entry (cond-> (history-assumptions/default-assumption behavior*)
                        (some? (:expected-return existing))
                        (assoc :expected-return (:expected-return existing))

                        (some? (:volatility existing))
                        (assoc :volatility (:volatility existing)))]
            ;; Switching away from proxy drops the entry's proxy ids, so
            ;; reconcile the reference-only instruments (GC any now-unreferenced).
            (save-assumptions+refs state
                                   (assoc assumptions instrument-id* entry)
                                   instrument-id*))))
      [])))

(defn set-portfolio-optimizer-history-assumption-expected-return
  [state instrument-id value]
  (update-history-assumption-field state instrument-id :expected-return
                                   (coercion/parse-percent-text value)))

(defn set-portfolio-optimizer-history-assumption-expected-volatility
  [state instrument-id value]
  (update-history-assumption-field state instrument-id :volatility
                                   (coercion/parse-percent-text value)))

(defn set-portfolio-optimizer-history-assumption-max-weight-cap
  [state instrument-id value]
  (update-history-assumption-field state instrument-id :max-weight
                                   (coercion/parse-percent-text value)))

(defn clear-portfolio-optimizer-history-assumption
  [state instrument-id]
  (let [instrument-id* (common/non-blank-text instrument-id)
        assumptions (history-assumptions-map state)]
    (if (and instrument-id*
             (contains? assumptions instrument-id*))
      ;; Removing the entry drops its proxy ids, so reconcile the reference-only
      ;; instruments too (GC any proxy no assumption references anymore). The
      ;; library entry is removed as well — Clear is the explicit "forget this
      ;; asset" gesture.
      (save-assumptions+refs state
                             (dissoc assumptions instrument-id*)
                             instrument-id*)
      [])))

(defn- unacknowledged
  ;; Editing any assumption detail withdraws the Apply acknowledgment: the
  ;; acknowledgment refers to the values it was clicked on. Entries that never
  ;; carried metadata stay key-for-key identical.
  [entry]
  (if (get-in entry [:metadata :acknowledged?])
    (let [metadata (dissoc (:metadata entry) :acknowledged?)]
      (if (seq metadata)
        (assoc entry :metadata metadata)
        (dissoc entry :metadata)))
    entry))

(defn set-portfolio-optimizer-history-assumption-proxy-asset
  "Adds (enabled? true) or removes (enabled? false) one proxy asset on a
  proxy-mode entry. The proxy may be ANY catalog asset, not just a universe
  member: enabling one that is not in the universe resolves its instrument from
  the market catalog, stores it as a reference-only proxy (history loaded, never
  allocated), and prefetches its history. An asset can never proxy itself, and an
  unknown id the catalog can't resolve is a no-op."
  [state instrument-id proxy-instrument-id enabled?]
  (let [instrument-id* (common/non-blank-text instrument-id)
        proxy-id* (common/non-blank-text proxy-instrument-id)
        enabled?* (common/parse-boolean-value enabled?)
        assumptions (history-assumptions-map state)
        entry (get assumptions instrument-id*)
        in-universe? (contains? (universe-instrument-ids state) proxy-id*)
        ;; Only resolve/prefetch when ADDING an out-of-universe proxy.
        resolved (when (and enabled?* proxy-id* (not in-universe?))
                   (resolve-catalog-instrument state proxy-id*))]
    (if (and instrument-id*
             proxy-id*
             (not= instrument-id* proxy-id*)
             (some? enabled?*)
             (history-assumptions/proxy? entry)
             ;; Adding an out-of-universe proxy the catalog can't resolve is a no-op.
             (or (not enabled?*) in-universe? (some? resolved)))
      (let [assumptions* (assoc assumptions instrument-id*
                                (-> entry
                                    (update-in [:proxy :instrument-ids]
                                               #(common/set-membership (vec (or % []))
                                                                       proxy-id*
                                                                       enabled?*))
                                    unacknowledged))]
        (save-assumptions+refs state assumptions* instrument-id* resolved))
      [])))

(defn set-portfolio-optimizer-history-assumption-proxy-search
  "Stores the per-card proxy catalog search query (UI-only; not part of the
  draft, so it never marks the scenario dirty)."
  [state instrument-id query]
  (if-let [instrument-id* (common/non-blank-text instrument-id)]
    (let [queries (or (get-in state contracts/ui-proxy-search-queries-path) {})]
      [[:effects/save
        contracts/ui-proxy-search-queries-path
        (assoc queries instrument-id* (str (or query "")))]])
    []))

(defn set-portfolio-optimizer-history-assumption-relationship-strength
  [state instrument-id value]
  (let [instrument-id* (common/non-blank-text instrument-id)
        strength (common/normalize-keyword-like value)
        assumptions (history-assumptions-map state)
        entry (get assumptions instrument-id*)]
    (if (and instrument-id*
             (contains? history-assumptions/relationship-strengths strength)
             (history-assumptions/proxy? entry))
      (save-assumptions+refs
       state
       (assoc assumptions instrument-id*
              (-> entry
                  (assoc-in [:proxy :relationship-strength] strength)
                  unacknowledged))
       instrument-id*)
      [])))

(defn apply-portfolio-optimizer-history-assumption
  "Acknowledges the configured assumption — the queue's Accept. Purely
  presentational: run readiness never depends on acknowledgment, it only
  decides whether the queue still owes this asset a turn."
  [state instrument-id]
  (let [instrument-id* (common/non-blank-text instrument-id)
        assumptions (history-assumptions-map state)]
    (if (and instrument-id*
             (contains? assumptions instrument-id*))
      (save-assumptions+refs
       state
       (update assumptions instrument-id*
               assoc :metadata {:source :user :acknowledged? true})
       instrument-id*)
      [])))

(defn hydrate-portfolio-optimizer-history-assumption-library
  "Gap-fill remembered assumptions from the wallet's assumption library into
  the draft: every universe instrument WITHOUT a draft entry takes its library
  entry; existing draft entries are never touched, so this is idempotent and
  can never clobber live edits. Restored reference-only proxies (out-of-
  universe basket members) re-enter :proxy-reference-instruments and get a
  history prefetch. Dispatched after the library mirror loads, after a draft
  restore/preseed, and on universe adds. Deliberately does NOT mark the draft
  dirty — applying remembered input is machine work, not a user edit."
  [state]
  (let [result (assumption-library/hydrate-assumptions
                {:assumptions (get-in state contracts/draft-history-assumptions-path)
                 :universe (get-in state contracts/draft-universe-path)
                 :entries (get-in state contracts/assumption-library-path)
                 :reference-instruments
                 (get-in state contracts/draft-proxy-reference-instruments-path)})]
    (if (nil? result)
      []
      (let [{:keys [assumptions reference-instruments new-reference-instruments]} result
            existing-refs (vec (get-in state
                                       contracts/draft-proxy-reference-instruments-path))
            prefetch-plan (when (seq new-reference-instruments)
                            (history-prefetch/enqueue-missing-instruments
                             state new-reference-instruments))
            path-values (cond-> [[contracts/draft-history-assumptions-path assumptions]]
                          (not= reference-instruments existing-refs)
                          (conj [contracts/draft-proxy-reference-instruments-path
                                 reference-instruments])

                          (:changed? prefetch-plan)
                          (conj [contracts/history-prefetch-path (:state prefetch-plan)]))]
        (cond-> [[:effects/save-many path-values]]
          (:start? prefetch-plan)
          (conj history-prefetch/selection-prefetch-effect))))))

(defn reset-portfolio-optimizer-history-assumption
  "Re-seeds the entry with its behavior's defaults, discarding edits."
  [state instrument-id]
  (let [instrument-id* (common/non-blank-text instrument-id)
        assumptions (history-assumptions-map state)
        behavior (get-in assumptions [instrument-id* :behavior])]
    (if-let [entry (and instrument-id*
                        (history-assumptions/default-assumption behavior))]
      ;; Re-seeding clears the entry's proxy ids, so reconcile reference-only
      ;; instruments (GC any now-unreferenced proxy).
      (save-assumptions+refs state (assoc assumptions instrument-id* entry) instrument-id*)
      [])))
