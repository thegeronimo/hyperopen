(ns hyperopen.schema.runtime-registration.funding-comparison)

(def effect-binding-rows
  [[:effects/api-fetch-predicted-fundings :api-fetch-predicted-fundings]])

(def action-binding-rows
  [[:actions/load-funding-comparison-route :load-funding-comparison-route]
   [:actions/load-funding-comparison :load-funding-comparison]
   [:actions/set-funding-comparison-query :set-funding-comparison-query]
   [:actions/set-funding-comparison-timeframe :set-funding-comparison-timeframe]
   [:actions/set-funding-comparison-sort :set-funding-comparison-sort]])
