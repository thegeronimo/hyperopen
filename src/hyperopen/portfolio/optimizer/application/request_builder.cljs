(ns hyperopen.portfolio.optimizer.application.request-builder
  (:require [clojure.set :as set]
            [hyperopen.domain.trading.core :as trading-core]
            [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]
            [hyperopen.portfolio.optimizer.application.history-loader.calendar :as calendar]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.constraints :as domain-constraints]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]
            [hyperopen.portfolio.optimizer.infrastructure.prior-data :as prior-data]))

(def default-return-model
  {:kind :historical-mean})

(def default-risk-model
  {:kind :ledoit-wolf-dense})

(def default-objective
  {:kind :minimum-variance})

(defn- draft-universe
  [draft]
  (vec (or (:universe draft) [])))

(def ^:private non-blank-text coercion/non-blank-text)

(def ^:private normalize-id-list coercion/normalize-id-list)

(defn- normalize-net-exposure
  [constraints]
  (let [net-min (:net-min constraints)
        net-max (:net-max constraints)]
    (if (or (some? net-min)
            (some? net-max))
      (cond-> {}
        (some? net-min) (assoc :min net-min)
        (some? net-max) (assoc :max net-max))
      (:net-exposure constraints))))

(def ^:private draft-only-constraint-keys
  #{:gross-min
    :gross-max
    :net-min
    :net-max
    :asset-overrides
    :held-locks
    :perp-leverage})

(defn- normalize-constraints
  [constraints]
  (let [constraints* (or constraints {})
        allowlist (normalize-id-list (:allowlist constraints*))
        blocklist (normalize-id-list (:blocklist constraints*))
        held-locks (normalize-id-list (:held-locks constraints*))
        net-exposure (normalize-net-exposure constraints*)]
    (cond-> (apply dissoc constraints* draft-only-constraint-keys)
      true
      (assoc :blocklist blocklist)

      true
      (assoc :include-spot? (true? (:include-spot? constraints*)))

      (empty? allowlist)
      (dissoc :allowlist)

      (seq allowlist)
      (assoc :allowlist allowlist)

      (contains? constraints* :gross-max)
      (assoc :gross-leverage (:gross-max constraints*))

      (contains? constraints* :gross-min)
      (assoc :gross-floor (:gross-min constraints*))

      (some? net-exposure)
      (assoc :net-exposure net-exposure)

      (contains? constraints* :asset-overrides)
      (assoc :per-asset-overrides (:asset-overrides constraints*))

      (contains? constraints* :held-locks)
      (assoc :held-position-locks held-locks)

      (contains? constraints* :perp-leverage)
      (assoc :per-perp-leverage-caps (:perp-leverage constraints*)))))

(defn fee-bps-for-mode
  "Resolve a draft :fee-mode to a flat fee in basis points (1 bps = 0.01%) from the
   canonical Hyperliquid default fee schedule (hyperopen.domain.trading.core/default-fees,
   expressed in PERCENT; bps = percent * 100). Taker is the conservative default for a
   rebalance (orders cross the spread). Spot legs are never auto-submittable in the
   optimizer (row-status blocks them), so every ready row is a perp and a single
   perp-derived default is honest for the whole preview."
  [fee-mode]
  (let [fees trading-core/default-fees]
    (* 100 (or (get fees fee-mode) (:taker fees)))))

(defn- normalize-execution-assumptions
  [execution-assumptions]
  (let [assumptions* (or execution-assumptions {})
        fallback-slippage-bps (or (:fallback-slippage-bps assumptions*)
                                  (:slippage-fallback-bps assumptions*))]
    (-> (cond-> (dissoc assumptions* :slippage-fallback-bps)
          (some? fallback-slippage-bps)
          (assoc :fallback-slippage-bps fallback-slippage-bps))
        ;; Derive the fee assumption once, here, so BOTH preview build sites (the
        ;; worker payload and the frontend refresh) inherit it via execution-assumptions.
        (assoc :default-fee-bps (fee-bps-for-mode (:fee-mode assumptions*))))))

(declare align-history universe-by-id)

