(ns hyperopen.views.portfolio.vm.chart-tooltip
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.chart.tooltip-core :as tooltip-core]
            [hyperopen.views.portfolio.vm.vaults-scope :as vaults-scope]))

(def ^:private tooltip-currency-format-options
  {:style "currency"
   :currency "USD"
   :maximumFractionDigits 0
   :minimumFractionDigits 0})

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
      "--"))

(defn- format-tooltip-time
  [time-ms]
  (or (fmt/format-intl-date-time time-ms tooltip-time-format-options)
      "--:--"))

(defn- format-tooltip-value
  [selected-tab value]
  (if (= selected-tab :returns)
    (or (fmt/format-signed-percent value
                                   {:decimals 2
                                    :signed? true})
        "0.00%")
    (let [n (if (number? value) value 0)
          n* (if (== n -0) 0 n)]
      (or (fmt/format-intl-number n* tooltip-currency-format-options)
          "$0"))))

(defn build-chart-hover-tooltip
  ([summary-time-range selected-tab hover series]
   (build-chart-hover-tooltip summary-time-range selected-tab hover series nil))
  ([summary-time-range selected-tab hover series summary-scope]
   (tooltip-core/build-hover-tooltip {:time-range summary-time-range
                                      :metric-kind selected-tab
                                      :hover-point (:point hover)
                                      :hovered-index (:index hover)
                                      :series series}
                                     {:format-date format-tooltip-date
                                      :format-time format-tooltip-time
                                      :format-metric-value format-tooltip-value
                                      :format-benchmark-value #(format-tooltip-value :returns %)
                                      :metric-label-fn (fn [metric-kind]
                                                         (or (vaults-scope/metric-label summary-scope metric-kind)
                                                             (tooltip-core/metric-label metric-kind)))})))
