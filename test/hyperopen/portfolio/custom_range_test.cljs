(ns hyperopen.portfolio.custom-range-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.custom-range :as custom-range]))

(defn- ms
  [iso]
  (.getTime (js/Date. iso)))

(def ^:private mar-3 (ms "2026-03-03T00:00:00.000Z"))
(def ^:private jun-12 (ms "2026-06-12T00:00:00.000Z"))
(def ^:private now (ms "2026-08-24T00:00:00.000Z"))

(def ^:private domain
  {:from (ms "2024-08-24T00:00:00.000Z")
   :to now})

;; --- the range value ----------------------------------------------------------------------

(deftest normalize-snaps-orders-and-rejects-test
  (testing "day-snaps both bounds"
    (is (= {:from mar-3 :to jun-12}
           (custom-range/normalize {:from (+ mar-3 (* 13 3600000))
                                    :to (+ jun-12 (* 22 3600000))}))))
  (testing "a backwards drag is still a valid range"
    (is (= {:from mar-3 :to jun-12}
           (custom-range/normalize {:from jun-12 :to mar-3}))))
  (testing "string keys, as parsed off a URL"
    (is (= {:from mar-3 :to jun-12}
           (custom-range/normalize {"from" mar-3 "to" jun-12}))))
  (testing "rejects anything unusable rather than inventing a window"
    (is (nil? (custom-range/normalize nil)))
    (is (nil? (custom-range/normalize {})))
    (is (nil? (custom-range/normalize {:from mar-3})))
    (is (nil? (custom-range/normalize {:from mar-3 :to nil})))
    (is (nil? (custom-range/normalize {:from mar-3 :to js/NaN})))
    (is (nil? (custom-range/normalize {:from mar-3 :to "jun"})))
    (is (nil? (custom-range/normalize "2026-03-03")))))

(deftest active?-tracks-normalize-test
  (is (true? (custom-range/active? {:from mar-3 :to jun-12})))
  (is (false? (custom-range/active? nil)))
  (is (false? (custom-range/active? {:from mar-3}))))

(deftest day-count-is-inclusive-test
  (is (= 1 (custom-range/day-count {:from mar-3 :to mar-3})))
  (is (= 2 (custom-range/day-count {:from mar-3 :to (+ mar-3 custom-range/day-ms)})))
  (is (= 102 (custom-range/day-count {:from mar-3 :to jun-12})))
  (is (nil? (custom-range/day-count nil))))

(deftest span-preset-picks-the-smallest-covering-bucket-test
  (let [span (fn [days]
               (custom-range/span-preset {:from mar-3
                                          :to (+ mar-3 (* (dec days) custom-range/day-ms))}))]
    (is (= :day (span 1)))
    (is (= :week (span 7)))
    (testing "one day past a bucket steps up, so the request always covers the span"
      (is (= :month (span 8))))
    (is (= :month (span 30)))
    (is (= :three-month (span 31)))
    (is (= :six-month (span 182)))
    (is (= :one-year (span 365)))
    (is (= :two-year (span 730)))
    (is (= :all-time (span 731)))
    (is (nil? (custom-range/span-preset nil)))))

(deftest clamp-to-domain-pulls-range-inside-test
  (let [before {:from (ms "2020-01-01T00:00:00.000Z")
                :to (ms "2020-06-01T00:00:00.000Z")}]
    (testing "a range entirely before the data collapses onto the near edge"
      (is (= {:from (:from domain) :to (:from domain)}
             (custom-range/clamp-to-domain before domain)))))
  (testing "a straddling range keeps its inside bound"
    (is (= {:from (:from domain) :to jun-12}
           (custom-range/clamp-to-domain {:from (ms "2020-01-01T00:00:00.000Z")
                                          :to jun-12}
                                         domain))))
  (testing "an inside range is untouched"
    (is (= {:from mar-3 :to jun-12}
           (custom-range/clamp-to-domain {:from mar-3 :to jun-12} domain))))
  (is (nil? (custom-range/clamp-to-domain nil domain))))

(deftest covers-domain?-test
  (is (true? (custom-range/covers-domain? domain domain)))
  (is (true? (custom-range/covers-domain? {:from (ms "2020-01-01T00:00:00.000Z")
                                           :to now}
                                          domain)))
  (is (false? (custom-range/covers-domain? {:from mar-3 :to jun-12} domain))))

;; --- windowing ----------------------------------------------------------------------------

