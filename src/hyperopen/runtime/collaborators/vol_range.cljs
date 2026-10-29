(ns hyperopen.runtime.collaborators.vol-range
  (:require [hyperopen.vol-range.actions :as vol-range-actions]
            [hyperopen.vol-range.execution-actions :as execution-actions]
            [hyperopen.vol-range.repair-actions :as repair-actions]
            [hyperopen.vol-range.replace-actions :as replace-actions]
            [hyperopen.vol-range.report-actions :as report-actions]))

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
   :resync-vol-ladders execution-actions/resync-vol-ladders
   :open-vol-ladder-repair repair-actions/open-vol-ladder-repair
   :refresh-vol-ladder-repair repair-actions/refresh-vol-ladder-repair
   :toggle-vol-ladder-repair-leg repair-actions/toggle-vol-ladder-repair-leg
   :close-vol-ladder-repair repair-actions/close-vol-ladder-repair
   :handle-vol-ladder-repair-keydown repair-actions/handle-vol-ladder-repair-keydown
   :submit-vol-ladder-repair repair-actions/submit-vol-ladder-repair
   :preview-vol-ladder-update replace-actions/preview-vol-ladder-update
   :open-vol-ladder-update replace-actions/open-vol-ladder-update
   :close-vol-ladder-update replace-actions/close-vol-ladder-update
   :handle-vol-ladder-update-keydown replace-actions/handle-vol-ladder-update-keydown
   :cancel-vol-ladder-for-update replace-actions/cancel-vol-ladder-for-update
   :check-vol-ladder-update replace-actions/check-vol-ladder-update
   :toggle-vol-ladder-update-rejected replace-actions/toggle-vol-ladder-update-rejected
   :review-vol-ladder-replacement replace-actions/review-vol-ladder-replacement
   :stop-vol-ladder-update replace-actions/stop-vol-ladder-update})

(defn report-action-deps
  "Release B Portfolio report handlers (a separate group: reporting never
  implies placement permission)."
  []
  {:load-vol-range-report report-actions/load-vol-range-report
   :refresh-vol-range-report report-actions/refresh-vol-range-report
   :select-vol-range-report-campaign report-actions/select-vol-range-report-campaign
   :request-start-vol-range-tracking report-actions/request-start-vol-range-tracking
   :cancel-start-vol-range-tracking report-actions/cancel-start-vol-range-tracking
   :confirm-start-vol-range-tracking report-actions/confirm-start-vol-range-tracking
   :select-vol-range-report-window report-actions/select-vol-range-report-window
   :set-vol-range-report-covered-subset report-actions/set-vol-range-report-covered-subset
   :refresh-vol-range-report-marks report-actions/refresh-vol-range-report-marks
   :set-vol-range-report-ui-flag report-actions/set-vol-range-report-ui-flag
   :export-vol-range-report report-actions/export-vol-range-report})
