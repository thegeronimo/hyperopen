(ns hyperopen.domain.trading.indicators.contracts)

(def ^:private valid-panes #{:overlay :separate})
(def ^:private valid-series-types #{:line :histogram})
(def ^:private valid-marker-kinds #{:fractal-high :fractal-low})

(defn valid-indicator-input?
  [data params]
  (and (sequential? data)
       (every? map? data)
       (map? params)))

(defn- valid-series?
  [series _expected-length]
  (and (map? series)
       (keyword? (:id series))
       (contains? valid-series-types (:series-type series))
       (vector? (:values series))))

(defn- valid-marker?
  [marker]
  (and (map? marker)
       (string? (:id marker))
       (number? (:time marker))
       (or (and (keyword? (:kind marker))
                (contains? valid-marker-kinds (:kind marker))
                (or (nil? (:price marker))
                    (number? (:price marker))))
           (and (string? (:position marker))
                (string? (:shape marker))))))

(defn valid-indicator-result?
  [result indicator-type expected-length]
  (and (map? result)
       (= indicator-type (:type result))
       (contains? valid-panes (:pane result))
       (vector? (:series result))
       (every? #(valid-series? % expected-length) (:series result))
       (or (nil? (:markers result))
           (and (vector? (:markers result))
                (every? valid-marker? (:markers result))))))

(defn enforce-indicator-result
  [indicator-type expected-length result]
  (when (valid-indicator-result? result indicator-type expected-length)
    result))
