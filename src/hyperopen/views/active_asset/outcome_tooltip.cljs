(ns hyperopen.views.active-asset.outcome-tooltip
  (:require ["lucide/dist/esm/icons/banknote.js" :default lucide-banknote-node]
            ["lucide/dist/esm/icons/crosshair.js" :default lucide-crosshair-node]
            ["lucide/dist/esm/icons/info.js" :default lucide-info-node]
            ["lucide/dist/esm/icons/shield.js" :default lucide-shield-node]
            [clojure.string :as str]))

(defn- lucide-node->hiccup
  [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn- icon
  [node role]
  (into [:svg {:class ["h-5" "w-5" "shrink-0" "text-[#2dd4bf]"]
               :viewBox "0 0 24 24"
               :fill "none"
               :stroke "currentColor"
               :stroke-width 1.9
               :stroke-linecap "round"
               :stroke-linejoin "round"
               :aria-hidden true
               :data-role role}]
        (map lucide-node->hiccup
             (array-seq node))))

(defn- detail-row
  ([icon-node icon-role label body]
   (detail-row icon-node icon-role label body nil))
  ([icon-node icon-role label body {:keys [center?]}]
   [:div {:class ["grid"
                 "grid-cols-[1.75rem_minmax(0,0.62fr)_minmax(0,1.78fr)]"
                 (if center? "items-center" "items-start")
                 "gap-4"
                 "px-6"
                 "py-4"]}
   [:div {:class ["flex" "justify-center" (when-not center? "pt-0.5")]}
    (icon icon-node icon-role)]
   [:div {:class ["text-sm" "font-medium" "leading-5" "text-slate-300"]}
    label]
   [:div {:class ["min-w-0"]}
    body]]))

(defn- same-copy?
  [left right]
  (= (some-> left str str/trim)
     (some-> right str str/trim)))

(defn outcome-tooltip-panel
  [{:keys [title
           summary
           settlement-label
           settlement-time-label
           yes-label
           no-label
           yes-payout-label
           no-payout-label
           footer-label]}]
  (when settlement-label
    (let [summary-copy (or summary
                           "This market resolves to YES or NO based on the following settlement condition at the specified time.")
          show-settlement-row? (not (same-copy? summary-copy settlement-label))]
      [:div {:class ["absolute"
                   "top-full"
                   "z-[240]"
                   "mt-3"
                   "left-0"
                   "rounded-lg"
                   "border"
                   "border-[#0f766e]/65"
                   "bg-[#07131a]/95"
                   "text-left"
                   "shadow-[0_0_24px_rgba(45,212,191,0.10),0_18px_70px_rgba(0,0,0,0.55)]"
                   "backdrop-blur"
                   "opacity-0"
                   "pointer-events-none"
                   "transition-opacity"
                   "duration-150"
                   "group-hover/outcome-name:opacity-100"
                   "group-hover/outcome-name:pointer-events-auto"
                   "group-focus-within/outcome-name:opacity-100"
                   "group-focus-within/outcome-name:pointer-events-auto"]
           :style {:width "min(44rem, calc(100vw - 2rem))"
                   :max-width "calc(100vw - 2rem)"}
           :role "tooltip"
           :data-role "outcome-market-tooltip"}
       [:div {:class ["absolute"
                   "-top-[10px]"
                   "left-[48%]"
                   "h-5"
                    "w-5"
                    "rotate-45"
                    "border-l"
                    "border-t"
                    "border-[#0f766e]/65"
                    "bg-[#07131a]"]
            :aria-hidden true}]
       [:div {:class ["relative"
                      "space-y-0"
                      "overflow-y-auto"
                      "scrollbar-thin"
                      "scrollbar-thumb-slate-600/80"
                      "scrollbar-track-transparent"]
              :style {:max-height "min(40rem, calc(100vh - 5rem))"}
              :data-role "outcome-tooltip-scroll-container"}
        [:div {:class ["flex" "items-start" "gap-4" "px-6" "pb-5" "pt-7"]}
         [:div {:class ["flex" "h-8" "w-8" "shrink-0" "items-center" "justify-center" "rounded-full" "text-[#2dd4bf]"]}
          (icon lucide-info-node "outcome-tooltip-info-icon")]
         [:div {:class ["min-w-0" "space-y-2"]}
          [:div {:class ["text-lg" "font-semibold" "leading-6" "text-slate-100"]}
           (or title "Outcome Details")]
          [:div {:class ["pr-2"
                         "text-sm"
                         "leading-6"
                         "text-slate-400"]
                 :data-role "outcome-tooltip-summary-scroll"}
           summary-copy]]]
        (when show-settlement-row?
          [:div {:class ["border-t" "border-slate-700/45"]}
           (detail-row
            lucide-crosshair-node
            "outcome-tooltip-settlement-icon"
            "Settlement Condition"
            [:div {:class ["space-y-1" "text-sm" "leading-5"]}
             [:div {:class ["whitespace-normal"
                            "break-words"
                            "font-semibold"
                            "text-slate-100"]
                    :data-role "outcome-tooltip-settlement-label"}
              settlement-label]
             [:div {:class ["text-slate-400"]}
              settlement-time-label]])])
        [:div {:class ["border-t" "border-slate-700/45"]}
         (detail-row
          lucide-banknote-node
          "outcome-tooltip-payout-icon"
          "Payout Rule"
          [:div {:class ["space-y-1" "text-sm" "leading-5"]}
           [:div {:class ["flex" "items-center" "gap-3"]}
            [:span {:class ["font-semibold" "text-[#2dd4bf]"]} (or yes-label "YES")]
            [:span {:class ["text-slate-500"]} "->"]
            [:span {:class ["font-semibold" "text-[#2dd4bf]"]} yes-payout-label]
            [:span {:class ["text-slate-400"]} "each"]]
           [:div {:class ["flex" "items-center" "gap-3"]}
            [:span {:class ["font-semibold" "text-[#fb7185]"]} (or no-label "NO")]
            [:span {:class ["text-slate-500"]} "->"]
            [:span {:class ["font-semibold" "text-[#fb7185]"]} no-payout-label]
            [:span {:class ["text-slate-400"]} "each"]]]
          {:center? true})]
        [:div {:class ["flex"
                     "items-center"
                     "gap-3"
                     "border-t"
                     "border-slate-700/45"
                     "px-6"
                     "py-4"
                     "text-sm"
                     "text-slate-400"]}
         (icon lucide-shield-node "outcome-tooltip-shield-icon")
         [:span footer-label]]]])))