(deftest cutoff-and-end-bounds-test
  (is (= mar-3 (custom-range/cutoff-ms {:from mar-3 :to jun-12})))
  (testing "the end bound covers the whole final day"
    (is (= (+ jun-12 (dec custom-range/day-ms))
           (custom-range/end-ms {:from mar-3 :to jun-12}))))
  (is (nil? (custom-range/cutoff-ms nil)))
  (is (nil? (custom-range/end-ms nil))))

(deftest clip-rows-to-end-drops-only-the-tail-test
  (let [rows [{:time-ms mar-3 :value 1}
              {:time-ms jun-12 :value 2}
              ;; same day as the end bound, later hour: must survive
              {:time-ms (+ jun-12 (* 23 3600000)) :value 3}
              {:time-ms (+ jun-12 custom-range/day-ms) :value 4}]
        clipped (custom-range/clip-rows-to-end rows {:from mar-3 :to jun-12})]
    (is (= [1 2 3] (mapv :value clipped)))
    (testing "rows before the start are kept — the existing cutoff machinery owns that edge"
      (is (= mar-3 (:time-ms (first clipped)))))
    (testing "no range clips nothing"
      (is (= 4 (count (custom-range/clip-rows-to-end rows nil)))))
    (is (= [] (custom-range/clip-rows-to-end nil {:from mar-3 :to jun-12})))))

(deftest clip-pairs-to-end-test
  (let [pairs [[mar-3 1] [jun-12 2] [(+ jun-12 custom-range/day-ms) 3]]]
    (is (= [[mar-3 1] [jun-12 2]]
           (custom-range/clip-pairs-to-end pairs {:from mar-3 :to jun-12})))
    (is (= pairs (custom-range/clip-pairs-to-end pairs nil)))))

(deftest clip-rows-applies-both-bounds-test
  (let [rows [{:time-ms (- mar-3 custom-range/day-ms) :value 0}
              {:time-ms mar-3 :value 1}
              {:time-ms jun-12 :value 2}
              {:time-ms (+ jun-12 custom-range/day-ms) :value 3}]]
    (is (= [1 2] (mapv :value (custom-range/clip-rows rows {:from mar-3 :to jun-12}))))))

;; --- formatting ---------------------------------------------------------------------------

(deftest iso-round-trip-test
  (is (= "2026-03-03" (custom-range/format-iso mar-3)))
  (is (= mar-3 (custom-range/parse-iso "2026-03-03")))
  (is (= mar-3 (custom-range/parse-iso "  2026-03-03  ")))
  (testing "strict: a malformed share link falls back rather than inventing a window"
    (is (nil? (custom-range/parse-iso "2026-3-3")))
    (is (nil? (custom-range/parse-iso "March 3")))
    (is (nil? (custom-range/parse-iso "2026-13-01")))
    (is (nil? (custom-range/parse-iso "")))
    (is (nil? (custom-range/parse-iso nil)))))

(deftest format-span-shows-the-year-only-when-ambiguous-test
  (is (= "Mar 3 – Jun 12" (custom-range/format-span {:from mar-3 :to jun-12} now)))
  (testing "another single year gets one suffix"
    (is (= "Mar 3 – Jun 12 '25"
           (custom-range/format-span {:from (ms "2025-03-03T00:00:00.000Z")
                                      :to (ms "2025-06-12T00:00:00.000Z")}
                                     now))))
  (testing "crossing a year boundary labels both ends"
    (is (= "Mar 3 '24 – Jun 12 '25"
           (custom-range/format-span {:from (ms "2024-03-03T00:00:00.000Z")
                                      :to (ms "2025-06-12T00:00:00.000Z")}
                                     now))))
  (is (nil? (custom-range/format-span nil now))))

(deftest format-draft-label-and-full-date-test
  (is (= "Mar 3 – Jun 12 · 102D"
         (custom-range/format-draft-label {:from mar-3 :to jun-12} now)))
  (is (= "Mar 3, 2026" (custom-range/format-full-date mar-3)))
  (is (nil? (custom-range/format-full-date nil))))

;; --- strip geometry -----------------------------------------------------------------------

(deftest fraction-maps-both-ways-test
  (is (= 0.0 (custom-range/time->fraction (:from domain) domain)))
  (is (= 1.0 (custom-range/time->fraction (:to domain) domain)))
  (testing "clamped outside the domain"
    (is (= 0.0 (custom-range/time->fraction (ms "2000-01-01T00:00:00.000Z") domain)))
    (is (= 1.0 (custom-range/time->fraction (ms "2030-01-01T00:00:00.000Z") domain))))
  (is (= (:from domain) (custom-range/fraction->time 0 domain)))
  (is (= (:to domain) (custom-range/fraction->time 1 domain)))
  (testing "a degenerate domain cannot divide by zero"
    (is (= 0.0 (custom-range/time->fraction mar-3 {:from mar-3 :to mar-3})))))

