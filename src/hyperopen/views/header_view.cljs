(ns hyperopen.views.header-view
  (:require [hyperopen.views.header.navigation :as navigation]
            [hyperopen.views.header.settings :as settings]
            [hyperopen.views.header.spectate :as spectate]
            [hyperopen.views.header.vm :as vm]
            [hyperopen.views.header.wallet :as wallet]))

(def brand-mark-classes
  ["text-primary"
   "font-black"
   "tracking-[-0.12em]"
   "italic"
   "select-none"])

(def brand-wordmark-classes
  ["text-primary"
   "font-black"
   "tracking-[-0.06em]"
   "italic"
   "select-none"])

(defn header-view
  [state]
  (let [{:keys [desktop-nav-items mobile-menu-open? mobile-nav more-nav settings spectate wallet]}
        (vm/header-vm state)]
    [:header.bg-base-200.border-b.border-base-300.w-full
     {:data-parity-id "header"}
     [:div {:class ["w-full" "app-shell-gutter" "py-2" "md:py-3"]}
      [:div {:class ["flex" "items-center" "gap-2" "md:gap-4"]}
       [:div {:class ["flex" "items-center" "gap-2.5" "md:gap-3" "min-w-0"]}
        (navigation/render-mobile-menu mobile-nav spectate mobile-menu-open?)
        [:button {:type "button"
                  :class ["md:hidden" "inline-flex" "items-center" "rounded-lg" "px-1" "py-0.5"]
                  :on {:click [[:actions/navigate "/trade"]]}
                  :data-role "mobile-brand"}
         [:span {:class (into ["text-lg" "leading-none"]
                              brand-mark-classes)}
          "HO"]]
        [:div {:class ["hidden" "md:flex" "items-center" "space-x-2" "sm:space-x-3"]}
         [:span {:class (into ["text-xl" "leading-none" "sm:text-3xl"]
                              brand-wordmark-classes)}
          "HyperOpen"]]]
       (navigation/render-desktop-nav desktop-nav-items more-nav)
       [:div {:class ["ml-auto" "flex" "items-center" "gap-1.5" "sm:gap-2.5" "lg:gap-4"]
              :data-parity-id "header-wallet-control"}
        [:div {:class ["inline-flex" "md:hidden" "lg:inline-flex"]}
         (spectate/render-trigger spectate)]
        (wallet/render wallet)
        [:div {:class ["relative" "flex" "items-center" "gap-1.5" "sm:gap-2"]
               :data-role "header-settings-toolbar"}
         (settings/render-trigger settings)
         (settings/render-shell settings)]]]]]))
