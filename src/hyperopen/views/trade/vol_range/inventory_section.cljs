(ns hyperopen.views.trade.vol-range.inventory-section
  "Inventory section of the Vol Range ticket: tiles, protected core / target /
  maximum inputs, the inventory track and the Set maximum offer."
  (:require [hyperopen.views.trade.vol-range.commands :as commands]
            [hyperopen.views.trade.vol-range.primitives :as p]))

(def protected-core-input-id "vol-range-protected-core")

(defn- track-bar
  "Marker positions are percentages (0-100) from the projection. Every marker
  carries a text label so the track never relies on color alone. The drawn bar
  is decorative; the labelled list is the accessible content, so the group is
  not role=img (which would hide its children from screen readers)."
  [{:keys [domain-low domain-high markers]}]
  [:div {:class ["flex" "flex-col" "gap-1"] :data-role "vol-range-inventory-track"
         :role "group" :aria-label "Inventory range if one side fills first"}
   [:div {:class ["relative" "h-1.5" "rounded-full" "bg-base-300"] :aria-hidden "true"}
    (for [{:keys [id position]} markers]
      ^{:key id}
      [:span {:class ["absolute" "top-1/2" "h-3" "w-0.5" "-translate-y-1/2" "bg-ho-text-secondary"]
              :data-marker (name id)
              :style {:left (str position "%")}}])]
   [:ul {:class ["flex" "flex-wrap" "gap-x-2" "gap-y-0.5" "text-xs" "text-ho-text-muted" "num"]}
    (for [{:keys [id label value]} markers]
      ^{:key id}
      [:li {:data-marker-label (name id)} (str label (when value (str " " value)))])]
   [:div {:class ["flex" "justify-between" "text-xs" "text-ho-text-muted" "num"] :aria-hidden "true"}
    [:span domain-low] [:span domain-high]]])

(defn inventory-section
  ([inventory] (inventory-section inventory {}))
  ([{:keys [base-symbol current available-to-sell core-input target-input maximum-input
            core-placeholder target-placeholder maximum-placeholder track set-maximum-offer
            starting-breach]}
    fe]
  (p/section {:title "Inventory" :data-role "vol-range-inventory"}
             [:div {:class ["grid" "grid-cols-2" "gap-2"]}
              (p/tile {:label "Current spot inventory" :value (str current " " base-symbol)})
              (p/tile {:label "Available to sell" :value (str available-to-sell " " base-symbol)})]
             (when starting-breach
               [:p {:class ["rounded-md" "border" "border-ho-warn" "p-2" "text-xs" "text-ho-warn"]
                    :data-role "vol-range-starting-breach"}
                starting-breach])
             [:div {:class ["grid" "grid-cols-3" "gap-2"]}
              (p/text-field {:id protected-core-input-id :label "Protected core" :value core-input
                             :placeholder core-placeholder :path [:inventory :protected-core]
                             :field-errors fe})
              (p/text-field {:id "vol-range-target" :label "Target" :value target-input
                             :placeholder target-placeholder :path [:inventory :target]
                             :field-errors fe})
              (p/text-field {:id "vol-range-maximum" :label "Maximum" :value maximum-input
                             :placeholder maximum-placeholder :path [:inventory :maximum]
                             :field-errors fe})]
             (when track (track-bar track))
             (when set-maximum-offer
               (p/action-button {:label (:label set-maximum-offer)
                                 :command (commands/apply-remediation (:id set-maximum-offer))
                                 :data-role "vol-range-set-maximum"
                                 :tone :warn})))))
