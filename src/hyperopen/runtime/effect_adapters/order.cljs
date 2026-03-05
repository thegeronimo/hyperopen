(ns hyperopen.runtime.effect-adapters.order
  (:require [nexus.registry :as nxr]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-adapters.common :as common]
            [hyperopen.runtime.state :as runtime-state]))

(defn- set-order-feedback-toast!
  [store kind message]
  (order-feedback-runtime/set-order-feedback-toast! store kind message))

(defn clear-order-feedback-toast!
  [store]
  (order-feedback-runtime/clear-order-feedback-toast! store))

(defn clear-order-feedback-toast-timeout!
  ([runtime]
   (clear-order-feedback-toast-timeout! runtime nil))
  ([runtime toast-id]
   (order-feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
    runtime
    platform/clear-timeout!
    toast-id)))

(defn- schedule-order-feedback-toast-clear!
  [runtime store toast-id]
  (order-feedback-runtime/schedule-order-feedback-toast-clear!
   {:store store
    :runtime runtime
    :toast-id toast-id
    :clear-order-feedback-toast! clear-order-feedback-toast!
    :clear-order-feedback-toast-timeout! (fn
                                           ([] (clear-order-feedback-toast-timeout! runtime))
                                           ([id] (clear-order-feedback-toast-timeout! runtime id)))
    :order-feedback-toast-duration-ms runtime-state/order-feedback-toast-duration-ms
    :set-timeout-fn platform/set-timeout!}))

(defn show-order-feedback-toast!
  ([store kind message]
   (show-order-feedback-toast! runtime-state/runtime store kind message))
  ([runtime store kind message]
   (order-feedback-runtime/show-order-feedback-toast!
    store
    kind
    message
    #(schedule-order-feedback-toast-clear! runtime %1 %2))))

(defn- order-api-effect-deps
  [runtime]
  {:dispatch! nxr/dispatch
   :exchange-response-error common/exchange-response-error
   :prune-canceled-open-orders-fn order-effects/prune-canceled-open-orders
   :runtime-error-message common/runtime-error-message
   :show-toast! (fn [store kind message]
                  (show-order-feedback-toast! runtime store kind message))})

(defn api-submit-order
  ([ctx store request]
   (api-submit-order runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-submit-order (order-api-effect-deps runtime) ctx store request)))

(defn api-cancel-order
  ([ctx store request]
   (api-cancel-order runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-cancel-order (order-api-effect-deps runtime) ctx store request)))

(defn api-submit-position-tpsl
  ([ctx store request]
   (api-submit-position-tpsl runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-submit-position-tpsl (order-api-effect-deps runtime) ctx store request)))

(defn api-submit-position-margin
  ([ctx store request]
   (api-submit-position-margin runtime-state/runtime ctx store request))
  ([runtime ctx store request]
   (order-effects/api-submit-position-margin (order-api-effect-deps runtime) ctx store request)))

(defn make-api-submit-order
  [runtime]
  (fn [ctx store request]
    (api-submit-order runtime ctx store request)))

(defn make-api-cancel-order
  [runtime]
  (fn [ctx store request]
    (api-cancel-order runtime ctx store request)))

(defn make-api-submit-position-tpsl
  [runtime]
  (fn [ctx store request]
    (api-submit-position-tpsl runtime ctx store request)))

(defn make-api-submit-position-margin
  [runtime]
  (fn [ctx store request]
    (api-submit-position-margin runtime ctx store request)))
