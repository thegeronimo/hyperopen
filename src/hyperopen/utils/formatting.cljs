(ns hyperopen.utils.formatting)

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