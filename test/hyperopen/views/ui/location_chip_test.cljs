(ns hyperopen.views.ui.location-chip-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.ui.location-chip :as location-chip]))

(deftest chip-names-each-place-in-text-test
  (doseq [[location label] [[:perps "Perps"] [:spot "Spot"] [:hyperevm "EVM"]]]
    (let [chip (location-chip/location-chip location)]
      (is (= :span (first chip)) (str location))
      (is (= (str "location-chip-" (name location)) (:data-role (hiccup/node-attrs chip))))
      (is (= [label] (hiccup/collect-strings chip))
          "the text names the place, so meaning never rests on color alone")
      (is (contains? (hiccup/node-class-set chip) "uppercase")))))

(deftest chip-is-nil-for-an-unknown-place-test
  (doseq [location [nil :core "spot" :evm]]
    (is (nil? (location-chip/location-chip location)) (pr-str location))))
