(ns hyperopen.views.autocorrelation-plot-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.autocorrelation-plot :as autocorrelation-plot]
            [hyperopen.views.funding-rate-plot :as funding-rate-plot]))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- approx=
  [left right epsilon]
  (<= (js/Math.abs (- left right))
      epsilon))

(defn- collect-rect-attrs-by-key
  [node data-key]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [tag (first n)
                    attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))
                    child-results (mapcat walk children)]
                (if (and (= :rect tag)
                         (contains? attrs data-key))
                  (cons attrs child-results)
                  child-results))

              (seq? n)
              (mapcat walk n)

              :else
              []))]
    (vec (walk node))))

(defn- collect-bar-attrs
  [node]
  (collect-rect-attrs-by-key node :data-lag))

(deftest autocorrelation-plot-renders-title-axis-and-bars-test
  (let [series [{:lag-days 1 :value 0.62}
                {:lag-days 2 :value -0.18}
                {:lag-days 3 :value nil :undefined? true}
                {:lag-days 4 :value 0.02}
                {:lag-days 5 :value 0.35}]
        node (autocorrelation-plot/autocorrelation-plot series)
        strings (set (collect-strings node))
        bars (collect-bar-attrs node)]
    (is (contains? strings "Past Rate Correlation"))
    (is (contains? strings "Lookback (days)"))
    (is (contains? strings "+1"))
    (is (contains? strings "0"))
    (is (contains? strings "-1"))
    (is (= 5 (count bars)))
    (is (= [1 2 3 4 5]
           (mapv :data-lag bars)))))

(deftest autocorrelation-plot-sorts-and-clamps-series-values-test
  (let [series [{:lag-days 3 :value 2}
                {:lag-days 1 :value -2}
                {:lag-days 2 :value 0.5}]
        node (autocorrelation-plot/autocorrelation-plot series)
        bars (collect-bar-attrs node)
        values (set (map :data-autocorrelation-value bars))]
    (is (= [1 2 3]
           (mapv :data-lag bars)))
    (is (contains? values "-1"))
    (is (contains? values "0.5"))
    (is (contains? values "1"))))

(deftest funding-rate-plot-renders-title-axis-and-bars-test
  (let [series [{:day-index 1 :daily-rate 0.0004}
                {:day-index 2 :daily-rate -0.0002}
                {:day-index 3 :daily-rate nil}
                {:day-index 4 :daily-rate 0.0001}
                {:day-index 5 :daily-rate 0.0003}]
        node (funding-rate-plot/funding-rate-plot series)
        strings (set (collect-strings node))
        bars (collect-rect-attrs-by-key node :data-day)]
    (is (contains? strings "Rate History"))
    (is (contains? strings "Day (oldest to newest)"))
    (is (contains? strings "0%"))
    (is (= 5 (count bars)))
    (is (= [1 2 3 4 5]
           (mapv :data-day bars)))))

(deftest funding-rate-plot-sorts-and-annualizes-series-values-test
  (let [series [{:day-index 3 :daily-rate 0.001}
                {:day-index 1 :daily-rate -0.001}
                {:day-index 2 :daily-rate "0.0005"}
                {:day-index 4 :daily-rate "bad"}]
        node (funding-rate-plot/funding-rate-plot series)
        bars (collect-rect-attrs-by-key node :data-day)
        value-by-day (into {}
                           (map (fn [attrs]
                                  [(:data-day attrs)
                                   (js/parseFloat (or (:data-funding-rate-value attrs) ""))]))
                           bars)]
    (is (= [1 2 3 4]
           (mapv :data-day bars)))
    (is (approx= -36.5 (get value-by-day 1) 1e-9))
    (is (approx= 18.25 (get value-by-day 2) 1e-9))
    (is (approx= 36.5 (get value-by-day 3) 1e-9))
    (is (js/isNaN (get value-by-day 4)))))
