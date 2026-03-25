(ns hyperopen.schema.runtime-registration.api-wallets)

(def effect-binding-rows
  [[:effects/api-load-api-wallets :api-load-api-wallets]
   [:effects/generate-api-wallet :generate-api-wallet]
   [:effects/api-authorize-api-wallet :api-authorize-api-wallet]
   [:effects/api-remove-api-wallet :api-remove-api-wallet]])

(def action-binding-rows
  [[:actions/load-api-wallet-route :load-api-wallet-route]
   [:actions/set-api-wallet-form-field :set-api-wallet-form-field]
   [:actions/set-api-wallet-sort :set-api-wallet-sort]
   [:actions/generate-api-wallet :generate-api-wallet]
   [:actions/open-api-wallet-authorize-modal :open-api-wallet-authorize-modal]
   [:actions/open-api-wallet-remove-modal :open-api-wallet-remove-modal]
   [:actions/close-api-wallet-modal :close-api-wallet-modal]
   [:actions/confirm-api-wallet-modal :confirm-api-wallet-modal]])
