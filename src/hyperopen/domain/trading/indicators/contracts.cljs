(ns hyperopen.domain.trading.indicators.contracts)

(def ^:private valid-panes #{:overlay :separate})
(def ^:private valid-series-types #{:line :histogram})

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

(defn valid-indicator-result?
  [result indicator-type expected-length]
  (and (map? result)
       (= indicator-type (:type result))
       (contains? valid-panes (:pane result))
       (vector? (:series result))
       (every? #(valid-series? % expected-length) (:series result))
       (or (nil? (:markers result))
           (vector? (:markers result)))))

(defn enforce-indicator-result
  [indicator-type expected-length result]
  (when (valid-indicator-result? result indicator-type expected-length)
    result))
