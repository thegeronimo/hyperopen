(ns hyperopen.views.vaults.list-view
  (:require [hyperopen.views.vaults.list-view.controls :as controls]
            [hyperopen.views.vaults.list-view.format :as format]
            [hyperopen.views.vaults.list-view.sections :as sections]
            [hyperopen.views.vaults.vm :as vault-vm]))

(def ^:private desktop-breakpoint-px
  1024)

(defn- viewport-width-px []
  (let [width (some-> js/globalThis .-innerWidth)]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn- desktop-vaults-layout? []
  (>= (viewport-width-px) desktop-breakpoint-px))

(defn vaults-view
  [state]
  (let [{:keys [query
                filters
                snapshot-range
                sort
                loading?
                refreshing?
                error
                preview-state
                protocol-rows
                user-rows
                visible-user-rows
                user-pagination
                total-visible-tvl]} (vault-vm/vault-list-vm state)
        desktop-layout? (desktop-vaults-layout?)
        wallet-connected? (boolean (get-in state [:wallet :connected?]))
        wallet-connecting? (boolean (get-in state [:wallet :connecting?]))]
    [:div {:class ["relative" "w-full" "app-shell-gutter" "py-4" "md:py-6"]
           :data-parity-id "vaults-root"
           :data-preview-state (some-> (:source preview-state) name)}
     [:div {:class ["pointer-events-none"
                    "absolute"
                    "inset-x-0"
                    "top-0"
                    "h-[180px]"
                    "md:h-[300px]"
                    "rounded-b-[24px]"
                    "opacity-90"]
            :style {:background-image "radial-gradient(120% 120% at 15% -10%, rgba(0, 148, 111, 0.35), rgba(6, 30, 34, 0.05) 60%), radial-gradient(130% 140% at 85% 20%, rgba(0, 138, 96, 0.22), rgba(6, 30, 34, 0) 68%), linear-gradient(180deg, rgba(4, 43, 36, 0.72) 0%, rgba(6, 27, 32, 0.15) 100%)"}}]

     [:div {:class ["relative" "mx-auto" "w-full" "max-w-[1280px]" "space-y-4"]}
      [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
       [:h1 {:class ["text-2xl" "font-normal" "text-trading-text" "sm:text-[48px]" "sm:leading-[52px]"]}
        "Vaults"]]

      (when-not wallet-connected?
        [:button {:type "button"
                  :class (into ["inline-flex"
                                "w-full"
                                "items-center"
                                "justify-center"
                                "rounded-xl"
                                "bg-[#55e6ce]"
                                "px-5"
                                "py-2.5"
                                "text-sm"
                                "font-medium"
                                "text-[#043a33]"
                                "transition-colors"
                                "hover:bg-[#6ef0da]"
                                "sm:w-auto"]
                               format/focus-ring-classes)
                  :disabled wallet-connecting?
                  :on {:click [[:actions/connect-wallet]]}
                  :data-role "vaults-route-connect"}
         (if wallet-connecting? "Connecting…" "Connect")])

      [:div {:class ["w-full" "max-w-[320px]" "rounded-xl" "bg-[#0f1a1f]" "px-3" "py-3" "md:max-w-[360px]" "md:rounded-2xl"]}
       [:div {:class ["text-sm" "font-normal" "text-trading-text-secondary"]}
        "Total Value Locked"]
       (if loading?
         [:div {:class ["mt-3" "h-10" "w-44" "rounded-md" "bg-base-300/70" "animate-pulse"]
                :data-role "vaults-total-visible-tvl-loading"}
          [:div {:class ["sr-only"]} "Loading total value locked"]]
         [:div {:class ["mt-1" "num" "text-[44px]" "leading-[46px]" "font-normal" "text-trading-text"]
                :data-role "vaults-total-visible-tvl"}
          (format/format-total-currency total-visible-tvl)])]

      [:div {:class ["rounded-lg" "border" "border-base-300/80" "bg-base-100/90" "p-2.5" "md:rounded-2xl" "md:p-3"]}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
        [:input {:id "vaults-search-input"
                 :type "search"
                 :class (into ["h-8"
                               "min-w-[260px]"
                               "flex-1"
                               "rounded-lg"
                               "border"
                               "border-base-300"
                               "bg-base-100"
                               "px-3"
                               "text-xs"
                               "text-trading-text"
                               "placeholder:text-trading-text-secondary"]
                              format/focus-ring-classes)
                 :placeholder "Search by vault address, name or leader..."
                 :value query
                 :on {:input [[:actions/set-vaults-search-query [:event.target/value]]]}}]
        (controls/role-filter-menu filters)
        (controls/range-menu snapshot-range)]]

      (when error
        [:div {:class ["rounded-xl" "border" "border-red-500/40" "bg-red-900/20" "px-3" "py-2.5" "text-sm" "text-red-200"]}
         error])

      (when refreshing?
        [:div {:class ["inline-flex"
                       "items-center"
                       "gap-2"
                       "self-start"
                       "rounded-lg"
                       "border"
                       "border-emerald-400/20"
                       "bg-emerald-500/10"
                       "px-3"
                       "py-2"
                       "text-sm"
                       "text-emerald-100"]
               :data-role "vaults-refreshing-banner"}
         [:div {:class ["h-2" "w-2" "rounded-full" "bg-emerald-300" "animate-pulse"]
                :aria-hidden true}]
         "Refreshing vaults…"])

      [:section {:class (into ["rounded-xl"
                               "border"
                               "border-base-300/80"
                               "bg-base-100/95"
                               "p-2.5"
                               "space-y-6"
                               "md:rounded-2xl"
                               "md:p-3"]
                              (when loading?
                                ["min-h-[24rem]" "md:min-h-[36rem]"]))}
       (sections/section-table state "Protocol Vaults" protocol-rows sort {:loading? loading?
                                                                           :desktop-layout? desktop-layout?})
       (sections/section-table state "User Vaults" visible-user-rows sort {:loading? loading?
                                                                           :pagination user-pagination
                                                                           :desktop-layout? desktop-layout?})
       [:div {:class ["text-right" "text-xs" "text-trading-text-secondary"]}
        (str (count protocol-rows) " protocol vaults | " (count user-rows) " user vaults")]]]]))

(defn ^:export route-view
  [state]
  (vaults-view state))

(goog/exportSymbol "hyperopen.views.vaults.list_view.route_view" route-view)
