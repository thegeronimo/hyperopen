(ns hyperopen.schema.runtime-registration.builder-codes)

(def effect-binding-rows
  [[:effects/api-load-builder-codes :api-load-builder-codes]
   [:effects/api-revoke-builder :api-revoke-builder]])

(def effect-order-policy-required-action-ids
  #{})

(def action-binding-rows
  [[:actions/load-builder-codes-route :load-builder-codes-route]
   [:actions/revoke-builder :revoke-builder]])
