(ns hyperopen.views.trading-chart.utils.chart-trades-model
  "Pure model behind the trader's own trades on the price chart.

  Turns raw Hyperliquid fill rows plus the chart's candles into:
  - `:groups`  one entry per (candle, side) with size-weighted average price,
               totals and the underlying fills, drawn as one badge each;
  - `:ribbon`  long/short position segments over time (perps only);
  - `:median-notional` the trader's median group notional, which sets the
               three badge size steps."
  (:require [hyperopen.views.trading-chart.utils.fill-fields :as fields]))

(defn- candle-index-at-or-before
  "Index of the last candle whose open time (seconds) is <= `time-sec`, or nil
  when `time-sec` is before the first candle. `candle-data` is sorted by time."
  [candle-data time-sec]
  (let [n (count candle-data)]
    (when (and (pos? n)
               (>= time-sec (:time (nth candle-data 0))))
      (loop [lo 0
             hi (dec n)]
        (if (>= lo hi)
          lo
          ;; upper-middle so `lo` always advances
          (let [mid (quot (+ lo hi 1) 2)]
            (if (<= (:time (nth candle-data mid)) time-sec)
              (recur mid hi)
              (recur lo (dec mid)))))))))

(defn- side-kind
  [fill sign]
  (cond
    (some? (:liquidation fill)) :liquidation
    (= 1 sign) :buy
    :else :sell))

(defn- normalize-fill
  "Fill row -> {:identity :time-ms :sign :side-kind :size :price :fee
  :fee-token :closed-pnl :start-position :maker? :input-index}, or nil when it
  cannot be drawn."
  [input-index fill]
  (let [sign (fields/fill-side-sign fill)
        time-ms (fields/fill-time fill)
        size (some-> (fields/fill-size fill) js/Math.abs)
        price (fields/fill-price fill)]
    (when (and (contains? #{1 -1} sign)
               (fields/finite-number? time-ms)
               (fields/finite-number? size)
               (pos? size)
               (fields/finite-number? price)
               (pos? price))
      {:identity (fields/fill-identity fill)
       :time-ms time-ms
       :sign sign
       :side-kind (side-kind fill sign)
       :size size
       :price price
       :fee (or (fields/parse-num (:fee fill)) 0)
       ;; spot buys pay fees in the base token, everything else in USDC
       :fee-token (fields/non-blank-text (:feeToken fill))
       :closed-pnl (or (fields/parse-num (:closedPnl fill)) 0)
       :start-position (fields/parse-num (:startPosition fill))
       :maker? (not (true? (:crossed fill)))
       :input-index input-index})))

(defn- execution-order-key
  "Fills of one sweeping order share a millisecond. Within a side the
  exchange's startPosition moves monotonically with execution (up for buys,
  down for sells), so sign * startPosition orders them; the input index is the
  last resort. Identities are never compared (mixed string/vector types)."
  [{:keys [time-ms sign start-position input-index]}]
  [time-ms
   (if (fields/finite-number? start-position) (* sign start-position) 0)
   input-index])

