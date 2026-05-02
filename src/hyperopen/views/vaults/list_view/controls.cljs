(ns hyperopen.views.vaults.list-view.controls
  (:require [clojure.string :as str]
            [hyperopen.views.vaults.list-view.format :as format]))

(defn- dropdown-option [label active? action]
  [:button {:type "button"
            :class (into ["flex"
                          "w-full"
                          "items-center"
                          "justify-between"
                          "rounded-md"
                          "px-2.5"
                          "py-1.5"
                          "text-xs"
                          "transition-colors"]
                         (concat (if active?
                                   ["bg-[#123a36]" "text-[#97fce4]"]
                                   ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"])
                                 format/focus-ring-classes))
            :on {:click [action]}}
   [:span label]
   (when active?
     [:span {:aria-hidden true} "ON"])])

(defn- menu-role-token [label]
  (-> (or label "menu")
      str
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- control-menu [label summary-text options]
  (let [role-token (menu-role-token label)]
    [:details {:class ["relative" "group"]
               :data-role (str "vaults-" role-token "-menu")}
     [:summary {:class (into ["flex"
                              "h-8"
                              "list-none"
                              "cursor-pointer"
                              "items-center"
                              "gap-1.5"
                              "rounded-lg"
                              "border"
                              "border-base-300"
                              "bg-base-100"
                              "px-2.5"
                              "text-xs"
                              "text-trading-text"
                              "hover:bg-base-200"]
                             format/focus-ring-classes)
               :data-role (str "vaults-" role-token "-menu-trigger")}
      [:span {:class ["hidden" "sm:inline" "text-trading-text-secondary"]} label]
      [:span {:class ["max-w-[180px]" "truncate"]} summary-text]
      [:svg {:class ["h-3.5"
                     "w-3.5"
                     "text-trading-text-secondary"
                     "transition-transform"
                     "duration-150"
                     "ease-out"
                     "group-open:rotate-180"]
             :data-role (str "vaults-" role-token "-menu-chevron")
             :viewBox "0 0 20 20"
             :fill "currentColor"
             :aria-hidden true}
       [:path {:fill-rule "evenodd"
               :clip-rule "evenodd"
               :d "M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.168l3.71-3.938a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"}]]]
     [:div {:class ["ui-dropdown-panel"
                    "absolute"
                    "right-0"
                    "top-full"
                    "z-30"
                    "mt-1.5"
                    "min-w-[220px]"
                    "rounded-xl"
                    "border"
                    "border-base-300"
                    "bg-base-100"
                    "p-2"
                    "shadow-2xl"]
            :data-role (str "vaults-" role-token "-menu-panel")
            :data-ui-native-details-panel "true"}
      options]]))

(defn- selected-role-labels [{:keys [leading? deposited? others?]}]
  (cond-> []
    leading? (conj "Leading")
    deposited? (conj "Deposited")
    others? (conj "Others")))

(defn role-filter-menu [filters]
  (let [role-labels (selected-role-labels filters)
        summary-text (if (seq role-labels)
                       (str/join ", " role-labels)
                       "None")]
    (control-menu "Filter"
                  summary-text
                  [:div {:class ["space-y-1"]}
                   (dropdown-option "Leading" (:leading? filters) [:actions/toggle-vaults-filter :leading])
                   (dropdown-option "Deposited" (:deposited? filters) [:actions/toggle-vaults-filter :deposited])
                   (dropdown-option "Others" (:others? filters) [:actions/toggle-vaults-filter :others])
                   [:div {:class ["my-1" "h-px" "bg-base-300"]}]
                   (dropdown-option "Closed" (:show-closed? filters) [:actions/toggle-vaults-filter :closed])])))

(defn- snapshot-range-label [snapshot-range]
  (case snapshot-range
    :day "24H"
    :week "7D"
    :month "30D"
    :three-month "3M"
    :six-month "6M"
    :one-year "1Y"
    :two-year "2Y"
    :all-time "All-time"
    "30D"))

(defn range-menu [snapshot-range]
  (control-menu "Range"
                (snapshot-range-label snapshot-range)
                [:div {:class ["space-y-1"]}
                 (dropdown-option "24H" (= snapshot-range :day) [:actions/set-vaults-snapshot-range :day])
                 (dropdown-option "7D" (= snapshot-range :week) [:actions/set-vaults-snapshot-range :week])
                 (dropdown-option "30D" (= snapshot-range :month) [:actions/set-vaults-snapshot-range :month])
                 (dropdown-option "3M" (= snapshot-range :three-month) [:actions/set-vaults-snapshot-range :three-month])
                 (dropdown-option "6M" (= snapshot-range :six-month) [:actions/set-vaults-snapshot-range :six-month])
                 (dropdown-option "1Y" (= snapshot-range :one-year) [:actions/set-vaults-snapshot-range :one-year])
                 (dropdown-option "2Y" (= snapshot-range :two-year) [:actions/set-vaults-snapshot-range :two-year])
                 (dropdown-option "All-time" (= snapshot-range :all-time) [:actions/set-vaults-snapshot-range :all-time])]))

(defn sort-header [label column sort-state]
  (let [active? (= column (:column sort-state))
        direction (:direction sort-state)]
    [:button {:type "button"
              :class (into ["inline-flex"
                            "items-center"
                            "gap-1"
                            "text-xs"
                            "font-normal"
                            "text-trading-text-secondary"
                            "hover:text-trading-text"]
                           format/focus-ring-classes)
              :on {:click [[:actions/set-vaults-sort column]]}}
     [:span label]
     (when active?
       [:span {:class ["text-xs"]}
        (if (= :asc direction) "^" "v")])]))