(defn- proxy-regression-series
  "The short-overlap regression input for one proxy-mode asset: the asset's and
  its proxies' return series aligned on THEIR common calendar (which is exactly
  the asset's usable overlap window). Computed here because the engine history
  deliberately excludes the asset (see `alignment-universe`), so the worker-side
  synthesis cannot derive the overlap itself. Returns nil when the asset has no
  usable overlap at all."
  [instrument-id proxy-ids requested-universe align-inputs]
  (let [by-id (universe-by-id requested-universe)
        sub-universe (into []
                           (keep by-id)
                           (cons instrument-id proxy-ids))
        sub-history (when (seq sub-universe)
                      (align-history (assoc align-inputs :universe sub-universe)))
        x-returns (get-in sub-history [:return-series-by-instrument instrument-id])]
    (when (seq x-returns)
      {:x-returns (vec x-returns)
       :proxy-returns-by-id (into {}
                                  (keep (fn [proxy-id]
                                          (when-let [series (get-in sub-history
                                                                    [:return-series-by-instrument
                                                                     proxy-id])]
                                            [proxy-id (vec series)])))
                                  proxy-ids)
       :observations (count x-returns)})))

(defn- normalize-proxy-entry
  "Flattens a draft proxy entry into the engine shape (proxy ids, prior weights,
  relationship strength as top-level keys) and, when the entry is engine-backed,
  attaches the short-overlap regression series."
  [instrument-id entry engine-backed? requested-universe align-inputs]
  (let [proxy-ids (history-assumptions/proxy-instrument-ids entry)]
    (cond-> (-> entry
                (dissoc :proxy :metadata)
                (assoc :proxy-instrument-ids proxy-ids
                       :proxy-prior-weights (history-assumptions/proxy-prior-weights entry)
                       :relationship-strength (history-assumptions/relationship-strength entry)))
      engine-backed?
      (assoc :regression-series (proxy-regression-series instrument-id
                                                         proxy-ids
                                                         requested-universe
                                                         align-inputs)))))

(defn- normalize-history-assumptions
  "Engine-shaped per-asset assumptions: conservative draft entries are carried
  through unchanged; proxy entries are flattened (and, when engine-backed, carry
  their short-overlap regression series)."
  [assumptions {:keys [proxy-engine-ids requested-universe align-inputs]}]
  (when (seq assumptions)
    (reduce-kv (fn [acc id entry]
                 (assoc acc id
                        (if (history-assumptions/proxy? entry)
                          (normalize-proxy-entry id
                                                 entry
                                                 (contains? proxy-engine-ids id)
                                                 requested-universe
                                                 align-inputs)
                          entry)))
               {}
               assumptions)))

(def ^:private finite-number? coercion/finite-number?)

(defn- non-zero-current-row?
  [row]
  (let [weight (:weight row)]
    (and (finite-number? weight)
         (not (zero? weight)))))

(defn- current-row-instrument
  [row]
  (select-keys row
               [:instrument-id
                :market-type
                :instrument-type
                :coin
                :dex
                :symbol
                :base
                :quote
                :name
                :vault-address
                :hip3?
                :optimizer-history/instrument-id
                :optimizer-history/display-symbol
                :optimizer-history/instrument-kind
                :optimizer-history/history-status
                :optimizer-history/quality-status
                :optimizer-history/proxy]))

(defn- universe-by-id
  [universe]
  (into {}
        (map (fn [instrument]
               [(:instrument-id instrument) instrument]))
        universe))

(defn- instrument-id-set
  [universe]
  (set (keep :instrument-id universe)))

(defn- conservative-engine-ids
  "Instrument-ids whose conservative assumption is complete enough to fold into the
  optimization for this objective."
  [assumptions objective]
  (let [return-required? (history-assumptions/return-required-for-objective?
                          (:kind objective))]
    (->> assumptions
         (keep (fn [[id entry]]
                 (when (history-assumptions/conservative-assumption-complete?
                        entry return-required?)
                   id)))
         set)))

(defn- structurally-complete-proxy-ids
  "Proxy entries complete on their own terms (proxies selected, no self-proxy,
  volatility, cap within the global max, expected return when the objective needs
  one). These are pulled OUT of the alignment universe so their short native
  series cannot shrink the shared estimation window; whether their proxies have
  usable history is validated after alignment (see `validated-proxy-ids`)."
  [assumptions objective constraints]
  (let [return-required? (history-assumptions/return-required-for-objective?
                          (:kind objective))
        max-asset-weight (:max-asset-weight constraints)]
    (->> assumptions
         (keep (fn [[id entry]]
                 (when (and (history-assumptions/proxy? entry)
                            (history-assumptions/proxy-assumption-complete?
                             entry
                             {:self-id id
                              :return-required? return-required?
                              :max-asset-weight max-asset-weight}))
                   id)))
         set)))

