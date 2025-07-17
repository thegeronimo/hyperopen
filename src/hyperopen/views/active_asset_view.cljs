(ns hyperopen.views.active-asset-view
  (:require [hyperopen.websocket.active-asset-ctx :as active-ctx]))

;; Pure presentation components

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

(defn format-currency [amount]
  (when amount
    (.format usd-formatter amount)))

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

(defn change-indicator [change-value change-pct]
  (let [is-positive (and change-value (>= change-value 0))
        color-class (if is-positive "text-success" "text-error")]
    [:span {:class color-class} 
     (str (format-number change-value 2) " / " (format-percentage change-pct))]))

(defn asset-icon [coin]
  [:div.flex.items-center.space-x-2
   [:img.w-6.h-6.rounded-full {:src (str "https://app.hyperliquid.xyz/coins/" coin ".svg") :alt coin}]
   [:span.font-medium coin]
   [:svg.w-4.h-4.text-gray-400 {:fill "none" :stroke "currentColor" :viewBox "0 0 24 24"}
    [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]])

(defn data-column [label value & [options]]
  (let [underlined? (:underlined options)
        value-component (if (:change? options)
                         (change-indicator (:change-value options) (:change-pct options))
                         [:span value])]
    [:div.flex.flex-col.space-y-1
     [:span.text-xs.text-gray-400 {:class (when underlined? "border-b border-dashed border-gray-600")} label]
     [:div.text-sm.font-medium value-component]]))

(defn active-asset-row [ctx-data]
  (let [coin (:coin ctx-data)
        mark (:mark ctx-data)
        oracle (:oracle ctx-data)
        change-24h (:change24h ctx-data)
        change-24h-pct (:change24hPct ctx-data)
        volume-24h (:volume24h ctx-data)
        open-interest (:openInterest ctx-data)
        open-interest-usd (format-currency (* open-interest mark))
        funding-rate (:fundingRate ctx-data)]
    [:div.flex.items-center.justify-between.p-4.bg-base-200.rounded-lg.border.border-base-300
     [:div.flex-1.flex.items-center.space-x-6
      ;; Asset/Pair column
      [:div.flex-0
       (asset-icon coin)]
      
      ;; Mark column
      [:div.flex-0
       (data-column "Mark" (format-currency mark) {:underlined true})]
      
      ;; Oracle column
      [:div.flex-0
       (data-column "Oracle" (format-currency oracle) {:underlined true})]
      
      ;; 24h Change column
      [:div.flex-3
       (data-column "24h Change" 
                    nil
                    {:change? true
                     :change-value change-24h
                     :change-pct change-24h-pct})]
      
      ;; 24h Volume column
      [:div.flex-0
       (data-column "24h Volume" (format-currency volume-24h))]
      
      ;; Open Interest column
      [:div.flex-0
       (data-column "Open Interest" open-interest-usd {:underlined true})]
      
      ;; Funding / Countdown column
      [:div.flex-1
       [:div.flex.flex-col.space-y-1
        [:span.text-xs.text-gray-400 "Funding / Countdown"]
        [:div.text-sm.font-medium
         [:span.text-success (format-percentage funding-rate 4)]
         [:span " / "]
         [:span (format-funding-countdown)]]]]]]))

(defn active-asset-list [contexts]
  [:div.space-y-2
   (for [[coin ctx-data] contexts]
     ^{:key coin}
     (active-asset-row ctx-data))])

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

(defn active-asset-panel [contexts loading?]
  [:div.bg-base-100.rounded-lg.shadow-lg
   [:div.p-4.border-b.border-base-300
    [:h2.text-lg.font-semibold "Active Assets"]
    [:p.text-sm.text-gray-500 "Real-time trading data"]]
   [:div.p-4
    (cond
      loading? (loading-state)
      (empty? contexts) (empty-state)
      :else (active-asset-list contexts))]])

;; Main component that takes state and renders the UI
(defn active-asset-view [state]
  (let [contexts (:contexts state)
        loading? (:loading state)]
    (active-asset-panel contexts loading?))) 
