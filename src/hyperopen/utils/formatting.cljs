(ns hyperopen.utils.formatting
  (:require [clojure.string :as str]
            [hyperopen.i18n.locale :as i18n-locale]))

(def ^:private min-trade-price-decimals 2)
(def ^:private max-trade-price-decimals 8)

(defn pad2 [value]
  (.padStart (str value) 2 "0"))

(defn- parse-number [value]
  (cond
    (number? value) (when-not (js/isNaN value) value)
    (string? value) (let [num (js/parseFloat value)]
                      (when-not (js/isNaN num) num))
    :else nil))

(def ^:private number-formatter*
  (memoize
   (fn [locale options]
     (js/Intl.NumberFormat. locale (clj->js options)))))

(def ^:private date-time-formatter*
  (memoize
   (fn [locale options]
     (js/Intl.DateTimeFormat. locale (clj->js options)))))

(defn- resolve-format-locale
  [locale]
  (i18n-locale/coalesce-locale locale))

(defn- format-with-number-formatter
  [num locale options]
  (.format (number-formatter*
            (resolve-format-locale locale)
            options)
           num))

(defn- coerce-date-value
  [value]
  (cond
    (instance? js/Date value) value
    :else (when-let [time-ms (parse-number value)]
            (js/Date. time-ms))))

(defn- date-time-formatter
  [locale options]
  (date-time-formatter*
   (resolve-format-locale locale)
   options))

(defn format-intl-number
  "Format a numeric value with Intl.NumberFormat options.
   Returns nil for non-numeric values."
  ([value options]
   (format-intl-number value options nil))
  ([value options locale]
   (when-let [num (parse-number value)]
     (format-with-number-formatter num locale options))))

(defn format-intl-date-time
  "Format a date/time value with Intl.DateTimeFormat options.
   Accepts js/Date, millisecond timestamps, or numeric strings."
  ([value options]
   (format-intl-date-time value options nil))
  ([value options locale]
   (when-let [date-value (coerce-date-value value)]
     (.format (date-time-formatter locale options)
              date-value))))

(defn format-intl-date-parts
  "Format date/time value to parts via Intl.DateTimeFormat#formatToParts."
  ([value options]
   (format-intl-date-parts value options nil))
  ([value options locale]
   (when-let [date-value (coerce-date-value value)]
     (js->clj (.formatToParts (date-time-formatter locale options)
                              date-value)
              :keywordize-keys true))))

(defn- normalize-fraction-digits [digits]
  (let [n (cond
            (number? digits) digits
            (string? digits) (js/parseFloat digits)
            :else js/NaN)
        normalized (if (js/isNaN n) 2 (js/Math.floor n))]
    (max 0 normalized)))

(defn- clamp-trade-price-decimals [decimals]
  (-> (or decimals min-trade-price-decimals)
      (max min-trade-price-decimals)
      (min max-trade-price-decimals)))

