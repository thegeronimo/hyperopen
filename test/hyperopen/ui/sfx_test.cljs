(ns hyperopen.ui.sfx-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.ui.sfx :as sfx]))

(deftest fill-sound-noops-without-webaudio-test
  (is (nil? (sfx/fill! true)))
  (is (nil? (sfx/fill! false))))

(deftest rekt-sound-noops-without-webaudio-test
  (is (nil? (sfx/rekt!))))

(deftest leverage-tick-tracks-tier-changes-test
  (is (nil? (sfx/leverage-tick-on-change! :leverage 2 true)))
  (is (nil? (sfx/leverage-tick-on-change! :leverage 2 true)))
  (is (nil? (sfx/leverage-tick-on-change! :leverage 3 true)))
  (is (nil? (sfx/leverage-tick-on-change! :order-size 1 true)))
  (is (nil? (sfx/leverage-tick-on-change! :order-size 2 false))))
