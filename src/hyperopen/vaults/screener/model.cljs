(ns hyperopen.vaults.screener.model
  "Pure vault-screener policy: option sets, the screenable metric catalog,
  presets, config normalization, universe selection, criterion evaluation and
  run estimates. Nothing here touches the store, the network or storage."
  (:require [clojure.string :as str]))

(def request-interval-ms
  "One `vaultDetails` request every 2 s keeps the screener at about half of the
  per-IP /info budget (~20 weight per call against ~1,200 per minute)."
  2000)

(def max-request-interval-ms
  8000)

(def cache-ttl-ms
  (* 6 60 60 1000))

(def near-miss-fraction
  0.1)

(def age-options
  [{:value 0 :label "Any"}
   {:value 30 :label "30 days"}
   {:value 90 :label "90 days"}
   {:value 180 :label "180 days"}
   {:value 365 :label "1 year"}])

(def tvl-options
  [{:value 1000 :label "$1k"}
   {:value 10000 :label "$10k"}
   {:value 50000 :label "$50k"}
   {:value 100000 :label "$100k"}])

(def window-options
  [{:value :month :label "30D"}
   {:value :three-month :label "3M"}
   {:value :six-month :label "6M"}
   {:value :one-year :label "1Y"}
   {:value :all-time :label "All-time"}])

(def min-history-options
  "vaultDetails history is sparse: an older vault's all-time series carries
  roughly 15 points per 6 months (about 28 per year), so the bar sits at 10."
  [{:value 0 :label "Any"}
   {:value 10 :label "10 points"}
   {:value 20 :label "20 points"}])

(def metric-defs
  "`:direction` :min means the value must be at least the threshold, :max at
  most. `:unit` :percent metrics are decimals in the engine and percent in the
  UI; `:abs?` compares the magnitude (drawdowns are negative decimals)."
  [{:key :sharpe :label "Sharpe" :direction :min :unit :ratio :default "1.0" :step "0.1"
    :hint "Return per unit of volatility"}
   {:key :max-drawdown :label "Max drawdown" :direction :max :unit :percent :abs? true
    :default "25" :step "1" :hint "Worst peak-to-trough fall"}
   {:key :sortino :label "Sortino" :direction :min :unit :ratio :default "1.5" :step "0.1"
    :hint "Like Sharpe, downside volatility only"}
   {:key :calmar :label "Calmar" :direction :min :unit :ratio :default "1.0" :step "0.1"
    :hint "CAGR divided by max drawdown"}
   {:key :volatility-ann :label "Volatility (ann.)" :direction :max :unit :percent
    :default "40" :step "1" :hint "Annualised standard deviation"}
   {:key :cagr :label "CAGR" :direction :min :unit :percent :default "20" :step "1"
    :hint "Compound annual growth"}
   {:key :cumulative-return :label "Cumulative return" :direction :min :unit :percent
    :default "10" :step "1" :hint "Total return over the window"}
   {:key :longest-dd-days :label "Longest drawdown" :direction :max :unit :days
    :default "60" :step "1" :hint "Longest time under water"}
   {:key :omega :label "Omega" :direction :min :unit :ratio :default "1.2" :step "0.1"
    :hint "Gains vs losses around zero"}
   {:key :profit-factor :label "Profit factor" :direction :min :unit :ratio :default "1.3"
    :step "0.1" :hint "Gross gains / gross losses"}])

(def metric-def-by-key
  (into {} (map (juxt :key identity)) metric-defs))

(def metric-keys
  (mapv :key metric-defs))

(def results-tabs
  [:pass :near :fail :thin])

(def default-config
  {:min-age-days 180
   :min-tvl 10000
   :window :six-month
   :min-history-points 10
   :include-deposited? true
   :criteria [{:metric :sharpe :value "1.0"}
              {:metric :max-drawdown :value "25"}]
   :results-tab :pass
   :results-sort {:metric :sharpe :direction :desc}})

