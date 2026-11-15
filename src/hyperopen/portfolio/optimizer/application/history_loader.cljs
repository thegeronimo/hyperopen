(ns hyperopen.portfolio.optimizer.application.history-loader
  (:require [hyperopen.portfolio.optimizer.application.history-loader.alignment :as alignment]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2 :as api-v2]
            [hyperopen.portfolio.optimizer.application.history-loader.request-plan :as request-plan]))

(def default-bars
  request-plan/default-bars)

(def default-funding-periods-per-year
  alignment/default-funding-periods-per-year)

(defn build-history-request-plan
  [universe opts]
  (request-plan/build-history-request-plan universe opts))

(defn align-history-inputs
  [request]
  (if (:api-v2-history request)
    (api-v2/align-api-v2-history-inputs request)
    (alignment/align-history-inputs request)))
