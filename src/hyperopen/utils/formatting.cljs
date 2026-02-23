(ns hyperopen.utils.formatting
  (:require [clojure.string :as str]))

;; Number formatters
(def usd-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:style           "currency"
        :currency        "USD"
        :minimumFractionDigits 2
        :maximumFractionDigits 2}))

(def large-number-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:style           "currency"
        :currency        "USD"
        :minimumFractionDigits 0
        :maximumFractionDigits 0}))

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
  ([price] (format-trade-price price nil))
  ([price raw]
   (when-let [num (parse-number price)]
     (let [decimals (trade-price-decimals num raw)
           min-visible (min-visible-price decimals)]
       (if (and (pos? num) (< num min-visible))
         (str "<$" (.toFixed min-visible decimals))
         (.toLocaleString (js/Number. num)
                          "en-US"
                          #js {:style "currency"
                               :currency "USD"
                               :minimumFractionDigits decimals
                               :maximumFractionDigits decimals}))))))

(defn format-trade-price-plain
  "Format a trade price without currency symbol using adaptive decimals."
  ([price] (format-trade-price-plain price nil))
  ([price raw]
   (when-let [num (parse-number price)]
     (let [decimals (trade-price-decimals num raw)
           min-visible (min-visible-price decimals)]
       (if (and (pos? num) (< num min-visible))
         (str "<" (.toFixed min-visible decimals))
         (.toLocaleString (js/Number. num)
                          "en-US"
                          #js {:minimumFractionDigits decimals
                               :maximumFractionDigits decimals}))))))

(defn format-trade-price-delta
  "Format absolute price change values with adaptive decimals."
  ([delta] (format-trade-price-delta delta nil))
  ([delta raw]
   (format-trade-price-plain delta raw)))

(defn format-fixed-number [value decimals]
  (let [num (or (parse-number value) 0)
        fraction-digits (normalize-fraction-digits decimals)]
    (.toLocaleString (js/Number. num)
                     "en-US"
                     #js {:minimumFractionDigits fraction-digits
                          :maximumFractionDigits fraction-digits})))

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

(defn format-currency [amount]
  (when amount
    (.format usd-formatter amount)))

(defn format-large-currency [amount]
  (when amount
    (.format large-number-formatter amount)))

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
