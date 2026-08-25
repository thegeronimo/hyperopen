(ns hyperopen.portfolio.optimizer.application.history-loader.api-v2.alignment
  (:require [hyperopen.portfolio.optimizer.application.history-loader.api-v2.codec :as codec]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.legacy-fallback :as legacy-fallback]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.sparse-lane :as sparse-lane]
            [hyperopen.portfolio.optimizer.application.history-loader.calendar :as calendar]
            [hyperopen.portfolio.optimizer.application.history-loader.instruments :as instruments]
            [hyperopen.portfolio.optimizer.application.history-loader.window :as history-window]
            [hyperopen.portfolio.optimizer.domain.history-series :as history-series]
            [hyperopen.portfolio.optimizer.domain.return-plausibility :as plausibility]))

(def default-min-observations
  2)

(defn- usable-series?
  [series]
  (and (map? series)
       (not (contains? #{:missing :rejected}
                       (:lineage-kind series)))
       (seq (:points series))))

(defn- missing-series-warning
  [instrument series]
  (let [local-id (instruments/normalize-instrument-id instrument)
        series-warning (first (:warnings series))]
    (or series-warning
        {:code (if (= :rejected (:lineage-kind series))
                 :validation-failed
                 :missing-candle-history)
         :instrument-id local-id
         :market-type (instruments/market-type instrument)})))

(defn- funding-summary
  [instrument series]
  (let [funding (:funding series)
        status (:status funding)
        carry (or (:annualized-carry funding) 0)
        perp? (instruments/perp-instrument? instrument)]
    (case status
      :available
      {:source (if (= :legacy-fallback (:lineage-kind series))
                 :legacy-fallback
                 :history-api-v2)
       :annualized-carry carry
       :status :available}

      :not-applicable
      {:source :not-applicable
       :annualized-carry 0
       :status :not-applicable}

      (:missing :rejected)
      (if perp?
        {:source :missing-market-funding-history
         :annualized-carry 0
         :status status}
        {:source :not-applicable
         :annualized-carry 0
         :status :not-applicable
         :diagnostic-status status})

      (if perp?
        {:source :missing-market-funding-history
         :annualized-carry 0
         :status :missing}
        {:source :not-applicable
         :annualized-carry 0
         :status :not-applicable}))))

(defn- funding-warning
  [instrument series]
  (when (and (instruments/perp-instrument? instrument)
             (= :missing (get-in series [:funding :status])))
    {:code :funding-history-missing
     :instrument-id (instruments/normalize-instrument-id instrument)}))

(defn- prices-for-calendar
  [points calendar]
  (let [by-time (calendar/row-by-time points)]
    (mapv #(get by-time %) calendar)))

(defn- points-by-local-id
  ;; The calendar helpers consume id -> point rows; alignment carries id -> series.
  [series-by-local-id]
  (into {}
        (map (fn [[local-id series]]
               [local-id (:points series)]))
        series-by-local-id))

(defn- point-level-return-calendar
  [series-by-local-id calendar]
  (calendar/point-level-return-calendar (points-by-local-id series-by-local-id)
                                        calendar))

(defn- returns-from-point-level
  [series-by-local-id return-calendar]
  (calendar/returns-from-point-level (points-by-local-id series-by-local-id)
                                     return-calendar))

(defn- point-return-count
  [series]
  (count (calendar/point-return-map (:points series))))

(def ^:private api-v2-hard-warning-codes
  #{:identity-ambiguous
    :instrument-kind-mismatch
    :proxy-mapping-unapproved
    :proxy-validation-failed
    :validation-failed})

(def ^:private api-v2-display-data-warning-codes
  #{:missing-candle-history
    :insufficient-candle-history})

(defn- aligned-return-entry
  [api-v2-history local-id]
  (get-in api-v2-history [:aligned-returns-by-instrument local-id]))

(defn- aligned-return-values
  [api-v2-history local-id]
  (vec (:returns (aligned-return-entry api-v2-history local-id))))

(defn- usable-aligned-returns?
  "Complete, finite, AND every value a plausible return. An implausible value
  demotes to the point-level path, where `calendar/point-return-map` drops the
  offending timestamps - established behaviour, since multi-chunk loads already
  demote routinely on the length check below."
  [api-v2-history local-id min-return-observations]
  (let [return-calendar (vec (:return-calendar api-v2-history))
        returns (aligned-return-values api-v2-history local-id)]
    (and (seq return-calendar)
         (= (count return-calendar) (count returns))
         (>= (count returns) min-return-observations)
         (every? codec/finite-number? returns)
         (not-any? plausibility/implausible-return? returns))))

(defn- all-selected-aligned-returns-usable?
  [api-v2-history local-ids min-return-observations]
  (and (seq local-ids)
       (every? #(usable-aligned-returns?
                 api-v2-history
                 %
                 min-return-observations)
               local-ids)))

