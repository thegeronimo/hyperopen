(ns hyperopen.portfolio.optimizer.domain.returns-sparse-member-test
  "An off-calendar sparse member has NO `:return-series-by-instrument` entry by
  design. `domain.returns` used to derive its instrument universe from that map
  alone while `domain.risk` used the union with the native map, so such a member
  silently received an expected return of 0 - `engine.context/expected-return-vector`
  coerces a missing entry to 0 with no warning. A zero expected return on a capped,
  apparently-uncorrelated asset is invisible and wrong."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.domain.returns :as returns]))

(def ^:private day-ms (* 24 60 60 1000))
(def ^:private d0 (.getTime (js/Date. "2024-08-01T00:00:00.000Z")))

(defn- intervals
  [n gap-days]
  (mapv (fn [idx]
          {:start-ms (+ d0 (* idx gap-days day-ms))
           :end-ms (+ d0 (* (inc idx) gap-days day-ms))
           :dt-days gap-days
           :dt-years (/ gap-days 365.2425)})
        (range n)))

(deftest native-only-member-gets-a-real-expected-return-test
  (let [history {:return-series-by-instrument {"perp:BTC" [0.01 0.02 0.015]}
                 ;; The lane member: native rows only.
                 :raw-price-series-by-instrument
                 {"vault:growi" [{:time-ms d0 :close 100}
                                 {:time-ms (+ d0 (* 13 day-ms)) :close 101}]}
                 :expected-return-series-by-instrument
                 {"vault:growi" (vec (repeat 30 0.01))}
                 :expected-return-intervals-by-instrument
                 {"vault:growi" (intervals 30 13)}
                 :return-intervals (intervals 3 1)}
        estimated (returns/estimate-expected-returns
                   {:return-model {:kind :historical-mean}
                    :history history})
        mu (get-in estimated [:expected-returns-by-instrument "vault:growi"])]
    (testing "the member is in the expected-return universe at all"
      (is (contains? (set (:instrument-ids estimated)) "vault:growi")))
    (testing "and gets a finite, non-zero expected return"
      (is (number? mu))
      (is (js/isFinite mu))
      (is (not= 0 mu)))
    (testing "annualized over its OWN irregular intervals, not as 365 daily steps"
      ;; 30 intervals of 13 days each at 1% per interval. Annualizing per-interval
      ;; gives roughly 0.01 * (365/13) ~= 0.28. Treating them as daily steps would
      ;; give ~3.65 - the ~13x overstatement this guards against.
      (is (< mu 1.0)))))

(deftest member-in-neither-map-still-gets-no-entry-test
  (let [estimated (returns/estimate-expected-returns
                   {:return-model {:kind :historical-mean}
                    :history {:return-series-by-instrument {"perp:BTC" [0.01 0.02]}
                              :return-intervals (intervals 2 1)}})]
    (is (not (contains? (:expected-returns-by-instrument estimated) "vault:absent")))))
