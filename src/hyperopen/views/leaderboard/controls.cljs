(ns hyperopen.views.leaderboard.controls
  (:require [hyperopen.views.leaderboard.styles :refer [focus-reset-classes
                                                         focus-visible-ring-classes]]))

(defn- sort-direction-icon
  [direction]
  [:svg {:class (into ["h-3" "w-3" "shrink-0" "opacity-70" "transition-transform"]
                      (if (= :asc direction)
                        ["rotate-180"]
                        ["rotate-0"]))
         :viewBox "0 0 12 12"
         :aria-hidden true}
   [:path {:d "M3 4.5L6 7.5L9 4.5"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "1.5"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(defn sortable-header
  [label column sort-state]
  (let [active? (= column (:column sort-state))]
    [:button {:type "button"
              :class (into ["inline-flex"
                            "items-center"
                            "gap-1"
                            "font-normal"
                            "text-trading-text-secondary"
                            "hover:text-trading-text"]
                           focus-visible-ring-classes)
              :on {:click [[:actions/set-leaderboard-sort column]]}}
     [:span label]
     (when active?
       (sort-direction-icon (:direction sort-state)))]))
(defn timeframe-button
  [selected? {:keys [value label]}]
  [:button {:type "button"
            :class (into ["rounded-lg"
                          "border"
                          "px-2.5"
                          "py-1.5"
                          "text-xs"
                          "font-medium"
                          "transition-colors"]
                         (concat focus-visible-ring-classes
                                 (if selected?
                                   ["border-[#2f7f73]" "bg-[#123a36]/85" "text-[#97fce4]"]
                                   ["border-base-300/80"
                                    "text-trading-text-secondary"
                                    "hover:bg-base-200"
                                    "hover:text-trading-text"])))
            :on {:click [[:actions/set-leaderboard-timeframe value]]}}
   label])

(defn- page-size-option
  [size active?]
  [:button {:type "button"
            :class (into ["flex"
                          "w-full"
                          "items-center"
                          "justify-start"
                          "rounded-md"
                          "px-2.5"
                          "py-1.5"
                          "text-xs"
                          "text-left"
                          "num"
                          "text-trading-text-secondary"
                          "hover:bg-base-200"
                          "hover:text-trading-text"]
                         focus-reset-classes)
            :role "option"
            :aria-selected (boolean active?)
            :on {:mousedown [[:actions/set-leaderboard-page-size size]]
                 :click [[:actions/set-leaderboard-page-size size]]}}
   (str size)])
(defn pagination-controls
  [{:keys [page
           page-count
           total-rows
           page-size
           page-size-options
           page-size-dropdown-open?]}]
  (let [page-size* (str page-size)]
    [:div {:class ["flex"
                   "flex-wrap"
                   "items-center"
                   "justify-between"
                   "gap-3"
                   "border-t"
                   "border-base-300/60"
                   "px-4"
                   "py-3"]
           :data-role "leaderboard-pagination"}
     [:div {:class ["flex" "flex-wrap" "items-center" "gap-3"]}
      [:div {:class ["flex" "items-center" "gap-2"]}
       [:span {:id "leaderboard-page-size-label"
               :class ["text-sm" "text-trading-text-secondary"]}
        "Rows"]
       [:div {:class ["relative"]
              :style (when page-size-dropdown-open?
                       {:z-index 1200})}
        (when page-size-dropdown-open?
          [:button {:type "button"
                    :class ["fixed" "inset-0" "cursor-default" "bg-transparent"]
                    :style {:z-index 1200}
                    :aria-label "Close rows per page menu"
                    :on {:click [[:actions/close-leaderboard-page-size-dropdown]]}}])
        [:button {:id "leaderboard-page-size"
                  :type "button"
                  :aria-haspopup "listbox"
                  :aria-expanded (boolean page-size-dropdown-open?)
                  :aria-labelledby "leaderboard-page-size-label"
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
                               focus-reset-classes)
                  :style (when page-size-dropdown-open?
                           {:z-index 1201})
                  :on {:click [[:actions/toggle-leaderboard-page-size-dropdown]]}}
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
                 :aria-labelledby "leaderboard-page-size-label"}
           (for [size page-size-options]
             ^{:key (str "leaderboard-page-size-" size)}
             (page-size-option size (= size page-size)))])]]
      [:span {:class ["text-sm" "text-trading-text-secondary"]}
       (str "Total: " total-rows " ranked trader"
            (when (not= 1 total-rows) "s"))]]
     [:div {:class ["flex" "items-center" "gap-2"]}
      [:button {:type "button"
                :class (into ["rounded-lg"
                              "border"
                              "border-base-300"
                              "px-3"
                              "py-1.5"
                              "text-sm"
                              "text-trading-text-secondary"
                              "transition-colors"
                              "hover:bg-base-200"
                              "hover:text-trading-text"
                              "disabled:cursor-not-allowed"
                              "disabled:opacity-50"]
                             focus-visible-ring-classes)
                :disabled (= page 1)
                :on {:click [[:actions/prev-leaderboard-page page-count]]}}
       "Prev"]
      [:div {:class ["num" "text-sm" "text-trading-text-secondary"]}
       (str page " / " page-count)]
      [:button {:type "button"
                :class (into ["rounded-lg"
                              "border"
                              "border-base-300"
                              "px-3"
                              "py-1.5"
                              "text-sm"
                              "text-trading-text-secondary"
                              "transition-colors"
                              "hover:bg-base-200"
                              "hover:text-trading-text"
                              "disabled:cursor-not-allowed"
                              "disabled:opacity-50"]
                             focus-visible-ring-classes)
                :disabled (= page page-count)
                :on {:click [[:actions/next-leaderboard-page page-count]]}}
       "Next"]]]))
