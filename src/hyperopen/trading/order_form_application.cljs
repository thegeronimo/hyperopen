(ns hyperopen.trading.order-form-application
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-type-registry :as order-types]))

(defn order-form-context [state]
  (let [draft (trading/order-form-draft state)
        ui-state (trading/order-form-ui-state state)
        runtime-state (trading/order-form-runtime-state state)
        market-info (trading/market-info state)
        order-type (:type draft)
        order-type-capabilities (order-types/order-type-entry order-type)
        summary (trading/order-summary state draft)
        submitting? (:submitting? runtime-state)
        submit-policy (trading/submit-policy state draft {:mode :view
                                                          :submitting? submitting?})]
    {:draft draft
     :ui-state ui-state
     :runtime-state runtime-state
     :market-info market-info
     :order-type-capabilities order-type-capabilities
     :summary summary
     :submitting? submitting?
     :submit-policy submit-policy}))
