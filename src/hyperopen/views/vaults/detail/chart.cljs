(ns hyperopen.views.vaults.detail.chart
  (:require [hyperopen.views.portfolio.vm.chart-math :as vm-chart-math]))

(def ^:private chart-y-tick-count
  4)

(def empty-y-ticks
  [{:value 3 :y-ratio 0}
   {:value 2 :y-ratio (/ 1 3)}
   {:value 1 :y-ratio (/ 2 3)}
   {:value 0 :y-ratio 1}])

(def ^:private default-strategy-series-stroke
  "#e7ecef")

(def ^:private account-value-series-stroke
  "#f7931a")

(def ^:private account-value-area-fill
  "rgba(247, 147, 26, 0.24)")

(def ^:private pnl-area-positive-fill
  "rgba(22, 214, 161, 0.24)")

(def ^:private pnl-area-negative-fill
  "rgba(237, 112, 136, 0.24)")

(def ^:private benchmark-series-strokes
  ["#f2cf66"
   "#7cc2ff"
   "#ff9d7c"
   "#8be28b"
   "#d8a8ff"
   "#ffdf8a"])

(defn strategy-series-stroke
  [selected-series]
  (case selected-series
    :account-value account-value-series-stroke
    default-strategy-series-stroke))

(defn benchmark-series-stroke
  [idx]
  (let [palette-size (count benchmark-series-strokes)]
    (if (pos? palette-size)
      (nth benchmark-series-strokes (mod idx palette-size))
      default-strategy-series-stroke)))

(defn chart-y-ticks
  [{:keys [min max step]}]
  (let [step* (if (and (number? step)
                       (pos? step))
                step
                (/ (vm-chart-math/non-zero-span min max) (dec chart-y-tick-count)))
        span (vm-chart-math/non-zero-span min max)]
    (mapv (fn [idx]
            (let [value (if (= idx (dec chart-y-tick-count))
                          min
                          (- max (* step* idx)))]
              {:value value
               :y-ratio (/ (- max value) span)}))
          (range chart-y-tick-count))))

(defn- value->y-ratio
  [{:keys [min max]} value]
  (let [span (vm-chart-math/non-zero-span min max)]
    (/ (- max value) span)))

(defn- annotate-time-presence
  [raw-series]
  (mapv (fn [{:keys [raw-points] :as series}]
          (assoc series
                 :raw-points (mapv (fn [{:keys [time-ms has-time-ms?] :as point}]
                                     (assoc point
                                            :has-time-ms? (if (some? has-time-ms?)
                                                            has-time-ms?
                                                            (vm-chart-math/finite-number? time-ms))))
                                   (vec (or raw-points [])))))
        (vec (or raw-series []))))

(defn build-chart-model
  [{:keys [selected-series raw-series]}]
  (let [raw-series* (annotate-time-presence raw-series)
        chart-domain-values (->> raw-series*
                                 (mapcat (fn [{:keys [raw-points]}]
                                           (map :value raw-points)))
                                 (filter number?)
                                 vec)
        domain (when (seq chart-domain-values)
                 (vm-chart-math/chart-domain chart-domain-values))
        time-domain (vm-chart-math/shared-time-domain (mapcat :raw-points raw-series*))
        series (mapv (fn [{:keys [id raw-points] :as entry}]
                       (let [points (if domain
                                      (vm-chart-math/normalize-chart-points raw-points
                                                                            domain
                                                                            time-domain)
                                      [])
                             is-strategy? (= id :strategy)
                             area-baseline-y-ratio (case selected-series
                                                     :pnl (when domain
                                                            (value->y-ratio domain 0))
                                                     :account-value 1
                                                     nil)
                             area-enabled? (and is-strategy?
                                                (not= selected-series :returns)
                                                (number? area-baseline-y-ratio))]
                         (cond-> (assoc entry
                                        :points points
                                        :has-data? (seq points))
                           (and is-strategy?
                                (= selected-series :account-value)
                                area-enabled?)
                           (assoc :area-fill account-value-area-fill)

                           (and is-strategy?
                                (= selected-series :pnl)
                                area-enabled?)
                           (assoc :area-positive-fill pnl-area-positive-fill
                                  :area-negative-fill pnl-area-negative-fill
                                  :zero-y-ratio area-baseline-y-ratio))))
                     raw-series*)
        strategy-series (or (some (fn [series-entry]
                                    (when (= :strategy (:id series-entry))
                                      series-entry))
                                  series)
                            {:points []
                             :has-data? false})
        chart-points (vec (or (:points strategy-series) []))]
    {:domain domain
     :y-ticks (if domain
                (chart-y-ticks domain)
                empty-y-ticks)
     :series series
     :strategy-series strategy-series
     :points chart-points}))
