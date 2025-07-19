(ns hyperopen.views.active-asset-view
  (:require [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.views.asset-selector-view :as asset-selector]))

;; Pure presentation components

(defn get-available-assets [state]
  "Get list of available assets from asset contexts"
  (let [asset-contexts (:asset-contexts state)]
    (->> (keys asset-contexts)
         (map (fn [coin-key]
                (let [coin (name coin-key)
                      context-data (get asset-contexts coin-key)]
                  (when context-data
                    (let [funding (:funding context-data)
                          mark-px (js/parseFloat (:markPx funding))
                          prev-day-px (js/parseFloat (:prevDayPx funding))
                          day-ntl-vlm (js/parseFloat (:dayNtlVlm funding))
                          open-interest (js/parseFloat (:openInterest funding))
                          funding-rate (js/parseFloat (:funding funding))
                          ;; Calculate 24h change
                          change-24h (when (and mark-px prev-day-px)
                                      (- mark-px prev-day-px))
                          change-24h-pct (when (and change-24h prev-day-px (not= prev-day-px 0))
                                          (* 100 (/ change-24h prev-day-px)))
                          ;; Calculate open interest in USD
                          open-interest-usd (when (and open-interest mark-px)
                                             (* open-interest mark-px))]
                      {:coin coin
                       :mark mark-px
                       :volume24h day-ntl-vlm
                       :change24h change-24h
                       :change24hPct change-24h-pct
                       :openInterest open-interest-usd
                       :fundingRate funding-rate
                       :funding funding
                       :info (:info context-data)})))))
         (filter #(not (nil? %))))))

(defn format-number [n decimals]
  (when (and n (number? n))
    (.toFixed n decimals)))

(def usd-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:style           "currency"
        :currency        "USD"
        :minimumFractionDigits 2
        :maximumFractionDigits 2}))

(def large-number-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:style           "currency"
        :currency        "USD"
        :minimumFractionDigits 0
        :maximumFractionDigits 0}))

(defn format-currency [amount]
  (when amount
    (.format usd-formatter amount)))

(defn format-large-currency [amount]
  (when amount
    (.format large-number-formatter amount)))

(defn format-percentage [value & [decimals]]
  (when value
    (str (format-number value (or decimals 2)) "%")))

(defn format-time [seconds]
  (when seconds
    (let [hours (js/Math.floor (/ seconds 3600))
          minutes (js/Math.floor (/ (mod seconds 3600) 60))
          secs (mod seconds 60)]
      (str (.padStart (str hours) 2 "0") ":"
           (.padStart (str minutes) 2 "0") ":"
           (.padStart (str secs) 2 "0")))))

(defn format-funding-countdown []
  (let [now (js/Date.)
        current-minutes (.getMinutes now)
        current-seconds (.getSeconds now)
        minutes-until-hour (- 60 current-minutes)
        seconds-until-minute (- 60 current-seconds)
        total-minutes (if (zero? seconds-until-minute) 
                       minutes-until-hour 
                       (dec minutes-until-hour))
        total-seconds (if (zero? seconds-until-minute) 
                       0 
                       seconds-until-minute)]
    (str "00:" (.padStart (str total-minutes) 2 "0") ":"
         (.padStart (str total-seconds) 2 "0"))))

(defn annualized-funding-rate [hourly-rate]
  (when hourly-rate
    (* hourly-rate 24 365)))

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
     (str (format-number change-value 2) " / " (format-percentage change-pct))]))

