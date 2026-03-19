(ns hyperopen.domain.trading.indicators.flow-branch-coverage-test
  (:require-macros [hyperopen.test-support.redefs :refer [with-direct-redefs]])
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.indicators.flow.money :as money]
            [hyperopen.domain.trading.indicators.flow.volume :as volume]
            [hyperopen.domain.trading.indicators.math-engine :as math-engine]
            [hyperopen.domain.trading.indicators.support :as support]))

(def ^:private candles
  [{:time 1 :open 100 :high 100 :low 100 :close 100 :volume 5}
   {:time 2 :open 109 :high 110 :low 109 :close 109.5 :volume 7}
   {:time 3 :open 96 :high 101 :low 90 :close 95 :volume 11}
   {:time 4 :open 90 :high 106 :low 80 :close 90 :volume 13}
   {:time 5 :open 90 :high 90 :low 90 :close 90 :volume 17}])

(deftest accumulation-distribution-covers-zero-range-and-nonzero-branches-test
  (let [result (volume/calculate-accumulation-distribution candles nil)
        values (get-in result [:series 0 :values])]
    (is (= :accumulation-distribution (:type result)))
    (is (= 5 (count values)))
    (is (= 0 (first values)))
    (is (number? (second values)))
    (is (not= (second values) (nth values 2)))))

(deftest accumulative-swing-index-covers-cond-arms-and-zero-guard-branches-test
  (let [result (volume/calculate-accumulative-swing-index candles nil)
        values (get-in result [:series 0 :values])]
    (is (= :accumulative-swing-index (:type result)))
    (is (= 5 (count values)))
    (is (= 0 (first values)))
    (is (some pos? (subvec values 1 4)))
    (is (= (nth values 3) (nth values 4)))))

(deftest net-volume-covers-up-down-and-flat-branches-test
  (let [series-data [{:time 1 :close 100 :volume 5}
                     {:time 2 :close 101 :volume 7}
                     {:time 3 :close 100 :volume 11}
                     {:time 4 :close 100 :volume 13}]
        result (volume/calculate-net-volume series-data nil)
        values (get-in result [:series 0 :values])]
    (is (= :net-volume (:type result)))
    (is (= [0 7 -11 0] values))))

(deftest volume-oscillator-parse-period-branches-are-covered-test
  (let [captured-opts (atom nil)]
    (with-direct-redefs [math-engine/percentage-volume-oscillator
                         (fn [_volume-values opts]
                           (reset! captured-opts opts)
                           {:pvoResult [1 2 3]
                            :signal [3 2 1]
                            :histogram [0 1 0]})]
      (let [result (volume/calculate-volume-oscillator support/sample-candles
                                                       {:fast "-5"
                                                        :slow "9999"
                                                        :signal "not-a-number"})]
        (is (= {:fast 1 :slow 400 :signal 9}
               @captured-opts))
        (is (= :volume-oscillator (:type result)))
        (is (= 3 (count (:series result))))))))

(deftest money-family-parse-period-fallback-and-clamp-branches-test
  (let [captured (atom {})]
    (with-direct-redefs [math-engine/chaikin-money-flow
                         (fn [_high _low _close _volume opts]
                           (swap! captured assoc :cmf opts)
                           [0.1 0.2 0.3])
                         math-engine/chaikin-oscillator
                         (fn [_high _low _close _volume opts]
                           (swap! captured assoc :cmo opts)
                           {:adResult [1 2 3]
                            :cmoResult [3 2 1]})
                         math-engine/ease-of-movement
                         (fn [_high _low _volume opts]
                           (swap! captured assoc :eom opts)
                           [1 1 1])
                         math-engine/elders-force-index
                         (fn [_close _volume opts]
                           (swap! captured assoc :efi opts)
                           [2 2 2])
                         math-engine/money-flow-index
                         (fn [_high _low _close _volume opts]
                           (swap! captured assoc :mfi opts)
                           [3 3 3])]
      (testing "nil params use defaults"
        (is (= :chaikin-money-flow
               (:type (money/calculate-chaikin-money-flow support/sample-candles nil))))
        (is (= :ease-of-movement
               (:type (money/calculate-ease-of-movement support/sample-candles nil)))))
      (testing "string and out-of-range params are parsed and clamped"
        (is (= :chaikin-oscillator
               (:type (money/calculate-chaikin-oscillator support/sample-candles
                                                          {:fast "-8" :slow "999"}))))
        (is (= :elders-force-index
               (:type (money/calculate-elders-force-index support/sample-candles
                                                          {:period "not-a-number"}))))
        (is (= :money-flow-index
               (:type (money/calculate-money-flow-index support/sample-candles
                                                        {:period 9999}))))
        (is (= {:period 20} (:cmf @captured)))
        (is (= {:period 14} (:eom @captured)))
        (is (= {:fast 1 :slow 400} (:cmo @captured)))
        (is (= {:period 13} (:efi @captured)))
        (is (= {:period 200} (:mfi @captured)))))))
