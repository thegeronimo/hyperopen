(ns hyperopen.portfolio.optimizer.application.history-loader.api-v2
  (:require [hyperopen.portfolio.optimizer.application.history-loader.api-v2.alignment :as alignment]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.bundle :as bundle]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.codec :as codec]
            [hyperopen.portfolio.optimizer.application.history-loader.api-v2.discovery :as discovery]))

(def contract-version
  codec/contract-version)

(defn normalize-api-map
  [value]
  (codec/normalize-api-map value))

(defn normalize-discovery
  [api-body]
  (discovery/normalize-discovery api-body))

(defn with-discovery-metadata
  [local-market discovery]
  (discovery/with-discovery-metadata local-market discovery))

(defn normalize-history-body
  ([api-body]
   (bundle/normalize-history-body api-body))
  ([request api-body]
   (bundle/normalize-history-body request api-body)))

(defn normalize-history-bundle
  [request api-body]
  (bundle/normalize-history-bundle request api-body))

(defn align-api-v2-history-inputs
  [request]
  (alignment/align-api-v2-history-inputs request))
