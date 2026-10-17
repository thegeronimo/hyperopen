(ns hyperopen.views.vaults.screener-view.save-dialog
  "Modal that saves screener results as a view (or renames a saved view)."
  (:require [hyperopen.vaults.screener.saved-views :as saved-views]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.views.vaults.list-view.format :as format]))

(defn- option-card
  [{:keys [label sub active? action role radio?]}]
  [:button {:type "button"
            :role (when radio? "radio")
            :aria-checked (when radio? (boolean active?))
            :aria-pressed (when-not radio? (boolean active?))
            :class (into ["flex" "w-full" "items-start" "gap-3" "rounded-xl" "border" "px-4" "py-3" "text-left"]
                         (concat (if active?
                                   ["border-ho-border-accent" "bg-ho-accent-soft"]
                                   ["border-base-300" "hover:bg-base-200"])
                                 format/focus-ring-classes))
            :data-role role
            :on {:click [action]}}
   (when radio?
     [:span {:aria-hidden true
             :class (into ["mt-0.5" "h-4" "w-4" "shrink-0" "rounded-full" "box-border"]
                          (if active? ["border-[5px]" "border-ho-accent"] ["border" "border-trading-text-secondary"]))}])
   [:span {:class ["flex" "flex-col" "gap-1"]}
    [:span {:class ["text-sm" "text-trading-text"]} label]
    [:span {:class ["text-xs" "leading-[18px]" "text-trading-text-secondary"]} sub]]])

(defn- qualifying-count
  [state scope]
  (let [run (get-in state [:vaults :screener :run])
        config (saved-views/view-config (:config run) (get-in state [:vaults-ui :screener]))]
    (count (saved-views/qualifying-addresses (:addresses run)
                                             (get-in state [:vaults :screener :results])
                                             config
                                             scope))))

(defn- criteria-chips
  [state]
  (let [run (get-in state [:vaults :screener :run])
        config (saved-views/view-config (:config run) (get-in state [:vaults-ui :screener]))]
    (->> (:criteria config)
         (keep (fn [{:keys [metric value]}]
                 (when (model/parse-number value)
                   (let [{:keys [label direction unit]} (model/metric-def-by-key metric)]
                     (str label (if (= direction :min) " ≥ " " ≤ ") value
                          (case unit :percent "%" :days " days" ""))))))
         vec)))

