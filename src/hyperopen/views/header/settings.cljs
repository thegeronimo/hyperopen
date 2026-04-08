(ns hyperopen.views.header.settings
  (:require [hyperopen.views.header.dom :as dom]
            [hyperopen.views.header.icons :as icons]))

(defn- trading-settings-icon-shell
  [data-role kind active?]
  (when kind
    [:div {:class ["flex"
                   "h-4.5"
                   "w-4.5"
                   "shrink-0"
                   "items-center"
                   "justify-center"
                   "pt-0.5"]
           :data-role data-role}
     (icons/trading-settings-row-icon kind active?)]))

(defn- trading-settings-toggle
  [{:keys [aria-label checked? disabled? on-change]}]
  [:label {:class ["relative"
                   "inline-flex"
                   "h-[18px]"
                   "w-[36px]"
                   "shrink-0"
                   (if disabled? "cursor-not-allowed" "cursor-pointer")
                   "items-center"]}
   [:input {:type "checkbox"
            :checked (boolean checked?)
            :disabled disabled?
            :class ["peer"
                    "absolute"
                    "inset-0"
                    "z-[1]"
                    "h-full"
                    "w-full"
                    (if disabled? "cursor-not-allowed" "cursor-pointer")
                    "opacity-0"]
            :aria-label aria-label
            :on {:change on-change}}]
   [:span {:aria-hidden true
           :class (into ["absolute"
                         "inset-0"
                         "pointer-events-none"
                         "rounded-full"
                         "border"
                         "shadow-[inset_0_1px_0_rgba(255,255,255,0.035)]"
                         "transition-all"
                         "duration-200"
                         "peer-disabled:opacity-55"
                         "peer-focus-visible:ring-2"
                         "peer-focus-visible:ring-[#50d2c1]/45"
                         "peer-focus-visible:ring-offset-0"]
                        (if checked?
                          ["border-[#2d7468]"
                           "bg-[#123d37]"]
                          ["border-[#434b51]"
                           "bg-[#293036]"]))}]
   [:span {:aria-hidden true
           :class (into ["absolute"
                         "left-[2px]"
                         "top-[2px]"
                         "pointer-events-none"
                         "h-[12px]"
                         "w-[12px]"
                         "rounded-full"
                         "shadow-[0_1px_3px_rgba(0,0,0,0.3)]"
                         "transition-transform"
                         "duration-200"
                         "peer-disabled:opacity-60"]
                        (if checked?
                          ["translate-x-[18px]" "bg-[#7ce7d7]"]
                          ["translate-x-0" "bg-[#79828b]"]))}]])

(defn- confirmation-strip
  [{:keys [body cancel-action confirm-action confirm-label title]}]
  (when title
    [:div {:class ["mt-2.5"
                   "flex"
                   "items-start"
                   "justify-between"
                   "gap-3"
                   "rounded-r-[8px]"
                   "border-l-2"
                   "border-[#2d7468]"
                   "bg-[#182126]/70"
                   "pl-3.5"
                   "pr-3"
                   "py-3"]
           :data-role "trading-settings-storage-mode-confirmation"}
     [:div {:class ["min-w-0" "space-y-1"]}
      [:div {:class ["text-[0.76rem]"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.12em]"
                     "text-[#eef5f4]"]}
       title]
      [:p {:class ["max-w-[19rem]"
                   "text-[0.72rem]"
                   "leading-[1.45]"
                   "text-[#94a0a5]"]}
       body]]
     [:div {:class ["flex" "shrink-0" "gap-2"]}
      [:button {:type "button"
                :class ["rounded-lg"
                        "border"
                        "border-[#333c42]"
                        "bg-transparent"
                        "px-3"
                        "py-1.5"
                        "text-[0.74rem]"
                        "font-medium"
                        "uppercase"
                        "tracking-[0.08em]"
                        "text-[#d9dfe4]"
                        "transition-colors"
                        "hover:bg-[#262e33]"
                        "hover:text-white"]
                :on {:click cancel-action}}
       "Cancel"]
      [:button {:type "button"
                :class ["rounded-lg"
                        "border"
                        "border-[#2d7468]"
                        "bg-[#123d37]"
                        "px-3"
                        "py-1.5"
                        "text-[0.74rem]"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.08em]"
                        "text-[#d8f5f0]"
                        "transition-colors"
                        "hover:bg-[#195047]"]
                :on {:click confirm-action}}
       confirm-label]]]))

