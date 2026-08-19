(ns hyperopen.domain.trading.core
  (:require [clojure.string :as str]))

(def order-type-spec
  (array-map
   :market {:entry-mode :market
            :limit-like? false
            :requires-trigger? false
            :validate :validate/market
            :build :build/market}
   :limit {:entry-mode :limit
           :limit-like? true
           :requires-trigger? false
           :validate :validate/limit
           :build :build/limit}
   :stop-market {:entry-mode :pro
                 :limit-like? false
                 :requires-trigger? true
                 :validate :validate/stop-market
                 :build :build/stop-market}
   :stop-limit {:entry-mode :pro
                :limit-like? true
                :requires-trigger? true
                :validate :validate/stop-limit
                :build :build/stop-limit}
   :take-market {:entry-mode :pro
                 :limit-like? false
                 :requires-trigger? true
                 :validate :validate/take-market
                 :build :build/take-market}
   :take-limit {:entry-mode :pro
                :limit-like? true
                :requires-trigger? true
                :validate :validate/take-limit
                :build :build/take-limit}
   :scale {:entry-mode :pro
           :limit-like? false
           :requires-trigger? false
           :validate :validate/scale
           :build :build/scale}
   :twap {:entry-mode :pro
          :limit-like? false
          :requires-trigger? false
          :validate :validate/twap
          :build :build/twap}))

(def order-types
  (vec (keys order-type-spec)))

(def advanced-order-types
  (->> order-types
       (filter (fn [order-type]
                 (= :pro (get-in order-type-spec [order-type :entry-mode]))))
       vec))

(def limit-like-order-types
  (->> order-types
       (filter (fn [order-type]
                 (true? (get-in order-type-spec [order-type :limit-like?]))))
       set))

(def ^:private trigger-order-types
  (->> order-types
       (filter (fn [order-type]
                 (true? (get-in order-type-spec [order-type :requires-trigger?]))))
       set))

(def tif-options [:gtc :ioc :alo])

(def default-max-slippage-pct 8.0)
(def default-market-slippage-pct 5.0)

(def default-fees
  {:taker 0.045
   :maker 0.015})

(def legacy-scale-skew->number
  {:even 1.0
   :front 0.5
   :back 2.0})

(def scale-min-order-count 2)
(def scale-max-order-count 100)
(def scale-min-endpoint-notional 10)
(def twap-min-runtime-minutes 5)

(def twap-max-runtime-minutes
  ;; Venue ceiling on TWAP runtime: 7 days. Raised from 1440 (24h) by the 2026-08-01
  ;; Hyperliquid TWAP upgrade, which also added trigger/termination prices and dynamic
  ;; suborder spacing.
  10080)

(def twap-frequency-seconds
  ;; FLOOR on venue suborder spacing, not the cadence. Before 2026-08-01 the venue always
  ;; worked a twapOrder as one clip every 30s; it now stretches the interval so each clip
  ;; clears twap-min-suborder-notional. See twap-venue-suborder-count.
  30)

(def twap-min-suborder-notional
  ;; Venue floor on a single TWAP clip's notional. The venue maintains this itself by
  ;; spacing clips further apart -- it is NOT a reason to reject an order.
  10)

(def twap-min-order-notional
  ;; Venue floor on a TWAP's TOTAL notional. This is the constraint a user can actually
  ;; violate, and the one validate-twap enforces.
  100)

(defn parse-num [v]
  (cond
    (number? v) v
    (string? v) (let [s (str/trim v)
                      n (js/parseFloat s)]
                  (when (and (not (str/blank? s))
                             (not (js/isNaN n)))
                    n))
    :else nil))

(defn- normalize-nonnegative-int [value]
  (when-let [parsed (parse-num value)]
    (let [normalized (-> parsed js/Math.floor int)]
      (when (>= normalized 0)
        normalized))))

(defn- clamp-num [n min-v max-v]
  (-> n
      (max min-v)
      (min max-v)))

(defn clamp-percent [percent]
  (let [parsed (or (parse-num percent) 0)]
    (clamp-num parsed 0 100)))

(defn- parse-scale-skew-number [value]
  (cond
    (number? value) value
    (string? value) (parse-num value)
    (keyword? value) (get legacy-scale-skew->number value)
    :else nil))