(def presets
  [{:id :steady-compounders
    :label "Steady compounders"
    :config {:min-age-days 180 :min-tvl 10000 :window :one-year
             :criteria [{:metric :sharpe :value "1.5"}
                        {:metric :max-drawdown :value "15"}
                        {:metric :longest-dd-days :value "45"}]}}
   {:id :low-drawdown
    :label "Low drawdown"
    :config {:min-age-days 90 :min-tvl 10000 :window :six-month
             :criteria [{:metric :max-drawdown :value "10"}
                        {:metric :volatility-ann :value "25"}]}}
   {:id :high-sharpe-large
    :label "High Sharpe, large"
    :config {:min-age-days 365 :min-tvl 50000 :window :all-time
             :criteria [{:metric :sharpe :value "2.0"}
                        {:metric :calmar :value "1.5"}]}}])

(def preset-by-id
  (into {} (map (juxt :id identity)) presets))

(defn- token
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim not-empty (str/replace #"^:" "") keyword)
    :else nil))

(defn parse-number
  [value]
  (cond
    (number? value) (when (js/isFinite value) value)
    (string? value) (let [text (str/trim value)]
                      (when (seq text)
                        (let [n (js/Number text)]
                          (when (js/isFinite n) n))))
    :else nil))

(defn- option-value
  [options value fallback]
  (let [values (set (map :value options))
        value* (if (keyword? fallback) (token value) (parse-number value))]
    (if (contains? values value*) value* fallback)))

(defn normalize-metric-key
  [value]
  (let [k (token value)]
    (when (contains? metric-def-by-key k) k)))

(defn- normalize-criterion
  [criterion]
  (when (map? criterion)
    (when-let [metric (normalize-metric-key (:metric criterion))]
      {:metric metric
       :value (let [v (:value criterion)]
                (cond
                  (string? v) v
                  (number? v) (str v)
                  :else (:default (metric-def-by-key metric))))})))

