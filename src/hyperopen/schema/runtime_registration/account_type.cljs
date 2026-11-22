(ns hyperopen.schema.runtime-registration.account-type)

(def effect-binding-rows
  [[:effects/api-set-user-abstraction :api-set-user-abstraction]])

(def effect-order-policy-required-action-ids
  #{})

(def action-binding-rows
  [[:actions/open-account-type-modal :open-account-type-modal]
   [:actions/close-account-type-modal :close-account-type-modal]
   [:actions/select-account-type :select-account-type]
   [:actions/submit-account-type :submit-account-type]])
