(ns hyperopen.views.trading-chart.derived-cache-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trading-chart.derived-cache :as derived-cache]))

(deftest memoized-indicator-outputs-short-circuits-when-no-active-indicators-test
  (binding [derived-cache/*calculate-indicator* (fn [& _]
                                                  (throw (js/Error. "should not calculate")))]
    (is (= {:indicators-data []
            :indicator-series []
            :indicator-markers []}
           (derived-cache/memoized-indicator-outputs [{:time 1 :close 100}]
                                                     :1d
                                                     {}
                                                     false)))))

(deftest memoized-indicator-outputs-invalidates-empty-runtime-cache-on-load-test
  (derived-cache/reset-derived-cache!)
  (let [calculate-calls (atom [])
        candle-data [{:time 1 :close 100}
                     {:time 2 :close 101}]
        active-indicators {:sma {:period 20}}
        unloaded-result (binding [derived-cache/*calculate-indicator*
                                  (fn [indicator-type data config]
                                    (swap! calculate-calls conj [indicator-type data config])
                                    {:series [{:id indicator-type :data data}]
                                     :markers []})]
                          (derived-cache/memoized-indicator-outputs candle-data
                                                                    :1d
                                                                    active-indicators
                                                                    false))
        loaded-result (binding [derived-cache/*calculate-indicator*
                                (fn [indicator-type data config]
                                  (swap! calculate-calls conj [indicator-type data config])
                                  {:series [{:id indicator-type :data data}]
                                   :markers []})]
                        (derived-cache/memoized-indicator-outputs candle-data
                                                                  :1d
                                                                  active-indicators
                                                                  true))]
    (is (= [] (:indicators-data unloaded-result)))
    (is (= 1 (count (:indicators-data loaded-result))))
    (is (= [[:sma candle-data {:period 20}]]
           @calculate-calls))))
