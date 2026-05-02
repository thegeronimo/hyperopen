(ns hyperopen.views.leaderboard.rows
  (:require [hyperopen.portfolio.routes :as portfolio-routes]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.leaderboard.styles :refer [focus-visible-ring-classes]]
            [hyperopen.wallet.core :as wallet]))

(declare trader-chip)

(defn- trader-link
  [address child]
  [:button {:type "button"
            :class (into ["inline-flex" "min-w-0" "items-center" "gap-2" "text-left"]
                         focus-visible-ring-classes)
            :on {:click [[:actions/navigate
                          (or (portfolio-routes/trader-portfolio-path address)
                              portfolio-routes/canonical-route)]]}
            :data-role "leaderboard-address-link"}
   child])

(defn- explorer-link
  [address]
  [:a {:href (str "https://app.hyperliquid.xyz/explorer/address/" address)
       :target "_blank"
       :rel "noreferrer"
       :class (into ["inline-flex"
                     "items-center"
                     "justify-center"
                     "rounded-md"
                     "border"
                     "border-base-300/80"
                     "bg-base-100/95"
                     "px-2.5"
                     "py-1.5"
                     "text-xs"
                     "font-medium"
                     "text-trading-text-secondary"
                     "transition-colors"
                     "hover:bg-base-200"
                     "hover:text-trading-text"]
                    focus-visible-ring-classes)
       :aria-label "Open trader in Hyperliquid Explorer"
       :data-role "leaderboard-explorer-link"}
   "Explorer"])

(defn- trader-link-shell
  [row]
  [:div {:class ["flex" "min-w-0" "items-center" "justify-between" "gap-3"]}
   [:div {:class ["min-w-0" "flex-1"]}
    (trader-link (:eth-address row)
                 (trader-chip row))]
   (explorer-link (:eth-address row))])

(defn- trader-card-header
  [row]
  [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
   [:div {:class ["min-w-0" "flex-1"]}
    (trader-link (:eth-address row)
                 (trader-chip row))]
   [:div {:class ["flex" "shrink-0" "flex-col" "items-end" "gap-2"]}
    [:div {:class ["text-right"]}
     [:div {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-trading-text-secondary"]}
      "Rank"]
     [:div {:class ["num" "text-sm" "font-semibold" "text-trading-text"]}
      (str "#" (:rank row))]]
    (explorer-link (:eth-address row))]])

(defn- format-account-value
  [value]
  (or (fmt/format-currency value)
      "$0.00"))

(defn- format-volume
  [value]
  (or (fmt/format-currency-with-digits value 0 0)
      "$0"))

(defn- format-pnl
  [value]
  (or (fmt/format-currency value)
      "$0.00"))

(defn- format-roi
  [value]
  (or (fmt/format-signed-percent-from-decimal value
                                              {:decimals 2
                                               :signed? true})
      "0.00%"))

(defn- metric-tone-class
  [value]
  (cond
    (not (number? value)) ["text-trading-text-secondary"]
    (pos? value) ["text-[#36e1d3]"]
    (neg? value) ["text-[#ff6b8a]"]
    :else ["text-trading-text"]))

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

(defn- sortable-header
  [label column sort-state]
  (let [active? (= column (:column sort-state))]
    [:button {:type "button"
              :class (into ["inline-flex"
                            "items-center"
                            "gap-1"
                            "font-normal"
                            "text-trading-text-secondary"
                            "hover:text-trading-text"]
                           focus-visible-ring-classes)
              :on {:click [[:actions/set-leaderboard-sort column]]}}
     [:span label]
     (when active?
       (sort-direction-icon (:direction sort-state)))]))

(defn- trader-chip
  [row]
  [:div {:class ["flex" "min-w-0" "items-center" "gap-2"]}
   [:div {:class ["min-w-0"]}
    [:div {:class ["truncate" "font-semibold" "text-trading-text"]}
     (or (:display-name row)
         (wallet/short-addr (:eth-address row))
         (:eth-address row))]]
   (when (:you? row)
     [:span {:class ["rounded-full"
                     "border"
                     "border-[#2b5d5b]"
                     "bg-[#103c39]"
                     "px-2"
                     "py-0.5"
                     "text-xs"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.08em]"
                     "text-[#9cf9e2]"]
             :data-role "leaderboard-you-badge"}
      "YOU"])])

(defn desktop-row
  [row]
  [:tr {:class (into ["border-b"
                      "border-base-300/50"
                      "text-sm"
                      "text-trading-text"
                      "hover:bg-base-200/40"]
                     (when (:you? row)
                       ["bg-[#0f2220]"]))
        :data-role "leaderboard-row"}
   [:td {:class ["px-3" "py-3" "num"]} (:rank row)]
   [:td {:class ["px-3" "py-3"]}
    (trader-link-shell row)]
   [:td {:class ["px-3" "py-3" "num"]}
    (format-account-value (:account-value row))]
   [:td {:class (into ["px-3" "py-3" "num"] (metric-tone-class (:pnl row)))}
    (format-pnl (:pnl row))]
   [:td {:class (into ["px-3" "py-3" "num"] (metric-tone-class (:roi row)))}
    (format-roi (:roi row))]
   [:td {:class ["px-3" "py-3" "num"]}
    (format-volume (:volume row))]])

(defn mobile-row
  [row timeframe-label]
  (let [card-classes (into ["block"
                            "w-full"
                            "rounded-xl"
                            "border"
                            "border-base-300/80"
                            "bg-base-100/95"
                            "p-3"
                            "space-y-3"
                            "transition-colors"
                            "hover:bg-base-200"]
                           (when (:you? row)
                             ["border-[#2c6d64]" "bg-[#0f2220]"]))]
    [:div {:class card-classes}
     (trader-card-header row)
     [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-2" "text-xs"]}
      [:div
       [:div {:class ["text-trading-text-secondary"]} "Account Value"]
       [:div {:class ["num" "text-sm" "text-trading-text"]}
        (format-account-value (:account-value row))]]
      [:div
       [:div {:class ["text-trading-text-secondary"]} (str "PnL (" timeframe-label ")")]
       [:div {:class (into ["num" "text-sm"] (metric-tone-class (:pnl row)))}
        (format-pnl (:pnl row))]]
      [:div
       [:div {:class ["text-trading-text-secondary"]} (str "ROI (" timeframe-label ")")]
       [:div {:class (into ["num" "text-sm"] (metric-tone-class (:roi row)))}
        (format-roi (:roi row))]]
      [:div
       [:div {:class ["text-trading-text-secondary"]} (str "Volume (" timeframe-label ")")]
       [:div {:class ["num" "text-sm" "text-trading-text"]}
        (format-volume (:volume row))]]]]))
