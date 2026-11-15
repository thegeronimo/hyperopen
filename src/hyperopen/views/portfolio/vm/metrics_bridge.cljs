(ns hyperopen.views.portfolio.vm.metrics-bridge
  (:require [hyperopen.portfolio.application.metrics-bridge :as app-metrics-bridge]))

(def metrics-request-signature
  app-metrics-bridge/metrics-request-signature)
