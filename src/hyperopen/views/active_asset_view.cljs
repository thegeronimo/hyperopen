(ns hyperopen.views.active-asset-view
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.websocket.active-asset-ctx :as active-ctx]
            [hyperopen.views.asset-icon :as asset-icon]
            [hyperopen.views.asset-selector-view :as asset-selector]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.asset-selector.markets :as markets]))

;; Pure presentation components

(defn get-available-assets [state]
  "Get list of available markets for the asset selector."
  (get-in state [:asset-selector :markets] []))



(defn tooltip [content & [position]]
  (let [pos (or position "top")
        tooltip-body (second content)
        body-node (if (string? tooltip-body)
                    [:div {:class ["rounded-md"
                                   "bg-gray-800"
                                   "px-2"
                                   "py-1"
                                   "text-xs"
                                   "text-white"
                                   "whitespace-nowrap"]}
                     tooltip-body]
                    tooltip-body)]
    [:div {:class ["relative" "group" "inline-flex"]}
     [:div (first content)]
     [:div {:class (into ["absolute" "opacity-0" "group-hover:opacity-100" "transition-opacity" "duration-200" "pointer-events-none" "z-50"]
                         (case pos
                           "top" ["bottom-full" "left-1/2" "transform" "-translate-x-1/2" "mb-2"]
                           "bottom" ["top-full" "left-1/2" "transform" "-translate-x-1/2" "mt-2"]
                           "left" ["right-full" "top-1/2" "transform" "-translate-y-1/2" "mr-2"]
                           "right" ["left-full" "top-1/2" "transform" "-translate-y-1/2" "ml-2"]))
             :style {:min-width "max-content"
                     :max-width "22rem"}}
      body-node]]))

(defn change-indicator [change-value change-pct & [change-raw]]
  (let [is-positive (and change-value (>= change-value 0))
        color-class (if is-positive "text-success" "text-error")]
    [:span {:class [color-class "num"]}
     (str (or (fmt/format-trade-price-delta change-value change-raw) "--")
          " / "
          (or (fmt/format-percentage change-pct) "--"))]))

(def active-asset-grid-template
  "md:grid-cols-[minmax(max-content,1.4fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,1.1fr)_minmax(0,1.1fr)_minmax(0,1.2fr)_minmax(0,1.6fr)]")

(def ^:private active-asset-chip-classes
  ["px-1.5"
   "py-0.5"
   "text-xs"
   "font-medium"
   "rounded"
   "border"
   "bg-emerald-500/20"
   "text-emerald-300"
   "border-emerald-500/30"])

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- parse-optional-number [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat value)
              :else js/NaN)]
    (when (and (number? num) (not (js/isNaN num)))
      num)))

