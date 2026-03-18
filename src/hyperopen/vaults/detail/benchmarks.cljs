(ns hyperopen.vaults.detail.benchmarks
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.detail.performance :as performance-model]
            [hyperopen.vaults.detail.types :as detail-types]))

(def ^:private max-vault-benchmark-options
  100)

(def ^:private empty-benchmark-markets-signature
  {:count 0
   :rolling-hash 1
   :xor-hash 0})

(def ^:private empty-vault-benchmark-rows-signature
  {:count 0
   :rolling-hash 1
   :xor-hash 0})

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

(defn- mix-signature-hash
  [rolling entry-hash]
  (let [rolling* (bit-or rolling 0)
        entry-hash* (bit-or entry-hash 0)]
    (bit-or
     (+ (bit-xor rolling* entry-hash*)
        0x9e3779b9
        (bit-shift-left rolling* 6)
        (unsigned-bit-shift-right rolling* 2))
     0)))

(defn benchmark-market-signature
  [market]
  (hash [(some-> (:coin market) str)
         (some-> (:symbol market) str)
         (some-> (:dex market) str)
         (:market-type market)
         (:openInterest market)
         (:cache-order market)
         (some-> (:key market) str)]))

(defn benchmark-markets-signature
  [markets]
  (reduce (fn [{:keys [count rolling-hash xor-hash] :as signature} market]
            (if (map? market)
              (let [market-hash (benchmark-market-signature market)]
                {:count (inc count)
                 :rolling-hash (mix-signature-hash rolling-hash market-hash)
                 :xor-hash (bit-xor (bit-or xor-hash 0) (bit-or market-hash 0))})
              signature))
          empty-benchmark-markets-signature
          (or markets [])))

(defn benchmark-vault-row-signature
  [row]
  (hash [(detail-types/normalize-vault-address (:vault-address row))
         (non-blank-text (:name row))
         (benchmark-vault-tvl row)
         (get-in row [:relationship :type])]))

(defn benchmark-vault-rows-signature
  [rows]
  (reduce (fn [{:keys [count rolling-hash xor-hash] :as signature} row]
            (if (map? row)
              (let [row-hash (benchmark-vault-row-signature row)]
                {:count (inc count)
                 :rolling-hash (mix-signature-hash rolling-hash row-hash)
                 :xor-hash (bit-xor (bit-or xor-hash 0) (bit-or row-hash 0))})
              signature))
          empty-vault-benchmark-rows-signature
          (or rows [])))

