(ns hyperopen.views.trading-chart.core-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.core :as chart-core]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn- collect-all-classes [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (concat (classes-from-tag (first node))
              (class-values (:class attrs))
              (mapcat collect-all-classes children)))

    (seq? node)
    (mapcat collect-all-classes node)

    :else []))

(defn- class-strings [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) [class-attr]
    (sequential? class-attr) (mapcat class-strings class-attr)
    :else []))

(defn- collect-class-strings [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (concat (class-strings (:class attrs))
              (mapcat collect-class-strings children)))

    (seq? node)
    (mapcat collect-class-strings node)

    :else []))

(defn- spaced-class-string? [value]
  (and (string? value)
       (<= 2 (count (remove str/blank? (str/split value #"\s+"))))))

(defn- collect-background-colors [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          bg-color (get-in attrs [:style :background-color])]
      (concat (if (string? bg-color) [bg-color] [])
              (mapcat collect-background-colors children)))

    (seq? node)
    (mapcat collect-background-colors node)

    :else []))

(deftest chart-top-menu-uses-base-background-class-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible false
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible false
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible false
                                                          :active-indicators {}}})
        classes (set (collect-all-classes menu))
        bg-colors (set (collect-background-colors menu))]
    (is (contains? classes "bg-base-100"))
    (is (not (contains? bg-colors "rgb(30, 41, 55)")))))

(deftest chart-canvas-uses-base-background-class-and-no-inline-legacy-color-test
  (let [canvas (chart-core/chart-canvas []
                                       :candlestick
                                       {}
                                       {:symbol "BTC"
                                        :timeframe-label "1D"
                                        :venue "Hyperopen"
                                        :candle-data []})
        classes (set (class-values (get-in canvas [1 :class])))
        bg-colors (set (collect-background-colors canvas))]
    (is (contains? classes "bg-base-100"))
    (is (not (contains? bg-colors "rgb(30, 41, 55)")))))

(deftest chart-top-menu-dropdowns-use-high-z-opaque-tokenized-classes-test
  (let [menu (chart-core/chart-top-menu {:chart-options {:timeframes-dropdown-visible true
                                                          :selected-timeframe :1d
                                                          :chart-type-dropdown-visible true
                                                          :selected-chart-type :candlestick
                                                          :indicators-dropdown-visible true
                                                          :active-indicators {}}})
        classes (set (collect-all-classes menu))
        class-strings* (collect-class-strings menu)]
    (is (contains? classes "z-[120]"))
    (is (contains? classes "isolate"))
    (is (contains? classes "bg-base-100"))
    (is (contains? classes "opacity-100"))
    (is (not-any? spaced-class-string? class-strings*))))
