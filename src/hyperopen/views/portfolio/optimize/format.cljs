(ns hyperopen.views.portfolio.optimize.format)

(defn finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

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

(defn keyword-label
  ([value]
   (keyword-label value "N/A"))
  ([value fallback]
   (cond
     (keyword? value) (name value)
     (some? value) (str value)
     :else fallback)))

(defn display-label
  [value]
  (case (cond
          (keyword? value) value
          (string? value) (keyword value)
          :else value)
    :minimum-variance "Minimum variance"
    :max-sharpe "Maximum Sharpe"
    :target-volatility "Target volatility"
    :target-return "Target return"
    :historical-mean "Historical mean"
    :ew-mean "EW mean"
    :black-litterman "Black-Litterman"
    :diagonal-shrink "Stabilized covariance"
    :sample-covariance "Sample covariance"
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
