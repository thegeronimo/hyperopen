(ns hyperopen.views.trading-chart.utils.indicator-view-adapter
  (:require [hyperopen.domain.trading.indicators.math :as imath]))

(def ^:private line-series-meta
  {[:week-52-high-low :high] {:name "52W High" :color "#10b981"}
   [:week-52-high-low :low] {:name "52W Low" :color "#ef4444"}
   [:atr :atr] {:name "ATR" :color "#14b8a6"}
   [:bollinger-bands :upper] {:name "BB Upper" :color "#22c55e"}
   [:bollinger-bands :basis] {:name "BB Basis" :color "#f59e0b"}
   [:bollinger-bands :lower] {:name "BB Lower" :color "#ef4444"}
   [:sma :sma] {:name "MA" :color "#38bdf8"}
   [:alma :alma] {:name "ALMA" :color "#f59e0b"}
   [:aroon :aroon-up] {:name "Aroon Up" :color "#22c55e"}
   [:aroon :aroon-down] {:name "Aroon Down" :color "#ef4444"}
   [:adx :adx] {:name "ADX" :color "#a855f7"}
   [:balance-of-power :bop] {:name "BOP" :color "#22c55e"}
   [:advance-decline :ad-bars] {:name "A/D" :color "#06b6d4"}})

(def ^:private histogram-series-meta
  {[:awesome-oscillator :ao] {:name "AO"
                              :positive-color "#10b981"
                              :negative-color "#ef4444"}
   [:accelerator-oscillator :ac] {:name "AC"
                                   :positive-color "#10b981"
                                   :negative-color "#ef4444"}})

(defn point
  [time value]
  (if (imath/finite-number? value)
    {:time time :value value}
    {:time time}))

(defn points-from-values
  [time-values indicator-values]
  (mapv point time-values indicator-values))

(defn- line-meta
  [indicator-type series-id]
  (merge {:name (name series-id)
          :color "#38bdf8"
          :line-width 2}
         (get line-series-meta [indicator-type series-id])))

(defn line-series
  [indicator-type series-id time-values indicator-values]
  (let [{:keys [name color line-width]} (line-meta indicator-type series-id)]
    {:id series-id
     :name name
     :series-type :line
     :color color
     :line-width line-width
     :data (points-from-values time-values indicator-values)}))

(defn- histogram-point
  [positive-color negative-color time value]
  (if (imath/finite-number? value)
    {:time time
     :value value
     :color (if (neg? value) negative-color positive-color)}
    {:time time}))

(defn histogram-series
  [indicator-type series-id time-values indicator-values]
  (let [{:keys [name positive-color negative-color]}
        (merge {:name (name series-id)
                :positive-color "#10b981"
                :negative-color "#ef4444"}
               (get histogram-series-meta [indicator-type series-id]))]
    {:id series-id
     :name name
     :series-type :histogram
     :data (mapv (fn [time value]
                   (histogram-point positive-color negative-color time value))
                 time-values
                 indicator-values)}))

(defn indicator-result
  ([indicator-type pane series]
   {:type indicator-type
    :pane pane
    :series series})
  ([indicator-type pane series markers]
   (cond-> {:type indicator-type
            :pane pane
            :series series}
     (seq markers) (assoc :markers markers))))

(defn- project-series
  [indicator-type time-values {:keys [id series-type values]}]
  (case series-type
    :histogram (histogram-series indicator-type id time-values values)
    :line (line-series indicator-type id time-values values)
    nil))

(defn project-domain-indicator
  [data {:keys [type pane series markers]}]
  (let [time-values (imath/times data)
        projected-series (->> series
                              (keep (fn [series-def]
                                      (project-series type time-values series-def)))
                              vec)]
    (indicator-result type pane projected-series markers)))
