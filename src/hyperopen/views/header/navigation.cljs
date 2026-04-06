(ns hyperopen.views.header.navigation
  (:require [hyperopen.views.header.icons :as icons]))

(def header-nav-link-base-classes
  ["header-nav-link"
   "transition-colors"
   "no-underline"])

(def header-nav-link-active-classes
  (into header-nav-link-base-classes
        ["header-nav-link-active"
         "hover:text-[#aefde8]"]))

(def header-nav-link-inactive-classes
  (into header-nav-link-base-classes
        ["text-white"
         "opacity-80"
         "hover:opacity-100"
         "hover:text-white"]))

(defn- nav-link
  [{:keys [action active? href label route]}]
  [:button {:type "button"
            :role "link"
            :class (into (if active?
                           header-nav-link-active-classes
                           header-nav-link-inactive-classes)
                         ["border-0"
                          "bg-transparent"
                          "p-0"
                          "appearance-none"])
            :href (or href route)
            :on {:click action}}
   label])

(defn- more-trigger-classes
  [active?]
  (if active?
    (into header-nav-link-active-classes
          ["group"
           "flex"
           "items-center"
           "space-x-1"
           "list-none"
           "cursor-pointer"])
    (into header-nav-link-inactive-classes
          ["group"
           "flex"
           "items-center"
           "space-x-1"
           "list-none"
           "cursor-pointer"])))

