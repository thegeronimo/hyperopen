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

(defn parse-num [v]
  (cond
    (number? v) v
    (string? v) (let [s (str/trim v)
                      n (js/parseFloat s)]
                  (when (and (not (str/blank? s))
                             (not (js/isNaN n)))
                    n))
    :else nil))

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

(defn normalize-scale-sz-decimals [sz-decimals]
  (let [parsed (parse-num sz-decimals)]
    (-> (or parsed 8)
        (max 0)
        (min 8)
        int)))

(defn floor-size-to-decimals [size sz-decimals]
  (if (and (number? size)
           (not (js/isNaN size))
           (js/isFinite size)
           (>= size 0))
    (let [factor (js/Math.pow 10 (normalize-scale-sz-decimals sz-decimals))]
      (/ (js/Math.floor (* size factor)) factor))
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

(defn market-max-leverage [context]
  (let [max-lev (parse-num (get-in context [:active-market :maxLeverage]))]
    (when (and (number? max-lev) (pos? max-lev))
      max-lev)))

(defn normalize-ui-leverage [context leverage]
  (let [raw (or (parse-num leverage) 20)
        max-lev (or (market-max-leverage context) 100)]
    (-> (clamp-num raw 1 max-lev)
        js/Math.round)))

(defn order-side->is-buy [side]
  (= side :buy))

(defn opposite-side [side]
  (if (= side :buy) :sell :buy))

(defn scale-weights [count skew]
  (let [n (max 1 (int count))]
    (if (= n 1)
      [1]
      (let [normalized-skew (normalize-scale-skew-number skew)
            skew* (if (<= normalized-skew 0) 1.0 normalized-skew)
            start-weight (/ 2.0 (* n (+ 1.0 skew*)))
            step (/ (* start-weight (- skew* 1.0)) (dec n))
            raw-weights (map (fn [idx]
                               (let [raw (+ start-weight (* step idx))]
                                 (if (and (number? raw)
                                          (js/isFinite raw)
                                          (>= raw 0))
                                   raw
                                   0)))
                             (range n))
            total (reduce + raw-weights)]
        (if (and (number? total) (js/isFinite total) (pos? total))
          (map #(/ % total) raw-weights)
          (repeat n (/ 1 n)))))))

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
