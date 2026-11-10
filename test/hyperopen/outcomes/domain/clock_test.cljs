(ns hyperopen.outcomes.domain.clock-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.outcomes.domain.clock :as clock]))

(def ^:private utc {:time-zone "UTC"})

(def ^:private tip-off (js/Date.UTC 2026 10 10 0 0))

(def ^:private minute 60000)
(def ^:private hour (* 60 minute))

(deftest parse-time-ms-reads-template-times-as-utc-test
  (is (= (js/Date.UTC 2026 10 30 21 0) (clock/parse-time-ms "20261130-2100")))
  (is (nil? (clock/parse-time-ms "Nov 30")))
  (is (nil? (clock/parse-time-ms nil))))

(deftest duration-text-test
  (is (= "1m" (clock/duration-text 10)))
  (is (= "42m" (clock/duration-text (* 42 minute))))
  (is (= "7h 34m" (clock/duration-text (+ (* 7 hour) (* 34 minute)))))
  (is (= "3h" (clock/duration-text (* 3 hour))))
  (is (= "12d" (clock/duration-text (* 12 24 hour)))))

(deftest game-clock-phases-test
  (let [game (clock/clock-spec :starts "20261110-0000" "20261117-0000")]
    (testing "hours before tip-off"
      (is (= {:phase :upcoming
              :label "Starts in 7h 34m"
              :absolute "Tue, Nov 10, 12:00 AM UTC"}
             (clock/clock-view game (- tip-off (* 7 hour) (* 34 minute)) utc))))
    (testing "inside the last hour"
      (is (= :soon (:phase (clock/clock-view game (- tip-off (* 42 minute)) utc)))))
    (testing "days ahead shows the date"
      (is (= "Starts Nov 10" (:label (clock/clock-view game (- tip-off (* 5 24 hour)) utc)))))
    (testing "in play"
      (is (= {:phase :live :label "Live"}
             (select-keys (clock/clock-view game (+ tip-off hour) utc) [:phase :label]))))
    (testing "after the live window"
      (is (= "Awaiting result · by Nov 17"
             (:label (clock/clock-view game (+ tip-off (* 6 hour)) utc)))))))

(deftest other-clock-kinds-test
  (let [now (js/Date.UTC 2026 10 9 16 25)]
    (is (= "Settles in 13h 35m"
           (:label (clock/clock-view (clock/clock-spec :settles "20261110-0600" nil) now utc))))
    (is (= "Settling"
           (:label (clock/clock-view (clock/clock-spec :settles "20261109-1600" nil) now utc))))
    (testing "Settling does not last forever"
      (is (= "Awaiting result"
             (:label (clock/clock-view (clock/clock-spec :settles "20261109-1000" nil) now utc)))))
    (is (= "Ends Dec 1"
           (:label (clock/clock-view (clock/clock-spec :ends "20261201-0000" nil) now utc))))
    (is (= "Decision Dec 9"
           (:label (clock/clock-view (clock/clock-spec :decision "20261209-1900" "20270127-1900") now utc))))
    (is (= "Resolves by Jun 30, 2027"
           (:label (clock/clock-view (clock/clock-spec :resolves "20270630-2359" nil) now utc))))
    (is (= "If listed by Jan 1, 2027"
           (:label (clock/clock-view (clock/clock-spec :listing "20270101-0459" nil) now utc))))
    (is (nil? (clock/clock-view nil now utc)))))