(defn price-decimals-from-raw
  "Infer display decimals from a raw price string, preserving useful precision
   while trimming noisy trailing zeros."
  [raw]
  (when (some? raw)
    (let [text (str raw)
          trimmed (str/trim text)]
      (when (seq trimmed)
        (let [base (first (str/split trimmed #"[eE]" 2))
              dot-idx (.indexOf base ".")]
          (if (= dot-idx -1)
            min-trade-price-decimals
            (let [fractional (.slice base (inc dot-idx))
                  cleaned (str/replace fractional #"0+$" "")
                  decimals (count cleaned)]
              (clamp-trade-price-decimals
               (if (pos? decimals) decimals min-trade-price-decimals)))))))))

(defn infer-price-decimals
  "Infer decimals from price magnitude when raw API precision is unavailable."
  [price]
  (when-let [num (parse-number price)]
    (let [abs-value (js/Math.abs num)]
      (cond
        (zero? abs-value) min-trade-price-decimals
        (>= abs-value 0.01) min-trade-price-decimals
        :else (let [log10 (/ (js/Math.log abs-value) (js/Math.log 10))
                    leading-zeros (max 0 (dec (- (js/Math.floor log10))))
                    decimals (+ leading-zeros 4)]
                (clamp-trade-price-decimals decimals))))))

(defn- trade-price-decimals [price raw]
  (or (price-decimals-from-raw raw)
      (infer-price-decimals price)
      min-trade-price-decimals))

(defn- min-visible-price [decimals]
  (js/Math.pow 10 (- decimals)))

(defn format-trade-price
  "Format a trade price in USD with adaptive decimals and a max 8 decimal cap."
  ([price] (format-trade-price price nil nil))
  ([price raw] (format-trade-price price raw nil))
  ([price raw locale]
   (when-let [num (parse-number price)]
     (let [decimals (trade-price-decimals num raw)
           min-visible (min-visible-price decimals)]
       (if (and (pos? num) (< num min-visible))
         (str "<$" (.toFixed min-visible decimals))
         (format-with-number-formatter
          num
          locale
          {:style "currency"
           :currency "USD"
           :minimumFractionDigits decimals
           :maximumFractionDigits decimals}))))))

(defn format-trade-price-plain
  "Format a trade price without currency symbol using adaptive decimals."
  ([price] (format-trade-price-plain price nil nil))
  ([price raw] (format-trade-price-plain price raw nil))
  ([price raw locale]
   (when-let [num (parse-number price)]
     (let [decimals (trade-price-decimals num raw)
           min-visible (min-visible-price decimals)]
       (if (and (pos? num) (< num min-visible))
         (str "<" (.toFixed min-visible decimals))
         (format-with-number-formatter
          num
          locale
          {:minimumFractionDigits decimals
           :maximumFractionDigits decimals}))))))

(defn format-trade-price-delta
  "Format absolute price change values with adaptive decimals."
  ([delta] (format-trade-price-delta delta nil nil))
  ([delta raw] (format-trade-price-delta delta raw nil))
  ([delta raw locale]
   (format-trade-price-plain delta raw locale)))

(defn format-fixed-number
  ([value decimals]
   (format-fixed-number value decimals nil))
  ([value decimals locale]
   (let [num (or (parse-number value) 0)
         fraction-digits (normalize-fraction-digits decimals)]
     (format-with-number-formatter
      num
      locale
      {:minimumFractionDigits fraction-digits
       :maximumFractionDigits fraction-digits}))))

(defn format-local-date-time [time-ms]
  (when time-ms
    (let [d (js/Date. time-ms)]
      (str (inc (.getMonth d))
           "/"
           (.getDate d)
           "/"
           (.getFullYear d)
           " - "
           (pad2 (.getHours d))
           ":"
           (pad2 (.getMinutes d))
           ":"
           (pad2 (.getSeconds d))))))

(defn format-local-time-hh-mm-ss [time-ms]
  (when time-ms
    (let [d (js/Date. time-ms)]
      (str (pad2 (.getHours d))
           ":"
           (pad2 (.getMinutes d))
           ":"
           (pad2 (.getSeconds d))))))

(defn format-local-datetime-input-value [time-ms]
  (when time-ms
    (let [d (js/Date. time-ms)]
      (str (.getFullYear d)
           "-"
           (pad2 (inc (.getMonth d)))
           "-"
           (pad2 (.getDate d))
           "T"
           (pad2 (.getHours d))
           ":"
           (pad2 (.getMinutes d))))))

;; Formatting functions
(defn format-number [n decimals]
  (when (and n (number? n))
    (.toFixed n decimals)))

(defn safe-to-fixed [value decimals]
  "Safely convert a value to fixed decimal places, defaulting to 0 if not a number"
  (let [num-value (if (and value (number? value)) value 0)]
    (.toFixed num-value decimals)))

(defn format-currency-with-digits
  "Format USD with explicit min/max fraction digits."
  ([amount min-digits max-digits]
   (format-currency-with-digits amount min-digits max-digits nil))
  ([amount min-digits max-digits locale]
   (let [min* (normalize-fraction-digits min-digits)
         max* (max min* (normalize-fraction-digits max-digits))]
     (when-let [num (parse-number amount)]
       (format-with-number-formatter
        num
        locale
        {:style "currency"
         :currency "USD"
         :minimumFractionDigits min*
         :maximumFractionDigits max*})))))

(defn format-currency
  ([amount]
   (format-currency amount nil))
  ([amount locale]
   (format-currency-with-digits amount 2 2 locale)))

(defn format-large-currency
  ([amount]
   (format-large-currency amount nil))
  ([amount locale]
   (format-currency-with-digits amount 0 0 locale)))

(defn format-percentage [value & [decimals]]
  (when value
    (str (safe-to-fixed value (or decimals 2)) "%")))

(defn annualized-funding-rate [hourly-rate]
  (when hourly-rate
    (* hourly-rate 24 365)))

(defn format-time [seconds]
  (when seconds
    (let [hours (js/Math.floor (/ seconds 3600))
          minutes (js/Math.floor (/ (mod seconds 3600) 60))
          secs (mod seconds 60)]
      (str (.padStart (str hours) 2 "0") ":"
           (.padStart (str minutes) 2 "0") ":"
           (.padStart (str secs) 2 "0")))))

(defn format-funding-countdown []
  (let [now (js/Date.)
        current-minutes (.getMinutes now)
        current-seconds (.getSeconds now)
        minutes-until-hour (- 60 current-minutes)
        seconds-until-minute (- 60 current-seconds)
        total-minutes (if (zero? seconds-until-minute) 
                       minutes-until-hour 
                       (dec minutes-until-hour))
        total-seconds (if (zero? seconds-until-minute) 
                       0 
                       seconds-until-minute)]
    (str "00:" (.padStart (str total-minutes) 2 "0") ":"
         (.padStart (str total-seconds) 2 "0"))))

(defn safe-number [value]
  "Convert value to number, handling NaN and nil cases"
  (let [num (if (number? value) value (js/parseFloat value))]
    (if (js/isNaN num) 0 num)))

(defn calculate-open-interest-usd [open-interest mark-price]
  "Calculate open interest in USD by multiplying open interest by mark price"
  (when (and open-interest mark-price)
    (* open-interest mark-price)))

(defn format-open-interest-usd [open-interest mark-price]
  "Calculate and format open interest in USD"
  (when-let [usd-value (calculate-open-interest-usd open-interest mark-price)]
    (format-large-currency usd-value))) 
