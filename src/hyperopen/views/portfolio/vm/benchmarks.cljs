(ns hyperopen.views.portfolio.vm.benchmarks
  (:require [clojure.string :as str]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.detail.performance :as vault-performance]
            [hyperopen.views.portfolio.vm.history :as vm-history]
            [hyperopen.views.portfolio.vm.utils :as vm-utils]))

(def ^:private vault-benchmark-prefix
  "vault:")

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

(def ^:private empty-source-version-counter
  0)

(declare mix-benchmark-markets-hash)

(defn- parse-cache-order
  [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn market-type-token
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim str/lower-case keyword)
    :else nil))

(defn benchmark-open-interest
  [market]
  (let [open-interest (vm-utils/optional-number (:openInterest market))]
    (if (vm-utils/finite-number? open-interest)
      open-interest
      0)))

(defn benchmark-option-label
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

(defn benchmark-option-rank
  [market]
  [(- (benchmark-open-interest market))
   (or (parse-cache-order (:cache-order market))
       js/Number.MAX_SAFE_INTEGER)
   (str/lower-case (or (some-> (:symbol market) str str/trim) ""))
   (str/lower-case (or (some-> (:coin market) str str/trim) ""))
   (str/lower-case (or (some-> (:key market) str str/trim) ""))])

(defn- normalize-vault-address
  [value]
  (some-> value str str/trim str/lower-case))

(defn vault-benchmark-value
  [vault-address]
  (str vault-benchmark-prefix vault-address))

(defn vault-benchmark-address
  [benchmark]
  (let [benchmark* (some-> benchmark str str/trim)
        benchmark-lower (some-> benchmark* str/lower-case)]
    (when (and (seq benchmark-lower)
               (str/starts-with? benchmark-lower vault-benchmark-prefix))
      (normalize-vault-address (subs benchmark* (count vault-benchmark-prefix))))))

(defn benchmark-vault-tvl
  [row]
  (let [tvl (vm-utils/optional-number (:tvl row))]
    (if (vm-utils/finite-number? tvl)
      tvl
      0)))

(defn benchmark-vault-name
  [row]
  (some-> (:name row) str str/trim))

(defn benchmark-vault-option-rank
  [row]
  [(- (benchmark-vault-tvl row))
   (str/lower-case (or (benchmark-vault-name row) ""))
   (str/lower-case (or (normalize-vault-address (:vault-address row)) ""))])

(defn benchmark-vault-row-signature
  [row]
  (hash [(normalize-vault-address (:vault-address row))
         (benchmark-vault-name row)
         (benchmark-vault-tvl row)
         (get-in row [:relationship :type])]))

(defn benchmark-vault-rows-signature
  [rows]
  (reduce (fn [{:keys [count rolling-hash xor-hash] :as signature} row]
            (if (map? row)
              (let [row-hash (benchmark-vault-row-signature row)]
                {:count (inc count)
                 :rolling-hash (mix-benchmark-markets-hash rolling-hash row-hash)
                 :xor-hash (bit-xor (bit-or xor-hash 0) (bit-or row-hash 0))})
              signature))
          empty-vault-benchmark-rows-signature
          (or rows [])))

(defn benchmark-vault-row?
  [row]
  (and (map? row)
       (seq (normalize-vault-address (:vault-address row)))
       (not= :child (get-in row [:relationship :type]))))

(defn eligible-vault-benchmark-rows
  [rows]
  (->> (or rows [])
       (filter benchmark-vault-row?)
       (sort-by benchmark-vault-option-rank)
       (take max-vault-benchmark-options)
       vec))

(defn build-vault-benchmark-selector-options
  [rows]
  (let [top-rows (eligible-vault-benchmark-rows rows)]
    (->> top-rows
         (reduce (fn [{:keys [seen options]} row]
                   (if-let [vault-address (normalize-vault-address (:vault-address row))]
                     (if (contains? seen vault-address)
                       {:seen seen
                        :options options}
                       (let [name (or (benchmark-vault-name row)
                                      vault-address)]
                         {:seen (conj seen vault-address)
                          :options (conj options
                                         {:value (vault-benchmark-value vault-address)
                                          :label (str name " (VAULT)")
                                          :tvl (benchmark-vault-tvl row)})}))
                     {:seen seen
                      :options options}))
                 {:seen #{}
                  :options []})
         :options
         vec)))

(defn mix-benchmark-markets-hash
  [rolling market-hash]
  (let [rolling* (bit-or rolling 0)
        raw-market-hash (if (map? market-hash)
                          (hash [(some-> (:coin market-hash) str)
                                 (some-> (:symbol market-hash) str)
                                 (some-> (:dex market-hash) str)
                                 (:market-type market-hash)
                                 (:openInterest market-hash)
                                 (:cache-order market-hash)
                                 (some-> (:key market-hash) str)])
                          market-hash)
        market-hash* (bit-or raw-market-hash 0)]
    (bit-or
     (+ (bit-xor rolling* market-hash*)
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
                 :rolling-hash (mix-benchmark-markets-hash rolling-hash market-hash)
                 :xor-hash (bit-xor (bit-or xor-hash 0) (bit-or market-hash 0))})
              signature))
          empty-benchmark-markets-signature
          (or markets [])))

