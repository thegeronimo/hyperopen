(ns hyperopen.views.portfolio.vm.custom-range-window-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.custom-range :as custom-range]
            [hyperopen.views.portfolio.vm.history :as vm-history]
            [hyperopen.views.portfolio.vm.summary :as vm-summary]))

(defn- ms [iso] (.getTime (js/Date. iso)))

(def ^:private day custom-range/day-ms)
(def ^:private start (ms "2026-01-01T00:00:00.000Z"))

(defn- series
  "60 consecutive daily samples starting 2026-01-01."
  [scale]
  (mapv (fn [i] [(+ start (* i day)) (* scale (inc i))])
        (range 60)))

(def ^:private all-time-summary
  {:accountValueHistory (series 100)
   :pnlHistory (series 10)})

(def ^:private summary-by-key
  {:all-time all-time-summary
   ;; A denser, shorter bucket: hourly samples over the last 3 days.
   :week {:accountValueHistory (mapv (fn [i] [(+ start (* 57 day) (* i 3600000)) (+ 5700 i)])
                                     (range 72))
          :pnlHistory (mapv (fn [i] [(+ start (* 57 day) (* i 3600000)) (+ 570 i)])
                            (range 72))}})

(deftest cutoff-comes-from-the-custom-range-not-preset-arithmetic-test
  (let [range {:from (ms "2026-01-10T00:00:00.000Z")
               :to (ms "2026-01-20T00:00:00.000Z")}]
    (is (= (:from range) (vm-history/summary-window-cutoff-ms range (ms "2026-02-01T00:00:00.000Z"))))
    (testing "and does not depend on the last sample at all"
      (is (= (:from range) (vm-history/summary-window-cutoff-ms range nil))))
    (testing "presets still use their own arithmetic"
      (let [last-ms (ms "2026-02-01T00:00:00.000Z")]
        (is (= (- last-ms day) (vm-history/summary-window-cutoff-ms :day last-ms)))
        (is (nil? (vm-history/summary-window-cutoff-ms :all-time last-ms)))))))

(deftest derived-summary-entry-clips-both-ends-of-a-custom-window-test
  (let [range {:from (ms "2026-01-10T00:00:00.000Z")
               :to (ms "2026-01-20T00:00:00.000Z")}
        entry (vm-summary/derived-summary-entry {:all-time all-time-summary} :all range)
        times (mapv :time-ms (:accountValueHistory entry))]
    (is (some? entry))
    (testing "the window starts at :from"
      (is (= (:from range) (first times))))
    (testing "and ends at :to — the end bound is what nothing else in the pipeline knows about"
      (is (= (:to range) (peek times))))
    (is (= 11 (count times)))
    (testing "pnl is rebased to the window start so the chart reads from zero"
      (is (= 0 (:value (first (:pnlHistory entry))))))))

(deftest custom-window-sources-the-densest-bucket-that-covers-it-test
  (testing "a short recent window prefers the dense :week bucket over daily all-time"
    (let [range {:from (ms "2026-02-27T00:00:00.000Z")
                 :to (ms "2026-02-28T00:00:00.000Z")}
          entry (vm-summary/derived-summary-entry summary-by-key :all range)]
      (is (< 2 (count (:accountValueHistory entry))))))
  (testing "a window reaching further back than the dense bucket falls back to all-time"
    (let [range {:from (ms "2026-01-05T00:00:00.000Z")
                 :to (ms "2026-01-15T00:00:00.000Z")}
          entry (vm-summary/derived-summary-entry summary-by-key :all range)
          times (mapv :time-ms (:accountValueHistory entry))]
      (is (= (:from range) (first times)))
      (is (= (:to range) (peek times))))))

(deftest a-custom-range-never-resolves-onto-a-preset-bucket-test
  (testing "even though a :month bucket exists, the custom window takes the derived path"
    (let [range {:from (ms "2026-01-10T00:00:00.000Z")
                 :to (ms "2026-01-20T00:00:00.000Z")}
          context (vm-summary/selected-summary-context
                   (assoc summary-by-key :month all-time-summary)
                   :all
                   range)
          times (mapv :time-ms (:accountValueHistory (:entry context)))]
      (is (= :derived (:source context)))
      (is (= (:to range) (peek times))))))

(deftest a-window-outside-the-data-reads-as-empty-not-as-all-time-test
  (testing "a shared link naming a window this account has no history for must NOT
            quietly plot the account's entire history under a custom label"
    (let [range {:from (ms "2020-01-01T00:00:00.000Z")
                 :to (ms "2020-06-01T00:00:00.000Z")}
          entry (vm-summary/derived-summary-entry {:all-time all-time-summary} :all range)
          context (vm-summary/selected-summary-context
                   (assoc summary-by-key :month all-time-summary)
                   :all
                   range)]
      (is (some? entry))
      (is (= [] (:accountValueHistory entry)))
      (is (= [] (:pnlHistory entry)))
      (testing "and the fallback chain is not entered"
        (is (= :derived (:source context)))
        (is (empty? (:accountValueHistory (:entry context)))))))
  (testing "a PRESET with no data still falls back as before"
    (is (nil? (vm-summary/derived-summary-entry {} :all :six-month)))))

(deftest returns-history-context-keeps-the-custom-cutoff-test
  (let [range {:from (ms "2026-01-10T00:00:00.000Z")
               :to (ms "2026-01-20T00:00:00.000Z")}
        context (vm-summary/returns-history-context {:all-time all-time-summary} :all range)]
    (testing "the cutoff is the custom start — benchmarks rebase against it, so a nil here
              would silently offset every benchmark line"
      (is (= (:from range) (:cutoff-ms context))))
    (is (= (:to range) (:window-end-ms context)))
    (is (true? (:has-data? context)))))
