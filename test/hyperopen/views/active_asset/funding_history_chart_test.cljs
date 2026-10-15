(ns hyperopen.views.active-asset.funding-history-chart-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.active-asset.funding-history-chart :as chart]))

(def ^:private oct-12-utc
  (.UTC js/Date 2026 9 12))

(deftest readout-text-uses-utc-date-and-signed-apy-test
  (is (= "Oct 12 · +12.40% APY"
         (chart/readout-text {:day-start-ms oct-12-utc :apy 12.4} 0 30)))
  (is (= "Oct 12 · -3.50% APY"
         (chart/readout-text {:day-start-ms oct-12-utc :apy -3.5} 0 30)))
  (is (= "Oct 12 · no data"
         (chart/readout-text {:day-start-ms oct-12-utc :apy nil} 0 30))))

(deftest readout-text-falls-back-to-days-ago-without-a-date-test
  (is (= "Today · +1.00% APY" (chart/readout-text {:apy 1} 2 3)))
  (is (= "2d ago · +1.00% APY" (chart/readout-text {:apy 1} 0 3))))

(deftest hover-layer-has-one-css-only-target-per-day-test
  (let [model {:points [{:day-index 1 :day-start-ms oct-12-utc :apy 10}
                        {:day-index 2 :apy nil :undefined? true}
                        {:day-index 3 :apy -2}]
               :mean-apy 4
               :max-apy 10
               :day-count 3}
        node (chart/funding-history-chart model :bars)
        days (hiccup/find-all-nodes node #(= "funding-history-day" (get-in % [1 :data-role])))
        readouts (hiccup/find-all-nodes node #(= "funding-history-readout" (get-in % [1 :data-role])))]
    (is (= 3 (count days)))
    (is (= ["Oct 12 · +10.00% APY" "1d ago · no data" "Today · -2.00% APY"]
           (mapv #(first (hiccup/collect-strings %)) readouts)))
    (is (every? #(contains? (hiccup/node-class-set %) "group/day") days))
    (is (nil? (hiccup/find-first-node node #(contains? (hiccup/node-attrs %) :on))))))
