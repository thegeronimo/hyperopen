(ns hyperopen.runtime.effect-adapters.borrow-lend
  (:require [nexus.registry :as nxr]
            [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.borrow-lend.effects :as borrow-lend-effects]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.effect-adapters.order :as order-adapters]))

(defn api-fetch-borrow-lend-reserves-effect
  [_ store]
  (borrow-lend-effects/api-fetch-borrow-lend-reserves!
   {:store store
    :request-borrow-lend-reserve-states! api/request-borrow-lend-reserve-states!
    :begin-borrow-lend-reserves-load api-projections/begin-borrow-lend-reserves-load
    :apply-borrow-lend-reserves-success api-projections/apply-borrow-lend-reserves-success
    :apply-borrow-lend-reserves-error api-projections/apply-borrow-lend-reserves-error}))

(defn api-fetch-borrow-lend-user-state-effect
  [_ store address]
  (borrow-lend-effects/api-fetch-borrow-lend-user-state!
   {:store store
    :address address
    :request-borrow-lend-user-state! api/request-borrow-lend-user-state!
    :begin-borrow-lend-user-state-load api-projections/begin-borrow-lend-user-state-load
    :apply-borrow-lend-user-state-success api-projections/apply-borrow-lend-user-state-success
    :apply-borrow-lend-user-state-error api-projections/apply-borrow-lend-user-state-error}))

(defn api-fetch-borrow-lend-interest-effect
  [_ store address]
  (borrow-lend-effects/api-fetch-borrow-lend-interest!
   {:store store
    :address address
    :request-borrow-lend-interest! api/request-borrow-lend-interest!
    :begin-borrow-lend-interest-load api-projections/begin-borrow-lend-interest-load
    :apply-borrow-lend-interest-success api-projections/apply-borrow-lend-interest-success
    :apply-borrow-lend-interest-error api-projections/apply-borrow-lend-interest-error}))

(defn api-submit-borrow-lend-effect
  [_ store request]
  (borrow-lend-effects/api-submit-borrow-lend!
   {:store store
    :request request
    :dispatch! nxr/dispatch
    :submit-borrow-lend! trading-api/submit-borrow-lend!
    :exchange-response-error common/exchange-response-error
    :runtime-error-message common/runtime-error-message
    :show-toast! order-adapters/show-order-feedback-toast!}))
