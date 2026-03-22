(ns hyperopen.views.header.spectate
  (:require [hyperopen.views.header.icons :as icons]))

(defn render-trigger
  [{:keys [active? button-label tooltip-copy tooltip-id trigger-action]}]
  [:div {:class ["relative" "inline-flex" "group"]
         :data-role "spectate-mode-trigger"}
   [:button {:type "button"
             :class (into ["inline-flex"
                           "h-9"
                           "w-9"
                           "sm:h-10"
                           "sm:w-10"
                           "items-center"
                           "justify-center"
                           "rounded-xl"
                           "border"
                           "transition-colors"
                           "focus:outline-none"
                           "focus:ring-2"
                           "focus:ring-[#66e3c5]/50"
                           "focus:ring-offset-0"]
                          (if active?
                            ["border-[#2c5d5a]"
                             "bg-[#0d3a35]"
                             "text-[#daf3ef]"
                             "hover:bg-[#115046]"]
                            ["border-base-300"
                             "bg-base-100"
                             "text-white"
                             "hover:bg-base-200"]))
             :on {:click trigger-action}
             :aria-label button-label
             :aria-describedby tooltip-id
             :data-spectate-mode-trigger "true"
             :data-role "spectate-mode-open-button"}
    (icons/spectate-mode-icon)]
   [:div {:id tooltip-id
          :role "tooltip"
          :class ["pointer-events-none"
                  "absolute"
                  "right-0"
                  "top-full"
                  "z-[260]"
                  "mt-2"
                  "w-64"
                  "translate-y-1"
                  "rounded-lg"
                  "border"
                  "border-[#264b4f]"
                  "bg-[#0b1619]"
                  "px-3"
                  "py-2.5"
                  "text-left"
                  "text-xs"
                  "leading-5"
                  "text-[#d2e8eb]"
                  "opacity-0"
                  "shadow-2xl"
                  "transition-all"
                  "duration-150"
                  "group-hover:translate-y-0"
                  "group-hover:opacity-100"
                  "group-focus-within:translate-y-0"
                  "group-focus-within:opacity-100"]
          :data-role "spectate-mode-open-tooltip"}
    [:div {:class ["absolute"
                   "-top-1.5"
                   "right-3"
                   "h-3"
                   "w-3"
                   "rotate-45"
                   "border-l"
                   "border-t"
                   "border-[#264b4f]"
                   "bg-[#0b1619]"]}]
    tooltip-copy]])
