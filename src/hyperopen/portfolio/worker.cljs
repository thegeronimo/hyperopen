(ns hyperopen.portfolio.worker
  (:require [cljs.reader :as reader]
            [hyperopen.portfolio.metrics :as metrics]))

(defn- handle-message [^js e]
  (let [data (.-data e)
        id (.-id data)
        type (keyword (.-type data))
        payload-str (.-payload data)
        payload (reader/read-string payload-str)]
    (case type
      :compute-metrics
      (let [{:keys [portfolio-request benchmark-requests]} payload
            portfolio-result (metrics/compute-performance-metrics
                              {:strategy-cumulative-rows (:strategy-cumulative-rows portfolio-request)
                               :strategy-daily-rows (:strategy-daily-rows portfolio-request)
                               :benchmark-daily-rows (:benchmark-daily-rows portfolio-request)
                               :rf (or (:rf portfolio-request) 0)
                               :mar (or (:mar portfolio-request) 0)
                               :periods-per-year (or (:periods-per-year portfolio-request) 365)
                               :quality-gates (:quality-gates portfolio-request)})
            benchmark-results (into {}
                                    (map (fn [{:keys [coin request]}]
                                           [coin (metrics/compute-performance-metrics
                                                  {:strategy-cumulative-rows (:strategy-cumulative-rows request)
                                                   :strategy-daily-rows (:strategy-daily-rows request)
                                                   :rf 0
                                                   :periods-per-year 365})]))
                                    benchmark-requests)]
        (.postMessage js/self (clj->js {:id id
                                        :type "metrics-result"
                                        :payload (pr-str {:portfolio-values portfolio-result
                                                          :benchmark-values-by-coin benchmark-results})})))
      
      (js/console.warn "Unknown message type received in portfolio worker:" type))))

(defn ^:export init []
  (js/console.log "Portfolio Web Worker initialized.")
  (.addEventListener js/self "message" handle-message))
