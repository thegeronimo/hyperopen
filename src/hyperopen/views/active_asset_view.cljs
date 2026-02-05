(ns hyperopen.views.active-asset-view
  (:require [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.views.asset-selector-view :as asset-selector]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.asset-selector.markets :as markets]))

;; Pure presentation components

(defn get-available-assets [state]
  "Get list of available markets for the asset selector."
  (get-in state [:asset-selector :markets] []))



(defn tooltip [content & [position]]
  (let [pos (or position "top")]
    [:div.relative.group
     [:div (first content)]
     [:div {:class (into ["absolute" "opacity-0" "group-hover:opacity-100" "transition-opacity" "duration-200" "pointer-events-none" "z-50"]
                         (case pos
                           "top" ["bottom-full" "left-1/2" "transform" "-translate-x-1/2" "mb-2"]
                           "bottom" ["top-full" "left-1/2" "transform" "-translate-x-1/2" "mt-2"]
                           "left" ["right-full" "top-1/2" "transform" "-translate-y-1/2" "mr-2"]
                           "right" ["left-full" "top-1/2" "transform" "-translate-y-1/2" "ml-2"]))
             :style {:min-width "max-content"}}
      [:div.bg-gray-800.text-white.text-xs.rounded.py-1.px-2.whitespace-nowrap
       (second content)
       [:div {:class (into ["absolute" "w-0" "h-0" "border-4" "border-transparent"]
                           (case pos
                             "top" ["top-full" "border-t-gray-800"]
                             "bottom" ["bottom-full" "border-b-gray-800"]
                             "left" ["left-full" "border-l-gray-800"]
                             "right" ["right-full" "border-r-gray-800"]))}]]]]))

(defn change-indicator [change-value change-pct]
  (let [is-positive (and change-value (>= change-value 0))
        color-class (if is-positive "text-success" "text-error")]
    [:span {:class color-class} 
     (str (fmt/format-number change-value 2) " / " (fmt/format-percentage change-pct))]))

(defn asset-icon [market dropdown-visible?]
  (let [coin (:coin market)
        base (or (:base market) coin)
        symbol (or (:symbol market) coin)
        dex (:dex market)
        market-type (:market-type market)]
    [:div.flex.items-center.space-x-2.cursor-pointer.hover:bg-base-300.rounded.px-2.py-1.transition-colors
     {:on {:click [[:actions/toggle-asset-dropdown :asset-selector]]}}
     [:img.w-6.h-6.rounded-full {:src (str "https://app.hyperliquid.xyz/coins/" base ".svg") :alt base}]
     [:div.flex.items-center.space-x-2
      [:span.font-medium symbol]
      (when (= market-type :spot)
        [:span {:class ["px-1.5" "py-0.5" "text-[10px]" "font-medium" "rounded" "bg-base-300" "text-gray-200"]}
         "SPOT"])
      (when dex
        [:span {:class ["px-1.5" "py-0.5" "text-[10px]" "font-medium" "rounded"
                        "bg-emerald-500/20" "text-emerald-300" "border" "border-emerald-500/30"]}
         dex])]
     [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"
                                                        :class (when dropdown-visible? "rotate-180")}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]))

