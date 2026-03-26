(ns hyperopen.views.header.settings
  (:require [hyperopen.views.header.dom :as dom]
            [hyperopen.views.header.icons :as icons]))

(defn- trading-settings-icon-shell
  [kind active?]
  [:div {:class ["mt-0.5"
                 "flex"
                 "h-5"
                 "w-5"
                 "shrink-0"
                 "items-center"
                 "justify-center"]}
   (icons/trading-settings-row-icon kind active?)])

(defn- trading-settings-toggle
  [{:keys [aria-label checked? on-change]}]
  [:label {:class ["relative"
                   "mt-0.5"
                   "inline-flex"
                   "h-[20px]"
                   "w-[40px]"
                   "shrink-0"
                   "cursor-pointer"
                   "items-center"]}
   [:input {:type "checkbox"
            :checked (boolean checked?)
            :class ["peer"
                    "absolute"
                    "inset-0"
                    "z-[1]"
                    "h-full"
                    "w-full"
                    "cursor-pointer"
                    "opacity-0"]
            :aria-label aria-label
            :on {:change on-change}}]
   [:span {:aria-hidden true
           :class (into ["absolute"
                         "inset-0"
                         "pointer-events-none"
                         "rounded-full"
                         "border"
                         "shadow-[inset_0_1px_0_rgba(255,255,255,0.04),inset_0_-1px_1px_rgba(0,0,0,0.22)]"
                         "transition-all"
                         "duration-200"
                         "peer-focus-visible:ring-2"
                         "peer-focus-visible:ring-[#66e3c5]/45"
                         "peer-focus-visible:ring-offset-0"]
                        (if checked?
                          ["border-[#67e5ba]"
                           "bg-[#67e5ba]"]
                          ["border-[#4a5258]"
                           "bg-[#353c42]"]))}]
   [:span {:aria-hidden true
           :class (into ["absolute"
                         "left-[3px]"
                         "top-[3px]"
                         "pointer-events-none"
                         "h-[14px]"
                         "w-[14px]"
                         "rounded-full"
                         "shadow-[0_1px_4px_rgba(0,0,0,0.32)]"
                         "transition-transform"
                         "duration-200"]
                        (if checked?
                          ["translate-x-[18px]" "bg-[#1f272c]"]
                          ["translate-x-0" "bg-[#687078]"]))}]])

(defn- confirmation-strip
  [{:keys [body confirm-label title]}]
  (when title
    [:div {:class ["mt-3"
                   "flex"
                   "items-start"
                   "justify-between"
                   "gap-3"
                   "rounded-[12px]"
                   "border"
                   "border-[#394047]"
                   "bg-[#22282d]"
                   "shadow-[inset_0_1px_0_rgba(255,255,255,0.02),0_6px_14px_rgba(0,0,0,0.18)]"
                   "px-3"
                   "py-3"]
           :data-role "trading-settings-storage-mode-confirmation"}
     [:div {:class ["min-w-0" "space-y-1"]}
      [:div {:class ["text-[0.84rem]" "font-semibold" "text-white"]}
       title]
      [:p {:class ["text-[0.76rem]" "leading-5" "text-[#9ba1a6]"]}
       body]]
     [:div {:class ["flex" "shrink-0" "gap-2"]}
      [:button {:type "button"
                :class ["rounded-lg"
                        "border"
                        "border-[#32373d]"
                        "bg-transparent"
                        "px-3"
                        "py-1.5"
                        "text-[0.8rem]"
                        "font-medium"
                        "text-[#d9dfe4]"
                        "transition-colors"
                        "hover:bg-[#2c3338]"
                        "hover:text-white"]
                :on {:click [[:actions/cancel-agent-storage-mode-change]]}}
       "Cancel"]
      [:button {:type "button"
                :class ["rounded-lg"
                        "border"
                        "border-[#44ccb5]/35"
                        "bg-[#18342e]"
                        "shadow-[inset_0_1px_0_rgba(255,255,255,0.03)]"
                        "px-3"
                        "py-1.5"
                        "text-[0.8rem]"
                        "font-medium"
                        "text-[#a9f5e4]"
                        "transition-colors"
                        "hover:bg-[#21473f]"]
                :on {:click [[:actions/confirm-agent-storage-mode-change]]}}
       confirm-label]]]))

