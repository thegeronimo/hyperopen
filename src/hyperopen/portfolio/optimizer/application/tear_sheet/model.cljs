(ns hyperopen.portfolio.optimizer.application.tear-sheet.model
  "View model of the hypothetical tear sheet: the optimizer's target weights
  backtested over each leg's own history, with the vault page's metrics
  engine and the same benchmarks a vault page offers."
  (:require [hyperopen.portfolio.optimizer.application.instrument-labels :as instrument-labels]
            [hyperopen.portfolio.optimizer.application.tear-sheet.backtest :as backtest]
            [hyperopen.portfolio.optimizer.application.tear-sheet.sources :as sources]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.ids :as ids]
            [clojure.string :as str]
            [hyperopen.vaults.detail.benchmarks :as vault-benchmarks]
            [hyperopen.vaults.detail.performance :as performance]))

(def ranges
  [{:value :month :label "30D" :days 30}
   {:value :three-month :label "3M" :days 91}
   {:value :six-month :label "6M" :days 182}
   {:value :one-year :label "1Y" :days 365}
   {:value :two-year :label "2Y" :days 730}
   {:value :all-time :label "All" :days nil}])

(def equal-weight-value "equal-weight")

(def equal-weight-stroke "#9aa7ad")

(def benchmark-strokes
  ["#4fd1c1" "#e2b545" "#7cc2ff" "#ff9d7c" "#8be28b" "#f2a7d8"])

(def strategy-stroke "#a493ff")

(def max-compare
  "Picked benchmarks besides equal weight."
  5)

(def defaults
  {:range :one-year
   :mode :returns
   :rebalance :daily
   :missing :spread
   :profit-share? true
   :equal-weight? true
   :compare [(sources/hlp-value)]
   :benchmark-search ""
   :benchmark-open? false
   :show-all-weights? false
   :tab :metrics})