(deftest pointer-fraction-handles-degenerate-geometry-test
  (is (= 0.5 (custom-range/pointer-fraction 150 {:left 100 :width 100})))
  (testing "clamped to the strip"
    (is (= 0.0 (custom-range/pointer-fraction 50 {:left 100 :width 100})))
    (is (= 1.0 (custom-range/pointer-fraction 900 {:left 100 :width 100}))))
  (testing "a hidden pane measures zero wide"
    (is (nil? (custom-range/pointer-fraction 150 {:left 100 :width 0})))
    (is (nil? (custom-range/pointer-fraction 150 nil)))
    (is (nil? (custom-range/pointer-fraction nil {:left 100 :width 100})))))

;; --- drag ---------------------------------------------------------------------------------

(def ^:private bounds {:left 0 :width 1000})

(defn- at-fraction
  [f]
  (* f (:width bounds)))

(deftest drag-begin-starts-a-fresh-sweep-off-handle-test
  (let [{:keys [mode range]} (custom-range/drag-begin
                              {:client-x (at-fraction 0.5)
                               :bounds bounds
                               :domain domain
                               :range nil})]
    (is (= :end mode))
    (testing "a fresh sweep is anchored where the pointer went down"
      (is (= (:from range) (:to range)))
      (is (= (custom-range/fraction->time 0.5 domain) (:from range))))))

(deftest drag-begin-grabs-the-nearest-handle-test
  (let [range {:from (custom-range/fraction->time 0.25 domain)
               :to (custom-range/fraction->time 0.75 domain)}]
    (testing "on the start handle"
      (is (= :start (:mode (custom-range/drag-begin {:client-x (at-fraction 0.25)
                                                     :bounds bounds
                                                     :domain domain
                                                     :range range})))))
    (testing "on the end handle"
      (is (= :end (:mode (custom-range/drag-begin {:client-x (at-fraction 0.75)
                                                   :bounds bounds
                                                   :domain domain
                                                   :range range})))))
    (testing "between the handles is a fresh sweep, not a nudge"
      (let [result (custom-range/drag-begin {:client-x (at-fraction 0.5)
                                             :bounds bounds
                                             :domain domain
                                             :range range})]
        (is (= :end (:mode result)))
        (is (= (:from (:range result)) (:to (:range result))))))))

(deftest drag-begin-collapsed-selection-opens-either-way-test
  (let [collapsed-ts (custom-range/fraction->time 0.5 domain)
        range {:from collapsed-ts :to collapsed-ts}
        just-left (custom-range/drag-begin {:client-x (at-fraction 0.495)
                                            :bounds bounds
                                            :domain domain
                                            :range range})
        just-right (custom-range/drag-begin {:client-x (at-fraction 0.505)
                                             :bounds bounds
                                             :domain domain
                                             :range range})]
    (is (= :start (:mode just-left)))
    (is (= :end (:mode just-right)))))

(deftest drag-begin-handle-only-refuses-a-stray-tap-test
  (let [range {:from (custom-range/fraction->time 0.25 domain)
               :to (custom-range/fraction->time 0.75 domain)}]
    (is (nil? (custom-range/drag-begin {:client-x (at-fraction 0.5)
                                        :bounds bounds
                                        :domain domain
                                        :range range
                                        :handle-only? true})))
    (testing "handles still work in handle-only mode"
      (is (= :start (:mode (custom-range/drag-begin {:client-x (at-fraction 0.25)
                                                     :bounds bounds
                                                     :domain domain
                                                     :range range
                                                     :handle-only? true})))))))

(deftest drag-begin-refuses-degenerate-geometry-test
  (is (nil? (custom-range/drag-begin {:client-x 10
                                      :bounds {:left 0 :width 0}
                                      :domain domain
                                      :range nil}))))

(deftest drag-move-requires-a-pressed-button-test
  (let [range {:from (custom-range/fraction->time 0.25 domain)
               :to (custom-range/fraction->time 0.75 domain)}]
    (testing "a released button ends the drag"
      (is (nil? (custom-range/drag-move {:mode :end
                                         :client-x (at-fraction 0.6)
                                         :bounds bounds
                                         :buttons 0
                                         :domain domain
                                         :range range})))
      (is (nil? (custom-range/drag-move {:mode :end
                                         :client-x (at-fraction 0.6)
                                         :bounds bounds
                                         :buttons nil
                                         :domain domain
                                         :range range}))))))

