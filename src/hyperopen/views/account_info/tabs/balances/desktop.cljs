(ns hyperopen.views.account-info.tabs.balances.desktop
  (:require [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]
            [hyperopen.views.account-info.tabs.balances.shared :as balances-shared]))

(def ^:private balances-desktop-grid-template-class
  "grid-cols-[minmax(84px,0.78fr)_minmax(132px,0.98fr)_minmax(152px,1.08fr)_minmax(102px,0.74fr)_minmax(176px,1.28fr)_minmax(64px,0.4fr)_minmax(104px,0.56fr)_minmax(48px,0.2fr)_minmax(120px,0.64fr)]")

(def ^:private balances-read-only-desktop-grid-template-class
  "grid-cols-[minmax(84px,0.82fr)_minmax(132px,1.04fr)_minmax(152px,1.14fr)_minmax(102px,0.8fr)_minmax(176px,1.34fr)_minmax(120px,0.72fr)]")

(defn- desktop-grid-template-class
  [read-only?]
  (if read-only?
    balances-read-only-desktop-grid-template-class
    balances-desktop-grid-template-class))

(defn sortable-balances-header
  ([column-name sort-state]
   (sortable-balances-header column-name sort-state :left))
  ([column-name sort-state align]
   (table/sortable-header-button column-name
                                 sort-state
                                 :actions/sort-balances
                                 {:full-width? true
                                  :extra-classes (table/header-alignment-classes align)})))

(defn balance-row
  ([row]
   (balance-row row {}))
  ([{:keys [coin
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
            available-balance-tooltip-position]}
    {:keys [read-only?]}]
   (let [coin-style (when-not (balances-shared/usdc-balance-row? {:coin coin})
                      {:color "rgb(151, 252, 228)"})
         selectable-coin (or selection-coin coin)
         {:keys [base-label prefix-label]} (balances-shared/balance-coin-display {:coin coin
                                                                                  :selection-coin selection-coin})
         send-enabled?* (and (not read-only?)
                             (balances-shared/send-enabled? {:key key
                                                             :selection-coin selection-coin
                                                             :coin coin
                                                             :available-balance available-balance}))
         send-action (when send-enabled?*
                       [:actions/open-funding-send-modal
                        (balances-shared/send-action-context {:coin coin
                                                              :selection-coin selection-coin
                                                              :available-balance available-balance
                                                              :amount-decimals amount-decimals})
                        :event.currentTarget/bounds])]
     (into [:div {:class ["grid"
                          (desktop-grid-template-class read-only?)
                          "gap-x-4"
                          "items-center"
                          "px-3"
                          "py-px"
                          "text-sm"
                          "leading-4"
                          "text-trading-text"
                          "hover:bg-base-300"]}
            (shared/coin-select-control selectable-coin
                                        (balances-shared/balance-coin-node {:base-label base-label
                                                                            :prefix-label prefix-label})
                                        {:style coin-style
                                         :extra-classes ["w-full"
                                                         "justify-start"
                                                         "text-left"
                                                         "font-semibold"
                                                         "truncate"]})
            (balances-shared/balance-amount-cell total-balance amount-decimals base-label)
            [:div.text-left.font-semibold.num.whitespace-nowrap
             (balances-shared/available-balance-value-node {:coin coin
                                                            :unit-label base-label
                                                            :available-balance available-balance
                                                            :amount-decimals amount-decimals
                                                            :transfer-disabled? transfer-disabled?
                                                            :tooltip-position available-balance-tooltip-position})]
            [:div.text-left.font-semibold.num "$" (shared/format-currency usdc-value)]
            [:div.text-left.font-medium.num.pr-4
             (balances-shared/balance-pnl-node {:coin coin
                                                :selection-coin selection-coin
                                                :pnl-value pnl-value
                                                :pnl-pct pnl-pct
                                                :contract-id contract-id})]]
           (concat
            (when-not read-only?
              [[:div.pl-2.text-left
                (if send-enabled?*
                  (balances-shared/balance-row-action-button "Send" send-action)
                  (balances-shared/balance-row-disabled-action "Send"))]
               [:div.text-left
                (if transfer-disabled?
                  [:span {:class ["text-xs" "text-trading-text-secondary"]} "Unified"]
                  (balances-shared/balance-row-action-button "Transfer"))]
               [:div.text-left]])
            [[:div.text-left
              (balances-shared/balance-contract-node contract-id)]])))))

(defn balance-table-header
  ([sort-state]
   (balance-table-header sort-state false []))
  ([sort-state extra-classes]
   (balance-table-header sort-state false extra-classes))
  ([sort-state read-only? extra-classes]
   (into [:div {:class (into ["grid"
                              (desktop-grid-template-class read-only?)
                              "gap-x-4"
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
          [:div.pr-4 (sortable-balances-header "PNL (ROE %)" sort-state :left)]]
         (concat
          (when-not read-only?
            [[:div.pl-2 (table/non-sortable-header "Send" :left)]
             [:div (table/non-sortable-header "Transfer" :left)]
             [:div (table/non-sortable-header "Repay" :left)]])
          [[:div (table/non-sortable-header "Contract" :left)]]))))
