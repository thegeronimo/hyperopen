(ns hyperopen.views.portfolio.vol-range-report
  "The Portfolio Vol Range tab (HO-SPEC-014 v2.1 sections 11.3, 12): the
  controls row (market, window, Refresh), the headline card (the selected
  window's dollar figures, or why they are hidden), execution activity, and
  each tracking campaign with its baseline, coverage and Start new
  tracking. Renders `report-model/report-view-model` only; dispatches only
  registered report actions, so rendering never loads anything."
  (:require [hyperopen.views.portfolio.vol-range.activity :as activity]
            [hyperopen.views.portfolio.vol-range.controls :as controls]
            [hyperopen.views.portfolio.vol-range.headline :as headline]
            [hyperopen.views.portfolio.vol-range.tracking :as tracking]))

(defn vol-range-report
  [{:keys [enabled? campaigns empty-message status-message read-only?] :as vm}]
  (when enabled?
    [:div {:class ["flex" "h-full" "min-h-0" "flex-col" "gap-4" "overflow-y-auto" "p-3" "md:p-4"]
           :data-role "vol-range-report"}
     [:div {:class ["text-xs" "text-ho-text-secondary"] :role "status" :aria-live "polite"
            :data-role "vol-range-report-status"}
      status-message]
     (if empty-message
       [:p {:class ["text-sm" "text-ho-text-secondary"] :data-role "vol-range-report-empty"} empty-message]
       [:div {:class ["flex" "flex-col" "gap-4"]}
        (controls/controls-row vm)
        (headline/headline-card (:headline vm))
        (activity/activity-table campaigns)
        (tracking/tracking-section campaigns read-only?)])]))