(def boolean-options
  #{:profit-share? :equal-weight? :benchmark-open? :show-all-weights?})

(def option-values
  {:range (set (map :value ranges))
   :mode #{:returns :drawdown}
   :rebalance backtest/rebalance-options
   :missing backtest/missing-options
   :tab #{:metrics :holdings :method}})

(def sparse-gap-days
  "Median sample spacing above which a leg counts as interpolated."
  1.5)

(def ^:private return-metric-keys
  ;; Endpoint returns are exact at sample dates; everything that reads the
  ;; day-to-day path is estimated when sparse legs are interpolated.
  #{:time-in-market :cumulative-return :cagr :mtd :m3 :m6 :ytd :y1 :y3-ann
    :y5-ann :y10-ann :all-time-ann})

(defn options
  [state]
  (let [stored (get-in state contracts/ui-tear-sheet-path)]
    (reduce-kv (fn [acc k v]
                 (let [allowed (get option-values k)]
                   (cond
                     (= k :compare) (assoc acc k (if (vector? v) (vec (take max-compare (filter string? v))) (:compare defaults)))
                     (= k :benchmark-search) (assoc acc k (if (string? v) v ""))
                     (contains? boolean-options k) (assoc acc k (boolean v))
                     (and allowed (contains? allowed v)) (assoc acc k v)
                     :else acc)))
               defaults
               (or stored {}))))

(defn vault-run?
  "True when a solved result holds at least one vault: the only runs that
  show the tear sheet."
  [result]
  (boolean (and (= :solved (:status result))
                (some #(ids/vault-address-from-value %) (:instrument-ids result)))))

(defn target-legs
  "Legs with a non-zero target weight, heaviest first."
  [state result]
  (let [labels (instrument-labels/labels-by-instrument
                (get-in state contracts/draft-universe-path)
                (:instrument-ids result))]
    (->> (map vector (:instrument-ids result) (:target-weights result))
         (keep (fn [[id w]]
                 (when (and (number? w) (> (js/Math.abs w) 1e-6))
                   (let [{:keys [rows vault? address fee-rate]} (sources/instrument-rows state id)]
                     {:id id
                      :weight w
                      :label (get labels id id)
                      :vault? vault?
                      :address address
                      :fee-rate fee-rate
                      :rows rows}))))
         (sort-by #(- (js/Math.abs (:weight %))))
         vec)))

(defn- window
  [legs range-value]
  (let [lasts (keep #(:time-ms (peek (:rows %))) legs)
        firsts (keep #(:time-ms (first (:rows %))) legs)]
    (when (and (seq lasts) (seq firsts))
      (let [end-ms (apply max lasts)
            days (:days (some #(when (= range-value (:value %)) %) ranges))
            start-ms (if days
                       (- end-ms (* days backtest/day-ms))
                       (apply min firsts))]
        {:start-ms start-ms :end-ms end-ms}))))

(defn- single-leg-run
  [id rows fee-rate opts {:keys [start-ms end-ms]}]
  (when (seq rows)
    (backtest/run {:legs [{:id id :weight 1 :rows rows :fee-rate fee-rate}]
                   :start-ms start-ms
                   :end-ms end-ms
                   :rebalance :hold
                   :missing :common
                   :profit-share? (:profit-share? opts)})))

(defn benchmark-options
  "{:value :label} for every benchmark the vault page offers."
  [state]
  (vault-benchmarks/benchmark-options state))

(defn- label-by-value
  [state]
  (into {equal-weight-value "Equal weight"} (map (juxt :value :label)) (benchmark-options state)))

(defn- benchmark-runs
  "{value run} for equal weight (when on) and each picked benchmark."
  [state legs opts win]
  (let [k (count legs)]
    (cond-> (into {}
                  (map (fn [value]
                         (let [{:keys [rows fee-rate]} (sources/benchmark-rows state value)]
                           [value (single-leg-run value rows fee-rate opts win)])))
                  (:compare opts))
      (and (:equal-weight? opts) (> k 1))
      (assoc equal-weight-value
             (backtest/run (merge win
                                  (select-keys opts [:rebalance :missing :profit-share?])
                                  {:legs (mapv #(assoc % :weight (/ 1 k)) legs)}))))))

(defn- benchmark-entries
  "Chips in display order with label, colour and data availability."
  [state opts benches]
  (let [labels (label-by-value state)]
    (into (if (:equal-weight? opts)
            [{:value equal-weight-value :label "Equal weight" :stroke equal-weight-stroke
              :builtin? true :available? (boolean (seq (:rows (get benches equal-weight-value))))}]
            [])
          (map-indexed (fn [i value]
                         {:value value
                          :label (str/replace (get labels value value) #" \(VAULT\)$" "")
                          :stroke (nth benchmark-strokes (mod i (count benchmark-strokes)))
                          :available? (boolean (seq (:rows (get benches value))))}))
          (:compare opts))))

(defn benchmark-search-model
  "The compare search box: candidates matching the query, minus picks."
  [state opts]
  (let [query (str/lower-case (str/trim (or (:benchmark-search opts) "")))
        picked (set (:compare opts))
        candidates (->> (benchmark-options state)
                        (remove #(contains? picked (:value %)))
                        (filter #(or (str/blank? query)
                                     (str/includes? (str/lower-case (str (:label %))) query)
                                     (str/includes? (str/lower-case (str (:value %))) query)))
                        (take 40)
                        vec)]
    {:query (:benchmark-search opts)
     :open? (:benchmark-open? opts)
     :full? (>= (count picked) max-compare)
     :candidates candidates
     :top-value (:value (first candidates))}))

(defn- points
  [rows]
  (mapv (fn [[t v]] {:time-ms t :value v}) rows))

(defn- own-return
  [rows start-ms end-ms]
  (let [from (max start-ms (or (:time-ms (first rows)) start-ms))
        a (backtest/value-at rows from)
        b (backtest/value-at rows end-ms)]
    (when (and (number? a) (number? b) (pos? a))
      (- (/ b a) 1))))

(defn- leg-coverage
  [legs {:keys [start-ms end-ms]} contribution]
  (mapv (fn [{:keys [rows id] :as leg}]
          (let [first-ms (:time-ms (first rows))]
            (assoc (dissoc leg :rows)
                   :first-ms first-ms
                   :joins-in-window? (and (number? first-ms) (> first-ms start-ms))
                   :gap-days (backtest/median-gap-days rows start-ms end-ms)
                   :own-return (own-return rows start-ms end-ms)
                   :contribution (get contribution id)
                   :has-history? (boolean (seq rows)))))
        legs))

(defn- with-estimated-rows
  [groups sparse?]
  (if-not sparse?
    groups
    (mapv (fn [group]
            (update group :rows
                    (fn [rows]
                      (mapv (fn [{:keys [key] :as row}]
                              (if (or (contains? return-metric-keys key)
                                      (= :low-confidence (:portfolio-status row)))
                                row
                                (assoc row
                                       :portfolio-status :low-confidence
                                       :portfolio-reason :interpolated-samples)))
                            rows))))
          groups)))

(defn- compute
  [state result opts]
  (let [legs (target-legs state result)
        win (window legs (:range opts))
        run (when win
              (backtest/run (merge win
                                   (select-keys opts [:rebalance :missing :profit-share?])
                                   {:legs legs})))]
    (when (seq (:rows run))
      (let [benches (benchmark-runs state legs opts win)
            entries (benchmark-entries state opts benches)
            selected (filterv :available? entries)
            coverage (leg-coverage legs (assoc win :start-ms (:start-ms run)) (:contribution run))
            sparse-weight (reduce + 0 (keep (fn [{:keys [gap-days weight]}]
                                              (when (and (number? gap-days) (> gap-days sparse-gap-days))
                                                (js/Math.abs weight)))
                                            coverage))
            metrics (performance/performance-metrics-model
                     {:selected-coins (mapv :value selected)
                      :label-by-coin (into {} (map (juxt :value :label)) selected)}
                     (:rows run)
                     (into {} (map (fn [{:keys [value]}] [value (:rows (get benches value))])) selected))
            strategy-rows (if (= :drawdown (:mode opts))
                            (backtest/drawdown-rows (:rows run))
                            (:rows run))
            series-rows (fn [rows] (if (= :drawdown (:mode opts)) (backtest/drawdown-rows rows) rows))]
        {:legs coverage
         :run run
         :window win
         :sparse? (pos? sparse-weight)
         :sparse-weight sparse-weight
         :max-gap-days (reduce max 0 (keep :gap-days coverage))
         :benchmarks entries
         :raw-series (into [{:id :strategy
                             :label "This target"
                             :stroke strategy-stroke
                             :raw-points (points strategy-rows)}]
                           (map-indexed (fn [i {:keys [value label stroke]}]
                                          {:id (keyword (str "benchmark-" i))
                                           :coin value
                                           :label label
                                           :stroke stroke
                                           :raw-points (points (series-rows (:rows (get benches value))))}))
                           selected)
         :metrics (update metrics :groups with-estimated-rows (pos? sparse-weight))}))))

(def ^:private memo
  (volatile! nil))

(defn- cache-key
  [state result opts]
  {:result (select-keys result [:instrument-ids :target-weights])
   ;; Typing in the compare search must not recompute the backtest.
   :opts (if (map? opts) (dissoc opts :benchmark-search :benchmark-open? :show-all-weights?) opts)
   :universe (get-in state contracts/draft-universe-path)
   :history (get-in state contracts/history-data-path)
   :vault-benchmarks (get-in state [:vaults :benchmark-details-by-address])
   :candles (into {}
                  (map (fn [v] [v (get-in state [:candles v sources/benchmark-candle-interval])]))
                  (if (map? opts) (:compare opts) []))})

(defn backtest-model
  "Memoized backtest + metrics for `result` under `opts` (nil when no leg has
  history in the window)."
  [state result opts]
  (let [k (cache-key state result opts)
        hit @memo]
    (if (and hit (= k (:key hit)))
      (:value hit)
      (let [v (compute state result opts)]
        (vreset! memo {:key k :value v})
        v))))

(defn- metric-value
  [model k]
  (get-in model [:metrics :values k]))

(defn hero-stats
  [model]
  {:cumulative-return (metric-value model :cumulative-return)
   :volatility (metric-value model :volatility-ann)
   :sharpe (metric-value model :sharpe)
   :sortino (metric-value model :sortino)
   :max-drawdown (metric-value model :max-drawdown)
   :longest-dd-days (metric-value model :longest-dd-days)})

(defn card-model
  "The Recommendation tab's compact card: the default method over 1Y."
  [state result]
  (when (vault-run? result)
    (let [opts (assoc defaults :range :one-year :mode :returns :compare [(sources/hlp-value)])
          model (backtest-model state result opts)]
      {:model model
       :stats (when model (hero-stats model))
       :legs (count (target-legs state result))})))

(def ^:private memo-card
  (volatile! nil))

(defn cached-card-model
  "Separate one-entry memo so the card and the tear sheet (different
  options) do not evict each other."
  [state result]
  (let [k (cache-key state result :card)
        hit @memo-card]
    (if (and hit (= k (:key hit)))
      (:value hit)
      (let [v (card-model state result)]
        (vreset! memo-card {:key k :value v})
        v))))
