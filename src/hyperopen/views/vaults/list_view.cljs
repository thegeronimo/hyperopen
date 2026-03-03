(ns hyperopen.views.vaults.list-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.views.vaults.vm :as vault-vm]))

(defn- format-total-currency
  [value]
  (or (fmt/format-large-currency (if (number? value) value 0))
      "$0"))

(defn- format-currency
  [value]
  (or (fmt/format-currency (if (number? value) value 0))
      "$0.00"))

(defn- format-percent
  [value]
  (let [n (if (number? value) value 0)]
    (str (.toFixed n 2) "%")))

(defn- percent-text-class
  [value]
  (if (neg? (if (number? value) value 0))
    ["text-[#ff6b8a]"]
    ["text-[#36e1d3]"]))

(defn- format-age
  [days]
  (let [n (if (number? days) (max 0 (js/Math.floor days)) 0)]
    (str n)))

(defn- dropdown-option [label active? action]
  [:button {:type "button"
            :class (into ["flex"
                          "w-full"
                          "items-center"
                          "justify-between"
                          "rounded-md"
                          "px-2.5"
                          "py-1.5"
                          "text-xs"
                          "transition-colors"]
                         (if active?
                           ["bg-[#123a36]" "text-[#97fce4]"]
                           ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
            :on {:click [[action]]}}
   [:span label]
   (when active?
     [:span {:aria-hidden true} "ON"])])

(defn- control-menu [label summary-text options]
  [:details {:class ["relative"]}
   [:summary {:class ["flex"
                      "h-9"
                      "list-none"
                      "cursor-pointer"
                      "items-center"
                      "gap-2"
                      "rounded-xl"
                      "border"
                      "border-base-300"
                      "bg-base-100"
                      "px-3"
                      "text-xs"
                      "text-trading-text"
                      "hover:bg-base-200"
                      "focus:outline-none"
                      "focus:ring-0"
                      "focus:ring-offset-0"]}
    [:span {:class ["text-trading-text-secondary"]} label]
    [:span {:class ["max-w-[180px]" "truncate"]} summary-text]
    [:svg {:class ["h-3.5" "w-3.5" "text-trading-text-secondary"]
           :viewBox "0 0 20 20"
           :fill "currentColor"
           :aria-hidden true}
     [:path {:fill-rule "evenodd"
             :clip-rule "evenodd"
             :d "M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.168l3.71-3.938a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"}]]]
   [:div {:class ["absolute"
                  "right-0"
                  "top-full"
                  "z-30"
                  "mt-1.5"
                  "min-w-[220px]"
                  "rounded-xl"
                  "border"
                  "border-base-300"
                  "bg-base-100"
                  "p-2"
                  "shadow-2xl"]}
    options]])

(defn- selected-role-labels [{:keys [leading? deposited? others?]}]
  (cond-> []
    leading? (conj "Leading")
    deposited? (conj "Deposited")
    others? (conj "Others")))

(defn- role-filter-menu [filters]
  (let [role-labels (selected-role-labels filters)
        summary-text (if (seq role-labels)
                       (str/join ", " role-labels)
                       "None")]
    (control-menu "Filter"
                  summary-text
                  [:div {:class ["space-y-1"]}
                   (dropdown-option "Leading" (:leading? filters) [:actions/toggle-vaults-filter :leading])
                   (dropdown-option "Deposited" (:deposited? filters) [:actions/toggle-vaults-filter :deposited])
                   (dropdown-option "Others" (:others? filters) [:actions/toggle-vaults-filter :others])
                   [:div {:class ["my-1" "h-px" "bg-base-300"]}]
                   (dropdown-option "Closed" (:show-closed? filters) [:actions/toggle-vaults-filter :closed])])))

(defn- snapshot-range-label [snapshot-range]
  (case snapshot-range
    :day "24H"
    :week "7D"
    :month "30D"
    :three-month "3M"
    :six-month "6M"
    :one-year "1Y"
    :two-year "2Y"
    :all-time "All-time"
    "30D"))

(defn- range-menu [snapshot-range]
  (control-menu "Range"
                (snapshot-range-label snapshot-range)
                [:div {:class ["space-y-1"]}
                 (dropdown-option "24H" (= snapshot-range :day) [:actions/set-vaults-snapshot-range :day])
                 (dropdown-option "7D" (= snapshot-range :week) [:actions/set-vaults-snapshot-range :week])
                 (dropdown-option "30D" (= snapshot-range :month) [:actions/set-vaults-snapshot-range :month])
                 (dropdown-option "3M" (= snapshot-range :three-month) [:actions/set-vaults-snapshot-range :three-month])
                 (dropdown-option "6M" (= snapshot-range :six-month) [:actions/set-vaults-snapshot-range :six-month])
                 (dropdown-option "1Y" (= snapshot-range :one-year) [:actions/set-vaults-snapshot-range :one-year])
                 (dropdown-option "2Y" (= snapshot-range :two-year) [:actions/set-vaults-snapshot-range :two-year])
                 (dropdown-option "All-time" (= snapshot-range :all-time) [:actions/set-vaults-snapshot-range :all-time])]))

(defn- sort-header [label column sort-state]
  (let [active? (= column (:column sort-state))
        direction (:direction sort-state)]
    [:button {:type "button"
              :class ["inline-flex"
                      "items-center"
                      "gap-1"
                      "text-xs"
                      "font-normal"
                      "text-trading-text-secondary"
                      "hover:text-trading-text"]
              :on {:click [[:actions/set-vaults-sort column]]}}
      [:span label]
      (when active?
       [:span {:class ["text-xs"]}
        (if (= :asc direction) "^" "v")])]))

(defn- vault-detail-route
  [vault-address]
  (str "/vaults/" vault-address))

(defn- normalize-series [series]
  (let [values (if (sequential? series)
                 (->> series
                      (keep #(when (number? %) %))
                      vec)
                 [])]
    (if (seq values)
      values
      [0 0])))

(defn- sparkline-path
  [series width height]
  (let [points (normalize-series series)
        min-value (apply min points)
        max-value (apply max points)
        value-span (max 0.000001 (- max-value min-value))
        step-x (/ width (max 1 (dec (count points))))]
    (->> points
         (map-indexed (fn [idx value]
                        (let [x (* idx step-x)
                              normalized (/ (- value min-value) value-span)
                              y (* (- 1 normalized) height)]
                          (str (if (zero? idx) "M" "L")
                               (.toFixed x 2)
                               ","
                               (.toFixed y 2)))))
         (str/join " "))))

(defn- snapshot-sparkline [series]
  (let [values (normalize-series series)
        start-value (first values)
        end-value (last values)
        positive? (>= end-value start-value)
        stroke (if positive?
                 "#36e1d3"
                 "#ff6b8a")]
    [:svg {:class ["h-7" "w-20"]
           :viewBox "0 0 80 28"
           :preserveAspectRatio "none"
           :aria-label "Vault snapshot trend"}
     [:path {:d (sparkline-path values 80 28)
             :fill "none"
             :stroke stroke
             :stroke-width 2
             :stroke-linecap "round"
             :stroke-linejoin "round"
             :vector-effect "non-scaling-stroke"}]]))

(defn- vault-row
  [{:keys [name vault-address leader apr tvl your-deposit age-days snapshot-series is-closed?]}]
  [:tr {:class ["border-b"
                "border-base-300/50"
                "text-sm"
                "text-trading-text"
                "hover:bg-base-200/50"]
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
      [:span {:class ["truncate" "font-semibold" "text-trading-text"]} name]
      (when is-closed?
        [:span {:class ["rounded"
                        "border"
                        "border-amber-600/40"
                        "px-1.5"
                        "py-0.5"
                        "text-xs"
                        "uppercase"
                        "tracking-wide"
                        "text-amber-300"]}
         "Closed"])]]]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (wallet/short-addr leader)]
   [:td {:class (into ["px-3" "py-2.5" "num"]
                      (percent-text-class apr))}
    (format-percent apr)]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (format-currency tvl)]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (format-currency your-deposit)]
   [:td {:class ["px-3" "py-2.5" "num" "text-trading-text"]}
    (format-age age-days)]
   [:td {:class ["px-3" "py-2.5" "text-right"]}
    (snapshot-sparkline snapshot-series)]])