(defn- coin-prefix [coin]
  (let [coin* (non-blank-text coin)]
    (when (and coin* (str/includes? coin* ":"))
      (let [[prefix _suffix] (str/split coin* #":" 2)]
        (non-blank-text prefix)))))

(defn- market-dex-label [market]
  (or (non-blank-text (:dex market))
      (coin-prefix (:coin market))))

(defn- leverage-chip-label [market]
  (when-let [max-leverage (parse-optional-number (:maxLeverage market))]
    (when (pos? max-leverage)
      (let [whole-number? (= max-leverage (js/Math.floor max-leverage))
            leverage-text (if whole-number?
                            (str (js/Math.floor max-leverage))
                            (fmt/safe-to-fixed max-leverage 1))]
        (str leverage-text "x")))))

(defn- base-symbol-segment [value]
  (let [text (some-> value non-blank-text (str/replace #"^.*:" ""))]
    (some-> text
            (str/split #"/|-" 2)
            first
            non-blank-text)))

(defn- direction-from-size [size]
  (cond
    (and (number? size) (pos? size)) :long
    (and (number? size) (neg? size)) :short
    :else :flat))

(defn- direction-label [direction]
  (case direction
    :long "Long"
    :short "Short"
    "Flat"))

(defn- unsigned-size-text [raw-size parsed-size]
  (let [size-text (non-blank-text raw-size)]
    (cond
      (and size-text
           (or (str/starts-with? size-text "-")
               (str/starts-with? size-text "+")))
      (subs size-text 1)

      size-text
      size-text

      (number? parsed-size)
      (fmt/safe-to-fixed (js/Math.abs parsed-size) 4)

      :else
      "0")))

(defn- normalized-position-value [position mark]
  (let [value (parse-optional-number (:positionValue position))
        size (parse-optional-number (:szi position))]
    (cond
      (number? value)
      (js/Math.abs value)

      (and (number? size)
           (number? mark))
      (js/Math.abs (* size mark))

      :else
      nil)))

(defn- display-base-symbol [market coin]
  (or (non-blank-text (:base market))
      (base-symbol-segment (:symbol market))
      (base-symbol-segment coin)
      "ASSET"))

(defn- funding-countdown-mm-ss [countdown-text]
  (let [text (non-blank-text countdown-text)]
    (if (and text (re-matches #"\d{2}:\d{2}:\d{2}" text))
      (subs text 3)
      (or text "--"))))

(defn- signed-percentage-text [value decimals]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 1e-8) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign (fmt/format-percentage (js/Math.abs normalized) decimals)))
    "—"))

(defn- signed-usd-text [value]
  (if (number? value)
    (let [normalized (if (< (js/Math.abs value) 0.005) 0 value)
          sign (cond
                 (pos? normalized) "+"
                 (neg? normalized) "-"
                 :else "")]
      (str sign "$" (fmt/format-fixed-number (js/Math.abs normalized) 2)))
    "—"))

(defn- signed-tone-class [value]
  (cond
    (and (number? value) (pos? value)) "text-success"
    (and (number? value) (neg? value)) "text-error"
    :else "text-gray-100"))

(defn- funding-payment-estimate [direction position-value rate]
  (when (and (number? position-value)
             (number? rate)
             (not= direction :flat))
    (* position-value
       (/ rate 100)
       (case direction
         :long -1
         :short 1
         0))))

(defn- funding-tooltip-model [position market coin mark funding-rate countdown-text]
  (let [size-raw (:szi position)
        size (parse-optional-number size-raw)
        direction (direction-from-size size)
        position-value (normalized-position-value position mark)
        base-symbol (display-base-symbol market coin)
        countdown-mm-ss (funding-countdown-mm-ss countdown-text)
        current-rate funding-rate
        next-24h-rate (when (number? funding-rate)
                        (* funding-rate 24))
        annual-rate (fmt/annualized-funding-rate funding-rate)]
    {:position-size-label (if (and (not= direction :flat)
                                   (number? size))
                            (str (direction-label direction)
                                 " "
                                 (unsigned-size-text size-raw size)
                                 " "
                                 base-symbol)
                            "No open position")
     :position-value position-value
     :projection-rows [{:id "current"
                        :label (str "Current in " countdown-mm-ss)
                        :rate current-rate
                        :payment (funding-payment-estimate direction position-value current-rate)}
                       {:id "next-24h"
                        :label "Next 24h *"
                        :rate next-24h-rate
                        :payment (funding-payment-estimate direction position-value next-24h-rate)}
                       {:id "apy"
                        :label "APY *"
                        :rate annual-rate
                        :payment (funding-payment-estimate direction position-value annual-rate)}]}))

(defn- funding-tooltip-panel [{:keys [position-size-label position-value projection-rows]}]
  [:div {:class ["w-[18rem]"
                 "rounded-lg"
                 "border"
                 "border-slate-700/80"
                 "bg-slate-800/95"
                 "px-3"
                 "py-2.5"
                 "text-[12px]"
                 "text-gray-100"
                 "shadow-xl"
                 "backdrop-blur-sm"]}
   [:div {:class ["mb-2.5"]}
    [:h4 {:class ["mb-1"
                  "text-[0.95rem]"
                  "font-semibold"
                  "text-gray-100"]}
     "Position"]
    [:div {:class ["grid"
                   "grid-cols-[auto_1fr]"
                   "gap-x-3"
                   "gap-y-0.5"
                   "text-[0.9rem]"
                   "leading-5"]}
     [:span {:class ["text-gray-300"]} "Size"]
     [:span {:class ["text-right" "num" "text-emerald-300"]}
      position-size-label]
     [:span {:class ["text-gray-300"]} "Value"]
     [:span {:class ["text-right" "num"]}
      (if (number? position-value)
        (str "$" (fmt/format-fixed-number position-value 2))
        "—")]]]
   [:div {:class ["mb-2"
                  "h-px"
                  "w-full"
                  "bg-slate-600/70"]}]
   [:div {:class ["mb-2.5"]}
    [:h4 {:class ["mb-1"
                  "text-[0.95rem]"
                  "font-semibold"
                  "text-gray-100"]}
     "Projections"]
    [:div {:class ["grid"
                   "grid-cols-[1fr_auto_auto]"
                   "gap-x-3"
                   "gap-y-0.5"
                   "text-[0.9rem]"
                   "leading-5"]}
     [:span]
     [:span {:class ["text-right" "text-gray-300"]} "Rate"]
     [:span {:class ["text-right" "text-gray-300"]} "Payment"]
     (for [{:keys [id label rate payment]} projection-rows]
       ^{:key id}
       [:div {:class ["contents"]}
        [:span {:class ["text-gray-100"]} label]
        [:span {:class ["text-right" "num" (signed-tone-class rate)]}
         (signed-percentage-text rate 4)]
        [:span {:class ["text-right" "num" (signed-tone-class payment)]}
         (signed-usd-text payment)]])]]
   [:p {:class ["italic" "text-[0.85rem]" "text-gray-300"]}
    "* Assume current position and funding rate"]])

(defn- symbol-monogram [market symbol coin]
  (let [base-symbol (or (non-blank-text (:base market))
                        (base-symbol-segment symbol)
                        (base-symbol-segment coin)
                        "ASSET")
        upper-symbol (str/upper-case base-symbol)]
    (subs upper-symbol 0 (min 5 (count upper-symbol)))))

(defn- resolve-active-market [full-state active-asset]
  (let [projected-market (:active-market full-state)
        market-by-key (get-in full-state [:asset-selector :market-by-key] {})]
    (cond
      (and (map? projected-market)
           (= (:coin projected-market) active-asset))
      projected-market

      (string? active-asset)
      (markets/resolve-market-by-coin market-by-key active-asset)

      :else
      nil)))

(defn asset-icon [market dropdown-visible? missing-icons loaded-icons]
  (let [coin (:coin market)
        symbol (or (:symbol market) coin)
        dex-label (market-dex-label market)
        leverage-label (leverage-chip-label market)
        market-type (:market-type market)
        market-key (or (:key market) (markets/coin->market-key coin))
        missing-icon? (contains? missing-icons market-key)
        loaded-icon? (contains? loaded-icons market-key)
        icon-src (when-not missing-icon?
                   (asset-icon/market-icon-url market))
        icon-layer? (seq icon-src)
        probe-icon? (and icon-layer? (not loaded-icon?))
        monogram (symbol-monogram market symbol coin)]
    [:div {:class ["flex" "items-center" "gap-2" "cursor-pointer" "hover:bg-base-300"
                   "rounded" "pr-2" "py-1" "transition-colors" "min-w-0"]
           :on {:click [[:actions/toggle-asset-dropdown :asset-selector]]}}
     [:div {:class ["relative" "w-6" "h-6" "shrink-0"]}
      [:div {:class ["w-6" "h-6" "rounded-full" "border" "border-slate-500/40"
                     "bg-slate-800/80" "text-slate-300/70" "text-[8px]"
                     "font-semibold" "tracking-tight" "uppercase" "leading-none"
                     "flex" "items-center" "justify-center" "overflow-hidden"
                     "px-0.5"]
             :aria-hidden true}
       monogram]
      (when icon-layer?
        [:div {:class ["absolute" "inset-0" "rounded-full" "bg-center" "bg-cover" "bg-no-repeat"]
               :aria-hidden true
               :style {:background-image (str "url('" icon-src "')")}}])
      (when probe-icon?
        [:img {:class ["absolute" "inset-0" "w-6" "h-6" "rounded-full" "opacity-0"
                       "pointer-events-none"]
               :src icon-src
               :alt ""
               :on {:load [[:actions/mark-loaded-asset-icon market-key]]
                    :error [[:actions/mark-missing-asset-icon market-key]]}}])]
     [:div.flex.items-center.space-x-2.min-w-0
      [:span.font-medium.truncate symbol]
      (when (= market-type :spot)
        [:span {:class active-asset-chip-classes}
         "SPOT"])
      (when dex-label
        [:span {:class active-asset-chip-classes}
         dex-label])
      (when leverage-label
        [:span {:class active-asset-chip-classes}
         leverage-label])]
     [:svg {:fill "none"
            :stroke "currentColor"
            :viewBox "0 0 24 24"
            :class (into ["w-4" "h-4" "text-gray-400" "transition-transform" "shrink-0"]
                         (when dropdown-visible? ["rotate-180"]))}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width 2 :d "M19 9l-7 7-7-7"}]]]))

(defn asset-selector-trigger [dropdown-visible?]
  [:button {:class ["flex" "items-center" "space-x-2" "cursor-pointer" "hover:bg-base-300"
                    "rounded" "pr-2" "py-1" "transition-colors"]
            :type "button"
            :on {:click [[:actions/toggle-asset-dropdown :asset-selector]]
                 :keydown [[:actions/handle-asset-selector-shortcut
                            [:event/key]
                            [:event/metaKey]
                            [:event/ctrlKey]
                            []]]}}
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
                          (change-indicator (:change-value options)
                                            (:change-pct options)
                                            (:change-raw options))
                          [:span {:class (into ["font-medium"]
                                               (when (:numeric? options) ["num"]))}
                           value])]
    [:div.text-center
     [:div {:class (into ["text-xs" "text-gray-400" "mb-1"]
                         (when underlined? ["border-b" "border-dashed" "border-gray-600"]))}
      label]
     [:div {:class (into ["text-xs"]
                         (when (:numeric? options) ["num"]))}
      value-component]]))

