(ns hyperopen.views.leaderboard-view
  (:require [hyperopen.views.leaderboard.controls :as controls]
            [hyperopen.views.leaderboard.states :as states]
            [hyperopen.views.leaderboard.styles :as styles]
            [hyperopen.views.leaderboard.vm :as leaderboard-vm]))

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
                page-size
                page-size-options
                page-size-dropdown-open?
                show-loading?
                has-results?]}
        (leaderboard-vm/leaderboard-vm state)]
    [:div {:class ["relative"
                   "w-full"
                   "app-shell-gutter"
                   "py-4"
                   "space-y-4"
                   "md:py-5"]
           :style styles/leaderboard-background-style
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
         "Track ranked traders across selectable performance windows."]]]
      [:div {:class styles/control-shell-classes}
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
                              styles/focus-visible-ring-classes)
                 :on {:input [[:actions/set-leaderboard-query [:event.target/value]]]}}]
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-2" "lg:justify-end"]
               :data-role "leaderboard-timeframes"}
         (for [option timeframe-options]
           ^{:key (:value option)}
           (controls/timeframe-button (= timeframe (:value option)) option))]]]

      [:section {:class styles/workspace-shell-classes}
       (states/pinned-row-card pinned-row timeframe-label desktop-layout?)

       (cond
         (seq error)
         (states/error-state error)

         show-loading?
         (states/loading-state {:desktop-layout? desktop-layout?
                                :page-size page-size})

         (not has-results?)
         (states/empty-state)

         desktop-layout?
         (states/table-shell rows timeframe-label sort)

         :else
         (states/mobile-shell rows timeframe-label))

       (when (and (not show-loading?)
                  (not (seq error))
                  has-results?)
         (controls/pagination-controls {:page page
                                         :page-count page-count
                                         :total-rows total-rows
                                         :page-size page-size
                                         :page-size-options page-size-options
                                         :page-size-dropdown-open? page-size-dropdown-open?}))]

      (states/methodology-note timeframe-label)]]))

(defn ^:export route-view
  [state]
  (leaderboard-view state))

(goog/exportSymbol "hyperopen.views.leaderboard_view.route_view" route-view)