(defn build-benchmark-selector-options
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

(defonce benchmark-selector-options-cache
  (atom nil))

(defonce eligible-vault-benchmark-rows-cache
  (atom nil))

(defonce vault-benchmark-selector-options-cache
  (atom nil))

(defonce returns-benchmark-selector-model-cache
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

(def ^:dynamic *build-benchmark-selector-options*
  build-benchmark-selector-options)

(def ^:dynamic *build-vault-benchmark-selector-options*
  build-vault-benchmark-selector-options)

(defn- memoized-benchmark-selector-options-result
  [markets]
  (let [cache @benchmark-selector-options-cache]
    (cond
      (and (map? cache)
           (identical? markets (:markets cache)))
      cache

      :else
      (let [signature (benchmark-markets-signature markets)]
        (if (and (map? cache)
                 (= signature (:markets-signature cache)))
          (do
            (reset! benchmark-selector-options-cache (assoc cache
                                                           :markets markets
                                                           :markets-signature signature))
            @benchmark-selector-options-cache)
          (let [options (*build-benchmark-selector-options* markets)]
            (reset! benchmark-selector-options-cache {:markets markets
                                                      :markets-signature signature
                                                      :options options})
            @benchmark-selector-options-cache))))))

(defn memoized-benchmark-selector-options
  [markets]
  (:options (memoized-benchmark-selector-options-result markets)))

(defn- memoized-vault-benchmark-selector-options-result
  [rows]
  (let [cache @vault-benchmark-selector-options-cache]
    (cond
      (and (map? cache)
           (identical? rows (:rows cache)))
      cache

      :else
      (let [rows-signature (benchmark-vault-rows-signature rows)]
        (if (and (map? cache)
                 (= rows-signature (:rows-signature cache)))
          (do
            (reset! vault-benchmark-selector-options-cache (assoc cache
                                                                  :rows rows
                                                                  :rows-signature rows-signature))
            @vault-benchmark-selector-options-cache)
          (let [options (*build-vault-benchmark-selector-options* rows)]
            (reset! vault-benchmark-selector-options-cache {:rows rows
                                                            :rows-signature rows-signature
                                                            :options options})
            @vault-benchmark-selector-options-cache))))))

(defn memoized-vault-benchmark-selector-options
  [rows]
  (:options (memoized-vault-benchmark-selector-options-result rows)))

(defn reset-portfolio-vm-cache!
  []
  (reset! benchmark-selector-options-cache nil)
  (reset! eligible-vault-benchmark-rows-cache nil)
  (reset! vault-benchmark-selector-options-cache nil)
  (reset! returns-benchmark-selector-model-cache nil))

(defn- benchmark-selector-options-result
  [state]
  (let [market-result (memoized-benchmark-selector-options-result
                       (get-in state [:asset-selector :markets]))
        vault-result (memoized-vault-benchmark-selector-options-result
                      (get-in state [:vaults :merged-index-rows]))]
    {:options (into (vec (:options market-result))
                    (:options vault-result))
     :options-signature {:markets (:markets-signature market-result)
                         :vault-rows (:rows-signature vault-result)}}))

(defn benchmark-selector-options
  [state]
  (:options (benchmark-selector-options-result state)))

(defn normalize-benchmark-search-query
  [value]
  (-> (or value "")
      str
      str/trim
      str/lower-case))

(defn benchmark-option-matches-search?
  [option search-query]
  (or (str/blank? search-query)
      (str/includes? (str/lower-case (or (:label option) "")) search-query)
      (str/includes? (str/lower-case (or (:value option) "")) search-query)))

(defn selected-returns-benchmark-coins
  [state]
  (let [coins (portfolio-actions/normalize-portfolio-returns-benchmark-coins
               (get-in state [:portfolio-ui :returns-benchmark-coins]))]
    (if (seq coins)
      coins
      (if-let [legacy-coin (portfolio-actions/normalize-portfolio-returns-benchmark-coin
                            (get-in state [:portfolio-ui :returns-benchmark-coin]))]
        [legacy-coin]
        []))))

(defn selected-benchmark-options
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
        selected-coins (->> (selected-returns-benchmark-coins state)
                            (filter (fn [coin]
                                      (if (vault-benchmark-address coin)
                                        (contains? option-values coin)
                                        true)))
                            vec)
        search (or (get-in state [:portfolio-ui :returns-benchmark-search]) "")
        suggestions-open? (boolean (get-in state [:portfolio-ui :returns-benchmark-suggestions-open?]))
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

(defn- benchmark-details-by-address
  [state vault-address]
  (or (get-in state [:vaults :benchmark-details-by-address vault-address])
      (get-in state [:vaults :details-by-address vault-address])))

(defn- normalized-return-rows
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (vm-history/history-point-time-ms row)
                     value (vm-history/history-point-value row)]
                 (when (and (number? time-ms)
                            (vm-utils/finite-number? value))
                   [time-ms value]))))
       (sort-by first)
       vec))

