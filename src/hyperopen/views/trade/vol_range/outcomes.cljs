(ns hyperopen.views.trade.vol-range.outcomes
  "If orders fill section, error/warning cards and the sticky footer."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.primitives :as p]))

(defn outcomes-section
  [{:keys [both-sides? base-symbol all-buys-first all-sells-first everything-fills vwap-gap]}]
  (p/section {:title "If orders fill" :data-role "vol-range-outcomes"}
             [:div {:class ["flex" "flex-col" "gap-0.5"]}
              (p/stat-row "All buys first" all-buys-first :data-role "vol-range-outcome-buys-first")
              (p/stat-row "All sells first" all-sells-first :data-role "vol-range-outcome-sells-first")
              (p/stat-row "Everything fills" everything-fills :data-role "vol-range-outcome-everything")]
             (when (and both-sides? vwap-gap)
               [:details {:class ["text-xs" "text-ho-text-secondary"] :data-role "vol-range-vwap-gap"
                          :open (not (:collapsed? vwap-gap))}
                [:summary {:class ["cursor-pointer"]} "Quoted VWAP gap"]
                [:div {:class ["mt-1" "flex" "flex-col" "gap-0.5"]}
                 (p/stat-row (str "Per " (or base-symbol "unit")) (:per-unit vwap-gap))
                 (p/stat-row "Percent" (:percent vwap-gap))
                 [:p {:class ["text-ho-text-muted"]} (:explanation vwap-gap)]]])))

(defn- error-card
  [{:keys [code message remediations]}]
  [:div {:class ["flex" "flex-col" "gap-1.5" "rounded-md" "border" "border-ho-warn" "p-2"]
         :role "alert"
         :data-role "vol-range-error"
         :data-code (when code (name code))}
   [:p {:class ["text-sm" "text-ho-warn"]} message]
   (when (seq remediations)
     [:div {:class ["flex" "flex-wrap" "gap-1.5"]}
      (for [{:keys [id label]} remediations]
        ^{:key id}
        (p/action-button {:label label
                          :command (commands/apply-remediation id)
                          :data-role (str "vol-range-remediation-" (name id))
                          :tone :warn}))])])

(defn messages
  "Error cards (with remediation buttons) followed by plain warnings."
  [errors warnings]
  (when (or (seq errors) (seq warnings))
    [:div {:class ["flex" "flex-col" "gap-1.5"] :data-role "vol-range-messages"}
     (for [[idx e] (map-indexed vector errors)]
       ^{:key (str "e" idx)} (error-card e))
     (for [[idx {:keys [message]}] (map-indexed vector warnings)]
       ^{:key (str "w" idx)}
       [:p {:class ["text-xs" "text-ho-warn"] :data-role "vol-range-warning"} message])]))

(defn footer
  [{:keys [note review-label review-enabled?]}]
  [:div {:class ["sticky" "bottom-0" "flex" "flex-col" "gap-1.5" "border-t" "border-ho-border"
                 "bg-base-100" "pt-2" "pb-1"]
         :data-role "vol-range-footer"}
   [:p {:class ["text-xs" "text-ho-text-muted"]} note]
   [:button {:type "button"
             :class ["h-10" "w-full" "rounded-lg" "bg-ho-accent" "text-sm" "font-semibold" "text-ho-bg"
                     "disabled:opacity-50" "disabled:cursor-not-allowed"]
             :disabled (not review-enabled?)
             :data-role "vol-range-review-open"
             :on {:click (p/actions (commands/open-review))}}
    review-label]])
