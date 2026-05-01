(ns hyperopen.api.default.funding-hyperunit
  (:require [hyperopen.api.gateway.funding-hyperunit :as funding-hyperunit-gateway]))

(defn request-hyperunit-operations!
  [opts]
  (funding-hyperunit-gateway/request-hyperunit-operations! {:fetch-fn js/fetch} opts))

(defn request-hyperunit-estimate-fees!
  [opts]
  (funding-hyperunit-gateway/request-hyperunit-estimate-fees! {:fetch-fn js/fetch} opts))

(defn request-hyperunit-withdrawal-queue!
  [opts]
  (funding-hyperunit-gateway/request-hyperunit-withdrawal-queue! {:fetch-fn js/fetch} opts))
