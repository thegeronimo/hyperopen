(ns hyperopen.funding-comparison.effects
  (:require [hyperopen.api.promise-effects :as promise-effects]))

(defn api-fetch-predicted-fundings!
  [{:keys [store
           request-predicted-fundings!
           begin-funding-comparison-load
           apply-funding-comparison-success
           apply-funding-comparison-error
           opts]}]
  (swap! store begin-funding-comparison-load)
  (-> (request-predicted-fundings! (or opts {}))
      (.then (promise-effects/apply-success-and-return
              store
              apply-funding-comparison-success))
      (.catch (promise-effects/apply-error-and-reject
               store
               apply-funding-comparison-error))))
