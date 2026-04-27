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

(defn- report-progress!
  [on-progress progress-state kind coin total]
  (when (fn? on-progress)
    (let [completed (swap! progress-state inc)]
      (on-progress {:kind kind
                    :coin coin
                    :completed completed
                    :total total
                    :percent (if (pos? total)
                               (* 100 (/ completed total))
                               100)}))))

(defn- tracked-request
  [request! on-progress progress-state kind total {:keys [coin opts]}]
  (-> (request! coin opts)
      (.then (fn [result]
               (report-progress! on-progress progress-state kind coin total)
               result))))

(defn- request-candle-entry
  [request-candle-snapshot! on-progress progress-state total {:keys [coin] :as request}]
  [coin (tracked-request request-candle-snapshot!
                         on-progress
                         progress-state
                         :candles
                         total
                         request)])

(defn- request-funding-entry
  [request-market-funding-history! on-progress progress-state total {:keys [coin] :as request}]
  [coin (tracked-request request-market-funding-history!
                         on-progress
                         progress-state
                         :funding
                         total
                         request)])

(defn request-history-bundle!
  [{:keys [request-candle-snapshot!
           request-market-funding-history!
           on-progress]}
   {:keys [universe] :as request}]
  (let [plan (history-loader/build-history-request-plan universe request)
        total (+ (count (:candle-requests plan))
                 (count (:funding-requests plan)))
        progress-state (atom 0)
        candle-entries (map (partial request-candle-entry
                                     request-candle-snapshot!
                                     on-progress
                                     progress-state
                                     total)
                            (:candle-requests plan))
        funding-entries (map (partial request-funding-entry
                                      request-market-funding-history!
                                      on-progress
                                      progress-state
                                      total)
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
