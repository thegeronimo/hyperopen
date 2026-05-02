(ns hyperopen.views.leaderboard.states
  (:require [hyperopen.views.leaderboard.controls :refer [sortable-header]]
            [hyperopen.views.leaderboard.rows :refer [desktop-row mobile-row]]
            [hyperopen.views.leaderboard.styles :refer [focus-visible-ring-classes]]))

(defn- loading-skeleton-block
  [extra-classes]
  [:span {:class (into ["block"
                        "h-3.5"
                        "rounded"
                        "ui-loading-shimmer"]
                       extra-classes)}])

(defn- desktop-loading-row
  [idx]
  [:tr {:class ["border-b" "border-base-300/40"]
        :data-index idx}
   [:td {:class ["px-3" "py-3"]} (loading-skeleton-block ["w-10"])]
   [:td {:class ["px-3" "py-3"]} (loading-skeleton-block ["w-32"])]
   [:td {:class ["px-3" "py-3"]} (loading-skeleton-block ["w-24"])]
   [:td {:class ["px-3" "py-3"]} (loading-skeleton-block ["w-20"])]
   [:td {:class ["px-3" "py-3"]} (loading-skeleton-block ["w-16"])]
   [:td {:class ["px-3" "py-3"]} (loading-skeleton-block ["w-24"])]])

(defn- mobile-loading-card
  [idx]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "p-3"
                 "space-y-3"]
         :data-index idx}
   [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
    (loading-skeleton-block ["w-28"])
    (loading-skeleton-block ["w-16"])]
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    (loading-skeleton-block ["w-20"])
    (loading-skeleton-block ["w-20"])
    (loading-skeleton-block ["w-24"])
    (loading-skeleton-block ["w-16"])]])

(defn loading-state
  [{:keys [desktop-layout? page-size]}]
  (let [row-count (-> (or page-size 5)
                      (max 1)
                      (min 5))]
    [:div {:class ["space-y-3" "p-4" "md:p-5"]
           :data-role "leaderboard-loading"}
     [:div {:class ["flex" "items-center" "gap-2" "text-xs" "text-trading-text-secondary"]}
      [:span {:class ["h-2" "w-2" "rounded-full" "bg-emerald-300" "animate-pulse"]
              :aria-hidden true}]
      [:span "Loading ranked traders and vault exclusions..."]]
     (if desktop-layout?
       [:div {:class ["overflow-x-auto"]}
        [:table {:class ["min-w-full"]}
         [:thead
          [:tr {:class ["border-b" "border-base-300/60"]}
           [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Rank"]
           [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Trader"]
           [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Account Value"]
           [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "PnL"]
           [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "ROI"]
           [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Volume"]]]
         [:tbody
          (for [idx (range row-count)]
            ^{:key (str "leaderboard-loading-row-" idx)}
            (desktop-loading-row idx))]]]
       [:div {:class ["space-y-2"]}
        (for [idx (range row-count)]
          ^{:key (str "leaderboard-loading-card-" idx)}
          (mobile-loading-card idx))])]))

(defn empty-state
  []
  [:div {:class ["px-4"
                 "py-10"
                 "text-center"
                 "text-sm"
                 "text-trading-text-secondary"]
         :data-role "leaderboard-empty"}
   "No traders match the current filters."])

(defn error-state
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

(defn pinned-row-card
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
(defn table-shell
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

(defn mobile-shell
  [rows timeframe-label]
  [:div {:class ["grid" "gap-3"]
         :data-role "leaderboard-mobile-list"}
   (for [row rows]
     ^{:key (:eth-address row)}
     (mobile-row row timeframe-label))])

(defn methodology-note
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
