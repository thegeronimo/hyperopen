(ns hyperopen.views.vaults.list-view.saved-views
  "Saved views on the All vaults page: the Views row in the toolbar and the
  active view's header with its menu."
  (:require [hyperopen.views.vaults.list-view.format :as format]))

(defn- bookmark-icon
  []
  [:svg {:viewBox "0 0 12 12" :class ["h-3" "w-3"] :fill "none" :stroke "currentColor"
         :stroke-width "1.4" :stroke-linejoin "round" :aria-hidden true}
   [:path {:d "M3 1.5h6v9L6 8.3 3 10.5z"}]])

(defn- chip-button
  [{:keys [label count active? action role saved?]}]
  [:button {:type "button"
            :aria-pressed (boolean active?)
            :class (into ["inline-flex" "min-h-8" "items-center" "gap-1.5" "rounded-full" "border" "px-3" "text-xs"
                          "transition-colors"]
                         (concat (if active?
                                   ["border-ho-border-accent" "bg-ho-accent-soft" "text-ho-accent-bright"]
                                   ["border-base-300" "text-trading-text" "hover:bg-base-200"])
                                 format/focus-ring-classes))
            :data-role role
            :on {:click [action]}}
   (when saved? (bookmark-icon))
   label
   (when count
     [:span {:class ["num" (if active? "text-ho-accent-bright" "text-trading-text-secondary")]} (str count)])])

(defn views-row
  [{:keys [views active]}]
  (when (seq views)
    (into [:div {:class ["flex" "flex-wrap" "items-center" "gap-1.5" "border-t" "border-base-300/70" "pt-3"]
                 :data-role "vaults-saved-views-row"}
           [:span {:class ["mr-1" "text-xs" "text-trading-text-secondary"]} "Views"]
           (chip-button {:label "All vaults"
                         :active? (nil? active)
                         :action [:actions/select-vault-saved-view nil]
                         :role "vaults-saved-view-all"})]
          (concat
           (map (fn [{:keys [id name count active?]}]
                  (chip-button {:label name
                                :count count
                                :active? active?
                                :saved? true
                                :action [:actions/select-vault-saved-view id]
                                :role "vaults-saved-view-chip"}))
                views)
           [[:button {:type "button"
                      :class (into ["inline-flex" "min-h-8" "items-center" "gap-1.5" "px-2" "text-xs"
                                    "text-ho-accent-bright" "hover:underline"]
                                   format/focus-ring-classes)
                      :on {:click [[:actions/set-vaults-view :screener]]}}
             "+ New view from a screen"]]))))

(defn- header-button
  [label action role]
  [:button {:type "button"
            :class (into ["inline-flex" "min-h-10" "items-center" "gap-2" "rounded-lg" "border" "border-base-300"
                          "bg-base-100" "px-3.5" "text-sm" "text-trading-text" "hover:bg-base-200"
                          "disabled:cursor-not-allowed" "disabled:opacity-50"]
                         format/focus-ring-classes)
            :data-role role
            :on {:click [action]}}
   label])

(defn optimize-icon
  []
  [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "none" :stroke "currentColor"
         :stroke-width "1.6" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
   [:path {:d "M2 13l4-5 3 3 5-7"}]
   [:path {:d "M10 4h4v4"}]])

(defn- optimize-button
  [count]
  [:button {:type "button"
            :disabled (zero? count)
            :class (into ["inline-flex" "min-h-10" "items-center" "gap-2" "rounded-lg" "bg-ho-accent" "px-3.5" "text-sm"
                          "font-medium" "text-ho-bg" "hover:bg-ho-accent-hi" "disabled:cursor-not-allowed"
                          "disabled:opacity-50"]
                         format/focus-ring-classes)
            :data-role "vaults-saved-view-optimize"
            :on {:click [[:actions/open-vault-optimizer-handoff :saved-view]]}}
   (optimize-icon)
   (str "Optimize these " count (if (= 1 count) " vault" " vaults"))])

(defn- menu-item
  [label command danger?]
  [:button {:type "button"
            :role "menuitem"
            :class (into ["flex" "min-h-10" "w-full" "items-center" "rounded-md" "px-2.5" "text-left" "text-sm"
                          "hover:bg-base-200"
                          (if danger? "text-ho-sell-hi" "text-trading-text")]
                         format/focus-ring-classes)
            :data-role (str "vaults-saved-view-menu-" (name command))
            :on {:click [[:actions/vault-saved-view-command command]]}}
   label])

(def ^:private status-tone-classes
  {:progress ["text-ho-accent-bright"]
   :success ["text-ho-accent-bright"]
   :warn ["text-ho-warn"]
   :muted ["text-trading-text-secondary"]})

