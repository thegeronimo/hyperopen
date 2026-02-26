(ns hyperopen.views.vaults-view
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.views.vaults.vm :as vault-vm]))

(def ^:private compact-currency-formatter
  (js/Intl.NumberFormat.
   "en-US"
   #js {:notation "compact"
        :maximumFractionDigits 1}))

(defn- format-compact-currency
  [value]
  (let [n (if (number? value) value 0)]
    (str "$" (.format compact-currency-formatter n))))

(defn- format-currency
  [value]
  (or (fmt/format-currency value)
      "$0.00"))

(defn- format-percent
  [value]
  (let [n (if (number? value) value 0)
        sign (cond
               (pos? n) "+"
               (neg? n) "-"
               :else "")]
    (str sign (.toFixed (js/Math.abs n) 2) "%")))

(defn- format-age
  [days]
  (let [n (if (number? days) (max 0 (js/Math.floor days)) 0)]
    (str n "d")))

(defn- filter-chip [label active? action]
  [:button {:type "button"
            :class (into ["rounded-md"
                          "border"
                          "px-2.5"
                          "py-1.5"
                          "text-xs"
                          "font-medium"
                          "transition-colors"]
                         (if active?
                           ["border-[#2f6b61]" "bg-[#1f5b55]" "text-trading-text"]
                           ["border-base-300" "bg-base-100" "text-trading-text-secondary" "hover:text-trading-text"]))
            :on {:click [[action]]}}
   label])

