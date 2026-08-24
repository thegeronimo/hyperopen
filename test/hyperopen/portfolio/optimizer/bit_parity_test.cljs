(ns hyperopen.portfolio.optimizer.bit-parity-test
  "Tests for the comparator the numeric-kernel parity suites are built on.

  `bit-parity/bit=` is the oracle for math_test, risk_covariance_parity_test,
  the codec fusion suite and risk_ledoit_wolf_test. An oracle that is itself
  wrong makes every suite that depends on it silently vacuous, so it gets its
  own coverage -- particularly for the two cases it exists to handle, where its
  answer must differ from `=`."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.bit-parity :as bit-parity]))

(deftest bit-equality-differs-from-clojure-equality-exactly-where-it-should-test
  ;; The two reasons this namespace exists.
  (is (false? (= js/NaN js/NaN)) "sanity: = calls two NaNs different")
  (is (true? (bit-parity/bit= js/NaN js/NaN)) "bit= must call two NaNs the same")

  (is (true? (= 0.0 -0.0)) "sanity: = calls the two zeros the same")
  (is (false? (bit-parity/bit= 0.0 -0.0)) "bit= must call the two zeros different")
  ;; Why the zero case is worth catching at all: the sign survives into later
  ;; arithmetic even though it prints identically.
  (is (not= (/ 1 0.0) (/ 1 -0.0))))

(deftest bit-equality-agrees-with-clojure-equality-everywhere-else-test
  (doseq [value [0 1 -1 0.5 1e300 1e-300 js/Infinity js/-Infinity
                 "text" :keyword nil true false
                 [] {} #{} [1 2 3] {:a 1} #{1 2}
                 [[1 2] [3 4]] {:a {:b [1 2]}}]]
    (is (true? (bit-parity/bit= value value))
        (str "bit= should be reflexive on " (pr-str value)))))

(deftest bit-equality-walks-nested-structures-test
  (is (bit-parity/bit= [[js/NaN 1] [2 3]] [[js/NaN 1] [2 3]]))
  (is (not (bit-parity/bit= [[js/NaN 1] [2 3]] [[js/NaN 1] [2 4]])))
  (is (not (bit-parity/bit= [[0.0]] [[-0.0]])))
  (is (bit-parity/bit= {:covariance [[js/NaN]]} {:covariance [[js/NaN]]}))
  (is (not (bit-parity/bit= {:covariance [[0.0]]} {:covariance [[-0.0]]})))
  (is (not (bit-parity/bit= {:a 1} {:a 1 :b 2})) "differing key sets are unequal")
  (is (not (bit-parity/bit= [1 2] [1 2 3])) "differing lengths are unequal")
  (is (not (bit-parity/bit= [1 2] {:a 1})) "different shapes are unequal"))

(deftest bit-equality-does-not-confuse-numbers-with-other-types-test
  (is (not (bit-parity/bit= 1 "1")))
  (is (not (bit-parity/bit= 0 nil)))
  (is (not (bit-parity/bit= js/NaN nil)))
  (is (not (bit-parity/bit= 1 true))))

(deftest first-difference-locates-the-divergence-test
  (is (nil? (bit-parity/first-difference [[1 2] [3 4]] [[1 2] [3 4]])))
  (is (nil? (bit-parity/first-difference {:a [js/NaN]} {:a [js/NaN]})))
  (is (re-find #"index 1,0" (bit-parity/first-difference [[1 2] [3 4]] [[1 2] [9 4]])))
  (is (re-find #"length 2 vs 3" (bit-parity/first-difference [1 2] [1 2 3])))
  (is (re-find #"keys" (bit-parity/first-difference {:a 1} {:b 1})))
  ;; The case a plain "not equal" message is useless for: both sides print the
  ;; same, so the message has to say so explicitly.
  (is (re-find #"differing bits"
               (bit-parity/first-difference [0.0] [-0.0]))))
