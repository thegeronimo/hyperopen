(ns hyperopen.views.chart.tooltip-core
  (:require [clojure.string :as str]))

(def ^:private default-benchmark-stroke
  "#e6edf2")

(def ^:private positive-value-classes
  ["text-[#16d6a1]"])

(def ^:private negative-value-classes
  ["text-[#ff7b72]"])

(def ^:private neutral-value-classes
  ["text-[#e6edf2]"])

(defn- finite-number?
  [value]
  (and (number? value)
       (js/isFinite value)))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn returns-metric?
  [metric-kind]
  (= metric-kind :returns))

(defn metric-label
  [metric-kind]
  (case metric-kind
    :account-value "Account Value"
    :pnl "PNL"
    :returns "Returns"
    "Value"))

(defn metric-value-classes
  [metric-kind value]
  (let [n (if (finite-number? value) value 0)]
    (case metric-kind
      :account-value ["text-[#ff9f1a]"]
      :pnl (cond
             (pos? n) positive-value-classes
             (neg? n) negative-value-classes
             :else neutral-value-classes)
      :returns (cond
                 (pos? n) positive-value-classes
                 (neg? n) negative-value-classes
                 :else neutral-value-classes)
      neutral-value-classes)))

(defn- benchmark-series?
  [{:keys [id]}]
  (and (keyword? id)
       (not= id :strategy)))

(defn- benchmark-series-name
  [{:keys [id coin]}]
  (or (non-blank-text coin)
      (name id)))

(defn- benchmark-series-label
  [{:keys [label] :as series}]
  (or (non-blank-text label)
      (benchmark-series-name series)))

(defn- benchmark-series-stroke
  [fallback-stroke stroke]
  (or (non-blank-text stroke)
      fallback-stroke))

(defn- latest-prior-benchmark-point
  [hover-time-ms series]
  (let [points (vec (or (:points series) []))
        point-count (count points)]
    (loop [idx 0
           latest nil]
      (if (>= idx point-count)
        latest
        (let [{point-time-ms :time-ms :as point} (nth points idx)]
          (if (and (finite-number? point-time-ms)
                   (<= point-time-ms hover-time-ms))
            (recur (inc idx) point)
            latest))))))

(defn- benchmark-point-value
  [hover-time-ms hovered-index series]
  (or (some-> (when (finite-number? hover-time-ms)
                (latest-prior-benchmark-point hover-time-ms series))
              :value)
      (get-in series [:points hovered-index :value])))

(defn- benchmark-row
  [hover-time-ms hovered-index format-benchmark-value fallback-stroke {:keys [stroke] :as series}]
  (let [value (benchmark-point-value hover-time-ms hovered-index series)]
    (when (finite-number? value)
      (let [series-name (benchmark-series-name series)]
        {:coin series-name
         :label (benchmark-series-label series)
         :value (format-benchmark-value value)
         :stroke (benchmark-series-stroke fallback-stroke stroke)}))))

(defn benchmark-rows
  [{:keys [metric-kind hover-time-ms hovered-index series]} {:keys [benchmark-enabled?
                                                                    format-benchmark-value
                                                                    fallback-stroke]
                                                             :or {benchmark-enabled? returns-metric?
                                                                  fallback-stroke default-benchmark-stroke}}]
  (if (and (benchmark-enabled? metric-kind)
           (or (number? hovered-index)
               (finite-number? hover-time-ms)))
    (into [] (comp (filter benchmark-series?)
                   (keep #(benchmark-row hover-time-ms
                                         hovered-index
                                         format-benchmark-value
                                         fallback-stroke
                                         %)))
          series)
    []))

(defn build-hover-tooltip
  [{:keys [time-range metric-kind hover-point hovered-index series]}
   {:keys [format-date
           format-time
           format-metric-value
           format-benchmark-value
           metric-label-fn
           metric-value-classes-fn
           benchmark-enabled?]
    :or {metric-label-fn metric-label
         metric-value-classes-fn metric-value-classes
         benchmark-enabled? returns-metric?}}]
  (when (map? hover-point)
    (let [time-ms (:time-ms hover-point)
          value (:value hover-point)]
      {:timestamp (if (= time-range :day)
                    (format-time time-ms)
                    (format-date time-ms))
       :metric-label (metric-label-fn metric-kind)
       :metric-value (format-metric-value metric-kind value)
       :value-classes (metric-value-classes-fn metric-kind value)
       :benchmark-values (benchmark-rows {:metric-kind metric-kind
                                          :hover-time-ms time-ms
                                          :hovered-index hovered-index
                                          :series series}
                                         {:benchmark-enabled? benchmark-enabled?
                                          :format-benchmark-value format-benchmark-value})})))
