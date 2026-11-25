(ns hyperopen.views.trading-chart.chart-type-dropdown-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.chart-type-dropdown :as chart-type-dropdown]))

(defn- class-strings [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) [class-attr]
    (sequential? class-attr) (mapcat class-strings class-attr)
    :else []))

(defn- collect-class-strings [node]
  (letfn [(walk [n]
            (cond
              (vector? n)
              (let [attrs (when (map? (second n)) (second n))
                    children (if attrs (drop 2 n) (drop 1 n))]
                (concat (class-strings (:class attrs))
                        (mapcat walk children)))

              (seq? n)
              (mapcat walk n)

              :else []))]
    (walk node)))

(defn- spaced-class-string? [value]
  (and (string? value)
       (<= 2 (count (remove str/blank? (str/split value #"\s+"))))))

(deftest hidden-state-emits-no-space-separated-class-string-test
  (let [view (chart-type-dropdown/chart-type-dropdown {:selected-chart-type :candlestick
                                                       :chart-type-dropdown-visible false})
        dropdown-class (set (get-in view [2 1 :class]))]
    (is (contains? dropdown-class "bg-base-100"))
    (is (contains? dropdown-class "z-[120]"))
    (is (contains? dropdown-class "isolate"))
    (is (contains? dropdown-class "opacity-0"))
    (is (contains? dropdown-class "scale-y-95"))
    (is (contains? dropdown-class "-translate-y-2"))
    (is (contains? dropdown-class "pointer-events-none"))
    (is (not-any? spaced-class-string? (collect-class-strings view)))))

(deftest visible-state-emits-no-space-separated-class-string-test
  (let [view (chart-type-dropdown/chart-type-dropdown {:selected-chart-type :candlestick
                                                       :chart-type-dropdown-visible true})
        dropdown-class (set (get-in view [2 1 :class]))]
    (is (contains? dropdown-class "bg-base-100"))
    (is (contains? dropdown-class "z-[120]"))
    (is (contains? dropdown-class "isolate"))
    (is (contains? dropdown-class "opacity-100"))
    (is (contains? dropdown-class "scale-y-100"))
    (is (contains? dropdown-class "translate-y-0"))
    (is (not-any? spaced-class-string? (collect-class-strings view)))))

(deftest chart-type-sections-match-hyperliquid-order-test
  (let [labels (mapv :label (mapcat :items chart-type-dropdown/chart-type-sections))]
    (is (= ["Bars"
            "Candles"
            "Hollow candles"
            "Line"
            "Line with markers"
            "Step line"
            "Area"
            "HLC area"
            "Baseline"
            "Columns"
            "High-low"
            "Heikin Ashi"]
           labels))))

(deftest chart-type-dropdown-normalizes-legacy-histogram-to-columns-test
  (let [view (chart-type-dropdown/chart-type-dropdown {:selected-chart-type :histogram
                                                       :chart-type-dropdown-visible false})]
    (is (= "Columns" (get-in view [1 3 1])))))

(deftest chart-type-menu-scrolls-within-the-chart-panel-test
  (let [classes (set (collect-class-strings
                      (chart-type-dropdown/chart-type-dropdown {:selected-chart-type :candlestick
                                                                :chart-type-dropdown-visible true})))]
    (is (contains? classes "overflow-y-auto"))
    (is (contains? classes "max-h-[max(8rem,calc(100vh_-_clamp(12rem,30vh,23rem)_-_17rem))]"))))
