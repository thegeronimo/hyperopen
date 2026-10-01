(ns hyperopen.views.account-info.tabs.trade-history
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.history-pagination :as history-pagination]
            [hyperopen.views.account-info.mobile-cards :as mobile-cards]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.views.account-info.tabs.trade-history.shared :as trade-shared]
            [hyperopen.utils.formatting :as fmt]))

(def default-trade-history-sort trade-shared/default-trade-history-sort)
(def trade-history-direction-filter-options trade-shared/trade-history-direction-filter-options)
(def trade-history-direction-filter-labels trade-shared/trade-history-direction-filter-labels)
(def trade-history-direction-filter-key trade-shared/trade-history-direction-filter-key)
(def trade-history-sort-state trade-shared/trade-history-sort-state)
(def sort-trade-history-by-column trade-shared/sort-trade-history-by-column)
(def reset-trade-history-sort-cache! trade-shared/reset-trade-history-sort-cache!)

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

(def ^:private trade-history-explorer-tx-base-url
  "https://app.hyperliquid.xyz/explorer/tx/")

(def ^:private trade-history-tx-hash-pattern
  #"^0x[0-9a-fA-F]{64}$")

(defn- trade-history-tx-hash [row]
  (shared/non-blank-text (or (:hash row)
                              (:txHash row)
                              (:tx-hash row))))

(defn- valid-trade-history-tx-hash? [hash-value]
  (boolean (and hash-value
                (re-matches trade-history-tx-hash-pattern hash-value))))

(defn- trade-history-explorer-tx-url [row]
  (let [hash-value (trade-history-tx-hash row)]
    (when (valid-trade-history-tx-hash? hash-value)
      (str trade-history-explorer-tx-base-url hash-value))))

(defn- trade-history-time-node [row]
  (let [formatted-time (shared/format-open-orders-time (trade-shared/trade-history-time-ms row))]
    (if-let [explorer-url (trade-history-explorer-tx-url row)]
      [:a {:href explorer-url
           :target "_blank"
           :rel "noopener noreferrer"
           :class ["inline-flex"
                   "min-h-6"
                   "items-center"
                   "gap-0.5"
                   "whitespace-nowrap"
                   "rounded"
                   "text-trading-green"
                   "hover:text-trading-green/80"
                   "focus-visible:outline-none"
                   "focus-visible:ring-2"
                   "focus-visible:ring-trading-green/70"
                   "focus-visible:ring-offset-1"
                   "focus-visible:ring-offset-base-100"]}
       [:span formatted-time]
       (shared/external-link-icon ["h-3" "w-3" "shrink-0"] {:stroke-width 2})]
      formatted-time)))

(defn- format-usdc-amount [value]
  (if-let [num (shared/parse-optional-num value)]
    (str (or (fmt/format-fixed-number num 2)
             "0.00")
         " USDC")
    "--"))

(defn- trade-history-direction-node [row]
  (let [direction-label (trade-shared/trade-history-direction-label row)
        direction-class (trade-shared/trade-history-action-class row direction-label)]
    (if (trade-shared/trade-history-price-improved? row direction-label)
      [:div {:class ["text-left" direction-class]}
       [:div {:class ["group" "relative" "inline-flex" "min-h-6" "items-center"]}
        [:span {:class ["cursor-help"
                        "rounded"
                        "underline"
                        "decoration-dotted"
                        "underline-offset-2"
                        "focus-visible:outline-none"
                        "focus-visible:ring-2"
                        "focus-visible:ring-trading-green/70"
                        "focus-visible:ring-offset-1"
                        "focus-visible:ring-offset-base-100"]
                :tabindex 0}
         direction-label]
        [:div {:class ["pointer-events-none"
                       "absolute"
                       "left-0"
                       "bottom-full"
                       "z-50"
                       "mb-2"
                       "opacity-0"
                       "transition-opacity"
                       "duration-200"
                       "group-hover:opacity-100"
                       "group-focus-within:opacity-100"]}
         [:div {:class ["relative"
                        "w-[520px]"
                        "max-w-[calc(100vw-2rem)]"
                        "min-w-[380px]"
                        "rounded-md"
                        "bg-gray-800"
                        "px-3"
                        "py-1.5"
                        "text-xs"
                        "leading-tight"
                        "text-gray-100"
                        "spectate-lg"
                        "whitespace-normal"]}
          trade-shared/trade-history-price-improved-tooltip-text
          [:div {:class ["absolute"
                         "top-full"
                         "left-3"
                         "h-0"
                         "w-0"
                         "border-4"
                         "border-transparent"
                         "border-t-gray-800"]}]]]]]
      [:div {:class ["text-left" direction-class]}
       direction-label])))

(defn- trade-history-coin-node [row market-by-key]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display (trade-shared/trade-history-coin row)
                                                                        market-by-key)
        direction-label (trade-shared/trade-history-direction-label row)
        direction-class (trade-shared/trade-history-action-class row direction-label)]
    (shared/coin-select-control
     (trade-shared/trade-history-coin row)
     [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
      [:span {:class (cond-> ["whitespace-nowrap"]
                       direction-class
                       (conj direction-class))}
       base-label]
      (when prefix-label
        [:span {:class shared/position-chip-classes} prefix-label])]
     {:extra-classes ["w-full" "justify-start" "text-left"]})))

