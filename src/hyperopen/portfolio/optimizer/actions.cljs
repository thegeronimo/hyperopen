(ns hyperopen.portfolio.optimizer.actions
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(def ^:private objective-models
  {:minimum-variance {:kind :minimum-variance}
   :max-sharpe {:kind :max-sharpe}
   :target-volatility {:kind :target-volatility}
   :target-return {:kind :target-return}})

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

(defn run-portfolio-optimizer
  [_state request request-signature]
  [[:effects/run-portfolio-optimizer request request-signature]])
