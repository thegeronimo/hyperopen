(ns hyperopen.portfolio.optimizer.contracts.specs
  (:require [clojure.spec.alpha :as s]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts.constants :as constants]
            [hyperopen.portfolio.optimizer.contracts.migrations :as migrations]
            [hyperopen.portfolio.optimizer.contracts.result-sections :as result-sections]
            [hyperopen.portfolio.optimizer.contracts.signatures :as signatures]))

(defn- contains-keys?
  [value ks]
  (and (map? value)
       (every? #(contains? value %) ks)))

(defn- optional?
  [pred value]
  (or (nil? value)
      (pred value)))

(defn- non-blank-string?
  [value]
  (and (string? value)
       (coercion/non-blank-text value)))

(defn- finite-number-or-nil?
  [value]
  (or (nil? value)
      (coercion/finite-number? value)))

(defn- vector-of?
  [pred value]
  (and (vector? value)
       (every? pred value)))

(defn- finite-number-vector?
  [value]
  (vector-of? coercion/finite-number? value))

(defn- allowed-keyword?
  [allowed value]
  (contains? allowed value))

(def draft-statuses constants/draft-statuses)

(def scenario-record-statuses constants/scenario-record-statuses)

(def tracking-snapshot-statuses constants/tracking-snapshot-statuses)

(def result-payload-statuses constants/result-payload-statuses)

(def objective-kinds constants/objective-kinds)

(def return-model-kinds constants/return-model-kinds)

(def risk-model-kinds constants/risk-model-kinds)

(def black-litterman-view-kinds
  #{:absolute :relative})

(def black-litterman-directions
  #{:outperform :underperform})

(def order-types
  #{:market :limit})

(def fee-modes
  #{:taker :maker})

(def history-assumption-behaviors constants/history-assumption-behaviors)

(def history-assumption-relationship-strengths
  #{:low :medium :high})

(defn- absent-or-allowed?
  [allowed value]
  (or (nil? value)
      (contains? allowed value)))

(defn- valid-instrument-map?
  [instrument-ids value]
  (and (map? value)
       (every? #(contains? value %) instrument-ids)))

(defn- instrument-map-with-values?
  [instrument-ids pred value]
  (and (valid-instrument-map? instrument-ids value)
       (every? (fn [[instrument-id nested-value]]
                 (and (non-blank-string? instrument-id)
                      (pred nested-value)))
               value)))

(defn- instrument?
  [value]
  (and (map? value)
       (non-blank-string? (:instrument-id value))
       (optional? #(contains? #{:perp :spot :vault}
                              (coercion/normalize-keyword-like %))
                  (:market-type value))))

(defn- instrument-vector?
  [value]
  (vector-of? instrument? value))

(defn- id-vector?
  [value]
  (vector-of? non-blank-string? value))

(defn- map-or-nil?
  [value]
  (or (nil? value)
      (map? value)))

(defn- finite-field?
  [value field]
  (finite-number-or-nil? (get value field)))

(defn- boolean-field?
  [value field]
  (optional? boolean? (get value field)))

(defn- id-vector-field?
  [value field]
  (optional? id-vector? (get value field)))

(defn- map-field?
  [value field]
  (map-or-nil? (get value field)))

(defn- objective?
  [value]
  (and (map? value)
       (allowed-keyword? objective-kinds (:kind value))
       (case (:kind value)
         :target-return (coercion/finite-number? (:target-return value))
         :target-volatility (coercion/finite-number? (:target-volatility value))
         true)))

(defn- black-litterman-view?
  [value]
  (and (map? value)
       (allowed-keyword? black-litterman-view-kinds (:kind value))
       (coercion/finite-number? (:return value))
       (finite-field? value :confidence)
       (finite-field? value :confidence-variance)
       (optional? #(allowed-keyword? black-litterman-directions %)
                  (:direction value))
       (map-field? value :weights)
       (case (:kind value)
         :absolute
         (or (non-blank-string? (:instrument-id value))
             (seq (:weights value)))

         :relative
         (or (and (non-blank-string? (:instrument-id value))
                  (non-blank-string? (:comparator-instrument-id value))
                  (not= (:instrument-id value)
                        (:comparator-instrument-id value)))
             (and (non-blank-string? (:long-instrument-id value))
                  (non-blank-string? (:short-instrument-id value))
                  (not= (:long-instrument-id value)
                        (:short-instrument-id value)))
             (seq (:weights value))))))

(defn- return-model?
  [value]
  (and (map? value)
       (allowed-keyword? return-model-kinds (:kind value))
       (finite-field? value :alpha)
       (if (= :black-litterman (:kind value))
         (optional? #(vector-of? black-litterman-view? %) (:views value))
         (optional? vector? (:views value)))))

(defn- risk-model?
  [value]
  (and (map? value)
       (allowed-keyword? risk-model-kinds (:kind value))
       (finite-field? value :shrinkage)))

(defn- constraints?
  [value]
  (and (map? value)
       (boolean-field? value :long-only?)
       (every? #(finite-field? value %)
               [:gross-min
                :gross-max
                :gross-leverage
                :net-min
                :net-max
                :net-band-pct
                :max-weight
                :max-long-weight
                :max-short-weight
                :max-asset-weight
                :dust-usdc
                :dust-threshold
                :max-turnover
                :rebalance-tolerance])
       (optional? #(and (map? %)
                        (finite-field? % :min)
                        (finite-field? % :max))
                  (:net-exposure value))
       (id-vector-field? value :allowlist)
       (id-vector-field? value :blocklist)
       (id-vector-field? value :held-locks)
       (id-vector-field? value :held-position-locks)
       (every? #(map-field? value %)
               [:asset-overrides
                :per-asset-overrides
                :perp-leverage
                :per-perp-leverage-caps])))

(defn- execution-assumptions?
  [value]
  (and (map? value)
       (optional? #(allowed-keyword? order-types %) (:default-order-type value))
       (optional? #(allowed-keyword? fee-modes %) (:fee-mode value))
       (every? #(finite-field? value %)
               [:fallback-slippage-bps
                :slippage-fallback-bps
                :default-fee-bps
                :manual-capital-usdc])
       (every? #(map-field? value %)
               [:prices-by-id
                :fee-bps-by-id
                :cost-contexts-by-id])))

(defn- history-assumption-proxy-config?
  [value]
  (and (map? value)
       (id-vector? (:instrument-ids value))
       (optional? #(contains? history-assumption-relationship-strengths %)
                  (:relationship-strength value))
       (map-or-nil? (:prior-weights value))))

(defn- history-assumption-entry?
  [value]
  (and (map? value)
       (allowed-keyword? history-assumption-behaviors (:behavior value))
       (finite-field? value :expected-return)
       (finite-field? value :volatility)
       (finite-field? value :max-weight)
       (case (:behavior value)
         :conservative (coercion/finite-number? (:correlation-floor value))
         :proxy (history-assumption-proxy-config? (:proxy value))
         false)))

(defn- history-assumptions?
  [value]
  (or (nil? value)
      (and (map? value)
           (every? (fn [[instrument-id entry]]
                     (and (non-blank-string? instrument-id)
                          (history-assumption-entry? entry)))
                   value))))

(defn- draft-metadata?
  [value]
  (and (map? value)
       (finite-field? value :created-at-ms)
       (finite-field? value :updated-at-ms)
       (boolean-field? value :dirty?)
       (optional? map? (:universe-source value))))

(defn- current-portfolio?
  [value]
  (and (map? value)
       (map-field? value :capital)
       (map-field? value :by-instrument)))

(defn- warning-vector?
  [value]
  (vector-of? map? value))

(defn- freshness?
  [value]
  (and (map? value)
       (finite-field? value :as-of-ms)
       (finite-field? value :latest-common-ms)
       (finite-field? value :oldest-common-ms)
       (finite-field? value :age-ms)
       (boolean? (:stale? value))))

(defn- instrument-keyed-map?
  [value]
  (and (map? value)
       (every? (fn [[instrument-id _]]
                 (non-blank-string? instrument-id))
               value)))

(defn- engine-history?
  [value]
  (and (map? value)
       (contains-keys? value [:calendar
                              :return-calendar
                              :eligible-instruments
                              :excluded-instruments
                              :price-series-by-instrument
                              :return-series-by-instrument
                              :return-intervals
                              :funding-by-instrument
                              :warnings
                              :freshness])
       (vector? (:calendar value))
       (vector? (:return-calendar value))
       (instrument-vector? (:eligible-instruments value))
       (instrument-vector? (:excluded-instruments value))
       (instrument-keyed-map? (:price-series-by-instrument value))
       (instrument-keyed-map? (:return-series-by-instrument value))
       (vector? (:return-intervals value))
       (instrument-keyed-map? (:funding-by-instrument value))
       (warning-vector? (:warnings value))
       (freshness? (:freshness value))))

(defn- solved-result-payload?
  [value]
  (let [instrument-ids (:instrument-ids value)
        target-weights (:target-weights value)
        current-weights (:current-weights value)
        instrument-count (count instrument-ids)]
    (and (vector? instrument-ids)
         (every? non-blank-string? instrument-ids)
         (finite-number-vector? target-weights)
         (finite-number-vector? current-weights)
         (= instrument-count (count target-weights))
         (= instrument-count (count current-weights))
         (instrument-map-with-values? instrument-ids
                                      coercion/finite-number?
                                      (:target-weights-by-instrument value))
         (instrument-map-with-values? instrument-ids
                                      coercion/finite-number?
                                      (:current-weights-by-instrument value))
         (instrument-map-with-values? instrument-ids
                                      coercion/finite-number?
                                      (:expected-returns-by-instrument value))
         (finite-field? value :as-of-ms)
         (optional? warning-vector? (:warnings value))
         (optional? vector? (:dropped-weights value))
         (optional? vector? (:solver-results value))
         (optional? vector? (:frontier value))
         (optional? map? (:frontiers value))
         (optional? map? (:frontier-summary value))
         (optional? map? (:frontier-summaries value))
         (optional? map? (:frontier-overlays value))
         (optional? map? (:current-performance value))
         (optional? map? (:performance value))
         (or (nil? (:return-decomposition-by-instrument value))
             (map? (:return-decomposition-by-instrument value)))
         (or (nil? (:diagnostics value))
             (map? (:diagnostics value)))
         (or (nil? (:risk-contributions value))
             (let [contributions (:risk-contributions value)]
               (and (map? contributions)
                    (keyword? (:quality contributions))
                    (finite-number-vector? (:relative-contributions contributions))
                    (finite-number-vector? (:target-relative-contributions contributions))
                    (instrument-keyed-map?
                     (:relative-contributions-by-instrument contributions))
                    (instrument-keyed-map?
                     (:target-relative-contributions-by-instrument contributions)))))
         (optional? map? (:current-risk-contributions value))
         (optional? map? (:equal-risk-solver value))
         (or (nil? (:inverse-volatility value))
             (let [sizing (:inverse-volatility value)]
               (and (map? sizing)
                    (vector? (:sizing-rows sizing))
                    (every? map? (:sizing-rows sizing))
                    (finite-number-vector? (:seed-weights sizing))
                    (coercion/finite-number? (:max-sizing-deviation sizing)))))
         (or (nil? (:risk-structure value))
             (let [structure (:risk-structure value)
                   correlation (:correlation structure)
                   correlation-ids (:instrument-ids correlation)]
               (and (map? structure)
                    (finite-field? structure :portfolio-volatility)
                    (result-sections/diversification-summaries? structure)
                    (instrument-keyed-map?
                     (:standalone-share-by-instrument structure))
                    (instrument-keyed-map?
                     (:diversification-share-by-instrument structure))
                    (instrument-keyed-map?
                     (:pnl-portfolio-correlation-by-instrument structure))
                    (map? correlation)
                    (vector? correlation-ids)
                    (every? non-blank-string? correlation-ids)
                    (vector? (:matrix correlation))
                    (= (count correlation-ids) (count (:matrix correlation)))
                    (every? #(and (vector? %)
                                  (= (count correlation-ids) (count %)))
                            (:matrix correlation)))))
         (or (nil? (:rebalance-preview value))
             (and (map? (:rebalance-preview value))
                  (optional? vector? (get-in value [:rebalance-preview :rows]))
                  (optional? vector? (get-in value [:rebalance-preview :trades]))
                  (optional? map? (get-in value [:rebalance-preview :summary])))))))

(s/def ::draft
  (s/and map?
         #(= migrations/draft-schema-version (:schema-version %))
	         #(contains-keys? % [:universe
	                             :objective
	                             :return-model
	                             :risk-model
	                             :constraints
	                             :execution-assumptions
	                             :history-assumptions
	                             :metadata])
	         #(absent-or-allowed? draft-statuses (:status %))
	         #(instrument-vector? (:universe %))
	         #(objective? (:objective %))
	         #(return-model? (:return-model %))
	         #(risk-model? (:risk-model %))
	         #(constraints? (:constraints %))
	         #(execution-assumptions? (:execution-assumptions %))
	         #(history-assumptions? (:history-assumptions %))
	         #(or (nil? (:proxy-reference-instruments %))
	              (instrument-vector? (:proxy-reference-instruments %)))
	         #(draft-metadata? (:metadata %))))

(s/def ::engine-request
  (s/and map?
         #(contains-keys? % [:universe
                             :requested-universe
                             :current-portfolio
                             :return-model
                             :risk-model
                             :objective
                             :constraints
                             :execution-assumptions
	                             :history])
	         #(instrument-vector? (:universe %))
	         #(instrument-vector? (:requested-universe %))
	         #(current-portfolio? (:current-portfolio %))
	         #(return-model? (:return-model %))
	         #(risk-model? (:risk-model %))
	         #(objective? (:objective %))
	         #(constraints? (:constraints %))
	         #(execution-assumptions? (:execution-assumptions %))
	         #(engine-history? (:history %))
	         #(optional? coercion/finite-number? (:as-of-ms %))
	         #(optional? warning-vector? (:warnings %))))

(s/def ::request-signature
  (s/and map?
	         #(= signatures/request-signature-schema-version (:schema-version %))
	         #(contains-keys? % [:request :input-signature])
	         #(map? (:request %))
	         #(s/valid? ::engine-request (:request %))
	         #(= (signatures/optimizer-input-signature (:request %))
	             (:input-signature %))))

(s/def ::scenario-record
  (s/and map?
         #(= migrations/scenario-record-schema-version (:schema-version %))
         #(contains-keys? % [:id :name :status :config :updated-at-ms])
         #(non-blank-string? (:id %))
         #(string? (:name %))
         #(contains? scenario-record-statuses (:status %))
         #(coercion/finite-number? (:updated-at-ms %))
         #(s/valid? ::draft (:config %))))

(s/def ::tracking-snapshot
  (s/and map?
         #(contains-keys? % [:scenario-id :as-of-ms :status])
         #(non-blank-string? (:scenario-id %))
         #(coercion/finite-number? (:as-of-ms %))
         #(contains? tracking-snapshot-statuses (:status %))
         #(if (= :tracked (:status %))
            (vector? (:rows %))
            true)
         #(if (= :not-trackable (:status %))
            (vector? (:warnings %))
            true)))

(s/def ::tracking-record
  (s/and map?
         #(= migrations/tracking-record-schema-version (:schema-version %))
         #(contains-keys? % [:scenario-id :updated-at-ms :snapshots])
         #(non-blank-string? (:scenario-id %))
         #(coercion/finite-number? (:updated-at-ms %))
         #(vector? (:snapshots %))
         #(every? (fn [snapshot]
                    (and (s/valid? ::tracking-snapshot snapshot)
                         (= (:scenario-id %) (:scenario-id snapshot))))
                  (:snapshots %))))

(s/def ::result-payload
  (s/and map?
         #(contains? % :status)
         #(contains? result-payload-statuses (:status %))
         #(if (= :solved (:status %))
            (solved-result-payload? %)
            true)))

(s/def ::worker-envelope
  (s/and map?
         #(contains-keys? % [:type :payload])))
