(ns hyperopen.views.leaderboard-view
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.leaderboard.vm :as leaderboard-vm]
            [hyperopen.wallet.core :as wallet]))

(def ^:private leaderboard-background-style
  {:background-image "radial-gradient(circle at 15% 0%, rgba(0, 212, 170, 0.10), transparent 35%), radial-gradient(circle at 85% 100%, rgba(0, 212, 170, 0.08), transparent 40%)"})

(def ^:private workspace-shell-classes
  ["rounded-xl"
   "border"
   "border-base-300/80"
   "bg-base-100/95"
   "overflow-hidden"])

(def ^:private control-shell-classes
  ["rounded-xl"
   "border"
   "border-base-300/80"
   "bg-base-100/95"
   "p-2.5"
   "md:p-3"])

(def ^:private focus-visible-ring-classes
  ["focus:outline-none"
   "focus:ring-2"
   "focus:ring-[#66e3c5]/45"
   "focus:ring-offset-1"
   "focus:ring-offset-base-100"
   "focus-visible:outline-none"
   "focus-visible:ring-2"
   "focus-visible:ring-[#66e3c5]/45"
   "focus-visible:ring-offset-1"
   "focus-visible:ring-offset-base-100"])

(defn- address-link
  [address child]
  [:a {:href (str "https://app.hyperliquid.xyz/explorer/address/" address)
       :target "_blank"
       :rel "noreferrer"
       :class (into ["inline-flex" "min-w-0" "items-center" "gap-2" "text-left"]
                    focus-visible-ring-classes)
       :data-role "leaderboard-address-link"}
   child])

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
   [:div {:class ["min-w-0" "space-y-0.5"]}
    [:div {:class ["truncate" "font-semibold" "text-trading-text"]}
     (or (:display-name row)
         (wallet/short-addr (:eth-address row))
         (:eth-address row))]
    [:div {:class ["num" "truncate" "text-xs" "text-trading-text-secondary"]}
     (wallet/short-addr (:eth-address row))]]
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

(defn- desktop-row
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
    (address-link (:eth-address row)
                  (trader-chip row))]
   [:td {:class ["px-3" "py-3" "num"]}
    (format-account-value (:account-value row))]
   [:td {:class (into ["px-3" "py-3" "num"] (metric-tone-class (:pnl row)))}
    (format-pnl (:pnl row))]
   [:td {:class (into ["px-3" "py-3" "num"] (metric-tone-class (:roi row)))}
    (format-roi (:roi row))]
   [:td {:class ["px-3" "py-3" "num"]}
    (format-volume (:volume row))]])

(defn- mobile-row
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
    (address-link
     (:eth-address row)
     [:div {:class card-classes}
      [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
       (trader-chip row)
       [:div {:class ["text-right"]}
        [:div {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-trading-text-secondary"]}
         "Rank"]
        [:div {:class ["num" "text-sm" "font-semibold" "text-trading-text"]}
         (str "#" (:rank row))]]]
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
         (format-volume (:volume row))]]]])))

(defn- timeframe-button
  [selected? {:keys [value label]}]
  [:button {:type "button"
            :class (into ["rounded-lg"
                          "border"
                          "px-2.5"
                          "py-1.5"
                          "text-xs"
                          "font-medium"
                          "transition-colors"]
                         (concat focus-visible-ring-classes
                                 (if selected?
                                   ["border-[#2f7f73]" "bg-[#123a36]/85" "text-[#97fce4]"]
                                   ["border-base-300/80"
                                    "text-trading-text-secondary"
                                    "hover:bg-base-200"
                                    "hover:text-trading-text"])))
            :on {:click [[:actions/set-leaderboard-timeframe value]]}}
   label])

(defn- loading-state
  []
  [:div {:class ["space-y-3" "p-4" "md:p-5"]
         :data-role "leaderboard-loading"}
   (for [idx (range 4)]
     ^{:key (str "leaderboard-loading-" idx)}
     [:div {:class ["grid"
                    "grid-cols-[72px_minmax(0,1fr)_repeat(4,minmax(0,1fr))]"
                    "gap-3"
                    "animate-pulse"]}
      [:div {:class ["h-5" "rounded" "bg-base-200"]}]
      [:div {:class ["h-5" "rounded" "bg-base-200"]}]
      [:div {:class ["h-5" "rounded" "bg-base-200"]}]
      [:div {:class ["h-5" "rounded" "bg-base-200"]}]
      [:div {:class ["h-5" "rounded" "bg-base-200"]}]
      [:div {:class ["h-5" "rounded" "bg-base-200"]}]])])

(defn- empty-state
  []
  [:div {:class ["px-4"
                 "py-10"
                 "text-center"
                 "text-sm"
                 "text-trading-text-secondary"]
         :data-role "leaderboard-empty"}
   "No traders match the current filters."])

