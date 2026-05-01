(ns hyperopen.api.info-client
  (:require [hyperopen.api.info-client.runtime :as runtime]
            [hyperopen.api.info-client.stats :as stats]))

(def default-config runtime/default-config)

(defn top-request-hotspots
  ([stats]
   (stats/top-request-hotspots stats))
  ([stats opts]
   (stats/top-request-hotspots stats opts)))

(defn make-info-client
  [opts]
  (runtime/make-info-client opts))
