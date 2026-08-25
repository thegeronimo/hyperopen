(ns hyperopen.portfolio.optimizer.application.history-loader-sparse-lane-test
  "The off-calendar classifier in isolation. Every threshold here is load-bearing:
  too low a floor admits a member natively and then blocks the run demanding a
  proxy for it, and a cadence-only predicate swallows members the backend already
  carries on its own coarse calendar."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.sparse-lane :as sparse-lane]))

(def ^:private day-ms (* 24 60 60 1000))
(def ^:private d0 (.getTime (js/Date. "2024-08-01T00:00:00.000Z")))

(defn- row
  [id points]
  {:instrument-id id
   :series {:points points}})

(defn- spaced-points
  [n gap-days]
  (mapv (fn [idx]
          {:time-ms (+ d0 (* idx gap-days day-ms))
           :close (+ 100 idx)
           :return (when (pos? idx) 0.01)})
        (range n)))

(def ^:private never-aligned (constantly false))

(deftest growi-shaped-series-takes-the-lane-test
  (testing "31 samples across 390 days qualifies"
    (let [ids (sparse-lane/off-calendar-ids
               [(row "vault:growi" (spaced-points 31 13))
                (row "perp:BTC" (spaced-points 400 1))]
               never-aligned)]
      (is (= #{"vault:growi"} ids)))))

(deftest dense-member-never-takes-the-lane-test
  (let [ids (sparse-lane/off-calendar-ids
             [(row "perp:BTC" (spaced-points 400 1))
              (row "perp:ETH" (spaced-points 400 1))]
             never-aligned)]
    (is (= #{} ids))))

(deftest observation-floor-is-pinned-to-the-assumption-gate-test
  (testing "29 samples is below the floor even though it spans 377 days"
    (let [ids (sparse-lane/off-calendar-ids
               [(row "vault:thin" (spaced-points 29 13))
                (row "perp:BTC" (spaced-points 400 1))]
               never-aligned)]
      (is (= #{} ids))))
  (testing "the floor IS the readiness assumption gate, not a copy of its value"
    (is (= 30 sparse-lane/off-calendar-min-observations))))

(deftest elapsed-span-floor-test
  (testing "40 samples 2 days apart spans only 78 days - a thin recent asset"
    (let [ids (sparse-lane/off-calendar-ids
               [(row "perp:NEW" (spaced-points 40 2))
                (row "perp:BTC" (spaced-points 400 1))]
               never-aligned)]
      (is (= #{} ids)))))

(deftest backend-served-member-stays-on-its-own-calendar-test
  (testing "a member the backend already aligns is never swept into the lane"
    (let [ids (sparse-lane/off-calendar-ids
               [(row "vault:served" (spaced-points 31 13))
                (row "perp:BTC" (spaced-points 400 1))]
               (constantly true))]
      (is (= #{} ids)))))

(deftest degenerate-all-sparse-universe-keeps-todays-behaviour-test
  (testing "with no dense member there is no calendar to protect"
    (let [ids (sparse-lane/off-calendar-ids
               [(row "vault:a" (spaced-points 31 13))
                (row "vault:b" (spaced-points 40 11))]
               never-aligned)]
      (is (= #{} ids)))))

(deftest lane-warning-is-descriptive-not-a-defect-test
  (let [cadence-by-id (sparse-lane/cadence-by-id
                       [(row "vault:growi" (spaced-points 31 13))
                        (row "perp:BTC" (spaced-points 400 1))]
                       never-aligned)
        warning (sparse-lane/warning "vault:growi" (get cadence-by-id "vault:growi"))]
    (is (= :sparse-native-history (:code warning)))
    (is (= 31 (:observations warning)))
    (is (= :pairwise-interval-aggregation (:policy warning)))
    (is (= 390 (js/Math.round (:elapsed-days warning))))))