(defn- trading-settings-row
  [{:keys [aria-label checked? confirmation data-role helper-copy icon-kind on-change title]}]
  [:div {:class ["px-3" "py-2"]
         :data-role data-role}
   [:div {:class ["flex"
                  "items-start"
                  "gap-3"
                  "rounded-[10px]"
                  "px-1"
                  "py-1.5"
                  "transition-colors"]}
    (trading-settings-icon-shell icon-kind checked?)
    [:div {:class ["min-w-0" "flex-1" "space-y-1" "pt-0.5"]}
     [:div {:class ["text-[0.95rem]" "font-semibold" "leading-5" "text-white"]}
      title]
     [:p {:class ["text-[0.76rem]" "leading-5" "text-[#9ba1a6]"]}
      helper-copy]]
    [:div {:class ["flex" "shrink-0" "items-start" "pt-1"]}
     (trading-settings-toggle {:aria-label aria-label
                               :checked? checked?
                               :on-change on-change})]]
   (confirmation-strip confirmation)])

(defn- trading-settings-section
  [{:keys [data-role rows title]}]
  (into
   [:section {:class ["overflow-hidden"
                      "rounded-[14px]"
                      "border"
                      "border-[#353d43]"
                      "bg-[#252c32]"
                      "shadow-[inset_0_1px_0_rgba(255,255,255,0.045),inset_0_-1px_0_rgba(0,0,0,0.14),0_4px_10px_rgba(0,0,0,0.20)]"]
              :data-role data-role}
    [:div {:class ["px-4"
                   "pt-3.5"
                   "text-[0.68rem]"
                   "font-semibold"
                   "uppercase"
                   "tracking-[0.14em]"
                   "text-[#8a939b]"]}
     title]]
   (mapcat (fn [[index row]]
             (cond-> []
               (pos? index)
               (conj [:div {:class ["mx-4" "h-px" "bg-[#31383e]"]}])

               :always
               (conj (trading-settings-row row))))
           (map-indexed vector rows))))

(defn- trading-settings-content
  [{:keys [close-actions footer-note sections title]} surface-id]
  [:div {:class (into ["flex" "max-h-full" "flex-col"]
                      (when (= surface-id :sheet)
                        ["pb-[max(0.5rem,env(safe-area-inset-bottom))]"]))}
   [:div {:class ["flex"
                  "items-center"
                  "justify-between"
                  "gap-4"
                  "px-4"
                  "pb-2"
                  "pt-4"]}
    [:h3 {:class ["text-[1rem]" "font-semibold" "tracking-[-0.01em]" "text-white"]
          :data-role "trading-settings-title"}
     title]
    [:button {:type "button"
              :class ["inline-flex"
                      "h-8.5"
                      "w-8.5"
                      "items-center"
                      "justify-center"
                      "rounded-[10px]"
                      "border"
                      "border-[#3a4248]"
                      "bg-[#2a3136]"
                      "shadow-[inset_0_1px_0_rgba(255,255,255,0.02)]"
                      "text-[#aeb6bc]"
                      "transition-colors"
                      "hover:bg-[#333b42]"
                      "hover:text-white"]
              :aria-label "Close trading settings"
              :data-role "trading-settings-close"
              :on {:click close-actions}}
     (icons/close-icon {:class ["h-4.5" "w-4.5"]})]]
   [:div {:class ["overflow-y-auto" "px-3" "pb-3" "pt-1"]}
    [:div {:class ["space-y-3"]}
     (for [{:keys [id] :as section} sections]
       ^{:key (str "settings-section:" (name id))}
       (trading-settings-section section))
     [:div {:class ["px-4" "pb-2.5" "pt-4" "text-[0.75rem]" "leading-5" "text-[#9aa1a7]"]
            :data-role "trading-settings-footer-note"}
      footer-note]]]])

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
                        "w-[336px]"
                        "max-h-[70vh]"
                        "max-w-[calc(100vw-1.5rem)]"
                        "overflow-hidden"
                        "rounded-[16px]"
                        "border"
                        "border-[#3b4349]"
                        "bg-[#232a30]"
                        "shadow-[inset_0_1px_0_rgba(255,255,255,0.05),inset_0_-1px_0_rgba(0,0,0,0.18),0_10px_18px_rgba(0,0,0,0.28),0_2px_4px_rgba(0,0,0,0.18)]"
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
                        "rounded-[18px]"
                        "border"
                        "border-[#3b4349]"
                        "bg-[#232a30]"
                        "shadow-[inset_0_1px_0_rgba(255,255,255,0.05),inset_0_-1px_0_rgba(0,0,0,0.18),0_10px_18px_rgba(0,0,0,0.28),0_2px_4px_rgba(0,0,0,0.18)]"
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