(defn- range-chip [label selected? range]
  [:button {:type "button"
            :class (into ["rounded-md"
                          "px-2.5"
                          "py-1.5"
                          "text-xs"
                          "font-medium"
                          "transition-colors"]
                         (if selected?
                           ["bg-base-300" "text-trading-text"]
                           ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
            :on {:click [[:actions/set-vaults-snapshot-range range]]}}
   label])

(defn- sort-header [label column sort-state]
  (let [active? (= column (:column sort-state))
        direction (:direction sort-state)]
    [:button {:type "button"
              :class ["inline-flex"
                      "items-center"
                      "gap-1"
                      "text-xs"
                      "font-medium"
                      "uppercase"
                      "tracking-wide"
                      "text-trading-text-secondary"
                      "hover:text-trading-text"]
              :on {:click [[:actions/set-vaults-sort column]]}}
     [:span label]
     (when active?
       [:span {:class ["text-xs"]}
        (if (= :asc direction) "↑" "↓")])]))

(defn- vault-detail-route
  [vault-address]
  (str "/vaults/" vault-address))

(defn- vault-row
  [{:keys [name vault-address leader apr tvl your-deposit age-days snapshot is-closed?]}]
  [:tr {:class ["border-b"
                "border-base-300/60"
                "text-sm"
                "text-trading-text"
                "hover:bg-base-200/60"]
        :data-role "vault-row"}
   [:td {:class ["px-3" "py-2.5"]}
    [:a {:href (vault-detail-route vault-address)
         :class ["block"
                 "w-full"
                 "text-left"
                 "focus:outline-none"
                 "focus:ring-0"
                 "focus:ring-offset-0"]
         :data-role "vault-row-link"}
     [:div {:class ["flex" "items-center" "gap-2"]}
      [:span {:class ["font-medium"]} name]
      (when is-closed?
        [:span {:class ["rounded" "border" "border-amber-600/50" "px-1.5" "py-0.5" "text-xs" "uppercase" "tracking-wide" "text-amber-400"]}
         "Closed"])]
     [:div {:class ["mt-0.5" "num" "text-xs" "text-trading-text-secondary"]}
      (wallet/short-addr vault-address)]]]
   [:td {:class ["px-3" "py-2.5" "num"]}
    (wallet/short-addr leader)]
   [:td {:class ["px-3" "py-2.5" "num"]} (format-percent apr)]
   [:td {:class ["px-3" "py-2.5" "num"]} (format-compact-currency tvl)]
   [:td {:class ["px-3" "py-2.5" "num"]} (format-currency your-deposit)]
   [:td {:class ["px-3" "py-2.5" "num"]} (format-age age-days)]
   [:td {:class ["px-3" "py-2.5" "num"]} (format-percent snapshot)]])

(defn- mobile-vault-card
  [{:keys [name vault-address leader apr tvl your-deposit age-days snapshot]}]
  [:a {:href (vault-detail-route vault-address)
       :class ["block"
               "w-full"
               "rounded-lg"
               "border"
               "border-base-300"
               "bg-base-100"
               "p-3"
               "text-left"
               "transition-colors"
               "hover:bg-base-200"]}
   [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
    [:div {:class ["min-w-0"]}
     [:div {:class ["truncate" "font-medium" "text-trading-text"]} name]
     [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
      (wallet/short-addr vault-address)]]
    [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
     (wallet/short-addr leader)]]
   [:div {:class ["mt-3" "grid" "grid-cols-2" "gap-2" "text-xs"]}
    [:div [:span {:class ["text-trading-text-secondary"]} "APR "] [:span {:class ["num" "text-trading-text"]} (format-percent apr)]]
    [:div [:span {:class ["text-trading-text-secondary"]} "TVL "] [:span {:class ["num" "text-trading-text"]} (format-compact-currency tvl)]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Deposit "] [:span {:class ["num" "text-trading-text"]} (format-currency your-deposit)]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Snapshot "] [:span {:class ["num" "text-trading-text"]} (format-percent snapshot)]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Age "] [:span {:class ["num" "text-trading-text"]} (format-age age-days)]]]])

(defn- section-table [label rows sort-state]
  [:section {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "overflow-hidden"]}
   [:div {:class ["flex" "items-center" "justify-between" "px-3" "py-2.5" "border-b" "border-base-300"]}
    [:h3 {:class ["text-sm" "font-semibold" "text-trading-text"]} label]
    [:span {:class ["num" "text-xs" "text-trading-text-secondary"]} (str (count rows) " vaults")]]
   [:div {:class ["hidden" "md:block" "overflow-x-auto"]}
    [:table {:class ["w-full" "border-collapse"] :data-role (str "vaults-" label "-table")}
     [:thead
      [:tr {:class ["border-b" "border-base-300" "bg-base-200/70"]}
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Vault" :vault sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Leader" :leader sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "APR" :apr sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "TVL" :tvl sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Your Deposit" :your-deposit sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Age" :age sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Snapshot" :snapshot sort-state)]]]
     [:tbody
      (if (seq rows)
        (for [row rows]
          ^{:key (str "vault-row-" (:vault-address row))}
          (vault-row row))
        [[:tr
          [:td {:col-span 7
                :class ["px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
           "No vaults match current filters."]]])]]]
   [:div {:class ["md:hidden" "space-y-2" "p-3"]}
    (if (seq rows)
      (for [row rows]
        ^{:key (str "mobile-vault-row-" (:vault-address row))}
        (mobile-vault-card row))
      [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/60" "px-3" "py-4" "text-center" "text-sm" "text-trading-text-secondary"]}
       "No vaults match current filters."])]] )

(defn vaults-view
  [state]
  (let [{:keys [query
                filters
                snapshot-range
                sort
                loading?
                error
                protocol-rows
                user-rows
                total-visible-tvl]} (vault-vm/vault-list-vm state)]
    [:div {:class ["w-full" "app-shell-gutter" "py-4" "space-y-4"]
           :data-parity-id "vaults-root"}
     [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
      [:div
       [:h1 {:class ["text-xl" "font-semibold" "text-trading-text"]} "Vaults"]
       [:p {:class ["text-sm" "text-trading-text-secondary"]}
        "Discover protocol and user vault strategies."]]
      [:button {:type "button"
                :disabled true
                :class ["rounded-lg"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "px-3"
                        "py-2"
                        "text-sm"
                        "text-trading-text-secondary"
                        "opacity-70"
                        "cursor-not-allowed"]}
       "Create Vault (Coming soon)"]]

     [:div {:class ["grid" "gap-3" "lg:grid-cols-[280px_minmax(0,1fr)]"]}
      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-3"]}
       [:div {:class ["text-xs" "uppercase" "tracking-wide" "text-trading-text-secondary"]}
        "Total Value Locked"]
       [:div {:class ["mt-1" "num" "text-2xl" "font-semibold" "text-trading-text"]}
        (format-currency total-visible-tvl)]]

      [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-3" "space-y-3"]}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
        [:input {:id "vaults-search-input"
                 :type "search"
                 :class ["h-9"
                         "min-w-[220px]"
                         "flex-1"
                         "rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-100"
                         "px-3"
                         "text-sm"
                         "text-trading-text"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :placeholder "Search vault, leader, or address"
                 :value query
                 :on {:input [[:actions/set-vaults-search-query [:event.target/value]]]}}]
        (filter-chip "Leading" (:leading? filters) [:actions/toggle-vaults-filter :leading])
        (filter-chip "Deposited" (:deposited? filters) [:actions/toggle-vaults-filter :deposited])
        (filter-chip "Others" (:others? filters) [:actions/toggle-vaults-filter :others])
        (filter-chip "Closed" (:show-closed? filters) [:actions/toggle-vaults-filter :closed])]
       [:div {:class ["flex" "items-center" "gap-1"]}
        (range-chip "24H" (= snapshot-range :day) :day)
        (range-chip "7D" (= snapshot-range :week) :week)
        (range-chip "30D" (= snapshot-range :month) :month)
        (range-chip "All-time" (= snapshot-range :all-time) :all-time)]]]

     (when loading?
       [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-100" "px-3" "py-2.5" "text-sm" "text-trading-text-secondary"]}
        "Loading vaults..."])

     (when error
       [:div {:class ["rounded-lg" "border" "border-red-500/40" "bg-red-900/20" "px-3" "py-2.5" "text-sm" "text-red-200"]}
        error])

     (section-table "Protocol Vaults" protocol-rows sort)
     (section-table "User Vaults" user-rows sort)]))
