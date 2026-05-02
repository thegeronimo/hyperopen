(ns hyperopen.views.trade.order-form-commands
  (:require [hyperopen.views.trade.order-form-placeholders :as placeholders]))

(def event-target-value placeholders/event-target-value)
(def event-target-checked placeholders/event-target-checked)
(def event-key placeholders/event-key)

(defn- command
  [command-id & args]
  {:command-id command-id
   :args (vec args)})

(defn select-entry-mode [mode]
  (command :order-form/select-entry-mode mode))

(defn select-entry-market []
  (select-entry-mode :market))

(defn select-entry-limit []
  (select-entry-mode :limit))

(defn toggle-pro-order-type-dropdown []
  (command :order-form/toggle-pro-order-type-dropdown))

(defn close-pro-order-type-dropdown []
  (command :order-form/close-pro-order-type-dropdown))

(defn handle-pro-order-type-dropdown-keydown [key]
  (command :order-form/handle-pro-order-type-dropdown-keydown key))

(defn toggle-margin-mode-dropdown []
  (command :order-form/toggle-margin-mode-dropdown))

(defn close-margin-mode-dropdown []
  (command :order-form/close-margin-mode-dropdown))

(defn handle-margin-mode-dropdown-keydown [key]
  (command :order-form/handle-margin-mode-dropdown-keydown key))

(defn toggle-leverage-popover []
  (command :order-form/toggle-leverage-popover))

(defn close-leverage-popover []
  (command :order-form/close-leverage-popover))

(defn handle-leverage-popover-keydown [key]
  (command :order-form/handle-leverage-popover-keydown key))

(defn toggle-size-unit-dropdown []
  (command :order-form/toggle-size-unit-dropdown))

(defn close-size-unit-dropdown []
  (command :order-form/close-size-unit-dropdown))

(defn handle-size-unit-dropdown-keydown [key]
  (command :order-form/handle-size-unit-dropdown-keydown key))

(defn toggle-tpsl-unit-dropdown []
  (command :order-form/toggle-tpsl-unit-dropdown))

(defn close-tpsl-unit-dropdown []
  (command :order-form/close-tpsl-unit-dropdown))

(defn handle-tpsl-unit-dropdown-keydown [key]
  (command :order-form/handle-tpsl-unit-dropdown-keydown key))

(defn toggle-tif-dropdown []
  (command :order-form/toggle-tif-dropdown))

(defn close-tif-dropdown []
  (command :order-form/close-tif-dropdown))

(defn handle-tif-dropdown-keydown [key]
  (command :order-form/handle-tif-dropdown-keydown key))

(defn select-pro-order-type [order-type]
  (command :order-form/select-pro-order-type order-type))

(defn set-order-ui-leverage-draft [leverage]
  (command :order-form/set-order-ui-leverage-draft leverage))

(defn confirm-order-ui-leverage []
  (command :order-form/confirm-order-ui-leverage))

(defn set-order-ui-leverage [leverage]
  (command :order-form/set-order-ui-leverage leverage))

(defn set-order-margin-mode [mode]
  (command :order-form/set-order-margin-mode mode))

(defn- update-order-field [path value]
  (command :order-form/update-order-form path value))

(defn update-order-form [path value]
  (update-order-field path value))

(defn set-order-side [side]
  (update-order-field [:side] side))

(defn set-order-outcome-side [side-index]
  (update-order-field [:outcome-side] side-index))

(defn set-limit-price-input []
  (update-order-field [:price] event-target-value))

(defn set-order-size-display-input []
  (command :order-form/set-order-size-display event-target-value))

(defn set-order-size-input-mode [mode]
  (command :order-form/set-order-size-input-mode mode))

(defn set-order-size-input-mode-input []
  (command :order-form/set-order-size-input-mode event-target-value))

(defn set-order-size-percent-input []
  (command :order-form/set-order-size-percent event-target-value))

(defn focus-order-price-input []
  (command :order-form/focus-order-price-input))

(defn blur-order-price-input []
  (command :order-form/blur-order-price-input))

(defn set-order-price-to-mid []
  (command :order-form/set-order-price-to-mid))

(defn toggle-order-tpsl-panel []
  (command :order-form/toggle-order-tpsl-panel))

(defn toggle-reduce-only []
  (update-order-field [:reduce-only] event-target-checked))

(defn toggle-post-only []
  (update-order-field [:post-only] event-target-checked))

(defn set-tif-input []
  (update-order-field [:tif] event-target-value))

(defn set-order-tif [tif]
  (update-order-field [:tif] tif))

(defn set-trigger-price-input []
  (update-order-field [:trigger-px] event-target-value))

(defn set-scale-start-input []
  (update-order-field [:scale :start] event-target-value))

(defn set-scale-end-input []
  (update-order-field [:scale :end] event-target-value))

(defn set-scale-count-input []
  (update-order-field [:scale :count] event-target-value))

(defn set-scale-skew-input []
  (update-order-field [:scale :skew] event-target-value))

(defn set-twap-hours-input []
  (update-order-field [:twap :hours] event-target-value))

(defn set-twap-minutes-input []
  (update-order-field [:twap :minutes] event-target-value))

(defn toggle-twap-randomize []
  (update-order-field [:twap :randomize] event-target-checked))

(defn toggle-tp-enabled []
  (update-order-field [:tp :enabled?] event-target-checked))

(defn set-tp-trigger-input []
  (update-order-field [:tp :trigger] event-target-value))

(defn set-tp-offset-input []
  (update-order-field [:tp :offset-input] event-target-value))

(defn toggle-tp-market []
  (update-order-field [:tp :is-market] event-target-checked))

(defn set-tp-limit-input []
  (update-order-field [:tp :limit] event-target-value))

(defn toggle-sl-enabled []
  (update-order-field [:sl :enabled?] event-target-checked))

(defn set-sl-trigger-input []
  (update-order-field [:sl :trigger] event-target-value))

(defn set-sl-offset-input []
  (update-order-field [:sl :offset-input] event-target-value))

(defn toggle-sl-market []
  (update-order-field [:sl :is-market] event-target-checked))

(defn set-sl-limit-input []
  (update-order-field [:sl :limit] event-target-value))

(defn set-tpsl-unit-input []
  (update-order-field [:tpsl :unit] event-target-value))

(defn set-order-tpsl-unit [unit]
  (update-order-field [:tpsl :unit] unit))

(defn submit-order []
  (command :order-form/submit-order))
