(ns hyperopen.schema.runtime-registration.leaderboard)

(def effect-binding-rows
  [[:effects/persist-leaderboard-preferences :persist-leaderboard-preferences]
   [:effects/api-fetch-leaderboard :api-fetch-leaderboard]])

(def action-binding-rows
  [[:actions/load-leaderboard-route :load-leaderboard-route]
   [:actions/load-leaderboard :load-leaderboard]
   [:actions/set-leaderboard-query :set-leaderboard-query]
   [:actions/set-leaderboard-timeframe :set-leaderboard-timeframe]
   [:actions/set-leaderboard-sort :set-leaderboard-sort]
   [:actions/set-leaderboard-page-size :set-leaderboard-page-size]
   [:actions/toggle-leaderboard-page-size-dropdown :toggle-leaderboard-page-size-dropdown]
   [:actions/close-leaderboard-page-size-dropdown :close-leaderboard-page-size-dropdown]
   [:actions/set-leaderboard-page :set-leaderboard-page]
   [:actions/next-leaderboard-page :next-leaderboard-page]
   [:actions/prev-leaderboard-page :prev-leaderboard-page]])
