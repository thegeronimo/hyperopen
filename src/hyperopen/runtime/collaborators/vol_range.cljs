(ns hyperopen.runtime.collaborators.vol-range
  (:require [hyperopen.vol-range.actions :as vol-range-actions]))

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
