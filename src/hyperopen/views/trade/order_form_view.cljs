(ns hyperopen.views.trade.order-form-view
  (:require [hyperopen.margin-rec.state :as margin-rec-state]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.ui.voice :as voice]
            [hyperopen.views.degen.order-form :as degen-order-form]
            [hyperopen.views.trade.order-form-component-primitives :as primitives]
            [hyperopen.views.trade.order-form-component-sections :as sections]
            [hyperopen.views.trade.order-form-controls :as controls]
            [hyperopen.views.trade.order-form-feedback :as feedback]
            [hyperopen.views.trade.order-form-footer :as footer]
            [hyperopen.views.trade.order-form-handlers :as handlers]
            [hyperopen.views.trade.order-form-vm :as order-form-vm]))

(defn- action-side-tab
  [label side active? on-click]
  (let [active-classes (if (= side :sell)
                         ["border-ho-sell-hi" "text-ho-sell-hi"]
                         ["border-primary" "text-primary"])
        dot-class (if (= side :sell) "bg-ho-sell-hi" "bg-primary")]
    [:button {:type "button"
              :role "tab"
              :aria-selected (boolean active?)
              :class (into ["flex"
                            "h-12"
                            "w-full"
                            "items-center"
                            "justify-center"
                            "gap-2"
                            "border-b-2"
                            "text-sm"
                            "font-medium"
                            "transition-colors"
                            "focus:outline-none"
                            "focus:ring-0"
                            "focus:ring-offset-0"]
                           (if active?
                             active-classes
                             ["border-transparent" "text-ho-text-secondary" "hover:text-ho-text"]))
              :on {:click on-click}}
     (when active?
       [:span {:class ["h-1.5" "w-1.5" "rounded-full" dot-class]
               :data-role "outcome-action-side-active-dot"}])
     label]))

