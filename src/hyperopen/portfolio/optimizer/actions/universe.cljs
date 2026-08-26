(ns hyperopen.portfolio.optimizer.actions.universe
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.actions.run :as run-actions]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as history-api-v2]
            [hyperopen.portfolio.optimizer.application.history-prefetch :as history-prefetch]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.view-model.universe-search :as universe-search]
            [hyperopen.portfolio.optimizer.black-litterman-actions.views :as black-litterman-views]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]
            [hyperopen.portfolio.optimizer.ids :as ids]
            [hyperopen.portfolio.optimizer.universe-keyboard :as universe-keyboard]
            [hyperopen.portfolio.routes :as portfolio-routes]))

;; Search state resets as a UNIT. The query, the keyboard cursor and both facets
;; are reset together at every site that clears the search, because the draft
;; add-asset popover shares these paths and renders no filter chips — a facet
;; left set there would silently hide rows with nothing on screen to explain it.
(defn- cleared-search-path-values
  []
  [[contracts/ui-universe-search-query-path ""]
   [contracts/ui-universe-search-active-index-path 0]
   [contracts/ui-universe-search-type-filter-path :all]
   [contracts/ui-universe-search-quote-filter-path :all]])

(defn set-portfolio-optimizer-universe-search-query
  [_state query]
  (let [query* (or (some-> query str) "")]
    [[:effects/save-many
      (if (seq query*)
        ;; Typing keeps the facets: narrowing a query under an active chip is the
        ;; point of the chip. Only an emptied field is a full reset.
        [[contracts/ui-universe-search-query-path query*]
         [contracts/ui-universe-search-active-index-path 0]]
        (cleared-search-path-values))]]))

(defn set-portfolio-optimizer-universe-search-type-filter
  [_state value]
  [[:effects/save-many
    [[contracts/ui-universe-search-type-filter-path
      (universe-search/normalize-type-filter value)]
     ;; Any facet change re-orders the list, so the cursor must return to the top
     ;; or it points at a row that is no longer there.
     [contracts/ui-universe-search-active-index-path 0]]]])

(defn set-portfolio-optimizer-universe-search-quote-filter
  [_state value]
  [[:effects/save-many
    [[contracts/ui-universe-search-quote-filter-path
      (universe-search/normalize-quote-filter value)]
     [contracts/ui-universe-search-active-index-path 0]]]])

(defn- draft-add-asset-closed-path-value
  []
  [contracts/ui-draft-add-asset-open-path false])

(defn- reset-search-path-values
  []
  (cleared-search-path-values))

(defn set-portfolio-optimizer-draft-add-asset-open
  [_state open?]
  [[:effects/save-many
    (into [[contracts/ui-draft-add-asset-open-path (boolean open?)]]
          (reset-search-path-values))]])

(declare add-portfolio-optimizer-universe-instrument)

(defn handle-portfolio-optimizer-universe-search-keydown
  [state key market-keys]
  (universe-keyboard/handle-keydown add-portfolio-optimizer-universe-instrument state key market-keys))

(declare add-portfolio-optimizer-universe-instrument-and-run)

(defn handle-portfolio-optimizer-draft-add-asset-keydown
  [state key market-keys]
  (universe-keyboard/handle-keydown
   add-portfolio-optimizer-universe-instrument-and-run
   state
   key
   market-keys))

(defn- with-prefetch-effect
  [effects prefetch-plan]
  (cond-> effects
    (:start? prefetch-plan)
    (conj history-prefetch/selection-prefetch-effect)))

(defn- with-prefetch-path-value
  [path-values prefetch-plan]
  (cond-> path-values
    (:changed? prefetch-plan)
    (conj [contracts/history-prefetch-path
           (:state prefetch-plan)])))

(defn- with-history-discovery
  [state market]
  (if (map? market)
    (history-api-v2/with-discovery-metadata
     market
     (get-in state contracts/history-discovery-path))
    market))

