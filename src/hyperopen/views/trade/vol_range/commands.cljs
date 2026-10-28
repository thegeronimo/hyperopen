(ns hyperopen.views.trade.vol-range.commands
  "View command constructors for the Vol Range ticket. Same {:command-id :args}
  shape as `hyperopen.views.trade.order-form-commands`; the runtime gateway maps
  command ids to registered actions through the order-form command catalog."
  (:require [hyperopen.views.trade.order-form-placeholders :as placeholders]))

(def event-target-value placeholders/event-target-value)
(def event-target-checked placeholders/event-target-checked)
(def event-key placeholders/event-key)

(defn- command
  [command-id & args]
  {:command-id command-id
   :args (vec args)})

(defn select-scale-mode [mode]
  (command :order-form/select-scale-mode mode))

(defn select-price-range []
  (select-scale-mode :price-range))

(defn select-volatility-range []
  (select-scale-mode :volatility-range))

(defn update-draft
  "Write a draft field; `path` is relative to the Vol Range draft."
  [path value]
  (command :order-form/update-vol-range-draft path value))

(defn update-draft-from-input
  "Write a draft field from the event target value."
  [path]
  (update-draft path event-target-value))

(defn update-draft-from-checkbox
  "Write a boolean draft field from the event target checked state."
  [path]
  (update-draft path event-target-checked))

(defn set-sell-quantity-percent [pct]
  (command :order-form/set-vol-range-sell-quantity-percent pct))

(defn apply-remediation [remediation-id]
  (command :order-form/apply-vol-range-remediation remediation-id))

(defn open-review []
  (command :order-form/open-vol-range-review))

(defn close-review []
  (command :order-form/close-vol-range-review))

(defn handle-review-keydown
  "Key events on the review dialog; only Escape closes it."
  []
  (command :order-form/handle-vol-range-review-keydown event-key))

(defn set-ui-flag [flag value]
  (command :order-form/set-vol-range-ui-flag flag value))

(defn refresh-daily-candles []
  (command :order-form/refresh-vol-range-daily-candles))

;; ---------------------------------------------------------------------------
;; Execution actions. These are already-registered runtime actions with no
;; order-form command id, so they are returned as action vectors directly.
;; ---------------------------------------------------------------------------

(defn place-orders-actions
  "Explicit commit click only; Enter in a field never reaches this."
  []
  [[:actions/place-vol-range-orders]])

(defn cancel-ladder-actions [grid-id] [[:actions/cancel-vol-ladder grid-id]])
(defn refresh-ladder-actions [grid-id] [[:actions/refresh-vol-ladder grid-id]])
(defn select-ladder-actions [grid-id] [[:actions/select-vol-ladder grid-id]])

;; Repair (spec 8.6): review a ladder's rejected orders and send the frozen
;; selection. Enter never submits; only the explicit commit click does.
(defn open-repair-actions [grid-id] [[:actions/open-vol-ladder-repair grid-id]])
(defn refresh-repair-actions [] [[:actions/refresh-vol-ladder-repair]])
(defn toggle-repair-leg-actions [logical-id] [[:actions/toggle-vol-ladder-repair-leg logical-id]])
(defn close-repair-actions [] [[:actions/close-vol-ladder-repair]])
(defn repair-keydown-actions [] [[:actions/handle-vol-ladder-repair-keydown [:event/key]]])
(defn submit-repair-actions [] [[:actions/submit-vol-ladder-repair]])
