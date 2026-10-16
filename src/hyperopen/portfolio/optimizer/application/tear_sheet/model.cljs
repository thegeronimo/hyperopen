(ns hyperopen.portfolio.optimizer.application.tear-sheet.model
  "View model of the hypothetical tear sheet: the optimizer's target weights
  backtested over each leg's own history, with the vault page's metrics
  engine and the same benchmarks a vault page offers."
  (:require [hyperopen.portfolio.optimizer.application.instrument-labels :as instrument-labels]
            [hyperopen.portfolio.optimizer.application.tear-sheet.backtest :as backtest]
            [hyperopen.portfolio.optimizer.application.tear-sheet.sources :as sources]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.ids :as ids]
            [hyperopen.vaults.detail.performance :as performance]))

(def ranges
  [{:value :month :label "30D" :days 30}
   {:value :three-month :label "3M" :days 91}
   {:value :six-month :label "6M" :days 182}
   {:value :one-year :label "1Y" :days 365}
   {:value :two-year :label "2Y" :days 730}
   {:value :all-time :label "All" :days nil}])

(def benchmark-defs
  [{:key :equal-weight :coin "equal-weight" :label "Equal weight" :stroke "#9aa7ad"}
   {:key :hlp :coin "hlp" :label "HLP" :stroke "#4fd1c1"}
   {:key :btc :coin "btc" :label "BTC" :stroke "#e2b545"}])

(def strategy-stroke "#a493ff")

(def defaults
  {:range :one-year
   :mode :returns
   :rebalance :daily
   :missing :spread
   :profit-share? true
   :benchmarks #{:equal-weight :hlp}
   :tab :metrics})

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
                     (= k :benchmarks) (assoc acc k (if (set? v) v (:benchmarks defaults)))
                     (= k :profit-share?) (assoc acc k (boolean v))
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

(defn- benchmark-runs
  [state legs opts win]
  (let [k (count legs)]
    {:equal-weight (when (> k 1)
                     (backtest/run (merge win
                                          (select-keys opts [:rebalance :missing :profit-share?])
                                          {:legs (mapv #(assoc % :weight (/ 1 k)) legs)})))
     :hlp (single-leg-run :hlp (sources/hlp-rows state) (sources/hlp-fee-rate state) opts win)
     :btc (single-leg-run :btc (sources/btc-rows state) 0 {:profit-share? false} win)}))

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
            selected (filterv #(and (contains? (:benchmarks opts) (:key %))
                                    (seq (:rows (get benches (:key %)))))
                              benchmark-defs)
            coverage (leg-coverage legs (assoc win :start-ms (:start-ms run)) (:contribution run))
            sparse-weight (reduce + 0 (keep (fn [{:keys [gap-days weight]}]
                                              (when (and (number? gap-days) (> gap-days sparse-gap-days))
                                                (js/Math.abs weight)))
                                            coverage))
            metrics (performance/performance-metrics-model
                     {:selected-coins (mapv :coin selected)
                      :label-by-coin (into {} (map (juxt :coin :label)) selected)}
                     (:rows run)
                     (into {} (map (fn [{:keys [key coin]}] [coin (:rows (get benches key))])) selected))
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
         :benchmarks (mapv (fn [{:keys [key] :as b}]
                             (assoc b
                                    :on? (contains? (:benchmarks opts) key)
                                    :available? (boolean (seq (:rows (get benches key))))))
                           benchmark-defs)
         :raw-series (into [{:id :strategy
                             :label "This target"
                             :stroke strategy-stroke
                             :raw-points (points strategy-rows)}]
                           (map-indexed (fn [i {:keys [key label stroke]}]
                                          {:id (keyword (str "benchmark-" i))
                                           :coin (name key)
                                           :label label
                                           :stroke stroke
                                           :raw-points (points (series-rows (:rows (get benches key))))}))
                           selected)
         :metrics (update metrics :groups with-estimated-rows (pos? sparse-weight))}))))

(def ^:private memo
  (volatile! nil))

(defn- cache-key
  [state result opts]
  {:result (select-keys result [:instrument-ids :target-weights])
   :opts opts
   :universe (get-in state contracts/draft-universe-path)
   :history (get-in state contracts/history-data-path)
   :hlp (get-in state [:vaults :benchmark-details-by-address sources/hlp-address])
   :btc (get-in state [:candles "BTC" sources/btc-candle-interval])})

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
    (let [opts (assoc defaults :range :one-year :mode :returns)
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
