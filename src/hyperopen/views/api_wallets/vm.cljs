(ns hyperopen.views.api-wallets.vm
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api-wallets.application.form-policy :as form-policy]
            [hyperopen.api-wallets.application.ui-state :as ui-state]
            [hyperopen.api-wallets.domain.policy :as policy]))

(defn- sort-state
  [state]
  (ui-state/normalize-sort-state
   (get-in state [:api-wallets-ui :sort])))

(defn api-wallets-vm
  [state]
  (let [owner-address (account-context/owner-address state)
        spectating? (account-context/spectate-mode-active? state)
        form (merge (ui-state/default-form)
                    (get-in state [:api-wallets-ui :form]))
        form-errors (form-policy/form-errors form)
        modal (merge (ui-state/default-modal-state)
                     (get-in state [:api-wallets-ui :modal]))
        sort (sort-state state)
        rows (policy/sorted-rows
              (policy/merged-rows (get-in state [:api-wallets :extra-agents])
                                  (get-in state [:api-wallets :default-agent-row]))
              sort)
        loading? (or (true? (get-in state [:api-wallets :loading :extra-agents?]))
                     (true? (get-in state [:api-wallets :loading :default-agent?])))
        error (or (get-in state [:api-wallets :errors :extra-agents])
                  (get-in state [:api-wallets :errors :default-agent]))
        server-time-ms (get-in state [:api-wallets :server-time-ms])
        generated-private-key (form-policy/generated-private-key
                               (get-in state [:api-wallets-ui :generated])
                               (:address form))
        authorize-disabled? (or loading?
                                (not (seq owner-address))
                                (not (form-policy/form-valid? form))
                                (true? (:submitting? modal)))
        modal-type (:type modal)
        modal-confirm-disabled? (or (true? (:submitting? modal))
                                    (case modal-type
                                      :authorize authorize-disabled?
                                      :remove (not (map? (:row modal)))
                                      true))]
    {:connected? (boolean (seq owner-address))
     :owner-address owner-address
     :spectating? spectating?
     :rows rows
     :sort sort
     :loading? loading?
     :error error
     :form form
     :form-errors form-errors
     :form-error (get-in state [:api-wallets-ui :form-error])
     :authorize-disabled? authorize-disabled?
     :modal modal
     :generated-private-key generated-private-key
     :generated-address (get-in state [:api-wallets-ui :generated :address])
     :valid-until-preview-ms (form-policy/valid-until-preview-ms server-time-ms
                                                                 (:days-valid form))
     :modal-confirm-disabled? modal-confirm-disabled?}))
