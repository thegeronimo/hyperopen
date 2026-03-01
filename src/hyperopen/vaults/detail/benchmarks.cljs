(ns hyperopen.vaults.detail.benchmarks
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.vaults.detail.types :as detail-types]))

(def ^:private max-vault-benchmark-options
  100)

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else nil))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- parse-cache-order
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn- market-type-token
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn- benchmark-open-interest
  [market]
  (let [open-interest (optional-number (:openInterest market))]
    (if (number? open-interest)
      open-interest
      0)))

(defn- benchmark-option-label
  [market]
  (let [symbol (some-> (:symbol market) str str/trim)
        coin (some-> (:coin market) str str/trim)
        dex (some-> (:dex market) str str/trim str/upper-case)
        market-type (market-type-token (:market-type market))
        type-label (case market-type
                     :spot "SPOT"
                     :perp "PERP"
                     nil)
        primary-label (or symbol coin "")]
    (cond
      (and (seq dex) (seq type-label)) (str primary-label " (" dex " " type-label ")")
      (seq type-label) (str primary-label " (" type-label ")")
      :else primary-label)))

(defn- benchmark-option-rank
  [market]
  [(- (benchmark-open-interest market))
   (or (parse-cache-order (:cache-order market))
       js/Number.MAX_SAFE_INTEGER)
   (str/lower-case (or (some-> (:symbol market) str str/trim) ""))
   (str/lower-case (or (some-> (:coin market) str str/trim) ""))
   (str/lower-case (or (some-> (:key market) str str/trim) ""))])

(defn- benchmark-vault-tvl
  [row]
  (or (optional-number (:tvl row))
      0))

(defn- benchmark-vault-option-rank
  [row]
  [(- (benchmark-vault-tvl row))
   (str/lower-case (or (non-blank-text (:name row)) ""))
   (str/lower-case (or (detail-types/normalize-vault-address (:vault-address row)) ""))])

(defn- benchmark-market-selector-options
  [state]
  (let [ordered-markets (->> (or (get-in state [:asset-selector :markets]) [])
                             (filter map?)
                             (sort-by benchmark-option-rank))]
    (->> ordered-markets
         (reduce (fn [{:keys [seen options]} market]
                   (if-let [coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                                  (:coin market))]
                     (if (contains? seen coin)
                       {:seen seen
                        :options options}
                       {:seen (conj seen coin)
                        :options (conj options
                                       {:value coin
                                        :label (benchmark-option-label market)
                                        :open-interest (benchmark-open-interest market)})})
                     {:seen seen
                      :options options}))
                 {:seen #{}
                  :options []})
         :options
         vec)))

(defn- benchmark-vault-row?
  [row]
  (and (map? row)
       (seq (detail-types/normalize-vault-address (:vault-address row)))
       (not= :child (get-in row [:relationship :type]))))

(defn- eligible-vault-benchmark-rows
  [rows]
  (->> (or rows [])
       (filter benchmark-vault-row?)
       (sort-by benchmark-vault-option-rank)
       (take max-vault-benchmark-options)
       vec))

(defn- benchmark-vault-selector-options
  [state]
  (let [top-rows (eligible-vault-benchmark-rows (get-in state [:vaults :merged-index-rows]))]
    (->> top-rows
         (reduce (fn [{:keys [seen options]} row]
                   (if-let [vault-address (detail-types/normalize-vault-address (:vault-address row))]
                     (if (contains? seen vault-address)
                       {:seen seen
                        :options options}
                       (let [name (or (non-blank-text (:name row))
                                      vault-address)]
                         {:seen (conj seen vault-address)
                          :options (conj options
                                         {:value (detail-types/vault-benchmark-value vault-address)
                                          :label (str name " (VAULT)")
                                          :tvl (benchmark-vault-tvl row)})}))
                     {:seen seen
                      :options options}))
                 {:seen #{}
                  :options []})
         :options
         vec)))

(defn- benchmark-selector-options
  [state]
  (into (benchmark-market-selector-options state)
        (benchmark-vault-selector-options state)))

(defn- normalize-benchmark-search-query
  [value]
  (-> (or value "")
      str
      str/trim
      str/lower-case))

(defn- benchmark-option-matches-search?
  [option search-query]
  (or (str/blank? search-query)
      (str/includes? (str/lower-case (or (:label option) "")) search-query)
      (str/includes? (str/lower-case (or (:value option) "")) search-query)))

