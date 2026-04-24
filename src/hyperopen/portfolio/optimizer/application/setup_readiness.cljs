(ns hyperopen.portfolio.optimizer.application.setup-readiness
  (:require [hyperopen.portfolio.optimizer.application.current-portfolio :as current-portfolio]
            [hyperopen.portfolio.optimizer.application.request-builder :as request-builder]))

(defn- current-as-of-ms
  [state]
  (or (get-in state [:portfolio :optimizer :runtime :as-of-ms])
      (.now js/Date)))

(defn- build-request
  [state draft]
  (request-builder/build-engine-request
   {:draft draft
    :current-portfolio (current-portfolio/current-portfolio-snapshot state)
    :history-data (get-in state [:portfolio :optimizer :history-data])
    :market-cap-by-coin (get-in state [:portfolio :optimizer :market-cap-by-coin])
    :as-of-ms (current-as-of-ms state)
    :stale-after-ms (get-in state [:portfolio :optimizer :runtime :stale-after-ms])
    :funding-periods-per-year (get-in state
                                      [:portfolio :optimizer :runtime :funding-periods-per-year])}))

(defn build-readiness
  [state]
  (let [draft (get-in state [:portfolio :optimizer :draft])
        requested-universe (vec (or (:universe draft) []))]
    (if (empty? requested-universe)
      {:status :blocked
       :reason :missing-universe
       :runnable? false
       :request nil
       :warnings []}
      (let [request (build-request state draft)
            runnable? (boolean (seq (:universe request)))]
        {:status (if runnable? :ready :blocked)
         :reason (when-not runnable? :no-eligible-history)
         :runnable? runnable?
         :request request
         :warnings (vec (:warnings request))}))))
