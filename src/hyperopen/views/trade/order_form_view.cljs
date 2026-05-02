(ns hyperopen.views.trade.order-form-view
  (:require [hyperopen.state.trading :as trading]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]
            [hyperopen.views.trade.order-form-component-sections :as sections]
            [hyperopen.views.trade.order-form-controls :as controls]
            [hyperopen.views.trade.order-form-feedback :as feedback]
            [hyperopen.views.trade.order-form-footer :as footer]
            [hyperopen.views.trade.order-form-handlers :as handlers]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(defn render-order-form
  [{:keys [state vm handlers ui]}]
  (let [{:keys [form
                side
                type
                entry-mode
                pro-dropdown-open?
                pro-dropdown-options
                pro-tab-label
                controls
                spot?
                outcome?
                outcome-sides
                outcome-side-index
                read-only?
                display
                ui-leverage
                size-percent
                display-size-percent
                notch-overlap-threshold
                size-input-mode
                size-display
                price
                quote-symbol
                base-symbol
                scale-preview-lines
                error
                submitting?
                submit]} vm
        margin-mode-dropdown-open? (boolean (or (:margin-mode-dropdown-open? ui)
                                                (get-in state [:order-form-ui :margin-mode-dropdown-open?])))
        leverage-popover-open? (boolean (or (:leverage-popover-open? ui)
                                            (get-in state [:order-form-ui :leverage-popover-open?])))
        leverage-draft (or (:leverage-draft ui)
                           (get-in state [:order-form-ui :leverage-draft]))
        size-unit-dropdown-open? (boolean (or (:size-unit-dropdown-open? ui)
                                              (get-in state [:order-form-ui :size-unit-dropdown-open?])))
        tif-dropdown-open? (boolean (or (:tif-dropdown-open? ui)
                                        (get-in state [:order-form-ui :tif-dropdown-open?])))
        max-leverage (or (:max-leverage ui)
                         (trading/market-max-leverage state))
        cross-margin-allowed? (if (contains? ui :cross-margin-allowed?)
                                (:cross-margin-allowed? ui)
                                (trading/cross-margin-allowed? state))
        entry-mode-handlers (:entry-mode handlers)
        leverage-handlers (:leverage handlers)
        side-handlers (:side handlers)
        outcome-handlers (:outcome handlers)
        price-handlers (:price handlers)
        size-handlers (:size handlers)
        {:keys [show-limit-like-controls?
                show-tpsl-toggle?
                show-tpsl-panel?
                show-post-only?
                show-scale-preview?
                show-liquidation-row?
                show-slippage-row?]}
        controls
        twap-preview (when (some #{:twap} (order-form-vm/order-type-sections type))
                       (feedback/twap-preview state form base-symbol))
        section-handlers (cond-> (:order-type-sections handlers)
                           twap-preview
                           (assoc :twap-preview twap-preview))
        toggle-handlers (:toggles handlers)
        tif-handlers (:tif handlers)
        tp-sl-handlers (:tp-sl handlers)
        submit-handlers (:submit handlers)
        fee-copy (footer/fee-row-copy (:fees display))
        spectate-mode-read-only? (= :spectate-mode-read-only (:reason submit))
        tpsl-panel (when show-tpsl-panel?
                     (feedback/tpsl-panel-model state form side ui-leverage controls))]
    [:div {:class ["bg-base-100"
                   "border"
                   "border-base-300"
                   "rounded-none"
                   "spectate-none"
                   "p-2.5"
                   "sm:p-3"
                   "font-sans"
                   "flex"
                   "flex-col"
                   "gap-2"
                   "sm:gap-2.5"]
           :data-parity-id "order-form"}
     (when spot?
       (feedback/unsupported-market-banner
        "Spot trading is not supported yet. You can still view spot charts and order books."))

     [:div {:class (into ["flex" "flex-col" "gap-2" "sm:gap-2.5"]
                         (when read-only? ["opacity-60" "pointer-events-none"]))}
      (controls/leverage-row state
                             (:margin-mode form)
                             cross-margin-allowed?
                             margin-mode-dropdown-open?
                             leverage-popover-open?
                             ui-leverage
                             leverage-draft
                             max-leverage
                             leverage-handlers)

      (sections/entry-mode-tabs {:entry-mode entry-mode
                                 :type type
                                 :pro-dropdown-open? pro-dropdown-open?
                                 :pro-tab-label pro-tab-label
                                 :pro-dropdown-options pro-dropdown-options
                                 :order-type-label order-form-vm/order-type-label}
                                entry-mode-handlers)

      (controls/side-row side side-handlers)
      (when outcome?
        (controls/outcome-side-row outcome-sides
                                   outcome-side-index
                                   outcome-handlers))
      (controls/balances-row display)

      (when show-limit-like-controls?
        (controls/price-row price quote-symbol price-handlers))

      (controls/size-row {:size-display size-display
                          :size-input-mode size-input-mode
                          :quote-symbol quote-symbol
                          :base-symbol base-symbol
                          :size-unit-dropdown-open? size-unit-dropdown-open?
                          :display-size-percent display-size-percent
                          :size-percent size-percent
                          :notch-overlap-threshold notch-overlap-threshold}
                         size-handlers)

      (sections/render-order-type-sections type
                                           form
                                           section-handlers)

      [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
       (primitives/row-toggle "Reduce Only"
                              (:reduce-only form)
                              (:on-toggle-reduce-only toggle-handlers))
       (when show-limit-like-controls?
         (sections/tif-inline-control form
                                      (assoc tif-handlers
                                             :dropdown-open? tif-dropdown-open?)))]

      (when show-tpsl-toggle?
        (primitives/row-toggle "Take Profit / Stop Loss"
                               show-tpsl-panel?
                               (:on-toggle-tpsl-panel toggle-handlers)))

      (when show-tpsl-panel?
        (sections/tp-sl-panel tpsl-panel tp-sl-handlers))

      (when show-post-only?
        (primitives/row-toggle "Post Only"
                               (:post-only form)
                               (:on-toggle-post-only toggle-handlers)))

      (when error
        [:div {:class ["text-xs" "text-red-400"]} error])

      (when spectate-mode-read-only?
        (feedback/spectate-mode-stop-affordance))

      (when-not spectate-mode-read-only?
        (footer/submit-row {:submitting? submitting?
                            :submit-disabled? (:disabled? submit)
                            :submit-tooltip (:tooltip submit)
                            :on-submit (:on-submit submit-handlers)}))

      (footer/footer-metrics display
                             show-liquidation-row?
                             show-slippage-row?
                             fee-copy
                             (when show-scale-preview? scale-preview-lines))]]))

(defn order-form-view [state]
  (render-order-form
   {:state state
    :vm (order-form-vm/order-form-vm state)
    :handlers (handlers/build-handlers)
    :ui {:margin-mode-dropdown-open? (boolean (get-in state [:order-form-ui :margin-mode-dropdown-open?]))
         :leverage-popover-open? (boolean (get-in state [:order-form-ui :leverage-popover-open?]))
         :leverage-draft (get-in state [:order-form-ui :leverage-draft])
         :size-unit-dropdown-open? (boolean (get-in state [:order-form-ui :size-unit-dropdown-open?]))
         :tif-dropdown-open? (boolean (get-in state [:order-form-ui :tif-dropdown-open?]))
         :max-leverage (trading/market-max-leverage state)
         :cross-margin-allowed? (trading/cross-margin-allowed? state)}}))
