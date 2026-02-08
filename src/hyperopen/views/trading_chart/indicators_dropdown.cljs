(ns hyperopen.views.trading-chart.indicators-dropdown
  (:require [hyperopen.views.trading-chart.utils.indicators :as indicators]))

(defn indicators-dropdown
  "Dropdown component for selecting and managing indicators"
  [{:keys [indicators-dropdown-visible active-indicators]}]
  [:div.relative
   (when indicators-dropdown-visible
     [:div
      {:class ["absolute" "left-0" "top-full" "mt-1" "w-80"
               "bg-base-100" "opacity-100" "border" "border-base-300"
               "rounded-lg" "shadow-lg" "z-[120]" "isolate" "p-4"]}
      [:div.text-white.font-medium.mb-3 "Add Indicators"]
      
      ;; Available indicators list
      [:div.space-y-2.mb-4
       (for [indicator (indicators/get-available-indicators)]
         [:div.flex.items-center.justify-between.p-2.hover:bg-gray-700.rounded
          {:key (:id indicator)}
          [:div.flex.flex-col
           [:span.text-white.text-sm (:name indicator)]
           [:span.text-gray-400.text-xs (:description indicator)]]
          [:button.px-3.py-1.bg-blue-600.hover:bg-blue-700.text-white.text-sm.rounded.transition-colors
           {:on {:click [[:actions/add-indicator (:id indicator) {:period (:default-period indicator)}]]}}
           "Add"]])]
      
      ;; Active indicators management
      (when (seq active-indicators)
        [:div
         [:div.text-white.font-medium.mb-2.border-t.border-base-300.pt-3 "Active Indicators"]
         [:div.space-y-2
          (for [[indicator-id config] active-indicators]
            (let [indicator-info (first (filter #(= (:id %) indicator-id) (indicators/get-available-indicators)))]
              [:div.flex.items-center.justify-between.p-2.bg-gray-700.rounded
               {:key indicator-id}
               [:div.flex.items-center.space-x-2
                [:span.text-white.text-sm (:short-name indicator-info)]
                [:input.w-16.px-2.py-1.bg-gray-600.text-white.text-sm.rounded.border.border-gray-500
                 {:type "number"
                  :value (:period config)
                  :min (:min-period indicator-info)
                  :max (:max-period indicator-info)
                  :on {:change [[:actions/update-indicator-period indicator-id [:event.target/value]]]}}]]
               [:button.px-2.py-1.bg-red-600.hover:bg-red-700.text-white.text-xs.rounded.transition-colors
                {:on {:click [[:actions/remove-indicator indicator-id]]}}
                "Remove"]]))]])])]) 