(defn- error-state
  [message]
  [:div {:class ["space-y-3"
                 "px-4"
                 "py-4"]
         :data-role "leaderboard-error"}
   [:div {:class ["rounded-lg"
                  "border"
                  "border-[#7a2836]/70"
                  "bg-[#2b1118]/80"
                  "px-4"
                  "py-4"]}
    [:p {:class ["text-sm" "text-[#ffb0c0]"]}
     (or message "Failed to load leaderboard data.")]]
   [:button {:type "button"
             :class (into ["rounded-lg"
                           "border"
                           "border-[#8a4b56]"
                           "bg-[#341b24]"
                           "px-3"
                           "py-2"
                           "text-sm"
                           "font-medium"
                           "text-[#ffd3db]"
                           "transition-colors"
                           "hover:border-[#a95e6b]"
                           "hover:bg-[#41212b]"]
                          focus-visible-ring-classes)
             :on {:click [[:actions/load-leaderboard]]}}
    "Retry"]])

(defn- pinned-row-card
  [row timeframe-label desktop-layout?]
  (when row
    [:div {:class ["border-b"
                   "border-base-300/60"
                   "bg-[#0f2220]/65"
                   "p-4"
                   "space-y-3"]
               :data-role "leaderboard-pinned-row"}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
     [:div
       [:div {:class ["text-xs" "font-semibold" "uppercase" "tracking-[0.12em]" "text-[#8fd8cb]"]}
        "Your Position"]
       [:div {:class ["text-sm" "text-trading-text-secondary"]}
        "Pinned separately from paginated results."]]
      [:div {:class ["rounded-full"
                     "border"
                     "border-[#2c6d64]"
                     "px-3"
                     "py-1"
                     "text-xs"
                     "font-semibold"
                     "uppercase"
                     "tracking-[0.08em]"
                     "text-[#9cf9e2]"]}
       (str "#" (:rank row))]]
     (if desktop-layout?
       [:div {:class ["overflow-x-auto"]}
        [:table {:class ["min-w-full"]}
         [:tbody
          (desktop-row row)]]]
       (mobile-row row timeframe-label))]))

(defn- pagination-controls
  [{:keys [page page-count total-rows]}]
  [:div {:class ["flex"
                 "items-center"
                 "justify-between"
                 "gap-3"
                 "border-t"
                 "border-base-300/60"
                 "px-4"
                 "py-3"]
         :data-role "leaderboard-pagination"}
   [:div {:class ["text-sm" "text-trading-text-secondary"]}
    (str total-rows " ranked trader"
         (when (not= 1 total-rows) "s"))]
   [:div {:class ["flex" "items-center" "gap-2"]}
    [:button {:type "button"
              :class (into ["rounded-lg"
                            "border"
                            "border-base-300"
                            "px-3"
                            "py-1.5"
                            "text-sm"
                            "text-trading-text-secondary"
                            "transition-colors"
                            "hover:bg-base-200"
                            "hover:text-trading-text"
                            "disabled:cursor-not-allowed"
                            "disabled:opacity-50"]
                           focus-visible-ring-classes)
              :disabled (= page 1)
              :on {:click [[:actions/prev-leaderboard-page page-count]]}}
     "Prev"]
    [:div {:class ["num" "text-sm" "text-trading-text-secondary"]}
     (str page " / " page-count)]
    [:button {:type "button"
              :class (into ["rounded-lg"
                            "border"
                            "border-base-300"
                            "px-3"
                            "py-1.5"
                            "text-sm"
                            "text-trading-text-secondary"
                            "transition-colors"
                            "hover:bg-base-200"
                            "hover:text-trading-text"
                            "disabled:cursor-not-allowed"
                            "disabled:opacity-50"]
                           focus-visible-ring-classes)
              :disabled (= page page-count)
              :on {:click [[:actions/next-leaderboard-page page-count]]}}
     "Next"]]])

(defn- table-shell
  [rows timeframe-label sort]
  [:div {:class ["overflow-x-auto"]}
   [:table {:class ["min-w-full"]
            :data-role "leaderboard-table"}
    [:thead
     [:tr {:class ["text-xs" "text-trading-text-secondary"]}
      [:th {:class ["px-3" "py-2" "text-left"]} "Rank"]
      [:th {:class ["px-3" "py-2" "text-left"]} "Trader"]
      [:th {:class ["px-3" "py-2" "text-left"]}
       (sortable-header "Account Value" :account-value sort)]
      [:th {:class ["px-3" "py-2" "text-left"]}
       (sortable-header (str "PnL (" timeframe-label ")") :pnl sort)]
      [:th {:class ["px-3" "py-2" "text-left"]}
       (sortable-header (str "ROI (" timeframe-label ")") :roi sort)]
      [:th {:class ["px-3" "py-2" "text-left"]}
       (sortable-header (str "Volume (" timeframe-label ")") :volume sort)]]]
    [:tbody
     (for [row rows]
       ^{:key (:eth-address row)}
       (desktop-row row))]]])

