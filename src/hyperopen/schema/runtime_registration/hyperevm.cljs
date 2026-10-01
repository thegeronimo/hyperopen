(ns hyperopen.schema.runtime-registration.hyperevm)

;; HyperEVM balance and bridge reads, the background receipt check for a
;; HyperEVM -> Core move left confirming, and the Balances tab's location
;; filter. None of these actions is covered by the effect-order policy: they
;; emit only reads and :effects/save.

(def effect-binding-rows
  [[:effects/fetch-hyperevm-balances :fetch-hyperevm-balances]
   [:effects/fetch-hyperevm-core-bridge-balance :fetch-hyperevm-core-bridge-balance]
   [:effects/fetch-hyperevm-core-account-status :fetch-hyperevm-core-account-status]
   [:effects/fetch-hyperevm-in-flight-receipt :fetch-hyperevm-in-flight-receipt]])

(def action-binding-rows
  [[:actions/refresh-hyperevm-balances :refresh-hyperevm-balances]
   [:actions/refresh-hyperevm-bridge-capacity :refresh-hyperevm-bridge-capacity]
   [:actions/check-hyperevm-in-flight :check-hyperevm-in-flight]
   [:actions/set-balances-location-filter :set-balances-location-filter]])

(def effect-order-policy-required-action-ids
  #{})