(defn- normalize-criteria
  [criteria]
  (->> (if (sequential? criteria) criteria [])
       (keep normalize-criterion)
       (reduce (fn [acc {:keys [metric] :as c}]
                 (if (some #(= metric (:metric %)) acc) acc (conj acc c)))
               [])))

(defn normalize-results-tab
  [value]
  (let [tab (token value)]
    (if (some #{tab} results-tabs) tab :pass)))

(defn- normalize-results-sort
  [sort-state]
  (let [metric (or (normalize-metric-key (:metric sort-state))
                   (#{:tvl :age} (token (:metric sort-state)))
                   :sharpe)]
    {:metric metric
     :direction (if (= :asc (token (:direction sort-state))) :asc :desc)}))

(defn normalize-config
  [config]
  (let [config* (merge default-config (when (map? config) config))]
    {:min-age-days (option-value age-options (:min-age-days config*) 180)
     :min-tvl (option-value tvl-options (:min-tvl config*) 10000)
     :window (option-value window-options (:window config*) :six-month)
     :min-history-points (option-value min-history-options (:min-history-points config*) 10)
     :include-deposited? (not (false? (:include-deposited? config*)))
     :criteria (normalize-criteria (:criteria config*))
     :results-tab (normalize-results-tab (:results-tab config*))
     :results-sort (normalize-results-sort (:results-sort config*))}))

(defn apply-preset
  [config preset-id]
  (if-let [{preset-config :config} (preset-by-id (token preset-id))]
    (normalize-config (merge (normalize-config config) preset-config))
    (normalize-config config)))

(defn universe-config-signature
  "The config fields that decide which vaults are fetched and how their
  metrics are computed. Thresholds are deliberately excluded: they are applied
  in the view and never require a fetch."
  [config]
  (select-keys (normalize-config config)
               [:min-age-days :min-tvl :window :include-deposited?]))

;; ---------------------------------------------------------------------------
;; Universe

(defn- in-base-universe?
  [row]
  (and (not= :child (:relationship-type row))
       (not (:is-closed? row))
       (seq (:vault-address row))))

(defn universe-row?
  [{:keys [min-age-days min-tvl include-deposited?]} row]
  (and (in-base-universe? row)
       (or (and (>= (or (:age-days row) 0) min-age-days)
                (>= (or (:tvl row) 0) min-tvl))
           (and include-deposited? (:has-deposit? row)))))

(defn base-universe-count
  [parsed-rows]
  (count (filter in-base-universe? parsed-rows)))

(defn universe-rows
  "Vaults the screen evaluates, largest TVL first, so the vaults most people
  care about land early in a run."
  [parsed-rows config]
  (let [config* (normalize-config config)]
    (->> parsed-rows
         (filter #(universe-row? config* %))
         (sort-by (fn [row] [(- (or (:tvl row) 0)) (:vault-address row)]))
         vec)))

;; ---------------------------------------------------------------------------
;; Cache freshness and estimates

(defn fresh-cache-entry?
  [saved-at-ms now-ms]
  (and (number? saved-at-ms)
       (number? now-ms)
       (< (- now-ms saved-at-ms) cache-ttl-ms)))

(defn estimate
  [addresses cache-index now-ms]
  (let [total (count addresses)
        cached (count (filter #(fresh-cache-entry? (get cache-index %) now-ms) addresses))
        to-fetch (- total cached)]
    {:total total
     :cached cached
     :to-fetch to-fetch
     :seconds (js/Math.round (/ (* to-fetch request-interval-ms) 1000))
     :megabytes (/ (js/Math.round (* to-fetch 0.028 10)) 10)}))

(defn format-duration
  [seconds]
  (let [s (max 0 (js/Math.round (or seconds 0)))]
    (cond
      (zero? s) "instant"
      (< s 60) (str s " s")
      :else (let [m (js/Math.floor (/ s 60))
                  r (mod s 60)]
              (if (zero? r)
                (str m " min")
                (str m " min " r " s"))))))

;; ---------------------------------------------------------------------------
;; Evaluation

(defn display-value
  "Engine value → the unit the user types thresholds in."
  [metric value]
  (when (number? value)
    (let [{:keys [unit abs?]} (metric-def-by-key metric)
          v (if (= unit :percent) (* 100 value) value)]
      (if abs? (js/Math.abs v) v))))

(defn- criterion-result
  [{:keys [metric value]} values]
  (let [threshold (parse-number value)
        {:keys [direction]} (metric-def-by-key metric)
        v (display-value metric (get values metric))]
    (cond
      (nil? threshold) nil
      (nil? v) {:metric metric :outcome :missing}
      :else
      (let [band (* near-miss-fraction (max (js/Math.abs threshold) 1e-9))
            pass? (if (= direction :min) (>= v threshold) (<= v threshold))
            gap (js/Math.abs (- v threshold))]
        {:metric metric
         :outcome (cond pass? :pass
                        (<= gap band) :near
                        :else :fail)
         :gap gap
         :value v
         :threshold threshold}))))

(defn evaluate
  "Classify one evaluated vault against the criteria.

  :thin — not enough history (below the minimum point count, a criterion
          metric suppressed by the tear-sheet quality gates, or the fetch
          failed);
  :pass — every criterion met;
  :near — exactly one criterion missed, within 10% of its threshold;
  :fail — otherwise."
  [{:keys [criteria min-history-points]} {:keys [values point-count error]}]
  (let [results (vec (keep #(criterion-result % values) criteria))
        missing (filter #(= :missing (:outcome %)) results)
        misses (filter #(#{:near :fail} (:outcome %)) results)]
    (cond
      error
      {:status :thin :reason :error :results results}

      (< (or point-count 0) (max 2 (or min-history-points 0)))
      {:status :thin :reason :history :results results}

      (seq missing)
      {:status :thin :reason :metric :metric (:metric (first missing)) :results results}

      (empty? misses)
      {:status :pass :results results}

      (and (= 1 (count misses))
           (= :near (:outcome (first misses))))
      {:status :near :miss (first misses) :results results}

      :else
      {:status :fail :results results})))

(defn sort-results
  [rows {:keys [metric direction]}]
  (let [value-of (fn [row]
                   (case metric
                     :tvl (:tvl row)
                     :age (:age-days row)
                     (get-in row [:values metric])))
        cmp (fn [a b]
              (let [va (value-of a)
                    vb (value-of b)]
                (cond
                  (and (nil? va) (nil? vb)) (compare (:vault-address a) (:vault-address b))
                  (nil? va) 1
                  (nil? vb) -1
                  :else (let [c (compare va vb)]
                          (if (zero? c)
                            (compare (:vault-address a) (:vault-address b))
                            (if (= direction :asc) c (- c)))))))]
    (vec (sort cmp rows))))
