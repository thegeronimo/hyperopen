(ns hyperopen.portfolio.optimizer.actions
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.routes :as portfolio-routes]))

(def ^:private objective-models
  {:minimum-variance {:kind :minimum-variance}
   :max-sharpe {:kind :max-sharpe}
   :target-volatility {:kind :target-volatility
                       :target-volatility 0.2}
   :target-return {:kind :target-return
                   :target-return 0.15}})

(def ^:private return-models
  {:historical-mean {:kind :historical-mean}
   :ew-mean {:kind :ew-mean
             :alpha 0.25}
   :black-litterman {:kind :black-litterman
                     :views []}})

(def ^:private risk-models
  {:ledoit-wolf {:kind :ledoit-wolf}
   :sample-covariance {:kind :sample-covariance}})

(def ^:private numeric-constraint-keys
  #{:max-asset-weight
    :gross-max
    :net-min
    :net-max
    :dust-usdc
    :max-turnover
    :rebalance-tolerance})

(def ^:private boolean-constraint-keys
  #{:long-only?})

(def ^:private numeric-objective-parameter-keys
  #{:target-return
    :target-volatility})

(def ^:private numeric-execution-assumption-keys
  #{:fallback-slippage-bps})

(def ^:private keyword-execution-assumption-keys
  #{:default-order-type
    :fee-mode})

(def ^:private instrument-filter-keys
  #{:allowlist
    :blocklist})

(def ^:private numeric-asset-override-keys
  #{:max-weight
    :perp-max-weight})

(def ^:private boolean-asset-override-keys
  #{:held-lock?})

(defn- normalize-keyword-like
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) (str/trim value)
               :else nil)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          (str/replace #"[_\s]+" "-")
          str/lower-case
          keyword))))

(defn- save-draft-path-values
  [path-values]
  [[:effects/save-many
    (conj (vec path-values)
          [[:portfolio :optimizer :draft :metadata :dirty?] true])]])

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- exposure->universe-instrument
  [exposure]
  (let [instrument-id (non-blank-text (:instrument-id exposure))
        coin (non-blank-text (:coin exposure))
        market-type (:market-type exposure)]
    (when (and instrument-id
               coin
               (keyword? market-type))
      (cond-> {:instrument-id instrument-id
               :market-type market-type
               :coin coin
               :shortable? (= :perp market-type)}
        (non-blank-text (:dex exposure))
        (assoc :dex (non-blank-text (:dex exposure)))))))

(defn- dedupe-instruments
  [instruments]
  (:items
   (reduce (fn [{:keys [seen] :as acc} instrument]
             (let [instrument-id (:instrument-id instrument)]
               (if (contains? seen instrument-id)
                 acc
                 (-> acc
                     (update :seen conj instrument-id)
                     (update :items conj instrument)))))
           {:seen #{}
            :items []}
           instruments)))

(defn- parse-number-value
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        (let [parsed (js/Number text)]
          (when (and (number? parsed)
                     (js/isFinite parsed))
            parsed))))

    :else nil))

(defn- parse-boolean-value
  [value]
  (cond
    (boolean? value) value
    (string? value) (case (str/lower-case (str/trim value))
                      "true" true
                      "false" false
                      nil)
    :else nil))

(defn- constraint-list
  [state constraint-key]
  (vec (or (get-in state [:portfolio :optimizer :draft :constraints constraint-key])
           [])))

(defn- instrument-market-type
  [state instrument-id]
  (some (fn [instrument]
          (when (= instrument-id (:instrument-id instrument))
            (:market-type instrument)))
        (get-in state [:portfolio :optimizer :draft :universe])))

(defn- set-membership
  [items item enabled?]
  (let [items* (vec (remove #(= item %) items))]
    (if enabled?
      (conj items* item)
      items*)))

(defn- set-draft-model
  [path models value]
  (if-let [model (get models (normalize-keyword-like value))]
    (save-draft-path-values [[path model]])
    []))

(defn set-portfolio-optimizer-objective-kind
  [_state kind]
  (set-draft-model [:portfolio :optimizer :draft :objective]
                   objective-models
                   kind))

(defn set-portfolio-optimizer-return-model-kind
  [_state kind]
  (set-draft-model [:portfolio :optimizer :draft :return-model]
                   return-models
                   kind))

(defn set-portfolio-optimizer-risk-model-kind
  [_state kind]
  (set-draft-model [:portfolio :optimizer :draft :risk-model]
                   risk-models
                   kind))

(defn set-portfolio-optimizer-constraint
  [_state constraint-key value]
  (let [constraint-key* (normalize-keyword-like constraint-key)
        value* (cond
                 (contains? numeric-constraint-keys constraint-key*)
                 (parse-number-value value)

                 (contains? boolean-constraint-keys constraint-key*)
                 (parse-boolean-value value)

                 :else nil)]
    (if (some? value*)
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints constraint-key*] value*]])
      [])))