(defn- more-menu-link
  [{:keys [action active? href label more-data-role route]}]
  [:button {:type "button"
            :role "link"
            :class (into ["flex"
                          "w-full"
                          "items-center"
                          "justify-between"
                          "gap-3"
                          "rounded-lg"
                          "border-0"
                          "bg-transparent"
                          "px-3"
                          "py-2"
                          "text-left"
                          "text-sm"
                          "no-underline"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"
                          "appearance-none"]
                         (if active?
                           ["bg-[#123a36]" "text-[#97fce4]"]
                           ["text-white" "hover:bg-base-200"]))
            :href (or href route)
            :data-role more-data-role
            :on {:click action}}
   [:span label]
   (when active?
     [:span {:class ["text-xs"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.08em]"]}
      "Open"])])

(defn render-desktop-nav
  [desktop-nav-items {:keys [active? items menu-key]}]
  [:nav.hidden.md:flex.flex-1.items-center.justify-start.space-x-8.ml-8
   {:data-parity-id "header-nav"}
   (for [{:keys [id route] :as item} desktop-nav-items]
     ^{:key (str "desktop-nav:" (name id) ":" route)}
     (nav-link item))
   [:details {:class ["relative" "group"]
              :replicant/key menu-key
              :data-role "header-more-menu"}
    [:summary {:class (more-trigger-classes active?)
               :data-role "header-more-trigger"}
     [:span "More"]
     (icons/chevron-down-icon
      {:class ["h-4"
               "w-4"
               "transition-transform"
               "duration-150"
               "ease-out"
               "group-open:rotate-180"]
       :data-role "header-more-chevron"})]
    [:div {:class ["ui-dropdown-panel"
                   "absolute"
                   "left-0"
                   "top-full"
                   "z-[260]"
                   "mt-2"
                   "min-w-[220px]"
                   "rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-trading-bg"
                   "p-2"
                   "shadow-2xl"]
           :style {:--ui-dropdown-origin "top left"}
           :data-ui-native-details-panel "true"
           :data-role "header-more-menu-panel"}
     (for [{:keys [id route] :as item} items]
       ^{:key (str "more-nav:" (name id) ":" route)}
       (more-menu-link item))]]])

(defn- mobile-menu-link
  [{:keys [action active? label mobile-data-role]}]
  [:button {:type "button"
            :class (into ["flex"
                          "w-full"
                          "items-center"
                          "px-4"
                          "py-3.5"
                          "text-left"
                          "text-[1.35rem]"
                          "font-medium"
                          "leading-none"
                          "transition-colors"]
                         (if active?
                           ["text-white"]
                           ["text-[#d7e7e8]" "hover:bg-[#0d1d22]" "hover:text-white"]))
            :on {:click action}
            :data-role mobile-data-role}
   [:span label]])

(defn- mobile-menu-section
  [items]
  [:div {:class ["border-b" "border-[#173038]" "py-2"]}
   (for [{:keys [id route] :as item} items]
     ^{:key (str "mobile-nav:" (name id) ":" route)}
     (mobile-menu-link item))])

(defn render-mobile-menu
  [{:keys [primary-items secondary-items]}
   {:keys [active? mobile-action mobile-label]}
   menu-open?]
  [:div {:class ["md:hidden"] :data-role "mobile-header-menu"}
   [:button {:type "button"
             :class ["flex"
                     "h-9"
                     "w-9"
                     "items-center"
                     "justify-center"
                     "rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "transition-colors"
                     "hover:bg-base-200"
                     "focus:outline-none"
                     "focus:ring-2"
                     "focus:ring-[#66e3c5]/50"
                     "focus:ring-offset-0"]
             :on {:click [[:actions/open-mobile-header-menu]]}
             :aria-label "Open mobile menu"
             :data-role "mobile-header-menu-trigger"}
    (icons/mobile-menu-icon)]
   (when menu-open?
     [:div {:class ["fixed" "inset-0" "z-[290]"] :data-role "mobile-header-menu-layer"}
      [:button {:type "button"
                :class ["absolute" "inset-0" "bg-black/55" "backdrop-blur-[1px]"]
                :style {:transition "opacity 0.14s ease-out"
                        :opacity 1}
                :replicant/mounting {:style {:opacity 0}}
                :replicant/unmounting {:style {:opacity 0}}
                :on {:click [[:actions/close-mobile-header-menu]]}
                :aria-label "Close mobile menu"
                :data-role "mobile-header-menu-backdrop"}]
      [:aside {:class ["absolute"
                       "inset-y-0"
                       "left-0"
                       "flex"
                       "w-[min(19rem,calc(100vw-2.75rem))]"
                       "max-w-full"
                       "flex-col"
                       "border-r"
                       "border-[#173038]"
                       "bg-[#071115]"
                       "shadow-2xl"]
               :style {:transition "transform 0.16s ease-out, opacity 0.16s ease-out"
                       :transform "translateX(0)"
                       :opacity 1}
               :replicant/mounting {:style {:transform "translateX(-18px)"
                                            :opacity 0}}
               :replicant/unmounting {:style {:transform "translateX(-18px)"
                                              :opacity 0}}
               :role "dialog"
               :aria-modal true
               :aria-label "Mobile navigation"
               :data-role "mobile-header-menu-panel"}
       [:div {:class ["flex"
                      "items-center"
                      "justify-between"
                      "border-b"
                      "border-[#173038]"
                      "px-4"
                      "py-4"]}
        [:div {:class ["flex" "items-center" "gap-3"]}
         [:span {:class ["text-primary"
                         "font-black"
                         "tracking-[-0.12em]"
                         "italic"
                         "select-none"
                         "text-[1.9rem]"
                         "leading-none"]
                 :data-role "mobile-header-menu-brand-mark"}
          "HO"]
         [:div {:class ["text-xs" "font-semibold" "uppercase" "tracking-[0.18em]" "text-[#85a3a8]"]}
          "Menu"]]
        [:button {:type "button"
                  :class ["inline-flex"
                          "h-9"
                          "w-9"
                          "items-center"
                          "justify-center"
                          "rounded-xl"
                          "border"
                          "border-[#173038]"
                          "bg-[#0b181d]"
                          "text-[#d7e7e8]"
                          "transition-colors"
                          "hover:bg-[#102229]"
                          "focus:outline-none"
                          "focus:ring-2"
                          "focus:ring-[#66e3c5]/40"
                          "focus:ring-offset-0"]
                  :on {:click [[:actions/close-mobile-header-menu]]}
                  :aria-label "Close mobile menu"
                  :data-role "mobile-header-menu-close"}
         (icons/close-icon {:class ["h-5" "w-5"]})]]
       [:div {:class ["flex-1" "overflow-y-auto" "pb-6"]}
        (mobile-menu-section primary-items)
        (mobile-menu-section secondary-items)
        [:div {:class ["py-2"]}
         [:button {:type "button"
                   :class ["flex"
                           "w-full"
                           "items-center"
                           "justify-between"
                           "px-4"
                           "py-3.5"
                           "text-left"
                           "text-[1.05rem]"
                           "font-medium"
                           "text-[#97f7e2]"
                           "transition-colors"
                           "hover:bg-[#0d1d22]"]
                   :on {:click mobile-action}
                   :data-role "mobile-header-menu-spectate"}
          [:span mobile-label]
          (icons/spectate-mode-icon)]]]]])])
