(ns hyperopen.views.trading-chart.utils.indicator-view-adapter
  (:require [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.polymorphism :as poly]
            [hyperopen.views.trading-chart.utils.indicator-style-catalog :as styles]))

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
         (get styles/line-series-meta [indicator-type series-id])))

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
               (get styles/histogram-series-meta [indicator-type series-id]))]
    {:id series-id
     :name name
     :series-type :histogram
     :data (mapv (fn [time value]
                   (histogram-point positive-color negative-color time value))
                 time-values
                 indicator-values)}))

(defmethod poly/series-operation [:view/project :line]
  [_ _ indicator-type series-id time-values indicator-values]
  (line-series indicator-type series-id time-values indicator-values))

(defmethod poly/series-operation [:view/project :histogram]
  [_ _ indicator-type series-id time-values indicator-values]
  (histogram-series indicator-type series-id time-values indicator-values))

(defn- project-marker-with-kind
  [indicator-type kind marker]
  (when-let [style (get styles/marker-meta [indicator-type kind])]
    (merge {:id (:id marker)
            :time (:time marker)}
           style)))

(defmethod poly/marker-operation [:view/project :fractal-high]
  [_ _ indicator-type marker]
  (project-marker-with-kind indicator-type :fractal-high marker))

(defmethod poly/marker-operation [:view/project :fractal-low]
  [_ _ indicator-type marker]
  (project-marker-with-kind indicator-type :fractal-low marker))

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
  (poly/series-operation :view/project
                         series-type
                         indicator-type
                         id
                         time-values
                         values))

(defn- project-marker
  [indicator-type marker]
  (poly/marker-operation :view/project (:kind marker) indicator-type marker))

(defn project-domain-indicator
  [data {:keys [type pane series markers]}]
  (let [time-values (imath/times data)
        projected-series (->> series
                              (keep (fn [series-def]
                                      (project-series type time-values series-def)))
                              vec)
        projected-markers (when (seq markers)
                            (->> markers
                                 (keep #(project-marker type %))
                                 vec))]
    (indicator-result type pane projected-series projected-markers)))
