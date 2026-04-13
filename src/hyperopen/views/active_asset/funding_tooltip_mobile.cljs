(ns hyperopen.views.active-asset.funding-tooltip-mobile)

(defn- tooltip-dismiss-actions
  [pin-id]
  [[:actions/set-funding-tooltip-pinned pin-id false]
   [:actions/set-funding-tooltip-visible pin-id false]])

(defn- tooltip-trigger-click-actions
  [pin-id pinned?]
  (if pinned?
    (tooltip-dismiss-actions pin-id)
    [[:actions/set-funding-tooltip-pinned pin-id true]
     [:actions/set-funding-tooltip-visible pin-id true]]))

(defn funding-tooltip-mobile-sheet
  [{:keys [trigger body open? pin-id pinned?]}]
  (let [open?* (boolean open?)
        dismiss-actions (tooltip-dismiss-actions pin-id)
        trigger-click-actions (tooltip-trigger-click-actions pin-id pinned?)]
    [:div {:class ["inline-flex"]}
     [:button {:type "button"
               :class ["inline-flex"
                       "cursor-pointer"
                       "appearance-none"
                       "border-0"
                       "bg-transparent"
                       "p-0"
                       "text-inherit"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :data-role "active-asset-funding-trigger"
               :aria-expanded open?*
               :aria-haspopup "dialog"
               :aria-pressed (boolean pinned?)
               :on {:click trigger-click-actions
                    :focus [[:actions/set-funding-tooltip-visible pin-id true]]
                    :blur [[:actions/set-funding-tooltip-visible pin-id false]]}}
      trigger]
     (when open?*
       [:div {:class ["fixed" "inset-0" "z-[250]"]
              :data-role "active-asset-funding-mobile-sheet-layer"}
        [:button {:type "button"
                  :class ["absolute"
                          "inset-0"
                          "bg-black/55"
                          "backdrop-blur-[1px]"
                          "pointer-events-auto"]
                  :style {:transition "opacity 0.14s ease-out"
                          :opacity 1}
                  :replicant/mounting {:style {:opacity 0}}
                  :replicant/unmounting {:style {:opacity 0}}
                  :aria-label "Close funding sheet"
                  :data-role "active-asset-funding-mobile-sheet-backdrop"
                  :on {:click dismiss-actions}}]
        [:div {:class ["absolute" "inset-x-0" "bottom-0" "w-full"]
               :replicant/mounting {:style {:transform "translateY(18px)"
                                            :opacity 0}}
               :replicant/unmounting {:style {:transform "translateY(18px)"
                                              :opacity 0}}}
         body]])]))
