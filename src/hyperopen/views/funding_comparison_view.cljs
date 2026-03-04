(ns hyperopen.views.funding-comparison-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.funding-comparison.vm :as funding-vm]))

(defn- format-rate
  [value]
  (if (number? value)
    (let [abs-value (js/Math.abs value)
          prefix (if (neg? value) "-" "+")]
      (str prefix (.toFixed (* abs-value 100) 4) "%"))
    "--"))

(defn- format-open-interest
  [value]
  (if (number? value)
    (or (fmt/format-large-currency value) "$0")
    "--"))

(defn- tone-class
  [tone]
  (case tone
    :negative ["text-[#ff6b8a]"]
    :positive ["text-[#36e1d3]"]
    ["text-trading-text-secondary"]))

(defn- arb-tone-class
  [raw-diff]
  (cond
    (not (number? raw-diff)) ["text-trading-text-secondary"]
    (pos? raw-diff) ["text-[#36e1d3]"]
    (neg? raw-diff) ["text-[#ff6b8a]"]
    :else ["text-trading-text-secondary"]))

(defn- sort-direction-icon
  [direction]
  [:svg {:class (into ["h-3" "w-3" "shrink-0" "opacity-70" "transition-transform"]
                      (if (= :asc direction)
                        ["rotate-180"]
                        ["rotate-0"]))
         :viewBox "0 0 12 12"
         :aria-hidden true}
   [:path {:d "M3 4.5L6 7.5L9 4.5"
           :fill "none"
           :stroke "currentColor"
           :stroke-width "1.5"
           :stroke-linecap "round"
           :stroke-linejoin "round"}]])

(defn- sort-header
  [label column sort-state]
  (let [active? (= column (:column sort-state))
        direction (:direction sort-state)]
    [:button {:type "button"
              :class ["inline-flex"
                      "items-center"
                      "gap-1"
                      "font-normal"
                      "text-trading-text-secondary"
                      "hover:text-trading-text"]
              :on {:click [[:actions/set-funding-comparison-sort column]]}}
     [:span label]
     (when active?
       (sort-direction-icon direction))]))

(defn- favorite-button
  [{:keys [favorite? favorite-market-key]}]
  (if (seq favorite-market-key)
    [:button {:type "button"
              :class ["text-sm"
                      "leading-none"
                      "text-amber-300"
                      "hover:text-amber-200"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]
              :on {:click [[:actions/toggle-asset-favorite favorite-market-key]]}
              :aria-label "Toggle favorite"}
     (if favorite? "★" "☆")]
    [:span {:class ["text-sm" "leading-none" "text-base-300"]} "☆"]))

(defn- arb-cell
  [{:keys [value direction raw-diff]}]
  (if (number? value)
    [:span {:class (into ["num"] (arb-tone-class raw-diff))
            :title (or direction "")}
     (str (.toFixed (* value 100) 4) "%")]
    [:span {:class ["text-trading-text-secondary"]} "--"]))

(defn- funding-row
  [{:keys [coin
           hyperliquid
           binance
           bybit
           binance-hl-arb
           bybit-hl-arb
           open-interest]
    :as row}]
  [:tr {:class ["border-b"
                "border-base-300/50"
                "text-sm"
                "text-trading-text"
                "hover:bg-base-200/40"]
        :data-role "funding-comparison-row"}
   [:td {:class ["px-3" "py-2.5"]}
    [:div {:class ["flex" "items-center" "gap-2"]}
     (favorite-button row)
     [:a {:href (str "/trade/" coin)
          :class ["truncate"
                  "text-trading-text"
                  "hover:text-primary"]}
      coin]]]
   [:td {:class ["px-3" "py-2.5" "num"]}
    (format-open-interest open-interest)]
   [:td {:class (into ["px-3" "py-2.5" "num"]
                      (tone-class (:tone hyperliquid)))}
    (format-rate (:rate hyperliquid))]
   [:td {:class (into ["px-3" "py-2.5" "num"]
                      (tone-class (:tone binance)))}
    (format-rate (:rate binance))]
   [:td {:class ["px-3" "py-2.5" "num"]}
    (arb-cell binance-hl-arb)]
   [:td {:class (into ["px-3" "py-2.5" "num"]
                      (tone-class (:tone bybit)))}
    (format-rate (:rate bybit))]
   [:td {:class ["px-3" "py-2.5" "num"]}
    (arb-cell bybit-hl-arb)]])

