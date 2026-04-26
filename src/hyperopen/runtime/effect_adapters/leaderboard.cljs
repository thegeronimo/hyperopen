(ns hyperopen.runtime.effect-adapters.leaderboard
  (:require [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.leaderboard.cache :as leaderboard-cache]
            [hyperopen.leaderboard.preferences :as leaderboard-preferences]
            [hyperopen.leaderboard.effects :as leaderboard-effects]
            [hyperopen.platform :as platform]))

(def ^:private known-excluded-addresses
  #{"0x2d1e9d7702fc42a1dc0d19c5a4e46925d5b7d9ac"})

(defn api-fetch-leaderboard-effect
  [_ store & [opts]]
  (leaderboard-effects/api-fetch-leaderboard!
   {:store store
    :request-leaderboard! api/request-leaderboard!
    :request-vault-index! api/request-vault-index!
    :load-leaderboard-cache-record! leaderboard-cache/load-leaderboard-cache-record!
    :persist-leaderboard-cache-record! leaderboard-cache/persist-leaderboard-cache-record!
    :begin-leaderboard-load api-projections/begin-leaderboard-load
    :apply-leaderboard-cache-hydration api-projections/apply-leaderboard-cache-hydration
    :apply-leaderboard-success api-projections/apply-leaderboard-success
    :apply-leaderboard-error api-projections/apply-leaderboard-error
    :known-excluded-addresses known-excluded-addresses
    :now-ms-fn platform/now-ms
    :opts opts}))

(defn persist-leaderboard-preferences-effect
  [_ store]
  (leaderboard-preferences/persist-leaderboard-preferences! @store))

(defn restore-leaderboard-preferences!
  [store]
  (leaderboard-preferences/restore-leaderboard-preferences! store))
