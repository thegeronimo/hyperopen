(ns hyperopen.views.account-info.tabs.balances
  (:require [clojure.string :as str]
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

(defn- available-balance-value-node [{:keys [coin available-balance amount-decimals transfer-disabled?]}]
  (let [value-text (shared/format-balance-amount available-balance amount-decimals)]
    (if transfer-disabled?
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
       [:div {:class ["pointer-events-none"
                      "absolute"
                      "right-0"
                      "bottom-full"
                      "z-50"
                      "mb-2"
                      "opacity-0"
                      "transition-opacity"
                      "duration-200"
                      "group-hover:opacity-100"
                      "group-focus-within:opacity-100"]}
        [:div {:class ["relative"
                       "w-[420px]"
                       "max-w-[calc(100vw-2rem)]"
                       "min-w-[280px]"
                       "rounded-md"
                       "bg-gray-800"
                       "px-3"
                       "py-1.5"
                       "text-xs"
                       "leading-tight"
                       "text-gray-100"
                       "shadow-lg"
                       "whitespace-normal"]}
         (unified-available-balance-tooltip-text coin available-balance amount-decimals)
         [:div {:class ["absolute"
                        "top-full"
                        "right-3"
                        "h-0"
                        "w-0"
                        "border-4"
                        "border-transparent"
                        "border-t-gray-800"]}]]]]
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

(defn sortable-balances-header
  ([column-name sort-state]
   (sortable-balances-header column-name sort-state :left))
  ([column-name sort-state align]
   (table/sortable-header-button column-name
                                 sort-state
                                 :actions/sort-balances
                                 {:full-width? true
                                  :extra-classes (table/header-alignment-classes align)})))

(defn balance-row [{:keys [coin
                           total-balance
                           available-balance
                           usdc-value
                           pnl-value
                           pnl-pct
                           amount-decimals
                           contract-id
                           transfer-disabled?]}]
  (let [coin-attrs (when-not (usdc-balance-row? {:coin coin})
                     {:style {:color "rgb(151, 252, 228)"}})]
    [:div.grid.grid-cols-8.gap-2.py-px.px-3.hover:bg-base-300.items-center.text-sm.text-trading-text
     (if coin-attrs
       [:div.font-semibold coin-attrs coin]
       [:div.font-semibold coin])
     [:div.text-right.font-semibold.num.num-right (shared/format-balance-amount total-balance amount-decimals)]
     [:div.text-right.font-semibold.num.num-right
      (available-balance-value-node {:coin coin
                                     :available-balance available-balance
                                     :amount-decimals amount-decimals
                                     :transfer-disabled? transfer-disabled?})]
     [:div.text-right.font-semibold.num.num-right "$" (shared/format-currency usdc-value)]
     [:div.text-right.font-semibold.num.num-right (shared/format-pnl pnl-value pnl-pct)]
     [:div.text-left
      [:button {:class ["btn" "btn-xs" "btn-ghost" "text-trading-text"]} "Send"]]
     [:div.text-left
      (if transfer-disabled?
        [:span {:class ["text-xs" "text-trading-text-secondary"]} "Unified"]
        [:button {:class ["btn" "btn-xs" "btn-ghost" "text-trading-text"]} "Transfer"])]
     [:div.text-left
      (balance-contract-node contract-id)]]))

(defn balance-table-header [sort-state]
  [:div.grid.grid-cols-8.gap-2.py-1.px-3.bg-base-200.text-sm.font-medium.text-trading-text
   [:div (sortable-balances-header "Coin" sort-state :left)]
   [:div (sortable-balances-header "Total Balance" sort-state :right)]
   [:div (sortable-balances-header "Available Balance" sort-state :right)]
   [:div (sortable-balances-header "USDC Value" sort-state :right)]
   [:div (sortable-balances-header "PNL (ROE %)" sort-state :right)]
   [:div (table/non-sortable-header "Send" :left)]
   [:div (table/non-sortable-header "Transfer" :left)]
   [:div (table/non-sortable-header "Contract" :left)]])

(defn balances-tab-content [balance-rows hide-small? sort-state]
  (let [visible-rows (if hide-small?
                       (filter (fn [row]
                                 (>= (shared/parse-num (:usdc-value row)) 1))
                               balance-rows)
                       balance-rows)
        sorted-rows (if (:column sort-state)
                      (sort-balances-by-column visible-rows
                                               (:column sort-state)
                                               (:direction sort-state))
                      visible-rows)]
    (if (seq visible-rows)
      (table/tab-table-content (balance-table-header sort-state)
                               (for [row sorted-rows]
                                 ^{:key (:key row)}
                                 (balance-row row)))
      (empty-state "No balance data available"))))
