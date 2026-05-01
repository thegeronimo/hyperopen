(ns hyperopen.api.default.leaderboard
  (:require [hyperopen.api.gateway.leaderboard :as leaderboard-gateway]))

(defn request-leaderboard!
  [opts]
  (leaderboard-gateway/request-leaderboard! {:fetch-fn js/fetch} opts))
