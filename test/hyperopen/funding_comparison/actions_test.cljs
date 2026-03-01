(ns hyperopen.funding-comparison.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding-comparison.actions :as actions]))

(deftest parse-funding-comparison-route-supports-hyphen-and-camel-paths-test
  (is (= :page (:kind (actions/parse-funding-comparison-route "/funding-comparison"))))
  (is (= :page (:kind (actions/parse-funding-comparison-route "/fundingComparison?coin=BTC"))))
  (is (= :other (:kind (actions/parse-funding-comparison-route "/trade")))))

(deftest load-funding-comparison-route-emits-projection-before-heavy-effects-test
  (is (= [[:effects/save [:funding-comparison-ui :loading?] true]
          [:effects/api-fetch-predicted-fundings]
          [:effects/fetch-asset-selector-markets {:phase :full}]]
         (actions/load-funding-comparison-route
          {:asset-selector {:markets []}}
          "/funding-comparison")))
  (is (= [[:effects/save [:funding-comparison-ui :loading?] true]
          [:effects/api-fetch-predicted-fundings]]
         (actions/load-funding-comparison-route
          {:asset-selector {:markets [{:coin "BTC"}]}}
          "/funding-comparison"))))

(deftest set-funding-comparison-timeframe-normalizes-synonyms-test
  (is (= [[:effects/save [:funding-comparison-ui :timeframe] :8hour]]
         (actions/set-funding-comparison-timeframe {} "8h")))
  (is (= [[:effects/save [:funding-comparison-ui :timeframe] :day]]
         (actions/set-funding-comparison-timeframe {} :day)))
  (is (= [[:effects/save [:funding-comparison-ui :timeframe] :8hour]]
         (actions/set-funding-comparison-timeframe {} :unknown))))

(deftest set-funding-comparison-sort-toggles-direction-for-same-column-test
  (is (= [[:effects/save [:funding-comparison-ui :sort]
           {:column :coin :direction :asc}]]
         (actions/set-funding-comparison-sort
          {:funding-comparison-ui {:sort {:column :coin :direction :desc}}}
          :coin)))
  (is (= [[:effects/save [:funding-comparison-ui :sort]
           {:column :open-interest :direction :desc}]]
         (actions/set-funding-comparison-sort
          {:funding-comparison-ui {:sort {:column :coin :direction :asc}}}
          :open-interest))))
