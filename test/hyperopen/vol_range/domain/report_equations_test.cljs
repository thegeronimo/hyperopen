(ns hyperopen.vol-range.domain.report-equations-test
  "Headline equations (HO-SPEC-014 v2.1 section 12.3, 13.5 rows 1 to 3):
  exact decimals and Net = Hold + Contribution for every case."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.domain.report-equations :as eq]))

(defn- figures [m]
  (let [f (eq/window-figures m)]
    (is (eq/identity-holds? f) (pr-str m))
    f))

(deftest thirteen-five-rows-test
  (testing "row 1: q0=150, P0=80; buy 7 at 76.4; mark 70"
    (is (= {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"}
           (figures {:q-a "150" :c-a "0" :p-a "80" :q-b "157" :c-b "-534.8" :p-b "70"}))))
  (testing "row 2: sell 30 for 2,652; mark 100. A profitable account can still lag holding"
    (is (= {:net-pnl "2652" :hold-pnl "3000" :contribution "-348"}
           (figures {:q-a "150" :c-a "0" :p-a "80" :q-b "120" :c-b "2652" :p-b "100"}))))
  (testing "row 3: buy 10 at 80 with a 0.004 base fee; mark 80"
    (is (= {:net-pnl "-0.32" :hold-pnl "0" :contribution "-0.32"}
           (figures {:q-a "150" :c-a "0" :p-a "80" :q-b "159.996" :c-b "-800" :p-b "80"})))))

(deftest a-subwindow-uses-its-starting-inventory-and-incremental-cash-test
  (is (= {:net-pnl "4665.2" :hold-pnl "4500" :contribution "165.2"}
         (figures {:q-a "150" :c-a "-100" :p-a "70" :q-b "157" :c-b "-634.8" :p-b "100"}))))

(deftest invalid-inputs-and-sums-test
  (is (nil? (eq/window-figures {:q-a "150" :c-a "0" :p-a nil :q-b "157" :c-b "-534.8" :p-b "70"})))
  (is (nil? (eq/window-figures {:q-a "1e3" :c-a "0" :p-a "80" :q-b "157" :c-b "0" :p-b "70"})))
  (let [a {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"}
        b {:net-pnl "20" :hold-pnl "30" :contribution "-10"}]
    (is (= {:net-pnl "-1524.8" :hold-pnl "-1470" :contribution "-54.8"} (eq/sum-figures [a b])))
    (is (eq/identity-holds? (eq/sum-figures [a b])))
    (is (nil? (eq/sum-figures [])))
    (is (nil? (eq/sum-figures [a nil])))))
