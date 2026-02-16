(ns hyperopen.orderbook.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.orderbook.actions :as actions]
            [hyperopen.orderbook.settings :as settings]))

(deftest toggle-orderbook-size-unit-dropdown-defaults-to-visible-when-missing-test
  (is (= [[:effects/save [:orderbook-ui :size-unit-dropdown-visible?] true]]
         (actions/toggle-orderbook-size-unit-dropdown {}))))

(deftest toggle-orderbook-size-unit-dropdown-hides-when-currently-visible-test
  (is (= [[:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]]
         (actions/toggle-orderbook-size-unit-dropdown {:orderbook-ui {:size-unit-dropdown-visible? true}}))))

(deftest toggle-orderbook-price-aggregation-dropdown-defaults-to-visible-when-missing-test
  (is (= [[:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] true]]
         (actions/toggle-orderbook-price-aggregation-dropdown {}))))

(deftest toggle-orderbook-price-aggregation-dropdown-hides-when-currently-visible-test
  (is (= [[:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]]
         (actions/toggle-orderbook-price-aggregation-dropdown {:orderbook-ui {:price-aggregation-dropdown-visible? true}}))))

(deftest select-functions-are-re-exported-from-settings-test
  (is (identical? settings/select-orderbook-size-unit
                  actions/select-orderbook-size-unit))
  (is (identical? settings/select-orderbook-price-aggregation
                  actions/select-orderbook-price-aggregation))
  (is (identical? settings/select-orderbook-tab
                  actions/select-orderbook-tab)))
