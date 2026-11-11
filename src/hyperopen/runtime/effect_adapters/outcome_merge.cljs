(ns hyperopen.runtime.effect-adapters.outcome-merge
  (:require [nexus.registry :as nxr]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.outcomes.application.merge-effects :as merge-effects]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.order :as order-adapters]))

(defn api-submit-outcome-merge-effect
  [_ store request]
  (merge-effects/api-submit-outcome-merge!
   {:store store
    :request request
    :dispatch! nxr/dispatch
    :submit-outcome-merge! trading-api/submit-outcome-merge!
    :exchange-response-error common/exchange-response-error
    :runtime-error-message common/runtime-error-message
    :show-toast! order-adapters/show-order-feedback-toast!}))
