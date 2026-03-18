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

(def ^:private balances-desktop-grid-template-class
  "grid-cols-[minmax(88px,0.84fr)_minmax(96px,0.8fr)_minmax(140px,1.16fr)_minmax(92px,0.76fr)_minmax(180px,1.58fr)_minmax(56px,0.5fr)_minmax(104px,0.88fr)_minmax(40px,0.22fr)_minmax(112px,0.9fr)]")

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
                                             unit-label
                                             available-balance
                                             amount-decimals
                                             transfer-disabled?
                                             tooltip-position]}]
  (let [display-unit-label (or unit-label coin "USDC")
        value-text (str (shared/format-balance-amount available-balance amount-decimals)
                        " "
                        display-unit-label)]
    (if transfer-disabled?
      (let [position (or tooltip-position :top)
            panel-position-classes (case position
                                     :bottom ["top-full" "mt-2"]
                                     ["bottom-full" "mb-2"])
            caret-position-classes (case position
                                     :bottom ["bottom-full" "border-b-gray-800"]
                                     ["top-full" "border-t-gray-800"])]
        [:div {:class ["group" "relative" "inline-flex" "min-h-6" "items-center" "justify-start"]}
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
         (unified-available-balance-tooltip-text display-unit-label available-balance amount-decimals)
         [:div {:class (into ["absolute"
                              "left-1/2"
                              "-translate-x-1/2"
                              "h-0"
                              "w-0"
                              "border-4"
                              "border-transparent"]
                             caret-position-classes)}]]]])
      value-text)))

(def ^:private external-link-button-classes
  ["inline-flex"
   "h-6"
   "w-6"
   "shrink-0"
   "items-center"
   "justify-center"
   "rounded"
   "transition-opacity"
   "hover:opacity-80"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"
   "focus-visible:outline-none"
   "focus-visible:ring-2"
   "focus-visible:ring-trading-green/70"
   "focus-visible:ring-offset-1"
   "focus-visible:ring-offset-base-100"])

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

(defn- external-link-button [href aria-label tone-classes]
  [:a {:href href
       :target "_blank"
       :rel "noopener noreferrer"
       :aria-label aria-label
       :title aria-label
       :class (into external-link-button-classes tone-classes)}
   (shared/external-link-icon ["h-3.5" "w-3.5" "shrink-0"] {:stroke-width 2})])

(defn- balance-contract-node [contract-id]
  (let [display-contract-id (abbreviate-contract-id contract-id)]
    (when-let [explorer-url (balance-contract-explorer-url contract-id)]
      [:span {:class ["inline-flex"
                      "min-h-6"
                      "items-center"
                      "gap-1"
                      "whitespace-nowrap"
                      "text-trading-text"]}
       [:span display-contract-id]
       (external-link-button explorer-url
                             (str "Open contract " display-contract-id " in Hyperliquid Explorer")
                             ["text-trading-green"])])))

(defn- balance-pnl-node
  [{:keys [coin selection-coin pnl-value pnl-pct contract-id]}]
  (if-let [pnl-text (shared/format-pnl-text pnl-value pnl-pct)]
    (let [tone-class (shared/pnl-tone-class pnl-value)
          explorer-url (balance-contract-explorer-url contract-id)
          asset-label (or selection-coin coin "asset")]
      [:span {:class ["inline-flex"
                      "min-h-6"
                      "items-center"
                      "justify-start"
                      "gap-1"
                      "whitespace-nowrap"
                      tone-class]}
       [:span {:class ["num"]} pnl-text]
       (when explorer-url
         (external-link-button explorer-url
                               (str "Open " asset-label " in Hyperliquid Explorer")
                               [tone-class]))])
    [:span {:class ["text-trading-text"]} "--"]))

(defn- balance-coin-display [{:keys [coin selection-coin]}]
  (let [{:keys [base-label prefix-label]}
        (shared/resolve-coin-display (or selection-coin coin) {})]
    {:base-label (or base-label coin "Asset")
     :prefix-label prefix-label}))

(defn- balance-coin-node [{:keys [base-label prefix-label]}]
  [:span {:class ["flex" "min-w-0" "items-center" "gap-1"]}
   [:span {:class ["truncate"]} base-label]
   (when prefix-label
     [:span {:class shared/position-chip-classes}
      prefix-label])])

(defn- balance-amount-cell [amount amount-decimals unit-label]
  [:div {:class ["text-left" "font-semibold" "num" "whitespace-nowrap"]}
   [:span {:class ["num"]}
    (shared/format-balance-amount amount amount-decimals)]
   " "
   [:span unit-label]])

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