(defn- trading-settings-row
  [{:keys [aria-label checked? confirmation data-role disabled? helper-copy icon-kind on-change title]}]
  (let [has-icon? (some? icon-kind)]
    [:div {:class ["py-3"]
           :data-role data-role}
     [:div {:class (into ["flex" "items-start" (if has-icon? "gap-3" "gap-0")]
                         (when disabled?
                           ["opacity-60"]))}
      (trading-settings-icon-shell (str data-role "-icon") icon-kind checked?)
      [:div {:class ["min-w-0" "flex-1" "space-y-1"]}
       [:div {:class ["text-[0.88rem]"
                      "font-semibold"
                      "leading-5"
                      "tracking-[0.015em]"
                      "text-[#eef3f2]"]}
        title]
       [:p {:class ["max-w-[16rem]"
                    "text-[0.72rem]"
                    "leading-[1.5]"
                    "text-[#8f9aa2]"]}
        helper-copy]]
      [:div {:class ["flex" "shrink-0" "items-start" "pt-0.5"]}
       (trading-settings-toggle {:aria-label aria-label
                                 :checked? checked?
                                 :disabled? disabled?
                                 :on-change on-change})]]
     (confirmation-strip confirmation)]))

(defn- trading-settings-section
  [{:keys [data-role rows title]}]
  (into
   [:section {:class ["border-t"
                      "border-[#2b3337]"
                      "pt-4"
                      "first:border-t-0"
                      "first:pt-0"]
              :data-role data-role}
    [:div {:class ["flex" "items-center" "gap-2" "pb-1"]}
     [:div {:class ["h-[2px]" "w-[10px]" "rounded-[1px]" "bg-[#62ded0]"]}]
     [:div {:class ["text-[0.58rem]"
                    "font-semibold"
                    "uppercase"
                    "tracking-[0.24em]"
                    "text-[#8fa7a5]"]}
      title]]]
   (mapcat (fn [[index row]]
             (cond-> []
               (pos? index)
               (conj [:div {:class ["h-px" "bg-[#242c31]"]}])

               :always
               (conj (trading-settings-row row))))
           (map-indexed vector rows))))

(defn- trading-settings-content
  [{:keys [close-actions footer-note sections title]} surface-id]
  [:div {:class (into ["flex" "max-h-full" "flex-col"]
                      (when (= surface-id :sheet)
                        ["pb-[max(0.5rem,env(safe-area-inset-bottom))]"]))}
   [:div {:class ["relative" "px-4" "pb-2" "pt-3.5"]}
    [:div {:class ["absolute"
                   "inset-x-4"
                   "top-0"
                   "h-px"
                   "bg-[linear-gradient(90deg,rgba(80,210,193,0),rgba(80,210,193,0.82),rgba(80,210,193,0))]"]}]
    [:div {:class ["flex" "items-center" "justify-between" "gap-4"]}
     [:h3 {:class ["text-[0.84rem]"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.12em]"
                   "text-[#f1f7f6]"]
           :data-role "trading-settings-title"}
      title]
     [:button {:type "button"
               :class ["inline-flex"
                       "h-8"
                       "w-8"
                       "items-center"
                       "justify-center"
                       "rounded-[10px]"
                       "border"
                       "border-[#333c42]"
                       "bg-[#18252a]"
                       "text-[#99a4ab]"
                       "transition-colors"
                       "hover:border-[#50d2c1]/45"
                       "hover:bg-[#1e2c31]"
                       "hover:text-white"]
               :aria-label "Close trading settings"
               :data-role "trading-settings-close"
               :on {:click close-actions}}
      (icons/close-icon {:class ["h-4.5" "w-4.5"]})]]
    [:div {:class ["mt-3" "h-px" "bg-[#2c3439]"]}]]
   [:div {:class ["overflow-y-auto" "px-4" "pb-3" "pt-2"]}
    [:div {:class ["space-y-0"]}
     (for [{:keys [id] :as section} sections]
       ^{:key (str "settings-section:" (name id))}
       (trading-settings-section section))
     [:div {:class ["border-t"
                    "border-[#2c3439]"
                    "pt-3.5"]}
      [:div {:class ["text-[0.7rem]"
                     "leading-[1.45]"
                     "tracking-[0.02em]"
                     "text-[#839097]"]
             :data-role "trading-settings-footer-note"}
       footer-note]]]]])

