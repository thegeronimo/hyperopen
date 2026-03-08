(ns hyperopen.views.account-info.tabs.balances
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.mobile-cards :as mobile-cards]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]))

(def ^:private unified-available-balance-tooltip-suffix
  " is available to withdraw or transfer. Some perps may have a larger available to trade amount, which can be seen in the order form for that asset.")

(def ^:private balance-contract-explorer-token-base-url
  "https://app.hyperliquid.xyz/explorer/token/")

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(defn- unified-available-balance-tooltip-text [coin available-balance amount-decimals]
  (str (shared/format-balance-amount available-balance amount-decimals)
       " "
       (or coin "USDC")
       unified-available-balance-tooltip-suffix))

(defn- available-balance-value-node [{:keys [coin
                                             available-balance
                                             amount-decimals
                                             transfer-disabled?
                                             tooltip-position]}]
  (let [value-text (shared/format-balance-amount available-balance amount-decimals)]
    (if transfer-disabled?
      (let [position (or tooltip-position :top)
            panel-position-classes (case position
                                     :bottom ["top-full" "mt-2"]
                                     ["bottom-full" "mb-2"])
            caret-position-classes (case position
                                     :bottom ["bottom-full" "border-b-gray-800"]
                                     ["top-full" "border-t-gray-800"])]
        [:div {:class ["group" "relative" "inline-flex" "min-h-6" "items-center" "justify-end"]}
       [:span {:class ["cursor-help"
                       "rounded"
                       "underline"
                       "decoration-dashed"
                       "underline-offset-2"
                       "focus-visible:outline-none"
                       "focus-visible:ring-2"
                       "focus-visible:ring-trading-green/70"
                       "focus-visible:ring-offset-1"
                       "focus-visible:ring-offset-base-100"]
               :tab-index 0}
        value-text]
       [:div {:class (into ["pointer-events-none"
                            "absolute"
                            "left-1/2"
                            "-translate-x-1/2"
                            "z-[120]"
                            "opacity-0"
                            "transition-opacity"
                            "duration-200"
                            "group-hover:opacity-100"
                            "group-focus-within:opacity-100"]
                           panel-position-classes)}
        [:div {:class ["relative"
                       "w-[520px]"
                       "max-w-[calc(100vw-2rem)]"
                       "min-w-[320px]"
                       "rounded-md"
                       "bg-gray-800"
                       "px-3"
                       "py-1.5"
                       "text-xs"
                       "leading-tight"
                       "text-left"
                       "text-gray-100"
                       "spectate-lg"
                       "whitespace-normal"]}
         (unified-available-balance-tooltip-text coin available-balance amount-decimals)
         [:div {:class (into ["absolute"
                              "left-1/2"
                              "-translate-x-1/2"
                              "h-0"
                              "w-0"
                              "border-4"
                              "border-transparent"]
                             caret-position-classes)}]]]])
      value-text)))

(defn- external-link-icon
  ([] (external-link-icon ["h-3" "w-3" "shrink-0"]))
  ([class-names]
   [:svg {:class class-names
          :viewBox "0 0 20 20"
          :fill "none"
          :stroke "currentColor"
          :stroke-width "1.8"
          :aria-hidden true}
    [:path {:d "M8 4h8v8"}]
    [:path {:d "M16 4 7 13"}]
    [:path {:d "M14 10v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"}]]))

(defn- normalize-balance-contract-id [contract-id]
  (projections/normalize-balance-contract-id contract-id))

(defn- abbreviate-contract-id [contract-id]
  (when-let [contract-id* (normalize-balance-contract-id contract-id)]
    (if (> (count contract-id*) 10)
      (let [prefix-len (if (str/starts-with? contract-id* "0x") 6 4)
            safe-prefix-len (min prefix-len (count contract-id*))]
        (str (subs contract-id* 0 safe-prefix-len)
             "..."
             (subs contract-id* (- (count contract-id*) 4))))
      contract-id*)))

(defn- balance-contract-explorer-url [contract-id]
  (when-let [contract-id* (normalize-balance-contract-id contract-id)]
    (str balance-contract-explorer-token-base-url contract-id*)))

