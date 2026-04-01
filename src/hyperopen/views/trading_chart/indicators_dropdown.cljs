(ns hyperopen.views.trading-chart.indicators-dropdown
  (:require [clojure.string :as str]
            [hyperopen.views.trading-chart.utils.indicator-catalog :as indicator-catalog]))

(defn- includes-query?
  [value query]
  (str/includes? (str/lower-case (or value "")) query))

(defn indicators-dropdown
  "Dropdown component for selecting and managing indicators."
  [{:keys [indicators-dropdown-visible volume-visible? active-indicators search-term]}]
  [:div.relative
   (when indicators-dropdown-visible
     (let [available-indicators (indicator-catalog/get-available-indicators)
           indicator-by-id (into {} (map (juxt :id identity)) available-indicators)
           query (str/lower-case (str/trim (or search-term "")))
           filtered-indicators (if (str/blank? query)
                                 available-indicators
                                 (filter (fn [indicator]
                                           (or (includes-query? (:name indicator) query)
                                               (includes-query? (:short-name indicator) query)
                                               (includes-query? (:description indicator) query)))
                                         available-indicators))]
       [:div
        {:class ["absolute" "left-0" "top-full" "mt-1" "w-[22rem]"
                 "bg-base-100" "opacity-100" "border" "border-base-300"
                 "spectate-lg" "z-[120]" "isolate" "overflow-hidden"]}
        [:div
         {:class ["flex" "items-center" "justify-between" "px-4" "py-3" "border-b" "border-base-300"]}
         [:h3 {:class ["text-white" "text-lg" "font-medium"]} "Indicators"]
         [:button
          {:type "button"
           :class ["h-7" "w-7" "rounded" "text-gray-300" "hover:text-white" "hover:bg-base-200"
                   "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-base-content/40"]
           :on {:click [[:actions/toggle-indicators-dropdown]]}
           :aria-label "Close indicators menu"}
          "×"]]
        [:label
         {:for "chart-indicators-search"
          :class ["flex" "items-center" "gap-2" "px-4" "py-3" "border-b" "border-base-300" "cursor-text"]}
         [:svg
          {:class ["h-4" "w-4" "text-gray-400"]
           :viewBox "0 0 20 20"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "2"
           :aria-hidden "true"}
          [:circle {:cx "8.5" :cy "8.5" :r "5.5"}]
          [:line {:x1 "12.5" :y1 "12.5" :x2 "17" :y2 "17"}]]
         [:input
         {:id "chart-indicators-search"
          :type "search"
          :value (or search-term "")
           :placeholder "Search"
           :class ["w-full" "border-0" "bg-transparent" "p-0" "text-lg" "text-gray-200"
                   "placeholder:text-gray-500" "focus:outline-none" "focus:ring-0"]
           :on {:input [[:actions/update-indicators-search [:event.target/value]]]}
           :aria-label "Search indicators"}]]
        [:div
         {:class ["px-4" "py-3" "border-b" "border-base-300"]}
         [:div
          {:class ["mb-2" "text-xs" "uppercase" "tracking-wide" "text-gray-500"]}
          "Chart Indicators"]
         (let [click-action (if volume-visible?
                              [:actions/hide-volume-indicator]
                              [:actions/show-volume-indicator])]
           [:button
            {:type "button"
             :class (into ["w-full" "px-3" "py-2.5" "text-left" "text-sm" "transition-colors"
                           "flex" "items-center" "justify-between" "gap-3" "rounded"
                           "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-base-content/40"]
                          (if volume-visible?
                            ["text-trading-green" "bg-base-200/50" "hover:bg-base-200/70"]
                            ["text-white" "bg-base-200/20" "hover:bg-base-200/45"]))
             :on {:click [click-action]}
             :aria-label (if volume-visible?
                           "Remove built-in volume indicator"
                           "Add built-in volume indicator")
             :aria-pressed (boolean volume-visible?)}
            [:span "Volume"]
            (when volume-visible?
              [:span {:class ["text-xs" "font-medium" "tracking-wide" "text-trading-green"]}
               "Added"])])]
        [:div
         {:class ["max-h-72" "overflow-y-auto"]}
         [:div
          {:class ["px-4" "pt-3" "pb-1" "text-xs" "uppercase" "tracking-wide" "text-gray-500"]}
          "Script Name"]
         (if (seq filtered-indicators)
           (for [indicator filtered-indicators]
             (let [indicator-id (:id indicator)
                   active? (contains? active-indicators indicator-id)
                   click-action (if active?
                                  [:actions/remove-indicator indicator-id]
                                  [:actions/add-indicator indicator-id (or (:default-config indicator) {})])]
               [:button
                {:key indicator-id
                 :type "button"
                 :class (into ["w-full" "px-4" "py-2.5" "text-left" "text-sm" "transition-colors"
                               "flex" "items-center" "justify-between" "gap-3"
                               "focus:outline-none" "focus-visible:ring-1" "focus-visible:ring-base-content/40"]
                              (if active?
                                ["text-trading-green" "bg-base-200/40" "hover:bg-base-200/60"]
                                ["text-white" "hover:bg-base-200/70"]))
                 :on {:click [click-action]}
                 :aria-label (if active?
                               (str "Remove " (:name indicator) " indicator")
                               (str "Add " (:name indicator) " indicator"))
                 :aria-pressed active?}
                [:span (:name indicator)]
                (when active?
                  [:span {:class ["text-xs" "font-medium" "text-trading-green" "tracking-wide"]}
                   "Added"])]))
           [:div
            {:class ["px-4" "py-6" "text-sm" "text-gray-400"]}
            "No indicators match your search."])]
        (when (seq active-indicators)
          [:div
           {:class ["border-t" "border-base-300" "px-4" "py-3"]}
           [:div
            {:class ["mb-1" "text-xs" "font-semibold" "uppercase" "tracking-wide" "text-gray-500"]}
            "Active Indicators"]
           [:div
            {:class ["mb-2" "text-xs" "text-gray-400"]}
            "Click an added indicator above to remove it quickly."]
           [:div
            {:class ["space-y-2"]}
            (for [[indicator-id config] (sort-by (comp name key) active-indicators)]
              (when-let [indicator-info (get indicator-by-id indicator-id)]
                [:div
                 {:key indicator-id
                  :class ["flex" "items-center" "justify-between" "gap-2" "rounded" "bg-base-200/70" "p-2"]}
                 [:div
                  {:class ["flex" "items-center" "gap-2"]}
                  [:span {:class ["text-sm" "text-white"]} (:short-name indicator-info)]
                  (when (:supports-period? indicator-info)
                    [:input
                     {:type "number"
                      :value (or (:period config) (:default-period indicator-info))
                      :min (:min-period indicator-info)
                      :max (:max-period indicator-info)
                      :class ["w-16" "rounded" "border" "border-base-300" "bg-base-100"
                              "px-2" "py-1" "text-sm" "text-white"]
                      :on {:change [[:actions/update-indicator-period indicator-id [:event.target/value]]]}
                      :aria-label (str "Set " (:short-name indicator-info) " period")}])]
                 [:button
                 {:type "button"
                  :class ["rounded" "px-2" "py-1" "text-xs" "text-red-300" "hover:text-red-200"
                           "hover:bg-red-500/10" "focus:outline-none" "focus-visible:ring-1"
                           "focus-visible:ring-red-300/60"]
                   :on {:click [[:actions/remove-indicator indicator-id]]}}
                  "Remove"]]))]])]))])
