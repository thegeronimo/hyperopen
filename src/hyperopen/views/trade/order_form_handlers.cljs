(ns hyperopen.views.trade.order-form-handlers
  (:require [hyperopen.views.trade.order-form-commands :as cmd]
            [hyperopen.views.trade.order-form-intent-adapter :as intent-adapter]))

(defn- dispatch-command
  [command]
  (intent-adapter/command->actions command))

(defn build-handlers []
  {:entry-mode {:on-close-dropdown (dispatch-command (cmd/close-pro-order-type-dropdown))
                :on-select-entry-market (dispatch-command (cmd/select-entry-market))
                :on-select-entry-limit (dispatch-command (cmd/select-entry-limit))
                :on-toggle-dropdown (dispatch-command (cmd/toggle-pro-order-type-dropdown))
                :on-dropdown-keydown (dispatch-command (cmd/handle-pro-order-type-dropdown-keydown cmd/event-key))
                :on-select-pro-order-type (fn [order-type]
                                            (dispatch-command (cmd/select-pro-order-type order-type)))}

   :leverage {:on-toggle-margin-mode-dropdown (dispatch-command (cmd/toggle-margin-mode-dropdown))
              :on-close-margin-mode-dropdown (dispatch-command (cmd/close-margin-mode-dropdown))
              :on-margin-mode-dropdown-keydown (dispatch-command (cmd/handle-margin-mode-dropdown-keydown cmd/event-key))
              :on-select-margin-mode (fn [mode]
                                       (dispatch-command (cmd/set-order-margin-mode mode)))
              :on-toggle-leverage-popover (dispatch-command (cmd/toggle-leverage-popover))
              :on-close-leverage-popover (dispatch-command (cmd/close-leverage-popover))
              :on-leverage-popover-keydown (dispatch-command (cmd/handle-leverage-popover-keydown cmd/event-key))
              :on-set-leverage-draft (dispatch-command (cmd/set-order-ui-leverage-draft cmd/event-target-value))
              :on-confirm-leverage (dispatch-command (cmd/confirm-order-ui-leverage))}

   :side {:on-select-side (fn [side]
                            (dispatch-command (cmd/set-order-side side)))}

   :outcome {:on-select-outcome-side (fn [side-index]
                                       (dispatch-command (cmd/set-order-outcome-side side-index)))}

   :price {:on-set-to-mid (dispatch-command (cmd/set-order-price-to-mid))
           :on-focus (dispatch-command (cmd/focus-order-price-input))
           :on-blur (dispatch-command (cmd/blur-order-price-input))
           :on-change (dispatch-command (cmd/set-limit-price-input))}

   :size {:on-change-display (dispatch-command (cmd/set-order-size-display-input))
          :on-change-mode (dispatch-command (cmd/set-order-size-input-mode-input))
          :on-toggle-dropdown (dispatch-command (cmd/toggle-size-unit-dropdown))
          :on-close-dropdown (dispatch-command (cmd/close-size-unit-dropdown))
          :on-dropdown-keydown (dispatch-command (cmd/handle-size-unit-dropdown-keydown cmd/event-key))
          :on-select-mode (fn [mode]
                            (dispatch-command (cmd/set-order-size-input-mode mode)))
          :on-change-percent (dispatch-command (cmd/set-order-size-percent-input))}

   :order-type-sections {:on-set-trigger-price (dispatch-command (cmd/set-trigger-price-input))
                         :on-set-scale-start (dispatch-command (cmd/set-scale-start-input))
                         :on-set-scale-end (dispatch-command (cmd/set-scale-end-input))
                         :on-set-scale-count (dispatch-command (cmd/set-scale-count-input))
                         :on-set-scale-skew (dispatch-command (cmd/set-scale-skew-input))
                         :on-set-twap-hours (dispatch-command (cmd/set-twap-hours-input))
                         :on-set-twap-minutes (dispatch-command (cmd/set-twap-minutes-input))
                         :on-toggle-twap-randomize (dispatch-command (cmd/toggle-twap-randomize))}

   :toggles {:on-toggle-reduce-only (dispatch-command (cmd/toggle-reduce-only))
             :on-toggle-post-only (dispatch-command (cmd/toggle-post-only))
             :on-toggle-tpsl-panel (dispatch-command (cmd/toggle-order-tpsl-panel))}

   :tif {:on-toggle-dropdown (dispatch-command (cmd/toggle-tif-dropdown))
         :on-close-dropdown (dispatch-command (cmd/close-tif-dropdown))
         :on-dropdown-keydown (dispatch-command (cmd/handle-tif-dropdown-keydown cmd/event-key))
         :on-select-tif (fn [tif]
                          (into []
                                (concat
                                 (dispatch-command (cmd/close-tif-dropdown))
                                 (dispatch-command (cmd/set-order-tif tif)))))}

   :tp-sl {:on-set-tp-trigger (dispatch-command (cmd/set-tp-trigger-input))
           :on-set-tp-offset (dispatch-command (cmd/set-tp-offset-input))
           :on-set-sl-trigger (dispatch-command (cmd/set-sl-trigger-input))
           :on-set-sl-offset (dispatch-command (cmd/set-sl-offset-input))
           :on-toggle-unit-dropdown (dispatch-command (cmd/toggle-tpsl-unit-dropdown))
           :on-close-unit-dropdown (dispatch-command (cmd/close-tpsl-unit-dropdown))
           :on-unit-dropdown-keydown (dispatch-command (cmd/handle-tpsl-unit-dropdown-keydown cmd/event-key))
           :on-select-tpsl-unit (fn [unit]
                                  (into []
                                        (concat
                                         (dispatch-command (cmd/close-tpsl-unit-dropdown))
                                         (dispatch-command (cmd/set-order-tpsl-unit unit)))))} 

   :submit {:on-submit (dispatch-command (cmd/submit-order))}})
