(ns hyperopen.runtime.effect-adapters.leaderboard
  (:require [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.leaderboard.cache :as leaderboard-cache]
            [hyperopen.leaderboard.preferences :as leaderboard-preferences]
            [hyperopen.leaderboard.effects :as leaderboard-effects]
            [hyperopen.platform :as platform]))

(def ^:private known-excluded-addresses
  #{"0x2d1e9d7702fc42a1dc0d19c5a4e46925d5b7d9ac"})

(defn- api-fetch-leaderboard-deps
  [store opts]
  (-> {:store store}
      (assoc :request-leaderboard! api/request-leaderboard!)
      (assoc :request-vault-index! api/request-vault-index!)
      (assoc :load-leaderboard-cache-record! leaderboard-cache/load-leaderboard-cache-record!)
      (assoc :persist-leaderboard-cache-record! leaderboard-cache/persist-leaderboard-cache-record!)
      (assoc :begin-leaderboard-load api-projections/begin-leaderboard-load)
      (assoc :apply-leaderboard-cache-hydration api-projections/apply-leaderboard-cache-hydration)
      (assoc :apply-leaderboard-success api-projections/apply-leaderboard-success)
      (assoc :apply-leaderboard-error api-projections/apply-leaderboard-error)
      (assoc :known-excluded-addresses known-excluded-addresses)
      (assoc :now-ms-fn platform/now-ms)
      (assoc :opts opts)))

(defn api-fetch-leaderboard-effect
  [_ store & [opts]]
  (leaderboard-effects/api-fetch-leaderboard!
   (api-fetch-leaderboard-deps store opts)))

(defn persist-leaderboard-preferences-effect
  [_ store]
  (leaderboard-preferences/persist-leaderboard-preferences! @store))

(defn restore-leaderboard-preferences!
  [store]
  (leaderboard-preferences/restore-leaderboard-preferences! store))