(defn- balance-contract-node [contract-id]
  (let [display-contract-id (abbreviate-contract-id contract-id)]
    (when-let [explorer-url (balance-contract-explorer-url contract-id)]
      [:a {:href explorer-url
           :target "_blank"
           :rel "noopener noreferrer"
           :class ["inline-flex"
                   "min-h-6"
                   "items-center"
                   "gap-0.5"
                   "whitespace-nowrap"
                   "rounded"
                   "text-trading-text"
                   "hover:text-trading-text/80"
                   "focus-visible:outline-none"
                   "focus-visible:ring-2"
                   "focus-visible:ring-trading-green/70"
                   "focus-visible:ring-offset-1"
                   "focus-visible:ring-offset-base-100"]}
       [:span display-contract-id]
       (external-link-icon ["h-3" "w-3" "shrink-0" "text-trading-green"])])))

(defn build-balance-rows [webdata2 spot-data]
  (projections/build-balance-rows webdata2 spot-data nil))

(defn build-balance-rows-for-account [webdata2 spot-data account]
  (projections/build-balance-rows webdata2 spot-data account))

(defn- usdc-balance-row? [row]
  (str/starts-with? (or (:coin row) "") "USDC"))

(defn- balance-sort-value [column row]
  (case column
    "Coin" (or (:coin row) "")
    "Total Balance" (shared/parse-num (:total-balance row))
    "Available Balance" (shared/parse-num (:available-balance row))
    "USDC Value" (shared/parse-num (:usdc-value row))
    "PNL (ROE %)" (shared/parse-num (:pnl-value row))
    0))

(defn- compare-balance-rows [column direction row-a row-b]
  (let [value-a (balance-sort-value column row-a)
        value-b (balance-sort-value column row-b)
        primary-cmp (if (= direction :desc)
                      (compare value-b value-a)
                      (compare value-a value-b))]
    (if (zero? primary-cmp)
      (let [coin-cmp (compare (or (:coin row-a) "")
                              (or (:coin row-b) ""))]
        (if (zero? coin-cmp)
          (compare (or (:key row-a) "")
                   (or (:key row-b) ""))
          coin-cmp))
      primary-cmp)))

(defn sort-balances-by-column [rows column direction]
  (let [[usdc-rows non-usdc-rows]
        (reduce (fn [[usdc* non-usdc*] row]
                  (if (usdc-balance-row? row)
                    [(conj usdc* row) non-usdc*]
                    [usdc* (conj non-usdc* row)]))
                [[] []]
                rows)
        compare-rows (partial compare-balance-rows column direction)]
    (->> (concat (sort compare-rows usdc-rows)
                 (sort compare-rows non-usdc-rows))
         vec)))

(defn- balance-matches-coin-search?
  [row query]
  (let [coin (:coin row)
        selection-coin (:selection-coin row)
        base-selection-coin (some-> selection-coin
                                    shared/parse-coin-namespace
                                    :base)]
    (or (shared/coin-matches-search? coin query)
        (shared/coin-matches-search? selection-coin query)
        (shared/coin-matches-search? base-selection-coin query))))