(defn- mobile-vault-card
  [{:keys [name vault-address leader apr tvl your-deposit age-days]}]
  [:a {:href (vault-detail-route vault-address)
       :class ["block"
               "w-full"
               "rounded-xl"
               "border"
               "border-base-300"
               "bg-base-100"
               "p-3"
               "text-left"
               "transition-colors"
               "hover:bg-base-200"]}
   [:div {:class ["flex" "items-center" "justify-between" "gap-2"]}
    [:div {:class ["min-w-0"]}
     [:div {:class ["truncate" "font-semibold" "text-trading-text"]} name]
     [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
      (wallet/short-addr vault-address)]]
    [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
     (wallet/short-addr leader)]]
   [:div {:class ["mt-3" "grid" "grid-cols-2" "gap-2" "text-xs"]}
    [:div [:span {:class ["text-trading-text-secondary"]} "APR "] [:span {:class (into ["num"] (percent-text-class apr))} (format-percent apr)]]
    [:div [:span {:class ["text-trading-text-secondary"]} "TVL "] [:span {:class ["num" "text-trading-text"]} (format-currency tvl)]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Your Deposit "] [:span {:class ["num" "text-trading-text"]} (format-currency your-deposit)]]
    [:div [:span {:class ["text-trading-text-secondary"]} "Age "] [:span {:class ["num" "text-trading-text"]} (format-age age-days)]]]])

(def ^:private loading-skeleton-row-count
  5)

(defn- skeleton-block
  [extra-classes]
  [:span {:class (into ["block"
                        "h-3.5"
                        "rounded"
                        "bg-base-300/70"
                        "animate-pulse"]
                       extra-classes)}])

(defn- desktop-loading-row
  [idx]
  [:tr {:class ["border-b" "border-base-300/40"]
        :data-role "vault-loading-row"
        :data-index idx}
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-40"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-24"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-14"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-20"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-24"])]
   [:td {:class ["px-3" "py-3"]} (skeleton-block ["w-10"])]
   [:td {:class ["px-3" "py-3" "text-right"]} (skeleton-block ["ml-auto" "w-20"])]])

(defn- mobile-loading-card
  [idx]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-base-300"
                 "bg-base-100"
                 "p-3"
                 "space-y-3"]
         :data-role "vault-loading-card"
         :data-index idx}
   [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
    (skeleton-block ["w-28"])
    (skeleton-block ["w-20"])]
   [:div {:class ["grid" "grid-cols-2" "gap-2"]}
    (skeleton-block ["w-20"])
    (skeleton-block ["w-20"])
    (skeleton-block ["w-24"])
    (skeleton-block ["w-16"])]])

