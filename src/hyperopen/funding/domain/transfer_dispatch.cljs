(ns hyperopen.funding.domain.transfer-dispatch
  "Route-aware entry points for the Transfer mode.

   `hyperopen.funding.domain.policy` points its `transfer-preview`,
   `transfer-max-amount` and `preview` at these, which re-routes both the
   modal commands and the view-model. The Perps <-> Spot route delegates to
   the legacy functions untouched, so its requests stay byte-identical; every
   route that touches HyperEVM goes to the EVM preview. An invalid route is
   never sent down the legacy path.

   `hyperopen.funding.domain.preview` must not require this namespace; it
   would be a cycle.

   The optional `now-ms` ages the HyperCore bridge capacity (see
   `hyperopen.hyperevm.domain.bridge/evm->core-capacity`); the application
   layer always supplies the clock."
  (:require [hyperopen.funding.domain.availability :as availability]
            [hyperopen.funding.domain.evm-transfer-amounts :as evm-amounts]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.domain.preview :as preview]
            [hyperopen.funding.domain.transfer-route :as transfer-route]))

(defn- legacy-route?
  [route]
  (and (:valid? route)
       (= :core-internal (transfer-route/route-kind route))))

(defn- legacy-modal
  "The modal the legacy functions see. An explicit Perps/Spot pair drives
   `:to-perp?`, which is all they read for direction."
  [modal route]
  (if (:legacy? route)
    modal
    (assoc modal :to-perp? (= :perps (:to route)))))

(defn transfer-preview*
  ([state modal]
   (transfer-preview* state modal nil))
  ([state modal now-ms]
   (let [route (transfer-route/transfer-route modal)]
     (if (legacy-route? route)
       (preview/transfer-preview state (legacy-modal modal route))
       (evm-preview/evm-transfer-preview state modal now-ms)))))

(defn transfer-max-amount*
  "A number for the Perps <-> Spot route (unchanged), and for routes that
   touch HyperEVM a decimal string floored to the route precision, or nil
   when the balance or bridge capacity is unknown."
  ([state modal]
   (transfer-max-amount* state modal nil))
  ([state modal now-ms]
   (let [route (transfer-route/transfer-route modal)]
     (cond
       (legacy-route? route)
       (availability/transfer-max-amount state (legacy-modal modal route))

       (:valid? route)
       (evm-amounts/max-amount-text state route
                                    (transfer-route/route-token state modal)
                                    now-ms)

       :else nil))))

(defn preview*
  ([state modal]
   (preview* state modal nil))
  ([state modal now-ms]
   (if (= :transfer (preview/normalize-mode (:mode modal)))
     (transfer-preview* state modal now-ms)
     (preview/preview state modal))))
