(ns hyperopen.schema.effect-order-contracts)

(def ^:private phase-values
  #{:projection :persistence :heavy-io :other})

(def ^:private failure-rule-values
  #{:heavy-before-projection-phase
    :duplicate-heavy-effect
    :phase-order-regression})

(def ^:private policy-projection-keys
  #{:action-id
    :required-phase-order
    :require-projection-before-heavy?
    :allow-duplicate-heavy-effects?
    :heavy-effect-ids})

(def ^:private summary-projection-keys
  #{:action-id
    :covered?
    :effect-ids
    :phases
    :projection-effect-count
    :heavy-effect-count
    :projection-before-heavy
    :duplicate-heavy-effect-ids
    :phase-order-valid})

(def ^:private policy-vector-keys
  #{:action-id :expected})

(def ^:private vector-case-keys
  #{:id :action-id :effects :expected})

(def ^:private wrapper-case-keys
  #{:id
    :action-id
    :effects
    :validation-enabled?
    :expected
    :records-debug-summary?
    :expected-summary})

(def ^:private assertion-success-keys
  #{:ok?})

(def ^:private assertion-failure-keys
  #{:ok? :rule :effect-index :effect-id})

(defn- exact-keys?
  [value expected]
  (and (map? value)
       (= expected (set (keys value)))))

(defn- vector-of?
  [predicate value]
  (and (vector? value)
       (every? predicate value)))

(defn- non-negative-integer?
  [value]
  (and (integer? value)
       (>= value 0)))

(defn- action-id?
  [value]
  (and (keyword? value)
       (= "actions" (namespace value))))

(defn- effect-id?
  [value]
  (and (keyword? value)
       (= "effects" (namespace value))))

(defn- effect-vector?
  [value]
  (and (vector? value)
       (seq value)
       (effect-id? (first value))))

(defn policy-projection
  [action-id policy]
  {:action-id action-id
   :required-phase-order (vec (or (:required-phase-order policy) []))
   :require-projection-before-heavy? (boolean (:require-projection-before-heavy? policy))
   :allow-duplicate-heavy-effects? (boolean (:allow-duplicate-heavy-effects? policy))
   :heavy-effect-ids (->> (or (:heavy-effect-ids policy) [])
                          sort
                          vec)})

(defn summary-projection
  [summary]
  {:action-id (:action-id summary)
   :covered? (boolean (:covered? summary))
   :effect-ids (vec (or (:effect-ids summary) []))
   :phases (vec (or (:phases summary) []))
   :projection-effect-count (or (:projection-effect-count summary) 0)
   :heavy-effect-count (or (:heavy-effect-count summary) 0)
   :projection-before-heavy (boolean (:projection-before-heavy summary))
   :duplicate-heavy-effect-ids (vec (or (:duplicate-heavy-effect-ids summary) []))
   :phase-order-valid (boolean (:phase-order-valid summary))})

(defn policy-projection-valid?
  [projection]
  (and (exact-keys? projection policy-projection-keys)
       (action-id? (:action-id projection))
       (vector-of? #(contains? phase-values %) (:required-phase-order projection))
       (boolean? (:require-projection-before-heavy? projection))
       (boolean? (:allow-duplicate-heavy-effects? projection))
       (vector-of? effect-id? (:heavy-effect-ids projection))))

(defn summary-projection-valid?
  [projection]
  (let [effect-ids (:effect-ids projection)
        phases (:phases projection)
        projection-count (count (filter #{:projection} phases))
        heavy-count (count (filter #{:heavy-io} phases))]
    (and (exact-keys? projection summary-projection-keys)
         (action-id? (:action-id projection))
         (boolean? (:covered? projection))
         (vector-of? effect-id? effect-ids)
         (vector-of? #(contains? phase-values %) phases)
         (= (count effect-ids) (count phases))
         (non-negative-integer? (:projection-effect-count projection))
         (= projection-count (:projection-effect-count projection))
         (non-negative-integer? (:heavy-effect-count projection))
         (= heavy-count (:heavy-effect-count projection))
         (boolean? (:projection-before-heavy projection))
         (vector-of? effect-id? (:duplicate-heavy-effect-ids projection))
         (every? (set effect-ids) (:duplicate-heavy-effect-ids projection))
         (boolean? (:phase-order-valid projection)))))

(defn assertion-outcome-valid?
  [projection]
  (or (and (exact-keys? projection assertion-success-keys)
           (true? (:ok? projection)))
      (and (exact-keys? projection assertion-failure-keys)
           (false? (:ok? projection))
           (contains? failure-rule-values (:rule projection))
           (non-negative-integer? (:effect-index projection))
           (effect-id? (:effect-id projection)))))

(defn policy-vector-valid?
  [value]
  (and (exact-keys? value policy-vector-keys)
       (action-id? (:action-id value))
       (map? (:expected value))
       (policy-projection-valid?
        (assoc (:expected value) :action-id (:action-id value)))))

(defn summary-vector-valid?
  [value]
  (and (exact-keys? value vector-case-keys)
       (keyword? (:id value))
       (action-id? (:action-id value))
       (vector-of? effect-vector? (:effects value))
       (summary-projection-valid? (:expected value))))

(defn assertion-vector-valid?
  [value]
  (and (exact-keys? value vector-case-keys)
       (keyword? (:id value))
       (action-id? (:action-id value))
       (vector-of? effect-vector? (:effects value))
       (assertion-outcome-valid? (:expected value))))

(defn wrapper-expected-valid?
  [value]
  (assertion-outcome-valid? value))

(defn wrapper-vector-valid?
  [value]
  (and (exact-keys? value wrapper-case-keys)
       (keyword? (:id value))
       (action-id? (:action-id value))
       (vector-of? effect-vector? (:effects value))
       (boolean? (:validation-enabled? value))
       (wrapper-expected-valid? (:expected value))
       (boolean? (:records-debug-summary? value))
       (summary-projection-valid? (:expected-summary value))))

(defn assert-policy-projection!
  [projection context]
  (when-not (policy-projection-valid? projection)
    (throw (js/Error.
            (str "effect-order policy projection contract validation failed. "
                 "context=" (pr-str context)
                 " projection=" (pr-str projection)))))
  projection)

(defn assert-policy-vector!
  [value context]
  (when-not (policy-vector-valid? value)
    (throw (js/Error.
            (str "effect-order policy vector contract validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str value)))))
  value)

(defn assert-summary-projection!
  [projection context]
  (when-not (summary-projection-valid? projection)
    (throw (js/Error.
            (str "effect-order summary projection contract validation failed. "
                 "context=" (pr-str context)
                 " projection=" (pr-str projection)))))
  projection)

(defn assert-assertion-outcome!
  [projection context]
  (when-not (assertion-outcome-valid? projection)
    (throw (js/Error.
            (str "effect-order assertion outcome contract validation failed. "
                 "context=" (pr-str context)
                 " projection=" (pr-str projection)))))
  projection)

(defn assert-summary-vector!
  [value context]
  (when-not (summary-vector-valid? value)
    (throw (js/Error.
            (str "effect-order summary vector contract validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str value)))))
  value)

(defn assert-assertion-vector!
  [value context]
  (when-not (assertion-vector-valid? value)
    (throw (js/Error.
            (str "effect-order assertion vector contract validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str value)))))
  value)

(defn assert-wrapper-vector!
  [value context]
  (when-not (wrapper-vector-valid? value)
    (throw (js/Error.
            (str "effect-order wrapper vector contract validation failed. "
                 "context=" (pr-str context)
                 " value=" (pr-str value)))))
  value)
