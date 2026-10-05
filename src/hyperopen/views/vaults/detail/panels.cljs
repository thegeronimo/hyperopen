(ns hyperopen.views.vaults.detail.panels
  (:require [hyperopen.views.vaults.detail.format :as vf]
            [hyperopen.wallet.core :as wallet]))

(defn detail-tab-button
  [{:keys [value label]} selected-tab]
  [:button {:type "button"
            :class (into ["border-b"
                          "px-3"
                          "py-2.5"
                          "text-sm"
                          "font-medium"
                          "transition-colors"]
                         (if (= value selected-tab)
                           ["border-ho-accent-hi" "text-trading-text"]
                           ["border-transparent" "text-[#8ea0a7]" "hover:text-trading-text"]))
            :on {:click [[:actions/set-vault-detail-tab value]]}}
   label])

(defn- render-address-list [addresses]
  (when (seq addresses)
    [:div {:class ["space-y-1.5"]}
     [:div {:class ["text-ho-text-muted"]}
      "This vault uses the following vaults as component strategies:"]
     (for [address addresses]
       ^{:key (str "component-vault-" address)}
       [:div {:class ["num" "break-all" "text-[#33d1b7]"]}
        address])]))

(defn- render-about-panel [{:keys [description leader relationship]}]
  (let [component-addresses (or (:child-addresses relationship) [])
        parent-address (:parent-address relationship)]
    [:div {:class ["space-y-3" "px-3" "pb-3" "pt-2" "text-sm"]}
     [:div
      [:div {:class ["text-ho-text-muted"]} "Leader"]
      [:div {:class ["num" "font-medium" "text-trading-text"]}
       (or (wallet/short-addr leader) "—")]]
     [:div
      [:div {:class ["text-ho-text-muted"]} "Description"]
      [:p {:class ["mt-1" "leading-5" "text-trading-text"]}
       (if (seq description)
         description
         "No vault description available.")]]
     (when parent-address
       [:div {:class ["text-ho-text-muted"]}
        "Parent strategy: "
        [:button {:type "button"
                  :class ["num" "text-ho-accent-hi" "hover:underline"]
                  :on {:click [[:actions/navigate (str "/vaults/" parent-address)]]}}
         parent-address]])
     (render-address-list component-addresses)]))

(defn- render-vault-performance-panel [{:keys [snapshot]}]
  [:div {:class ["grid" "grid-cols-2" "gap-3" "px-3" "pb-3" "pt-2" "text-sm"]}
   [:div
    [:div {:class ["text-ho-text-muted"]} "24H"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (vf/format-percent (:day snapshot))]]
   [:div
    [:div {:class ["text-ho-text-muted"]} "7D"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (vf/format-percent (:week snapshot))]]
   [:div
    [:div {:class ["text-ho-text-muted"]} "30D"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (vf/format-percent (:month snapshot))]]
   [:div
    [:div {:class ["text-ho-text-muted"]} "All-time"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (vf/format-percent (:all-time snapshot))]]])

(defn- your-performance-row
  [label value]
  [:div
   [:div {:class ["text-ho-text-muted"]} label]
   [:div {:class ["num" "font-medium" "text-trading-text"]} value]])

(defn- render-your-performance-panel [{:keys [metrics position]}]
  (if (= :open (:status position))
    [:div {:class ["grid" "grid-cols-2" "gap-3" "px-3" "pb-3" "pt-2" "text-sm"]}
     (your-performance-row "Current Value" (vf/format-currency (:value position)))
     (your-performance-row "Cost Basis" (vf/format-currency (:cost-basis position)))
     (your-performance-row "P&L Since Deposit"
                           (str (vf/format-currency (:unrealized position))
                                (when (number? (:unrealized-pct position))
                                  (str " (" (vf/format-percent (:unrealized-pct position)) ")"))))
     (your-performance-row "All-time Earned" (vf/format-currency (:all-time-earned position)))]
    [:div {:class ["space-y-3" "px-3" "pb-3" "pt-2" "text-sm"]}
     (your-performance-row "Your Deposits" (vf/format-currency (:your-deposit metrics)))
     (your-performance-row "All-time Earned" (vf/format-currency (:all-time-earned metrics)))]))

(defn render-tab-panel
  [{:keys [selected-tab] :as vm}]
  (case selected-tab
    :vault-performance (render-vault-performance-panel vm)
    :your-performance (render-your-performance-panel vm)
    (render-about-panel vm)))

(defn relationship-links
  [{:keys [relationship]}]
  (case (:type relationship)
    :child
    (when-let [parent-address (:parent-address relationship)]
      [:div {:class ["mt-1.5" "text-xs" "text-[#8fa3aa]"]}
       "Parent strategy: "
       [:button {:type "button"
                 :class ["num" "text-ho-accent-hi" "hover:underline"]
                 :on {:click [[:actions/navigate (str "/vaults/" parent-address)]]}}
        (wallet/short-addr parent-address)]])

    nil))
