(ns hyperopen.domain.trading.indicators.result)

(defn line-series
  [id values]
  {:id id
   :series-type :line
   :values values})

(defn histogram-series
  [id values]
  {:id id
   :series-type :histogram
   :values values})

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