(defn save-dialog
  [state]
  (let [{:keys [open? mode name scope membership]} (get-in state [:vaults-ui :save-view-dialog])
        rename? (= mode :rename)]
    (when open?
      (let [pass-count (when-not rename? (qualifying-count state :pass))
            near-count (when-not rename? (qualifying-count state :pass-and-near))
            chosen-count (if (= scope :pass-and-near) near-count pass-count)]
        [:div {:class ["fixed" "inset-0" "z-50" "flex" "items-start" "justify-center" "overflow-y-auto" "bg-ho-bg-deep/80"
                       "px-4" "py-10" "md:py-20"]
               :data-role "vault-save-view-dialog"}
         [:div {:role "dialog"
                :aria-modal true
                :aria-labelledby "vault-save-view-title"
                :class ["flex" "w-full" "max-w-[560px]" "flex-col" "gap-5" "rounded-2xl" "border" "border-base-300"
                        "bg-base-100" "p-6" "shadow-2xl"]}
          [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
           [:div
            [:h2 {:id "vault-save-view-title" :class ["text-[20px]" "font-medium" "text-trading-text"]}
             (if rename? "Rename view" "Save these results")]
            (when-not rename?
              [:p {:class ["mt-1.5" "text-sm" "leading-5" "text-trading-text-secondary"]}
               "Turn this screen into a view you can pick on the All vaults page."])]
           [:button {:type "button"
                     :aria-label "Close"
                     :class (into ["-mr-2" "-mt-2" "inline-flex" "h-11" "w-11" "items-center" "justify-center" "rounded-lg"
                                   "text-trading-text-secondary" "hover:bg-base-200"]
                                  format/focus-ring-classes)
                     :on {:click [[:actions/close-vault-save-view-dialog]]}}
            [:svg {:viewBox "0 0 16 16" :class ["h-4" "w-4"] :fill "none" :stroke "currentColor"
                   :stroke-width "1.5" :stroke-linecap "round" :aria-hidden true}
             [:path {:d "M4 4l8 8M12 4l-8 8"}]]]]
          [:label {:class ["flex" "flex-col" "gap-2"]}
           [:span {:class ["text-xs" "text-trading-text-secondary"]} "Name"]
           [:input {:type "text"
                    :value (or name "")
                    :maxlength 60
                    :autocomplete "off"
                    :class ["h-11" "rounded-lg" "border" "border-ho-border-accent" "bg-ho-bg" "px-3.5" "text-base"
                            "text-trading-text" "focus:border-ho-accent" "focus:ring-0"]
                    :data-role "vault-save-view-name"
                    :on {:input [[:actions/set-vault-save-view-field :name [:event.target/value]]]}}]
           (when-not rename?
             [:span {:class ["text-xs" "text-trading-text-secondary"]} "Suggested from your criteria. You can rename it later."])]
          (when-not rename?
            [:div {:class ["flex" "flex-col" "gap-2"]}
             [:span {:class ["text-xs" "text-trading-text-secondary"]} "Vaults to include"]
             [:div {:role "group" :aria-label "Vaults to include" :class ["grid" "grid-cols-2" "gap-2"]}
              (option-card {:label "Passing vaults"
                            :sub (str pass-count (if (= 1 pass-count) " vault" " vaults"))
                            :active? (not= scope :pass-and-near)
                            :action [:actions/set-vault-save-view-field :scope :pass]
                            :role "vault-save-view-scope-pass"})
              (option-card {:label "Passing + near misses"
                            :sub (str near-count (if (= 1 near-count) " vault" " vaults"))
                            :active? (= scope :pass-and-near)
                            :action [:actions/set-vault-save-view-field :scope :pass-and-near]
                            :role "vault-save-view-scope-near"})]])
          (when-not rename?
            [:div {:class ["flex" "flex-col" "gap-2"]}
             [:span {:class ["text-xs" "text-trading-text-secondary"]} "When you refresh the view"]
             [:div {:role "radiogroup" :aria-label "When you refresh the view" :class ["flex" "flex-col" "gap-2"]}
              (option-card {:label "Update the members"
                            :sub "Re-run the screen and keep whichever vaults pass now. You see who joined and who left."
                            :active? (not= membership :fixed)
                            :radio? true
                            :action [:actions/set-vault-save-view-field :membership :update]
                            :role "vault-save-view-membership-update"})
              (option-card {:label "Keep these vaults fixed"
                            :sub "Refresh only updates their numbers. Good for a watchlist you picked by hand."
                            :active? (= membership :fixed)
                            :radio? true
                            :action [:actions/set-vault-save-view-field :membership :fixed]
                            :role "vault-save-view-membership-fixed"})]])
          (when-not rename?
            [:div {:class ["flex" "flex-col" "gap-2.5" "rounded-xl" "border" "border-base-300" "bg-ho-bg" "px-4" "py-3.5"]}
             [:div {:class ["text-xs" "text-trading-text-secondary"]} "Saved with the view"]
             (into [:div {:class ["flex" "flex-wrap" "gap-1.5"]}]
                   (map (fn [text]
                          [:span {:class ["rounded-full" "border" "border-ho-border-accent" "bg-ho-accent-soft" "px-2.5"
                                          "py-1" "text-xs" "text-ho-accent-bright"]}
                           text]))
                   (criteria-chips state))
             [:div {:class ["text-xs" "leading-[18px]" "text-trading-text-secondary"]}
              "The screen settings are kept too, so you can open this view in the Screener, tweak it and save it again."]])
          [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
           [:span {:class ["text-xs" "text-trading-text-secondary"]} "Saved on this device"]
           [:div {:class ["flex" "gap-2"]}
            [:button {:type "button"
                      :class (into ["min-h-11" "rounded-lg" "border" "border-base-300" "px-4" "text-sm" "text-trading-text"
                                    "hover:bg-base-200"]
                                   format/focus-ring-classes)
                      :on {:click [[:actions/close-vault-save-view-dialog]]}}
             "Cancel"]
            [:button {:type "button"
                      :disabled (and (not rename?) (zero? (or chosen-count 0)))
                      :class (into ["min-h-11" "rounded-lg" "bg-ho-accent" "px-4" "text-sm" "font-medium" "text-ho-bg"
                                    "hover:bg-ho-accent-hi" "disabled:cursor-not-allowed" "disabled:opacity-50"]
                                   format/focus-ring-classes)
                      :data-role "vault-save-view-submit"
                      :on {:click [[:actions/save-vault-screener-view]]}}
             (if rename?
               "Save name"
               (str "Save view · " chosen-count (if (= 1 chosen-count) " vault" " vaults")))]]]]]))))