(defn set-portfolio-optimizer-objective-parameter
  [_state parameter-key value]
  (let [parameter-key* (normalize-keyword-like parameter-key)
        value* (when (contains? numeric-objective-parameter-keys parameter-key*)
                 (parse-number-value value))]
    (if (some? value*)
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :objective parameter-key*] value*]])
      [])))

(defn set-portfolio-optimizer-execution-assumption
  [_state assumption-key value]
  (let [assumption-key* (normalize-keyword-like assumption-key)
        value* (cond
                 (contains? numeric-execution-assumption-keys assumption-key*)
                 (parse-number-value value)

                 (contains? keyword-execution-assumption-keys assumption-key*)
                 (normalize-keyword-like value)

                 :else nil)]
    (if (some? value*)
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :execution-assumptions assumption-key*] value*]])
      [])))

(defn set-portfolio-optimizer-instrument-filter
  [state filter-key instrument-id enabled?]
  (let [filter-key* (normalize-keyword-like filter-key)
        instrument-id* (non-blank-text instrument-id)
        enabled?* (parse-boolean-value enabled?)]
    (if (and (contains? instrument-filter-keys filter-key*)
             instrument-id*
             (some? enabled?*))
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints filter-key*]
         (set-membership (constraint-list state filter-key*) instrument-id* enabled?*)]])
      [])))

(defn set-portfolio-optimizer-asset-override
  [state override-key instrument-id value]
  (let [override-key* (normalize-keyword-like override-key)
        instrument-id* (non-blank-text instrument-id)
        numeric-value (when (contains? numeric-asset-override-keys override-key*)
                        (parse-number-value value))
        boolean-value (when (contains? boolean-asset-override-keys override-key*)
                        (parse-boolean-value value))]
    (cond
      (and instrument-id*
           (= :max-weight override-key*)
           (some? numeric-value))
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints :asset-overrides instrument-id* :max-weight]
         numeric-value]])

      (and instrument-id*
           (= :perp-max-weight override-key*)
           (= :perp (instrument-market-type state instrument-id*))
           (some? numeric-value))
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints :perp-leverage instrument-id* :max-weight]
         numeric-value]])

      (and instrument-id*
           (= :held-lock? override-key*)
           (some? boolean-value))
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints :held-locks]
         (set-membership (constraint-list state :held-locks) instrument-id* boolean-value)]])

      :else [])))

(defn set-portfolio-optimizer-universe-from-current
  [state]
  (let [snapshot (current-portfolio/current-portfolio-snapshot state)
        universe (->> (:exposures snapshot)
                      (keep exposure->universe-instrument)
                      dedupe-instruments)]
    (if (seq universe)
      (save-draft-path-values
       [[[:portfolio :optimizer :draft :universe] universe]])
      [])))

(defn load-portfolio-optimizer-history-from-draft
  [state]
  (if (seq (get-in state [:portfolio :optimizer :draft :universe]))
    [[:effects/load-portfolio-optimizer-history]]
    []))

(defn- build-request-signature
  [request]
  {:scenario-id (:scenario-id request)
   :as-of-ms (:as-of-ms request)
   :request request})

(defn run-portfolio-optimizer-from-draft
  [state]
  (let [{:keys [request runnable?]} (setup-readiness/build-readiness state)]
    (if runnable?
      [[:effects/run-portfolio-optimizer request (build-request-signature request)]]
      [])))

(defn save-portfolio-optimizer-scenario-from-current
  [state]
  (if (= :solved
         (get-in state [:portfolio :optimizer :last-successful-run :result :status]))
    [[:effects/save-portfolio-optimizer-scenario]]
    []))

(defn load-portfolio-optimizer-route
  [_state path]
  (let [route (portfolio-routes/parse-portfolio-route path)]
    (case (:kind route)
      :optimize-index [[:effects/load-portfolio-optimizer-scenario-index]]
      :optimize-scenario [[:effects/load-portfolio-optimizer-scenario
                           (:scenario-id route)]]
      [])))

(defn run-portfolio-optimizer
  [_state request request-signature]
  [[:effects/run-portfolio-optimizer request request-signature]])
