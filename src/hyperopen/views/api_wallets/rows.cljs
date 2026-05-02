(ns hyperopen.views.api-wallets.rows
  (:require [hyperopen.views.api-wallets.common :refer [format-valid-until]]))

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

(defn- sortable-header
  [label column sort]
  (let [active? (= column (:column sort))]
    [:button {:type "button"
              :class ["inline-flex"
                      "items-center"
                      "gap-1"
                      "font-normal"
                      "text-trading-text-secondary"
                      "hover:text-trading-text"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]
              :on {:click [[:actions/set-api-wallet-sort column]]}}
     [:span label]
     (when active?
       (sort-direction-icon (:direction sort)))]))

(defn- api-wallet-row
  [row]
  [:tr {:class ["border-b"
                "border-base-300/50"
                "text-sm"
                "text-trading-text"
                "hover:bg-base-200/40"]
        :data-role "api-wallets-table-row"}
   [:td {:class ["px-3" "py-3" "text-left"]}
    [:div {:class ["flex" "items-center" "gap-2"]}
     [:span {:class ["font-medium" "text-white"]}
      (:name row)]
     (when (= :default (:row-kind row))
       [:span {:class ["rounded-full"
                       "border"
                       "border-[#2b5d5b]"
                       "bg-[#103c39]"
                       "px-2"
                       "py-0.5"
                       "text-xs"
                       "font-semibold"
                       "uppercase"
                       "tracking-[0.08em]"
                       "text-[#9cf9e2]"]}
        "Default"])]] 
   [:td {:class ["px-3" "py-3" "text-left" "num"]}
    (:address row)]
   [:td {:class ["px-3" "py-3" "text-left" "num"]}
    (format-valid-until (:valid-until-ms row))]
   [:td {:class ["px-3" "py-3" "text-left"]}
    [:button {:type "button"
              :class ["rounded-lg"
                      "border"
                      "border-[#6a3941]"
                      "bg-[#2a151c]"
                      "px-3"
                      "py-1.5"
                      "text-xs"
                      "font-medium"
                      "text-[#f3c0cb]"
                      "transition-colors"
                      "hover:border-[#8a4b56]"
                      "hover:bg-[#341b24]"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]
              :on {:click [[:actions/open-api-wallet-remove-modal row]]}}
     "Remove"]]])

(defn- api-wallet-mobile-row
  [row]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "p-3"
                 "space-y-2"]
         :data-role "api-wallets-mobile-row"}
   [:div {:class ["flex" "items-start" "justify-between" "gap-2"]}
    [:div {:class ["space-y-1"]}
     [:div {:class ["flex" "items-center" "gap-2"]}
      [:span {:class ["text-sm" "font-medium" "text-white"]}
       (:name row)]
      (when (= :default (:row-kind row))
        [:span {:class ["rounded-full"
                        "border"
                        "border-[#2b5d5b]"
                        "bg-[#103c39]"
                        "px-2"
                        "py-0.5"
                        "text-xs"
                        "font-semibold"
                        "uppercase"
                        "tracking-[0.08em]"
                        "text-[#9cf9e2]"]}
         "Default"])]
     [:div {:class ["num" "break-all" "text-xs" "text-trading-text-secondary"]}
      (:address row)]]
    [:button {:type "button"
              :class ["rounded-lg"
                      "border"
                      "border-[#6a3941]"
                      "bg-[#2a151c]"
                      "px-3"
                      "py-1.5"
                      "text-xs"
                      "font-medium"
                      "text-[#f3c0cb]"]
              :on {:click [[:actions/open-api-wallet-remove-modal row]]}}
     "Remove"]]
   [:div {:class ["text-xs" "text-trading-text-secondary"]}
    [:span "Valid Until "]
    [:span {:class ["num" "text-trading-text"]}
     (format-valid-until (:valid-until-ms row))]]])
(defn rows-section
  [{:keys [rows sort loading? error]}]
  [:section {:class ["rounded-xl"
                     "border"
                     "border-base-300"
                     "bg-base-100"
                     "overflow-hidden"]}
   [:div {:class ["hidden" "md:block" "overflow-x-auto"]}
    [:table {:class ["min-w-full"]
             :data-role "api-wallets-table"}
     [:thead {:class ["bg-base-100"]}
      [:tr {:class ["text-xs" "text-trading-text-secondary"]}
       [:th {:class ["px-3" "py-2" "text-left"]}
        (sortable-header "API Wallet Name" :name sort)]
       [:th {:class ["px-3" "py-2" "text-left"]}
        (sortable-header "API Wallet Address" :address sort)]
       [:th {:class ["px-3" "py-2" "text-left"]}
        (sortable-header "Valid Until" :valid-until sort)]
       [:th {:class ["px-3" "py-2" "text-left"]}
        "Action"]]]
     [:tbody
      (cond
        (seq rows)
        (for [row rows]
          ^{:key (str (:row-kind row) ":" (:address row) ":" (:approval-name row))}
          (api-wallet-row row))

        loading?
        [:tr
         [:td {:col-span 4
               :class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]}
          "Loading API wallets..."]]

        (seq error)
        [:tr
         [:td {:col-span 4
               :class ["px-3" "py-8" "text-center" "text-sm" "text-[#f2b8c5]"]}
          error]]

        :else
        [:tr
         [:td {:col-span 4
               :class ["px-3" "py-8" "text-center" "text-sm" "text-trading-text-secondary"]}
          "No API wallets authorized yet."]])]]]
   [:div {:class ["grid" "gap-2" "p-3" "md:hidden"]}
    (cond
      (seq rows)
      (for [row rows]
        ^{:key (str (:row-kind row) ":" (:address row) ":" (:approval-name row))}
        (api-wallet-mobile-row row))

      loading?
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100" "px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
       "Loading API wallets..."]

      (seq error)
      [:div {:class ["rounded-xl" "border" "border-[#7b3340]" "bg-[#3a1b22]/55" "px-3" "py-6" "text-center" "text-sm" "text-[#f2b8c5]"]}
       error]

      :else
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100" "px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
       "No API wallets authorized yet."])]])