(defn- api-v2-blocking-warning?
  [warning]
  (contains? api-v2-hard-warning-codes (:code warning)))

(defn- display-data-warning?
  [warning]
  (contains? api-v2-display-data-warning-codes (:code warning)))

(def ^:private proxy-extension-warning-codes
  ;; Codes about the backend's OPTIONAL catalog-proxy lookback extension. When
  ;; that extension fails validation the backend falls back to serving the
  ;; asset's own native series, so the warning documents a shorter lookback -
  ;; it must not reject the served series itself (live 2026-07-07: STRK's
  ;; Tiingo extension failed quality checks while 870 usable native daily
  ;; points were served, and the hard exclusion discarded all of them).
  #{:proxy-mapping-unapproved
    :proxy-validation-failed})

(def ^:private own-history-lineage-kinds
  ;; Lineages that are the asset's OWN realized history - the only series a
  ;; failed-proxy warning may be forgiven against. A proxy-derived or unknown
  ;; lineage stays hard-excluded when its proxy failed validation.
  #{:native :vault-derived})

(defn- own-usable-series?
  [series]
  (and (usable-series? series)
       (contains? own-history-lineage-kinds (:lineage-kind series))
       (not (contains? #{:failed :rejected}
                       (get-in series [:quality :status])))))

(defn- forgiven-proxy-warning?
  [warning series]
  (and (contains? proxy-extension-warning-codes (:code warning))
       (own-usable-series? series)))

(defn- warning-id-map
  [rows]
  (into {}
        (mapcat (fn [{:keys [instrument-id backend-id]}]
                  (cond-> [[instrument-id instrument-id]]
                    backend-id
                    (conj [backend-id instrument-id]))))
        rows))

(defn- canonical-warning
  [id-map warning]
  (let [warning-id (:instrument-id warning)]
    (cond-> warning
      (contains? id-map warning-id)
      (assoc :instrument-id (get id-map warning-id)))))

(defn- warning-local-id
  [id-map warning]
  (or (get id-map (:instrument-id warning))
      (:instrument-id warning)))

(defn- warning-targets-instrument?
  [local-id backend-id warning]
  (contains? (cond-> #{local-id}
               backend-id (conj backend-id))
             (:instrument-id warning)))

(defn- hard-warning-for-instrument?
  [api-v2-history local-id backend-id series]
  (boolean
   (some (fn [warning]
           (and (legacy-fallback/hard-warning? warning)
                (not (forgiven-proxy-warning? warning series))
                (warning-targets-instrument? local-id backend-id warning)))
         (:warnings api-v2-history))))

(defn- return-history-warning
  [instrument api-v2-history min-return-observations]
  (let [local-id (instruments/normalize-instrument-id instrument)
        returns (aligned-return-values api-v2-history local-id)
        observations (count (filter codec/finite-number? returns))
        missing? (empty? returns)]
    (cond-> {:code (if missing?
                     :missing-return-history
                     :insufficient-return-history)
             :instrument-id local-id
             :observations observations
             :required min-return-observations
             :market-type (instruments/market-type instrument)}
      missing?
      (dissoc :observations :required))))

(defn align-api-v2-history-inputs
  [{:keys [universe
           api-v2-history
           candle-history-by-coin
           funding-history-by-coin
           vault-details-by-address
           as-of-ms
           stale-after-ms
           funding-periods-per-year
           min-observations]}]
  (let [min-observations* (or min-observations default-min-observations)
        funding-periods-per-year* (or funding-periods-per-year
                                      legacy-fallback/default-funding-periods-per-year)
        min-return-observations (max 1 (dec min-observations*))
        series-by-instrument (:series-by-instrument api-v2-history)
        rows (mapv (fn [instrument]
                     (let [local-id (instruments/normalize-instrument-id instrument)
                           backend-id (codec/non-blank-text
                                       (:optimizer-history/instrument-id instrument))
                           api-series (get series-by-instrument local-id)
                           hard-api-warning? (hard-warning-for-instrument?
                                              api-v2-history
                                              local-id
                                              backend-id
                                              api-series)
                           ;; The legacy series is only consulted when the api
                           ;; series can't be used, and building it (candle +
                           ;; funding normalization) dominates alignment cost —
                           ;; never compute it for an instrument the api serves.
                           fallback-wanted? (and (not hard-api-warning?)
                                                 (legacy-fallback/series-fallback-needed?
                                                  api-series))
                           legacy-series* (when fallback-wanted?
                                            (legacy-fallback/series
                                             instrument
                                             candle-history-by-coin
                                             funding-history-by-coin
                                             vault-details-by-address
                                             funding-periods-per-year*))
                           legacy-fallback? (and fallback-wanted?
                                                 (usable-series? legacy-series*))
                           series (if legacy-fallback?
                                    legacy-series*
                                    api-series)]
                       {:instrument instrument
                        :instrument-id local-id
                        :backend-id backend-id
                        :legacy-fallback? legacy-fallback?
                        :series series}))
                   (or universe []))
        ;; Pre-eligibility depth of each member's OWN served series. Readiness
        ;; and the universe badges consume this so a degraded alignment (e.g. a
        ;; collapsed shared calendar) can never blind them to how much history
        ;; an excluded asset actually has.
        served-observations-by-instrument
        (into {}
              (keep (fn [{:keys [instrument-id series]}]
                      (when (and instrument-id (seq (:points series)))
                        [instrument-id (count (:points series))])))
              rows)
        id-map (warning-id-map rows)
        fallback-local-ids (set (keep (fn [{:keys [instrument-id
                                                   legacy-fallback?]}]
                                        (when legacy-fallback?
                                          instrument-id))
                                      rows))
        series-by-local-id (into {}
                                 (map (juxt :instrument-id :series))
                                 rows)
        api-warnings (->> (concat (:warnings api-v2-history)
                                  (mapcat (fn [{:keys [series
                                                       legacy-fallback?]}]
                                            (when-not legacy-fallback?
                                              (:warnings series)))
                                          rows))
                          (mapv #(canonical-warning id-map %))
                          (remove #(legacy-fallback/suppress-warning?
                                    fallback-local-ids
                                    (warning-local-id id-map %)
                                    %))
                          ;; A failed OPTIONAL proxy lookback-extension on an
                          ;; asset whose served series is its own usable native
                          ;; history stays visible as information, but must not
                          ;; read as a rejection anywhere downstream: pre-tag it
                          ;; so status projection and run blocking skip it (live
                          ;; 2026-07-08: POL aligned on 658 native days yet
                          ;; badged "No history" off its forgiven warning).
                          (mapv (fn [warning]
                                  (cond-> warning
                                    (forgiven-proxy-warning?
                                     warning
                                     (get series-by-local-id
                                          (warning-local-id id-map warning)))
                                    (assoc :forgiven? true))))
                          vec)
        hard-warning-by-local-id (into {}
                                       (keep (fn [warning]
                                               (let [local-id (warning-local-id
                                                               id-map
                                                               warning)]
                                                 (when (and (api-v2-blocking-warning?
                                                             warning)
                                                            (not (:forgiven? warning)))
                                                   [local-id warning]))))
                                       api-warnings)
        base-candidates (filterv (fn [{:keys [instrument-id backend-id series]}]
                                   (and instrument-id
                                        (or backend-id series)
                                        (not (contains? hard-warning-by-local-id
                                                        instrument-id))
                                        (not= :rejected (:lineage-kind series))))
                                 rows)
        ;; Members whose own series is long but sampled far too coarsely to share
        ;; a daily calendar (a downsampled vault history). They stay in the
        ;; universe and are estimated by the mixed-frequency pairwise path from
        ;; their native rows; they never join the intersection below, so they
        ;; cannot shrink anyone else's window. See history-loader.api-v2.sparse-lane.
        off-calendar-cadence-by-id (sparse-lane/cadence-by-id
                                    base-candidates
                                    #(usable-aligned-returns? api-v2-history
                                                              %
                                                              min-return-observations))
        off-calendar-local-ids (set (keys off-calendar-cadence-by-id))
        calendar-candidates (if (seq off-calendar-local-ids)
                              (filterv #(not (contains? off-calendar-local-ids
                                                        (:instrument-id %)))
                                       base-candidates)
                              base-candidates)
        ;; Reject superset calendars that omit an actual member-valid timestamp.
        candidate-series-by-id (into {}
                                     (map (juxt :instrument-id :series))
                                     calendar-candidates)
        candidate-calendars (delay
                              (let [common (calendar/common-calendar
                                            (map :points (vals candidate-series-by-id)))]
                                [common (point-level-return-calendar
                                         candidate-series-by-id common)]))
        ;; Deliberately still the FULL candidate set: an off-calendar member the
        ;; backend also served would otherwise read as an unexpected extra key and
        ;; falsely trip calendar-poisoned?.
        response-superset? (let [member? (set (map :instrument-id base-candidates))]
                             (boolean
                              (some #(not (member? %))
                                    (concat (keys (or (:aligned-returns-by-instrument
                                                       api-v2-history)
                                                      {}))
                                            (keys (or (:series-by-instrument
                                                       api-v2-history)
                                                      {}))))))
        calendar-poisoned? (and response-superset?
                                (let [[common returns] @candidate-calendars]
                                  (or (not (every? (set (:common-calendar api-v2-history)) common))
                                      (not (every? (set (:return-calendar api-v2-history)) returns)))))
        use-aligned? (and (not calendar-poisoned?)
                          (all-selected-aligned-returns-usable?
                           api-v2-history
                           (mapv :instrument-id calendar-candidates)
                           min-return-observations))
        prepared (mapv (fn [{:keys [instrument instrument-id backend-id series]
                             :as row}]
                         (let [hard-warning (get hard-warning-by-local-id
                                                 instrument-id)]
                           (cond
                             (or (not instrument-id)
                                 (and (not backend-id)
                                      (nil? series)))
                             (assoc row
                                    :excluded? true
                                    :warning {:code :identity-ambiguous
                                              :instrument-id instrument-id
                                              :market-type (instruments/market-type
                                                            instrument)})

                             hard-warning
                             (assoc row
                                    :excluded? true
                                    :warning hard-warning)

                             (= :rejected (:lineage-kind series))
                             (assoc row
                                    :excluded? true
                                    :warning (missing-series-warning instrument
                                                                    series))

                             ;; In the universe, off the shared calendar. Must sit
                             ;; ABOVE use-aligned? so a member the backend happens
                             ;; to have aligned still takes the lane.
                             (contains? off-calendar-local-ids instrument-id)
                             (assoc row
                                    :excluded? false
                                    :off-calendar? true
                                    :warning (sparse-lane/warning
                                              instrument-id
                                              (get off-calendar-cadence-by-id
                                                   instrument-id)))

                             use-aligned?
                             (assoc row :excluded? false)

                             (not (usable-series? series))
                             (assoc row
                                    :excluded? true
                                    :warning (return-history-warning
                                              instrument
                                              api-v2-history
                                              min-return-observations))

                             (< (point-return-count series)
                                min-return-observations)
                             (assoc row
                                    :excluded? true
                                    :warning {:code :insufficient-return-history
                                              :instrument-id instrument-id
                                              :observations (point-return-count
                                                             series)
                                              :required min-return-observations
                                              :market-type (instruments/market-type
                                                            instrument)})

                             :else
                             (assoc row :excluded? false))))
                       rows)
        ;; ONE predicate for shared-calendar membership, used by BOTH `eligible`
        ;; bindings (here and after the peel below). A lane row carries
        ;; :excluded? false, so filtering only the first binding would let any
        ;; peel silently re-admit every lane member to the calendar.
        calendar-member? (fn [row]
                           (and (not (:excluded? row))
                                (not (:off-calendar? row))))
        eligible (filterv calendar-member? prepared)
        eligible-local-ids (mapv :instrument-id eligible)
        series-by-local-id (into {}
                                 (map (fn [{:keys [instrument-id series]}]
                                        [instrument-id series]))
                                 eligible)
        ;; Lane members must not force the client-side calendar recompute below:
        ;; they are not on the calendar at all, so the backend's clean
        ;; common_calendar stays usable for everyone else.
        legacy-fallback-used? (seq (remove off-calendar-local-ids fallback-local-ids))
        ;; A poisoned response taints the price calendar too — recompute it from
        ;; the eligible members' own series instead of adopting the backend's.
        effective-calendar (if (and (seq (:common-calendar api-v2-history))
                                    (not legacy-fallback-used?)
                                    (not calendar-poisoned?))
                             (vec (:common-calendar api-v2-history))
                             (if use-aligned?
                               (vec (:return-calendar api-v2-history))
                               (if (= (set eligible-local-ids) (set (keys candidate-series-by-id)))
                                 (first @candidate-calendars)
                                 (calendar/common-calendar
                                  (map :points (vals series-by-local-id))))))
        effective-return-calendar (if use-aligned?
                                    (vec (:return-calendar api-v2-history))
                                    (if (= (set eligible-local-ids)
                                           (set (keys candidate-series-by-id)))
                                      (second @candidate-calendars)
                                      (point-level-return-calendar series-by-local-id
                                                                   effective-calendar)))
        return-series-by-instrument (if use-aligned?
                                      (into {}
                                            (map (fn [local-id]
                                                   [local-id
                                                    (get-in api-v2-history
                                                            [:aligned-returns-by-instrument
                                                             local-id
                                                             :returns])]))
                                            eligible-local-ids)
                                      (returns-from-point-level series-by-local-id
                                                                effective-return-calendar))
        common-gap? (< (count effective-return-calendar)
                       min-return-observations)
        ;; A collapsed shared calendar used to exclude EVERY member behind one
        ;; anonymous universe-level warning (live 2026-07-08: a 6-day listing
        ;; disjoint from a stale-ended series nuked a 79-asset universe, and the
        ;; UI blamed the healthy assets). Peel the poisoning members instead:
        ;; each is excluded individually with its own warning, the rest align.
        peel (when (and common-gap? (> (count eligible) 1))
               (calendar/peel-poisoning-members
                (points-by-local-id series-by-local-id)
                min-return-observations))
        peel-warning-by-id (into {}
                                 (map (fn [{:keys [instrument-id observations]}]
                                        [instrument-id
                                         {:code :insufficient-common-history
                                          :instrument-id instrument-id
                                          :observations observations
                                          :required min-return-observations}]))
                                 (:peeled peel))
        prepared (if peel
                   (mapv (fn [row]
                           (if-let [warning (get peel-warning-by-id
                                                 (:instrument-id row))]
                             (assoc row :excluded? true :warning warning)
                             row))
                         prepared)
                   prepared)
        eligible (if peel
                   (filterv calendar-member? prepared)
                   eligible)
        eligible-local-ids (if peel (mapv :instrument-id eligible) eligible-local-ids)
        series-by-local-id (if peel
                             (into {} (map (juxt :instrument-id :series)) eligible)
                             series-by-local-id)
        use-aligned? (if peel false use-aligned?)
        effective-calendar (if peel
                             (calendar/common-calendar
                              (map :points (vals series-by-local-id)))
                             effective-calendar)
        effective-return-calendar (if peel
                                    (point-level-return-calendar series-by-local-id
                                                                 effective-calendar)
                                    effective-return-calendar)
        return-series-by-instrument (if peel
                                      (returns-from-point-level series-by-local-id
                                                                effective-return-calendar)
                                      return-series-by-instrument)
        common-gap? (if peel
                      (< (count effective-return-calendar) min-return-observations)
                      common-gap?)
        history-warning (when (and (seq eligible)
                                   common-gap?)
                          {:code :insufficient-common-history
                           :observations (count effective-return-calendar)
                           :required min-return-observations})
        ;; calendar-eligible = shared-calendar members (feed the calendar-sampled
        ;; maps). effective-eligible = everything that stays in the run, lane
        ;; members included (feeds the NATIVE maps the mixed-frequency estimator
        ;; reads). A collapsed calendar still drops the calendar members, but the
        ;; lane does not depend on the calendar and survives it.
        calendar-eligible (if common-gap? [] eligible)
        off-calendar-rows (filterv :off-calendar? prepared)
        lane-series-by-local-id (into {}
                                      (map (juxt :instrument-id :series))
                                      off-calendar-rows)
        effective-eligible (into calendar-eligible off-calendar-rows)
        excluded-instruments (vec (concat (map :instrument (filter :excluded? prepared))
                                          (when common-gap?
                                            (map :instrument eligible))))
        legacy-fallback-warnings (keep (fn [{:keys [instrument-id
                                                    legacy-fallback?]}]
                                         (when legacy-fallback?
                                           (legacy-fallback/warning instrument-id)))
                                       rows)
        warnings (codec/distinct-warnings
                  (concat (remove (fn [warning]
                                    (and (display-data-warning? warning)
                                         (or use-aligned?
                                             (some #(= (warning-local-id
                                                       id-map
                                                       warning)
                                                       (:instrument-id %))
                                                   prepared))))
                                  api-warnings)
                          legacy-fallback-warnings
                          (keep :warning prepared)
                          ;; Funding disclosure covers lane members too - it is
                          ;; perp-gated and reads only the member's own series, so
                          ;; it is correct off the calendar. plausibility-warnings
                          ;; deliberately stays calendar-only: its copy states the
                          ;; bar "was discarded before estimating risk", which is
                          ;; true of calendar/point-return-map but NOT of the lane,
                          ;; where pair-estimate reads the raw close.
                          (keep (fn [{:keys [instrument series]}]
                                  (funding-warning instrument series))
                                (into eligible off-calendar-rows))
                          (calendar/plausibility-warnings eligible)
                          (when history-warning [history-warning])))
        ;; Calendar-sampled maps stay CALENDAR-ONLY: sampling a lane member on a
        ;; calendar it never joined would hand the solver a vector of nils, and
        ;; letting it into source-series would let history-window name it as the
        ;; window-limiting instrument.
        price-series-by-instrument (into {}
                                         (keep (fn [{:keys [instrument-id series]}]
                                                 (when (seq (:points series))
                                                   [instrument-id
                                                    (prices-for-calendar (:points series)
                                                                         effective-calendar)])))
                                         calendar-eligible)
        return-intervals (calendar/return-intervals-for-calendar effective-calendar
                                                                  effective-return-calendar)
        source-series-by-instrument (into {}
                                          (map (fn [{:keys [instrument-id series]}]
                                                 [instrument-id (:points series)]))
                                          calendar-eligible)
        history-window (history-window/history-window
                        {:calendar effective-calendar
                         :return-calendar effective-return-calendar
                         :return-intervals return-intervals
                         :source-series-by-instrument source-series-by-instrument})
        ;; THE load-bearing line: native metadata over calendar members AND lane
        ;; members, so a lane member gets :raw-price-series, :cadence and the
        ;; expected-return maps the mixed-frequency estimator reads.
        native-history (history-series/native-history-metadata-for-series effective-eligible)
        ;; The lane's own cadence carries :off-calendar? so domain.constraints can
        ;; cap it - sparse-safety-max-weight's ladder returns nil above 59
        ;; intervals, which would leave a deep lane member uncapped.
        cadence-by-instrument (reduce (fn [acc local-id]
                                        (cond-> acc
                                          (contains? acc local-id)
                                          (assoc-in [local-id :off-calendar?] true)))
                                      (:cadence-by-instrument native-history)
                                      off-calendar-local-ids)
        funding-by-instrument (into {}
                                    (map (fn [instrument]
                                           (let [local-id (instruments/normalize-instrument-id
                                                           instrument)
                                                 series (or (get series-by-local-id local-id)
                                                            (get lane-series-by-local-id
                                                                 local-id))]
                                             [local-id (funding-summary instrument series)])))
                                    (or universe []))]
    (cond-> {:calendar effective-calendar
             :return-calendar effective-return-calendar
             :eligible-instruments (mapv :instrument effective-eligible)
             :excluded-instruments excluded-instruments
             :price-series-by-instrument price-series-by-instrument
             :return-series-by-instrument (select-keys return-series-by-instrument
                                                       (map :instrument-id calendar-eligible))
             :return-intervals return-intervals
             :history-window history-window
             :raw-price-series-by-instrument (:raw-price-series-by-instrument native-history)
             :served-observations-by-instrument served-observations-by-instrument
             :cadence-by-instrument cadence-by-instrument
             :expected-return-series-by-instrument (:expected-return-series-by-instrument native-history)
             :expected-return-intervals-by-instrument (:expected-return-intervals-by-instrument native-history)
             :risk-estimation (:risk-estimation native-history)
             :funding-by-instrument funding-by-instrument
             :warnings warnings
             :freshness (calendar/freshness effective-calendar as-of-ms stale-after-ms)
             :alignment-source {:kind (if use-aligned? :api-v2-aligned-returns :api-v2-point-returns)
                                :status (:status api-v2-history)
                                :dataset-version (:dataset-version api-v2-history)
                                :observations (count effective-calendar)}}
      ;; Emitted ONLY when the lane is populated. `optimizer-input-signature`
      ;; hashes the whole :history map minus :freshness, so emitting these
      ;; unconditionally would change every existing scenario's signature and
      ;; mass-invalidate cached results.
      (seq off-calendar-rows)
      (assoc :off-calendar-instrument-ids (mapv :instrument-id off-calendar-rows)
             ;; request-builder re-stamps :freshness from the shared calendar
             ;; after alignment returns; an all-lane universe has none, and
             ;; calendar/freshness reports stale? true for an empty calendar.
             :freshness-calendar (if (seq effective-calendar)
                                   effective-calendar
                                   (->> (vals lane-series-by-local-id)
                                        (mapcat :points)
                                        (keep :time-ms)
                                        distinct
                                        sort
                                        vec))))))
