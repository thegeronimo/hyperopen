(ns hyperopen.views.earn.modal
  "The Earn modal: one dialog for supply, withdraw, borrow and repay, with a
  preview of what the change does before it is signed.")

(def ^:private tone-classes
  {:buy ["text-ho-buy"]
   :sell ["text-ho-sell"]
   :safe ["text-ho-buy"]
   :caution ["text-ho-warn"]
   :risk ["text-ho-sell"]
   :none ["text-ho-text"]
   :neutral ["text-ho-text"]})

(defn- tab-button
  [{:keys [kind label active? disabled?]}]
  [:button {:type "button"
            :role "tab"
            :aria-selected (str active?)
            :disabled disabled?
            :class (into ["flex-1" "h-9" "rounded-md" "text-sm" "font-medium" "transition-colors"
                          "disabled:opacity-40" "disabled:cursor-not-allowed"]
                         (if active?
                           ["bg-ho-surface-raised" "text-ho-text-hi"]
                           ["text-ho-text-secondary" "hover:text-ho-text"]))
            :data-role (str "earn-modal-tab-" (name kind))
            :on {:click [[:actions/set-borrow-lend-modal-kind kind]]}}
   label])

(defn- preset-button
  [label fraction]
  [:button {:type "button"
            :class ["flex-1" "h-8" "rounded-md" "border" "border-ho-border" "bg-ho-surface-raised"
                    "text-xs" "text-ho-text" "hover:border-ho-text-dim"]
            :data-role (str "earn-modal-preset-" label)
            :on {:click [[:actions/set-borrow-lend-amount-fraction fraction]]}}
   label])

(defn- preview-section
  [{:keys [preview-title preview]}]
  (when (seq preview)
    [:section {:class ["rounded-lg" "border" "border-ho-border" "overflow-hidden"]
               :aria-label preview-title
               :data-role "earn-modal-preview"}
     [:div {:class ["flex" "justify-between" "items-baseline" "px-3.5" "py-2.5" "bg-ho-bg"]}
      [:h3 {:class ["m-0" "text-xs" "font-semibold" "uppercase" "tracking-wide" "text-ho-text-secondary"]}
       preview-title]
      [:span {:class ["text-xs" "text-ho-text-dim"]} "estimate"]]
     [:dl {:class ["m-0" "grid" "grid-cols-[1fr_auto_auto_auto]" "gap-x-2.5" "gap-y-2.5" "px-3.5" "py-3" "items-baseline"]}
      (mapcat (fn [{:keys [label before after tone]}]
                [[:dt {:class ["text-ho-text-secondary" "text-sm"]} label]
                 [:dd {:class ["m-0" "num" "text-sm" "text-ho-text-secondary"]} before]
                 [:dd {:class ["m-0" "text-ho-text-dim" "text-xs"] :aria-hidden "true"} "→"]
                 [:dd {:class (into ["m-0" "num" "text-sm" "font-medium" "text-right"]
                                    (get tone-classes tone ["text-ho-text"]))}
                  after]])
              preview)]]))

(defn- note
  [text classes]
  (when (seq text)
    [:p {:class (into ["m-0" "text-xs" "leading-5"] classes)} text]))

(defn earn-modal
  [{:keys [title tabs amount max-label max-value borrow-apy above-kink? rate-note collateral-note
           pm-note error submitting? submit-disabled? cta kind] :as modal}]
  [:div {:class ["fixed" "inset-0" "z-50" "flex" "items-center" "justify-center" "p-4"]
         :data-role "earn-modal-layer"}
   [:button {:type "button"
             :aria-label "Close"
             :class ["absolute" "inset-0" "bg-ho-bg-deep/70" "cursor-default"]
             :on {:click [[:actions/close-borrow-lend-modal]]}}]
   [:div {:role "dialog"
          :aria-modal "true"
          :aria-labelledby "earn-modal-title"
          :class ["relative" "w-full" "max-w-[472px]" "max-h-[calc(100vh-2rem)]" "overflow-y-auto"
                  "rounded-xl" "border" "border-ho-border" "bg-ho-surface" "p-5"
                  "flex" "flex-col" "gap-4" "text-ho-text"]
          :data-role "earn-modal"}
    [:div {:class ["flex" "items-center" "justify-between"]}
     [:h2 {:id "earn-modal-title" :class ["m-0" "text-base" "font-semibold"]} title]
     [:button {:type "button"
               :aria-label "Close"
               :class ["w-8" "h-8" "rounded-md" "text-ho-text-secondary" "hover:text-ho-text"
                       "inline-flex" "items-center" "justify-center"]
               :on {:click [[:actions/close-borrow-lend-modal]]}}
      [:svg {:width 16 :height 16 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
             :stroke-width 2 :stroke-linecap "round" :aria-hidden "true"}
       [:path {:d "M6 6l12 12M18 6L6 18"}]]]]
    [:div {:role "tablist" :class ["flex" "gap-1" "p-1" "rounded-lg" "bg-ho-bg"]}
     (for [tab tabs] ^{:key (name (:kind tab))} (tab-button tab))]
    (note pm-note ["rounded-lg" "border" "border-ho-border-accent" "bg-ho-accent-soft" "p-3" "text-ho-text"])
    [:div {:class ["flex" "flex-col" "gap-2"]}
     [:div {:class ["flex" "justify-between" "text-xs" "text-ho-text-secondary"]}
      [:label {:for "earn-modal-amount"} "Amount"]
      [:span max-label " " [:span {:class ["num" "text-ho-text"]} max-value]]]
     [:div {:class (into ["flex" "items-center" "gap-2.5" "px-3.5" "h-14" "rounded-lg" "border" "bg-ho-bg"]
                         (if error ["border-ho-sell"] ["border-ho-border" "focus-within:border-ho-accent"]))}
      [:input {:id "earn-modal-amount"
               :type "text"
               :inputmode "decimal"
               :autocomplete "off"
               :placeholder "0.00"
               :value (or amount "")
               :aria-invalid (str (boolean error))
               :aria-describedby (when error "earn-modal-error")
               :class ["flex-1" "min-w-0" "bg-transparent" "border-0" "outline-none" "p-0"
                       "text-[22px]" "num" "text-ho-text" "focus:ring-0"]
               :data-role "earn-modal-amount"
               :on {:input [[:actions/set-borrow-lend-amount [:event.target/value]]]}}]
      [:span {:class ["font-semibold" "text-ho-text-secondary"]} (:symbol modal)]]
     (when error
       [:div {:id "earn-modal-error" :role "alert" :class ["text-xs" "text-ho-sell"]
              :data-role "earn-modal-error"}
        error])
     [:div {:class ["flex" "gap-1.5"]}
      (preset-button "25%" 0.25)
      (preset-button "50%" 0.5)
      (preset-button "75%" 0.75)
      (preset-button "Max" 1)]]
    (preview-section modal)
    (when borrow-apy
      [:dl {:class ["m-0" "grid" "grid-cols-[1fr_auto]" "gap-2" "text-xs"]}
       [:dt {:class ["text-ho-text-secondary"]} "Borrow APY (variable)"]
       [:dd {:class (into ["m-0" "num"] (if above-kink? ["text-ho-warn"] ["text-ho-text"]))} borrow-apy]
       [:dt {:class ["text-ho-text-secondary"]} "Interest is charged"]
       [:dd {:class ["m-0"]} "Hourly"]])
    (when (and borrow-apy above-kink? (= :borrow kind))
      (note "This pool is above its 80% utilization kink, so the rate can rise quickly if more is borrowed."
            ["text-ho-text-dim"]))
    (note rate-note ["text-ho-text-dim"])
    (note collateral-note ["text-ho-text-dim"])
    [:button {:type "button"
              :disabled submit-disabled?
              :aria-busy (str (boolean submitting?))
              :class ["h-11" "rounded-lg" "bg-ho-accent" "text-ho-bg-deep" "text-sm" "font-semibold"
                      "hover:bg-ho-accent-hi" "disabled:bg-ho-surface-raised" "disabled:text-ho-text-dim"
                      "disabled:cursor-not-allowed"]
              :data-role "earn-modal-submit"
              :on {:click [[:actions/submit-borrow-lend]]}}
     cta]]])
