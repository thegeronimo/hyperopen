(ns hyperopen.views.trading-chart.utils.chart-interop.price-format-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.utils.chart-interop.price-format :as price-format]))

(deftest infer-series-price-format-prefers-metadata-decimals-and-clamps-range-test
  (let [extract-called? (atom false)
        from-string (price-format/infer-series-price-format
                     [{:time 1 :value 1}]
                     (fn [_]
                       (reset! extract-called? true)
                       [1 2 3])
                     {:price-decimals "5.9"})
        from-negative (price-format/infer-series-price-format
                       [{:time 1 :value 1}]
                       (fn [_] [1 2 3])
                       {:price-decimals -3})
        from-large (price-format/infer-series-price-format
                    [{:time 1 :value 1}]
                    (fn [_] [1 2 3])
                    {:price-decimals "99"})
        from-string-map (js->clj from-string :keywordize-keys true)
        from-negative-map (js->clj from-negative :keywordize-keys true)
        from-large-map (js->clj from-large :keywordize-keys true)]
    (is (false? @extract-called?))
    (is (= "custom" (:type from-string-map)))
    (is (== 0.00001 (:minMove from-string-map)))
    (is (= "1,234.00000" ((.-formatter ^js from-string) 1234)))
    (is (== 1 (:minMove from-negative-map)))
    (is (= "1,234" ((.-formatter ^js from-negative) 1234)))
    (is (== 1e-12 (:minMove from-large-map)))
    (is (= "1.000000000000" ((.-formatter ^js from-large) 1)))))

(deftest infer-series-price-format-infers-from-prices-and-falls-back-to-default-test
  (let [captured (atom [])
        from-positive (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                4)]
                        (price-format/infer-series-price-format
                         [{:time 1}]
                         (fn [_] [0 -5 "2.5" "bad" 10])))
        from-absolute (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                3)]
                        (price-format/infer-series-price-format
                         [{:time 1}]
                         (fn [_] [-8 "-2.5"])))
        from-fallback (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                nil)]
                        (price-format/infer-series-price-format
                         []
                         (fn [_] ["bad" nil])))
        from-positive-map (js->clj from-positive :keywordize-keys true)
        from-absolute-map (js->clj from-absolute :keywordize-keys true)
        from-fallback-map (js->clj from-fallback :keywordize-keys true)]
    (is (= [2.5 2.5 nil] @captured))
    (is (== 0.0001 (:minMove from-positive-map)))
    (is (= "10.0000" ((.-formatter ^js from-positive) 10)))
    (is (== 0.001 (:minMove from-absolute-map)))
    (is (= "-8.000" ((.-formatter ^js from-absolute) -8)))
    (is (== 0.01 (:minMove from-fallback-map)))
    (is (= "1,234.50" ((.-formatter ^js from-fallback) 1234.5)))))

(deftest outcome-price-unit-relabels-without-changing-the-scale-test
  (let [percent (price-format/infer-series-price-format [] (fn [_] []) {:price-decimals 5
                                                                        :price-unit :percent})
        cents (price-format/infer-series-price-format [] (fn [_] []) {:price-decimals 5
                                                                      :price-unit :cents})]
    (is (= "custom" (.-type ^js percent)))
    (is (== 0.00001 (.-minMove ^js percent)) "minMove stays in dollars")
    (is (= "20.9%" ((.-formatter ^js percent) 0.209)))
    (is (= "20.9¢" ((.-formatter ^js cents) 0.209)))
    (is (= ["20.0%" "20.5%" "21.0%"]
           (vec ((.-tickmarksFormatter ^js percent) #js [0.2 0.205 0.21]))))
    (is (= ["20.85¢" "20.90¢"]
           (vec ((.-tickmarksFormatter ^js cents) #js [0.2085 0.209]))))))

(deftest non-outcome-price-format-has-no-unit-test
  (let [plain (price-format/infer-series-price-format [] (fn [_] []) {:price-decimals 5})]
    (is (= "0.20900" ((.-formatter ^js plain) 0.209)))
    (is (undefined? (.-tickmarksFormatter ^js plain)))))
