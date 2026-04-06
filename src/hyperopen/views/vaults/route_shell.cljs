(ns hyperopen.views.vaults.route-shell
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.wallet.core :as wallet]))

(def ^:private desktop-breakpoint-px
  1024)

(def ^:private loading-skeleton-row-count
  5)

(defn- viewport-width-px []
  (let [width (some-> js/globalThis .-innerWidth)]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn- desktop-vaults-layout? []
  (>= (viewport-width-px) desktop-breakpoint-px))

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

(defn- snapshot-range-label
  [snapshot-range]
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

(defn- preview-present?
  [preview]
  (boolean (or (seq (:protocol-rows preview))
               (seq (:user-rows preview)))))

(defn- normalize-series [series]
  (let [values (if (sequential? series)
                 (->> series
                      (keep #(when (number? %) %))
                      vec)
                 [])]
    (if (seq values)
      values
      [0 0])))

(def ^:private memoized-sparkline-model
  (memoize
   (fn [series width height]
     (let [values (normalize-series series)
           start-value (first values)
           end-value (last values)
           positive? (>= end-value start-value)
           stroke (if positive?
                    "#36e1d3"
                    "#ff6b8a")
           min-value (apply min values)
           max-value (apply max values)
           value-span (max 0.000001 (- max-value min-value))
           step-x (/ width (max 1 (dec (count values))))
           path (->> values
                     (map-indexed (fn [idx value]
                                    (let [x (* idx step-x)
                                          normalized (/ (- value min-value) value-span)
                                          y (* (- 1 normalized) height)]
                                      (str (if (zero? idx) "M" "L")
                                           (.toFixed x 2)
                                           ","
                                           (.toFixed y 2)))))
                     (str/join " "))]
       {:path path
        :stroke stroke}))))

(defn- snapshot-sparkline [series]
  (let [{:keys [path stroke]} (memoized-sparkline-model series 80 28)]
    [:svg {:class ["h-7" "w-20"]
           :viewBox "0 0 80 28"
           :preserveAspectRatio "none"
           :aria-label "Vault snapshot trend"}
     [:path {:d path
             :fill "none"
             :stroke stroke
             :stroke-width 2
             :stroke-linecap "round"
             :stroke-linejoin "round"
             :vector-effect "non-scaling-stroke"}]]))

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

(defn- preview-desktop-row
  [row]
  (let [{:keys [name
                vault-address
                leader
                apr
                tvl
                your-deposit
                age-days
                snapshot-series]} row]
    [:tr {:class ["border-b" "border-base-300/40"]
          :data-role "vault-route-shell-row"}
     [:td {:class ["px-3" "py-3"]}
      [:div {:class ["truncate" "font-semibold" "text-trading-text"]}
       (or name "Vault")]
      [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
       (wallet/short-addr vault-address)]]
     [:td {:class ["px-3" "py-3" "num" "text-trading-text-secondary"]}
      (wallet/short-addr leader)]
     [:td {:class (into ["px-3" "py-3" "num"] (percent-text-class apr))}
      (format-percent apr)]
     [:td {:class ["px-3" "py-3" "num" "text-trading-text"]}
      (format-currency tvl)]
     [:td {:class ["px-3" "py-3" "num" "text-trading-text"]}
      (format-currency your-deposit)]
     [:td {:class ["px-3" "py-3" "num" "text-trading-text-secondary"]}
      (format-age age-days)]
     [:td {:class ["px-3" "py-3" "text-right"]}
      [:div {:class ["ml-auto" "w-fit"]}
       (snapshot-sparkline snapshot-series)]]]))

(defn- preview-mobile-card
  [row]
  (let [{:keys [name
                vault-address
                leader
                apr
                tvl
                your-deposit
                age-days
                snapshot-series]} row]
    [:div {:class ["rounded-xl"
                   "border"
                   "border-base-300"
                   "bg-base-100"
                   "p-3"
                   "space-y-3"]
           :data-role "vault-route-shell-row"}
     [:div {:class ["flex" "items-start" "justify-between" "gap-3"]}
      [:div {:class ["min-w-0"]}
       [:div {:class ["truncate" "font-semibold" "text-trading-text"]}
        (or name "Vault")]
       [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
        (wallet/short-addr vault-address)]]
      [:div {:class ["num" "text-xs" "text-trading-text-secondary"]}
       (wallet/short-addr leader)]]
     [:div {:class ["grid" "grid-cols-2" "gap-2" "text-xs"]}
      [:div
       [:div {:class ["text-trading-text-secondary"]} "APR"]
       [:div {:class (into ["num"] (percent-text-class apr))}
        (format-percent apr)]]
      [:div
       [:div {:class ["text-trading-text-secondary"]} "TVL"]
       [:div {:class ["num" "text-trading-text"]}
        (format-currency tvl)]]
      [:div
       [:div {:class ["text-trading-text-secondary"]} "Your Deposit"]
       [:div {:class ["num" "text-trading-text"]}
        (format-currency your-deposit)]]
      [:div
       [:div {:class ["text-trading-text-secondary"]} "Age"]
       [:div {:class ["num" "text-trading-text-secondary"]}
        (format-age age-days)]]]
     [:div {:class ["pt-1"]}
      (snapshot-sparkline snapshot-series)]]))

(defn- preview-section
  [label rows desktop-layout?]
  [:section {:class ["space-y-2"]}
   [:h3 {:class ["text-sm" "font-normal" "text-trading-text"]} label]
   (if desktop-layout?
     [:div {:class ["overflow-x-auto"]}
      [:table {:class ["w-full" "border-collapse"]}
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
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Vault"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Leader"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "APR"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "TVL"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Your Deposit"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Age"]
         [:th {:class ["px-3" "py-2" "text-right" "text-xs" "font-normal" "text-trading-text-secondary"]} "Snapshot"]]]
       [:tbody
        (if (seq rows)
          (for [row rows]
            ^{:key (str "vault-route-shell-row-" (:vault-address row))}
            (preview-desktop-row row))
          [:tr
           [:td {:col-span 7
                 :class ["px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
            "No cached vaults available."]])]]]
     [:div {:class ["space-y-2"]}
      (if (seq rows)
        (for [row rows]
          ^{:key (str "vault-route-shell-row-" (:vault-address row))}
          (preview-mobile-card row))
        [:div {:class ["rounded-lg"
                       "border"
                       "border-base-300"
                       "bg-base-200/60"
                       "px-3"
                       "py-4"
                       "text-center"
                       "text-sm"
                       "text-trading-text-secondary"]}
         "No cached vaults available."])])])

(defn- loading-section
  [label desktop-layout?]
  [:section {:class ["space-y-2"]}
   [:h3 {:class ["text-sm" "font-normal" "text-trading-text"]} label]
   (if desktop-layout?
     [:div {:class ["overflow-x-auto"]}
      [:table {:class ["w-full" "border-collapse"]}
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
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Vault"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Leader"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "APR"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "TVL"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Your Deposit"]
         [:th {:class ["px-3" "py-2" "text-left" "text-xs" "font-normal" "text-trading-text-secondary"]} "Age"]
         [:th {:class ["px-3" "py-2" "text-right" "text-xs" "font-normal" "text-trading-text-secondary"]} "Snapshot"]]]
       [:tbody
        (for [idx (range loading-skeleton-row-count)]
          ^{:key (str "vault-loading-row-" label "-" idx)}
          (desktop-loading-row idx))]]]
     [:div {:class ["space-y-2"]}
      (for [idx (range loading-skeleton-row-count)]
        ^{:key (str "mobile-vault-loading-row-" label "-" idx)}
        (mobile-loading-card idx))])])

