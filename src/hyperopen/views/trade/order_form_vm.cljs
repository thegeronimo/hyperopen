(ns hyperopen.views.trade.order-form-vm
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-application :as application]
            [hyperopen.trading.order-type-registry :as order-types]
            [hyperopen.views.trade.order-form-vm-selectors :as selectors]
            [hyperopen.views.trade.order-form-vm-submit :as submit]))

(def order-type-config
  order-types/order-type-config)

(defn order-type-label [order-type]
  (order-types/order-type-label order-type))

(defn order-type-sections [order-type]
  (order-types/order-type-sections order-type))

(defn pro-dropdown-options []
  (order-types/pro-order-types))

(defn pro-tab-label [entry-mode order-type]
  (if (= entry-mode :pro)
    (order-type-label order-type)
    "Pro"))

(defn order-form-vm [state]
  (let [{:keys [draft
                ui
                runtime-state
                market-info
                order-type-capabilities
                pricing-policy
                scale-preview-lines
                summary
                submitting?
                submit-policy]}
        (application/order-form-context state)
        normalized-form draft
        {:keys [quote-symbol
                base-symbol
                spot?
                hip3?
                read-only?
                sz-decimals
                max-leverage]}
        market-info
        side (:side normalized-form)
        type (:type normalized-form)
        entry-mode (:entry-mode normalized-form)
        pro-dropdown-open? (boolean (get-in ui [:entry :pro-dropdown-open?]))
        pro-mode? (= entry-mode :pro)
        tpsl-panel-open? (boolean (get-in ui [:panels :tpsl-open?]))
        controls (selectors/order-type-controls {:entry-mode entry-mode
                                                 :pro-mode? pro-mode?
                                                 :tpsl-panel-open? tpsl-panel-open?
                                                 :order-type-capabilities order-type-capabilities})
        summary-display (selectors/summary-display summary sz-decimals)
        ui-leverage (:ui-leverage normalized-form)
        size-percent (trading/clamp-percent (:size-percent normalized-form))
        price (selectors/price-model pricing-policy)
        submit-form (:form submit-policy)
        submit-errors (:errors submit-policy)
        required-submit-fields (:required-fields submit-policy)
        submit-tooltip (submit/submit-tooltip-from-policy submit-policy)
        submit-disabled? (:disabled? submit-policy)]
    {:form normalized-form
     :side side
     :type type
     :entry-mode entry-mode
     :pro-dropdown-open? pro-dropdown-open?
     :tpsl-panel-open? tpsl-panel-open?
     :pro-dropdown-options (pro-dropdown-options)
     :pro-tab-label (pro-tab-label entry-mode type)
     :controls controls
     :spot? spot?
     :hip3? hip3?
     :read-only? read-only?
     :display summary-display
     :ui-leverage ui-leverage
     :next-leverage (selectors/next-leverage ui-leverage max-leverage)
     :size-percent size-percent
     :display-size-percent (selectors/display-size-percent size-percent)
     :notch-overlap-threshold selectors/notch-overlap-threshold
     :size-input-mode (:size-input-mode normalized-form)
     :size-display (:size-display normalized-form)
     :price price
     :quote-symbol quote-symbol
     :base-symbol base-symbol
     :scale-preview-lines scale-preview-lines
     :error (:error runtime-state)
     :submitting? submitting?
     :submit {:form submit-form
              :errors submit-errors
              :required-fields required-submit-fields
              :reason (:reason submit-policy)
              :error-message (:error-message submit-policy)
              :tooltip submit-tooltip
              :market-price-missing? (:market-price-missing? submit-policy)
              :disabled? submit-disabled?}}))