(defn- user-vault-pagination-controls
  [{:keys [total-rows
           page-size
           page
           page-count
           page-size-options
           page-size-dropdown-open?]}]
  (when (pos? total-rows)
    (let [page-size* (str page-size)]
      [:div {:class ["mt-2"
                     "flex"
                     "flex-wrap"
                     "items-center"
                     "justify-between"
                     "gap-2"
                     "border-t"
                     "border-base-300/80"
                     "pt-2"
                     "text-xs"]}
       [:div {:class ["flex" "items-center" "gap-2"]}
        [:span {:id "vaults-user-page-size-label"
                :class ["text-trading-text-secondary"]}
         "Rows"]
        [:div {:class ["relative"]
               :style (when page-size-dropdown-open?
                        {:z-index 1200})}
         (when page-size-dropdown-open?
           [:button {:type "button"
                     :class ["fixed" "inset-0" "bg-transparent" "cursor-default"]
                     :style {:z-index 1200}
                     :aria-label "Close rows per page menu"
                     :on {:click [[:actions/close-vaults-user-page-size-dropdown]]}}])
         [:button {:id "vaults-user-page-size"
                   :type "button"
                   :aria-haspopup "listbox"
                   :aria-expanded (boolean page-size-dropdown-open?)
                   :aria-labelledby "vaults-user-page-size-label"
                   :class ["relative"
                           "flex"
                           "h-8"
                           "min-w-[72px]"
                           "cursor-pointer"
                           "items-center"
                           "justify-between"
                           "gap-2"
                           "rounded-lg"
                           "border"
                           "border-base-300"
                           "bg-base-100"
                           "pl-3"
                           "pr-2"
                           "text-xs"
                           "text-trading-text"
                           "hover:bg-base-200"
                           "focus:outline-none"
                           "focus:ring-0"
                           "focus:ring-offset-0"]
                   :style (when page-size-dropdown-open?
                            {:z-index 1201})
                   :on {:click [[:actions/toggle-vaults-user-page-size-dropdown]]}}
          [:span {:class ["num" "text-sm" "leading-none"]} page-size*]
          [:svg {:class ["h-3.5" "w-3.5" "shrink-0" "text-trading-text-secondary"]
                 :viewBox "0 0 20 20"
                 :fill "currentColor"
                 :aria-hidden true}
           [:path {:fill-rule "evenodd"
                   :clip-rule "evenodd"
                   :d "M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.168l3.71-3.938a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"}]]]
         (when page-size-dropdown-open?
           [:div {:class ["absolute"
                          "left-0"
                          "bottom-full"
                          "mb-1"
                          "min-w-[88px]"
                          "max-h-40"
                          "overflow-y-auto"
                          "rounded-lg"
                          "border"
                          "border-base-300"
                          "bg-base-100"
                          "p-1"
                          "shadow-2xl"]
                  :style {:z-index 1202}
                  :role "listbox"
                  :aria-labelledby "vaults-user-page-size-label"}
            (for [size page-size-options]
              (let [size* (str size)
                    active? (= size* page-size*)]
                ^{:key (str "vault-page-size-" size)}
                [:button {:type "button"
                          :class (into ["w-full"
                                        "rounded-md"
                                        "px-2.5"
                                        "py-1.5"
                                        "text-left"
                                        "text-xs"
                                        "num"
                                        "transition-colors"]
                                       (if active?
                                         ["bg-[#123a36]" "text-[#97fce4]"]
                                         ["text-trading-text-secondary" "hover:bg-base-200" "hover:text-trading-text"]))
                          :role "option"
                          :aria-selected (boolean active?)
                          :on {:click [[:actions/set-vaults-user-page-size size]]}}
                 size*]))])]
        [:span {:class ["text-trading-text-secondary"]}
         (str "Total: " total-rows)]]
       [:div {:class ["flex" "items-center" "gap-2"]}
        [:button {:type "button"
                  :class ["h-7"
                          "rounded-md"
                          "border"
                          "border-base-300"
                          "px-2"
                          "text-xs"
                          "text-trading-text"
                          "disabled:cursor-not-allowed"
                          "disabled:opacity-40"]
                  :disabled (<= page 1)
                  :on {:click [[:actions/prev-vaults-user-page page-count]]}}
         "Prev"]
        [:span {:class ["min-w-[6rem]" "text-center" "text-trading-text-secondary"]}
         (str "Page " page " of " page-count)]
        [:button {:type "button"
                  :class ["h-7"
                          "rounded-md"
                          "border"
                          "border-base-300"
                          "px-2"
                          "text-xs"
                          "text-trading-text"
                          "disabled:cursor-not-allowed"
                          "disabled:opacity-40"]
                  :disabled (>= page page-count)
                  :on {:click [[:actions/next-vaults-user-page page-count]]}}
         "Next"]]])))

