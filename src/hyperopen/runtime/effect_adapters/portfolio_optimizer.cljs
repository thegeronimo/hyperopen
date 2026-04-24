(ns hyperopen.runtime.effect-adapters.portfolio-optimizer
  (:require [hyperopen.portfolio.optimizer.application.run-bridge :as run-bridge]))

(def ^:dynamic *request-run!* run-bridge/request-run!)

(defn run-portfolio-optimizer-effect
  ([_ store request request-signature]
   (run-portfolio-optimizer-effect nil store request request-signature nil))
  ([_ store request request-signature opts]
   (let [opts* (or opts {})]
     (*request-run!*
      (cond-> {:request request
               :request-signature request-signature
               :store store}
        (contains? opts* :computed-at-ms)
        (assoc :computed-at-ms (:computed-at-ms opts*)))))))
