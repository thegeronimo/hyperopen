(ns hyperopen.active-asset.funding-policy
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private default-hypothetical-position-value
  1000)

(def ^:private annualization-days
  365)

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn funding-tooltip-pin-id
  [coin]
  (str "funding-rate-tooltip-pin-"
       (-> (or coin "asset")
           str
           str/lower-case
           (str/replace #"[^a-z0-9_-]" "-"))))

(defn- value-signature
  [value]
  {:hash (hash value)
   :count (when (counted? value)
            (count value))})

(defn parse-optional-number [value]
  (let [num (cond
              (number? value) value
              (string? value) (parse-utils/parse-localized-currency-decimal value)
              :else js/NaN)]
    (when (and (number? num) (not (js/isNaN num)))
      num)))

(defn- parse-decimal-input
  ([value]
   (parse-decimal-input value nil))
  ([value locale]
   (parse-utils/parse-localized-currency-decimal value locale)))

(defn- base-symbol-segment [value]
  (let [text (some-> value non-blank-text (str/replace #"^.*:" ""))]
    (some-> text
            (str/split #"/|-" 2)
            first
            non-blank-text)))

(defn direction-from-size [size]
  (cond
    (and (number? size) (pos? size)) :long
    (and (number? size) (neg? size)) :short
    :else :flat))

(defn- direction-label [direction]
  (case direction
    :long "Long"
    :short "Short"
    "Flat"))

(defn- signed-position-value
  [direction position-value]
  (when (number? position-value)
    (* (js/Math.abs position-value)
       (case direction
         :short -1
         :long 1
         0))))

(defn- unsigned-size-text [raw-size parsed-size]
  (let [size-text (non-blank-text raw-size)]
    (cond
      (and size-text
           (or (str/starts-with? size-text "-")
               (str/starts-with? size-text "+")))
      (subs size-text 1)

      size-text
      size-text

      (number? parsed-size)
      (fmt/safe-to-fixed (js/Math.abs parsed-size) 4)

      :else
      "0")))

(defn- live-size-input-text
  [raw-size parsed-size]
  (or (non-blank-text raw-size)
      (when (number? parsed-size)
        (fmt/safe-to-fixed parsed-size 4))
      ""))

(defn- live-value-input-text
  [direction position-value]
  (or (some-> (signed-position-value direction position-value)
              (fmt/safe-to-fixed 2))
      ""))

(defn normalized-position-value [position mark]
  (let [value (parse-optional-number (:positionValue position))
        size (parse-optional-number (:szi position))]
    (cond
      (number? value)
      (js/Math.abs value)

      (and (number? size)
           (number? mark))
      (js/Math.abs (* size mark))

      :else
      nil)))

(defn- default-hypothetical-size [mark]
  (let [mark* (parse-optional-number mark)]
    (when (and (number? mark*)
               (pos? mark*))
      (/ default-hypothetical-position-value mark*))))

(defn- hypothetical-position-model
  [coin mark hypothetical-input locale]
  (let [mark* (parse-optional-number mark)
        stored (if (map? hypothetical-input)
                 hypothetical-input
                 {})
        use-defaults? (and (not (contains? stored :size-input))
                           (not (contains? stored :value-input)))
        default-size (default-hypothetical-size mark*)
        size-input (if (contains? stored :size-input)
                     (str (or (:size-input stored) ""))
                     (if (number? default-size)
                       (fmt/safe-to-fixed default-size 4)
                       ""))
        value-input (if (contains? stored :value-input)
                      (str (or (:value-input stored) ""))
                      (fmt/safe-to-fixed default-hypothetical-position-value 2))
        size* (parse-decimal-input size-input locale)
        value-raw* (parse-decimal-input value-input locale)
        value-sign (cond
                     (and (number? value-raw*) (neg? value-raw*)) -1
                     (and (number? value-raw*) (pos? value-raw*)) 1
                     :else nil)
        value* (when (number? value-raw*)
                 (js/Math.abs value-raw*))
        resolved-size (cond
                        (number? size*) size*
                        (and (number? value*)
                             (number? mark*)
                             (pos? mark*))
                        (* (or value-sign 1)
                           (/ value* mark*))
                        (and use-defaults?
                             (number? default-size))
                        default-size
                        :else nil)
        resolved-value (cond
                         (number? value*) value*
                         (and (number? resolved-size)
                              (number? mark*)
                              (pos? mark*))
                         (* (js/Math.abs resolved-size) mark*)
                         :else nil)]
    {:coin coin
     :size-input size-input
     :value-input value-input
     :size resolved-size
     :position-value resolved-value
     :direction (direction-from-size resolved-size)}))

(defn- display-base-symbol [market coin]
  (or (non-blank-text (:base market))
      (base-symbol-segment (:symbol market))
      (base-symbol-segment coin)
      "ASSET"))

(defn funding-payment-estimate [direction position-value rate]
  (when (and (number? position-value)
             (number? rate)
             (not= direction :flat))
    (* position-value
       (/ rate 100)
       (case direction
         :long -1
         :short 1
         0))))

(defn- daily-decimal->annualized-percent
  [daily-decimal]
  (when (number? daily-decimal)
    (* daily-decimal annualization-days 100)))

(defn- daily-decimal-stddev->annualized-percent
  [daily-decimal-stddev]
  (when (number? daily-decimal-stddev)
    (* daily-decimal-stddev
       100
       (js/Math.sqrt annualization-days))))

(defn- predictability-rows
  [summary direction position-value]
  (let [annualized-mean (daily-decimal->annualized-percent (:mean summary))
        annualized-volatility (daily-decimal-stddev->annualized-percent (:stddev summary))
        expected-payment (funding-payment-estimate direction position-value annualized-mean)
        lower-rate (when (and (number? annualized-mean)
                              (number? annualized-volatility))
                     (- annualized-mean annualized-volatility))
        upper-rate (when (and (number? annualized-mean)
                              (number? annualized-volatility))
                     (+ annualized-mean annualized-volatility))
        lower-payment (funding-payment-estimate direction position-value lower-rate)
        upper-payment (funding-payment-estimate direction position-value upper-rate)]
    [{:id "mean-rate"
      :label "Mean APY"
      :rate annualized-mean
      :rate-kind :signed-percentage
      :payment expected-payment
      :payment-kind :signed-usd}
     {:id "volatility"
      :label "Volatility"
      :rate annualized-volatility
      :rate-kind :unsigned-percentage
      :payment {:lower lower-payment
                :upper upper-payment}
      :payment-kind :usd-range}]))

(defn- predictability-lag-note
  [summary]
  (let [lag-order [:lag-1d :lag-5d :lag-15d]
        first-insufficient (some (fn [lag]
                                   (let [lag-stat (get-in summary [:autocorrelation lag])]
                                     (when (:insufficient? lag-stat)
                                       lag-stat)))
                                 lag-order)]
    (when first-insufficient
      (str "Lag "
           (:lag-days first-insufficient)
           "d needs at least "
           (:minimum-daily-count first-insufficient)
           " daily points"))))

(defn funding-tooltip-model
  [position market coin mark funding-rate predictability-state hypothetical-input locale]
  (let [size-raw (:szi position)
        size (parse-optional-number size-raw)
        direction (direction-from-size size)
        position-value (normalized-position-value position mark)
        has-live-position? (and (number? size)
                                (not= direction :flat))
        hypothetical-active? (or (not has-live-position?)
                                 (map? hypothetical-input))
        hypothetical-model (when hypothetical-active?
                             (hypothetical-position-model coin mark hypothetical-input locale))
        effective-direction (if hypothetical-active?
                              (:direction hypothetical-model)
                              direction)
        effective-position-value (if hypothetical-active?
                                   (:position-value hypothetical-model)
                                   position-value)
        base-symbol (display-base-symbol market coin)
        live-seed-entry (when has-live-position?
                          {:size-input (live-size-input-text size-raw size)
                           :value-input (live-value-input-text direction position-value)})
        next-24h-rate (when (number? funding-rate)
                        (* funding-rate 24))
        annual-rate (fmt/annualized-funding-rate funding-rate)
        predictability-summary (:summary predictability-state)
        predictability-loading? (true? (:loading? predictability-state))
        predictability-error (non-blank-text (:error predictability-state))]
    {:position-mode (if hypothetical-active? :hypothetical :live)
     :position-title (if hypothetical-active?
                       "Hypothetical Position"
                       "Your Position")
     :position-action-label (cond
                              (and hypothetical-active? has-live-position?) "Use live"
                              has-live-position? "Edit estimate"
                              :else nil)
     :position-action-coin coin
     :position-action-mark mark
     :position-action-entry live-seed-entry
     :position-pin-id (funding-tooltip-pin-id coin)
     :hypothetical-helper-text (if has-live-position?
                                 "Edit size or value to estimate payments. Use negative size or value for short."
                                 "Enter size or value to estimate payments. Use negative size or value for short.")
     :position-size-label (if has-live-position?
                            (str (direction-label direction)
                                 " "
                                 (unsigned-size-text size-raw size)
                                 " "
                                 base-symbol)
                            "No open position")
     :position-value (if hypothetical-active?
                       effective-position-value
                       position-value)
     :position-base-symbol base-symbol
     :hypothetical-size-input (:size-input hypothetical-model)
     :hypothetical-value-input (:value-input hypothetical-model)
     :hypothetical-coin (:coin hypothetical-model)
     :hypothetical-mark mark
     :projection-rows [{:id "next-24h"
                        :label "Next 24h"
                        :rate next-24h-rate
                        :payment (funding-payment-estimate effective-direction
                                                          effective-position-value
                                                          next-24h-rate)}
                       {:id "apy"
                        :label "APY"
                        :rate annual-rate
                        :payment (funding-payment-estimate effective-direction
                                                          effective-position-value
                                                          annual-rate)}]
     :predictability-loading? predictability-loading?
     :predictability-error predictability-error
     :predictability-rows (when (map? predictability-summary)
                            (predictability-rows predictability-summary
                                                 effective-direction
                                                 effective-position-value))
     :predictability-daily-rate-series (when (map? predictability-summary)
                                         (vec (or (:daily-funding-series predictability-summary)
                                                  [])))
     :predictability-autocorrelation-series (when (map? predictability-summary)
                                              (vec (or (:autocorrelation-series predictability-summary)
                                                       [])))
     :predictability-lag-note (when (map? predictability-summary)
                                (predictability-lag-note predictability-summary))}))

(defonce ^:private funding-tooltip-model-cache
  (atom nil))

(defn- funding-tooltip-cache-key
  [position market coin mark funding-rate predictability-state hypothetical-input locale]
  {:position {:szi (:szi position)
              :position-value (:positionValue position)}
   :market-base (non-blank-text (:base market))
   :market-symbol (non-blank-text (:symbol market))
   :coin (non-blank-text coin)
   :mark mark
   :funding-rate funding-rate
   :predictability-loading? (true? (:loading? predictability-state))
   :predictability-error (non-blank-text (:error predictability-state))
   :predictability-summary-signature (value-signature (:summary predictability-state))
   :hypothetical-input hypothetical-input
   :locale locale})

(defn memoized-funding-tooltip-model
  [position market coin mark funding-rate predictability-state hypothetical-input locale]
  (let [cache-key (funding-tooltip-cache-key position
                                             market
                                             coin
                                             mark
                                             funding-rate
                                             predictability-state
                                             hypothetical-input
                                             locale)
        cached @funding-tooltip-model-cache]
    (if (and (map? cached)
             (= cache-key (:key cached)))
      (:result cached)
      (let [result (funding-tooltip-model position
                                          market
                                          coin
                                          mark
                                          funding-rate
                                          predictability-state
                                          hypothetical-input
                                          locale)]
        (reset! funding-tooltip-model-cache {:key cache-key
                                             :result result})
        result))))
