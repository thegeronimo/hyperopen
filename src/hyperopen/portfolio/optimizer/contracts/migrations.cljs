(ns hyperopen.portfolio.optimizer.contracts.migrations
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(def draft-schema-version 1)
(def scenario-record-schema-version 1)
(def tracking-record-schema-version 1)

(defn- keyword-like
  [value]
  (coercion/normalize-keyword-like value))

(defn- normalize-position-side
  [value]
  (case (keyword-like value)
    :short :short
    :long))

(defn- shortable-instrument?
  [instrument]
  (cond
    (contains? instrument :shortable?)
    (true? (:shortable? instrument))

    (= :perp (keyword-like (or (:market-type instrument)
                               (:instrument-type instrument))))
    true

    :else false))

(defn- migrate-universe-instrument
  [instrument]
  (if (map? instrument)
    (let [shortable? (shortable-instrument? instrument)
          side (normalize-position-side (:position-side instrument))]
      (assoc instrument
             :position-side (if (and (= :short side)
                                     shortable?)
                              :short
                              :long)))
    instrument))

(defn canonical-doubled-dex-id
  "Collapses a doubled-dex instrument id (\"perp:xyz:xyz:ORCL\") to its canonical
  form (\"perp:xyz:ORCL\"). The per-dex clearinghouse feed reports coins ALREADY
  dex-namespaced, and the holdings snapshot used to re-prefix them — ids in that
  doubled shape leaked into persisted drafts (universe entries, history-assumption
  keys) and scenario configs, where they match neither the market catalog key nor
  the live holdings id, so current-position joins silently miss. Any other value
  passes through unchanged; idempotent."
  [id]
  (if (string? id)
    (let [parts (str/split id #":")]
      (if (and (<= 4 (count parts))
               (contains? #{"perp" "spot"} (first parts))
               (= (nth parts 1) (nth parts 2)))
        (str/join ":" (into [(first parts) (second parts)] (drop 3 parts)))
        id))
    id))

(defn- canonicalize-universe-instrument-ids
  "Rewrites doubled-dex :instrument-id values on universe entries and drops the
  duplicates a rewrite may create (first entry wins — when a canonical entry
  already coexists with its doubled twin, the established one is kept)."
  [universe]
  (let [rewritten (mapv (fn [instrument]
                          (if (map? instrument)
                            (update instrument :instrument-id canonical-doubled-dex-id)
                            instrument))
                        universe)]
    (:out (reduce (fn [{:keys [seen out] :as acc} instrument]
                    (let [id (when (map? instrument) (:instrument-id instrument))]
                      (if (and (string? id) (contains? seen id))
                        acc
                        {:seen (cond-> seen (string? id) (conj id))
                         :out (conj out instrument)})))
                  {:seen #{} :out []}
                  rewritten))))

(defn- migrate-draft-universe
  [draft]
  (if (vector? (:universe draft))
    (update draft :universe
            #(canonicalize-universe-instrument-ids
              (mapv migrate-universe-instrument %)))
    draft))

(defn- migrate-draft-history-assumptions
  [draft]
  (-> draft
      (update :history-assumptions #(or % {}))
      (update :proxy-reference-instruments #(vec (or % [])))))

(defn- legacy-proxy-entry?
  [entry]
  (and (= :proxy (:behavior entry))
       (not (map? (:proxy entry)))))

(defn- migrate-history-assumption-entry
  [entry]
  (if (legacy-proxy-entry? entry)
    (-> entry
        (assoc :behavior :conservative
               :correlation-floor history-assumptions/conservative-correlation-floor)
        (update :max-weight #(if (coercion/positive-number? %)
                               %
                               history-assumptions/default-conservative-max-weight))
        (dissoc :proxy-instrument-id :relationship :implied-correlation))
    entry))

(defn- migrate-draft-history-assumption-behaviors
  [draft]
  (if (map? (:history-assumptions draft))
    (update draft :history-assumptions
            (fn [by-id]
              (reduce-kv (fn [acc id entry]
                           (assoc acc id (migrate-history-assumption-entry entry)))
                         {}
                         by-id)))
    draft))

(defn- canonicalize-id-vector
  [ids]
  (if (vector? ids)
    (vec (distinct (map canonical-doubled-dex-id ids)))
    ids))

(defn- canonicalize-id-keyed-map
  "Rewrites doubled-dex keys of an id-keyed map; when a rewrite collides with an
  existing canonical key, the entry already under the canonical key wins."
  [by-id]
  (if (map? by-id)
    (reduce-kv (fn [acc id entry]
                 (let [id* (canonical-doubled-dex-id id)]
                   (if (contains? acc id*)
                     acc
                     (assoc acc id* entry))))
               {}
               by-id)
    by-id))

(def ^:private constraints-id-vector-keys
  [:allowlist :blocklist :held-locks :held-position-locks])

(def ^:private constraints-id-map-keys
  [:asset-overrides :per-asset-overrides :perp-leverage :per-perp-leverage-caps])

(defn- migrate-draft-doubled-dex-ids
  "Persisted drafts written while the holdings snapshot doubled the dex prefix
  carry ids like \"perp:xyz:xyz:ORCL\" on every id-bearing draft surface —
  universe entries (handled in migrate-draft-universe), history-assumption keys,
  and the constraints' id vectors/maps (held-locks were seeded straight from the
  doubled holdings ids). Canonicalize them all so the draft joins live holdings
  and the market catalog again. Idempotent; no schema-version bump needed."
  [draft]
  (cond-> draft
    (map? (:history-assumptions draft))
    (update :history-assumptions canonicalize-id-keyed-map)

    (map? (:constraints draft))
    (update :constraints
            (fn [constraints]
              (as-> constraints c
                (reduce (fn [acc k]
                          (if (contains? acc k)
                            (update acc k canonicalize-id-vector)
                            acc))
                        c
                        constraints-id-vector-keys)
                (reduce (fn [acc k]
                          (if (contains? acc k)
                            (update acc k canonicalize-id-keyed-map)
                            acc))
                        c
                        constraints-id-map-keys))))))

(defn- finite-number?
  [x]
  (and (number? x) (js/isFinite x)))

(defn- clamp01
  [x]
  (-> x (max 0.0) (min 1.0)))

(defn migrate-constraints-net-band
  "The net band changed units: it used to be an ABSOLUTE leverage half-width
  persisted as a net-min/net-max spread; it is now :net-band-pct, a decimal
  fraction of REALIZED gross (5% = 0.05). A stored spread with a valid gross
  target converts via q = half-width / gross-target (so the widest permitted
  absolute deviation is preserved at the configured gross); the min/max
  collapse to their midpoint, which is the target the new band widens. Without
  a usable gross target the band resets to the 0% default rather than guessing.
  Constraints already carrying :net-band-pct only get the value re-clamped.
  Idempotent; purely additive for min = max drafts, so no schema-version bump."
  [constraints]
  (if-not (map? constraints)
    constraints
    (let [nmin (:net-min constraints)
          nmax (:net-max constraints)
          gmin (:gross-min constraints)
          gmax (:gross-max constraints)
          gross-target (cond
                         (and (finite-number? gmin) (finite-number? gmax))
                         (/ (+ gmin gmax) 2)

                         (finite-number? gmax) gmax
                         (finite-number? gmin) gmin
                         :else nil)]
      (cond
        (contains? constraints :net-band-pct)
        (update constraints :net-band-pct
                #(if (finite-number? %) (clamp01 %) 0.0))

        (and (finite-number? nmin) (finite-number? nmax) (> nmax nmin))
        (let [half (/ (- nmax nmin) 2)
              midpoint (/ (+ nmin nmax) 2)
              pct (if (and (finite-number? gross-target) (pos? gross-target))
                    (clamp01 (/ half gross-target))
                    0.0)]
          (assoc constraints
                 :net-min midpoint
                 :net-max midpoint
                 :net-band-pct (/ (js/Math.round (* pct 1000000)) 1000000)))

        :else
        (assoc constraints :net-band-pct 0.0)))))

(defn- migrate-draft-net-band
  [draft]
  (if (map? (:constraints draft))
    (update draft :constraints migrate-constraints-net-band)
    draft))

(defn migrate-draft
  [draft]
  (let [draft* (or draft {})
        version (or (:schema-version draft*) draft-schema-version)]
    (case version
      1 (-> draft*
            migrate-draft-universe
            migrate-draft-history-assumptions
            migrate-draft-history-assumption-behaviors
            migrate-draft-doubled-dex-ids
            migrate-draft-net-band
            (assoc :schema-version draft-schema-version))
      (throw (ex-info "Unsupported optimizer draft schema version."
                      {:contract :optimizer/draft
                       :schema-version version})))))

(defn migrate-scenario-record
  [scenario-record]
  (let [record* (or scenario-record {})
        version (or (:schema-version record*) scenario-record-schema-version)]
    (case version
      1 (cond-> (assoc record* :schema-version scenario-record-schema-version)
          (map? (:config record*))
          (update :config migrate-draft))
      (throw (ex-info "Unsupported optimizer scenario record schema version."
                      {:contract :optimizer/scenario-record
                       :schema-version version})))))

(defn migrate-tracking-record
  [tracking-record]
  (let [record* (or tracking-record {})
        version (or (:schema-version record*) tracking-record-schema-version)]
    (case version
      1 (-> record*
            (assoc :schema-version tracking-record-schema-version)
            (update :snapshots #(vec (or % []))))
      (throw (ex-info "Unsupported optimizer tracking record schema version."
                      {:contract :optimizer/tracking-record
                       :schema-version version})))))
