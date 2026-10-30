(ns hyperopen.vol-range.domain.report-intervals-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.domain.report-intervals :as iv]))

(deftest normalize-merges-overlapping-and-touching-intervals-test
  (is (= [[0 9] [12 15]] (iv/normalize [[5 9] [0 5] [12 15] [13 14]])))
  (is (= [] (iv/normalize [[5 5] [7 3] nil [1] ["a" 2]])) "empty and malformed intervals are dropped")
  (is (= [[0 10]] (iv/union [[0 4]] [[4 10]] [[2 3]]))))

(deftest subtract-and-uncovered-test
  (is (= [[0 2] [5 6] [9 10]] (iv/subtract [[0 10]] [[2 5] [6 9]])))
  (is (= [] (iv/subtract [[2 5]] [[0 10]])))
  (is (= [[0 10]] (iv/subtract [[0 10]] [[10 20] [-5 0]])) "touching intervals remove nothing")
  (is (= [[3 4] [8 10]] (iv/uncovered [[0 3] [4 8]] [0 10]))))

(deftest intersect-test
  (is (= [[2 3] [5 6]] (iv/intersect [[0 3] [5 9]] [[2 6]])))
  (is (= [] (iv/intersect [[0 3]] [[3 6]])) "half-open intervals that touch share no point"))

(deftest covers-is-half-open-test
  (testing "[a, b) needs every point up to but not including b"
    (is (iv/covers? [[0 10]] [0 10]))
    (is (iv/covers? [[0 4] [4 10]] [0 10]))
    (is (not (iv/covers? [[0 9]] [0 10])))
    (is (not (iv/covers? [[1 10]] [0 10])))
    (is (iv/covers? [] [5 5]) "an empty interval is trivially covered"))
  (is (iv/contains-point? [[0 10]] 0))
  (is (not (iv/contains-point? [[0 10]] 10))))