(def ^:private trade-history-grid-template-class
  "grid-cols-[minmax(180px,1.45fr)_minmax(90px,1.05fr)_minmax(160px,1.2fr)_minmax(90px,0.8fr)_minmax(130px,1.1fr)_minmax(130px,1.05fr)_minmax(110px,0.9fr)_minmax(120px,1fr)]")

(defn- format-trade-history-price [row]
  (let [price (or (:px row) (:price row) (:p row))]
    (if-let [num (shared/parse-optional-num price)]
      (or (fmt/format-trade-price-plain num price) "--")
      "--")))

(defn- format-order-history-size [value]
  (if-let [num (shared/parse-optional-num value)]
    (or (fmt/format-intl-number num
                                {:minimumFractionDigits 0
                                 :maximumFractionDigits 6})
        "--")
    "--"))

(defn- format-trade-history-size [row market-by-key]
  (let [size-raw (or (:sz row) (:size row) (:s row))
        size-text (if-let [size-string (shared/non-blank-text size-raw)]
                    size-string
                    (format-order-history-size size-raw))
        {:keys [base-label]} (shared/resolve-coin-display (trade-shared/trade-history-coin row) market-by-key)]
    (if (= size-text "--")
      "--"
      (str size-text " " base-label))))

(defn- format-trade-history-value [row]
  (format-usdc-amount (trade-shared/trade-history-value-number row)))

(defn- format-trade-history-fee [row]
  (format-usdc-amount (projections/trade-history-fee-number row)))

(defn- format-trade-history-closed-pnl [row]
  (format-usdc-amount (projections/trade-history-closed-pnl-number row)))

(defn sortable-trade-history-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-trade-history))

(defn- trade-history-table-header
  ([sort-state]
   (trade-history-table-header sort-state []))
  ([sort-state extra-classes]
   [:div {:class (into ["grid"
                        "gap-2"
                        "py-1"
                        "px-3"
                        "bg-base-200"
                        "text-sm"
                        "font-medium"
                        "text-trading-text-secondary"
                        trade-history-grid-template-class]
                       extra-classes)}
    [:div {:class ["text-left"]} (sortable-trade-history-header "Time" sort-state)]
    [:div {:class ["text-left"]} (sortable-trade-history-header "Coin" sort-state)]
    [:div {:class ["text-left"]} (sortable-trade-history-header "Direction" sort-state)]
    [:div {:class ["text-left"]} (sortable-trade-history-header "Price" sort-state)]
    [:div {:class ["text-left"]} (sortable-trade-history-header "Size" sort-state)]
    [:div {:class ["text-left"]} (sortable-trade-history-header "Trade Value" sort-state)]
    [:div {:class ["text-left"]} (sortable-trade-history-header "Fee" sort-state)]
    [:div {:class ["text-left"]} (sortable-trade-history-header "Closed PNL" sort-state)]]))

