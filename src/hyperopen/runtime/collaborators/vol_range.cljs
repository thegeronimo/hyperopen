(ns hyperopen.runtime.collaborators.vol-range
  (:require [hyperopen.vol-range.actions :as vol-range-actions]
            [hyperopen.vol-range.execution-actions :as execution-actions]
            [hyperopen.vol-range.repair-actions :as repair-actions]))

(defn action-deps []
  {:select-scale-mode vol-range-actions/select-scale-mode
   :update-vol-range-draft vol-range-actions/update-vol-range-draft
   :set-vol-range-sell-quantity-percent vol-range-actions/set-vol-range-sell-quantity-percent
   :apply-vol-range-remediation vol-range-actions/apply-vol-range-remediation
   :open-vol-range-review vol-range-actions/open-vol-range-review
   :close-vol-range-review vol-range-actions/close-vol-range-review
   :handle-vol-range-review-keydown vol-range-actions/handle-vol-range-review-keydown
   :set-vol-range-ui-flag vol-range-actions/set-vol-range-ui-flag
   :refresh-vol-range-daily-candles vol-range-actions/refresh-vol-range-daily-candles})

(defn execution-action-deps
  "Release A placement and Vol ladders dock handlers (a separate group so the
  A0 preview catalog stays exactly the A0 commands)."
  []
  {:place-vol-range-orders execution-actions/place-vol-range-orders
   :cancel-vol-ladder execution-actions/cancel-vol-ladder
   :refresh-vol-ladder execution-actions/refresh-vol-ladder
   :select-vol-ladder execution-actions/select-vol-ladder
   :sync-vol-ladders execution-actions/sync-vol-ladders
   :reconcile-open-vol-ladders execution-actions/reconcile-open-vol-ladders
   :open-vol-ladder-repair repair-actions/open-vol-ladder-repair
   :refresh-vol-ladder-repair repair-actions/refresh-vol-ladder-repair
   :toggle-vol-ladder-repair-leg repair-actions/toggle-vol-ladder-repair-leg
   :close-vol-ladder-repair repair-actions/close-vol-ladder-repair
   :handle-vol-ladder-repair-keydown repair-actions/handle-vol-ladder-repair-keydown
   :submit-vol-ladder-repair repair-actions/submit-vol-ladder-repair})
