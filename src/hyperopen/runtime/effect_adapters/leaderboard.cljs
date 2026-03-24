(ns hyperopen.runtime.effect-adapters.leaderboard
  (:require [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.leaderboard.effects :as leaderboard-effects]))

(def ^:private known-excluded-addresses
  #{"0x2d1e9d7702fc42a1dc0d19c5a4e46925d5b7d9ac"})

(defn api-fetch-leaderboard-effect
  [_ store]
  (leaderboard-effects/api-fetch-leaderboard!
   {:store store
    :request-leaderboard! api/request-leaderboard!
    :request-vault-index! api/request-vault-index!
    :begin-leaderboard-load api-projections/begin-leaderboard-load
    :apply-leaderboard-success api-projections/apply-leaderboard-success
    :apply-leaderboard-error api-projections/apply-leaderboard-error
    :known-excluded-addresses known-excluded-addresses}))
