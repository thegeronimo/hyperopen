(ns hyperopen.views.account-info.tabs.balances.mobile
  (:require [clojure.string :as str]
            [hyperopen.views.account-info.mobile-cards :as mobile-cards]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tabs.balances.shared :as balances-shared]))

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

(defn mobile-balance-card [expanded-row-id row {:keys [read-only?]}]
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
                          (balances-shared/available-balance-value-node {:coin coin
                                                                         :unit-label base-label
                                                                         :available-balance available-balance
                                                                         :amount-decimals amount-decimals
                                                                         :transfer-disabled? transfer-disabled?
                                                                         :tooltip-position available-balance-tooltip-position})
                          {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
                         (mobile-cards/detail-item
                          "PNL (ROE %)"
                          (balances-shared/balance-pnl-node {:coin coin
                                                             :selection-coin selection-coin
                                                             :pnl-value pnl-value
                                                             :pnl-pct pnl-pct
                                                             :contract-id contract-id})
                          {:value-classes ["font-medium"]})
                         (when-let [contract-node (balances-shared/balance-contract-node contract-id)]
                           (mobile-cards/detail-item "Contract"
                                                     contract-node
                                                     {:full-width? true}))])
                       (when-not read-only?
                         [:div {:class ["border-t" "border-[#17313d]" "pt-2.5"]}
                          [:div {:class ["flex" "flex-wrap" "items-center" "gap-x-5" "gap-y-2"]}
                           (mobile-balance-footer-action "Send" send-enabled?* send-action)
                           (mobile-balance-footer-action "Transfer to Perps" transfer-enabled?)]])]})))
