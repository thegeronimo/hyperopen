(ns hyperopen.views.account-info.position-overlay-mobile-layout-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.views.account-info.position-margin-modal :as position-margin-modal]
            [hyperopen.views.account-info.position-reduce-popover :as position-reduce-popover]
            [hyperopen.views.account-info.test-support.fixtures :as fixtures]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(def ^:private iphone-anchor
  {:viewport-width 430
   :viewport-height 932
   :top 780
   :bottom 812
   :right 414})

(deftest position-margin-modal-renders-mobile-bottom-sheet-layout-test
  (let [modal (position-margin/from-position-row
               {}
               (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
               iphone-anchor)
        modal-view (position-margin-modal/position-margin-modal-view modal)
        layer-node (hiccup/find-by-data-role modal-view "position-margin-mobile-sheet-layer")
        backdrop-node (hiccup/find-by-data-role modal-view "position-margin-mobile-sheet-backdrop")
        surface-node (hiccup/find-first-node modal-view #(= "true" (get-in % [1 :data-position-margin-surface])))
        surface-classes (hiccup/node-class-set surface-node)
        modal-strings (set (hiccup/collect-strings modal-view))
        close-button (hiccup/find-first-node modal-view #(= "Close margin sheet" (get-in % [1 :aria-label])))]
    (is (some? layer-node))
    (is (some? backdrop-node))
    (is (some? close-button))
    (is (contains? surface-classes "rounded-t-[22px]"))
    (is (contains? surface-classes "bg-[#06131a]"))
    (is (= true (get-in surface-node [1 :aria-modal])))
    (is (= "translateY(0)" (get-in surface-node [1 :style :transform])))
    (is (= "max(env(safe-area-inset-bottom), 1rem)"
           (get-in surface-node [1 :style :padding-bottom])))
    (is (contains? modal-strings "Adjust Margin"))
    (is (contains? modal-strings "Current Margin"))
    (is (contains? modal-strings "Margin Available to Add"))))

(deftest position-reduce-popover-renders-mobile-bottom-sheet-layout-test
  (let [popover (position-reduce/from-position-row
                 (fixtures/sample-position-row "xyz:NVDA" 10 "0.500")
                 iphone-anchor)
        popover-view (position-reduce-popover/position-reduce-popover-view popover)
        layer-node (hiccup/find-by-data-role popover-view "position-reduce-mobile-sheet-layer")
        backdrop-node (hiccup/find-by-data-role popover-view "position-reduce-mobile-sheet-backdrop")
        surface-node (hiccup/find-first-node popover-view #(= "true" (get-in % [1 :data-position-reduce-surface])))
        surface-classes (hiccup/node-class-set surface-node)
        popover-strings (set (hiccup/collect-strings popover-view))
        close-button (hiccup/find-first-node popover-view #(= "Close position sheet" (get-in % [1 :aria-label])))]
    (is (some? layer-node))
    (is (some? backdrop-node))
    (is (some? close-button))
    (is (contains? surface-classes "rounded-t-[22px]"))
    (is (contains? surface-classes "bg-[#06131a]"))
    (is (= true (get-in surface-node [1 :aria-modal])))
    (is (= "translateY(0)" (get-in surface-node [1 :style :transform])))
    (is (= "max(env(safe-area-inset-bottom), 1rem)"
           (get-in surface-node [1 :style :padding-bottom])))
    (is (contains? popover-strings "Close Position"))
    (is (contains? popover-strings "Market Close"))))
