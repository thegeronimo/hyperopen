(ns hyperopen.vol-range.domain.report-marks-test
  "Hourly marks (HO-SPEC-014 v2.1 section 12.4): `b` floors to the hour
  after a one-minute settle; the mark at `x` is the close of the completed
  candle opening at `x − 1h`; a missing close is missing, never carried
  forward; only completed, positive decimal closes are kept, and the venue's
  own later candle (not a local clock running ahead) proves completion."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.domain.report-marks :as marks]
            [hyperopen.vol-range.report-fixtures :as fx]))

(def hour fx/hour)
(def h0 (* hour (quot fx/t0 hour)))

(deftest the-window-end-floors-after-the-settle-test
  (is (= (- h0 hour) (marks/window-end (+ h0 30000))) "14:00:30 is inside the one-minute settle")
  (is (= h0 (marks/window-end (+ h0 60000))))
  (is (= h0 (marks/window-end (+ h0 hour 59999))))
  (is (= h0 (marks/hour-floor (+ h0 1))))
  (is (= (+ h0 hour) (marks/hour-ceil (+ h0 1))))
  (is (= h0 (marks/hour-ceil h0))))

(def margin 600000)

(deftest only-completed-decimal-closes-are-kept-test
  (let [rows [(fx/candle h0 "80")
              (fx/candle (+ h0 hour) "70.5")
              (fx/candle (+ h0 (* 2 hour)) "100")
              (assoc (fx/candle (+ h0 (* 3 hour)) "1e2") :t (+ h0 (* 3 hour)))
              (fx/candle (+ h0 (* 4 hour)) "0")
              (assoc (fx/candle h0 "90") :t (+ h0 5))
              {:t (str (+ h0 (* 5 hour))) :c "60"}
              "garbage"]]
    (is (= {h0 "80" (+ h0 hour) "70.5" (+ h0 (* 2 hour)) "100" (+ h0 (* 5 hour)) "60"}
           (marks/normalize-candles rows (+ h0 (* 6 hour) margin))))
    (is (= {h0 "80" (+ h0 hour) "70.5" (+ h0 (* 2 hour)) "100"} (marks/normalize-candles rows (+ h0 (* 6 hour))))
        "the latest candle is completed only by a later one or the clock margin")))

(deftest a-local-clock-ahead-of-the-venue-never-completes-a-candle-test
  (testing "the clock runs 90 s fast: at local 14:01:05 the venue is at 13:59:35 and returns the 13:00 candle in progress"
    (let [local (+ h0 (* 2 hour) 65000)
          rows [(fx/candle h0 "80") (fx/candle (+ h0 hour) "71")]]
      (is (= (+ h0 (* 2 hour)) (marks/window-end local)) "b is 14:00 by the local clock")
      (is (= {h0 "80"} (marks/normalize-candles rows local)) "the 13:00 close is not taken")
      (let [entry (marks/merge-read nil {:candles rows :complete? true :requested-at-ms local} [h0 (+ h0 (* 2 hour))])]
        (is (= [[h0 (+ h0 hour)]] (:covered entry)) "the 13:00 hour is not proven")
        (is (= :pending (:status (marks/mark-at entry (+ h0 (* 2 hour))))) "P_b stays loading")
        (is (= [[(+ h0 hour) (+ h0 (* 2 hour))]] (marks/missing-opens entry [h0 (+ h0 (* 2 hour))]))
            "so the hour is read again")
        (testing "once the venue has started 14:00, the final 13:00 close is taken"
          (let [later (marks/merge-read entry {:candles (conj (pop rows) (fx/candle (+ h0 hour) "70")
                                                               (fx/candle (+ h0 (* 2 hour)) "69"))
                                               :complete? true :requested-at-ms (+ local 60000)}
                                        [(+ h0 hour) (+ h0 (* 2 hour))])]
            (is (= {:status :ok :price "70" :source :hourly-close :candle-open-ms (+ h0 hour)}
                   (marks/mark-at later (+ h0 (* 2 hour)))))
            (is (= [] (marks/missing-opens later [h0 (+ h0 (* 2 hour))])))
            (is (nil? (get-in later [:closes (+ h0 (* 2 hour))])) "the in-progress 14:00 candle is outside the read"))))))
  (testing "an illiquid next hour without a candle: the clock completes it only after the margin"
    (let [rows [(fx/candle h0 "80") (fx/candle (+ h0 hour) "71")]]
      (is (= {h0 "80"} (marks/normalize-candles rows (+ h0 (* 2 hour) (dec margin)))))
      (is (= {h0 "80" (+ h0 hour) "71"} (marks/normalize-candles rows (+ h0 (* 2 hour) margin))))))
  (is (nil? (marks/completed-before [] nil)) "a read without a time or rows proves nothing"))

