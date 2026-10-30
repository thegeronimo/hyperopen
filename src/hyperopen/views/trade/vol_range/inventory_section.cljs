(ns hyperopen.views.trade.vol-range.inventory-section
  "Inventory section of the Vol Range ticket: tiles, a one-line limits
  summary, and (when opened, or when a limit needs attention) the keep at
  least / lean toward / hold at most inputs, the inventory track and the Set
  maximum offer."
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

(def limits-region-id "vol-range-limits")

(defn- limit-fields [{:keys [base-symbol limit-unit limit-note tilt? core-input target-input maximum-input
                             core-placeholder target-placeholder maximum-placeholder]}
                     fe]
  (let [quote? (= :quote limit-unit)
        ;; The fields are narrow; the switch above names the unit.
        field (fn [k] [:inventory (if quote? (keyword (str (name k) "-quote")) k)])]
    [:div {:class ["flex" "flex-col" "gap-1"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
      [:span {:class ["text-xs" "text-ho-text-secondary"]} "Limits in"]
      (p/unit-toggle {:path [:inventory :limit-unit] :unit limit-unit :base-symbol base-symbol
                      :data-role "vol-range-limit-unit" :aria-label "Inventory limit unit"})]
     [:div {:class ["grid" (if tilt? "grid-cols-3" "grid-cols-2") "gap-2"]}
      (p/text-field {:id protected-core-input-id :label "Keep at least" :value core-input
                     :placeholder core-placeholder :path (field :protected-core)
                     :field-errors fe})
      (when tilt?
        (p/text-field {:id "vol-range-target" :label "Lean toward" :value target-input
                       :placeholder target-placeholder :path (field :target)
                       :field-errors fe}))
      (p/text-field {:id "vol-range-maximum" :label "Hold at most" :value maximum-input
                     :placeholder maximum-placeholder :path (field :maximum)
                     :field-errors fe})]
     [:p {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-limit-hint"}
      (str "A plan is refused if all its sells filling could leave less than Keep at least, or all its buys"
           " filling (with your open orders) could hold more than Hold at most."
           (when tilt? " Tilt shifts prices only when holdings differ from Lean toward; blank means current holdings, so no shift.")
           " A blank field uses the value shown.")]
     (when limit-note
       [:div {:class ["text-xs" "text-ho-text-muted"] :data-role "vol-range-limit-note"}
        limit-note])]))

(defn inventory-section
  ([inventory] (inventory-section inventory {}))
  ([{:keys [base-symbol current available-to-sell summary open? collapsible? track set-maximum-offer
            starting-breach] :as inventory}
    fe]
   (p/section {:title "Inventory" :data-role "vol-range-inventory"}
              [:div {:class ["grid" "grid-cols-2" "gap-2"]}
               (p/tile {:label "Current spot inventory" :value (str current " " base-symbol)})
               (p/tile {:label "Available to sell" :value (str available-to-sell " " base-symbol)})]
              (when starting-breach
                [:p {:class ["rounded-md" "border" "border-ho-warn" "p-2" "text-xs" "text-ho-warn"]
                     :data-role "vol-range-starting-breach"}
                 starting-breach])
              [:div {:class ["flex" "items-start" "justify-between" "gap-2"]}
               [:p {:class ["text-xs" "text-ho-text-secondary" "num"] :data-role "vol-range-limits-summary"}
                summary]
               (when collapsible?
                 [:button {:type "button"
                           :class ["shrink-0" "text-xs" "text-ho-text-secondary" "underline"]
                           :aria-expanded (boolean open?)
                           :data-role "vol-range-limits-toggle"
                           :on {:click (p/actions (commands/set-ui-flag :limits-open? (not (boolean open?))))}}
                  (if open? "Hide limits" "Edit limits")])]
              (when open?
                [:div {:id limits-region-id :class ["flex" "flex-col" "gap-2"]}
                 (limit-fields inventory fe)
                 (when track (track-bar track))
                 (when set-maximum-offer
                   (p/action-button {:label (:label set-maximum-offer)
                                     :command (commands/apply-remediation (:id set-maximum-offer))
                                     :data-role "vol-range-set-maximum"
                                     :tone :warn}))]))))