(defn- trade-history-table-row [row market-by-key]
  [:div {:class ["account-table-row"
                 "grid"
                 "gap-2"
                 "py-px"
                 "px-3"
                 "hover:bg-base-300"
                 "text-sm"
                 trade-history-grid-template-class]}
   [:div {:class ["text-left" "text-xs" "whitespace-nowrap"]}
    (trade-history-time-node row)]
   [:div {:class ["text-left"]}
    (trade-history-coin-node row market-by-key)]
   (trade-history-direction-node row)
   [:div {:class ["text-left" "num" "whitespace-nowrap"]}
    (format-trade-history-price row)]
   [:div {:class ["text-left" "num" "whitespace-nowrap"]}
    (format-trade-history-size row market-by-key)]
   [:div {:class ["text-left" "num" "whitespace-nowrap"]}
    (format-trade-history-value row)]
   [:div {:class ["text-left" "num" "whitespace-nowrap"]}
    (format-trade-history-fee row)]
   [:div {:class ["text-left" "num" "whitespace-nowrap" (trade-shared/trade-history-closed-pnl-class row)]}
    (format-trade-history-closed-pnl row)]])

(defn- mobile-trade-history-coin-node [row market-by-key]
  (let [{:keys [base-label prefix-label]}
        (shared/resolve-coin-display (trade-shared/trade-history-coin row) market-by-key)]
    [:span {:class ["flex" "min-w-0" "items-center" "gap-1"]}
     [:span {:class ["truncate" "font-medium" "leading-4" "text-trading-text"]} base-label]
     (when prefix-label
       [:span {:class shared/position-chip-classes}
        prefix-label])]))