(defn- asset-fills
  "Drawable fills for the active asset, deduplicated, oldest first."
  [active-asset fills market-by-key]
  (->> (or fills [])
       (filter #(and (map? %)
                     (fields/asset-fill-match? active-asset % market-by-key)))
       (keep-indexed normalize-fill)
       (reduce (fn [{:keys [seen rows] :as acc} row]
                 (if (contains? seen (:identity row))
                   acc
                   {:seen (conj seen (:identity row))
                    :rows (conj rows row)}))
               {:seen #{} :rows []})
       :rows
       (sort-by execution-order-key)
       vec))

(defn- position-after
  [{:keys [start-position sign size]}]
  (when (fields/finite-number? start-position)
    (+ start-position (* sign size))))

(defn- median
  [values]
  (let [sorted (vec (sort values))
        n (count sorted)]
    (when (pos? n)
      (if (odd? n)
        (nth sorted (quot n 2))
        (/ (+ (nth sorted (dec (quot n 2)))
              (nth sorted (quot n 2)))
           2)))))

(defn- size-step
  [notional median-notional]
  (cond
    (not (and (fields/finite-number? median-notional) (pos? median-notional))) :typical
    (< notional (* 0.5 median-notional)) :small
    (> notional (* 2 median-notional)) :large
    :else :typical))

(defn- build-group
  [candle side-kind rows]
  (let [size (reduce + (map :size rows))
        notional (reduce + (map #(* (:size %) (:price %)) rows))
        latest (last rows)]
    {:id (str (:time candle) "-" (name side-kind))
     :time (:time candle)
     :side-kind side-kind
     ;; buys draw below the candle, sells above; a liquidation follows the
     ;; direction of its forced trade
     :sign (:sign latest)
     :count (count rows)
     :size size
     :avg-price (/ notional size)
     :notional notional
     :fees (reduce + (map :fee rows))
     :fee-token (let [tokens (set (keep :fee-token rows))]
                  (cond
                    (empty? tokens) nil
                    (= 1 (count tokens)) (first tokens)
                    :else :mixed))
     :closed-pnl (reduce + (map :closed-pnl rows))
     :position-after (position-after latest)
     :time-ms (:time-ms latest)
     :candle-high (:high candle)
     :candle-low (:low candle)
     :fills (mapv #(select-keys % [:time-ms :sign :size :price :maker? :fee :fee-token]) rows)}))

(def ^:private side-order
  {:buy 0 :sell 1 :liquidation 2})

(defn- build-groups
  [rows candle-data]
  (let [snapped (keep (fn [row]
                        (when-let [idx (candle-index-at-or-before
                                        candle-data
                                        (js/Math.floor (/ (:time-ms row) 1000)))]
                          (assoc row :candle-index idx)))
                      rows)
        groups (->> snapped
                    (group-by (juxt :candle-index :side-kind))
                    (map (fn [[[idx kind] group-rows]]
                           (build-group (nth candle-data idx) kind group-rows)))
                    (sort-by (juxt :time (comp side-order :side-kind)))
                    vec)
        median-notional (median (map :notional groups))]
    {:groups (mapv #(assoc % :size-step (size-step (:notional %) median-notional)) groups)
     :median-notional median-notional
     :snapped snapped}))

(defn- direction-of
  [position]
  (cond
    (pos? position) :long
    (neg? position) :short
    :else nil))

(defn- ribbon-segments
  "Position over time from each fill's own `startPosition` + signed size.
  Using the exchange-reported start position per fill (instead of a running
  sum) keeps the ribbon right even when some older fills are not loaded."
  [snapped candle-data]
  (let [rows (filterv #(fields/finite-number? (:start-position %)) snapped)
        first-time (:time (first candle-data))
        last-time (:time (last candle-data))]
    (if (or (empty? rows) (nil? first-time))
      []
      (let [first-row (first rows)
            leading (when (and (not (zero? (:start-position first-row)))
                               (< first-time (:time (nth candle-data (:candle-index first-row)))))
                      {:from-time first-time
                       :to-time (:time (nth candle-data (:candle-index first-row)))
                       :position (:start-position first-row)})
            tail (map-indexed
                  (fn [i row]
                    (let [next-row (nth rows (inc i) nil)]
                      {:from-time (:time (nth candle-data (:candle-index row)))
                       :to-time (if next-row
                                  (:time (nth candle-data (:candle-index next-row)))
                                  last-time)
                       :open-ended? (nil? next-row)
                       :position (position-after row)}))
                  rows)]
        (->> (cons leading tail)
             (remove nil?)
             (keep (fn [{:keys [position] :as segment}]
                     (when-let [direction (direction-of position)]
                       (-> segment
                           (assoc :direction direction
                                  :size (js/Math.abs position))
                           (dissoc :position)))))
             ;; several fills inside one candle yield zero-width segments; the
             ;; last one in that candle carries the position forward
             (remove #(and (= (:from-time %) (:to-time %))
                           (not (:open-ended? %))))
             (reduce (fn [acc segment]
                       (let [prev (peek acc)]
                         (if (and prev
                                  (= (:direction prev) (:direction segment))
                                  (= (:size prev) (:size segment))
                                  (= (:to-time prev) (:from-time segment)))
                           (conj (pop acc) (assoc prev
                                                  :to-time (:to-time segment)
                                                  :open-ended? (:open-ended? segment)))
                           (conj acc segment))))
                     [])
             (mapv #(update % :open-ended? boolean)))))))

(defn build-chart-trades
  "{:active-asset :fills :market-by-key :candle-data :perp?} ->
  {:groups [...] :groups-by-id {...} :ribbon [...] :median-notional n}."
  [{:keys [active-asset fills market-by-key candle-data perp?]}]
  (let [candle-data* (vec (or candle-data []))]
    (if (or (empty? candle-data*) (empty? fills))
      {:groups [] :groups-by-id {} :ribbon [] :median-notional nil}
      (let [rows (asset-fills active-asset fills market-by-key)
            {:keys [groups median-notional snapped]} (build-groups rows candle-data*)]
        {:groups groups
         :groups-by-id (into {} (map (juxt :id identity)) groups)
         :ribbon (if perp? (ribbon-segments snapped candle-data*) [])
         :median-notional median-notional}))))
