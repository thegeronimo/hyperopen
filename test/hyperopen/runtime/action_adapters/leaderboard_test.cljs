(ns hyperopen.runtime.action-adapters.leaderboard-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.runtime.action-adapters.leaderboard :as adapters]))

(deftest leaderboard-route-and-query-adapters-preserve-action-effects-test
  (is (= [[:effects/save [:leaderboard-ui :page] 1]
          [:effects/api-fetch-leaderboard]]
         (adapters/load-leaderboard-route-action {} "/leaderboard?window=month")))
  (is (= []
         (adapters/load-leaderboard-route-action {} "/trade")))
  (is (= [[:effects/save-many [[[:leaderboard-ui :query] "desk"]
                               [[:leaderboard-ui :page] 1]]]]
         (adapters/set-leaderboard-query-action {} "desk")))
  (is (= [[:effects/save-many [[[:leaderboard-ui :query] ""]
                               [[:leaderboard-ui :page] 1]]]]
         (adapters/set-leaderboard-query-action {} nil))))

(deftest leaderboard-timeframe-sort-and-page-size-adapters-preserve-normalization-test
  (is (= [[:effects/save-many [[[:leaderboard-ui :timeframe] :all-time]
                               [[:leaderboard-ui :page] 1]]]
          [:effects/persist-leaderboard-preferences]]
         (adapters/set-leaderboard-timeframe-action {} "all time")))
  (is (= [[:effects/save-many [[[:leaderboard-ui :sort]
                                {:column :roi
                                 :direction :desc}]
                               [[:leaderboard-ui :page] 1]]]
          [:effects/persist-leaderboard-preferences]]
         (adapters/set-leaderboard-sort-action
          {:leaderboard-ui {:sort {:column :pnl
                                   :direction :desc}}}
          :roi)))
  (is (= [[:effects/save-many [[[:leaderboard-ui :sort]
                                {:column :pnl
                                 :direction :asc}]
                               [[:leaderboard-ui :page] 1]]]
          [:effects/persist-leaderboard-preferences]]
         (adapters/set-leaderboard-sort-action
          {:leaderboard-ui {:sort {:column :pnl
                                   :direction :desc}}}
          :pnl)))
  (is (= [[:effects/save-many [[[:leaderboard-ui :page-size] 50]
                               [[:leaderboard-ui :page] 1]
                               [[:leaderboard-ui :page-size-dropdown-open?] false]]]
          [:effects/persist-leaderboard-preferences]]
         (adapters/set-leaderboard-page-size-action {} "50")))
  (is (= [[:effects/save-many [[[:leaderboard-ui :page-size] 10]
                               [[:leaderboard-ui :page] 1]
                               [[:leaderboard-ui :page-size-dropdown-open?] false]]]
          [:effects/persist-leaderboard-preferences]]
         (adapters/set-leaderboard-page-size-action {} "999"))))

(deftest leaderboard-dropdown-and-pagination-adapters-preserve-bounds-test
  (is (= [[:effects/save [:leaderboard-ui :page-size-dropdown-open?] true]]
         (adapters/toggle-leaderboard-page-size-dropdown-action
          {:leaderboard-ui {:page-size-dropdown-open? false}})))
  (is (= [[:effects/save [:leaderboard-ui :page-size-dropdown-open?] false]]
         (adapters/close-leaderboard-page-size-dropdown-action {})))
  (is (= [[:effects/save [:leaderboard-ui :page] 4]]
         (adapters/set-leaderboard-page-action {} "8" 4)))
  (is (= [[:effects/save [:leaderboard-ui :page] 3]]
         (adapters/next-leaderboard-page-action
          {:leaderboard-ui {:page 2}}
          4)))
  (is (= [[:effects/save [:leaderboard-ui :page] 1]]
         (adapters/prev-leaderboard-page-action
          {:leaderboard-ui {:page 1}}
          4))))
