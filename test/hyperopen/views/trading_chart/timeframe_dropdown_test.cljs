(ns hyperopen.views.trading-chart.timeframe-dropdown-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.timeframe-dropdown :as timeframe-dropdown]))

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
  (let [view (timeframe-dropdown/timeframe-dropdown {:selected-timeframe :1m
                                                     :timeframes-dropdown-visible false})
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
  (let [view (timeframe-dropdown/timeframe-dropdown {:selected-timeframe :1m
                                                     :timeframes-dropdown-visible true})
        dropdown-class (set (get-in view [2 1 :class]))]
    (is (contains? dropdown-class "bg-base-100"))
    (is (contains? dropdown-class "z-[120]"))
    (is (contains? dropdown-class "isolate"))
    (is (contains? dropdown-class "opacity-100"))
    (is (contains? dropdown-class "scale-y-100"))
    (is (contains? dropdown-class "translate-y-0"))
    (is (not-any? spaced-class-string? (collect-class-strings view)))))

(deftest dropdown-transition-class-is-tokenized-collection-test
  (let [hidden-class (get-in (timeframe-dropdown/timeframe-dropdown
                              {:selected-timeframe :1m
                               :timeframes-dropdown-visible false})
                             [2 1 :class])
        visible-class (get-in (timeframe-dropdown/timeframe-dropdown
                               {:selected-timeframe :1m
                                :timeframes-dropdown-visible true})
                              [2 1 :class])
        tokenized-collection? (fn [class-value]
                                (and (sequential? class-value)
                                     (every? string? class-value)
                                     (not-any? spaced-class-string? class-value)))]
    (is (tokenized-collection? hidden-class))
    (is (tokenized-collection? visible-class))))