(def ^:private unusable-history-statuses
  #{:missing :rejected :unavailable :unsupported :disabled})

(def ^:private unusable-quality-statuses
  #{:failed :rejected :missing})

(defn- exposure-abs-notional-usdc
  [exposure]
  (or (some-> (:abs-notional-usdc exposure)
              common/parse-number-value
              js/Math.abs)
      (some-> (:signed-notional-usdc exposure)
              common/parse-number-value
              js/Math.abs)
      0))

(defn- optimizer-history-status
  [instrument key]
  (common/normalize-keyword-like (get instrument key)))

(defn- known-unusable-history?
  [instrument]
  (or (contains? unusable-history-statuses
                 (optimizer-history-status instrument
                                           :optimizer-history/history-status))
      (contains? unusable-quality-statuses
                 (optimizer-history-status instrument
                                           :optimizer-history/quality-status))))

(defn- known-usable-history?
  [instrument]
  (and (:optimizer-history/instrument-id instrument)
       (not (known-unusable-history? instrument))))

(defn- spot-instrument?
  [instrument]
  (let [instrument-id (:instrument-id instrument)
        market-type (ids/normalize-market-type
                     (or (:market-type instrument)
                         (:instrument-type instrument)
                         (:optimizer-history/instrument-kind instrument)))]
    (or (= :spot market-type)
        (and (string? instrument-id)
             (or (.startsWith instrument-id "spot:")
                 (.startsWith instrument-id "hl:spot:"))))))

(defn- include-spot-assets?
  [state]
  (true? (get-in state (conj contracts/draft-constraints-path :include-spot?))))

(defn- draft-universe-exposures-from-current
  [state snapshot]
  (let [include-spot? (include-spot-assets? state)]
    (cond->> (:exposures snapshot)
      (not include-spot?)
      (remove spot-instrument?)
      :always
      vec)))

(defn- snapshot-with-exposures
  [snapshot exposures]
  (let [exposures* (vec exposures)
        gross-usdc (reduce + (map :abs-notional-usdc exposures*))
        net-usdc (reduce + (map :signed-notional-usdc exposures*))]
    (-> snapshot
        (assoc :exposures exposures*
               :by-instrument (into {}
                                    (map (juxt :instrument-id identity))
                                    exposures*))
        (assoc-in [:capital :gross-exposure-usdc] gross-usdc)
        (assoc-in [:capital :net-exposure-usdc] net-usdc))))

(defn- from-current-candidate
  [state idx exposure]
  (when-let [instrument (common/exposure->universe-instrument exposure)]
    {:idx idx
     :abs-notional-usdc (exposure-abs-notional-usdc exposure)
     :instrument (with-history-discovery state instrument)}))

(defn- from-current-sort-key
  [{:keys [instrument abs-notional-usdc idx]}]
  [(if (known-usable-history? instrument) 0 1)
   (- abs-notional-usdc)
   idx])

(defn- holdings-omission
  [reason exposure-or-instrument]
  {:instrument-id (:instrument-id exposure-or-instrument)
   :label (or (common/non-blank-text (:symbol exposure-or-instrument))
              (common/non-blank-text (:coin exposure-or-instrument))
              (:instrument-id exposure-or-instrument))
   :reason reason})

(defn- universe-from-current-partition
  "Split the holdings exposures into the usable universe and the assets omitted for
  unusable optimizer history, so a holdings load can show what it left out instead
  of silently dropping it."
  [state exposures]
  (let [candidates (->> exposures
                        (map-indexed #(from-current-candidate state %1 %2))
                        (keep identity))
        unusable (filter #(known-unusable-history? (:instrument %)) candidates)
        universe (->> candidates
                      (remove #(known-unusable-history? (:instrument %)))
                      (sort-by from-current-sort-key)
                      (map :instrument)
                      common/dedupe-instruments
                      vec)]
    {:universe universe
     :omitted (mapv #(holdings-omission :unusable-history (:instrument %))
                    unusable)}))

(defn add-portfolio-optimizer-universe-instrument
  [state market-key]
  (let [market-key* (common/non-blank-text market-key)
        universe (common/draft-universe state)
        market (universe-candidates/resolve-market state market-key*)
        instrument (common/market->universe-instrument
                    (with-history-discovery state market))
        instrument-id (:instrument-id instrument)]
    (if (and instrument
             (not (common/instrument-present? universe instrument-id)))
      (let [prefetch-plan (history-prefetch/enqueue-missing-instruments
                           state
                           [instrument])
            path-values (with-prefetch-path-value
                          (into [[contracts/draft-universe-path (conj universe instrument)]
                                 ;; Hand-adding an asset makes the set a custom universe,
                                 ;; not a mirror of holdings; the source line follows.
                                 [contracts/draft-universe-source-path {:kind :custom}]]
                                (cleared-search-path-values))
                          prefetch-plan)]
        (with-prefetch-effect
          (common/save-draft-path-values path-values)
          prefetch-plan))
      [])))

(defn add-portfolio-optimizer-universe-matches
  "Add the currently visible search matches to the universe in one step.

  Emits a single prefetch effect for the whole batch rather than one per
  instrument, so it satisfies the same effect-order policy as the single add
  (:allow-duplicate-heavy-effects? false) instead of needing a relaxed one."
  [state market-keys]
  (let [universe (common/draft-universe state)
        instruments (->> (or market-keys [])
                         (keep common/non-blank-text)
                         (take universe-search/add-all-limit)
                         (keep #(some->> (universe-candidates/resolve-market state %)
                                         (with-history-discovery state)
                                         common/market->universe-instrument))
                         (remove #(common/instrument-present? universe
                                                              (:instrument-id %)))
                         common/dedupe-instruments
                         vec)]
    (if (seq instruments)
      (let [universe* (into universe instruments)
            prefetch-plan (history-prefetch/enqueue-missing-instruments state
                                                                        instruments)
            path-values (with-prefetch-path-value
                          (into [[contracts/draft-universe-path universe*]
                                 [contracts/draft-universe-source-path {:kind :custom}]]
                                (cleared-search-path-values))
                          prefetch-plan)]
        (with-prefetch-effect
          (common/save-draft-path-values path-values)
          prefetch-plan))
      [])))

(defn- draft-add-run-save-effect
  [effect]
  (if (= :effects/save-many (first effect))
    (update effect 1
            (fn [path-values]
              (let [path-values* (vec (remove #(or (= contracts/ui-draft-add-asset-open-path
                                                     (first %))
                                                  (= contracts/history-prefetch-path
                                                     (first %)))
                                             path-values))
                    dirty-path-value (peek path-values*)]
                (if (= contracts/draft-dirty-path (first dirty-path-value))
                  (conj (pop path-values*)
                        (draft-add-asset-closed-path-value)
                        dirty-path-value)
                  (conj path-values*
                        (draft-add-asset-closed-path-value))))))
    effect))

(defn- selection-prefetch-effect?
  [effect]
  (and (= :effects/load-portfolio-optimizer-history (first effect))
       (= :selection-prefetch (:source (second effect)))))

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

(defn- instrument-shortable?
  [instrument]
  (cond
    (contains? instrument :shortable?)
    (true? (:shortable? instrument))

    (= :perp (ids/normalize-market-type
              (or (:market-type instrument)
                  (:instrument-type instrument))))
    true

    :else false))

(defn- position-side-for-instrument
  [instrument side]
  (common/selectable-position-side (instrument-shortable? instrument)
                                   side))

(defn- side-updated-universe
  [universe instrument-id side]
  (reduce (fn [{:keys [changed?] :as acc} instrument]
            (if (common/instrument-matches-id? instrument instrument-id)
              (let [side* (position-side-for-instrument instrument side)
                    current-side (common/normalize-position-side
                                  (:position-side instrument))]
                (-> acc
                    (update :universe conj (assoc instrument
                                                  :position-side side*))
                    (assoc :changed? (or changed?
                                         (not= current-side side*)))))
              (update acc :universe conj instrument)))
          {:changed? false
           :universe []}
          universe))

(defn set-portfolio-optimizer-universe-instrument-side
  [state instrument-id side]
  (let [instrument-id* (common/non-blank-text instrument-id)
        universe (common/draft-universe state)]
    (if instrument-id*
      (let [{:keys [changed? universe]} (side-updated-universe universe
                                                               instrument-id*
                                                               side)]
        (if changed?
          (common/save-draft-path-values
           [[contracts/draft-universe-path universe]])
          []))
      [])))

(defn set-portfolio-optimizer-universe-instrument-side-and-run
  [state instrument-id side]
  (let [effects (set-portfolio-optimizer-universe-instrument-side state
                                                                  instrument-id
                                                                  side)]
    (if (seq effects)
      (into effects
            (run-actions/run-portfolio-optimizer-from-draft
             (projected-state-after-save-effects state effects)))
      [])))

(defn add-portfolio-optimizer-universe-instrument-and-run
  [state market-key]
  (let [effects (add-portfolio-optimizer-universe-instrument state market-key)]
    (if (seq effects)
      (let [effects* (vec (remove selection-prefetch-effect?
                                  (update effects 0 draft-add-run-save-effect)))]
        (into effects*
              (run-actions/run-portfolio-optimizer-from-draft
               (projected-state-after-save-effects state effects*))))
      [])))

(defn toggle-portfolio-optimizer-universe-instrument-exclusion-and-run
  [state instrument-id]
  (let [instrument-id* (common/non-blank-text instrument-id)
        universe (common/draft-universe state)
        instrument (common/find-instrument universe instrument-id*)]
    (if (and instrument-id* instrument)
      (let [blocklist (common/constraint-list state :blocklist)
            instrument-ids (set (ids/instrument-id-candidates instrument))
            excluded? (boolean (some instrument-ids blocklist))
            blocklist* (vec (remove instrument-ids blocklist))
            effects (common/save-draft-path-values
                     [[(conj contracts/draft-constraints-path :blocklist)
                       (if excluded?
                         blocklist*
                         (common/set-membership blocklist*
                                                instrument-id*
                                                true))]])
            state* (projected-state-after-save-effects state effects)]
        (into effects
              (run-actions/run-portfolio-optimizer-from-draft state*)))
      [])))

(defn- black-litterman-universe-path-values
  [state universe*]
  (let [ids (set (keep :instrument-id universe*))
        return-model (get-in state contracts/draft-return-model-path)
        views (vec (:views return-model))
        views* (vec (filter (fn [view]
                              (every? ids
                                      (black-litterman-views/view-instrument-ids view)))
                            views))
        draft-path (fn [kind field]
                     (conj contracts/ui-black-litterman-editor-path
                           :drafts
                           kind
                           field))
        clear-if-missing (fn [kind field]
                           (let [value (get-in state (draft-path kind field))]
                             (when (and value (not (contains? ids value)))
                               [(draft-path kind field) nil])))]
    (cond-> []
      (= :black-litterman (:kind return-model))
      (conj [contracts/draft-return-model-views-path views*])

      :always
      (into (keep identity
                  [(clear-if-missing :absolute :instrument-id)
                   (clear-if-missing :relative :instrument-id)
                   (clear-if-missing :relative :comparator-instrument-id)])))))

(defn remove-portfolio-optimizer-universe-instrument
  [state instrument-id]
  (let [instrument-id* (common/non-blank-text instrument-id)
        universe (common/draft-universe state)
        universe* (vec (remove #(= instrument-id* (:instrument-id %)) universe))
        constraints (get-in state contracts/draft-constraints-path)]
    (if (and instrument-id*
             (not= universe universe*))
      (let [prefetch-state (history-prefetch/remove-instrument state instrument-id*)
            prefetch-changed? (not= (history-prefetch/prefetch-state state)
                                    prefetch-state)
            path-values (cond-> (into [[contracts/draft-universe-path universe*]
                                        [(conj contracts/draft-constraints-path :allowlist)
                                         (common/set-membership (vec (:allowlist constraints)) instrument-id* false)]
                                        [(conj contracts/draft-constraints-path :blocklist)
                                         (common/set-membership (vec (:blocklist constraints)) instrument-id* false)]
                                        [(conj contracts/draft-constraints-path :held-locks)
                                         (common/set-membership (vec (:held-locks constraints)) instrument-id* false)]
                                        [(conj contracts/draft-constraints-path :asset-overrides)
                                         (dissoc (or (:asset-overrides constraints) {}) instrument-id*)]
                                        [(conj contracts/draft-constraints-path :perp-leverage)
                                         (dissoc (or (:perp-leverage constraints) {}) instrument-id*)]
                                        [contracts/draft-history-assumptions-path
                                         (dissoc (or (get-in state contracts/draft-history-assumptions-path) {})
                                                 instrument-id*)]]
                                       (black-litterman-universe-path-values state universe*))
                          prefetch-changed?
                          (conj [contracts/history-prefetch-path prefetch-state]))]
        (common/save-draft-path-values path-values))
      [])))

(defn clear-portfolio-optimizer-universe
  "Empty the draft universe and every per-asset residue (allow/block lists, held
  locks, asset overrides, per-perp leverage, history assumptions, Black-Litterman
  views) in one step — the \"start from scratch\" escape hatch from the
  holdings-seeded default. The resulting draft is non-default, so the holdings
  preseed will not refill it, and the per-wallet draft autosave remembers the
  cleared choice across reloads."
  [state]
  (let [universe (common/draft-universe state)]
    (if (seq universe)
      (let [prefetch-state (history-prefetch/cleanup-to-instrument-ids
                            (history-prefetch/prefetch-state state)
                            [])
            prefetch-changed? (not= (history-prefetch/prefetch-state state)
                                    prefetch-state)
            path-values (cond-> (into [[contracts/draft-universe-path []]
                                       [contracts/draft-universe-source-path {:kind :custom}]
                                       [(conj contracts/draft-constraints-path :allowlist) []]
                                       [(conj contracts/draft-constraints-path :blocklist) []]
                                       [(conj contracts/draft-constraints-path :held-locks) []]
                                       [(conj contracts/draft-constraints-path :asset-overrides) {}]
                                       [(conj contracts/draft-constraints-path :perp-leverage) {}]
                                       [contracts/draft-history-assumptions-path {}]]
                                      (black-litterman-universe-path-values state []))
                          prefetch-changed?
                          (conj [contracts/history-prefetch-path prefetch-state]))]
        (common/save-draft-path-values path-values))
      [])))

(defn set-portfolio-optimizer-universe-from-current
  [state]
  ;; An untouched (nil or pristine-default) draft is first materialized as a full
  ;; default draft — both for the computation (the holdings-derived constraints
  ;; need a base) and as the first written path-value, so the in-store draft is
  ;; always a complete ::draft that the per-wallet autosave can persist and the
  ;; restore can validate. A touched draft is only updated in place.
  (let [draft (get-in state contracts/draft-path)
        untouched? (optimizer-defaults/untouched-draft? draft)
        state (cond-> state
                untouched? (assoc-in contracts/draft-path
                                     (optimizer-defaults/default-draft)))
        snapshot (current-portfolio/current-portfolio-snapshot state)
        draft-exposures (draft-universe-exposures-from-current state snapshot)
        draft-snapshot (snapshot-with-exposures snapshot draft-exposures)
        {:keys [universe omitted]} (universe-from-current-partition
                                    state
                                    draft-exposures)
        spot-omitted (when-not (include-spot-assets? state)
                       (mapv #(holdings-omission :spot-excluded %)
                             (filter spot-instrument? (:exposures snapshot))))
        universe-source {:kind :holdings
                         :omitted (vec (concat spot-omitted omitted))}
        current-derived-constraints
        (when-let [constraints (get-in state contracts/draft-constraints-path)]
          (current-portfolio/current-derived-constraints draft-snapshot constraints))]
    (if (seq universe)
      (let [prefetch-state (history-prefetch/cleanup-to-instrument-ids
                            (history-prefetch/prefetch-state state)
                            (keep :instrument-id universe))
            prefetch-base-state (assoc-in state
                                          contracts/history-prefetch-path
                                          prefetch-state)
            prefetch-plan (history-prefetch/enqueue-missing-instruments
                           prefetch-base-state
                           universe)
            prefetch-changed? (or (not= (history-prefetch/prefetch-state state)
                                        prefetch-state)
                                  (:changed? prefetch-plan))
            path-values (cond-> (into (cond-> []
                                        untouched?
                                        (conj [contracts/draft-path
                                               (optimizer-defaults/default-draft)])
                                        :always
                                        (into [[contracts/draft-universe-path universe]
                                               [contracts/draft-universe-source-path universe-source]]))
                                      (black-litterman-universe-path-values state universe))
                          current-derived-constraints
                          (conj [contracts/draft-constraints-path
                                 current-derived-constraints])

                          prefetch-changed?
                          (conj [contracts/history-prefetch-path
                                 (:state prefetch-plan)]))]
        (with-prefetch-effect
          (common/save-draft-path-values path-values)
          prefetch-plan))
      [])))

(defn auto-preseed-portfolio-optimizer-universe-from-current
  "Auto-seed the optimizer universe from the user's current holdings when they
  enter a fresh /optimize/new draft, so a returning user reaches a result without
  hand-searching and adding each asset. Reuses
  `set-portfolio-optimizer-universe-from-current` verbatim (universe +
  holdings-derived constraints + history prefetch).

  Fires only on the :optimize-new route and only while the draft is UNTOUCHED
  (absent, or still exactly default-draft), so it never clobbers a universe the
  user has built or deliberately cleared. The underlying seed no-ops when the
  holdings snapshot has not loaded yet; a cold direct page-load where data lands
  after the route event is covered by the holdings-arrival watcher in
  `infrastructure/draft-autosave`, which re-dispatches the restore-or-preseed
  funnel on the data-arrival transition."
  [state path]
  (let [route (portfolio-routes/parse-portfolio-route path)
        draft (get-in state contracts/draft-path)]
    (if (and (= :optimize-new (:kind route))
             (optimizer-defaults/untouched-draft? draft))
      ;; The underlying seed materializes the full default draft itself before
      ;; layering the holdings universe + derived constraints on top.
      (set-portfolio-optimizer-universe-from-current state)
      [])))