(defn- action-side-tabs
  [side side-handlers]
  [:div {:class ["relative" "grid" "h-12" "grid-cols-2" "items-end" "border-y" "border-base-300"]
         :data-role "outcome-action-side-tabs"
         :role "tablist"
         :aria-label "Order action side"}
   [:span {:class ["pointer-events-none" "absolute" "left-1/2" "top-4" "h-4" "w-px" "bg-base-300"]
           :data-role "outcome-action-side-divider"}]
   (action-side-tab "Buy" :buy (= side :buy) ((:on-select-side side-handlers) :buy))
   (action-side-tab "Sell" :sell (= side :sell) ((:on-select-side side-handlers) :sell))])

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
                account-mode-label
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
        twap-section? (some #{:twap} (order-form-vm/order-type-sections type))
        ;; Section renderers only receive (form callbacks), so everything the TWAP section
        ;; needs that it cannot derive from the form -- the resolved schedule, the guard
        ;; band, the side-dependent Max/Min label -- rides in the callbacks map.
        twap-schedule (when twap-section?
                        (feedback/twap-schedule state form base-symbol))
        twap-guards (when twap-section?
                      (feedback/twap-guards state form))
        twap-submit (feedback/twap-submit-copy {:order-type type
                                                :schedule twap-schedule
                                                :guards twap-guards})
        section-handlers (cond-> (:order-type-sections handlers)
                           twap-schedule
                           (assoc :twap-schedule twap-schedule)

                           twap-guards
                           (assoc :twap-guards twap-guards))
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
     [:div {:class (into ["flex" "flex-col" "gap-2" "sm:gap-2.5"]
                         (when read-only? ["opacity-60" "pointer-events-none"]))}
      ;; Leverage/margin is perp-only — spot has no leverage.
      (when (and (not outcome?) (not spot?))
        (controls/leverage-row state
                               (:margin-mode form)
                               cross-margin-allowed?
                               margin-mode-dropdown-open?
                               leverage-popover-open?
                               ui-leverage
                               leverage-draft
                               max-leverage
                               leverage-handlers
                               account-mode-label))
      (when (and (not outcome?) (not spot?))
        (degen-order-form/leverage-warning-banner state ui-leverage))

      (sections/entry-mode-tabs {:entry-mode entry-mode
                                 :type type
                                 :pro-dropdown-open? pro-dropdown-open?
                                 :pro-tab-label pro-tab-label
                                 :pro-dropdown-options pro-dropdown-options
                                 :order-type-label order-form-vm/order-type-label}
                                entry-mode-handlers)

      (if outcome?
        (action-side-tabs side side-handlers)
        (let [degen? (voice/degen? state)]
          (controls/side-row side
                             side-handlers
                             {:buy-label (voice/label state :order-form/buy)
                              :sell-label (voice/label state :order-form/sell)
                              :massive? degen?
                              :buy-sublabel (when degen? "(number go up pls)")
                              :sell-sublabel (when degen? "(get me out)")})))
      (when outcome?
        [:div {:class ["space-y-1.5"]}
         (controls/outcome-side-row outcome-sides
                                    outcome-side-index
                                    outcome-handlers
                                    {:action-prefix (if (= side :sell)
                                                      "Sell "
                                                      "Buy ")
                                     :side->intent (fn [outcome-side]
                                                     (if (= 1 (:side-index outcome-side))
                                                       :sell
                                                       :buy))})])
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
                          :notch-overlap-threshold notch-overlap-threshold
                          :degen-risk (degen-order-form/size-risk state
                                                                  size-percent
                                                                  ui-leverage)}
                         size-handlers)

      (sections/render-order-type-sections type
                                           form
                                           section-handlers)

      (when (or (not outcome?) show-limit-like-controls?)
        [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
         ;; Reduce-only is perp-only (spot has no position to reduce).
         (when (and (not outcome?) (not spot?))
           (primitives/row-toggle "Reduce Only"
                                  (:reduce-only form)
                                  (:on-toggle-reduce-only toggle-handlers)))
         (when show-limit-like-controls?
           (sections/tif-inline-control form
                                        (assoc tif-handlers
                                               :dropdown-open? tif-dropdown-open?)))])

      ;; TP/SL brackets are perp-only (they place reduce-only legs against a
      ;; position); spot has no position, so the toggle/panel are hidden.
      (when (and (not outcome?) (not spot?) show-tpsl-toggle?)
        (primitives/row-toggle "Take Profit / Stop Loss"
                               show-tpsl-panel?
                               (:on-toggle-tpsl-panel toggle-handlers)))

      (when (and (not outcome?) (not spot?) show-tpsl-panel?)
        (sections/tp-sl-panel tpsl-panel tp-sl-handlers))

      (when (and (not outcome?) show-post-only?)
        (primitives/row-toggle "Post Only"
                               (:post-only form)
                               (:on-toggle-post-only toggle-handlers)))

      ;; Isolated perp orders can auto top-up to the modeled margin
      ;; recommendation after the fill (one-shot, background).
      (when (and (not outcome?)
                 (not spot?)
                 (= :isolated (:margin-mode form)))
        (let [auto-topup? (trading-settings/margin-rec-auto-topup? state)
              coin (get-in state [:active-market :coin])
              rec-target (when (and auto-topup? (seq coin))
                           (margin-rec-state/ready-recommended-equity
                            state
                            (margin-rec-state/position-key-for-coin coin)))]
          [:div {:class ["flex" "flex-col" "gap-1"]
                 :data-role "margin-rec-auto-topup-row"}
           (primitives/row-toggle "Auto top-up isolated margin"
                                  auto-topup?
                                  [[:actions/set-margin-rec-auto-topup (not auto-topup?)]])
           (when auto-topup?
             [:div {:class ["text-xs" "leading-4" "text-gray-500"]
                    :data-role "margin-rec-auto-topup-note"}
              (str "After this order fills, isolated margin is topped up once to"
                   " the modeled recommendation"
                   (when (number? rec-target)
                     (str " (currently $" (.toFixed rec-target 2) ")"))
                   ", capped by available collateral. It never repeats or chases"
                   " a falling position.")])]))

      (when error
        [:div {:class ["text-xs" "text-red-400"]} error])

      (when spectate-mode-read-only?
        (feedback/spectate-mode-stop-affordance))

      (when-not spectate-mode-read-only?
        (footer/submit-row {:submitting? submitting?
                            :submit-disabled? (:disabled? submit)
                            :submit-tooltip (:tooltip submit)
                            :submit-label (or (:label twap-submit)
                                              (voice/label state :order-form/submit))
                            :submitting-label (voice/label state :order-form/submitting)
                            :submit-recap (:recap twap-submit)
                            :on-submit (:on-submit submit-handlers)}))

      (footer/footer-metrics display
                             (and show-liquidation-row? (not spot?))
                             (not spot?)
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
