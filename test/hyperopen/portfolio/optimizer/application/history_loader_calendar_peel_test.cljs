(ns hyperopen.portfolio.optimizer.application.history-loader-calendar-peel-test
  "Unit coverage for calendar/peel-poisoning-members: the recovery that stops
  one thin, calendar-disjoint member from emptying the shared return calendar
  and (pre-fix, live 2026-07-08) excluding an entire universe."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-loader.calendar :as calendar]))

(def day-ms
  (* 24 60 60 1000))

(defn- points
  "Daily point rows spanning [start-day, start-day + n) with finite returns on
  every row except the first (matching real served series)."
  [start-day n]
  (mapv (fn [idx]
          {:time-ms (* (+ start-day idx) day-ms)
           :close (+ 100 idx)
           :return (when (pos? idx) 0.01)})
        (range n)))

(deftest peel-returns-nil-when-calendar-already-sufficient-test
  (is (nil? (calendar/peel-poisoning-members
             {"perp:AAA" (points 0 40)
              "perp:BBB" (points 0 40)}
             1))))

(deftest peel-removes-single-disjoint-thin-member-test
  (let [result (calendar/peel-poisoning-members
                {"perp:AAA" (points 0 40)
                 "perp:BBB" (points 0 40)
                 "perp:GRAM" (points 45 5)}
                1)]
    (is (= #{"perp:AAA" "perp:BBB"} (:kept-ids result)))
    (is (= [{:instrument-id "perp:GRAM" :observations 0}]
           (:peeled result)))
    (is (= 39 (count (:return-calendar result))))))

(deftest peel-removes-both-of-two-disjoint-young-members-test
  (let [result (calendar/peel-poisoning-members
                {"perp:AAA" (points 0 40)
                 "perp:BBB" (points 0 40)
                 "perp:YY1" (points 45 5)
                 "perp:YY2" (points 52 5)}
                1)]
    (is (= #{"perp:AAA" "perp:BBB"} (:kept-ids result)))
    (is (= #{"perp:YY1" "perp:YY2"}
           (set (map :instrument-id (:peeled result)))))
    (is (= 39 (count (:return-calendar result))))))

(deftest peel-two-member-universe-keeps-the-deeper-side-test
  (let [result (calendar/peel-poisoning-members
                {"perp:DEEP" (points 0 40)
                 "perp:YOUNG" (points 45 5)}
                1)]
    (is (= #{"perp:DEEP"} (:kept-ids result)))
    (is (= ["perp:YOUNG"] (mapv :instrument-id (:peeled result))))
    (is (= 39 (count (:return-calendar result))))))

(deftest peel-reports-partial-overlap-observations-test
  (let [result (calendar/peel-poisoning-members
                {"perp:AAA" (points 0 40)
                 "perp:BBB" (points 0 40)
                 "perp:LATE" (points 35 10)}
                10)]
    (is (= #{"perp:AAA" "perp:BBB"} (:kept-ids result)))
    (is (= [{:instrument-id "perp:LATE" :observations 4}]
           (:peeled result)))
    (is (= 39 (count (:return-calendar result))))))

(deftest peel-returns-nil-when-no-viable-subset-test
  (let [returnless (mapv #(dissoc % :return) (points 0 10))]
    (is (nil? (calendar/peel-poisoning-members
               {"perp:AAA" returnless
                "perp:BBB" (mapv #(dissoc % :return) (points 20 10))}
               1)))))
