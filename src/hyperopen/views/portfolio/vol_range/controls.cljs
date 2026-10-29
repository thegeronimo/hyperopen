(ns hyperopen.views.portfolio.vol-range.controls
  "Controls row of the Vol Range report (HO-SPEC-014 v2.1 sections 11.3,
  11.4, 12.4): the market select (All tracked markets or one market), the
  window buttons (Since tracking began, 7D, 30D; `aria-pressed`), Refresh
  and the coverage summary chip. Wraps at phone width. Dispatches only
  registered report actions."
  (:require [hyperopen.vol-range.application.report-copy :as copy]
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

(def ^:private segment-classes
  ["min-h-[28px]" "px-2.5" "py-1" "text-xs" "border-r" "border-ho-border" "last:border-r-0"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"])

(defn- window-buttons [{:keys [options]}]
  [:div {:class ["inline-flex" "overflow-hidden" "rounded-md" "border" "border-ho-border"]
         :role "group"
         :aria-label copy/window-group-label
         :data-role "vol-range-report-window"}
   (for [{:keys [id label selected?]} options]
     ^{:key (name id)}
     [:button {:type "button"
               :class (into segment-classes (if selected?
                                              ["bg-base-300" "text-ho-text" "font-semibold"]
                                              ["text-ho-text-secondary"]))
               :aria-pressed (if selected? "true" "false")
               :data-role (str "vol-range-report-window-" (name id))
               :on {:click [[:actions/select-vol-range-report-window id]]}}
      label])])

(defn controls-row
  [{:keys [markets window loading? read-only?] :as vm}]
  [:div {:class ["flex" "flex-wrap" "items-center" "gap-3"] :data-role "vol-range-report-controls"}
   (market-select markets)
   (window-buttons window)
   (when-not read-only?
     [:button {:type "button" :class tracking/button-classes
               :disabled (boolean loading?)
               :aria-busy (boolean loading?)
               :data-role "vol-range-report-refresh"
               :on {:click [[:actions/refresh-vol-range-report]]}}
      copy/refresh])
   (coverage/chip (:coverage vm) "vol-range-report-coverage-summary")])
