(ns hyperopen.views.portfolio.vol-range.tracking
  "One tracking campaign of the Vol Range report (HO-SPEC-014 v2.1 sections
  12.1, 12.6): its baseline, Cost basis unavailable, coverage, and Start new
  tracking from now behind an inline confirmation. Starting new tracking
  never changes an order."
  (:require [hyperopen.vol-range.application.report-copy :as copy]
            [hyperopen.views.portfolio.vol-range.coverage :as coverage]))

(def button-classes
  ["min-h-[24px]" "rounded-md" "border" "border-ho-border" "px-2.5" "py-1" "text-xs" "text-ho-text"
   "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-ho-text"
   "disabled:cursor-not-allowed" "disabled:opacity-50"])

(defn- restart-control [{:keys [campaign-key market]} {:keys [visible? confirming? busy?]}]
  (when visible?
    (if confirming?
      [:div {:class ["flex" "flex-col" "gap-2" "rounded-md" "border" "border-ho-border" "p-2"]
             :role "group"
             :aria-label (str "Start new tracking for " market)
             :data-role "vol-range-report-start-tracking-confirm"}
       [:p {:class ["text-xs" "text-ho-text"]} copy/confirm-question]
       [:div {:class ["flex" "flex-wrap" "gap-2"]}
        [:button {:type "button" :class button-classes
                  :data-role "vol-range-report-start-tracking-yes"
                  :on {:click [[:actions/confirm-start-vol-range-tracking]]}}
         copy/confirm-yes]
        [:button {:type "button" :class button-classes
                  :data-role "vol-range-report-start-tracking-no"
                  :on {:click [[:actions/cancel-start-vol-range-tracking]]}}
         copy/confirm-no]]]
      [:div
       [:button {:type "button" :class button-classes
                 :disabled (boolean busy?)
                 :aria-busy (boolean busy?)
                 :aria-label (str copy/start-tracking " for " market)
                 :data-role "vol-range-report-start-tracking"
                 :on {:click [[:actions/request-start-vol-range-tracking campaign-key]]}}
        copy/start-tracking]])))

(defn campaign-card
  [{:keys [campaign-id market baseline-text cost-basis-text ended-text restart] :as c}]
  [:article {:class ["flex" "flex-col" "gap-2" "border-b" "border-ho-border" "py-3"]
             :data-role "vol-range-report-campaign"
             :data-campaign-id campaign-id
             :data-open (str (:open? c))}
   [:h4 {:class ["text-sm" "font-semibold" "text-ho-text"]} market]
   [:p {:class ["text-xs" "text-ho-text"] :data-role "vol-range-report-baseline"} baseline-text]
   (when ended-text
     [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-range-report-ended"} ended-text])
   [:p {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-range-report-cost-basis"} cost-basis-text]
   (coverage/coverage-block (:coverage c))
   (restart-control c restart)])

(defn tracking-section
  [campaigns read-only?]
  [:section {:class ["flex" "flex-col"] :data-role "vol-range-report-tracking"}
   [:h3 {:class ["text-sm" "font-semibold" "text-ho-text"]} copy/tracking-title]
   (when read-only?
     [:p {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-report-read-only"} copy/read-only-note])
   (for [c campaigns] ^{:key (:campaign-id c)} (campaign-card c))])