(defn- section-table [label rows sort-state {:keys [loading? pagination]}]
  [:section {:class ["space-y-2"]}
   [:h3 {:class ["text-sm" "font-normal" "text-trading-text"]} label]
   [:div {:class ["hidden" "md:block" "overflow-x-auto"]}
    [:table {:class ["w-full" "border-collapse"]
             :data-role (str "vaults-" (str/lower-case (str/replace label #"\\s+" "-")) "-table")}
     [:colgroup
      [:col {:style {:width "24%"}}]
      [:col {:style {:width "16%"}}]
      [:col {:style {:width "12%"}}]
      [:col {:style {:width "12%"}}]
      [:col {:style {:width "12%"}}]
      [:col {:style {:width "12%"}}]
      [:col {:style {:width "12%"}}]]
     [:thead
      [:tr {:class ["border-b" "border-base-300/70"]}
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Vault" :vault sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Leader" :leader sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "APR" :apr sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "TVL" :tvl sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Your Deposit" :your-deposit sort-state)]
       [:th {:class ["px-3" "py-2" "text-left"]} (sort-header "Age" :age sort-state)]
       [:th {:class ["px-3" "py-2" "text-right"]} (sort-header "Snapshot" :snapshot sort-state)]]]
     [:tbody
      (cond
        loading?
        (for [idx (range loading-skeleton-row-count)]
          ^{:key (str "vault-loading-row-" label "-" idx)}
          (desktop-loading-row idx))

        (seq rows)
        (for [row rows]
          ^{:key (str "vault-row-" (:vault-address row))}
          (vault-row row))

        :else
        [:tr
         [:td {:col-span 7
               :class ["px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
          "No vaults match current filters."]])]]]
   [:div {:class ["space-y-2" "md:hidden"]}
    (cond
      loading?
      (for [idx (range loading-skeleton-row-count)]
        ^{:key (str "mobile-vault-loading-row-" label "-" idx)}
        (mobile-loading-card idx))

      (seq rows)
      (for [row rows]
        ^{:key (str "mobile-vault-row-" (:vault-address row))}
        (mobile-vault-card row))

      :else
      [:div {:class ["rounded-lg"
                     "border"
                     "border-base-300"
                     "bg-base-200/60"
                     "px-3"
                     "py-4"
                     "text-center"
                     "text-sm"
                     "text-trading-text-secondary"]}
       "No vaults match current filters."])]
   (user-vault-pagination-controls pagination)])

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
                visible-user-rows
                user-pagination
                total-visible-tvl]} (vault-vm/vault-list-vm state)]
    [:div {:class ["relative" "w-full" "app-shell-gutter" "py-6"]
           :data-parity-id "vaults-root"}
     [:div {:class ["pointer-events-none"
                    "absolute"
                    "inset-x-0"
                    "top-0"
                    "h-[360px]"
                    "rounded-b-[24px]"
                    "opacity-95"]
            :style {:background-image "radial-gradient(120% 120% at 15% -10%, rgba(0, 148, 111, 0.35), rgba(6, 30, 34, 0.05) 60%), radial-gradient(130% 140% at 85% 20%, rgba(0, 138, 96, 0.22), rgba(6, 30, 34, 0) 68%), linear-gradient(180deg, rgba(4, 43, 36, 0.72) 0%, rgba(6, 27, 32, 0.15) 100%)"}}]

     [:div {:class ["relative" "mx-auto" "w-full" "max-w-[1280px]" "space-y-4"]}
      [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
       [:h1 {:class ["text-3xl" "font-normal" "text-trading-text" "sm:text-[48px]" "sm:leading-[52px]"]}
        "Vaults"]
       [:button {:type "button"
                 :disabled true
                 :class ["rounded-xl"
                         "bg-[#55e6ce]"
                         "px-5"
                         "py-2.5"
                         "text-sm"
                         "font-medium"
                         "text-[#043a33]"
                         "opacity-70"
                         "cursor-not-allowed"]}
        "Establish Connection"]]

      [:div {:class ["w-full" "max-w-[360px]" "rounded-2xl" "bg-[#0f1a1f]" "px-3" "py-3"]}
       [:div {:class ["text-sm" "font-normal" "text-trading-text-secondary"]}
        "Total Value Locked"]
       (if loading?
         [:div {:class ["mt-3" "h-10" "w-44" "rounded-md" "bg-base-300/70" "animate-pulse"]}
         [:div {:class ["sr-only"]} "Loading total value locked"]]
         [:div {:class ["mt-1" "num" "text-[44px]" "leading-[46px]" "font-normal" "text-trading-text"]}
          (format-total-currency total-visible-tvl)])]

      [:div {:class ["rounded-2xl" "border" "border-base-300/80" "bg-base-100/90" "p-3"]}
       [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
        [:input {:id "vaults-search-input"
                 :type "search"
                 :class ["h-9"
                         "min-w-[260px]"
                         "flex-1"
                         "rounded-xl"
                         "border"
                         "border-base-300"
                         "bg-base-100"
                         "px-3"
                         "text-xs"
                         "text-trading-text"
                         "placeholder:text-trading-text-secondary"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"]
                 :placeholder "Search by vault address, name or leader..."
                 :value query
                 :on {:input [[:actions/set-vaults-search-query [:event.target/value]]]}}]
        (role-filter-menu filters)
        (range-menu snapshot-range)]]

      (when error
        [:div {:class ["rounded-xl" "border" "border-red-500/40" "bg-red-900/20" "px-3" "py-2.5" "text-sm" "text-red-200"]}
         error])

      [:section {:class ["rounded-2xl" "border" "border-base-300/80" "bg-base-100/95" "p-3" "space-y-6"]}
       (section-table "Protocol Vaults" protocol-rows sort {:loading? loading?})
       (section-table "User Vaults" visible-user-rows sort {:loading? loading?
                                                            :pagination user-pagination})
       [:div {:class ["text-right" "text-xs" "text-trading-text-secondary"]}
        (str (count protocol-rows) " protocol vaults | " (count user-rows) " user vaults")]]]]))