(defn- aligned-summary-return-rows
  [benchmark-rows strategy-points]
  (let [benchmark-rows* (normalized-return-rows benchmark-rows)
        benchmark-count (count benchmark-rows*)
        strategy-time-points (mapv :time-ms strategy-points)
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
              output* (if (vm-utils/finite-number? latest-value*)
                        (conj output [time-ms latest-value*])
                        output)]
          (recur (inc time-idx)
                 benchmark-idx*
                 latest-value*
                 output*))))))

(defn- cumulative-return-time-points
  [rows]
  (->> (or rows [])
       (keep (fn [row]
               (let [time-ms (vm-history/history-point-time-ms row)
                     value (vm-history/history-point-value row)]
                 (when (and (number? time-ms)
                            (vm-utils/finite-number? value))
                   {:time-ms time-ms
                    :value value}))))
       vec))

(defn sampled-series-source-version-counter
  [rows]
  (let [rows* (or rows [])
        row-count (count rows*)]
    (if (pos? row-count)
      (let [mid-idx (quot row-count 2)
            first-row (nth rows* 0 nil)
            mid-row (nth rows* mid-idx nil)
            last-row (nth rows* (dec row-count) nil)]
        (hash [row-count
               (vm-history/history-point-time-ms first-row)
               (vm-history/history-point-value first-row)
               (vm-history/history-point-time-ms mid-row)
               (vm-history/history-point-value mid-row)
               (vm-history/history-point-time-ms last-row)
               (vm-history/history-point-value last-row)]))
      empty-source-version-counter)))

(defn benchmark-source-version-by-coin
  [benchmark-cumulative-rows-by-coin selected-benchmark-coins]
  (into {}
        (map (fn [coin]
               [coin
                (sampled-series-source-version-counter
                 (get benchmark-cumulative-rows-by-coin coin))]))
        selected-benchmark-coins))

(defn- cumulative-row-pairs
  [rows]
  (mapv (fn [{:keys [time-ms value]}]
          [time-ms value])
        rows))

(defn benchmark-cumulative-return-rows-by-coin
  [state summary-time-range benchmark-coins strategy-time-points]
  (if (and (seq benchmark-coins)
           (seq strategy-time-points))
    (let [{:keys [interval]} (portfolio-actions/returns-benchmark-candle-request summary-time-range)
          normalized-range (portfolio-actions/normalize-summary-time-range summary-time-range)]
      (reduce (fn [rows-by-coin coin]
                (if (seq coin)
                  (if-let [vault-address (vault-benchmark-address coin)]
                    (let [details (benchmark-details-by-address state vault-address)
                          summary (vault-performance/portfolio-summary-by-range details
                                                                               normalized-range)]
                      (assoc rows-by-coin
                             coin
                             (aligned-summary-return-rows
                              (portfolio-metrics/returns-history-rows state summary :all)
                              strategy-time-points)))
                    (let [candles (vm-history/benchmark-candle-points (get-in state [:candles coin interval]))]
                      (assoc rows-by-coin
                             coin
                             (cumulative-row-pairs
                              (vm-history/aligned-benchmark-return-rows candles strategy-time-points)))))
                  rows-by-coin))
              {}
              benchmark-coins))
    {}))

(defn benchmark-computation-context
  [state summary-entry summary-scope summary-time-range returns-benchmark-selector]
  (let [strategy-cumulative-rows (portfolio-metrics/returns-history-rows state
                                                                          summary-entry
                                                                          summary-scope)
        strategy-time-points (cumulative-return-time-points strategy-cumulative-rows)
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector)
                                          []))
        benchmark-cumulative-rows-by-coin (benchmark-cumulative-return-rows-by-coin state
                                                                                     summary-time-range
                                                                                     selected-benchmark-coins
                                                                                     strategy-time-points)
        strategy-source-version (sampled-series-source-version-counter strategy-cumulative-rows)
        benchmark-source-version-map (benchmark-source-version-by-coin benchmark-cumulative-rows-by-coin
                                                                       selected-benchmark-coins)]
    {:strategy-cumulative-rows strategy-cumulative-rows
     :benchmark-cumulative-rows-by-coin benchmark-cumulative-rows-by-coin
     :strategy-source-version strategy-source-version
     :benchmark-source-version-map benchmark-source-version-map}))
