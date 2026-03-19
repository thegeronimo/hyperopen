(ns hyperopen.views.ui.performance-metrics-tooltip)

(def ^:private tooltip-shell-base-classes
  ["pointer-events-none"
   "absolute"
   "left-0"
   "top-full"
   "z-50"
   "mt-2"
   "opacity-0"
   "transition-opacity"
   "duration-100"])

(def ^:private tooltip-panel-base-classes
  ["relative"
   "rounded-lg"
   "border"
   "border-base-300"
   "bg-gray-800"
   "px-3"
   "py-2.5"
   "text-left"
   "spectate-lg"
   "whitespace-normal"])

(def ^:private tooltip-heading-classes
  ["text-xs" "font-medium" "uppercase" "tracking-[0.18em]" "text-[#8ea1b3]"])

(def ^:private tooltip-body-classes
  ["mt-1.5" "text-xs" "leading-5" "text-gray-100"])

(def ^:private tooltip-arrow-classes
  ["absolute"
   "bottom-full"
   "left-5"
   "h-0"
   "w-0"
   "border-4"
   "border-transparent"
   "border-b-gray-800"])

(defn- tooltip-shell
  [data-role {:keys [size-classes visibility-classes panel-classes]} & children]
  (let [panel-node (into [:div {:class (vec (concat tooltip-panel-base-classes
                                                     panel-classes))
                                :role "tooltip"}]
                         (concat children
                                 [[:div {:class tooltip-arrow-classes}]]))]
    [:div {:class (vec (concat tooltip-shell-base-classes
                               size-classes
                               visibility-classes))
           :data-role (when data-role
                        (str data-role "-tooltip"))}
     panel-node]))

(defn estimated-banner-tooltip
  [reasons data-role reason-title-fn]
  (tooltip-shell data-role
                 {:size-classes ["max-w-[min(420px,calc(100vw-2rem))]"]
                  :visibility-classes ["group-hover:opacity-100"
                                       "group-focus-within:opacity-100"]
                  :panel-classes ["text-xs" "leading-5" "text-gray-100"]}
                 [:div {:class tooltip-heading-classes}
                  "Estimation Method"]
                 [:div {:class tooltip-body-classes}
                  "Estimated rows stay visible when the selected range does not meet the usual reliability gates."]
                 (when (seq reasons)
                   [:ul {:class ["mt-2" "space-y-1" "pl-4" "list-disc" "text-[#c7d4da]"]}
                    (for [reason reasons]
                      ^{:key (str data-role "-reason-" (name reason))}
                      [:li (reason-title-fn reason)])])))

(defn metric-label-tooltip
  [label description data-role]
  (when (seq description)
    (tooltip-shell data-role
                   {:size-classes ["w-[min(420px,calc(100vw-2rem))]"
                                   "min-w-[280px]"]
                    :visibility-classes ["group-hover:opacity-100"]}
                   [:div {:class tooltip-heading-classes}
                    label]
                   [:div {:class tooltip-body-classes}
                    description])))
