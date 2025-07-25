(ns hyperopen.views.trading-chart.timeframe-dropdown
  (:require [replicant.core :as r]))

(defn timeframe-dropdown [{:keys [selected-timeframe timeframes-dropdown-visible]}]
  [:div.relative
   [:button.flex.items-center.space-x-1.px-3.py-1.text-sm.font-medium.text-gray-300.hover:text-white.hover:bg-gray-700.rounded.transition-colors
    {:on {:click [[:actions/toggle-timeframes-dropdown]]}}
    [:span.inline-block.transition-transform.duration-200.ease-in-out
     {:class (if timeframes-dropdown-visible "rotate-180" "rotate-0")}
     "▼"]]
   ;; Dropdown menu (toggled with state)
   [:div.absolute.top-full.left-0.mt-1.bg-gray-800.border.border-gray-600.rounded.shadow-lg.z-50.min-w-32.overflow-hidden
    {:class (if timeframes-dropdown-visible 
              "opacity-100 scale-y-100 translate-y-0" 
              "opacity-0 scale-y-95 -translate-y-2 pointer-events-none")
     :style {:transition "all 50ms ease-in-out"}}
    ;; Minutes section
    [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-gray-600 "Minutes"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :1m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :1m]]}}
     "1 min"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :3m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :3m]]}}
     "3 min"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :5m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :5m]]}}
     "5 min"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :15m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :15m]]}}
     "15 min"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :30m) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :30m]]}}
     "30 min"]
    ;; Hours section
    [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-gray-600.border-t "Hours"]
    [:button.relative.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :1h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :1h]]}}
     "1 hour"
     [:span.absolute.right-2.top-2.text-yellow-400.text-xs "⭐"]]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :2h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :2h]]}}
     "2 hours"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :4h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :4h]]}}
     "4 hours"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :8h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :8h]]}}
     "8 hours"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :12h) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :12h]]}}
     "12 hours"]
    ;; Days section
    [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-gray-600.border-t "Days"]
    [:button.relative.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :1d) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :1d]]}}
     "1 day"
     [:span.absolute.right-2.top-2.text-yellow-400.text-xs "⭐"]]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :3d) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :3d]]}}
     "3 days"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :1w) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :1w]]}}
     "1 week"]
    [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
     {:class (if (= selected-timeframe :1M) ["text-blue-400" "bg-gray-700"] ["text-gray-300"])
      :on {:click [[:actions/select-chart-timeframe :1M]]}}
     "1 month"]]]) 