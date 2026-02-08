(ns hyperopen.views.trading-chart.chart-type-dropdown
  (:require [replicant.core :as r]))

;; Supported chart types based on the image
(def supported-chart-types
  [{:key :area :label "Area" 
    :icon [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
           [:path {:d "M4,28 L4,20 L8,16 L12,18 L16,12 L20,14 L24,8 L28,10 L28,28 Z" :fill "currentColor" :opacity "0.6"}]
           [:path {:d "M4,20 L8,16 L12,18 L16,12 L20,14 L24,8 L28,10" :fill "none" :stroke "currentColor" :stroke-width "2"}]]}
   {:key :bar :label "Bar" 
    :icon [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
           ;; First OHLC bar
           [:line {:x1 "6" :y1 "12" :x2 "6" :y2 "24" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "4" :y1 "14" :x2 "6" :y2 "14" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "6" :y1 "20" :x2 "8" :y2 "20" :stroke "currentColor" :stroke-width "1"}]
           ;; Second OHLC bar
           [:line {:x1 "12" :y1 "8" :x2 "12" :y2 "22" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "10" :y1 "10" :x2 "12" :y2 "10" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "12" :y1 "18" :x2 "14" :y2 "18" :stroke "currentColor" :stroke-width "1"}]
           ;; Third OHLC bar
           [:line {:x1 "18" :y1 "14" :x2 "18" :y2 "26" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "16" :y1 "16" :x2 "18" :y2 "16" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "18" :y1 "22" :x2 "20" :y2 "22" :stroke "currentColor" :stroke-width "1"}]
           ;; Fourth OHLC bar
           [:line {:x1 "24" :y1 "10" :x2 "24" :y2 "20" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "22" :y1 "12" :x2 "24" :y2 "12" :stroke "currentColor" :stroke-width "1"}]
           [:line {:x1 "24" :y1 "16" :x2 "26" :y2 "16" :stroke "currentColor" :stroke-width "1"}]]}
   {:key :baseline :label "Baseline" 
    :icon [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
           [:line {:x1 "4" :y1 "20" :x2 "28" :y2 "20" :stroke "currentColor" :stroke-width "2" :opacity "0.5"}]
           [:path {:d "M4,20 L8,16 L12,18 L16,12 L20,14 L24,8 L28,10" :fill "none" :stroke "currentColor" :stroke-width "2"}]
           [:path {:d "M4,20 L8,16 L12,18 L16,12 L20,14 L24,8 L28,10 L28,20 L4,20 Z" :fill "currentColor" :opacity "0.3"}]]}
   {:key :candlestick :label "Candlestick" 
    :icon [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
           [:defs
            [:style ".cls-1 { fill: none; }"]]
           [:path {:d "M26,10H24V6H22v4H20V22h2v4h2V22h2ZM24,20H22V12h2Z"}]
           [:path {:d "M14,8H12V4H10V8H8V18h2v4h2V18h2Zm-2,8H10V10h2Z"}]
           [:path {:d "M30,30H4a2,2,0,0,1-2-2V2H4V28H30Z"}]
           [:rect.cls-1 {:height "32" :width "32"}]]}
   {:key :histogram :label "Histogram" 
    :icon [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
           [:rect {:x "4" :y "22" :width "3" :height "6" :fill "currentColor"}]
           [:rect {:x "7" :y "18" :width "3" :height "10" :fill "currentColor"}]
           [:rect {:x "10" :y "20" :width "3" :height "8" :fill "currentColor"}]
           [:rect {:x "13" :y "16" :width "3" :height "12" :fill "currentColor"}]
           [:rect {:x "16" :y "14" :width "3" :height "14" :fill "currentColor"}]
           [:rect {:x "19" :y "12" :width "3" :height "16" :fill "currentColor"}]
           [:rect {:x "22" :y "10" :width "3" :height "18" :fill "currentColor"}]
           [:rect {:x "25" :y "8" :width "3" :height "20" :fill "currentColor"}]]}
   {:key :line :label "Line" 
    :icon [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
           [:path {:d "M4,20 L8,16 L12,18 L16,12 L20,14 L24,8 L28,10" :fill "none" :stroke "currentColor" :stroke-width "2"}]
           [:circle {:cx "4" :cy "20" :r "2" :fill "currentColor"}]
           [:circle {:cx "8" :cy "16" :r "2" :fill "currentColor"}]
           [:circle {:cx "12" :cy "18" :r "2" :fill "currentColor"}]
           [:circle {:cx "16" :cy "12" :r "2" :fill "currentColor"}]
           [:circle {:cx "20" :cy "14" :r "2" :fill "currentColor"}]
           [:circle {:cx "24" :cy "8" :r "2" :fill "currentColor"}]
           [:circle {:cx "28" :cy "10" :r "2" :fill "currentColor"}]]}])

(defn chart-type-dropdown [{:keys [selected-chart-type chart-type-dropdown-visible]}]
  (let [selected-type (first (filter #(= (:key %) selected-chart-type) supported-chart-types))]
    [:div.relative
     [:button.flex.items-center.space-x-1.px-3.py-1.text-sm.font-medium.text-gray-300.hover:text-white.hover:bg-gray-700.rounded.transition-colors
      {:on {:click [[:actions/toggle-chart-type-dropdown]]}}
      (:icon selected-type)
      [:span (:label selected-type)]
      [:span.inline-block.transition-transform.duration-200.ease-in-out
       {:class (if chart-type-dropdown-visible "rotate-180" "rotate-0")}
       "▼"]]
     ;; Dropdown menu (toggled with state)
     [:div
      {:class (into ["absolute" "top-full" "left-0" "mt-1"
                     "bg-base-100" "border" "border-base-300" "rounded" "shadow-lg"
                     "z-[120]" "isolate" "min-w-40" "overflow-hidden"]
                    (if chart-type-dropdown-visible
                      ["opacity-100" "scale-y-100" "translate-y-0"]
                      ["opacity-0" "scale-y-95" "-translate-y-2" "pointer-events-none"]))
       :style {:transition "all 50ms ease-in-out"}}
      ;; Header
      [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-base-300 "Supported types"]
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
