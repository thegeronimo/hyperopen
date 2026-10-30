(ns hyperopen.views.portfolio.vol-range.activity
  "Execution activity of the tracked Vol Range fills (HO-SPEC-014 v2.1
  section 12.6): always shown, also when coverage suppresses dollar
  figures. Execution detail, not P&L."
  (:require [hyperopen.vol-range.application.report-copy :as copy]))

(def ^:private cell ["px-2" "py-1.5" "text-left" "align-top" "whitespace-nowrap"])

(defn activity-table
  [campaigns]
  [:section {:class ["flex" "flex-col" "gap-2"] :data-role "vol-range-report-activity-section"}
   [:h3 {:class ["text-sm" "font-semibold" "text-ho-text"]} copy/activity-title]
   [:p {:class ["text-xs" "text-ho-text-muted"]} copy/activity-note]
   [:div {:class ["overflow-x-auto"]}
    [:table {:class ["min-w-full" "text-xs" "text-ho-text"] :data-role "vol-range-report-activity"}
     [:thead
      [:tr {:class ["border-b" "border-ho-border" "text-ho-text-secondary"]}
       (for [c copy/activity-columns] ^{:key c} [:th {:class (conj cell "font-medium") :scope "col"} c])]]
     [:tbody
      (for [{:keys [campaign-id activity]} campaigns
            :let [{:keys [market fills bought buy-vwap sold sell-vwap maker-taker fees]} activity]]
        ^{:key campaign-id}
        [:tr {:class ["border-b" "border-ho-border"] :data-role "vol-range-report-activity-row"
              :data-campaign-id campaign-id}
         [:th {:class (conj cell "font-medium") :scope "row"} market]
         [:td {:class (conj cell "num") :data-role "vol-range-report-activity-fills"} fills]
         [:td {:class (conj cell "num") :data-role "vol-range-report-activity-bought"} bought]
         [:td {:class (conj cell "num") :data-role "vol-range-report-activity-buy-vwap"} buy-vwap]
         [:td {:class (conj cell "num") :data-role "vol-range-report-activity-sold"} sold]
         [:td {:class (conj cell "num") :data-role "vol-range-report-activity-sell-vwap"} sell-vwap]
         [:td {:class (conj cell "num") :data-role "vol-range-report-activity-maker-taker"} maker-taker]
         [:td {:class (conj cell "num") :data-role "vol-range-report-activity-fees"} fees]])]]]])
