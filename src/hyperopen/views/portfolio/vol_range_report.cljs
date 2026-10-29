(ns hyperopen.views.portfolio.vol-range-report
  "The Portfolio Vol Range tab (HO-SPEC-014 v2.1 sections 11.3, 12): the
  market control and Refresh, the headline card (no dollar figures yet: it
  says when figures can first appear), execution activity, and each
  tracking campaign with its baseline, coverage and Start new tracking.
  Renders `report-model/report-view-model` only; dispatches only registered
  report actions, so rendering never loads anything."
  (:require [hyperopen.vol-range.application.report-copy :as copy]
            [hyperopen.views.portfolio.vol-range.activity :as activity]
            [hyperopen.views.portfolio.vol-range.coverage :as coverage]
            [hyperopen.views.portfolio.vol-range.tracking :as tracking]))

(defn- market-select [{:keys [options]}]
  [:label {:class ["flex" "items-center" "gap-2" "text-xs" "text-ho-text-secondary"]}
   [:span copy/market-label]
   [:select {:class ["h-8" "px-2" "bg-base-200" "border" "border-base-300" "rounded-lg" "text-sm" "text-ho-text"]
             :data-role "vol-range-report-market"
             :on {:change [[:actions/select-vol-range-report-campaign [:event.target/value]]]}}
    (for [{:keys [id label selected?]} options]
      ^{:key id}
      [:option {:value id :selected (boolean selected?)} label])]])

(defn- controls [{:keys [markets loading? read-only?] :as vm}]
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-3"] :data-role "vol-range-report-controls"}
   (market-select markets)
   (when-not read-only?
     [:button {:type "button" :class tracking/button-classes
               :disabled (boolean loading?)
               :aria-busy (boolean loading?)
               :data-role "vol-range-report-refresh"
               :on {:click [[:actions/refresh-vol-range-report]]}}
      copy/refresh])
   (coverage/chip (:coverage vm) "vol-range-report-coverage-summary")])

(defn- headline [{:keys [message]}]
  (when message
    [:div {:class ["rounded-md" "border" "border-ho-border" "p-3"] :data-role "vol-range-report-headline"}
     [:p {:class ["text-sm" "text-ho-text-secondary"] :role "status" :data-role "vol-range-report-suppressed"}
      message]]))

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
        (controls vm)
        (headline (:headline vm))
        (activity/activity-table campaigns)
        (tracking/tracking-section campaigns read-only?)])]))
