(ns hyperopen.portfolio.optimizer.infrastructure.history-client
  (:require [hyperopen.portfolio.optimizer.application.history-loader :as history-loader]))

(defn- promise-all
  [promises]
  (js/Promise.all (clj->js (vec promises))))

(defn- resolve-map
  [entries]
  (let [entries* (vec entries)]
    (-> (promise-all (map second entries*))
        (.then (fn [results]
                 (into {}
                       (map (fn [[entry result]]
                              [(first entry) result])
                            (map vector entries* (array-seq results)))))))))

(defn- request-candle-entry
  [request-candle-snapshot! {:keys [coin opts]}]
  [coin (request-candle-snapshot! coin opts)])

(defn- request-funding-entry
  [request-market-funding-history! {:keys [coin opts]}]
  [coin (request-market-funding-history! coin opts)])

(defn request-history-bundle!
  [{:keys [request-candle-snapshot!
           request-market-funding-history!]}
   {:keys [universe] :as request}]
  (let [plan (history-loader/build-history-request-plan universe request)
        candle-entries (map (partial request-candle-entry request-candle-snapshot!)
                            (:candle-requests plan))
        funding-entries (map (partial request-funding-entry request-market-funding-history!)
                             (:funding-requests plan))]
    (-> (promise-all [(resolve-map candle-entries)
                      (resolve-map funding-entries)])
        (.then (fn [results]
                 (let [[candle-history-by-coin funding-history-by-coin]
                       (array-seq results)]
                   {:candle-history-by-coin candle-history-by-coin
                    :funding-history-by-coin funding-history-by-coin
                    :warnings (:warnings plan)
                    :request-plan plan}))))))