(defn- mobile-trade-history-time-node [row]
  (let [formatted-time (shared/format-open-orders-time (trade-shared/trade-history-time-ms row))
        explorer-url (trade-history-explorer-tx-url row)
        [date-part time-part] (str/split formatted-time #" - " 2)
        container-children (if time-part
                             [[:span (str date-part " -")]
                              [:span {:class ["inline-flex" "items-center" "gap-1"]}
                               time-part
                               (when explorer-url
                                 (shared/external-link-icon ["h-3" "w-3" "shrink-0" "text-trading-green"]
                                                            {:stroke-width 2}))]]
                             [[:span {:class ["inline-flex" "items-center" "gap-1"]}
                               formatted-time
                               (when explorer-url
                                 (shared/external-link-icon ["h-3" "w-3" "shrink-0" "text-trading-green"]
                                                            {:stroke-width 2}))]])]
    (into [(if explorer-url :a :div)
           (cond-> {:class ["inline-flex"
                            "min-w-0"
                            "flex-col"
                            "items-start"
                            "gap-0.5"
                            "rounded"
                            "text-trading-text"
                            "focus-visible:outline-none"
                            "focus-visible:ring-2"
                            "focus-visible:ring-trading-green/70"
                            "focus-visible:ring-offset-1"
                            "focus-visible:ring-offset-base-100"]}
             explorer-url
             (assoc :href explorer-url
                    :target "_blank"
                    :rel "noopener noreferrer"))]
          container-children)))

(defn- mobile-trade-history-closed-pnl-node [row]
  (let [formatted-pnl (format-trade-history-closed-pnl row)
        pnl-class (trade-shared/trade-history-closed-pnl-class row)]
    (if-let [explorer-url (trade-history-explorer-tx-url row)]
      [:a {:href explorer-url
           :target "_blank"
           :rel "noopener noreferrer"
           :class ["inline-flex"
                   "min-h-6"
                   "items-center"
                   "gap-1"
                   "rounded"
                   pnl-class
                   "focus-visible:outline-none"
                   "focus-visible:ring-2"
                   "focus-visible:ring-trading-green/70"
                   "focus-visible:ring-offset-1"
                   "focus-visible:ring-offset-base-100"]}
       [:span {:class ["num"]} formatted-pnl]
       (shared/external-link-icon ["h-3" "w-3" "shrink-0" "text-trading-green"]
                                  {:stroke-width 2})]
      [:span {:class ["num" pnl-class]} formatted-pnl])))

(defn- mobile-trade-history-card [expanded-row-id row market-by-key]
  (let [row-id (some-> (trade-shared/trade-history-row-id row) str str/trim)
        expanded? (= expanded-row-id row-id)]
    (mobile-cards/expandable-card
     {:data-role (str "mobile-trade-history-card-" row-id)
      :expanded? expanded?
      :toggle-actions [[:actions/toggle-account-info-mobile-card :trade-history row-id]]
      :summary-grid-classes ["grid"
                             "grid-cols-[minmax(0,1fr)_minmax(0,0.95fr)_minmax(0,0.72fr)_auto]"
                             "items-start"
                             "gap-x-3"
                             "gap-y-2"]
      :summary-items [(mobile-cards/summary-item "Coin"
                                                 (mobile-trade-history-coin-node row market-by-key)
                                                 {:value-classes ["font-medium"
                                                                  "leading-4"
                                                                  "text-trading-text"]})
                      (mobile-cards/summary-item "Direction"
                                                 (trade-history-direction-node row)
                                                 {:value-classes ["font-medium"
                                                                  "leading-4"
                                                                  "text-trading-text"]})
                      (mobile-cards/summary-item "Price"
                                                 (format-trade-history-price row)
                                                 {:value-classes ["num"
                                                                  "font-medium"
                                                                  "leading-4"
                                                                  "whitespace-nowrap"]})]
      :detail-content (mobile-cards/detail-grid
                       "grid-cols-3"
                       [(mobile-cards/detail-item "Time"
                                                  (mobile-trade-history-time-node row)
                                                  {:value-classes ["font-medium" "text-trading-text"]})
                        (mobile-cards/detail-item "Size"
                                                  (format-trade-history-size row market-by-key)
                                                  {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
                        (mobile-cards/detail-item "Trade Value"
                                                  (format-trade-history-value row)
                                                  {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
                        (mobile-cards/detail-item "Closed PNL"
                                                  (mobile-trade-history-closed-pnl-node row)
                                                  {:value-classes ["font-medium"]})
                        (mobile-cards/detail-item "Fee"
                                                  (format-trade-history-fee row)
                                                  {:value-classes ["num" "font-medium" "whitespace-nowrap"]})])})))

(defn trade-history-table [fills trade-history-state]
  (let [all-rows (cond
                   (vector? fills) fills
                   (seq fills) (vec fills)
                   :else [])
        market-by-key (or (:market-by-key trade-history-state) {})
        direction-filter (trade-history-direction-filter-key trade-history-state)
        coin-search (:coin-search trade-history-state "")
        sort-state (trade-history-sort-state trade-history-state)
        mobile-expanded-card-id (get-in trade-history-state [:mobile-expanded-card :trade-history])
        sorted-rows (trade-shared/memoized-sorted-trade-history all-rows
                                                                direction-filter
                                                                sort-state
                                                                market-by-key
                                                                coin-search)
        {:keys [rows] :as pagination} (history-pagination/paginate-history-rows sorted-rows trade-history-state)]
    (if (seq sorted-rows)
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
       (trade-history-table-header sort-state ["hidden" "lg:grid"])
       (into [:div {:class ["hidden"
                            "lg:block"
                            "flex-1"
                            "min-h-0"
                            "min-w-0"
                            "overflow-auto"
                            "scrollbar-hide"]
                   :data-role "account-tab-rows-viewport"}]
             (map (fn [row]
                    ^{:key (trade-shared/trade-history-row-id row)}
                    (trade-history-table-row row market-by-key))
                  rows))
       [:div {:class ["hidden" "lg:block"]}
        (history-pagination/trade-history-pagination-controls pagination)]
       (into [:div {:class ["lg:hidden"
                            "flex-1"
                            "min-h-0"
                            "overflow-y-auto"
                            "scrollbar-hide"
                            "space-y-2.5"
                            "px-2.5"
                            "py-2"]
                   :data-role "trade-history-mobile-cards-viewport"}]
             (concat
              (map (fn [row]
                     ^{:key (str "mobile-" (trade-shared/trade-history-row-id row))}
                     (mobile-trade-history-card mobile-expanded-card-id row market-by-key))
                   rows)
              [[:div {:class ["px-1" "pt-1"]}
                (history-pagination/trade-history-pagination-controls pagination)]]))]
      (empty-state "No fills"))))

(defn trade-history-tab-content
  ([fills]
   (trade-history-table fills {}))
  ([fills trade-history-state]
   (trade-history-table fills trade-history-state)))
