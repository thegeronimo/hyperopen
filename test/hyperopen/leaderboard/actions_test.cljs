(ns hyperopen.leaderboard.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.leaderboard.actions :as actions]))

(deftest parse-leaderboard-route-normalizes-supported-paths-test
  (is (= {:kind :page
          :path "/leaderboard"}
         (actions/parse-leaderboard-route "/leaderboard/")))
  (is (= {:kind :page
          :path "/leaderboard"}
         (actions/parse-leaderboard-route "/leaderboard?window=month")))
  (is (= {:kind :other
          :path "/trade"}
         (actions/parse-leaderboard-route "/trade"))))

(deftest load-leaderboard-route-emits-projection-before-heavy-effect-test
  (is (= [[:effects/save [:leaderboard-ui :page] 1]
          [:effects/api-fetch-leaderboard]]
         (actions/load-leaderboard-route {} "/leaderboard")))
  (is (= []
         (actions/load-leaderboard-route {} "/portfolio"))))

(deftest leaderboard-timeframe-normalizes-month-and-all-time-test
  (is (= :month (actions/normalize-leaderboard-timeframe nil)))
  (is (= :all-time (actions/normalize-leaderboard-timeframe "all time")))
  (is (= [[:effects/save-many [[[:leaderboard-ui :timeframe] :week]
                               [[:leaderboard-ui :page] 1]]]]
         (actions/set-leaderboard-timeframe {} :week))))

(deftest leaderboard-sort-and-pagination-actions-reset-or-clamp-page-test
  (is (= [[:effects/save-many [[[:leaderboard-ui :sort]
                                {:column :roi
                                 :direction :desc}]
                               [[:leaderboard-ui :page] 1]]]]
         (actions/set-leaderboard-sort
          {:leaderboard-ui {:sort {:column :pnl
                                   :direction :desc}}}
          :roi)))
  (is (= [[:effects/save-many [[[:leaderboard-ui :sort]
                                {:column :pnl
                                 :direction :asc}]
                               [[:leaderboard-ui :page] 1]]]]
         (actions/set-leaderboard-sort
          {:leaderboard-ui {:sort {:column :pnl
                                   :direction :desc}}}
          :pnl)))
  (is (= [[:effects/save [:leaderboard-ui :page] 3]]
         (actions/set-leaderboard-page {} "8" 3)))
  (is (= [[:effects/save [:leaderboard-ui :page] 1]]
         (actions/prev-leaderboard-page {:leaderboard-ui {:page 1}} 4))))