(defn render-trigger
  [{:keys [open? return-focus? trigger-action trigger-key]}]
  [:button {:type "button"
            :class ["inline-flex"
                    "h-9"
                    "w-9"
                    "items-center"
                    "justify-center"
                    "rounded-xl"
                    "border"
                    "border-base-300"
                    "bg-base-100"
                    "transition-colors"
                    "hover:bg-base-200"]
            :on {:click trigger-action}
            :replicant/key trigger-key
            :replicant/on-render (when return-focus?
                                   dom/focus-visible-node!)
            :aria-haspopup "dialog"
            :aria-expanded open?
            :title "Settings"
            :aria-label "Settings"
            :data-role "header-settings-button"}
   (icons/settings-icon)])

(defn render-shell
  [{:keys [close-actions keydown-action open?] :as settings}]
  (when open?
    (list
     [:button {:type "button"
               :class ["fixed" "inset-0" "z-[275]" "bg-black/45"]
               :style {:transition "opacity 0.2s ease-out"
                       :opacity 1}
               :replicant/mounting {:style {:opacity 0}}
               :replicant/unmounting {:style {:opacity 0}}
               :aria-label "Dismiss trading settings"
               :data-role "trading-settings-backdrop"
               :on {:click close-actions}}]
     [:section {:class ["absolute"
                        "right-0"
                        "top-full"
                        "z-[285]"
                        "mt-2"
                        "hidden"
                        "w-[328px]"
                        "max-h-[70vh]"
                        "max-w-[calc(100vw-1.5rem)]"
                        "overflow-hidden"
                        "rounded-[15px]"
                        "border"
                        "border-[#384046]"
                        "bg-[#132026]"
                        "shadow-[inset_0_1px_0_rgba(255,255,255,0.04),0_18px_34px_rgba(0,0,0,0.34),0_2px_6px_rgba(0,0,0,0.2)]"
                        "md:block"]
                :role "dialog"
                :aria-modal true
                :aria-label "Trading settings"
                :tab-index 0
                :data-role "trading-settings-panel"
                :style {:transition "transform 0.22s cubic-bezier(0.22,1,0.36,1), opacity 0.22s ease-out"
                        :transform "translateY(0) scale(1)"
                        :transform-origin "top right"
                        :opacity 1}
                :replicant/mounting {:style {:transform "translateY(-8px) scale(0.97)"
                                             :opacity 0}}
                :replicant/unmounting {:style {:transform "translateY(-8px) scale(0.97)"
                                               :opacity 0}}
                :on {:keydown keydown-action}
                :replicant/on-render dom/focus-visible-node!}
      (trading-settings-content settings :panel)]
     [:section {:class ["fixed"
                        "inset-x-3"
                        "bottom-3"
                        "z-[285]"
                        "max-h-[76vh]"
                        "overflow-hidden"
                        "rounded-[16px]"
                        "border"
                        "border-[#384046]"
                        "bg-[#132026]"
                        "shadow-[inset_0_1px_0_rgba(255,255,255,0.04),0_18px_34px_rgba(0,0,0,0.34),0_2px_6px_rgba(0,0,0,0.2)]"
                        "md:hidden"]
                :role "dialog"
                :aria-modal true
                :aria-label "Trading settings"
                :tab-index 0
                :data-role "trading-settings-sheet"
                :style {:transition "transform 0.22s cubic-bezier(0.22,1,0.36,1), opacity 0.22s ease-out"
                        :transform "translateY(0)"
                        :opacity 1}
                :replicant/mounting {:style {:transform "translateY(18px)"
                                             :opacity 0}}
                :replicant/unmounting {:style {:transform "translateY(18px)"
                                               :opacity 0}}
                :on {:keydown keydown-action}
                :replicant/on-render dom/focus-visible-node!}
      (trading-settings-content settings :sheet)])))
