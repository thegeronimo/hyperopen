(ns hyperopen.schema.runtime-registration.borrow-lend)

(def effect-binding-rows
  [[:effects/api-fetch-borrow-lend-reserves :api-fetch-borrow-lend-reserves]
   [:effects/api-fetch-borrow-lend-user-state :api-fetch-borrow-lend-user-state]
   [:effects/api-fetch-borrow-lend-interest :api-fetch-borrow-lend-interest]
   [:effects/api-submit-borrow-lend :api-submit-borrow-lend]])

(def effect-order-policy-required-action-ids
  #{:actions/load-borrow-lend-route
    :actions/load-borrow-lend
    :actions/submit-borrow-lend})

(def action-binding-rows
  [[:actions/load-borrow-lend-route :load-borrow-lend-route]
   [:actions/load-borrow-lend :load-borrow-lend]
   [:actions/set-borrow-lend-tab :set-borrow-lend-tab]
   [:actions/open-borrow-lend-modal :open-borrow-lend-modal]
   [:actions/close-borrow-lend-modal :close-borrow-lend-modal]
   [:actions/set-borrow-lend-modal-kind :set-borrow-lend-modal-kind]
   [:actions/set-borrow-lend-amount :set-borrow-lend-amount]
   [:actions/set-borrow-lend-amount-fraction :set-borrow-lend-amount-fraction]
   [:actions/submit-borrow-lend :submit-borrow-lend]])
