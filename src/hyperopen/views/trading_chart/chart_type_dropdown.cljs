(ns hyperopen.views.trading-chart.chart-type-dropdown
  (:require [replicant.core :as r]))

;; Supported chart types based on the image
(def supported-chart-types
  [{:key :area :label "Area" :icon "📊"}
   {:key :bar :label "Bar" :icon "📊"}
   {:key :baseline :label "Baseline" :icon "📊"}
   {:key :candlestick :label "Candlestick" :icon "📈"}
   {:key :histogram :label "Histogram" :icon "📊"}
   {:key :line :label "Line" :icon "📈"}])

(defn chart-type-dropdown [{:keys [selected-chart-type chart-type-dropdown-visible]}]
  (let [selected-type (first (filter #(= (:key %) selected-chart-type) supported-chart-types))]
    [:div.relative
     [:button.flex.items-center.space-x-1.px-3.py-1.text-sm.font-medium.text-gray-300.hover:text-white.hover:bg-gray-700.rounded.transition-colors
      {:on {:click [[:actions/toggle-chart-type-dropdown]]}}
      [:span (:icon selected-type)]
      [:span (:label selected-type)]
      [:span.inline-block.transition-transform.duration-200.ease-in-out
       {:class (if chart-type-dropdown-visible "rotate-180" "rotate-0")}
       "▼"]]
     ;; Dropdown menu (toggled with state)
     [:div.absolute.top-full.left-0.mt-1.bg-gray-800.border.border-gray-600.rounded.shadow-lg.z-50.min-w-40.overflow-hidden
      {:class (if chart-type-dropdown-visible 
                "opacity-100 scale-y-100 translate-y-0" 
                "opacity-0 scale-y-95 -translate-y-2 pointer-events-none")
       :style {:transition "all 50ms ease-in-out"}}
      ;; Header
      [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-gray-600 "Supported types"]
      ;; Chart type options
      (for [chart-type supported-chart-types]
        [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
         {:key (:key chart-type)
          :class (if (= selected-chart-type (:key chart-type)) 
                  ["text-blue-400" "bg-gray-700"] 
                  ["text-gray-300"])
          :on {:click [[:actions/select-chart-type (:key chart-type)]]}}
         [:span.mr-2 (:icon chart-type)]
         (:label chart-type)])]])) 