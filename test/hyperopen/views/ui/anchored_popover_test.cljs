(ns hyperopen.views.ui.anchored-popover-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.ui.anchored-popover :as anchored-popover]))

(deftest complete-anchor-no-longer-requires-bottom-test
  (is (true? (anchored-popover/complete-anchor? {:left 10
                                                 :right 30
                                                 :top 40})))
  (is (false? (anchored-popover/complete-anchor? {:left 10
                                                  :right 30}))))

(deftest anchored-popover-width-clamps-to-available-viewport-test
  (let [style (anchored-popover/anchored-popover-layout-style
               {:anchor {:left 280
                         :right 320
                         :top 48
                         :viewport-width 320
                         :viewport-height 640}
                :preferred-width-px 448
                :estimated-height-px 560})]
    (is (= "308px" (:width style)))
    (is (= "12px" (:left style)))))

(deftest centered-overlay-centers-horizontally-and-biases-up-test
  (let [style (anchored-popover/centered-overlay-layout-style
               {:anchor {:left 620 :right 680 :top 900
                         :viewport-width 1440 :viewport-height 960}
                :preferred-width-px 780
                :preferred-height-px 560})]
    (is (= "780px" (:width style)))
    (is (= "330px" (:left style)))
    (is (= "154px" (:top style)))))

(deftest centered-overlay-clamps-within-short-viewport-test
  (let [style (anchored-popover/centered-overlay-layout-style
               {:anchor {:viewport-width 400 :viewport-height 500}
                :preferred-width-px 780
                :preferred-height-px 560})]
    (is (= "376px" (:width style)))
    (is (= "12px" (:left style)))
    (is (= "12px" (:top style)))))