(defn- min-history-items
  [current]
  (into [[:div {:class ["mt-1" "border-t" "border-base-300" "px-2.5" "pb-1" "pt-2" "text-xs" "text-trading-text-secondary"]}
          "Minimum history in the window"]]
        (map (fn [[points label]]
               [:button {:type "button"
                         :role "menuitemradio"
                         :aria-checked (= points current)
                         :class (into ["flex" "min-h-10" "w-full" "items-center" "justify-between" "rounded-md" "px-2.5"
                                       "text-left" "text-sm" "text-trading-text" "hover:bg-base-200"]
                                      format/focus-ring-classes)
                         :data-role (str "vaults-saved-view-menu-min-history-" points)
                         :on {:click [[:actions/vault-saved-view-command (keyword (str "min-history-" points))]]}}
                label
                [:span {:class ["text-ho-accent-bright"]} (if (= points current) "✓" "")]]))
        [[0 "Any"] [10 "10 points"] [20 "20 points"]]))

(defn view-header
  [{:keys [name count chips status membership menu-open? confirm-delete? min-history-points]}]
  [:section {:aria-label (str "Saved view " name)
             :class ["relative" "flex" "flex-col" "gap-3" "rounded-2xl" "border" "border-ho-border-accent"
                     "bg-ho-accent-soft/40" "px-5" "py-4"]
             :data-role "vaults-saved-view-header"}
   [:div {:class ["flex" "flex-wrap" "items-start" "justify-between" "gap-3"]}
    [:div {:class ["flex" "min-w-0" "flex-col" "gap-2"]}
     [:h2 {:class ["text-lg" "font-medium" "text-trading-text"]}
      name [:span {:class ["num" "ml-2" "text-sm" "font-normal" "text-trading-text-secondary"]}
            (str count (if (= 1 count) " vault" " vaults"))]]
     (into [:div {:class ["flex" "flex-wrap" "gap-1.5"]}]
           (map (fn [{:keys [text accent?]}]
                  [:span {:class (into ["rounded-full" "border" "px-2.5" "py-1" "text-xs"]
                                       (if accent?
                                         ["border-ho-border-accent" "bg-ho-accent-soft" "text-ho-accent-bright"]
                                         ["border-base-300" "text-trading-text"]))}
                   text]))
           chips)]
    [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
     (optimize-button count)
     (cond
       (:paused? status) (header-button "Resume" [:actions/control-vault-screener-run :resume] "vaults-saved-view-resume")
       (:refreshing? status) (header-button "Pause" [:actions/control-vault-screener-run :pause] "vaults-saved-view-pause")
       :else (header-button "Refresh" [:actions/vault-saved-view-command :refresh] "vaults-saved-view-refresh"))
     (header-button "Open in Screener" [:actions/vault-saved-view-command :open-in-screener] "vaults-saved-view-open")
     [:button {:type "button"
               :aria-label "More view actions"
               :aria-expanded (boolean menu-open?)
               :class (into ["inline-flex" "h-10" "w-10" "items-center" "justify-center" "rounded-lg" "border"
                             "border-base-300" "text-trading-text" "hover:bg-base-200"]
                            format/focus-ring-classes)
               :data-role "vaults-saved-view-menu-trigger"
               :on {:click [[:actions/vault-saved-view-command :toggle-menu]]}}
      [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "currentColor" :aria-hidden true}
       [:circle {:cx "3.5" :cy "8" :r "1.3"}]
       [:circle {:cx "8" :cy "8" :r "1.3"}]
       [:circle {:cx "12.5" :cy "8" :r "1.3"}]]]]]
   [:div {:class (into ["text-sm"] (get status-tone-classes (:tone status) []))
          :data-role "vaults-saved-view-status"}
    (:text status)]
   (if confirm-delete?
     [:div {:class ["flex" "flex-wrap" "items-center" "gap-3" "rounded-xl" "border" "border-ho-sell/40" "bg-ho-sell/10" "px-4" "py-3" "text-sm"]
            :data-role "vaults-saved-view-confirm-delete"}
      [:span {:class ["text-trading-text"]} (str "Delete “" name "”? This removes the view from this device.")]
      [:button {:type "button"
                :class (into ["min-h-9" "rounded-lg" "bg-ho-sell" "px-3" "text-sm" "font-medium" "text-ho-bg"] format/focus-ring-classes)
                :data-role "vaults-saved-view-confirm-delete-button"
                :on {:click [[:actions/vault-saved-view-command :confirm-delete]]}}
       "Delete view"]
      [:button {:type "button"
                :class (into ["min-h-9" "rounded-lg" "border" "border-base-300" "px-3" "text-sm" "text-trading-text"] format/focus-ring-classes)
                :on {:click [[:actions/vault-saved-view-command :cancel-delete]]}}
       "Keep it"]]
     [:div {:class ["hidden"]}])
   (if menu-open?
     [:div {:role "menu"
            :class ["absolute" "right-5" "top-16" "z-30" "flex" "w-56" "flex-col" "rounded-xl" "border" "border-base-300"
                    "bg-base-100" "p-1.5" "shadow-2xl"]
            :data-role "vaults-saved-view-menu"}
      (menu-item "Rename" :rename false)
      (menu-item (if (= membership :fixed) "Update members on refresh" "Keep these vaults fixed") :toggle-fixed false)
      (menu-item "Delete view" :request-delete true)
      (into [:div {:class ["flex" "flex-col"]}] (min-history-items min-history-points))]
     [:div {:class ["hidden"]}])])
