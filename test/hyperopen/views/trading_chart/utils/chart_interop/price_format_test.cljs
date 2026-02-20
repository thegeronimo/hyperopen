(ns hyperopen.views.trading-chart.utils.chart-interop.price-format-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.utils.chart-interop.price-format :as price-format]))

(deftest infer-series-price-format-prefers-metadata-decimals-and-clamps-range-test
  (let [extract-called? (atom false)
        from-string (js->clj (price-format/infer-series-price-format
                              [{:time 1 :value 1}]
                              (fn [_]
                                (reset! extract-called? true)
                                [1 2 3])
                              {:price-decimals "5.9"})
                             :keywordize-keys true)
        from-negative (js->clj (price-format/infer-series-price-format
                                [{:time 1 :value 1}]
                                (fn [_] [1 2 3])
                                {:price-decimals -3})
                               :keywordize-keys true)
        from-large (js->clj (price-format/infer-series-price-format
                             [{:time 1 :value 1}]
                             (fn [_] [1 2 3])
                             {:price-decimals "99"})
                            :keywordize-keys true)]
    (is (false? @extract-called?))
    (is (= {:type "price" :precision 5 :minMove 0.00001}
           from-string))
    (is (= 0 (:precision from-negative)))
    (is (== 1 (:minMove from-negative)))
    (is (= 12 (:precision from-large)))
    (is (== 1e-12 (:minMove from-large)))))

(deftest infer-series-price-format-infers-from-prices-and-falls-back-to-default-test
  (let [captured (atom [])
        from-positive (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                4)]
                        (js->clj (price-format/infer-series-price-format
                                  [{:time 1}]
                                  (fn [_] [0 -5 "2.5" "bad" 10]))
                                 :keywordize-keys true))
        from-absolute (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                3)]
                        (js->clj (price-format/infer-series-price-format
                                  [{:time 1}]
                                  (fn [_] [-8 "-2.5"]))
                                 :keywordize-keys true))
        from-fallback (with-redefs [fmt/infer-price-decimals (fn [price]
                                                                (swap! captured conj price)
                                                                nil)]
                        (js->clj (price-format/infer-series-price-format
                                  []
                                  (fn [_] ["bad" nil]))
                                 :keywordize-keys true))]
    (is (= [2.5 2.5 nil] @captured))
    (is (= 4 (:precision from-positive)))
    (is (== 0.0001 (:minMove from-positive)))
    (is (= 3 (:precision from-absolute)))
    (is (== 0.001 (:minMove from-absolute)))
    (is (= 2 (:precision from-fallback)))
    (is (== 0.01 (:minMove from-fallback)))))