(deftest the-mark-is-the-close-of-the-candle-before-the-boundary-test
  (let [entry {:closes {h0 "80" (+ h0 hour) "70"} :covered [[h0 (+ h0 (* 3 hour))]]}]
    (is (= {:status :ok :price "70" :source :hourly-close :candle-open-ms (+ h0 hour)}
           (marks/mark-at entry (+ h0 (* 2 hour)))))
    (is (= {:status :missing :candle-open-ms (+ h0 (* 2 hour))} (marks/mark-at entry (+ h0 (* 3 hour))))
        "a read proved the hour had no close: missing, not carried forward")
    (is (= :pending (:status (marks/mark-at entry (+ h0 (* 4 hour))))) "not read yet")
    (is (= :error (:status (marks/mark-at (assoc entry :error "x") (+ h0 (* 4 hour))))))
    (is (= :pending (:status (marks/mark-at nil h0))))))

(deftest merging-reads-proves-only-completed-hours-test
  (let [read {:candles fx/candles :complete? true :requested-at-ms (+ h0 (* 3 hour) 1000)
              :received-at-ms (+ h0 (* 3 hour) 1100)}
        entry (marks/merge-read nil read [h0 (+ h0 (* 5 hour))])]
    (is (= {(+ h0 hour) "80" (+ h0 (* 2 hour)) "70"} (:closes entry)) "the 03:00 hour was still open")
    (is (= [[h0 (+ h0 (* 3 hour))]] (:covered entry)) "the venue's 03:00 candle proves the hours before it")
    (is (= [[(+ h0 (* 3 hour)) (+ h0 (* 5 hour))]] (marks/missing-opens entry [h0 (+ h0 (* 5 hour))])))
    (is (nil? (:error entry)))
    (let [later (marks/merge-read entry (assoc read :requested-at-ms (+ h0 (* 9 hour))) [(+ h0 (* 2 hour)) (+ h0 (* 5 hour))])]
      (is (= "100" (get-in later [:closes (+ h0 (* 3 hour))])))
      (is (= [] (marks/missing-opens later [h0 (+ h0 (* 5 hour))]))))
    (testing "closes outside the requested hours are not taken"
      (is (= {(+ h0 hour) "80"} (:closes (marks/merge-read nil (assoc read :requested-at-ms (+ h0 (* 9 hour)))
                                                          [(+ h0 hour) (+ h0 (* 2 hour))])))))
    (testing "an incomplete read proves nothing and keeps its error"
      (let [bad (marks/merge-read entry {:candles [] :complete? false :requested-at-ms (+ h0 (* 9 hour))
                                         :error "Candle page cap reached"}
                                  [h0 (+ h0 (* 5 hour))])]
        (is (= (:covered entry) (:covered bad)))
        (is (= "Candle page cap reached" (:error bad)))
        (is (= (:closes entry) (:closes bad)))))))

(deftest needed-opens-and-checkpoints-test
  (is (= [h0 (+ h0 (* 3 hour))] (marks/needed-opens fx/t0 (+ h0 (* 3 hour))))
      "Since tracking began from 13:42 needs the 13:00 candle onwards")
  (is (= [(- h0 hour) (+ h0 (* 3 hour))] (marks/needed-opens h0 (+ h0 (* 3 hour)))))
  (is (= [(+ h0 hour) (+ h0 (* 2 hour)) (+ h0 (* 3 hour))] (marks/hour-boundaries fx/t0 (+ h0 (* 3 hour)))))
  (is (= [(+ h0 hour)] (marks/hour-boundaries h0 (+ h0 hour)))))