(defn usable-proxy-id-set
  "Assets allowed to serve as proxies: their realized history reached the risk
  model (aligned eligible) and they do not lean on a history assumption
  themselves (a synthetic row cannot anchor another synthetic row).

  Off-calendar sparse members are eligible but carry NO shared-calendar return
  series, so `proxy-regression-series` would read nil returns and silently drop
  the regression while the proxy still validated. They cannot anchor anything."
  ([eligible-universe assumptions]
   (usable-proxy-id-set eligible-universe assumptions nil))
  ([eligible-universe assumptions off-calendar-ids]
   (let [off-calendar (set off-calendar-ids)]
     (->> eligible-universe
          (keep :instrument-id)
          (remove #(some? (:behavior (get assumptions %))))
          (remove off-calendar)
          set))))

(defn- validated-proxy-ids
  "The structurally complete proxy ids whose every selected proxy has usable
  optimizer history. The rest stay out of the engine universe and readiness
  blocks the run with per-asset guidance."
  [candidate-ids assumptions usable-ids]
  (->> candidate-ids
       (filter (fn [id]
                 (every? usable-ids
                         (history-assumptions/proxy-instrument-ids
                          (get assumptions id)))))
       set))

(defn- readmit-assumption-instruments
  "Adds engine-backed assumption assets that the engine history does not cover
  back into the engine universe so they reach the solver: conservative assets
  that alignment dropped (no history), and proxy assets (always, since complete
  proxy assets are excluded from alignment by design)."
  [eligible-universe requested-universe engine-backed-ids]
  (let [eligible-ids (instrument-id-set eligible-universe)
        requested-by-id (universe-by-id requested-universe)
        readmitted (->> engine-backed-ids
                        (remove eligible-ids)
                        (keep requested-by-id)
                        vec)]
    (into (vec eligible-universe) readmitted)))

(defn- mirror-assumption-caps
  "Mirrors each engine-backed assumption cap (conservative or proxy) into the
  per-asset-overrides that the constraint machinery already enforces, taking the
  tighter of any existing cap."
  [constraints assumptions engine-backed-ids]
  (reduce (fn [acc id]
            (let [cap (get-in assumptions [id :max-weight])]
              (if (coercion/positive-number? cap)
                (update-in acc [:per-asset-overrides id]
                           domain-constraints/merge-max-weight-override cap)
                acc)))
          constraints
          engine-backed-ids))

(defn- current-portfolio-rows
  [current-portfolio]
  (let [exposures (seq (:exposures current-portfolio))]
    (if exposures
      exposures
      (vals (or (:by-instrument current-portfolio) {})))))

(defn- current-portfolio-universe
  [current-portfolio requested-universe]
  (let [requested-by-id (universe-by-id requested-universe)]
    (vec (vals (reduce (fn [acc row]
                         (let [instrument-id (:instrument-id row)]
                           (if (and instrument-id
                                    (non-zero-current-row? row))
                             (assoc acc
                                    instrument-id
                                    (merge (get requested-by-id instrument-id)
                                           (current-row-instrument row)))
                             acc)))
                       {}
                       (current-portfolio-rows current-portfolio))))))

(defn- current-history-source
  [history-data current-universe requested-universe]
  (let [current-ids (instrument-id-set current-universe)
        selected-ids (instrument-id-set requested-universe)]
    (cond
      (empty? current-ids)
      nil

      (set/subset? current-ids selected-ids)
      history-data

      :else
      (:current-portfolio-history-data history-data))))

(defn- confidence-variance
  [confidence]
  (let [confidence* (-> confidence
                        (max 0.0)
                        (min 1.0))]
    (max 0.000001 (- 1.0 confidence*))))

(defn- confidence-variance*
  [view*]
  (let [confidence (:confidence view*)]
    (cond
      (finite-number? (:confidence-variance view*))
      (:confidence-variance view*)

      (finite-number? confidence)
      (confidence-variance confidence)

      :else
      nil)))

(defn- invalid-black-litterman-view-warning
  [view]
  (cond-> {:code :invalid-black-litterman-view}
    (:id view) (assoc :view-id (:id view))))

(defn- black-litterman-view-outside-universe-warning
  [view instrument-ids]
  (cond-> {:code :black-litterman-view-outside-universe
           :instrument-ids (vec instrument-ids)}
    (:id view) (assoc :view-id (:id view))))

(defn- normalize-direction
  [direction]
  (case direction
    :underperform :underperform
    :outperform :outperform
    :outperform))

(defn- normalize-black-litterman-view
  [view]
  (let [view* (or view {})
        confidence (:confidence view*)
        instrument-id (non-blank-text (:instrument-id view*))
        comparator-id (non-blank-text (:comparator-instrument-id view*))
        long-id (non-blank-text (:long-instrument-id view*))
        short-id (non-blank-text (:short-instrument-id view*))
        direction (normalize-direction (:direction view*))
        confidence-variance* (confidence-variance* view*)]
    (cond
      (and (= :absolute (:kind view*))
           instrument-id
           (finite-number? (:return view*)))
      {:view (cond-> (assoc view* :weights (or (:weights view*)
                                               {instrument-id 1}))
               confidence-variance*
               (assoc :confidence-variance confidence-variance*))}

      (and (= :relative (:kind view*))
           instrument-id
           comparator-id
           (not= instrument-id comparator-id)
           (finite-number? (:return view*)))
      {:view (cond-> (assoc view*
                            :direction direction
                            :weights (case direction
                                       :underperform {instrument-id -1
                                                      comparator-id 1}
                                       {instrument-id 1
                                        comparator-id -1}))
               confidence-variance*
               (assoc :confidence-variance confidence-variance*))}

      (and (= :relative (:kind view*))
           long-id
           short-id
           (not= long-id short-id)
           (finite-number? (:return view*)))
      {:view (cond-> (assoc view* :weights (or (:weights view*)
                                               {long-id 1
                                                short-id -1}))
               confidence-variance*
               (assoc :confidence-variance confidence-variance*))}

      :else
      {:warning (invalid-black-litterman-view-warning view*)})))

(defn- normalize-return-model
  [return-model]
  (let [return-model* (or return-model default-return-model)]
    (if (= :black-litterman (:kind return-model*))
      (let [normalized (map normalize-black-litterman-view
                            (or (:views return-model*) []))]
        {:return-model (assoc return-model*
                              :views (vec (keep :view normalized)))
         :warnings (vec (keep :warning normalized))})
      {:return-model return-model*
       :warnings []})))

(defn- non-zero-weight-id?
  [[instrument-id weight]]
  (and (non-blank-text instrument-id)
       (finite-number? weight)
       (not (zero? weight))))

(defn- black-litterman-view-instrument-ids
  [view]
  (let [weights (->> (:weights view)
                     (filter non-zero-weight-id?)
                     (map first)
                     vec)]
    (case (:kind view)
      :relative
      (let [ids (or (seq (normalize-id-list [(:instrument-id view)
                                             (:comparator-instrument-id view)]))
                    (seq (normalize-id-list [(:long-instrument-id view)
                                             (:short-instrument-id view)]))
                    weights)]
        (vec ids))

      :absolute
      (let [ids (or (seq (normalize-id-list [(:instrument-id view)]))
                    weights)]
        (vec ids))

      weights)))

(defn- view-overlaps-eligible-universe?
  [eligible-ids view]
  (let [ids (black-litterman-view-instrument-ids view)]
    (case (:kind view)
      :relative
      (and (seq ids)
           (every? #(contains? eligible-ids %) ids))

      (boolean
       (some #(contains? eligible-ids %) ids)))))

(defn- filter-black-litterman-views-for-universe
  [return-model eligible-universe]
  (if-not (= :black-litterman (:kind return-model))
    {:return-model return-model
     :warnings []}
    (let [eligible-ids (set (keep :instrument-id eligible-universe))
          normalized (map (fn [view]
                            (if (view-overlaps-eligible-universe? eligible-ids view)
                              {:view view}
                              {:warning
                               (black-litterman-view-outside-universe-warning
                                view
                                (black-litterman-view-instrument-ids view))}))
                          (or (:views return-model) []))]
      {:return-model (assoc return-model
                            :views (vec (keep :view normalized)))
       :warnings (vec (keep :warning normalized))})))

(defn- black-litterman-return-model?
  [return-model]
  (= :black-litterman (:kind return-model)))

(defn- bl-prior
  [universe current-portfolio market-cap-by-coin return-model]
  (when (black-litterman-return-model? return-model)
    (prior-data/resolve-black-litterman-prior
     {:universe universe
      :market-cap-by-coin market-cap-by-coin
      :current-portfolio current-portfolio})))

(def ^:private align-history-memo-capacity
  ;; Eviction is a wholesale reset, so the capacity must exceed the largest
  ;; realistic build: requested + currently-held universes PLUS one sub-universe
  ;; per engine-backed proxy assumption (30+ with dozens of assumptions; the old
  ;; capacity of 16 reset MID-BUILD and nothing survived to the next render).
  64)

(defonce ^:private align-history-memo
  (volatile! {}))

(defn- align-history
  ;; Alignment walks every candle series and dominates request building, but
  ;; its inputs only change when the universe or loaded history move - not
  ;; when draft constraints are edited. The memo key deliberately EXCLUDES
  ;; :as-of-ms / :stale-after-ms: those fall back to a wall-clock bucket, and
  ;; keying on them re-ran the full alignment every bucket roll (multi-second
  ;; render stalls on a fixed cadence). INVARIANT: alignment may consume the
  ;; as-of ONLY for the O(1) :freshness stamp, re-stamped here on every call.
  [{:keys [universe
           history-data
           as-of-ms
           stale-after-ms
           funding-periods-per-year]}]
  (let [inputs {:universe universe
                :api-v2-history (:api-v2-history history-data)
                :candle-history-by-coin (:candle-history-by-coin history-data)
                :funding-history-by-coin (:funding-history-by-coin history-data)
                :vault-details-by-address (:vault-details-by-address history-data)
                :funding-periods-per-year funding-periods-per-year}
        memo @align-history-memo
        aligned (if-let [entry (find memo inputs)]
                  (val entry)
                  (let [value (history-loader/align-history-inputs inputs)
                        memo* (if (>= (count memo) align-history-memo-capacity)
                                {}
                                memo)]
                    (vreset! align-history-memo (assoc memo* inputs value))
                    value))]
    (assoc aligned
           ;; Alignment's own :freshness is recomputed here because as-of-ms is
           ;; not an alignment input (it would bust the memo every tick). Fall
           ;; back to :freshness-calendar when the shared calendar is empty: a
           ;; universe whose only members ride the off-calendar sparse lane has no
           ;; shared calendar, and calendar/freshness reports stale? true for an
           ;; empty one, so current data would read as permanently stale.
           :freshness (calendar/freshness (if (seq (:calendar aligned))
                                            (:calendar aligned)
                                            (:freshness-calendar aligned))
                                          as-of-ms
                                          stale-after-ms))))

(defn build-engine-request
  [{:keys [draft
           current-portfolio
           history-data
           market-cap-by-coin
           as-of-ms
           stale-after-ms
           funding-periods-per-year
           frontier-points-override]}]
  (let [draft* (contracts/migrate-draft draft)
        requested-universe (draft-universe draft*)
        normalized-return-model (normalize-return-model (:return-model draft*))
        return-model (:return-model normalized-return-model)
        risk-model (or (:risk-model draft*) default-risk-model)
        ;; Refinement raises the frontier point budget on the objective; the engine
        ;; clamps to its [2,80] cap (domain.objectives/bounded-frontier-point-count).
        objective (cond-> (or (:objective draft*) default-objective)
                    (coercion/finite-number? frontier-points-override)
                    (assoc :frontier-points frontier-points-override))
        constraints (normalize-constraints (:constraints draft*))
        ;; History assumptions are folded into the optimization: their assets are
        ;; re-admitted to the engine universe, their caps mirror into the constraint
        ;; machinery, and the assumptions ride along for covariance synthesis in
        ;; engine.context. A structurally complete PROXY asset is additionally pulled
        ;; out of the alignment universe first: history alignment intersects every
        ;; eligible asset's return calendar, so a 10-day asset would otherwise shrink
        ;; the shared covariance-estimation window of the whole universe.
        draft-assumptions (:history-assumptions draft*)
        ;; Reference-only proxy instruments: catalog proxies picked for a thin
        ;; asset that are NOT in the portfolio universe. Their history is aligned
        ;; (so covariance synthesis can use them) but they are excluded from the
        ;; allocatable engine universe below, so they never receive a weight.
        ;; A stored reference that IS a universe member is not reference-only -
        ;; the user allocates it by right - so it must neither be evicted from
        ;; the engine universe nor ride the alignment universe a second time
        ;; (live 2026-07-09: an emptied proxy basket left BTC/ETH in the stored
        ;; refs and silently evicted both from an 88-asset universe).
        requested-ids (instrument-id-set requested-universe)
        reference-instruments (into []
                                    (remove #(contains? requested-ids
                                                        (:instrument-id %)))
                                    (:proxy-reference-instruments draft*))
        reference-ids (set (keep :instrument-id reference-instruments))
        ;; Metadata pool for resolving proxy instruments (universe + references),
        ;; used by the regression sub-alignment.
        proxy-universe (into requested-universe reference-instruments)
        proxy-candidate-ids (structurally-complete-proxy-ids draft-assumptions
                                                             objective
                                                             (:constraints draft*))
        conservative-ids (conservative-engine-ids draft-assumptions objective)
        ;; Complete-assumption assets of EITHER behavior leave the alignment
        ;; universe: their synthesized covariance row never uses the aligned
        ;; series, and keeping a thin native series in the intersection would
        ;; silently shrink every other asset's estimation window.
        assumption-excluded-ids (into conservative-ids proxy-candidate-ids)
        ;; Alignment = requested (minus assumption-excluded thin assets) PLUS the
        ;; reference-only proxies, so their return series reach the risk result.
        alignment-universe (into (if (seq assumption-excluded-ids)
                                   (filterv #(not (contains? assumption-excluded-ids
                                                             (:instrument-id %)))
                                            requested-universe)
                                   requested-universe)
                                 reference-instruments)
        align-inputs {:history-data history-data
                      :as-of-ms as-of-ms
                      :stale-after-ms stale-after-ms
                      :funding-periods-per-year funding-periods-per-year}
        history (align-history (assoc align-inputs :universe alignment-universe))
        eligible-universe (:eligible-instruments history)
        usable-proxy-ids (usable-proxy-id-set eligible-universe
                                              draft-assumptions
                                              (:off-calendar-instrument-ids history))
        proxy-engine-ids (validated-proxy-ids proxy-candidate-ids
                                              draft-assumptions
                                              usable-proxy-ids)
        assumption-engine-ids (into conservative-ids proxy-engine-ids)
        ;; Reference-only proxies aligned into eligible-universe, but they must
        ;; NOT be allocatable - drop them before building the engine universe.
        allocatable-eligible (if (seq reference-ids)
                               (filterv #(not (contains? reference-ids
                                                         (:instrument-id %)))
                                        eligible-universe)
                               eligible-universe)
        engine-universe (readmit-assumption-instruments allocatable-eligible
                                                       requested-universe
                                                       assumption-engine-ids)
        constraints (mirror-assumption-caps constraints
                                            draft-assumptions
                                            assumption-engine-ids)
        history-assumptions* (normalize-history-assumptions
                              draft-assumptions
                              {:proxy-engine-ids proxy-engine-ids
                               :requested-universe proxy-universe
                               :align-inputs align-inputs})
        current-universe (current-portfolio-universe current-portfolio
                                                     requested-universe)
        current-history-data (current-history-source history-data
                                                     current-universe
                                                     requested-universe)
        current-history (when (and (seq current-universe)
                                   current-history-data)
                          (cond-> (align-history
                                   {:universe current-universe
                                    :history-data current-history-data
                                    :as-of-ms as-of-ms
                                    :stale-after-ms stale-after-ms
                                    :funding-periods-per-year funding-periods-per-year})
                            (seq (:requested-instrument-ids current-history-data))
                            (assoc :requested-instrument-ids
                                   (:requested-instrument-ids current-history-data))))
        universe-filtered-return-model (filter-black-litterman-views-for-universe
                                        return-model
                                        eligible-universe)
        return-model (:return-model universe-filtered-return-model)
        prior (bl-prior requested-universe
                        current-portfolio
                        market-cap-by-coin
                        return-model)
        warnings (vec (concat (:warnings normalized-return-model)
                              (:warnings universe-filtered-return-model)
                              (:warnings history)
                              (:warnings prior)))]
    (cond-> {:scenario-id (:id draft*)
             :universe engine-universe
             :requested-universe requested-universe
             :current-portfolio-universe current-universe
             :current-portfolio current-portfolio
             :current-portfolio-history current-history
             :return-model return-model
             :risk-model risk-model
             :objective objective
             :constraints constraints
             :execution-assumptions (normalize-execution-assumptions
                                     (:execution-assumptions draft*))
             :history history
             :warnings warnings
             :as-of-ms as-of-ms}
      (seq history-assumptions*) (assoc :history-assumptions history-assumptions*)
      prior (assoc :black-litterman-prior prior))))