(defn active-asset-row [ctx-data market dropdown-state full-state]
  (let [coin (or (:coin market) (:coin ctx-data))
        icon-market (-> (or market {})
                        (assoc :coin (or (:coin market) coin))
                        (assoc :symbol (or (:symbol market) coin)))
        mark (or (:mark ctx-data) (:mark market))
        mark-raw (or (:markRaw ctx-data) (:markRaw market))
        oracle (:oracle ctx-data)
        oracle-raw (:oracleRaw ctx-data)
        change-24h (or (:change24h ctx-data) (:change24h market))
        change-24h-pct (or (:change24hPct ctx-data) (:change24hPct market))
        volume-24h (or (:volume24h ctx-data) (:volume24h market))
        open-interest-raw (:openInterest ctx-data)
        open-interest-usd (if (= :spot (:market-type market))
                            nil
                            (or (when (and open-interest-raw mark)
                                  (fmt/calculate-open-interest-usd open-interest-raw mark))
                                (:openInterest market)))
        funding-rate (parse-optional-number (:fundingRate ctx-data))
        countdown-text (fmt/format-funding-countdown)
        active-position (trading-state/position-for-active-asset full-state)
        funding-tooltip (funding-tooltip-model (or active-position {})
                                               market
                                               coin
                                               mark
                                               funding-rate
                                               countdown-text)
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
                   active-asset-grid-template]}
      ;; Asset/Pair column
      [:div {:class ["flex" "justify-start" "app-shell-gutter-left" "min-w-fit"]}
       (asset-icon icon-market
                   dropdown-visible?
                   (get-in full-state [:asset-selector :missing-icons] #{})
                   (get-in full-state [:asset-selector :loaded-icons] #{}))]
      
      ;; Mark column
      [:div.flex.justify-center
       (data-column "Mark"
                    (if mark
                      (fmt/format-trade-price mark mark-raw)
                      "Loading...")
                    {:underlined true
                     :numeric? true})]
      
      ;; Oracle column
      [:div.flex.justify-center
       (data-column "Oracle"
                    (if (and (not is-spot) oracle)
                      (fmt/format-trade-price oracle oracle-raw)
                      (if is-spot "—" "Loading..."))
                    {:underlined true
                     :numeric? true})]
      
      ;; 24h Change column
      [:div.flex.justify-center
       (data-column "24h Change" 
                    (if (or has-perp-data? has-spot-data?) nil "Loading...")
                    {:change? (or has-perp-data? has-spot-data?)
                     :change-value change-24h
                     :change-pct change-24h-pct
                     :change-raw nil
                     :numeric? true})]
      
      ;; 24h Volume column
      [:div.flex.justify-center
       (data-column "24h Volume"
                    (if volume-24h (fmt/format-large-currency volume-24h) "Loading...")
                    {:numeric? true})]
      
      ;; Open Interest column 
      [:div.flex.justify-center 
       (data-column "Open Interest"
                    (cond
                      is-spot "—"
                      open-interest-usd (fmt/format-large-currency open-interest-usd)
                      :else "Loading...")
                    {:underlined true
                     :numeric? true})]
      
      ;; Funding / Countdown column
     [:div.flex.justify-center
      [:div.text-center
        [:div {:class ["text-xs" "text-gray-400" "mb-1"]} "Funding / Countdown"]
        [:div {:class ["text-xs" "flex" "items-center" "justify-center"]}
         (if (and (not is-spot) has-perp-data?)
           (tooltip 
             [[:span {:class ["cursor-help" "num" (signed-tone-class funding-rate)]}
               (signed-percentage-text funding-rate 4)]
              (funding-tooltip-panel funding-tooltip)]
             "bottom")
           [:span (if is-spot "—" "Loading...")])
         [:span.mx-1 "/"]
         [:span.num (if is-spot "—" countdown-text)]]]]]))

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
                   active-asset-grid-template]}
     [:div {:class ["flex" "justify-start" "app-shell-gutter-left" "min-w-fit"]}
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
       [:div {:class ["text-xs" "text-gray-400" "mb-1"]} "Funding / Countdown"]
       [:div {:class ["text-xs" "text-gray-400"]} "— / —"]]]]))

