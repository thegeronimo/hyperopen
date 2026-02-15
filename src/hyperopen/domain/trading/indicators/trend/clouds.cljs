(ns hyperopen.domain.trading.indicators.trend.clouds
  (:require [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.math :as imath]
            [hyperopen.domain.trading.indicators.result :as result]))

(def ^:private parse-period imath/parse-period)
(def ^:private field-values imath/field-values)
(def ^:private normalize-values imath/normalize-values)

(defn- fit-series-length
  [values size]
  (let [series (vec values)
        current-size (count series)]
    (cond
      (= current-size size) series
      (> current-size size) (subvec series 0 size)
      :else (into series (repeat (- size current-size) nil)))))

(defn calculate-ichimoku-cloud
  [data params]
  (let [short (parse-period (:short params) 9 2 200)
        medium (parse-period (:medium params) 26 2 300)
        long (parse-period (:long params) 52 2 400)
        close-shift (parse-period (:close params) 26 1 300)
        size (count data)
        result (math-engine/ichimoku-cloud (field-values data :high)
                                            (field-values data :low)
                                            (field-values data :close)
                                            {:short short
                                             :medium medium
                                             :long long
                                             :close close-shift})
        tenkan (fit-series-length (normalize-values (:tenkan result) {:zero-as-nil? true}) size)
        kijun (fit-series-length (normalize-values (:kijun result) {:zero-as-nil? true}) size)
        ssa (fit-series-length (normalize-values (:ssa result) {:zero-as-nil? true}) size)
        ssb (fit-series-length (normalize-values (:ssb result) {:zero-as-nil? true}) size)
        lagging-span (fit-series-length (normalize-values (:laggingSpan result) {:zero-as-nil? true}) size)]
    (result/indicator-result :ichimoku-cloud
                             :overlay
                             [(result/line-series :tenkan tenkan)
                              (result/line-series :kijun kijun)
                              (result/line-series :ssa ssa)
                              (result/line-series :ssb ssb)
                              (result/line-series :lagging lagging-span)])))
