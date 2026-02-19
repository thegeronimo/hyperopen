(ns hyperopen.views.trading-chart.chart-type-dropdown)

(def bars-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:line {:x1 "6" :y1 "7" :x2 "6" :y2 "25" :stroke "currentColor" :stroke-width "1.5"}]
   [:line {:x1 "3" :y1 "11" :x2 "6" :y2 "11" :stroke "currentColor" :stroke-width "1.5"}]
   [:line {:x1 "6" :y1 "19" :x2 "9" :y2 "19" :stroke "currentColor" :stroke-width "1.5"}]
   [:line {:x1 "14" :y1 "5" :x2 "14" :y2 "23" :stroke "currentColor" :stroke-width "1.5"}]
   [:line {:x1 "11" :y1 "9" :x2 "14" :y2 "9" :stroke "currentColor" :stroke-width "1.5"}]
   [:line {:x1 "14" :y1 "17" :x2 "17" :y2 "17" :stroke "currentColor" :stroke-width "1.5"}]
   [:line {:x1 "22" :y1 "9" :x2 "22" :y2 "27" :stroke "currentColor" :stroke-width "1.5"}]
   [:line {:x1 "22" :y1 "13" :x2 "25" :y2 "13" :stroke "currentColor" :stroke-width "1.5"}]])

(def candles-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:line {:x1 "8" :y1 "6" :x2 "8" :y2 "26" :stroke "currentColor" :stroke-width "1.5"}]
   [:rect {:x "6" :y "10" :width "4" :height "10" :stroke "currentColor" :stroke-width "1.5" :fill "currentColor"}]
   [:line {:x1 "18" :y1 "5" :x2 "18" :y2 "25" :stroke "currentColor" :stroke-width "1.5"}]
   [:rect {:x "16" :y "8" :width "4" :height "10" :stroke "currentColor" :stroke-width "1.5" :fill "none"}]])

(def hollow-candles-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:line {:x1 "8" :y1 "6" :x2 "8" :y2 "26" :stroke "currentColor" :stroke-width "1.5"}]
   [:rect {:x "6" :y "10" :width "4" :height "10" :stroke "currentColor" :stroke-width "1.5" :fill "none"}]
   [:line {:x1 "18" :y1 "5" :x2 "18" :y2 "25" :stroke "currentColor" :stroke-width "1.5"}]
   [:rect {:x "16" :y "8" :width "4" :height "10" :stroke "currentColor" :stroke-width "1.5" :fill "none"}]
   [:line {:x1 "24" :y1 "8" :x2 "28" :y2 "12" :stroke "currentColor" :stroke-width "1.5"}]])

(def line-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:path {:d "M4 22 L10 18 L14 20 L20 12 L26 14 L28 10"
           :stroke "currentColor"
           :stroke-width "2"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(def line-with-markers-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:path {:d "M4 22 L10 18 L14 20 L20 12 L26 14 L28 10"
           :stroke "currentColor"
           :stroke-width "2"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]
   [:circle {:cx "4" :cy "22" :r "1.6" :fill "currentColor"}]
   [:circle {:cx "10" :cy "18" :r "1.6" :fill "currentColor"}]
   [:circle {:cx "14" :cy "20" :r "1.6" :fill "currentColor"}]
   [:circle {:cx "20" :cy "12" :r "1.6" :fill "currentColor"}]
   [:circle {:cx "26" :cy "14" :r "1.6" :fill "currentColor"}]
   [:circle {:cx "28" :cy "10" :r "1.6" :fill "currentColor"}]])

(def step-line-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:path {:d "M4 22 H10 V16 H16 V12 H22 V9 H28"
           :stroke "currentColor"
           :stroke-width "2"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(def area-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
   [:path {:d "M4 28 L4 21 L10 17 L14 19 L20 12 L26 14 L28 11 L28 28 Z" :opacity "0.45"}]
   [:path {:d "M4 21 L10 17 L14 19 L20 12 L26 14 L28 11"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "2"}]])

(def hlc-area-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
   [:path {:d "M4 28 L4 22 L10 19 L14 21 L20 16 L26 18 L28 15 L28 28 Z" :opacity "0.35"}]
   [:path {:d "M4 22 L10 19 L14 21 L20 16 L26 18 L28 15"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "2"}]
   [:path {:d "M4 20 L10 15 L14 16 L20 9 L26 11 L28 8"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "1.5"
           :opacity "0.75"}]])

(def baseline-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
   [:line {:x1 "4" :y1 "20" :x2 "28" :y2 "20" :stroke "currentColor" :stroke-width "2" :opacity "0.55"}]
   [:path {:d "M4 20 L10 16 L14 18 L20 11 L26 13 L28 9"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "2"}]
   [:path {:d "M4 20 L10 16 L14 18 L20 11 L26 13 L28 9 L28 20 L4 20 Z" :opacity "0.25"}]])

(def columns-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "currentColor" :style {:vertical-align "text-bottom"}}
   [:rect {:x "4" :y "21" :width "3" :height "7"}]
   [:rect {:x "8" :y "17" :width "3" :height "11"}]
   [:rect {:x "12" :y "19" :width "3" :height "9"}]
   [:rect {:x "16" :y "14" :width "3" :height "14"}]
   [:rect {:x "20" :y "11" :width "3" :height "17"}]
   [:rect {:x "24" :y "8" :width "3" :height "20"}]])

