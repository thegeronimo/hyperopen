(ns hyperopen.views.chart.range-strip-model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.custom-range :as custom-range]
            [hyperopen.views.chart.range-strip-model :as model]))

(defn- ms [iso] (.getTime (js/Date. iso)))

(defn- rows
  [pairs]
  (mapv (fn [[t v]] {:time-ms t :value v}) pairs))

(deftest rows-domain-widens-a-single-utc-day-history-test
  (testing "a history spanning only hours of one UTC day would collapse to a zero-width
            domain once day-snapped, pinning the sparkline to the left edge and making
            the strip completely un-draggable"
    (let [same-day (rows [[(ms "2026-08-24T02:00:00.000Z") 10]
                          [(ms "2026-08-24T20:00:00.000Z") 12]])
          domain (model/rows-domain same-day)
          normalized (custom-range/normalize domain)]
      (is (< (:from normalized) (:to normalized)))
      (testing "so a pointer at the right edge maps to a later day than one at the left"
        (is (not= (custom-range/fraction->time 0 domain)
                  (custom-range/fraction->time 1 domain))))))
  (testing "a multi-day history is left exactly as it is"
    (let [multi (rows [[(ms "2026-08-01T00:00:00.000Z") 10]
                       [(ms "2026-08-24T00:00:00.000Z") 12]])]
      (is (= {:from (ms "2026-08-01T00:00:00.000Z")
              :to (ms "2026-08-24T00:00:00.000Z")}
             (model/rows-domain multi)))))
  (is (nil? (model/rows-domain []))))

(deftest build-model-reports-unavailable-without-data-test
  (let [empty-model (model/build-model {:rows [] :strip-open? true})]
    (is (false? (:available? empty-model)))))

(deftest build-model-tracks-the-drag-state-test
  (let [data (rows (mapv (fn [i]
                           [(+ (ms "2026-01-01T00:00:00.000Z") (* i custom-range/day-ms))
                            (+ 100 i)])
                         (range 60)))
        range* {:from (ms "2026-01-10T00:00:00.000Z")
                :to (ms "2026-01-20T00:00:00.000Z")}
        idle (model/build-model {:rows data :range range* :strip-open? true
                                 :now-ms (ms "2026-08-24T00:00:00.000Z")})
        dragging (model/build-model {:rows data :range range* :strip-open? true
                                     :drag-mode :end
                                     :now-ms (ms "2026-08-24T00:00:00.000Z")})]
    (is (true? (:available? idle)))
    (is (= "Drag across the strip, or grab a handle" (:hint idle)))
    (is (= "Release to set the range" (:hint dragging)))
    (is (true? (:dragging? dragging)))
    (is (= "Jan 10 – Jan 20 · 11D" (:draft-label idle)))
    (testing "the selected segment is drawn over the base sparkline"
      (is (some? (:sparkline-points idle)))
      (is (some? (:selected-points idle)))
      (is (not= (:sparkline-points idle) (:selected-points idle))))
    (testing "geometry comes out as percentages for the HTML overlay lane"
      (is (re-matches #"[\d.]+%" (:left (:selection idle))))
      (is (re-matches #"[\d.]+%" (:width (:selection idle))))
      (is (re-matches #"[\d.]+%" (:from (:handles idle)))))))