(defn- mobile-shell
  [rows timeframe-label]
  [:div {:class ["grid" "gap-3"]
         :data-role "leaderboard-mobile-list"}
   (for [row rows]
     ^{:key (:eth-address row)}
     (mobile-row row timeframe-label))])

(defn- methodology-note
  [timeframe-label]
  [:section {:class ["space-y-2"
                     "px-1"
                     "pt-1"]
             :data-role "leaderboard-methodology"}
   [:h2 {:class ["text-sm" "font-semibold" "text-trading-text"]}
    "Methodology"]
   [:p {:class ["text-sm" "leading-6" "text-trading-text-secondary"]}
    (str "Ranks are recomputed after filtering and sorting. Account value reflects the current balance, while PnL, ROI, and volume use the selected "
         timeframe-label
         " window. Vault and other non-user addresses are excluded from this leaderboard baseline.")]])

(defn leaderboard-view
  [state]
  (let [{:keys [query
                timeframe
                timeframe-label
                timeframe-options
                sort
                loading?
                error
                desktop-layout?
                pinned-row
                rows
                page
                page-count
                total-rows
                has-results?]}
        (leaderboard-vm/leaderboard-vm state)]
    [:div {:class ["relative"
                   "w-full"
                   "app-shell-gutter"
                   "py-4"
                   "space-y-4"
                   "md:py-5"]
           :style leaderboard-background-style
           :data-parity-id "leaderboard-root"}
     [:div {:class ["pointer-events-none"
                    "absolute"
                    "inset-x-0"
                    "top-0"
                    "h-[180px]"
                    "md:h-[280px]"
                    "rounded-b-[24px]"
                    "opacity-90"]
            :style {:background-image "radial-gradient(120% 120% at 15% -10%, rgba(0, 148, 111, 0.22), rgba(6, 30, 34, 0.02) 60%), radial-gradient(130% 140% at 85% 20%, rgba(0, 138, 96, 0.14), rgba(6, 30, 34, 0) 68%), linear-gradient(180deg, rgba(4, 43, 36, 0.54) 0%, rgba(6, 27, 32, 0.08) 100%)"}}]
     [:div {:class ["relative" "mx-auto" "w-full" "max-w-[1280px]" "space-y-4"]}
      [:div {:class ["flex" "flex-wrap" "items-end" "justify-between" "gap-3"]}
       [:div {:class ["space-y-1"]}
        [:h1 {:class ["text-2xl" "font-normal" "text-trading-text"]}
         "Leaderboard"]
        [:p {:class ["max-w-2xl" "text-sm" "text-trading-text-secondary"]}
         "Track ranked traders across selectable performance windows."]]
       [:div {:class ["text-xs" "uppercase" "tracking-[0.08em]" "text-trading-text-secondary"]}
        "Read-only ranking surface"]]
      [:div {:class control-shell-classes}
       [:div {:class ["flex" "flex-col" "gap-2.5" "lg:flex-row" "lg:items-center" "lg:justify-between"]}
        [:input {:id "leaderboard-search"
                 :type "text"
                 :placeholder "Search wallet or display name"
                 :value query
                 :class (into ["h-8"
                               "w-full"
                               "max-w-[360px]"
                               "rounded-lg"
                               "border"
                               "border-base-300/80"
                               "bg-base-100"
                               "px-3"
                               "text-xs"
                               "text-trading-text"
                               "placeholder:text-trading-text-secondary"]
                              focus-visible-ring-classes)
                 :on {:input [[:actions/set-leaderboard-query [:event.target/value]]]}}]
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "lg:justify-end"]
               :data-role "leaderboard-timeframes"}
         (for [option timeframe-options]
           ^{:key (:value option)}
           (timeframe-button (= timeframe (:value option)) option))]]]

      [:section {:class workspace-shell-classes}
       (pinned-row-card pinned-row timeframe-label desktop-layout?)

       (cond
         (seq error)
         (error-state error)

         loading?
         (loading-state)

         (not has-results?)
         (empty-state)

         desktop-layout?
         (table-shell rows timeframe-label sort)

         :else
         (mobile-shell rows timeframe-label))

       (when (and (not loading?)
                  (not (seq error))
                  has-results?)
         (pagination-controls {:page page
                               :page-count page-count
                               :total-rows total-rows}))]

      (methodology-note timeframe-label)]]))

(defn ^:export route-view
  [state]
  (leaderboard-view state))

(goog/exportSymbol "hyperopen.views.leaderboard_view.route_view" route-view)
