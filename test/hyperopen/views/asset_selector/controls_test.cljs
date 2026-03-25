(ns hyperopen.views.asset-selector.controls-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.asset-selector.controls :as controls]
            [hyperopen.views.asset-selector.test-support :as support]))

(deftest tooltip-position-classes-cover-default-and-explicit-directions-test
  (doseq [[position expected-panel-class expected-arrow-class]
          [[nil "bottom-full" "top-full"]
           ["bottom" "top-full" "bottom-full"]
           ["left" "right-full" "left-full"]
           ["right" "left-full" "right-full"]]]
    (let [tooltip-node (controls/tooltip [[:span "APR"] "Annualized funding"] position)
          panel-node (last (support/node-children tooltip-node))
          arrow-node (support/find-first-node tooltip-node #(contains? (set (support/collect-all-classes %)) "border-4"))
          panel-classes (set (support/collect-all-classes panel-node))
          arrow-classes (set (support/collect-all-classes arrow-node))]
      (is (contains? panel-classes expected-panel-class))
      (is (contains? panel-classes "group-hover:opacity-100"))
      (is (contains? arrow-classes expected-arrow-class)))))

(deftest search-controls-use-parity-input-styling-test
  (let [controls-node (controls/search-controls "" false false)
        search-input (support/find-first-node controls-node
                                              (fn [candidate]
                                                (and (vector? candidate)
                                                     (= :input (first candidate))
                                                     (= "Search" (get-in candidate [1 :placeholder])))))
        attrs (second search-input)
        classes (set (support/collect-all-classes search-input))]
    (is (some? search-input))
    (is (= "Search assets" (:aria-label attrs)))
    (is (contains? classes "asset-selector-search-input"))
    (is (contains? classes "focus:outline-none"))
    (is (contains? classes "focus:ring-0"))
    (is (not (contains? classes "ring-primary")))))
