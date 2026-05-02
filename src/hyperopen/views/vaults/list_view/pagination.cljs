(ns hyperopen.views.vaults.list-view.pagination
  (:require [hyperopen.views.vaults.list-view.format :as format]))

(defn user-vault-pagination-controls
  [{:keys [total-rows
           page-size
           page
           page-count
           page-size-options
           page-size-dropdown-open?]}]
  (when (pos? total-rows)
    (let [page-size* (str page-size)]
      [:div {:class ["mt-2"
                     "flex"
                     "flex-wrap"
                     "items-center"
                     "justify-between"
                     "gap-2"
                     "border-t"
                     "border-base-300/80"
                     "pt-2"
                     "text-xs"]}
       [:div {:class ["flex" "items-center" "gap-2"]}
        [:span {:id "vaults-user-page-size-label"
                :class ["text-trading-text-secondary"]}
         "Rows"]
        [:div {:class ["relative"]
               :style (when page-size-dropdown-open?
                        {:z-index 1200})}
         (when page-size-dropdown-open?
           [:button {:type "button"
                     :class ["fixed" "inset-0" "bg-transparent" "cursor-default"]
                     :style {:z-index 1200}
                     :aria-label "Close rows per page menu"
                     :on {:click [[:actions/close-vaults-user-page-size-dropdown]]}}])
         [:button {:id "vaults-user-page-size"
                   :type "button"
                   :aria-haspopup "listbox"
                   :aria-expanded (boolean page-size-dropdown-open?)
                   :aria-labelledby "vaults-user-page-size-label"
                   :class (into ["relative"
                                 "flex"
                                 "h-8"
                                 "min-w-[72px]"
                                 "cursor-pointer"
                                 "items-center"
                                 "justify-between"
                                 "gap-2"
                                 "rounded-lg"
                                 "border"
                                 "border-base-300"
                                 "bg-base-100"
                                 "pl-3"
                                 "pr-2"
                                 "text-xs"
                                 "text-trading-text"
                                 "hover:bg-base-200"]
                                format/focus-ring-classes)
                   :style (when page-size-dropdown-open?
                            {:z-index 1201})
                   :on {:click [[:actions/toggle-vaults-user-page-size-dropdown]]}}
          [:span {:class ["num" "text-sm" "leading-none"]} page-size*]
          [:svg {:class ["h-3.5" "w-3.5" "shrink-0" "text-trading-text-secondary"]
                 :viewBox "0 0 20 20"
                 :fill "currentColor"
                 :aria-hidden true}
           [:path {:fill-rule "evenodd"
                   :clip-rule "evenodd"
                   :d "M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.168l3.71-3.938a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"}]]]
         (when page-size-dropdown-open?
           [:div {:class ["absolute"
                          "left-0"
                          "bottom-full"
                          "mb-1"
                          "min-w-[88px]"
                          "max-h-40"
                          "overflow-y-auto"
                          "rounded-lg"
                          "border"
                          "border-base-300"
                          "bg-base-100"
                          "p-1"
                          "shadow-2xl"]
                  :style {:z-index 1202}
                  :role "listbox"
                  :aria-labelledby "vaults-user-page-size-label"}
            (for [size page-size-options]
              (let [size* (str size)
                    active? (= size* page-size*)]
                ^{:key (str "vault-page-size-" size)}
                [:button {:type "button"
                          :class (into ["w-full"
                                        "rounded-md"
                                        "px-2.5"
                                        "py-1.5"
                                        "text-left"
                                        "text-xs"
                                        "num"
                                        "transition-colors"]
                                       (concat (if active?
                                                 ["bg-[#123a36]" "text-[#97fce4]"]
                                                 ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"])
                                               format/focus-ring-classes))
                          :role "option"
                          :aria-selected (boolean active?)
                          :on {:click [[:actions/set-vaults-user-page-size size]]}}
                 size*]))])]
        [:span {:class ["text-trading-text-secondary"]}
         (str "Total: " total-rows)]]
       [:div {:class ["flex" "items-center" "gap-2"]}
        [:button {:type "button"
                  :class (into ["h-7"
                                "rounded-md"
                                "border"
                                "border-base-300"
                                "px-2"
                                "text-xs"
                                "text-trading-text"
                                "disabled:cursor-not-allowed"
                                "disabled:opacity-40"]
                               format/focus-ring-classes)
                  :disabled (<= page 1)
                  :on {:click [[:actions/prev-vaults-user-page page-count]]}}
         "Prev"]
        [:span {:class ["min-w-[6rem]" "text-center" "text-trading-text-secondary"]}
         (str "Page " page " of " page-count)]
        [:button {:type "button"
                  :class (into ["h-7"
                                "rounded-md"
                                "border"
                                "border-base-300"
                                "px-2"
                                "text-xs"
                                "text-trading-text"
                                "disabled:cursor-not-allowed"
                                "disabled:opacity-40"]
                               format/focus-ring-classes)
                  :disabled (>= page page-count)
                  :on {:click [[:actions/next-vaults-user-page page-count]]}}
         "Next"]]])))