(defn active-asset-list [contexts dropdown-state full-state]
  (let [active-asset (:active-asset full-state)
        ctx-data (when active-asset (get contexts active-asset))
        active-market (resolve-active-market full-state active-asset)]
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
  (let [active-asset (:active-asset full-state)
        active-market (resolve-active-market full-state active-asset)
        selected-key (or (:key active-market)
                         (when active-asset (markets/coin->market-key active-asset)))]
    [:div {:class ["relative" "bg-base-200" "border-b" "border-base-300" "rounded-none" "shadow-none"]
           :data-parity-id "market-strip"}
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
          :loading? (:loading? dropdown-state false)
          :phase (:phase dropdown-state :bootstrap)
          :search-term (:search-term dropdown-state "")
          :sort-by (:sort-by dropdown-state :volume)
          :sort-direction (:sort-direction dropdown-state :asc)
          :favorites (:favorites dropdown-state #{})
          :favorites-only? (:favorites-only? dropdown-state false)
          :missing-icons (:missing-icons dropdown-state #{})
          :loaded-icons (:loaded-icons dropdown-state #{})
          :highlighted-market-key (:highlighted-market-key dropdown-state nil)
          :scroll-top (:scroll-top dropdown-state 0)
          :render-limit (:render-limit dropdown-state 120)
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
                                                         :loading? false
                                                         :phase :bootstrap
                                                         :favorites #{}
                                                         :missing-icons #{}
                                                         :loaded-icons #{}
                                                         :highlighted-market-key nil
                                                         :scroll-top 0
                                                         :render-limit 120
                                                         :last-render-limit-increase-ms nil
                                                         :favorites-only? false
                                                         :strict? false
                                                         :active-tab :all})]
    (active-asset-panel contexts loading? dropdown-state state))) 
