(ns hyperopen.views.trading-chart.utils.chart-interop.numeric)

(defn coerce-number
  [value]
  (cond
    (number? value) (when-not (js/isNaN value) value)
    (string? value) (let [parsed (js/parseFloat value)]
                      (when-not (js/isNaN parsed) parsed))
    :else nil))

(defn coerce-number-values
  [values]
  (->> (or values [])
       (map coerce-number)
       (filter some?)
       vec))