(def high-low-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:line {:x1 "8" :y1 "6" :x2 "8" :y2 "26" :stroke "currentColor" :stroke-width "1.7"}]
   [:line {:x1 "8" :y1 "18" :x2 "12" :y2 "18" :stroke "currentColor" :stroke-width "1.7"}]
   [:line {:x1 "16" :y1 "8" :x2 "16" :y2 "24" :stroke "currentColor" :stroke-width "1.7"}]
   [:line {:x1 "16" :y1 "14" :x2 "20" :y2 "14" :stroke "currentColor" :stroke-width "1.7"}]
   [:line {:x1 "24" :y1 "5" :x2 "24" :y2 "27" :stroke "currentColor" :stroke-width "1.7"}]
   [:line {:x1 "24" :y1 "20" :x2 "28" :y2 "20" :stroke "currentColor" :stroke-width "1.7"}]])

(def heikin-ashi-icon
  [:svg.w-4.h-4.inline-block {:viewBox "0 0 32 32" :fill "none" :style {:vertical-align "text-bottom"}}
   [:line {:x1 "8" :y1 "6" :x2 "8" :y2 "26" :stroke "currentColor" :stroke-width "1.5"}]
   [:rect {:x "6" :y "11" :width "4" :height "9" :stroke "currentColor" :stroke-width "1.5" :fill "none"}]
   [:line {:x1 "18" :y1 "6" :x2 "18" :y2 "26" :stroke "currentColor" :stroke-width "1.5"}]
   [:rect {:x "16" :y "8" :width "4" :height "10" :stroke "currentColor" :stroke-width "1.5" :fill "currentColor"}]
   [:path {:d "M23 20 L28 14" :stroke "currentColor" :stroke-width "1.5"}]])

(def chart-type-sections
  [{:id :ohlc
    :items [{:key :bar :label "Bars" :icon bars-icon}
            {:key :candlestick :label "Candles" :icon candles-icon}
            {:key :hollow-candles :label "Hollow candles" :icon hollow-candles-icon}]}
   {:id :line
    :items [{:key :line :label "Line" :icon line-icon}
            {:key :line-with-markers :label "Line with markers" :icon line-with-markers-icon}
            {:key :step-line :label "Step line" :icon step-line-icon}]}
   {:id :area
    :items [{:key :area :label "Area" :icon area-icon}
            {:key :hlc-area :label "HLC area" :icon hlc-area-icon}
            {:key :baseline :label "Baseline" :icon baseline-icon}]}
   {:id :columns
    :items [{:key :columns :label "Columns" :icon columns-icon}
            {:key :high-low :label "High-low" :icon high-low-icon}]}
   {:id :heikin-ashi
    :items [{:key :heikin-ashi :label "Heikin Ashi" :icon heikin-ashi-icon}]}])

(def supported-chart-types
  (vec (mapcat :items chart-type-sections)))

(def supported-chart-type-by-key
  (into {} (map (juxt :key identity)) supported-chart-types))

(defn- normalize-chart-type
  [chart-type]
  (if (= chart-type :histogram) :columns chart-type))

(defn- resolve-selected-type
  [selected-chart-type]
  (or (get supported-chart-type-by-key (normalize-chart-type selected-chart-type))
      (get supported-chart-type-by-key :candlestick)))

(defn- option-row
  [selected-chart-type chart-type]
  [:button.block.w-full.text-left.px-3.py-2.text-sm.hover:bg-gray-700.hover:text-white
   {:key (:key chart-type)
    :class (if (= selected-chart-type (:key chart-type))
             ["text-trading-green" "bg-gray-700"]
             ["text-gray-300"])
    :on {:click [[:actions/select-chart-type (:key chart-type)]]}}
   [:span.mr-2 (:icon chart-type)]
   (:label chart-type)])

(defn chart-type-dropdown
  [{:keys [selected-chart-type chart-type-dropdown-visible]}]
  (let [normalized-chart-type (normalize-chart-type selected-chart-type)
        selected-type (resolve-selected-type normalized-chart-type)]
    [:div.relative
     [:button
      {:class ["flex" "items-center" "justify-center"
               "h-6" "w-6" "p-0"
               "text-gray-300" "hover:text-white" "hover:bg-gray-700"
               "rounded" "transition-colors"
               "[&_svg]:w-[1.1rem]" "[&_svg]:h-[1.1rem]"]
       :aria-label "Chart type"
       :on {:click [[:actions/toggle-chart-type-dropdown]]}}
      [:span.inline-flex.items-center.justify-center.leading-none
       (:icon selected-type)]
      [:span.sr-only (:label selected-type)]]
     [:div
      {:class (into ["absolute" "top-full" "left-0" "mt-1"
                     "bg-base-100" "border" "border-base-300" "rounded" "shadow-lg"
                     "z-[120]" "isolate" "min-w-52" "overflow-hidden"]
                    (if chart-type-dropdown-visible
                      ["opacity-100" "scale-y-100" "translate-y-0"]
                      ["opacity-0" "scale-y-95" "-translate-y-2" "pointer-events-none"]))
       :style {:transition "all 50ms ease-in-out"}}
      [:div.px-3.py-2.text-xs.text-gray-400.uppercase.font-semibold.border-b.border-base-300 "Chart types"]
      (for [[section-idx section] (map-indexed vector chart-type-sections)]
        [:div {:key (:id section)}
         (when (pos? section-idx)
           [:div {:class ["border-t" "border-base-300"]
                  :key (str "divider-" section-idx)}])
         (for [chart-type (:items section)]
           (option-row normalized-chart-type chart-type))])]]))
