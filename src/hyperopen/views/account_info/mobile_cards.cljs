(ns hyperopen.views.account-info.mobile-cards)

(defn- chevron-icon [open?]
  [:svg {:class (into ["h-4"
                       "w-4"
                       "shrink-0"
                       "transition-transform"
                       "duration-150"]
                      (if open?
                        ["rotate-180"]
                        ["rotate-0"]))
         :viewBox "0 0 12 12"
         :fill "none"
         :stroke "currentColor"
         :aria-hidden true}
   [:path {:d "M3 4.5L6 7.5L9 4.5"
           :stroke-linecap "round"
           :stroke-linejoin "round"
           :stroke-width "1.5"}]])

(defn summary-item
  ([label content]
   (summary-item label content {}))
  ([label content {:keys [value-classes]
                   :or {value-classes []}}]
   [:div {:class ["min-w-0" "space-y-1"]}
    [:div {:class ["text-xs"
                   "font-medium"
                   "leading-4"
                   "text-trading-text-secondary"]}
     label]
    [:div {:class (into ["min-w-0"
                         "text-sm"
                         "font-semibold"
                         "leading-5"
                         "text-trading-text"]
                        value-classes)}
     content]]))

(defn detail-item
  ([label content]
   (detail-item label content {}))
  ([label content {:keys [full-width? value-classes]
                   :or {full-width? false
                        value-classes []}}]
   [:div {:class (into ["min-w-0" "space-y-1"]
                       (when full-width?
                         ["col-span-full"]))}
    [:div {:class ["text-xs"
                   "font-medium"
                   "leading-4"
                   "text-trading-text-secondary"]}
     label]
    [:div {:class (into ["min-w-0"
                         "text-sm"
                         "font-semibold"
                         "leading-5"
                         "text-trading-text"]
                        value-classes)}
     content]]))

(defn detail-grid [column-classes children]
  (into [:div {:class ["grid"
                       column-classes
                       "gap-x-3"
                       "gap-y-3"]}]
        (keep identity children)))

(defn expandable-card
  [{:keys [data-role expanded? toggle-actions summary-items detail-content]}]
  (let [chevron-node
        [:div {:class ["flex"
                       "h-full"
                       "items-start"
                       "justify-end"
                       "pt-1"
                       "text-trading-text-secondary"]}
         (chevron-icon expanded?)]]
    [:div {:class ["overflow-hidden"
                   "rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-base-200/70"]
           :data-role data-role}
     [:button {:type "button"
               :class ["w-full"
                       "px-3"
                       "py-3"
                       "text-left"
                       "transition-colors"
                       "hover:bg-base-200"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :aria-expanded (boolean expanded?)
               :on {:click toggle-actions}}
      (into [:div {:class ["grid"
                           "grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)_minmax(0,1fr)_auto]"
                   "items-start"
                   "gap-3"]}]
            (concat summary-items [chevron-node]))]
     (when expanded?
       [:div {:class ["border-t" "border-base-300" "px-3" "py-3"]}
        detail-content])]))
