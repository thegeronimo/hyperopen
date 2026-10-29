(ns hyperopen.views.portfolio.vol-range.coverage
  "Coverage chip and detail for the Vol Range report (HO-SPEC-014 v2.1
  section 12.6). The state is text plus an icon, never color alone.")

(defn- tone [state]
  (case state
    :complete ["text-ho-text-secondary"]
    :partial ["text-ho-text-secondary"]
    ["text-ho-warn"]))

(defn chip
  "The state chip for a coverage model ({:state :label :icon})."
  [{:keys [state label icon]} data-role]
  (when state
    [:span {:class (into ["inline-flex" "items-center" "gap-1" "rounded-md" "border" "border-ho-border"
                          "px-2" "py-0.5" "text-xs" "font-medium"]
                         (tone state))
            :data-role data-role
            :data-state (name state)}
     [:span {:aria-hidden true} icon]
     [:span label]]))

(defn coverage-block
  "Chip, how far fills were checked, and each interval with its reason."
  [{:keys [detail intervals] :as coverage}]
  [:div {:class ["flex" "flex-col" "gap-1"]}
   [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
    (chip coverage "vol-range-report-coverage")
    [:span {:class ["text-xs" "text-ho-text-secondary"]} detail]]
   (when (seq intervals)
     [:ul {:class ["flex" "flex-col" "gap-0.5" "text-xs" "text-ho-text-muted"]
           :data-role "vol-range-report-coverage-detail"}
      (for [{:keys [from-ms state text]} intervals]
        ^{:key from-ms}
        [:li {:data-state (name state)} text])])])