(deftest drag-move-nudges-the-live-edge-test
  (let [range {:from (custom-range/fraction->time 0.25 domain)
               :to (custom-range/fraction->time 0.75 domain)}
        moved-end (custom-range/drag-move {:mode :end
                                           :client-x (at-fraction 0.6)
                                           :bounds bounds
                                           :buttons 1
                                           :domain domain
                                           :range range})
        moved-start (custom-range/drag-move {:mode :start
                                             :client-x (at-fraction 0.4)
                                             :bounds bounds
                                             :buttons 1
                                             :domain domain
                                             :range range})]
    (is (= :end (:mode moved-end)))
    (is (= (:from range) (:from (:range moved-end))))
    (is (= (custom-range/fraction->time 0.6 domain) (:to (:range moved-end))))
    (is (= :start (:mode moved-start)))
    (is (= (:to range) (:to (:range moved-start))))
    (is (= (custom-range/fraction->time 0.4 domain) (:from (:range moved-start))))))

(deftest drag-move-swaps-the-live-edge-on-crossover-test
  (let [range {:from (custom-range/fraction->time 0.25 domain)
               :to (custom-range/fraction->time 0.75 domain)}]
    (testing "dragging the end back past the start makes the start live"
      (let [{:keys [mode range]} (custom-range/drag-move {:mode :end
                                                          :client-x (at-fraction 0.1)
                                                          :bounds bounds
                                                          :buttons 1
                                                          :domain domain
                                                          :range range})]
        (is (= :start mode))
        (is (= (custom-range/fraction->time 0.1 domain) (:from range)))
        (is (= (custom-range/fraction->time 0.25 domain) (:to range)))
        (is (<= (:from range) (:to range)))))
    (testing "dragging the start forward past the end makes the end live"
      (let [{:keys [mode range]} (custom-range/drag-move {:mode :start
                                                          :client-x (at-fraction 0.9)
                                                          :bounds bounds
                                                          :buttons 1
                                                          :domain domain
                                                          :range range})]
        (is (= :end mode))
        (is (= (custom-range/fraction->time 0.75 domain) (:from range)))
        (is (= (custom-range/fraction->time 0.9 domain) (:to range)))
        (is (<= (:from range) (:to range)))))))

(deftest drag-sweep-end-to-end-stays-ordered-test
  (testing "a full right-to-left sweep never produces an inverted range"
    (let [start (custom-range/drag-begin {:client-x (at-fraction 0.8)
                                          :bounds bounds
                                          :domain domain
                                          :range nil})]
      (loop [state start
             fractions [0.7 0.5 0.3 0.2]]
        (if-let [f (first fractions)]
          (let [next-state (custom-range/drag-move {:mode (:mode state)
                                                    :client-x (at-fraction f)
                                                    :bounds bounds
                                                    :buttons 1
                                                    :domain domain
                                                    :range (:range state)})]
            (is (<= (:from (:range next-state)) (:to (:range next-state))))
            (recur next-state (rest fractions)))
          (do
            (is (= (custom-range/fraction->time 0.2 domain) (:from (:range state))))
            (is (= (custom-range/fraction->time 0.8 domain) (:to (:range state))))))))))

(deftest selection-geometry-floors-a-collapsed-selection-test
  (let [collapsed-ts (custom-range/fraction->time 0.5 domain)
        geometry (custom-range/selection-geometry {:from collapsed-ts :to collapsed-ts}
                                                  domain
                                                  0.004)]
    (is (= 0.5 (:x geometry)))
    (testing "a single-day selection stays visible and grabbable"
      (is (= 0.004 (:width geometry)))))
  (testing "the floored width never runs past the strip"
    (let [geometry (custom-range/selection-geometry {:from (:to domain) :to (:to domain)}
                                                    domain
                                                    0.004)]
      (is (= 1.0 (:x geometry)))
      (is (= 0.0 (:width geometry)))))
  (is (nil? (custom-range/selection-geometry nil domain 0.004))))

;; --- strip domain -------------------------------------------------------------------------

(deftest utc-day-start-and-end-bracket-the-day-test
  (let [noon (+ mar-3 (* 12 3600000))]
    (is (= mar-3 (custom-range/utc-day-start noon)))
    (is (= (+ mar-3 (dec custom-range/day-ms)) (custom-range/utc-day-end noon)))
    (is (nil? (custom-range/utc-day-start nil)))))
