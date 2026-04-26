(ns hyperopen.views.vaults.detail.chart-tooltip
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.chart.tooltip-core :as tooltip-core]
            [hyperopen.views.vaults.detail.format :as vf]))

(def ^:private tooltip-time-format-options
  {:hour "2-digit"
   :minute "2-digit"
   :hour12 false})

(def ^:private tooltip-date-format-options
  {:year "numeric"
   :month "short"
   :day "2-digit"})

(defn- format-tooltip-date
  [time-ms]
  (or (fmt/format-intl-date-time time-ms tooltip-date-format-options)
      "—"))

(defn- format-tooltip-time
  [time-ms]
  (or (fmt/format-intl-date-time time-ms tooltip-time-format-options)
      "--:--"))

(defn format-chart-tooltip-value
  [axis-kind value]
  (let [n (if (fmt/finite-number? value) value 0)]
    (case axis-kind
      :returns (or (fmt/format-signed-percent n {:decimals 2
                                                 :signed? true})
                   "0.00%")
      :pnl (vf/format-currency n {:missing "$0.00"})
      :account-value (vf/format-currency n {:missing "$0.00"})
      (vf/format-currency n {:missing "$0.00"}))))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- primary-series-label
  [series]
  (some (fn [{:keys [id label]}]
          (when (= :strategy id)
            (non-blank-text label)))
        (or series [])))

(defn- primary-metric-label
  [series metric-kind]
  (let [metric-label (tooltip-core/metric-label metric-kind)]
    (if-let [series-label (primary-series-label series)]
      (str series-label " " metric-label)
      metric-label)))

(defn build-chart-hover-tooltip
  [summary-time-range axis-kind hover series]
  (tooltip-core/build-hover-tooltip {:time-range summary-time-range
                                     :metric-kind axis-kind
                                     :hover-point (:point hover)
                                     :hovered-index (:index hover)
                                     :series series}
                                    {:format-date format-tooltip-date
                                     :format-time format-tooltip-time
                                     :format-metric-value format-chart-tooltip-value
                                     :metric-label-fn #(primary-metric-label series %)
                                     :format-benchmark-value #(format-chart-tooltip-value :returns %)}))
