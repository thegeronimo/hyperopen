(ns hyperopen.portfolio.optimizer.actions.draft
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]))

(def objective-models
  {:minimum-variance {:kind :minimum-variance}
   :max-sharpe {:kind :max-sharpe}
   :target-volatility {:kind :target-volatility
                       :target-volatility 0.2}
   :target-return {:kind :target-return
                   :target-return 0.15}})

(def return-models
  {:historical-mean {:kind :historical-mean}
   :ew-mean {:kind :ew-mean
             :alpha 0.015159678336035098}
   :black-litterman {:kind :black-litterman
                     :views []}})

(def risk-models
  {:diagonal-shrink {:kind :diagonal-shrink}
   :ledoit-wolf {:kind :diagonal-shrink}
   :sample-covariance {:kind :sample-covariance}})

(def setup-presets
  {:conservative {:objective {:kind :minimum-variance}
                  :return-model {:kind :historical-mean}}
   :risk-adjusted {:objective {:kind :max-sharpe}
                   :return-model {:kind :historical-mean}}
   :use-my-views {:objective {:kind :max-sharpe}
                  :return-model {:kind :black-litterman
                                 :views []}}})

(def numeric-constraint-keys
  #{:max-asset-weight
    :gross-max
    :net-min
    :net-max
    :dust-usdc
    :max-turnover
    :rebalance-tolerance})

(def boolean-constraint-keys
  #{:long-only?})

(def numeric-objective-parameter-keys
  #{:target-return
    :target-volatility})

(def numeric-execution-assumption-keys
  #{:fallback-slippage-bps
    :manual-capital-usdc})

(def keyword-execution-assumption-keys
  #{:default-order-type
    :fee-mode})

(def instrument-filter-keys
  #{:allowlist
    :blocklist})

(def numeric-asset-override-keys
  #{:max-weight
    :perp-max-weight})

(def boolean-asset-override-keys
  #{:held-lock?})

(defn- set-draft-model
  [path models value]
  (if-let [model (get models (common/normalize-keyword-like value))]
    (common/save-draft-path-values [[path model]])
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

(defn apply-portfolio-optimizer-setup-preset
  [_state preset]
  (if-let [{:keys [objective return-model]} (get setup-presets
                                                 (common/normalize-keyword-like preset))]
    (common/save-draft-path-values
     [[[:portfolio :optimizer :draft :objective] objective]
      [[:portfolio :optimizer :draft :return-model] return-model]])
    []))

(defn set-portfolio-optimizer-constraint
  [_state constraint-key value]
  (let [constraint-key* (common/normalize-keyword-like constraint-key)
        value* (cond
                 (contains? numeric-constraint-keys constraint-key*)
                 (common/parse-number-value value)

                 (contains? boolean-constraint-keys constraint-key*)
                 (common/parse-boolean-value value)

                 :else nil)]
    (if (some? value*)
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints constraint-key*] value*]])
      [])))

(defn set-portfolio-optimizer-objective-parameter
  [_state parameter-key value]
  (let [parameter-key* (common/normalize-keyword-like parameter-key)
        value* (when (contains? numeric-objective-parameter-keys parameter-key*)
                 (common/parse-number-value value))]
    (if (some? value*)
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :objective parameter-key*] value*]])
      [])))

(defn set-portfolio-optimizer-execution-assumption
  [_state assumption-key value]
  (let [assumption-key* (common/normalize-keyword-like assumption-key)
        manual-capital-clear? (and (= :manual-capital-usdc assumption-key*)
                                   (nil? (common/non-blank-text value)))
        value* (cond
                 manual-capital-clear?
                 nil

                 (contains? numeric-execution-assumption-keys assumption-key*)
                 (common/parse-number-value value)

                 (contains? keyword-execution-assumption-keys assumption-key*)
                 (common/normalize-keyword-like value)

                 :else nil)]
    (if (or (some? value*)
            manual-capital-clear?)
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :execution-assumptions assumption-key*] value*]])
      [])))

(defn set-portfolio-optimizer-instrument-filter
  [state filter-key instrument-id enabled?]
  (let [filter-key* (common/normalize-keyword-like filter-key)
        instrument-id* (common/non-blank-text instrument-id)
        enabled?* (common/parse-boolean-value enabled?)]
    (if (and (contains? instrument-filter-keys filter-key*)
             instrument-id*
             (some? enabled?*))
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints filter-key*]
         (common/set-membership
          (common/constraint-list state filter-key*)
          instrument-id*
          enabled?*)]])
      [])))

(defn set-portfolio-optimizer-asset-override
  [state override-key instrument-id value]
  (let [override-key* (common/normalize-keyword-like override-key)
        instrument-id* (common/non-blank-text instrument-id)
        numeric-value (when (contains? numeric-asset-override-keys override-key*)
                        (common/parse-number-value value))
        boolean-value (when (contains? boolean-asset-override-keys override-key*)
                        (common/parse-boolean-value value))]
    (cond
      (and instrument-id*
           (= :max-weight override-key*)
           (some? numeric-value))
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints :asset-overrides instrument-id* :max-weight]
         numeric-value]])

      (and instrument-id*
           (= :perp-max-weight override-key*)
           (= :perp (common/instrument-market-type state instrument-id*))
           (some? numeric-value))
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints :perp-leverage instrument-id* :max-weight]
         numeric-value]])

      (and instrument-id*
           (= :held-lock? override-key*)
           (some? boolean-value))
      (common/save-draft-path-values
       [[[:portfolio :optimizer :draft :constraints :held-locks]
         (common/set-membership
          (common/constraint-list state :held-locks)
          instrument-id*
          boolean-value)]])

      :else [])))