(defn- filter-balances-by-coin-search
  [rows coin-search]
  (let [rows* (or rows [])
        query (shared/normalize-coin-search-query coin-search)]
    (if (str/blank? query)
      (vec rows*)
      (->> rows*
           (filterv #(balance-matches-coin-search? % query))))))

(defn sortable-balances-header
  ([column-name sort-state]
   (sortable-balances-header column-name sort-state :left))
  ([column-name sort-state align]
   (table/sortable-header-button column-name
                                 sort-state
                                 :actions/sort-balances
                                 {:full-width? true
                                  :extra-classes (table/header-alignment-classes align)})))

(def ^:private balance-row-action-button-classes
  ["inline-flex"
   "min-h-6"
   "w-full"
   "justify-start"
   "appearance-none"
   "border-0"
   "bg-transparent"
   "p-0"
   "font-medium"
   "text-trading-text"
   "transition-colors"
   "hover:bg-transparent"
   "hover:text-[#7fffe4]"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus:shadow-none"
   "focus-visible:outline-none"
   "focus-visible:ring-0"
   "focus-visible:ring-offset-0"
   "focus-visible:text-[#7fffe4]"
   "focus-visible:underline"
   "underline-offset-2"
   "whitespace-nowrap"])

(defn- balance-row-action-button [label]
  [:button {:class balance-row-action-button-classes
            :type "button"}
   label])

(defn balance-row [{:keys [coin
                           selection-coin
                           total-balance
                           available-balance
                           usdc-value
                           pnl-value
                           pnl-pct
                           amount-decimals
                           contract-id
                           transfer-disabled?
                           available-balance-tooltip-position]}]
  (let [coin-style (when-not (usdc-balance-row? {:coin coin})
                     {:color "rgb(151, 252, 228)"})
        selectable-coin (or selection-coin coin)]
    [:div.grid.grid-cols-8.gap-2.py-px.px-3.hover:bg-base-300.items-center.text-sm.text-trading-text
     (shared/coin-select-control selectable-coin
                                 (or coin "")
                                 {:style coin-style
                                  :extra-classes ["w-full"
                                                  "justify-start"
                                                  "text-left"
                                                  "font-semibold"
                                                  "truncate"]})
     [:div.text-right.font-semibold.num.num-right (shared/format-balance-amount total-balance amount-decimals)]
     [:div.text-right.font-semibold.num.num-right
      (available-balance-value-node {:coin coin
                                     :available-balance available-balance
                                     :amount-decimals amount-decimals
                                     :transfer-disabled? transfer-disabled?
                                     :tooltip-position available-balance-tooltip-position})]
     [:div.text-right.font-semibold.num.num-right "$" (shared/format-currency usdc-value)]
     [:div.text-right.font-medium.num.num-right (shared/format-pnl pnl-value pnl-pct)]
     [:div.text-left
      (balance-row-action-button "Send")]
     [:div.text-left
      (if transfer-disabled?
        [:span {:class ["text-xs" "text-trading-text-secondary"]} "Unified"]
        (balance-row-action-button "Transfer"))]
     [:div.text-left
      (balance-contract-node contract-id)]]))

(defn balance-table-header
  ([sort-state]
   (balance-table-header sort-state []))
  ([sort-state extra-classes]
   [:div {:class (into ["grid"
                        "grid-cols-8"
                        "gap-2"
                        "py-1"
                        "px-3"
                        "bg-base-200"
                        "text-sm"
                        "font-medium"
                        "text-trading-text"]
                       extra-classes)}
    [:div (sortable-balances-header "Coin" sort-state :left)]
    [:div (sortable-balances-header "Total Balance" sort-state :right)]
    [:div (sortable-balances-header "Available Balance" sort-state :right)]
    [:div (sortable-balances-header "USDC Value" sort-state :right)]
    [:div (sortable-balances-header "PNL (ROE %)" sort-state :right)]
    [:div (table/non-sortable-header "Send" :left)]
    [:div (table/non-sortable-header "Transfer" :left)]
    [:div (table/non-sortable-header "Contract" :left)]]))

(defn- mobile-balance-coin-node [{:keys [coin selection-coin]}]
  (let [{:keys [base-label prefix-label]}
        (shared/resolve-coin-display (or selection-coin coin) {})]
    [:span {:class ["flex" "min-w-0" "items-center" "gap-1.5"]}
     [:span {:class ["truncate" "text-trading-text"]} (or base-label coin "Asset")]
     (when prefix-label
       [:span {:class shared/position-chip-classes} prefix-label])]))

(defn- mobile-balance-action-chip [label]
  [:span {:class ["inline-flex"
                  "items-center"
                  "rounded-full"
                  "border"
                  "border-base-300"
                  "bg-base-100/70"
                  "px-2.5"
                  "py-1"
                  "text-xs"
                  "font-medium"
                  "leading-none"
                  "text-trading-text"]}
   label])

(defn- mobile-balance-card [expanded-row-id row]
  (let [{:keys [coin
                key
                selection-coin
                total-balance
                available-balance
                usdc-value
                pnl-value
                pnl-pct
                amount-decimals
                contract-id
                transfer-disabled?
                available-balance-tooltip-position]} row
        row-id (some-> key str str/trim)
        {:keys [base-label]} (shared/resolve-coin-display (or selection-coin coin) {})
        expanded? (= expanded-row-id row-id)
        transfer-label (if transfer-disabled? "Unified" "Transfer")]
    (mobile-cards/expandable-card
     {:data-role (str "mobile-balance-card-" row-id)
      :expanded? expanded?
      :toggle-actions [[:actions/toggle-account-info-mobile-card :balances row-id]]
      :summary-items [(mobile-cards/summary-item "Coin"
                                                 (mobile-balance-coin-node row)
                                                 {:value-classes ["text-trading-text"]})
                      (mobile-cards/summary-item "USDC Value"
                                                 (str "$" (shared/format-currency usdc-value))
                                                 {:value-classes ["num"]})
                      (mobile-cards/summary-item "Total Balance"
                                                 (str (shared/format-balance-amount total-balance amount-decimals)
                                                      " "
                                                      (or base-label coin ""))
                                                 {:value-classes ["num"]})]
      :detail-content (mobile-cards/detail-grid
                       "grid-cols-2"
                       [(mobile-cards/detail-item
                         "Available Balance"
                         (available-balance-value-node {:coin coin
                                                        :available-balance available-balance
                                                        :amount-decimals amount-decimals
                                                        :transfer-disabled? transfer-disabled?
                                                        :tooltip-position available-balance-tooltip-position})
                         {:value-classes ["num"]})
                        (mobile-cards/detail-item
                         "PNL (ROE %)"
                         (shared/format-pnl pnl-value pnl-pct))
                        (mobile-cards/detail-item
                         "Actions"
                         [:div {:class ["flex" "flex-wrap" "gap-2"]}
                          (mobile-balance-action-chip "Send")
                          (mobile-balance-action-chip transfer-label)]
                         {:full-width? true})
                        (when-let [contract-node (balance-contract-node contract-id)]
                          (mobile-cards/detail-item "Contract"
                                                    contract-node
                                                    {:full-width? true}))])})))

(defn balances-tab-content
  ([balance-rows hide-small? sort-state]
   (balances-tab-content balance-rows hide-small? sort-state "" {}))
  ([balance-rows hide-small? sort-state coin-search]
   (balances-tab-content balance-rows hide-small? sort-state coin-search {}))
  ([balance-rows hide-small? sort-state coin-search mobile-expanded-card]
   (let [rows* (or balance-rows [])
         visible-rows (if hide-small?
                        (filter (fn [row]
                                  (>= (shared/parse-num (:usdc-value row)) 1))
                                rows*)
                        rows*)
         search-filtered-rows (filter-balances-by-coin-search visible-rows coin-search)
         sorted-rows (if (:column sort-state)
                       (sort-balances-by-column search-filtered-rows
                                                (:column sort-state)
                                                (:direction sort-state))
                       search-filtered-rows)
         expanded-row-id (:balances mobile-expanded-card)]
     (if (seq search-filtered-rows)
       [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
        (balance-table-header sort-state ["hidden" "lg:grid"])
        (into [:div {:class ["hidden"
                             "lg:block"
                             "flex-1"
                             "min-h-0"
                             "overflow-y-auto"
                             "scrollbar-hide"]
                    :data-role "account-tab-rows-viewport"}]
              (map-indexed (fn [idx row]
                             (let [tooltip-position (if (zero? idx) :bottom :top)]
                               ^{:key (:key row)}
                               (balance-row (assoc row :available-balance-tooltip-position tooltip-position))))
                           sorted-rows))
        (into [:div {:class ["lg:hidden"
                             "flex-1"
                             "min-h-0"
                             "overflow-y-auto"
                             "scrollbar-hide"
                             "space-y-2.5"
                             "px-2.5"
                             "py-2"]
                    :data-role "balances-mobile-cards-viewport"}]
              (map-indexed (fn [idx row]
                             (let [tooltip-position (if (zero? idx) :bottom :top)]
                               ^{:key (str "mobile-" (:key row))}
                               (mobile-balance-card expanded-row-id
                                                    (assoc row :available-balance-tooltip-position tooltip-position))))
                           sorted-rows))]
       (empty-state "No balance data available")))))