(defn normalize-scale-skew-number [value]
  (let [parsed (parse-scale-skew-number value)]
    (if (number? parsed)
      (clamp-num parsed 0 100)
      1.0)))

(defn valid-scale-skew? [value]
  (let [parsed (parse-scale-skew-number value)]
    (and (number? parsed)
         (> parsed 0)
         (<= parsed 100))))

(defn number->clean-string [value decimals]
  (let [safe-decimals (-> (or decimals 4)
                          (max 0)
                          (min 8))]
    (if (number? value)
      (-> (.toFixed value safe-decimals)
          (str/replace #"0+$" "")
          (str/replace #"\.$" ""))
      "")))

(defn normalize-scale-order-count [count]
  (when-let [parsed (parse-num count)]
    (-> parsed
        int
        (max scale-min-order-count)
        (min scale-max-order-count))))

(defn valid-scale-order-count? [count]
  (when-let [parsed (parse-num count)]
    (and (>= parsed scale-min-order-count)
         (<= parsed scale-max-order-count))))

(def ^:private minutes-per-day 1440)

(defn split-twap-total-minutes
  "Splits a total minute count into the {:days :hours :minutes} triple the TWAP runtime
   inputs render. Days exist because the venue accepts runtimes up to 7 days."
  [value]
  (let [total-minutes (or (normalize-nonnegative-int value) 0)]
    {:days (quot total-minutes minutes-per-day)
     :hours (quot (mod total-minutes minutes-per-day) 60)
     :minutes (mod total-minutes 60)}))

(defn twap-total-minutes
  "Total runtime in minutes for a TWAP form. Accepts either the split
   {:days :hours :minutes} shape or a bare {:minutes n} total."
  [twap-form]
  (let [twap* (or twap-form {})]
    (if (or (contains? twap* :days) (contains? twap* :hours))
      (+ (* minutes-per-day (or (normalize-nonnegative-int (:days twap*)) 0))
         (* 60 (or (normalize-nonnegative-int (:hours twap*)) 0))
         (or (normalize-nonnegative-int (:minutes twap*)) 0))
      (normalize-nonnegative-int (:minutes twap*)))))

(defn valid-twap-runtime? [minutes]
  (when-let [total-minutes (normalize-nonnegative-int minutes)]
    (<= twap-min-runtime-minutes total-minutes twap-max-runtime-minutes)))

(defn twap-suborder-count
  "Upper bound on the number of clips a TWAP of this runtime can be worked as: the count
   the venue would use if it could sit at the twap-frequency-seconds spacing floor.
   Notional-blind, so it OVERSTATES the clip count for small orders over long runtimes --
   use twap-venue-suborder-count when the order's notional is known."
  [minutes]
  (when-let [total-minutes (normalize-nonnegative-int minutes)]
    (+ 1 (/ (* 60 total-minutes) twap-frequency-seconds))))

(defn twap-order-notional
  "Total USD notional of a TWAP order, or nil when either input is unusable."
  [total-size reference-price]
  (let [size* (parse-num total-size)
        price* (parse-num reference-price)]
    (when (and (number? size*) (pos? size*)
               (number? price*) (pos? price*))
      (* size* price*))))

(defn twap-venue-suborder-count
  "Number of clips the venue will actually work this TWAP as.

   Since the 2026-08-01 upgrade the venue picks the spacing from the order's total size
   AND its runtime rather than always firing every 30 seconds: it clips as often as the
   twap-frequency-seconds floor allows, but never so often that a clip falls below
   twap-min-suborder-notional. So the real count is the smaller of the spacing-floor
   count and the notional-floor count.

   Reproduces both of the venue's own documented examples: $10,000 over 60 minutes gives
   121 clips of ~$83 (spacing-bound, every 30s), and $10,000 over 4 days gives 1,000
   clips of ~$10 (notional-bound, roughly every 6 minutes)."
  [minutes total-notional]
  (let [spacing-bound (twap-suborder-count minutes)
        notional* (parse-num total-notional)]
    (when (and (number? spacing-bound) (pos? spacing-bound)
               (number? notional*) (pos? notional*))
      (let [notional-bound (js/Math.floor (/ notional* twap-min-suborder-notional))]
        (max 2 (min spacing-bound notional-bound))))))

(defn twap-interval-seconds-for-count
  "Seconds between clips when a runtime of `minutes` is worked as `order-count` clips.
   The first clip goes out immediately, so the runtime is divided across one fewer gap
   than there are clips."
  [minutes order-count]
  (let [total-minutes (normalize-nonnegative-int minutes)]
    (when (and (number? order-count) (> order-count 1)
               (number? total-minutes) (pos? total-minutes))
      (/ (* 60 total-minutes) (dec order-count)))))

(defn twap-suborder-interval-seconds
  "Estimated seconds between clips for a TWAP of this runtime and notional. Derived from
   twap-venue-suborder-count, so it stretches past twap-frequency-seconds for small orders
   over long runtimes. An estimate: the venue owns the real schedule."
  [minutes total-notional]
  (twap-interval-seconds-for-count minutes (twap-venue-suborder-count minutes total-notional)))

(defn twap-suborder-size
  "Base-currency size of one TWAP clip. With a reference price the notional-aware venue
   clip count is used; without one it falls back to the notional-blind spacing bound."
  ([total-size minutes]
   (twap-suborder-size total-size minutes nil))
  ([total-size minutes reference-price]
   (let [size* (parse-num total-size)
         notional (twap-order-notional total-size reference-price)
         order-count (if (number? notional)
                       (twap-venue-suborder-count minutes notional)
                       (twap-suborder-count minutes))]
     (when (and (number? size*)
                (pos? size*)
                (number? order-count)
                (pos? order-count))
       (/ size* order-count)))))

(defn twap-suborder-notional [total-size minutes reference-price]
  (let [suborder-size (twap-suborder-size total-size minutes reference-price)
        reference-price* (parse-num reference-price)]
    (when (and (number? suborder-size)
               (pos? suborder-size)
               (number? reference-price*)
               (pos? reference-price*))
      (* suborder-size reference-price*))))

(defn normalize-scale-sz-decimals [sz-decimals]
  (let [parsed (parse-num sz-decimals)]
    (-> (or parsed 8)
        (max 0)
        (min 8)
        int)))

(defn floor-size-to-decimals [size sz-decimals]
  (if (and (number? size)
           (not (js/isNaN size))
           (js/isFinite size))
    (let [safe-size (max 0 size)
          factor (js/Math.pow 10 (normalize-scale-sz-decimals sz-decimals))]
      (/ (js/Math.floor (* safe-size factor)) factor))
    0))

(defn normalize-order-type [order-type]
  (let [candidate (if (keyword? order-type) order-type (keyword order-type))]
    (if (contains? order-type-spec candidate) candidate :limit)))

(defn limit-like-type? [order-type]
  (contains? limit-like-order-types (normalize-order-type order-type)))

(defn trigger-type? [order-type]
  (contains? trigger-order-types (normalize-order-type order-type)))

(defn entry-mode-for-type [order-type]
  (get-in order-type-spec [(normalize-order-type order-type) :entry-mode] :pro))

(defn order-type-validator-id [order-type]
  (get-in order-type-spec [(normalize-order-type order-type) :validate]))

(defn order-type-builder-id [order-type]
  (get-in order-type-spec [(normalize-order-type order-type) :build]))

(defn normalize-entry-mode [entry-mode order-type]
  (let [candidate (cond
                    (keyword? entry-mode) entry-mode
                    (string? entry-mode) (keyword entry-mode)
                    :else nil)]
    (if (contains? #{:market :limit :pro} candidate)
      candidate
      (entry-mode-for-type order-type))))

(defn normalize-pro-order-type [order-type]
  (let [candidate (normalize-order-type order-type)]
    (if (some #{candidate} advanced-order-types)
      candidate
      :stop-market)))

(defn spot-market-context?
  "True when the trading context's market is a spot market (keyword or string)."
  [context]
  (= "spot" (some-> (get-in context [:market :market-type]) name str/lower-case)))

(defn market-max-leverage [context]
  (let [max-lev (parse-num (get-in context [:market :maxLeverage]))]
    (when (and (number? max-lev) (pos? max-lev))
      max-lev)))

(defn normalize-ui-leverage [context leverage]
  (if (spot-market-context? context)
    ;; Spot has no leverage. Forcing 1 here also stops percent-sizing from
    ;; multiplying by the perp fallback (100) for spot markets, which would
    ;; otherwise oversize spot orders ~100x (spot :maxLeverage is nil).
    1
    (let [raw (or (parse-num leverage) 20)
          max-lev (or (market-max-leverage context) 100)]
      (-> (clamp-num raw 1 max-lev)
          js/Math.round))))

(defn order-side->is-buy [side]
  (= side :buy))

(defn opposite-side [side]
  (if (= side :buy) :sell :buy))

(defn scale-weights [count skew]
  (let [n (max 1 (int count))]
    (let [normalized-skew (normalize-scale-skew-number skew)
          skew* (if (<= normalized-skew 0) 1.0 normalized-skew)
          start-weight (/ 2.0 (* n (+ 1.0 skew*)))
          step (if (= n 1)
                 0
                 (/ (* start-weight (- skew* 1.0)) (dec n)))
          raw-weights (map (fn [idx]
                             (+ start-weight (* step idx)))
                           (range n))
          safe-weights (when (every? (fn [raw]
                                       (and (number? raw)
                                            (js/isFinite raw)))
                                     raw-weights)
                         (map #(max 0 %) raw-weights))
          total (when (seq safe-weights)
                  (reduce + safe-weights))]
      (if (and (number? total) (js/isFinite total) (pos? total))
        (map #(/ % total) safe-weights)
        (repeat n (/ 1 n))))))

(defn scale-order-legs
  "Build deterministic scale ladder legs as [{:price p :size s}] or nil when inputs are incomplete."
  ([size count skew start end]
   (scale-order-legs size count skew start end nil))
  ([size count skew start end opts]
   (let [size* (parse-num size)
         count* (parse-num count)
         start-px (parse-num start)
         end-px (parse-num end)
         order-count (normalize-scale-order-count count*)
         sz-decimals (normalize-scale-sz-decimals (:sz-decimals opts))]
     (when (and (number? size*)
                (pos? size*)
                (number? count*)
                (> count* 1)
                (number? start-px)
                (number? end-px)
                (number? order-count)
                (valid-scale-skew? skew))
       (let [weights (vec (scale-weights order-count skew))
             step (if (= order-count 1)
                    0
                    (/ (- end-px start-px) (dec order-count)))]
         (mapv (fn [i w]
                 (let [raw-size (* size* w)]
                   {:price (+ start-px (* step i))
                    :size (floor-size-to-decimals raw-size sz-decimals)}))
               (range order-count)
               weights))))))

(defn portfolio-margin-abstraction?
  [raw-abstraction]
  (= "portfoliomargin" (some-> raw-abstraction str str/trim str/lower-case)))

(defn scale-order-value
  "Return the generated ladder's finite, positive leg-notional sum, or nil."
  [form opts]
  (let [scale (or (:scale form) {})
        legs (scale-order-legs (:size form)
                               (:count scale)
                               (:skew scale)
                               (:start scale)
                               (:end scale)
                               opts)
        notionals (when (seq legs)
                    (mapv (fn [{:keys [price size]}]
                            (when (and (number? price)
                                       (js/isFinite price)
                                       (pos? price)
                                       (number? size)
                                       (js/isFinite size)
                                       (pos? size))
                              (* price size)))
                          legs))]
    (when (and (seq notionals)
               (every? #(and (number? %) (js/isFinite %) (pos? %)) notionals))
      (reduce + notionals))))

(defn scale-preview-boundaries
  "Return first/last scale ladder legs as:
   {:start {:price number :size number}
    :end   {:price number :size number}}
   Returns nil for incomplete/invalid input."
  ([form]
   (scale-preview-boundaries form nil))
  ([form opts]
   (let [scale (or (:scale form) {})
         legs (scale-order-legs (:size form)
                                (:count scale)
                                (:skew scale)
                                (:start scale)
                                (:end scale)
                                opts)]
     (when (seq legs)
       {:start (first legs)
        :end (last legs)}))))