(defn- mobile-row
  [{:keys [coin open-interest hyperliquid binance bybit binance-hl-arb bybit-hl-arb] :as row}]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "p-3"
                 "space-y-2"]
         :data-role "funding-comparison-mobile-row"}
   [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
    [:div {:class ["flex" "items-center" "gap-2"]}
     (favorite-button row)
     [:a {:href (str "/trade/" coin)
          :class ["text-sm" "font-medium" "text-trading-text"]}
      coin]]
    [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
     (format-open-interest open-interest)]]
   [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-1" "text-xs"]}
    [:div [:span {:class ["text-trading-text-secondary"]} "HL "]
     [:span {:class (into ["num"] (tone-class (:tone hyperliquid)))}
      (format-rate (:rate hyperliquid))]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Binance "]
     [:span {:class (into ["num"] (tone-class (:tone binance)))}
      (format-rate (:rate binance))]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Bybit "]
     [:span {:class (into ["num"] (tone-class (:tone bybit)))}
      (format-rate (:rate bybit))]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Bin-HL Arb "]
     [:span {:class (into ["num"] (arb-tone-class (:raw-diff binance-hl-arb)))
             :title (or (:direction binance-hl-arb) "")}
      (if-let [value (:value binance-hl-arb)]
        (str (.toFixed (* value 100) 4) "%")
        "--")]]
    [:div {:class ["col-span-2"]}
     [:span {:class ["text-trading-text-secondary"]} "Bybit-HL Arb "]
     [:span {:class (into ["num"] (arb-tone-class (:raw-diff bybit-hl-arb)))
             :title (or (:direction bybit-hl-arb) "")}
      (if-let [value (:value bybit-hl-arb)]
        (str (.toFixed (* value 100) 4) "%")
        "--")]]]])

(defn- timeframe-button
  [selected? {:keys [value label]}]
  [:button {:type "button"
            :class (into ["rounded-lg"
                          "border"
                          "px-2.5"
                          "py-1"
                          "text-xs"
                          "transition-colors"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"]
                         (if selected?
                           ["border-[#2f7f73]" "bg-[#123a36]" "text-[#97fce4]"]
                           ["border-base-300" "text-trading-text-secondary" "hover:bg-base-200"]))
            :on {:click [[:actions/set-funding-comparison-timeframe value]]}}
   label])

(defn funding-comparison-view
  [state]
  (let [{:keys [query
                timeframe
                timeframe-options
                sort
                loading?
                error
                rows
                loaded-at-ms]} (funding-vm/funding-comparison-vm state)
        summary-text (cond
                       loading? "Loading predicted funding rates..."
                       (seq error) (str "Error: " error)
                       :else (str (count rows) " coin"
                                  (when (not= 1 (count rows)) "s")
                                  " shown"))]
    [:div {:class ["flex"
                   "h-full"
                   "w-full"
                   "flex-col"
                   "gap-3"
                   "app-shell-gutter"
                   "pt-4"
                   "pb-16"]
           :data-parity-id "funding-comparison-root"}
     [:div {:class ["rounded-xl"
                    "border"
                    "border-base-300"
                    "bg-base-100"
                    "p-4"
                    "space-y-3"]}
      [:div {:class ["space-y-1"]}
       [:h1 {:class ["text-xl" "font-semibold" "text-white"]}
        "Funding Comparison"]
       [:p {:class ["text-sm" "text-trading-text-secondary"]}
        "Compare predicted funding rates across Hyperliquid, Binance, and Bybit."]]
      [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
       [:input {:id "funding-comparison-search"
                :type "text"
                :placeholder "Search by coin"
                :value query
                :class ["h-9"
                        "w-full"
                        "max-w-sm"
                        "rounded-xl"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "px-3"
                        "text-sm"
                        "text-trading-text"
                        "focus:outline-none"
                        "focus:ring-0"
                        "focus:ring-offset-0"]
                :on {:input [[:actions/set-funding-comparison-query [:event.target/value]]]}}]
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-1"]
              :data-role "funding-comparison-timeframes"}
        (for [option timeframe-options]
          ^{:key (:value option)}
          (timeframe-button (= timeframe (:value option)) option))]]
      [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-2" "text-xs"]}
       [:span {:class ["text-trading-text-secondary"]
               :data-role "funding-comparison-summary"}
        summary-text]
       (when (number? loaded-at-ms)
         [:span {:class ["num" "text-trading-text-secondary"]}
          (str "Updated " (or (fmt/format-local-time-hh-mm-ss loaded-at-ms) ""))])]]

     [:div {:class ["hidden" "md:block" "overflow-x-auto" "rounded-xl" "border" "border-base-300"]}
      [:table {:class ["min-w-full" "bg-base-100"]
               :data-role "funding-comparison-table"}
       [:thead {:class ["bg-base-100"]}
        [:tr {:class ["text-xs" "text-trading-text-secondary"]}
         [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Coin" :coin sort)]
         [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Hyperliquid OI" :open-interest sort)]
         [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Hyperliquid" :hyperliquid sort)]
         [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Binance" :binance sort)]
         [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Binance-HL Arb" :binance-hl-arb sort)]
         [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Bybit" :bybit sort)]
         [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Bybit-HL Arb" :bybit-hl-arb sort)]]]
       [:tbody
       (if (seq rows)
         (for [row rows]
           ^{:key (:coin row)}
           (funding-row row))
          [:tr {:data-role "funding-comparison-empty-row"}
           [:td {:col-span 7
                 :class ["px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
            (if loading?
              "Loading..."
              "No rows match the current filters.")]])]]]

     [:div {:class ["grid" "gap-2" "md:hidden"]}
      (if (seq rows)
        (for [row rows]
          ^{:key (:coin row)}
          (mobile-row row))
        [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100" "p-3" "text-sm" "text-trading-text-secondary"]}
         (if loading?
           "Loading..."
           "No rows match the current filters.")])]

     (when (seq error)
       [:div {:class ["rounded-xl" "border" "border-[#7a2836]" "bg-[#2b1118]" "px-3" "py-2" "text-sm" "text-[#ff9db2"]
              :data-role "funding-comparison-error"}
        error])]))