(defn asset-selector-trigger [dropdown-visible?]
  [:button.flex.items-center.space-x-2.cursor-pointer.hover:bg-base-300.rounded.px-2.py-1.transition-colors
   {:type "button"
    :on {:click [[:actions/toggle-asset-dropdown :asset-selector]]}}
   [:div.w-6.h-6.rounded-full.bg-base-300.flex.items-center.justify-center
    [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "m21 21-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}]]]
   [:span.font-medium "Select Asset"]
   [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"
                                                      :class (when dropdown-visible? "rotate-180")}
    [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]])

(defn data-column [label value & [options]]
  (let [underlined? (:underlined options)
        value-component (if (:change? options)
                          (change-indicator (:change-value options) (:change-pct options))
                          [:span.font-medium value])]
    [:div.text-center
     [:div {:class (into ["text-[11px]" "text-gray-400" "mb-1"]
                         (when underlined? ["border-b" "border-dashed" "border-gray-600"]))}
      label]
     [:div {:class ["text-[13px]"]} value-component]]))

(defn active-asset-row [ctx-data market dropdown-state full-state]
  (let [coin (or (:coin market) (:coin ctx-data))
        mark (or (:mark ctx-data) (:mark market))
        oracle (:oracle ctx-data)
        change-24h (or (:change24h ctx-data) (:change24h market))
        change-24h-pct (or (:change24hPct ctx-data) (:change24hPct market))
        volume-24h (or (:volume24h ctx-data) (:volume24h market))
        open-interest-raw (:openInterest ctx-data)
        open-interest-usd (if (= :spot (:market-type market))
                            nil
                            (or (when (and open-interest-raw mark)
                                  (fmt/calculate-open-interest-usd open-interest-raw mark))
                                (:openInterest market)))
        funding-rate (:fundingRate ctx-data)
        dropdown-visible? (= (:visible-dropdown dropdown-state) :asset-selector)
        is-spot (= :spot (:market-type market))
        ;; Handle missing data gracefully
        has-perp-data? (and mark oracle change-24h volume-24h open-interest-usd funding-rate)
        has-spot-data? (and mark change-24h volume-24h)]
    [:div {:class ["relative"
                   "grid"
                   "grid-cols-7"
                   "gap-2"
                   "md:gap-3"
                   "items-center"
                   "px-0"
                   "py-2"
                   "md:grid-cols-[1.4fr_0.9fr_0.9fr_1.1fr_1.1fr_1.2fr_1.6fr]"]}
      ;; Asset/Pair column
      [:div.flex.justify-start
       (asset-icon (or market {:coin coin}) dropdown-visible?)]
      
      ;; Mark column
      [:div.flex.justify-center
       (data-column "Mark" (if mark (fmt/format-currency mark) "Loading...") {:underlined true})]
      
      ;; Oracle column
      [:div.flex.justify-center
       (data-column "Oracle"
                    (if (and (not is-spot) oracle)
                      (fmt/format-currency oracle)
                      (if is-spot "—" "Loading..."))
                    {:underlined true})]
      
      ;; 24h Change column
      [:div.flex.justify-center
       (data-column "24h Change" 
                    (if (or has-perp-data? has-spot-data?) nil "Loading...")
                    {:change? (or has-perp-data? has-spot-data?)
                     :change-value change-24h
                     :change-pct change-24h-pct})]
      
      ;; 24h Volume column
      [:div.flex.justify-center
       (data-column "24h Volume" (if volume-24h (fmt/format-large-currency volume-24h) "Loading..."))]
      
      ;; Open Interest column 
      [:div.flex.justify-center 
       (data-column "Open Interest"
                    (cond
                      is-spot "—"
                      open-interest-usd (fmt/format-large-currency open-interest-usd)
                      :else "Loading...")
                    {:underlined true})]
      
      ;; Funding / Countdown column
      [:div.flex.justify-center
       [:div.text-center
       [:div {:class ["text-[11px]" "text-gray-400" "mb-1"]} "Funding / Countdown"]
        [:div {:class ["text-[13px]" "flex" "items-center" "justify-center"]}
         (if (and (not is-spot) has-perp-data?)
           (tooltip 
             [[:span.text-success.cursor-help (fmt/format-percentage funding-rate 4)]
              (str "Annualized: " (fmt/format-percentage (fmt/annualized-funding-rate funding-rate) 2))])
           [:span (if is-spot "—" "Loading...")])
         [:span.mx-1 "/"]
         [:span (if is-spot "—" (fmt/format-funding-countdown))]]]]]))

(defn select-asset-row [dropdown-state]
  (let [dropdown-visible? (= (:visible-dropdown dropdown-state) :asset-selector)]
    [:div {:class ["relative"
                   "grid"
                   "grid-cols-7"
                   "gap-2"
                   "md:gap-3"
                   "items-center"
                   "px-0"
                   "py-2"
                   "md:grid-cols-[1.4fr_0.9fr_0.9fr_1.1fr_1.1fr_1.2fr_1.6fr]"]}
     [:div.flex.justify-start
      (asset-selector-trigger dropdown-visible?)]

     [:div.flex.justify-center
      (data-column "Mark" "—" {:underlined true})]

     [:div.flex.justify-center
      (data-column "Oracle" "—" {:underlined true})]

     [:div.flex.justify-center
      (data-column "24h Change" "—")]

     [:div.flex.justify-center
      (data-column "24h Volume" "—")]

     [:div.flex.justify-center 
      (data-column "Open Interest" "—" {:underlined true})]

     [:div.flex.justify-center
      [:div.text-center
       [:div {:class ["text-[11px]" "text-gray-400" "mb-1"]} "Funding / Countdown"]
       [:div {:class ["text-[13px]" "text-gray-400"]} "— / —"]]]]))

(defn active-asset-list [contexts dropdown-state full-state]
  (let [active-asset (:active-asset full-state)
        ctx-data (when active-asset (get contexts active-asset))
        market-by-key (get-in full-state [:asset-selector :market-by-key])
        active-market (or (:active-market full-state)
                          (when active-asset
                            (get market-by-key (markets/coin->market-key active-asset))))]
    [:div.space-y-2
     (when active-asset
       ^{:key active-asset}
       (active-asset-row (or ctx-data {:coin active-asset}) active-market dropdown-state full-state))]))

(defn empty-state []
  [:div.flex.flex-col.items-center.justify-center.p-8.text-center
   [:div.text-gray-400.mb-4
    [:svg.w-12.h-12.mx-auto {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
     [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"}]]]
   [:h3.text-lg.font-medium.text-gray-300 "No active assets"]
   [:p.text-sm.text-gray-500 "Subscribe to assets to see their trading data"]])

(defn loading-state []
  [:div.flex.items-center.justify-center.p-8
   [:div.animate-spin.rounded-full.h-8.w-8.border-b-2.border-primary]])

(defn active-asset-panel [contexts loading? dropdown-state full-state]
  (let [active-market (:active-market full-state)
        active-asset (:active-asset full-state)
        selected-key (or (:key active-market)
                         (when active-asset (markets/coin->market-key active-asset)))]
    [:div {:class ["relative" "bg-base-200" "border-b" "border-base-300" "rounded-none" "shadow-none"]}
     [:div
      (if (:active-asset full-state)
        (active-asset-list contexts dropdown-state full-state)
        (select-asset-row dropdown-state))]
     ;; Asset Selector Dropdown positioned at panel level
     (when (:visible-dropdown dropdown-state)
       (asset-selector/asset-selector-wrapper
         {:visible? true
          :markets (get-available-assets full-state)
          :selected-market-key selected-key
          :search-term (:search-term dropdown-state "")
          :sort-by (:sort-by dropdown-state :volume)
          :sort-direction (:sort-direction dropdown-state :asc)
          :favorites (:favorites dropdown-state #{})
          :favorites-only? (:favorites-only? dropdown-state false)
          :strict? (:strict? dropdown-state false)
          :active-tab (:active-tab dropdown-state :all)}))]))

;; Main component that takes state and renders the UI
(defn active-asset-view [state]
  (let [active-assets (:active-assets state)
        contexts (:contexts active-assets)
        loading? (:loading active-assets)
        dropdown-state (get-in state [:asset-selector] {:visible-dropdown nil
                                                         :search-term ""
                                                         :sort-by :volume
                                                         :sort-direction :desc
                                                         :favorites #{}
                                                         :favorites-only? false
                                                         :strict? false
                                                         :active-tab :all})]
    (active-asset-panel contexts loading? dropdown-state state))) 