(defn- send-enabled?
  [{:keys [key selection-coin coin available-balance]}]
  (let [row-key (some-> key str str/trim)]
    (and (seq (shared/non-blank-text (or selection-coin coin)))
         (number? (shared/parse-num available-balance))
         (pos? (shared/parse-num available-balance))
         (not (#{"perps-usdc" "unified-usdc-fallback"} row-key)))))

(defn- send-action-context
  [{:keys [coin selection-coin available-balance amount-decimals]}]
  (let [{:keys [base-label prefix-label]}
        (shared/resolve-coin-display (or selection-coin coin) {})
        max-display (shared/format-balance-amount available-balance amount-decimals)]
    {:token (or selection-coin coin)
     :symbol (or base-label coin "Asset")
     :prefix-label prefix-label
     :max-amount available-balance
     :max-display max-display
     :max-input max-display}))

(defn- balance-row-action-button
  ([label]
   (balance-row-action-button label nil))
  ([label action]
   [:button {:class balance-row-action-button-classes
             :type "button"
             :on (when action {:click [action]})}
    label]))

(defn- balance-row-disabled-action [label]
  [:span {:class ["inline-flex"
                  "min-h-6"
                  "items-center"
                  "text-xs"
                  "text-trading-text-secondary"]}
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
                           key
                           transfer-disabled?
                           available-balance-tooltip-position]}]
  (let [coin-style (when-not (usdc-balance-row? {:coin coin})
                     {:color "rgb(151, 252, 228)"})
        selectable-coin (or selection-coin coin)
        {:keys [base-label prefix-label] :as coin-display} (balance-coin-display {:coin coin
                                                                                   :selection-coin selection-coin})
        send-enabled?* (send-enabled? {:key key
                                       :selection-coin selection-coin
                                       :coin coin
                                       :available-balance available-balance})
        send-action (when send-enabled?*
                      [:actions/open-funding-send-modal
                       (send-action-context {:coin coin
                                             :selection-coin selection-coin
                                             :available-balance available-balance
                                             :amount-decimals amount-decimals})
                       :event.currentTarget/bounds])]
    [:div {:class ["grid"
                   balances-desktop-grid-template-class
                   "gap-x-3"
                   "items-center"
                   "px-3"
                   "py-px"
                   "text-sm"
                   "leading-4"
                   "text-trading-text"
                   "hover:bg-base-300"]}
     (shared/coin-select-control selectable-coin
                                 (balance-coin-node {:base-label base-label
                                                     :prefix-label prefix-label})
                                 {:style coin-style
                                  :extra-classes ["w-full"
                                                  "justify-start"
                                                  "text-left"
                                                  "font-semibold"
                                                  "truncate"]})
     (balance-amount-cell total-balance amount-decimals base-label)
     [:div.text-left.font-semibold.num.whitespace-nowrap
      (available-balance-value-node {:coin coin
                                     :unit-label base-label
                                     :available-balance available-balance
                                     :amount-decimals amount-decimals
                                     :transfer-disabled? transfer-disabled?
                                     :tooltip-position available-balance-tooltip-position})]
     [:div.text-left.font-semibold.num "$" (shared/format-currency usdc-value)]
     [:div.text-left.font-medium.num.pr-4
      (balance-pnl-node {:coin coin
                         :selection-coin selection-coin
                         :pnl-value pnl-value
                         :pnl-pct pnl-pct
                         :contract-id contract-id})]
     [:div.pl-2.text-left
      (if send-enabled?*
        (balance-row-action-button "Send" send-action)
        (balance-row-disabled-action "Send"))]
     [:div.text-left
      (if transfer-disabled?
        [:span {:class ["text-xs" "text-trading-text-secondary"]} "Unified"]
        (balance-row-action-button "Transfer"))]
     [:div.text-left]
     [:div.text-left
      (balance-contract-node contract-id)]]))

(defn balance-table-header
  ([sort-state]
   (balance-table-header sort-state []))
  ([sort-state extra-classes]
   [:div {:class (into ["grid"
                        balances-desktop-grid-template-class
                        "gap-x-3"
                        "py-1"
                        "px-3"
                        "bg-base-200"
                        "text-sm"
                        "font-medium"
                        "text-trading-text"]
                       extra-classes)}
    [:div (sortable-balances-header "Coin" sort-state :left)]
    [:div (sortable-balances-header "Total Balance" sort-state :left)]
    [:div (sortable-balances-header "Available Balance" sort-state :left)]
    [:div (sortable-balances-header "USDC Value" sort-state :left)]
    [:div.pr-4 (sortable-balances-header "PNL (ROE %)" sort-state :left)]
    [:div.pl-2 (table/non-sortable-header "Send" :left)]
    [:div (table/non-sortable-header "Transfer" :left)]
    [:div (table/non-sortable-header "Repay" :left)]
    [:div (table/non-sortable-header "Contract" :left)]]))

(defn- mobile-balance-coin-node [{:keys [coin selection-coin]}]
  (let [{:keys [base-label prefix-label]}
        (shared/resolve-coin-display (or selection-coin coin) {})]
    [:span {:class ["flex" "min-w-0" "items-center" "gap-1"]}
     [:span {:class ["truncate" "font-medium" "leading-4" "text-trading-text"]} (or base-label coin "Asset")]
     (when prefix-label
       [:span {:class shared/position-chip-classes}
        prefix-label])]))

(defn- mobile-balance-footer-action
  ([label enabled?]
   (mobile-balance-footer-action label enabled? nil))
  ([label enabled? action]
   (let [attrs {:class (into ["inline-flex"
                              "items-center"
                              "justify-start"
                              "bg-transparent"
                              "p-0"
                              "text-sm"
                              "font-medium"
                              "leading-none"
                              "transition-colors"
                              "whitespace-nowrap"]
                             (if enabled?
                               ["text-trading-green"
                                "hover:text-[#7fffe4]"
                                "focus:outline-none"
                                "focus:ring-0"
                                "focus:ring-offset-0"
                                "focus-visible:text-[#7fffe4]"]
                               ["cursor-default" "text-trading-text-secondary"]))}]
     (if (and enabled? action)
       [:button (assoc attrs
                       :type "button"
                       :on {:click [action]})
        label]
       [:span attrs label]))))

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
        transfer-enabled? (not transfer-disabled?)
        send-enabled?* (send-enabled? {:key key
                                       :selection-coin selection-coin
                                       :coin coin
                                       :available-balance available-balance})
        send-action (when send-enabled?*
                      [:actions/open-funding-send-modal
                       (send-action-context {:coin coin
                                             :selection-coin selection-coin
                                             :available-balance available-balance
                                             :amount-decimals amount-decimals})
                       :event.currentTarget/bounds])]
    (mobile-cards/expandable-card
     {:data-role (str "mobile-balance-card-" row-id)
      :expanded? expanded?
      :toggle-actions [[:actions/toggle-account-info-mobile-card :balances row-id]]
      :summary-grid-classes ["grid"
                             "grid-cols-[minmax(0,0.82fr)_minmax(0,0.8fr)_minmax(0,1.25fr)_auto]"
                             "items-start"
                             "gap-x-3"
                             "gap-y-2"]
      :summary-items [(mobile-cards/summary-item "Coin"
                                                 (mobile-balance-coin-node row)
                                                 {:value-classes ["font-medium"
                                                                  "leading-4"
                                                                  "text-trading-text"]})
                      (mobile-cards/summary-item "USDC Value"
                                                 (str "$" (shared/format-currency usdc-value))
                                                 {:value-classes ["num"
                                                                  "font-medium"
                                                                  "leading-4"
                                                                  "whitespace-nowrap"]})
                      (mobile-cards/summary-item "Total Balance"
                                                 (str (shared/format-balance-amount total-balance amount-decimals)
                                                      " "
                                                      (or base-label coin ""))
                                                 {:value-classes ["num"
                                                                  "font-medium"
                                                                  "leading-4"
                                                                  "whitespace-nowrap"]})]
      :detail-content [:div {:class ["space-y-3"]}
                       (mobile-cards/detail-grid
                        "grid-cols-2"
                        [(mobile-cards/detail-item
                         "Available Balance"
                         (available-balance-value-node {:coin coin
                                                         :unit-label base-label
                                                         :available-balance available-balance
                                                         :amount-decimals amount-decimals
                                                         :transfer-disabled? transfer-disabled?
                                                         :tooltip-position available-balance-tooltip-position})
                          {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
                         (mobile-cards/detail-item
                          "PNL (ROE %)"
                          (balance-pnl-node {:coin coin
                                             :selection-coin selection-coin
                                             :pnl-value pnl-value
                                             :pnl-pct pnl-pct
                                             :contract-id contract-id})
                          {:value-classes ["font-medium"]})
                         (when-let [contract-node (balance-contract-node contract-id)]
                           (mobile-cards/detail-item "Contract"
                                                     contract-node
                                                     {:full-width? true}))])
                       [:div {:class ["border-t" "border-[#17313d]" "pt-2.5"]}
                        [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-5" "gap-y-2"]}
                         (mobile-balance-footer-action "Send" send-enabled?* send-action)
                         (mobile-balance-footer-action "Transfer to Perps" transfer-enabled?)]]]})))

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
