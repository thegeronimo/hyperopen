(ns hyperopen.api
  (:require [hyperopen.api.instance :as api-instance]))

(defn make-api
  ([] (make-api {}))
  ([opts]
   (api-instance/make-api opts)))