(defn- benchmark-market-selector-options
  [markets]
  (let [ordered-markets (->> (or markets [])
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

(defonce benchmark-market-selector-options-cache
  (atom nil))

(defn- memoized-benchmark-market-selector-options-result
  [markets]
  (let [cache @benchmark-market-selector-options-cache]
    (cond
      (and (map? cache)
           (identical? markets (:markets cache)))
      cache

      :else
      (let [markets-signature (benchmark-markets-signature markets)]
        (if (and (map? cache)
                 (= markets-signature (:markets-signature cache)))
          (do
            (reset! benchmark-market-selector-options-cache (assoc cache
                                                                  :markets markets
                                                                  :markets-signature markets-signature))
            @benchmark-market-selector-options-cache)
          (let [options (benchmark-market-selector-options markets)]
            (reset! benchmark-market-selector-options-cache {:markets markets
                                                             :markets-signature markets-signature
                                                             :options options})
            @benchmark-market-selector-options-cache))))))

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

(defonce eligible-vault-benchmark-rows-cache
  (atom nil))

(defn memoized-eligible-vault-benchmark-rows
  [rows]
  (let [cache @eligible-vault-benchmark-rows-cache]
    (cond
      (and (map? cache)
           (identical? rows (:rows cache)))
      (:eligible-rows cache)

      :else
      (let [rows-signature (benchmark-vault-rows-signature rows)]
        (if (and (map? cache)
                 (= rows-signature (:rows-signature cache)))
          (do
            (reset! eligible-vault-benchmark-rows-cache (assoc cache
                                                               :rows rows
                                                               :rows-signature rows-signature))
            (:eligible-rows cache))
          (let [eligible-rows (eligible-vault-benchmark-rows rows)]
            (reset! eligible-vault-benchmark-rows-cache {:rows rows
                                                         :rows-signature rows-signature
                                                         :eligible-rows eligible-rows})
            eligible-rows))))))

(defn- benchmark-vault-selector-options
  [rows]
  (let [top-rows (memoized-eligible-vault-benchmark-rows rows)]
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

(defonce benchmark-vault-selector-options-cache
  (atom nil))

(defn- memoized-benchmark-vault-selector-options-result
  [rows]
  (let [cache @benchmark-vault-selector-options-cache]
    (cond
      (and (map? cache)
           (identical? rows (:rows cache)))
      cache

      :else
      (let [rows-signature (benchmark-vault-rows-signature rows)]
        (if (and (map? cache)
                 (= rows-signature (:rows-signature cache)))
          (do
            (reset! benchmark-vault-selector-options-cache (assoc cache
                                                                 :rows rows
                                                                 :rows-signature rows-signature))
            @benchmark-vault-selector-options-cache)
          (let [options (benchmark-vault-selector-options rows)]
            (reset! benchmark-vault-selector-options-cache {:rows rows
                                                            :rows-signature rows-signature
                                                            :options options})
            @benchmark-vault-selector-options-cache))))))

(defonce returns-benchmark-selector-model-cache
  (atom nil))

(defn reset-vault-detail-benchmarks-cache!
  []
  (reset! benchmark-market-selector-options-cache nil)
  (reset! eligible-vault-benchmark-rows-cache nil)
  (reset! benchmark-vault-selector-options-cache nil)
  (reset! returns-benchmark-selector-model-cache nil))

(defn- benchmark-selector-options-result
  [state]
  (let [market-result (memoized-benchmark-market-selector-options-result
                       (get-in state [:asset-selector :markets]))
        vault-result (memoized-benchmark-vault-selector-options-result
                      (get-in state [:vaults :merged-index-rows]))]
    {:options (into (vec (:options market-result))
                    (:options vault-result))
     :options-signature {:markets (:markets-signature market-result)
                         :vault-rows (:rows-signature vault-result)}}))

(defn- benchmark-selector-options
  [state]
  (:options (benchmark-selector-options-result state)))

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
  (let [{:keys [options options-signature]} (benchmark-selector-options-result state)
        option-values (into #{} (map :value) options)
        selected-coins (->> (selected-vault-detail-returns-benchmark-coins state)
                            (filter (fn [coin]
                                      (if (detail-types/vault-benchmark-address coin)
                                        (contains? option-values coin)
                                        true)))
                            vec)
        search (or (get-in state [:vaults-ui :detail-returns-benchmark-search]) "")
        suggestions-open? (boolean (get-in state [:vaults-ui :detail-returns-benchmark-suggestions-open?]))
        cache @returns-benchmark-selector-model-cache]
    (if (and (map? cache)
             (= options-signature (:options-signature cache))
             (= selected-coins (:selected-coins cache))
             (= search (:search cache))
             (= suggestions-open? (:suggestions-open? cache)))
      (:model cache)
      (let [selected-coin-set (set selected-coins)
            search-query (normalize-benchmark-search-query search)
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
                            :else "All symbols selected.")
            model {:selected-coins selected-coins
                   :selected-options selected-options
                   :coin-search search
                   :suggestions-open? suggestions-open?
                   :candidates candidates
                   :top-coin top-coin
                   :empty-message empty-message
                   :label-by-coin (into {} (map (juxt :value :label)) options)}]
        (reset! returns-benchmark-selector-model-cache {:options-signature options-signature
                                                        :selected-coins selected-coins
                                                        :search search
                                                        :suggestions-open? suggestions-open?
                                                        :model model})
        model))))

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
                      (let [time-ms (portfolio-metrics/history-point-time-ms row)
                            value (portfolio-metrics/history-point-value row)
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
                 (let [time-ms (portfolio-metrics/history-point-time-ms row)
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

(defn- benchmark-details-by-address
  [state vault-address]
  (or (get-in state [:vaults :benchmark-details-by-address vault-address])
      (get-in state [:vaults :details-by-address vault-address])))

(defn- normalized-return-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (portfolio-metrics/history-point-time-ms row)
                     value (portfolio-metrics/history-point-value row)]
                 (when (and (number? time-ms)
                            (number? value))
                   [time-ms value]))))
       (sort-by first)
       vec))

(defn- aligned-summary-return-rows
  [benchmark-rows strategy-return-points]
  (let [benchmark-rows* (normalized-return-rows benchmark-rows)
        benchmark-count (count benchmark-rows*)
        strategy-time-points (mapv :time-ms strategy-return-points)
        strategy-count (count strategy-time-points)]
    (loop [time-idx 0
           benchmark-idx 0
           latest-value nil
           output []]
      (if (>= time-idx strategy-count)
        output
        (let [time-ms (nth strategy-time-points time-idx)
              [benchmark-idx* latest-value*]
              (loop [idx benchmark-idx
                     latest latest-value]
                (if (>= idx benchmark-count)
                  [idx latest]
                  (let [[benchmark-time-ms benchmark-value] (nth benchmark-rows* idx)]
                    (if (<= benchmark-time-ms time-ms)
                      (recur (inc idx) benchmark-value)
                      [idx latest]))))
              output* (if (number? latest-value*)
                        (conj output [time-ms latest-value*])
                        output)]
          (recur (inc time-idx)
                 benchmark-idx*
                 latest-value*
                 output*))))))

(defn benchmark-cumulative-return-points-by-coin
  [state snapshot-range benchmark-coins strategy-return-points]
  (if (and (seq benchmark-coins)
           (seq strategy-return-points))
    (let [{:keys [interval]} (portfolio-actions/returns-benchmark-candle-request snapshot-range)
          normalized-range (portfolio-actions/normalize-summary-time-range snapshot-range)]
      (reduce (fn [rows-by-coin coin]
                (if (seq coin)
                  (let [aligned-rows (if-let [vault-address (detail-types/vault-benchmark-address coin)]
                                       (let [details (benchmark-details-by-address state vault-address)
                                             summary (performance-model/portfolio-summary-by-range details
                                                                                                   normalized-range)]
                                         (aligned-summary-return-rows
                                          (portfolio-metrics/returns-history-rows state summary :all)
                                          strategy-return-points))
                                       (let [candles (benchmark-candle-points (get-in state [:candles coin interval]))]
                                         (aligned-benchmark-return-rows candles strategy-return-points)))]
                    (assoc rows-by-coin
                           coin
                           (rows->chart-points aligned-rows :returns)))
                  rows-by-coin))
              {}
              benchmark-coins))
    {}))
