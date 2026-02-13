(ns hyperopen.api
  (:require [hyperopen.api.instance :as api-instance]))

(def info-url api-instance/info-url)

(def default-info-client-config
  api-instance/default-info-client-config)

(def make-default-api-service
  api-instance/make-default-api-service)

(defn make-api
  ([] (make-api {}))
  ([opts]
   (api-instance/make-api opts)))
