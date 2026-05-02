(ns hyperopen.views.asset-selector.controls-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.system :as app-system]
            [hyperopen.views.asset-selector.controls :as controls]
            [hyperopen.views.asset-selector.test-support :as support]
            [nexus.registry :as nxr]))

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

(deftest search-controls-focuses-and-selects-input-on-mount-test
  (let [controls-node (controls/search-controls "ETH" false false)
        search-input (support/find-first-node controls-node
                                              (fn [candidate]
                                                (and (vector? candidate)
                                                     (= :input (first candidate))
                                                     (= "Search" (get-in candidate [1 :placeholder])))))
        on-render (get-in search-input [1 :replicant/on-render])
        focus-calls (atom 0)
        select-calls (atom 0)]
    (is (fn? on-render))
    (with-redefs [platform/queue-microtask! (fn [f] (f))]
      (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                  :replicant/node #js {:isConnected true
                                       :focus (fn []
                                                (swap! focus-calls inc))
                                       :select (fn []
                                                 (swap! select-calls inc))}}))
    (is (= 1 @focus-calls))
    (is (= 1 @select-calls))
    (with-redefs [platform/queue-microtask! (fn [f] (f))]
      (on-render {:replicant/life-cycle :replicant.life-cycle/update
                  :replicant/node #js {:isConnected true
                                       :focus (fn []
                                                (swap! focus-calls inc))
                                       :select (fn []
                                                 (swap! select-calls inc))}}))
    (is (= 1 @focus-calls))
    (is (= 1 @select-calls))))

(deftest search-controls-dispatch-selector-shortcuts-from-input-test
  (let [dispatches* (atom [])
        prevent-calls* (atom 0)
        stop-calls* (atom 0)
        controls-node (controls/search-controls "" false false ["perp:BTC" "perp:ETH"])
        search-input (support/find-first-node controls-node
                                              (fn [candidate]
                                                (and (vector? candidate)
                                                     (= :input (first candidate))
                                                     (= "Search" (get-in candidate [1 :placeholder])))))
        keydown (get-in search-input [1 :on :keydown])
        arrow-event #js {:key "ArrowDown"
                         :metaKey false
                         :ctrlKey false
                         :preventDefault (fn []
                                           (swap! prevent-calls* inc))
                         :stopPropagation (fn []
                                            (swap! stop-calls* inc))}
        input-event #js {:key "e"
                         :metaKey false
                         :ctrlKey false
                         :preventDefault (fn []
                                           (swap! prevent-calls* inc))
                         :stopPropagation (fn []
                                            (swap! stop-calls* inc))}]
    (is (fn? keydown))
    (with-redefs [app-system/store ::store
                  nxr/dispatch (fn [store event actions]
                                 (swap! dispatches* conj {:store store
                                                          :event event
                                                          :actions actions}))]
      (keydown arrow-event)
      (keydown input-event))
    (is (= 1 @prevent-calls*))
    (is (= 1 @stop-calls*))
    (is (= [{:store ::store
             :event nil
             :actions [[:actions/handle-asset-selector-shortcut
                        "ArrowDown"
                        false
                        false
                        ["perp:BTC" "perp:ETH"]]]}]
           @dispatches*))))

(deftest tab-rows-include-outcome-and-outcome-period-subfilters-test
  (let [desktop (controls/tab-row :outcome)
        mobile (controls/mobile-tab-row :outcome)
        subtabs (controls/outcome-subtab-row :outcome)
        strings (set (concat (support/collect-strings desktop)
                             (support/collect-strings mobile)
                             (support/collect-strings subtabs)))]
    (is (contains? strings "Outcome"))
    (is (contains? strings "Crypto (15m)"))
    (is (contains? strings "Crypto (1d)"))))

(deftest outcome-sort-headers-use-chance-labels-test
  (let [desktop (controls/sort-controls :price :desc :outcome)
        mobile (controls/mobile-sort-header :price :desc :outcome)
        strings (set (concat (support/collect-strings desktop)
                             (support/collect-strings mobile)))]
    (is (contains? strings "% Chance"))
    (is (not (contains? strings "Last Price")))))
