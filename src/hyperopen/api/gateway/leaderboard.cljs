(ns hyperopen.api.gateway.leaderboard
  (:require [hyperopen.api.endpoints.leaderboard :as leaderboard-endpoints]))

(defn request-leaderboard!
  ([deps]
   (request-leaderboard! deps {}))
  ([{:keys [fetch-fn leaderboard-url]} opts]
   (leaderboard-endpoints/request-leaderboard! (or fetch-fn js/fetch)
                                               (or leaderboard-url
                                                   leaderboard-endpoints/default-leaderboard-url)
                                               opts)))
