(ns hyperopen.views.vaults.detail-vm.chart-section
  (:require [hyperopen.portfolio.custom-range :as custom-range]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.vaults.detail.benchmarks :as benchmarks-model]
            [hyperopen.vaults.detail.performance :as performance-model]
            [hyperopen.vaults.detail.types :as detail-types]
            [hyperopen.views.chart.range-strip-model :as range-strip-model]
            [hyperopen.views.portfolio.vm.history :as vm-history]
            [hyperopen.views.vaults.detail.chart :as chart-model]
            [hyperopen.views.vaults.detail-vm.cache :as cache]
            [hyperopen.views.vaults.detail-vm.montecarlo :as montecarlo]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(def ^:private chart-timeframe-options
  [{:value :day
    :label "24H"}
   {:value :week
    :label "7D"}
   {:value :month
    :label "30D"}
   {:value :three-month
    :label "3M"}
   {:value :six-month
    :label "6M"}
   {:value :one-year
    :label "1Y"}
   {:value :two-year
    :label "2Y"}
   {:value :all-time
    :label "All-time"}])

(defn- resolve-chart-series
  [series-by-key selected-series]
  (let [selected* (vault-ui-state/normalize-vault-detail-chart-series selected-series)
        has-series? (fn [k]
                      (seq (get series-by-key k)))]
    (cond
      (= :returns selected*) :returns
      (has-series? selected*) selected*
      (has-series? :pnl) :pnl
      (has-series? :account-value) :account-value
      :else selected*)))

(defn- benchmark-history-pending?
  [selected-series activity-tab strategy-return-points selected-benchmark-coins benchmark-points-by-coin]
  (and (or (= selected-series :returns)
           (= activity-tab :performance-metrics))
       (seq strategy-return-points)
       (seq selected-benchmark-coins)
       (boolean
        (some (fn [coin]
                (and (seq coin)
                     (nil? (detail-types/vault-benchmark-address coin))
                     (empty? (get benchmark-points-by-coin coin))))
              selected-benchmark-coins))))

(defn- background-status-model
  [benchmark-history-pending?]
  (let [items (cond-> []
                benchmark-history-pending?
                (conj {:id :benchmark-history
                       :label "Benchmark history"}))]
    {:visible? (boolean (seq items))
     :title "Vault analytics are still syncing"
     :detail "The chart is ready. The remaining analytics will fill in automatically."
     :items items}))

(defn- build-benchmark-series
  [selected-series selected-benchmark-coins benchmark-label-by-coin benchmark-points-by-coin]
  (if (= selected-series :returns)
    (mapv (fn [idx coin]
            {:id (keyword (str "benchmark-" idx))
             :coin coin
             :label (or (get benchmark-label-by-coin coin)
                        coin)
             :stroke (chart-model/benchmark-series-stroke idx)
             :raw-points (vec (or (get benchmark-points-by-coin coin) []))})
          (range)
          selected-benchmark-coins)
    []))

(defn- build-benchmark-context
  [strategy-return-points benchmark-points-by-coin selected-benchmark-coins]
  (let [strategy-cumulative-rows (performance-model/cumulative-rows strategy-return-points)
        benchmark-cumulative-rows-by-coin
        (into {}
              (map (fn [coin]
                     [coin (performance-model/cumulative-rows
                            (get benchmark-points-by-coin coin))]))
              selected-benchmark-coins)]
    {:strategy-cumulative-rows strategy-cumulative-rows
     :benchmark-cumulative-rows-by-coin benchmark-cumulative-rows-by-coin
     :strategy-source-version (cache/sampled-series-source-version strategy-cumulative-rows)
     :benchmark-source-version-map (cache/benchmark-source-version-map benchmark-cumulative-rows-by-coin
                                                                       selected-benchmark-coins)}))

(defn- strip-rows
  "All-time account-value series as `{:time-ms :value}`, which is the context the
  range strip always shows regardless of the window currently selected."
  [summary]
  (->> (or (:accountValueHistory summary) [])
       (keep (fn [row]
               (let [time-ms (portfolio-metrics/history-point-time-ms row)
                     value (portfolio-metrics/history-point-value row)]
                 (when (and (number? time-ms)
                            (number? value))
                   {:time-ms time-ms
                    :value value}))))
       vec))

(defn build-vault-detail-chart-section
  [state snapshot-range activity-tab chart-series details-base viewer-details metrics-context
   vault-label range-ui]
  (let [details (merge (or details-base {})
                       (or viewer-details {}))
        summary (cache/cached-portfolio-summary details-base viewer-details snapshot-range)
        returns-history-context (performance-model/returns-history-context state details snapshot-range)
        returns-benchmark-selector (benchmarks-model/returns-benchmark-selector-model state)
        series-by-key (cache/cached-chart-series-data state summary)
        selected-series (resolve-chart-series series-by-key chart-series)
        strategy-return-points (vec (or (get (performance-model/chart-series-data
                                              state summary (:summary returns-history-context))
                                             :returns)
                                        []))
        strategy-raw-points (if (= selected-series :returns)
                              strategy-return-points
                              (vec (or (get series-by-key selected-series) [])))
        selected-benchmark-coins (vec (or (:selected-coins returns-benchmark-selector) []))
        benchmark-label-by-coin (or (:label-by-coin returns-benchmark-selector) {})
        benchmark-points-by-coin (cache/cached-benchmark-points-by-coin state snapshot-range
                                                                        selected-benchmark-coins
                                                                        strategy-return-points
                                                                        returns-history-context)
        benchmark-history-loading? (benchmark-history-pending? selected-series activity-tab
                                                               strategy-return-points
                                                               selected-benchmark-coins
                                                               benchmark-points-by-coin)
        benchmark-series (build-benchmark-series selected-series
                                                 selected-benchmark-coins
                                                 benchmark-label-by-coin
                                                 benchmark-points-by-coin)
        raw-series (cond-> [{:id :strategy
                             :label vault-label
                             :stroke (chart-model/strategy-series-stroke selected-series)
                             :raw-points strategy-raw-points}]
                     (seq benchmark-series)
                     (into benchmark-series))
        chart-model* (chart-model/build-chart-model {:selected-series selected-series
                                                     :raw-series raw-series})
        series (:series chart-model*)
        benchmark-context (build-benchmark-context strategy-return-points
                                                   benchmark-points-by-coin
                                                   selected-benchmark-coins)
        performance-metrics-base (cache/cached-performance-metrics-model state
                                                                         snapshot-range
                                                                         returns-benchmark-selector
                                                                         benchmark-context)
        performance-metrics (assoc performance-metrics-base
                                   :loading? (or benchmark-history-loading?
                                                 (:loading? performance-metrics-base)))
        return-for-range (:return-for-range metrics-context)
        month-return (:month-return metrics-context)
        ;; Build the Monte Carlo model only when its tab is active so the engine
        ;; runs lazily on tab open rather than on every vault detail re-render.
        ;; The vault MC tab has no range control of its own, so it always
        ;; resamples the vault's all-time realized returns (not the chart
        ;; range-windowed series) to maximize bootstrap samples.
        ;; The strip is context, not the selection: it always plots the vault's
        ;; whole history so a drag can reach any window, including one wider than
        ;; the preset currently applied.
        strip-source-rows (strip-rows (performance-model/portfolio-summary details :all-time))
        build-strip (fn [target]
                      (range-strip-model/build-model
                       {:rows strip-source-rows
                        :range (:custom range-ui)
                        :strip-open? (= (:strip-target range-ui) target)
                        :drag-mode (:drag-mode range-ui)}))
        ;; One range, two places it can be edited from: the chart card and the
        ;; tearsheet each render their own strip, and only the one that was
        ;; opened is visible. Both read the same window, so they cannot disagree.
        range-strip (build-strip :chart)
        metrics-range-strip (build-strip :metrics)
        strip-domain (:domain range-strip)
        ;; Re-opening the strip on an already-custom window seeds with that
        ;; window verbatim; recomputing the end from the domain would stretch the
        ;; selection to today behind the trader's back.
        custom-seed-to (or (:to (:custom range-ui)) (:to strip-domain))
        custom-seed-from (or (:from (:custom range-ui))
                             (vm-history/summary-window-cutoff-ms snapshot-range
                                                                  (:to strip-domain))
                             (:from strip-domain))
        custom-selector (fn [target]
                          {:active? (boolean (:custom range-ui))
                           :open? (= (:strip-target range-ui) target)
                           :label (:span-label range-strip)
                           :open-action :actions/open-vault-detail-custom-range
                           :open-args [target custom-seed-from custom-seed-to]
                           :clear-action :actions/set-vaults-snapshot-range
                           :clear-args [(:preset range-ui)]})
        monte-carlo (when (= activity-tab :monte-carlo)
                      (let [mc-summary (performance-model/portfolio-summary details :all-time)
                            mc-rows (performance-model/cumulative-rows
                                     (:returns (performance-model/chart-series-data state mc-summary)))]
                        (montecarlo/montecarlo-model
                         state
                         {:strategy-cumulative-rows mc-rows
                          :strategy-source-version (cache/sampled-series-source-version mc-rows)
                          :start-equity (:tvl metrics-context)
                          :window-label "All-time"
                          :vault-label vault-label})))]
    {:background-status (background-status-model benchmark-history-loading?)
     :monte-carlo monte-carlo
     :snapshot-range snapshot-range
     :snapshot {:day (return-for-range :day)
                :week (return-for-range :week)
                :month month-return
                :all-time (return-for-range :all-time)}
     :performance-metrics (assoc performance-metrics
                                 :vault-label vault-label
                                 :timeframe-options chart-timeframe-options
                                 ;; The PRESET keyword, which is what highlights
                                 ;; the ON row and what the panel gates its whole
                                 ;; chip on. The CUSTOM window travels separately
                                 ;; in `:custom-range` below, so the chip can label
                                 ;; itself with the window actually in force.
                                 :selected-timeframe (:preset range-ui)
                                 :custom-range (custom-selector :metrics)
                                 :range-strip metrics-range-strip
                                 :timeframe-menu-open? (true? (get-in state [:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?])))
     :range-strip range-strip
     :chart {:axis-kind (case selected-series
                          :pnl :pnl
                          :returns :returns
                          :account-value :account-value
                          :account-value)
             :series-tabs [{:value :returns
                            :label "Returns"}
                           {:value :account-value
                            :label "Account Value"}
                           {:value :pnl
                            :label "PNL"}]
             :timeframe-options chart-timeframe-options
             :timeframe-menu-open? (true? (get-in state [:vaults-ui :detail-chart-timeframe-dropdown-open?]))
             ;; The MENU shows the preset; the data pipeline above already ran on
             ;; the effective window. Handing the menu a custom range map would
             ;; make `timeframe-token` return nil and mislabel the chip.
             :selected-timeframe (:preset range-ui)
             ;; The window on screen, for the tooltip's timestamp format. The
             ;; menu label above needs the preset; the tooltip needs the truth.
             :tooltip-timeframe (or (custom-range/span-preset (:custom range-ui))
                                    (:preset range-ui))
             :custom-range (custom-selector :chart)
             :range-strip range-strip
             :selected-series selected-series
             :returns-benchmark returns-benchmark-selector
             :strategy-window returns-history-context
             :y-ticks (:y-ticks chart-model*)
             :points (:points chart-model*)
             :series series}}))
