(ns hyperopen.views.account-info.table)

(def header-base-text-classes
  ["text-m" "font-medium" "text-trading-text-secondary" "min-h-6" "py-0.5"])

(def sortable-header-interaction-classes
  ["hover:text-trading-text" "transition-colors"])

(def sortable-header-layout-classes
  ["flex" "items-center" "space-x-1" "group"])

(defn header-alignment-classes [align]
  (case align
    :right ["justify-end" "text-right"]
    :center ["justify-center" "text-center"]
    ["justify-start" "text-left"]))

(defn- header-label-node [column-name explanation]
  (if explanation
    [:span {:class ["relative" "inline-flex" "items-center" "underline" "decoration-dashed" "underline-offset-2"]}
     column-name
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "left-1/2"
                     "-translate-x-1/2"
                     "top-full"
                     "z-[120]"
                     "mt-2"
                     "w-56"
                     "rounded-md"
                     "bg-gray-800"
                     "px-2.5"
                     "py-1.5"
                     "text-left"
                     "text-xs"
                     "leading-tight"
                     "text-gray-100"
                     "whitespace-normal"
                     "shadow-lg"
                     "opacity-0"
                     "transition-opacity"
                     "duration-200"
                     "group-hover:opacity-100"
                     "group-focus-visible:opacity-100"]}
      explanation]]
    column-name))

(defn sortable-header-button
  ([column-name sort-state action-key]
   (sortable-header-button column-name sort-state action-key {}))
  ([column-name sort-state action-key {:keys [full-width? extra-classes explanation]
                                       :or {full-width? false
                                            extra-classes []}}]
   (let [current-column (:column sort-state)
         current-direction (:direction sort-state)
         is-active (= current-column column-name)
         sort-icon (when is-active
                     (if (= current-direction :asc) "↑" "↓"))]
      [:button {:class (into (if full-width? ["w-full"] [])
                            (concat header-base-text-classes
                                    sortable-header-interaction-classes
                                    sortable-header-layout-classes
                                    extra-classes))
               :on {:click [[action-key column-name]]}}
      (header-label-node column-name explanation)
      (when sort-icon
        [:span.text-xs.opacity-70 sort-icon])])))

(defn non-sortable-header
  ([column-name]
   (non-sortable-header column-name :left {}))
  ([column-name align]
   (non-sortable-header column-name align {}))
  ([column-name align {:keys [explanation]}]
   [:div {:class (into ["w-full"]
                       (concat ["group"]
                               header-base-text-classes
                               (header-alignment-classes align)))}
    (header-label-node column-name explanation)]))

(defn tab-table-content
  ([header rows]
   (tab-table-content header rows nil))
  ([header rows footer]
   [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
    header
    (into [:div {:class ["flex-1" "min-h-0" "overflow-y-auto" "scrollbar-hide"]}]
          rows)
    (when footer
      footer)]))
