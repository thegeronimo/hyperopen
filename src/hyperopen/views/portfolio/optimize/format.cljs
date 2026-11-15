(ns hyperopen.views.portfolio.optimize.format
  (:require [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def finite-number? coercion/finite-number?)

(defn- locale-number
  [value options]
  (.toLocaleString value "en-US" (clj->js options)))

(defn format-pct
  ([value]
   (format-pct value nil))
  ([value {:keys [minimum-fraction-digits maximum-fraction-digits]
           :or {minimum-fraction-digits 2
                maximum-fraction-digits 2}}]
   (if (finite-number? value)
     (str (locale-number (* 100 value)
                         {:minimumFractionDigits minimum-fraction-digits
                          :maximumFractionDigits maximum-fraction-digits})
          "%")
     "N/A")))

(defn format-pct-delta
  ([value]
   (format-pct-delta value nil))
  ([value {:keys [minimum-fraction-digits maximum-fraction-digits suffix]
           :or {minimum-fraction-digits 2
                maximum-fraction-digits 2
                suffix " pts"}}]
   (if (finite-number? value)
     (let [pct (* 100 value)
           label (locale-number pct
                                {:minimumFractionDigits minimum-fraction-digits
                                 :maximumFractionDigits maximum-fraction-digits})]
       (str (when (pos? pct) "+") label suffix))
     "N/A")))

(defn format-multiple
  ([value]
   (format-multiple value nil))
  ([value {:keys [minimum-fraction-digits maximum-fraction-digits]
           :or {minimum-fraction-digits 2
                maximum-fraction-digits 2}}]
   (if (finite-number? value)
     (str (locale-number value
                         {:minimumFractionDigits minimum-fraction-digits
                          :maximumFractionDigits maximum-fraction-digits})
          "x")
     "N/A")))

(defn format-decimal
  ([value]
   (format-decimal value nil))
  ([value {:keys [maximum-fraction-digits]
           :or {maximum-fraction-digits 3}}]
   (if (finite-number? value)
     (locale-number value {:maximumFractionDigits maximum-fraction-digits})
     "N/A")))

(defn format-effective-n
  [value universe-size]
  (if (finite-number? value)
    (locale-number (if (pos? universe-size)
                     (min value universe-size)
                     value)
                   {:maximumFractionDigits 1})
    "N/A"))

(defn format-usdc
  ([value]
   (format-usdc value nil))
  ([value {:keys [maximum-fraction-digits]
           :or {maximum-fraction-digits 2}}]
   (if (finite-number? value)
     (str "$" (locale-number value
                             {:maximumFractionDigits maximum-fraction-digits}))
     "N/A")))

(def ^:private friendly-keyword-labels
  {:below-min-notional "Below $10 minimum"
   :quantity-below-lot "Below one lot"
   :already-filled "Already filled"
   :already-resting "Already resting"
   :excluded-from-optimization "Excluded from optimization — held"
   :solver-fallback-used "Backup solver used"})

(defn keyword-label
  ([value]
   (keyword-label value "N/A"))
  ([value fallback]
   (cond
     (keyword? value) (get friendly-keyword-labels value (name value))
     (some? value) (str value)
     :else fallback)))

(def ^:private display-labels
  {:minimum-variance "Minimum risk"
   :max-sharpe "Maximum Sharpe"
   :equal-risk "Equal Risk"
   :inverse-volatility "Risk-weighted sizing"
   :target-volatility "Target volatility"
   :target-return "Target return"
   :historical-mean "Historical mean"
   :ew-mean "EW mean"
   :black-litterman "Black-Litterman"
   :diagonal-shrink "Stabilized covariance"
   :ledoit-wolf-dense "Ledoit-Wolf covariance"
   :mixed-frequency "Mixed-frequency covariance"
   :sample-covariance "Sample covariance"})

(defn- display-label-key
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn display-label
  [value]
  (get display-labels
       (display-label-key value)
       (keyword-label value)))

(defn format-time
  [ms]
  (if (number? ms)
    (.toLocaleString (js/Date. ms)
                     "en-US"
                     #js {:month "short"
                          :day "numeric"
                          :hour "2-digit"
                          :minute "2-digit"})
    "N/A"))

(defn format-duration
  "Compact runtime label, e.g. 3800 -> \"3.8s\"."
  [ms]
  (if (and (number? ms) (>= ms 0))
    (str (locale-number (/ ms 1000) {:maximumFractionDigits 1}) "s")
    "—"))

(defn refinement-quality-label
  [quality]
  (case quality
    :high "High"
    :medium "Medium"
    :low "Low"
    "—"))

(defn refinement-stability-label
  [stability]
  (case stability
    :stable "Stable"
    :moderate "Moderate"
    :provisional "Provisional"
    "—"))

(defn refinement-tier-ready-label
  [tier]
  (case tier
    :draft "Draft result ready"
    :refined "Refined result ready"
    :maximum "Refined to maximum density"
    :partial "Partial result ready"
    "Result ready"))

(defn refinement-stop-reason-label
  [stop-reason]
  (case stop-reason
    :draft-budget-reached "Draft budget reached"
    :refined-density-reached "Refined density reached"
    :maximum-density-reached "Maximum density reached"
    :frontier-sweep-incomplete "Frontier sweep incomplete"
    "—"))