(defn- selected-vault-detail-returns-benchmark-coins
  [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:vaults-ui :detail-returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:vaults-ui :detail-returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn- selected-benchmark-options
  [options selected-coins]
  (let [options-by-coin (into {} (map (juxt :value identity)) options)]
    (mapv (fn [coin]
            (or (get options-by-coin coin)
                {:value coin
                 :label coin
                 :open-interest 0}))
          selected-coins)))

(defn returns-benchmark-selector-model
  [state]
  (let [options (benchmark-selector-options state)
        option-values (into #{} (map :value) options)
        selected-coins (->> (selected-vault-detail-returns-benchmark-coins state)
                            (filter (fn [coin]
                                      (if (detail-types/vault-benchmark-address coin)
                                        (contains? option-values coin)
                                        true)))
                            vec)
        selected-coin-set (set selected-coins)
        search (or (get-in state [:vaults-ui :detail-returns-benchmark-search]) "")
        search-query (normalize-benchmark-search-query search)
        suggestions-open? (boolean (get-in state [:vaults-ui :detail-returns-benchmark-suggestions-open?]))
        selected-options (selected-benchmark-options options selected-coins)
        candidates (->> options
                        (remove (fn [{:keys [value]}]
                                  (contains? selected-coin-set value)))
                        (filter #(benchmark-option-matches-search? % search-query))
                        vec)
        top-coin (some-> candidates first :value)
        empty-message (cond
                        (empty? options) "No benchmark symbols available."
                        (seq candidates) nil
                        (seq search-query) "No matching symbols."
                        :else "All symbols selected.")]
    {:selected-coins selected-coins
     :selected-options selected-options
     :coin-search search
     :suggestions-open? suggestions-open?
     :candidates candidates
     :top-coin top-coin
     :empty-message empty-message
     :label-by-coin (into {} (map (juxt :value :label)) options)}))

(defn- history-point
  [row]
  (cond
    (and (sequential? row)
         (>= (count row) 2))
    {:time-ms (optional-number (first row))
     :value (optional-number (second row))}

    (map? row)
    {:time-ms (or (optional-number (:time row))
                  (optional-number (:timestamp row))
                  (optional-number (:time-ms row))
                  (optional-number (:timeMs row))
                  (optional-number (:ts row))
                  (optional-number (:t row)))
     :value (or (optional-number (:value row))
                (optional-number (:account-value row))
                (optional-number (:accountValue row))
                (optional-number (:pnl row)))}

    :else
    nil))

(defn- normalize-chart-point-value
  [series value]
  (when (number? value)
    (if (= series :returns)
      (let [rounded (/ (js/Math.round (* value 100)) 100)]
        (if (== rounded -0)
          0
          rounded))
      value)))

(defn- rows->chart-points
  [rows series]
  (->> rows
       (map-indexed (fn [idx row]
                      (let [{:keys [time-ms value]} (history-point row)
                            value* (normalize-chart-point-value series value)]
                        (when (and (number? time-ms)
                                   (number? value*))
                          {:index idx
                           :time-ms time-ms
                           :value value*}))))
       (keep identity)
       vec))

(defn- candle-point-close
  [row]
  (cond
    (map? row)
    (or (optional-number (:c row))
        (optional-number (:close row)))

    (and (sequential? row)
         (>= (count row) 5))
    (optional-number (nth row 4))

    :else
    nil))

(defn- benchmark-candle-points
  [rows]
  (if (sequential? rows)
    (->> rows
         (keep (fn [row]
                 (let [time-ms (some-> row history-point :time-ms)
                       close (candle-point-close row)]
                   (when (and (number? time-ms)
                              (number? close)
                              (pos? close))
                     {:time-ms time-ms
                      :close close}))))
         (sort-by :time-ms)
         vec)
    []))

(defn- aligned-benchmark-return-rows
  [benchmark-points strategy-points]
  (let [benchmark-count (count benchmark-points)
        strategy-time-points (mapv :time-ms strategy-points)
        strategy-count (count strategy-time-points)]
    (loop [time-idx 0
           candle-idx 0
           latest-close nil
           anchor-close nil
           output []]
      (if (>= time-idx strategy-count)
        output
        (let [time-ms (nth strategy-time-points time-idx)
              [candle-idx* latest-close*]
              (loop [idx candle-idx
                     latest latest-close]
                (if (>= idx benchmark-count)
                  [idx latest]
                  (let [{candle-time-ms :time-ms
                         close :close} (nth benchmark-points idx)]
                    (if (<= candle-time-ms time-ms)
                      (recur (inc idx) close)
                      [idx latest]))))
              anchor-close* (or anchor-close latest-close*)
              output* (if (and (number? latest-close*)
                               (number? anchor-close*)
                               (pos? anchor-close*))
                        (let [cumulative-return (* 100 (- (/ latest-close* anchor-close*) 1))]
                          (if (number? cumulative-return)
                            (conj output [time-ms cumulative-return])
                            output))
                        output)]
          (recur (inc time-idx)
                 candle-idx*
                 latest-close*
                 anchor-close*
                 output*))))))

(defn- vault-benchmark-rows-by-address
  [state]
  (->> (eligible-vault-benchmark-rows (get-in state [:vaults :merged-index-rows]))
       (reduce (fn [rows-by-address row]
                 (if-let [vault-address (detail-types/normalize-vault-address (:vault-address row))]
                   (assoc rows-by-address vault-address row)
                   rows-by-address))
               {})))

(defn- vault-snapshot-range-keys
  [snapshot-range]
  (case (portfolio-actions/normalize-summary-time-range snapshot-range)
    :day [:day :week :month :all-time]
    :week [:week :month :all-time :day]
    :month [:month :week :all-time :day]
    :three-month [:all-time :month :week :day]
    :six-month [:all-time :month :week :day]
    :one-year [:all-time :month :week :day]
    :two-year [:all-time :month :week :day]
    :all-time [:all-time :month :week :day]
    [:month :week :all-time :day]))

(defn- vault-snapshot-point-value
  [entry]
  (cond
    (number? entry)
    entry

    (and (sequential? entry)
         (>= (count entry) 2))
    (optional-number (second entry))

    (map? entry)
    (or (optional-number (:value entry))
        (optional-number (:pnl entry))
        (optional-number (:account-value entry))
        (optional-number (:accountValue entry)))

    :else
    nil))

(defn- normalize-vault-snapshot-return
  [raw tvl]
  (cond
    (not (number? raw))
    nil

    (and (number? tvl)
         (pos? tvl)
         (> (js/Math.abs raw) 1000))
    (* 100 (/ raw tvl))

    (<= (js/Math.abs raw) 1)
    (* 100 raw)

    :else
    raw))

(defn- vault-benchmark-snapshot-values
  [row snapshot-range]
  (let [snapshot-by-key (or (:snapshot-by-key row) {})
        tvl (benchmark-vault-tvl row)]
    (or (some (fn [snapshot-key]
                (let [raw-values (get snapshot-by-key snapshot-key)]
                  (when (sequential? raw-values)
                    (let [normalized-values (->> raw-values
                                                 (keep vault-snapshot-point-value)
                                                 (keep #(normalize-vault-snapshot-return % tvl))
                                                 vec)]
                      (when (seq normalized-values)
                        normalized-values)))))
              (vault-snapshot-range-keys snapshot-range))
        [])))

(defn- aligned-vault-return-rows
  [snapshot-values strategy-return-points]
  (let [values (vec (or snapshot-values []))
        value-count (count values)
        strategy-time-points (mapv :time-ms strategy-return-points)
        strategy-count (count strategy-time-points)]
    (if (and (pos? value-count)
             (pos? strategy-count))
      (mapv (fn [idx time-ms]
              (let [ratio (if (> strategy-count 1)
                            (/ idx (dec strategy-count))
                            0)
                    value-idx (if (> value-count 1)
                                (js/Math.round (* ratio (dec value-count)))
                                0)
                    value-idx* (max 0 (min (dec value-count) value-idx))]
                [time-ms (nth values value-idx*)]))
            (range strategy-count)
            strategy-time-points)
      [])))

(defn benchmark-cumulative-return-points-by-coin
  [state snapshot-range benchmark-coins strategy-return-points]
  (if (and (seq benchmark-coins)
           (seq strategy-return-points))
    (let [{:keys [interval]} (portfolio-actions/returns-benchmark-candle-request snapshot-range)
          any-vault-benchmark? (boolean (some detail-types/vault-benchmark-address benchmark-coins))
          vault-rows-by-address (when any-vault-benchmark?
                                  (vault-benchmark-rows-by-address state))]
      (reduce (fn [rows-by-coin coin]
                (if (seq coin)
                  (let [aligned-rows (if-let [vault-address (detail-types/vault-benchmark-address coin)]
                                       (aligned-vault-return-rows
                                        (vault-benchmark-snapshot-values
                                         (get vault-rows-by-address vault-address)
                                         snapshot-range)
                                        strategy-return-points)
                                       (let [candles (benchmark-candle-points (get-in state [:candles coin interval]))]
                                         (aligned-benchmark-return-rows candles strategy-return-points)))]
                    (assoc rows-by-coin
                           coin
                           (rows->chart-points aligned-rows :returns)))
                  rows-by-coin))
              {}
              benchmark-coins))
    {}))
