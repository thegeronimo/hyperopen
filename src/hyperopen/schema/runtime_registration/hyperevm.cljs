(ns hyperopen.schema.runtime-registration.hyperevm)

(def effect-binding-rows
  [[:effects/fetch-hyperevm-balances :fetch-hyperevm-balances]
   [:effects/fetch-hyperevm-core-bridge-balance :fetch-hyperevm-core-bridge-balance]
   [:effects/fetch-hyperevm-core-account-status :fetch-hyperevm-core-account-status]
   [:effects/fetch-hyperevm-in-flight-receipt :fetch-hyperevm-in-flight-receipt]])

(def action-binding-rows
  [[:actions/refresh-hyperevm-balances :refresh-hyperevm-balances]
   [:actions/retry-hyperevm-balances :retry-hyperevm-balances]
   [:actions/refresh-hyperevm-bridge-capacity :refresh-hyperevm-bridge-capacity]
   [:actions/check-hyperevm-in-flight :check-hyperevm-in-flight]
   [:actions/set-balances-location-filter :set-balances-location-filter]])

(def effect-order-policy-required-action-ids
  #{})