(defn vaults-route-loading-shell
  [state]
  (let [preview (get-in state [:vaults :startup-preview])
        preview-visible? (preview-present? preview)
        desktop-layout? (desktop-vaults-layout?)
        wallet-connected? (boolean (get-in state [:wallet :connected?]))
        wallet-connecting? (boolean (get-in state [:wallet :connecting?]))
        snapshot-range (get-in state [:vaults-ui :snapshot-range] :month)
        query (get-in state [:vaults-ui :search-query] "")
        protocol-rows (or (:protocol-rows preview) [])
        user-rows (or (:user-rows preview) [])
        total-visible-tvl (:total-visible-tvl preview)]
    [:div {:class ["flex-1" "bg-base-100"]
           :data-parity-id "app-route-module-shell"}
     [:div {:class ["relative" "w-full" "app-shell-gutter" "py-4" "md:py-6"]
            :data-role "vaults-route-loading-shell"
            :data-preview-state (when preview-visible? "startup-preview")}
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
                   :class ["inline-flex"
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
                   :disabled wallet-connecting?
                   :on {:click [[:actions/connect-wallet]]}
                   :data-role "vaults-route-connect"}
          (if wallet-connecting? "Connecting…" "Connect")])

       [:div {:class ["w-full" "max-w-[320px]" "rounded-xl" "bg-[#0f1a1f]" "px-3" "py-3" "md:max-w-[360px]" "md:rounded-2xl"]}
        [:div {:class ["text-sm" "font-normal" "text-trading-text-secondary"]}
         "Total Value Locked"]
        (if preview-visible?
          [:div {:class ["mt-1" "num" "text-[44px]" "leading-[46px]" "font-normal" "text-trading-text"]
                 :data-role "vaults-total-visible-tvl"}
           (format-total-currency total-visible-tvl)]
          [:div {:class ["mt-3" "h-10" "w-44" "rounded-md" "bg-base-300/70" "animate-pulse"]
                 :data-role "vaults-total-visible-tvl-loading"}
           [:div {:class ["sr-only"]} "Loading total value locked"]])]

       [:div {:class ["rounded-lg" "border" "border-base-300/80" "bg-base-100/90" "p-2.5" "md:rounded-2xl" "md:p-3"]}
        [:div {:class ["flex" "flex-wrap" "items-center" "gap-2"]}
         [:input {:id "vaults-search-input"
                  :type "search"
                  :class ["h-8"
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
                  :placeholder "Search by vault address, name or leader..."
                  :value query
                  :readOnly true
                  :disabled true}]
         [:div {:class ["flex"
                        "h-8"
                        "items-center"
                        "gap-1.5"
                        "rounded-lg"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "px-2.5"
                        "text-xs"
                        "text-trading-text-secondary"
                        "opacity-80"]}
          [:span {:class ["hidden" "sm:inline"]} "Filter"]
          [:span "All"]]
         [:div {:class ["flex"
                        "h-8"
                        "items-center"
                        "gap-1.5"
                        "rounded-lg"
                        "border"
                        "border-base-300"
                        "bg-base-100"
                        "px-2.5"
                        "text-xs"
                        "text-trading-text-secondary"
                        "opacity-80"]}
          [:span {:class ["hidden" "sm:inline"]} "Range"]
          [:span (snapshot-range-label snapshot-range)]]]]

       (when preview-visible?
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
                               (when-not preview-visible?
                                 ["min-h-[24rem]" "md:min-h-[36rem]"]))}
        (if preview-visible?
          (preview-section "Protocol Vaults" protocol-rows desktop-layout?)
          (loading-section "Protocol Vaults" desktop-layout?))
        (if preview-visible?
          (preview-section "User Vaults" user-rows desktop-layout?)
          (loading-section "User Vaults" desktop-layout?))
        [:div {:class ["text-right" "text-xs" "text-trading-text-secondary"]}
         (if preview-visible?
           (str (count protocol-rows) " protocol vaults | " (count user-rows) " user vaults")
           "Loading vaults…")]]]]]))
