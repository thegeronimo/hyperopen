(ns hyperopen.views.active-asset.statistics)

(defn desktop-stat-cell
  [content]
  [:div {:class ["asset-stat-cell" "flex" "min-w-max" "justify-center"]
         :data-role "active-asset-stat-cell"}
   content])

(defn desktop-statistics-scroll-region
  ([content]
   (desktop-statistics-scroll-region content nil))
  ([content tooltip-id]
   [:div (cond-> {:class ["min-w-0"
                           "overflow-x-auto"
                           "focus:outline-none"
                           "focus:ring-1"
                           "focus:ring-inset"
                           "focus:ring-primary"]
                  :tabindex 0
                  :aria-label "Market statistics"
                  :data-role "active-asset-statistics-scroll"}
           (seq tooltip-id)
           (assoc :on {:scroll [[:actions/set-funding-tooltip-pinned tooltip-id false]
                                [:actions/set-funding-tooltip-visible tooltip-id false]]}))
    content]))
