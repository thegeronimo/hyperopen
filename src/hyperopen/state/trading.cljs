(ns hyperopen.state.trading
  (:require [clojure.string :as str]))

(def order-types
  [:market :limit :stop-market :stop-limit :take-market :take-limit :scale :twap])

(def advanced-order-types
  [:stop-market :stop-limit :take-market :take-limit :scale :twap])

(def limit-like-order-types
  #{:limit :stop-limit :take-limit})

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

(defn default-order-form []
  {:entry-mode :limit
   :type :limit
   :side :buy
   :ui-leverage 20
   :size-percent 0
   :pro-order-type-dropdown-open? false
   :tpsl-panel-open? false
   :size-display ""
   :size ""
   :price ""
   :trigger-px ""
   :reduce-only false
   :post-only false
   :tif :gtc
   :slippage 0.5
   :scale {:start ""
           :end ""
           :count 5
           :skew "1.00"}
   :twap {:minutes 5
          :randomize true}
   :tp {:enabled? false
        :trigger ""
        :is-market true
        :limit ""}
   :sl {:enabled? false
        :trigger ""
        :is-market true
        :limit ""}
   :submitting? false
   :error nil})

(defn parse-num [v]
  (cond
    (number? v) v
    (string? v) (let [s (str/trim v)
                      n (js/parseFloat s)]
                  (when (and (not (str/blank? s)) (not (js/isNaN n))) n))
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

(defn- valid-scale-skew? [value]
  (let [parsed (parse-scale-skew-number value)]
    (and (number? parsed)
         (> parsed 0)
         (<= parsed 100))))

(defn- number->clean-string [value decimals]
  (let [safe-decimals (-> (or decimals 4)
                          (max 0)
                          (min 8))]
    (if (number? value)
      (-> (.toFixed value safe-decimals)
          (str/replace #"0+$" "")
          (str/replace #"\.$" ""))
      "")))

(defn- normalize-scale-form [scale]
  (let [raw-scale (or scale {})
        raw-skew (:skew raw-scale)
        normalized-skew (cond
                          (string? raw-skew) raw-skew
                          (number? raw-skew) (number->clean-string (normalize-scale-skew-number raw-skew) 2)
                          (keyword? raw-skew) (number->clean-string (normalize-scale-skew-number raw-skew) 2)
                          :else "1.00")]
    {:start (or (:start raw-scale) "")
     :end (or (:end raw-scale) "")
     :count (or (:count raw-scale) 5)
     :skew normalized-skew}))

(defn- normalize-scale-order-count [count]
  (when-let [parsed (parse-num count)]
    (-> parsed
        int
        (max scale-min-order-count)
        (min scale-max-order-count))))

(defn- valid-scale-order-count? [count]
  (when-let [parsed (parse-num count)]
    (and (>= parsed scale-min-order-count)
         (<= parsed scale-max-order-count))))

(defn- normalize-scale-sz-decimals [sz-decimals]
  (let [parsed (parse-num sz-decimals)]
    (-> (or parsed 8)
        (max 0)
        (min 8)
        int)))

(defn- floor-size-to-decimals [size sz-decimals]
  (if (and (number? size)
           (not (js/isNaN size))
           (js/isFinite size)
           (>= size 0))
    (let [factor (js/Math.pow 10 (normalize-scale-sz-decimals sz-decimals))]
      (/ (js/Math.floor (* size factor)) factor))
    0))

(declare scale-order-legs)

(defn normalize-order-type [order-type]
  (let [candidate (if (keyword? order-type) order-type (keyword order-type))]
    (if (some #{candidate} order-types) candidate :limit)))

(defn limit-like-type? [order-type]
  (contains? limit-like-order-types (normalize-order-type order-type)))

(defn entry-mode-for-type [order-type]
  (case (normalize-order-type order-type)
    :market :market
    :limit :limit
    :pro))

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

(defn market-max-leverage [state]
  (let [max-lev (parse-num (get-in state [:active-market :maxLeverage]))]
    (when (and (number? max-lev) (pos? max-lev))
      max-lev)))

(defn normalize-ui-leverage [state leverage]
  (let [raw (or (parse-num leverage) 20)
        max-lev (or (market-max-leverage state) 100)]
    (-> (clamp-num raw 1 max-lev)
        js/Math.round)))

(defn- active-clearinghouse-state [state]
  (let [dex (get-in state [:active-market :dex])]
    (if (and (string? dex) (seq dex))
      (get-in state [:perp-dex-clearinghouse dex])
      (get-in state [:webdata2 :clearinghouseState]))))

(defn available-to-trade [state]
  (let [clearinghouse (or (active-clearinghouse-state state) {})
        withdrawable (parse-num (:withdrawable clearinghouse))
        summary (or (get-in clearinghouse [:marginSummary])
                    (get-in clearinghouse [:crossMarginSummary])
                    {})
        account-value (or (parse-num (:accountValue summary)) 0)
        margin-used (or (parse-num (:totalMarginUsed summary)) 0)
        fallback-available (- account-value margin-used)]
    (max 0 (if (number? withdrawable)
             withdrawable
             fallback-available))))

(defn position-for-active-asset [state]
  (let [active-asset (:active-asset state)
        positions (get-in (active-clearinghouse-state state) [:assetPositions])]
    (some (fn [entry]
            (let [position (or (:position entry) entry)]
              (when (= active-asset (:coin position))
                position)))
          positions)))

(defn current-position-summary [state]
  (let [active-asset (:active-asset state)
        position (position-for-active-asset state)
        size (or (parse-num (:szi position)) 0)
        liquidation (parse-num (:liquidationPx position))]
    {:coin active-asset
     :size size
     :abs-size (js/Math.abs size)
     :direction (cond
                  (pos? size) :long
                  (neg? size) :short
                  :else :flat)
     :liquidation-price (when (and (number? liquidation) (pos? liquidation))
                          liquidation)}))

(defn- size-decimals [state]
  (let [sz-decimals (parse-num (get-in state [:active-market :szDecimals]))]
    (-> (or sz-decimals 4)
        (max 0)
        (min 8)
        int)))

(defn base-size-string
  "Format canonical base size by truncating to market szDecimals."
  [state value]
  (when (and (number? value) (pos? value))
    (let [decimals (size-decimals state)
          factor (js/Math.pow 10 decimals)
          truncated (/ (js/Math.floor (* value factor)) factor)]
      (when (pos? truncated)
        (number->clean-string truncated decimals)))))

(defn- level-price [level]
  (let [price (or (parse-num (:px level))
                  (parse-num (:price level))
                  (parse-num (:p level)))]
    (when (and (number? price) (pos? price))
      price)))

(defn- level-size [level]
  (let [size (or (parse-num (:sz level))
                 (parse-num (:size level))
                 (parse-num (:s level)))]
    (when (and (number? size) (pos? size))
      size)))

(defn- normalize-level [level]
  (let [price (level-price level)
        size (level-size level)]
    (when (and (number? price)
               (pos? price)
               (number? size)
               (pos? size))
      {:price price
       :size size})))

(defn- ordered-market-levels [state side]
  (let [active-asset (:active-asset state)
        raw-levels (case side
                     :buy (get-in state [:orderbooks active-asset :asks])
                     :sell (get-in state [:orderbooks active-asset :bids])
                     nil)
        comparator (case side
                     :buy <
                     :sell >
                     nil)]
    (when comparator
      (->> (or raw-levels [])
           (keep normalize-level)
           (sort-by :price comparator)
           vec))))

(defn- top-of-book-midpoint [state]
  (let [active-asset (:active-asset state)
        bids (->> (get-in state [:orderbooks active-asset :bids])
                  (keep normalize-level))
        asks (->> (get-in state [:orderbooks active-asset :asks])
                  (keep normalize-level))
        best-bid (reduce (fn [best level]
                           (let [price (:price level)]
                             (if (or (nil? best) (> price best))
                               price
                               best)))
                         nil
                         bids)
        best-ask (reduce (fn [best level]
                           (let [price (:price level)]
                             (if (or (nil? best) (< price best))
                               price
                               best)))
                         nil
                         asks)]
    (when (and (number? best-bid)
               (pos? best-bid)
               (number? best-ask)
               (pos? best-ask))
      (/ (+ best-bid best-ask) 2))))

(defn- simulate-average-fill-price [levels requested-size]
  ;; Walk sorted levels deterministically and require a full simulated fill.
  ;; Return nil when visible depth cannot satisfy requested-size.
  (when (and (number? requested-size) (pos? requested-size))
    (loop [remaining requested-size
           filled-notional 0
           remaining-levels levels]
      (cond
        (<= remaining 0)
        (/ filled-notional requested-size)

        (empty? remaining-levels)
        nil

        :else
        (let [{:keys [price size]} (first remaining-levels)
              fill-size (min remaining size)]
          (recur (- remaining fill-size)
                 (+ filled-notional (* fill-size price))
                 (rest remaining-levels)))))))

(defn- market-slippage-estimate-pct [state form]
  (let [requested-size (parse-num (:size form))
        side (:side form)
        levels (ordered-market-levels state side)
        avg-fill (simulate-average-fill-price levels requested-size)
        midpoint (top-of-book-midpoint state)]
    (when (and (number? avg-fill)
               (pos? avg-fill)
               (number? midpoint)
               (pos? midpoint))
      (* 100 (/ (js/Math.abs (- avg-fill midpoint)) midpoint)))))

(defn- best-side-price
  "Return best price for a side independent of vector ordering.
   For bids, pass > comparator. For asks, pass < comparator."
  [levels better?]
  (reduce (fn [best level]
            (let [px (parse-num (:px level))]
              (if (and (number? px)
                       (pos? px)
                       (or (nil? best) (better? px best)))
                px
                best)))
          nil
          (or levels [])))

(defn best-bid-price [state]
  (let [active-asset (:active-asset state)
        bids (get-in state [:orderbooks active-asset :bids])]
    (best-side-price bids >)))

(defn best-ask-price [state]
  (let [active-asset (:active-asset state)
        asks (get-in state [:orderbooks active-asset :asks])]
    (best-side-price asks <)))

(defn reference-price [state form]
  (let [order-type (normalize-order-type (:type form))
        limit-price (when (limit-like-type? order-type)
                      (parse-num (:price form)))
        side (:side form)
        best-px (if (= side :buy)
                  (best-ask-price state)
                  (best-bid-price state))
        projected-mark (parse-num (get-in state [:active-market :mark]))
        streamed-mark (parse-num (get-in state [:active-assets :contexts (:active-asset state) :mark]))]
    (cond
      (and (number? limit-price) (pos? limit-price)) limit-price
      (and (number? best-px) (pos? best-px)) best-px
      (and (number? projected-mark) (pos? projected-mark)) projected-mark
      (and (number? streamed-mark) (pos? streamed-mark)) streamed-mark
      :else nil)))

(defn mid-price-summary
  "Return deterministic price context for UI rows:
   {:mid-price number|nil :source :mid|:reference|:none}."
  [state form]
  (let [bid (best-bid-price state)
        ask (best-ask-price state)
        mid (when (and (number? bid)
                       (pos? bid)
                       (number? ask)
                       (pos? ask))
              (/ (+ bid ask) 2))
        ref (reference-price state form)]
    (cond
      (number? mid) {:mid-price mid :source :mid}
      (number? ref) {:mid-price ref :source :reference}
      :else {:mid-price nil :source :none})))

(defn effective-limit-price
  "Return a deterministic fallback price for limit-like order types.
   Prefers mid (bid/ask average), then reference price."
  [state form]
  (when (limit-like-type? (:type form))
    (let [{:keys [mid-price]} (mid-price-summary state form)
          ref (reference-price state form)]
      (cond
        (and (number? mid-price) (pos? mid-price)) mid-price
        (and (number? ref) (pos? ref)) ref
        :else nil))))

(defn effective-limit-price-string
  "String representation for deterministic limit fallback price."
  [state form]
  (when-let [price (effective-limit-price state form)]
    (number->clean-string price 6)))

(defn mid-price-string
  "String representation for the true midpoint (best bid/ask average).
   Returns nil when midpoint is unavailable."
  [state form]
  (let [{:keys [mid-price source]} (mid-price-summary state form)]
    (when (and (= source :mid)
               (number? mid-price)
               (pos? mid-price))
      (number->clean-string mid-price 6))))

(defn size-from-percent [state form percent]
  (let [pct (clamp-percent percent)
        available (available-to-trade state)
        leverage (normalize-ui-leverage state (:ui-leverage form))
        ref-price (reference-price state form)
        notional (* available leverage (/ pct 100))]
    (when (and (pos? pct)
               (number? ref-price)
               (pos? ref-price)
               (pos? notional))
      (/ notional ref-price))))

(defn percent-from-size [state form size]
  (let [size-value (parse-num size)
        available (available-to-trade state)
        leverage (normalize-ui-leverage state (:ui-leverage form))
        ref-price (reference-price state form)
        notional-capacity (* available leverage)]
    (when (and (number? size-value)
               (pos? size-value)
               (number? ref-price)
               (pos? ref-price)
               (pos? notional-capacity))
      (clamp-percent (* 100 (/ (* size-value ref-price) notional-capacity))))))

(defn apply-size-percent [state form percent]
  (let [pct (clamp-percent percent)
        available (available-to-trade state)
        leverage (normalize-ui-leverage state (:ui-leverage form))
        notional (* available leverage (/ pct 100))
        normalized-form (assoc form
                               :size-percent pct
                               :ui-leverage (normalize-ui-leverage state (:ui-leverage form)))
        computed-size (size-from-percent state normalized-form pct)
        quantized-size (when (number? computed-size)
                         (base-size-string state computed-size))
        display-notional (when (and (number? notional) (pos? notional))
                           (number->clean-string notional 2))]
    (cond
      (zero? pct) (assoc normalized-form :size "" :size-display "")
      (seq quantized-size) (assoc normalized-form
                                  :size quantized-size
                                  :size-display (or display-notional ""))
      :else normalized-form)))

(defn sync-size-from-percent [state form]
  (let [pct (clamp-percent (:size-percent form))]
    (if (pos? pct)
      (apply-size-percent state form pct)
      (assoc form
             :size-percent pct
             :ui-leverage (normalize-ui-leverage state (:ui-leverage form))))))

(defn sync-size-percent-from-size [state form]
  (let [size (parse-num (:size form))
        derived (percent-from-size state form (:size form))
        leverage (normalize-ui-leverage state (:ui-leverage form))]
    (cond
      (or (nil? size) (<= size 0))
      (assoc form :size-percent 0 :ui-leverage leverage)

      (number? derived)
      (assoc form :size-percent derived :ui-leverage leverage)

      :else
      (assoc form
             :size-percent (clamp-percent (:size-percent form))
             :ui-leverage leverage))))

(defn normalize-order-form [state form]
  (let [normalized-type (normalize-order-type (:type form))
        entry-mode (normalize-entry-mode (:entry-mode form) normalized-type)
        final-type (case entry-mode
                     :market :market
                     :limit :limit
                     (normalize-pro-order-type normalized-type))
        normalized-form (-> form
                            (assoc :entry-mode entry-mode
                                   :type final-type
                                   :size-display (or (:size-display form) (:size form) "")
                                   :size-percent (clamp-percent (:size-percent form))
                                   :pro-order-type-dropdown-open? (boolean (:pro-order-type-dropdown-open? form))
                                   :ui-leverage (normalize-ui-leverage state (:ui-leverage form))
                                   :tpsl-panel-open? (boolean (:tpsl-panel-open? form)))
                            (assoc :scale (normalize-scale-form (:scale form))))]
    (cond-> normalized-form
      (= :scale final-type) (assoc :tpsl-panel-open? false)
      (= :scale final-type) (assoc-in [:tp :enabled?] false)
      (= :scale final-type) (assoc-in [:sl :enabled?] false))))

(defn order-summary [state form]
  (let [normalized-form (normalize-order-form state form)
        requested-type (normalize-order-type (:type form))
        market-order? (= requested-type :market)
        size (parse-num (:size normalized-form))
        ref-price (reference-price state normalized-form)
        order-value (when (and (number? size) (pos? size) (number? ref-price) (pos? ref-price))
                      (* size ref-price))
        leverage (normalize-ui-leverage state (:ui-leverage normalized-form))
        margin-required (when (and (number? order-value) (pos? leverage))
                          (/ order-value leverage))
        position (current-position-summary state)
        liquidation-price (:liquidation-price position)
        slippage-est (if market-order?
                       (market-slippage-estimate-pct state normalized-form)
                       0)]
    {:available-to-trade (available-to-trade state)
     :current-position position
     :reference-price ref-price
     :order-value order-value
     :margin-required margin-required
     :liquidation-price liquidation-price
     :slippage-est slippage-est
     :slippage-max default-max-slippage-pct
     :fees default-fees}))

(defn validate-order-form
  ([form]
   (validate-order-form nil form))
  ([state form]
   (let [size (parse-num (:size form))
         price (parse-num (:price form))
         trigger (parse-num (:trigger-px form))
         scale-start (parse-num (get-in form [:scale :start]))
         scale-end (parse-num (get-in form [:scale :end]))
         scale-count (parse-num (get-in form [:scale :count]))
         scale-skew (get-in form [:scale :skew])
         scale-sz-decimals (or (get-in state [:active-market :szDecimals])
                               (:sz-decimals form)
                               (get-in form [:scale :sz-decimals]))
         scale-legs (when (= :scale (:type form))
                      (scale-order-legs size
                                        scale-count
                                        scale-skew
                                        scale-start
                                        scale-end
                                        {:sz-decimals scale-sz-decimals}))
         start-leg (first scale-legs)
         end-leg (last scale-legs)
         start-notional (when (and (map? start-leg)
                                   (number? (:price start-leg))
                                   (number? (:size start-leg)))
                          (* (:price start-leg) (:size start-leg)))
         end-notional (when (and (map? end-leg)
                                 (number? (:price end-leg))
                                 (number? (:size end-leg)))
                        (* (:price end-leg) (:size end-leg)))
         twap-min (parse-num (get-in form [:twap :minutes]))
         tp-enabled? (get-in form [:tp :enabled?])
         sl-enabled? (get-in form [:sl :enabled?])
         tp-trigger (parse-num (get-in form [:tp :trigger]))
         sl-trigger (parse-num (get-in form [:sl :trigger]))]
     (cond-> []
       (or (nil? size) (<= size 0)) (conj "Size must be greater than 0.")
       (and (limit-like-type? (:type form))
            (or (nil? price) (<= price 0))) (conj "Price is required for limit orders.")
       (and (#{:stop-market :stop-limit :take-market :take-limit} (:type form))
            (or (nil? trigger) (<= trigger 0))) (conj "Trigger price is required for stop/take orders.")
       (and (= :scale (:type form))
            (or (nil? scale-start)
                (nil? scale-end)
                (not (valid-scale-order-count? scale-count))))
       (conj "Scale orders need start/end prices and count between 2 and 100.")
       (and (= :scale (:type form))
            (not (valid-scale-skew? scale-skew)))
       (conj "Scale skew must be greater than 0 and at most 100.")
       (and (= :scale (:type form))
            (or (nil? start-notional)
                (nil? end-notional)
                (< start-notional scale-min-endpoint-notional)
                (< end-notional scale-min-endpoint-notional)))
       (conj "Scale start/end orders must each be at least 10 in order value.")
       (and (= :twap (:type form))
            (or (nil? twap-min) (<= twap-min 0)))
       (conj "TWAP minutes must be greater than 0.")
       (and tp-enabled? (or (nil? tp-trigger) (<= tp-trigger 0)))
       (conj "TP trigger price is required when TP is enabled.")
       (and sl-enabled? (or (nil? sl-trigger) (<= sl-trigger 0)))
       (conj "SL trigger price is required when SL is enabled.")))))

(def ^:private validation-error->required-fields
  {"Size must be greater than 0." ["Size"]
   "Price is required for limit orders." ["Price"]
   "Trigger price is required for stop/take orders." ["Trigger Price"]
   "Scale orders need start/end prices and count between 2 and 100." ["Start Price" "End Price" "Total Orders"]
   "TWAP minutes must be greater than 0." ["Minutes"]
   "TP trigger price is required when TP is enabled." ["TP Trigger"]
   "SL trigger price is required when SL is enabled." ["SL Trigger"]})

(def ^:private required-field-rank
  {"Price" 0
   "Size" 1
   "Trigger Price" 2
   "Start Price" 3
   "End Price" 4
   "Total Orders" 5
   "Minutes" 6
   "TP Trigger" 7
   "SL Trigger" 8})

(defn submit-required-fields
  "Map validation errors to deterministic field labels for submit guidance UI."
  [errors]
  (->> (or errors [])
       (mapcat #(get validation-error->required-fields %))
       distinct
       (sort-by #(get required-field-rank % 999))
       vec))

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
                                 (if (and (number? raw) (js/isFinite raw) (>= raw 0))
                                   raw
                                   0)))
                             (range n))
            total (reduce + raw-weights)]
        (if (and (number? total) (js/isFinite total) (pos? total))
          (map #(/ % total) raw-weights)
          (repeat n (/ 1 n)))))))

(defn- scale-order-legs
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
             step (if (= order-count 1) 0 (/ (- end-px start-px) (dec order-count)))]
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

(defn build-scale-orders [asset-idx side total-size start end reduce-only post-only]
  (let [legs (scale-order-legs (get total-size :size)
                               (get total-size :count)
                               (get total-size :skew)
                               start
                               end
                               {:sz-decimals (get total-size :sz-decimals)})
        tif (if post-only "Alo" "Gtc")]
    (mapv (fn [{:keys [price size]}]
            {:a asset-idx
             :b (order-side->is-buy side)
             :p (str price)
             :s (str size)
             :r reduce-only
             :t {:limit {:tif tif}}})
          legs)))

(defn build-tpsl-orders [asset-idx side form]
  (let [tp (get-in form [:tp])
        sl (get-in form [:sl])
        tp-enabled? (:enabled? tp)
        sl-enabled? (:enabled? sl)
        close-side (opposite-side side)
        mk-trigger (fn [tpsl cfg]
                     {:a asset-idx
                      :b (order-side->is-buy close-side)
                      :p (str (or (parse-num (:limit cfg)) (parse-num (:trigger cfg))))
                      :s (str (parse-num (:size form)))
                      :r true
                      :t {:trigger {:isMarket (:is-market cfg)
                                    :triggerPx (parse-num (:trigger cfg))
                                    :tpsl tpsl}}})]
    (cond-> []
      tp-enabled? (conj (mk-trigger "tp" tp))
      sl-enabled? (conj (mk-trigger "sl" sl)))))

(defn build-order-action
  "Return {:action action :grouping grouping}"
  [state form]
  (let [active-asset (:active-asset state)
        asset-idx (get-in state [:asset-contexts (keyword active-asset) :idx])
        side (:side form)
        size (parse-num (:size form))
        price (parse-num (:price form))
        trigger (parse-num (:trigger-px form))
        reduce-only (:reduce-only form)
        post-only (:post-only form)
        tif (case (:tif form)
              :ioc "Ioc"
              :alo "Alo"
              "Gtc")
        grouping (if (or (get-in form [:tp :enabled?]) (get-in form [:sl :enabled?]))
                   "normalTpsl"
                   "na")]
    (when (and active-asset asset-idx size)
      (let [base-order {:a asset-idx
                        :b (order-side->is-buy side)
                        :p (str price)
                        :s (str size)
                        :r reduce-only}
            order (case (:type form)
                    :limit (assoc base-order :t {:limit {:tif (if post-only "Alo" tif)}})
                    :market (assoc base-order :t {:limit {:tif "Ioc"}})
                    :stop-market (assoc base-order :p (str (or price trigger))
                                        :t {:trigger {:isMarket true :triggerPx trigger :tpsl "sl"}})
                    :stop-limit (assoc base-order :t {:trigger {:isMarket false :triggerPx trigger :tpsl "sl"}})
                    :take-market (assoc base-order :p (str (or price trigger))
                                        :t {:trigger {:isMarket true :triggerPx trigger :tpsl "tp"}})
                    :take-limit (assoc base-order :t {:trigger {:isMarket false :triggerPx trigger :tpsl "tp"}})
                    base-order)
            tpsl-orders (build-tpsl-orders asset-idx side form)
            orders (cond-> [order]
                     (seq tpsl-orders) (into tpsl-orders))]
        {:action {:type "order"
                  :grouping grouping
                  :orders orders}
         :asset-idx asset-idx
         :orders orders}))))

(defn build-twap-action [state form]
  (let [active-asset (:active-asset state)
        asset-idx (get-in state [:asset-contexts (keyword active-asset) :idx])
        side (:side form)
        size (parse-num (:size form))
        minutes (parse-num (get-in form [:twap :minutes]))
        randomize (boolean (get-in form [:twap :randomize]))]
    (when (and active-asset asset-idx size minutes)
      {:action {:type "twapOrder"
                :twap {:a asset-idx
                       :b (order-side->is-buy side)
                       :s (str size)
                       :r (boolean (:reduce-only form))
                       :m (int minutes)
                       :t randomize}}
       :asset-idx asset-idx})))

(defn best-price [state side]
  (if (= side :buy)
    (best-ask-price state)
    (best-bid-price state)))

(defn apply-market-price [state form]
  (let [side (:side form)
        px (best-price state side)
        slippage (or (parse-num (:slippage form)) 0.5)
        adj (if (= side :buy) (+ 1 (/ slippage 100)) (- 1 (/ slippage 100)))]
    (when px
      (assoc form :price (str (* px adj))))))

(defn prepare-order-form-for-submit
  "Return a normalized order form suitable for deterministic submit validation.
   {:form prepared-form
    :market-price-missing? boolean}"
  [state form]
  (let [normalized-form (normalize-order-form state form)
        market-form (when (= :market (:type normalized-form))
                      (apply-market-price state normalized-form))
        form-with-market (or market-form normalized-form)
        form* (if (and (limit-like-type? (:type form-with-market))
                       (str/blank? (:price form-with-market)))
                (if-let [fallback-price (effective-limit-price-string state form-with-market)]
                  (assoc form-with-market :price fallback-price)
                  form-with-market)
                form-with-market)]
    {:form form*
     :market-price-missing? (and (= :market (:type normalized-form))
                                 (nil? market-form))}))

(defn build-order-request [state form]
  (case (:type form)
    :twap (build-twap-action state form)
    :scale (let [active-asset (:active-asset state)
                 asset-idx (get-in state [:asset-contexts (keyword active-asset) :idx])
                 side (:side form)
                 size (parse-num (:size form))
                 scale (get-in form [:scale])
                 orders (when (and asset-idx size)
                          (build-scale-orders
                            asset-idx
                            side
                            {:size size
                             :count (:count scale)
                             :skew (:skew scale)
                             :sz-decimals (get-in state [:active-market :szDecimals])}
                            (get scale :start)
                            (get scale :end)
                            (:reduce-only form)
                            (:post-only form)))]
             (when (seq orders)
               {:action {:type "order"
                         :grouping "na"
                         :orders (vec orders)}
                :asset-idx asset-idx
                :orders orders}))
    (build-order-action state form)))
