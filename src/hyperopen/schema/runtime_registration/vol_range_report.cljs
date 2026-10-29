(ns hyperopen.schema.runtime-registration.vol-range-report
  "Registration rows for Vol Range reporting (HO-SPEC-014 v2.1 section 12):
  the `[:effects/vol-range-report op params]` effect and the Portfolio
  report actions. No action needs an effect-order policy: each emits at most
  one report effect after its projection writes.")

(def effect-binding-rows
  [[:effects/vol-range-report :vol-range-report]])

(def action-binding-rows
  [[:actions/load-vol-range-report :load-vol-range-report]
   [:actions/refresh-vol-range-report :refresh-vol-range-report]
   [:actions/select-vol-range-report-campaign :select-vol-range-report-campaign]
   [:actions/request-start-vol-range-tracking :request-start-vol-range-tracking]
   [:actions/cancel-start-vol-range-tracking :cancel-start-vol-range-tracking]
   [:actions/confirm-start-vol-range-tracking :confirm-start-vol-range-tracking]])

(def effect-order-policy-required-action-ids #{})
