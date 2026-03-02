(ns hyperopen.api.gateway.funding-hyperunit
  (:require [hyperopen.api.endpoints.funding-hyperunit :as funding-hyperunit-endpoints]))

(defn- resolve-base-url
  [deps opts]
  (or (:hyperunit-base-url opts)
      (:base-url opts)
      (:hyperunit-base-url deps)
      funding-hyperunit-endpoints/default-mainnet-base-url))

(defn- request-opts
  [opts]
  (dissoc (or opts {})
          :hyperunit-base-url
          :base-url))

(defn request-hyperunit-generate-address!
  [deps opts]
  (funding-hyperunit-endpoints/request-generate-address!
   (or (:fetch-fn deps) js/fetch)
   (resolve-base-url deps opts)
   (request-opts opts)))

(defn request-hyperunit-operations!
  [deps opts]
  (funding-hyperunit-endpoints/request-operations!
   (or (:fetch-fn deps) js/fetch)
   (resolve-base-url deps opts)
   (request-opts opts)))

(defn request-hyperunit-estimate-fees!
  [deps opts]
  (funding-hyperunit-endpoints/request-estimate-fees!
   (or (:fetch-fn deps) js/fetch)
   (resolve-base-url deps opts)
   (request-opts opts)))

(defn request-hyperunit-withdrawal-queue!
  [deps opts]
  (funding-hyperunit-endpoints/request-withdrawal-queue!
   (or (:fetch-fn deps) js/fetch)
   (resolve-base-url deps opts)
   (request-opts opts)))
