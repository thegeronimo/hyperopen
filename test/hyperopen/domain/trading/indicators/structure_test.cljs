(ns hyperopen.domain.trading.indicators.structure-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.domain.trading.indicators.structure :as structure]))

(def fractal-candles
  [{:time 1 :open 8 :high 10 :low 6 :close 9 :volume 100}
   {:time 2 :open 10 :high 12 :low 7 :close 11 :volume 110}
   {:time 3 :open 11 :high 16 :low 9 :close 12 :volume 120}
   {:time 4 :open 12 :high 13 :low 8 :close 10 :volume 115}
   {:time 5 :open 10 :high 11 :low 7 :close 9 :volume 108}
   {:time 6 :open 9 :high 12 :low 6 :close 10 :volume 125}
   {:time 7 :open 10 :high 10 :low 3 :close 8 :volume 130}
   {:time 8 :open 8 :high 13 :low 5 :close 11 :volume 122}
   {:time 9 :open 11 :high 12 :low 6 :close 10 :volume 118}])

(deftest williams-fractal-semantic-markers-test
  (let [result (structure/calculate-structure-indicator :williams-fractal fractal-candles {})
        markers (:markers result)
        bearish (first markers)
        bullish (second markers)]
    (is (= :williams-fractal (:type result)))
    (is (= :overlay (:pane result)))
    (is (= 0 (count (:series result))))
    (is (= 2 (count markers)))
    (is (every? keyword? (map :kind markers)))
    (is (every? number? (map :time markers)))
    (is (every? number? (map :price markers)))
    (is (every? nil? (map :shape markers)))
    (is (every? nil? (map :color markers)))
    (is (= :fractal-high (:kind bearish)))
    (is (= 3 (:time bearish)))
    (is (= 16 (:price bearish)))
    (is (= :fractal-low (:kind bullish)))
    (is (= 7 (:time bullish)))
    (is (= 3 (:price bullish)))))
