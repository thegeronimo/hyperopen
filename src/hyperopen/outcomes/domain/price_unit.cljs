(ns hyperopen.outcomes.domain.price-unit
  "Outcome prices read in a unit, not in dollars. A share pays $1 when its
   answer is right, so a price of 0.209 is both 20.9¢ and a 20.9% chance.
   These helpers only relabel: the price underneath stays in dollars.

   Pure: no state, no storage.")

(def units #{:percent :cents})

(def default-unit :percent)

(def ^:private default-price-decimals
  "Outcome books tick at 0.00001 dollars."
  5)

(def ^:private minus "−")

(defn normalize-unit
  [unit]
  (let [unit* (cond
                (keyword? unit) unit
                (string? unit) (keyword unit)
                :else nil)]
    (if (contains? units unit*) unit* default-unit)))

(defn unit-suffix
  [unit]
  (if (= :cents (normalize-unit unit)) "¢" "%"))

(defn outcome-market?
  "True for the outcome market `market` when it is the market of `coin`; a
   lagging active market for another coin never counts."
  [coin market]
  (boolean (and (map? market)
                (= :outcome (:market-type market))
                (some? coin)
                (= coin (:coin market)))))

(defn- finite-number
  [value]
  (let [n (cond
            (number? value) value
            (string? value) (js/parseFloat value)
            :else js/NaN)]
    (when (and (number? n) (js/isFinite n)) n)))

(defn- fixed
  "price × 100 with exactly `decimals` digits. `.toFixed` absorbs float noise
   such as 0.209 × 100 = 20.900000000000002."
  [price decimals]
  (let [text (.toFixed (* 100 price) decimals)]
    ;; -0.0 -> 0.0
    (if (re-matches #"-0(\.0*)?" text) (subs text 1) text)))

(defn- trim-zeros
  "Drop trailing zeros but keep at least one decimal: 20.900 -> 20.9,
   20.000 -> 20.0, 20.875 -> 20.875."
  [text]
  (if (neg? (.indexOf text "."))
    text
    (let [trimmed (.replace text #"0+$" "")]
      (if (.endsWith trimmed ".") (str trimmed "0") trimmed))))

(defn max-decimals
  "Most digits a unit label shows for a book whose dollar prices carry
   `price-decimals` digits: two of them move left of the point. Never fewer
   than one."
  [price-decimals]
  (let [d (or (some-> (finite-number price-decimals) js/Math.floor)
              default-price-decimals)]
    (max 1 (- (max 0 (min 12 d)) 2))))

(defn format-price
  "One price in the unit, e.g. \"20.9%\" or \"20.875¢\": one decimal, more
   only when the price needs them (up to `max-decimals`)."
  [price {:keys [unit price-decimals]}]
  (when-let [p (finite-number price)]
    (str (trim-zeros (fixed p (max-decimals price-decimals)))
         (unit-suffix unit))))

(defn- exact-at?
  "True when price x 100 needs no more than `decimals` digits."
  [price decimals]
  (let [scaled (* 100 price)
        factor (js/Math.pow 10 decimals)]
    (< (js/Math.abs (- (* scaled factor) (js/Math.round (* scaled factor)))) 1e-6)))

(defn cents-number-text
  "A price as the cents number the ticket's limit field takes, no sign:
   0.209 -> \"20.9\", 0.20875 -> \"20.875\"."
  [price {:keys [price-decimals]}]
  (when-let [p (finite-number price)]
    (trim-zeros (fixed p (max-decimals price-decimals)))))

(defn tick-decimals
  "Fewest decimals (at least one) that print every tick exactly, so ticks
   0.05¢ apart read 20.85 / 20.90 rather than 20.8 / 20.9. Ticks that are
   never exact fall back to the fewest digits that keep labels distinct."
  [prices price-decimals]
  (let [limit (max-decimals price-decimals)
        values (keep finite-number prices)
        n (count (distinct values))
        candidates (range 1 (inc limit))]
    (or (some (fn [d] (when (every? #(exact-at? % d) values) d)) candidates)
        (some (fn [d]
                (when (= n (count (distinct (map #(fixed % d) values))))
                  d))
              candidates)
        limit)))

(defn format-ticks
  "Axis tick labels: one shared decimal count, the fewest that tells the
   ticks apart, so a zoomed-in axis reads 20.85% 20.90% 20.95%."
  [prices {:keys [unit price-decimals]}]
  (let [d (tick-decimals prices price-decimals)
        suffix (unit-suffix unit)]
    (mapv (fn [price]
            (if-let [p (finite-number price)]
              (str (fixed p d) suffix)
              (str price)))
          prices)))

(defn format-delta
  "A price change in the unit: \"+0.5 pts\" for percent (a change in
   percentage points, not a percent change), \"+0.5¢\" for cents."
  [delta {:keys [unit price-decimals]}]
  (when-let [d (finite-number delta)]
    (let [text (trim-zeros (fixed (js/Math.abs d) (max-decimals price-decimals)))
          zero? (re-matches #"0\.0*" text)
          sign (cond zero? "" (pos? d) "+" :else minus)]
      (str sign text (if (= :cents (normalize-unit unit)) "¢" " pts")))))

;; ---------------------------------------------------------------------------
;; Order book: cents with the grouping's precision

(defn step-decimals
  "Fraction digits of a grouping step: 0.00001 -> 5, 0.01 -> 2, 1 -> 0."
  [step]
  (if-let [s (finite-number step)]
    (if (pos? s)
      (max 0 (js/Math.round (- (/ (js/Math.log s) (js/Math.log 10)))))
      0)
    default-price-decimals))

(defn cents-decimals
  "Cents digits for a book shown at `price-decimals` dollar digits."
  [price-decimals]
  (max 0 (- (or (some-> (finite-number price-decimals) js/Math.floor)
                default-price-decimals)
            2)))

(defn raw-cents-decimals
  "Cents digits that print a raw dollar price without dropping any of its
   digits: \"0.22500\" -> 3, \"0.000123\" -> 4, \"0.5\" -> 0."
  [raw]
  (let [text (str (or raw ""))
        dot (.indexOf text ".")]
    (if (neg? dot)
      0
      (max 0 (- (count (re-find #"^\d*" (subs text (inc dot)))) 2)))))

(defn book-cents-text
  "A book price in cents without the sign: 0.225 at 3 digits -> \"22.500\"."
  [price decimals]
  (when-let [p (finite-number price)]
    (fixed p (max 0 decimals))))

(defn step-cents-label
  "A grouping step in cents: 0.00001 -> \"0.001¢\", 0.01 -> \"1¢\"."
  [step]
  (when-let [s (finite-number step)]
    (str (fixed s (cents-decimals (step-decimals s))) "¢")))