(defn asset-icon [coin dropdown-visible?]
  [:div.flex.items-center.space-x-2.cursor-pointer.hover:bg-base-300.rounded.px-2.py-1.transition-colors
   {:on {:click [[:actions/toggle-asset-dropdown coin]]}}
   [:img.w-6.h-6.rounded-full {:src (str "https://app.hyperliquid.xyz/coins/" coin ".svg") :alt coin}]
   [:span.font-medium coin]
   [:svg.w-4.h-4.text-gray-400.transition-transform {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"
                                                      :class (when dropdown-visible? "rotate-180")}
    [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]])

(defn data-column [label value & [options]]
  (let [underlined? (:underlined options)
        value-component (if (:change? options)
                         (change-indicator (:change-value options) (:change-pct options))
                         [:span.font-medium value])]
    [:div.text-center
     [:div.text-xs.text-gray-400.mb-1 {:class (when underlined? ["border-b" "border-dashed" "border-gray-600"])} label]
     [:div.text-sm value-component]]))

(defn active-asset-row [ctx-data dropdown-state full-state]
  (let [coin (:coin ctx-data)
        mark (:mark ctx-data)
        oracle (:oracle ctx-data)
        change-24h (:change24h ctx-data)
        change-24h-pct (:change24hPct ctx-data)
        volume-24h (:volume24h ctx-data)
        open-interest (:openInterest ctx-data)
        open-interest-usd (format-currency (* open-interest mark))
        funding-rate (:fundingRate ctx-data)
        dropdown-visible? (= (:visible-dropdown dropdown-state) coin)]
    [:div.relative.flex.items-center.justify-between.px-4.py-2.bg-base-200.rounded-lg.border.border-base-300
     [:div.flex.items-center.space-x-4.w-full
      ;; Asset/Pair column
      [:div.flex-shrink-0.relative.w-20
       (asset-icon coin dropdown-visible?)
       ;; Asset Selector Dropdown
       (when dropdown-visible?
         (asset-selector/asset-selector-dropdown
           {:visible? dropdown-visible?
            :assets (get-available-assets full-state)
            :selected-asset coin
            :search-term (:search-term dropdown-state "")
            :sort-by (:sort-by dropdown-state :name)
            :sort-direction (:sort-direction dropdown-state :asc)}))]
      
      ;; Mark column
      [:div.flex-shrink-0.w-24
       (data-column "Mark" (format-currency mark) {:underlined true})]
      
      ;; Oracle column
      [:div.flex-shrink-0.w-24
       (data-column "Oracle" (format-currency oracle) {:underlined true})]
      
      ;; 24h Change column
      [:div.flex-shrink-0.w-32
       (data-column "24h Change" 
                    nil
                    {:change? true
                     :change-value change-24h
                     :change-pct change-24h-pct})]
      
      ;; 24h Volume column
      [:div.flex-shrink-0.w-28
       (data-column "24h Volume" (format-large-currency volume-24h))]
      
      ;; Open Interest column
      [:div.flex-shrink-0.w-32
       (data-column "Open Interest" (format-large-currency open-interest) {:underlined true})]
      
      ;; Funding / Countdown column
      [:div.flex-shrink-0.w-36
       [:div.text-center
        [:div.text-xs.text-gray-400.mb-1 "Funding / Countdown"]
        [:div.text-sm.flex.items-center.justify-center
         (tooltip 
           [[:span.text-success.cursor-help (format-percentage funding-rate 4)]
            (str "Annualized: " (format-percentage (annualized-funding-rate funding-rate) 2))])
         [:span.mx-1 "/"]
         [:span (format-funding-countdown)]]]]]]))

(defn active-asset-list [contexts dropdown-state full-state]
  [:div.space-y-2
   (for [[coin ctx-data] contexts]
     ^{:key coin}
     (active-asset-row ctx-data dropdown-state full-state))])

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
  [:div.bg-base-100.rounded-lg.shadow-lg
   [:div.p-4.border-b.border-base-300
    [:h2.text-lg.font-semibold "Active Assets"]
    [:p.text-sm.text-gray-500 "Real-time trading data"]]
   [:div.p-4
    (cond
      loading? (loading-state)
      (empty? contexts) (empty-state)
      :else (active-asset-list contexts dropdown-state full-state))]])

;; Main component that takes state and renders the UI
(defn active-asset-view [state]
  (let [active-assets (:active-assets state)
        contexts (:contexts active-assets)
        loading? (:loading active-assets)
        dropdown-state (get-in state [:asset-selector] {:visible-dropdown nil})]
    (active-asset-panel contexts loading? dropdown-state state))) 
