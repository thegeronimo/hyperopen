(ns hyperopen.views.vaults.detail.panels
  (:require [hyperopen.views.vaults.detail.format :as vf]
            [hyperopen.views.vaults.detail.position :as position-view]
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

(defn- your-performance-row
  [label value]
  [:div
   [:div {:class ["text-ho-text-muted"]} label]
   [:div {:class ["num" "font-medium" "text-trading-text"]} value]])

(defn- signed-tone
  [value]
  (cond
    (not (number? value)) "text-trading-text"
    (>= value 0.005) "text-ho-buy"
    (<= value -0.005) "text-ho-sell"
    :else "text-trading-text"))

(defn- performance-cell
  [{:keys [label value tone detail role]}]
  [:div {:class ["min-w-0"] :data-role role}
   [:div {:class ["text-ho-text-muted"]} label]
   [:div {:class ["num" "font-medium" (or tone "text-trading-text")]} value]
   (when detail
     [:div {:class ["num" "text-xs" "text-ho-text-dim"]} detail])])

(defn- period-cell
  [label {:keys [pnl pct]} role]
  (performance-cell {:label label
                     :role role
                     :value (position-view/format-signed-currency pnl)
                     :tone (signed-tone pnl)
                     :detail (when (number? pct) (vf/format-percent pct))}))

(defn- lockup-value
  [{:keys [locked? lockup-until-ms]}]
  (cond
    locked? (str "Until " (position-view/format-date lockup-until-ms))
    (number? lockup-until-ms) (str "Ended " (position-view/format-date lockup-until-ms))
    :else "None"))

(defn- render-open-performance-panel
  [{:keys [period-pnl unrealized unrealized-pct max-drawdown-pct cost-basis
           all-time-earned realized days-held transfer-count locked?]
    :as position}]
  [:div {:class ["px-4" "pb-4" "pt-3" "text-sm"] :data-role "vault-your-performance"}
   [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-3.5"]}
    (period-cell "24H" (:day period-pnl) "vault-your-performance-day")
    (period-cell "7D" (:week period-pnl) "vault-your-performance-week")
    (period-cell "30D" (:month period-pnl) "vault-your-performance-month")
    (performance-cell {:label "Since deposit"
                       :role "vault-your-performance-since-deposit"
                       :value (position-view/format-signed-currency unrealized)
                       :tone (signed-tone unrealized)
                       :detail (when (number? unrealized-pct) (vf/format-percent unrealized-pct))})
    (performance-cell {:label "Max drawdown"
                       :role "vault-your-performance-drawdown"
                       :value (vf/format-percent max-drawdown-pct)
                       :tone (signed-tone max-drawdown-pct)
                       :detail (when (number? max-drawdown-pct) "vault, since deposit")})
    (performance-cell {:label "Lockup"
                       :role "vault-your-performance-lockup"
                       :value (lockup-value position)
                       :tone (when locked? "text-ho-warn")})
    (performance-cell {:label "Cost basis"
                       :value (vf/format-currency cost-basis)})
    (performance-cell {:label "All-time earned"
                       :value (position-view/format-signed-currency all-time-earned)
                       :tone (signed-tone all-time-earned)
                       :detail (when (and (number? realized) (>= (js/Math.abs realized) 0.005))
                                 (str (position-view/format-signed-currency realized) " realized"))})]
   [:div {:class ["mt-3.5" "border-t" "border-ho-border-accent-muted" "pt-2.5" "text-xs" "text-ho-text-muted"]}
    (str (cond
           (= 0 days-held) "Opened today"
           (= 1 days-held) "Held 1 day"
           (number? days-held) (str "Held " days-held " days")
           :else "Held —")
         (when (pos? transfer-count)
           (str " · " transfer-count (if (= 1 transfer-count) " transfer" " transfers")))
         " · period figures estimated from vault history")]])

(defn- return-cell
  [label value role]
  (performance-cell {:label label
                     :role role
                     :value (vf/format-percent value)
                     :tone (signed-tone value)}))

(defn- render-vault-performance-panel
  [{:keys [snapshot metrics vault-max-drawdown-pct followers leader-commission leader-fraction]}]
  [:div {:class ["px-4" "pb-4" "pt-3" "text-sm"] :data-role "vault-performance-panel"}
   [:div {:class ["grid" "grid-cols-2" "gap-x-3" "gap-y-3.5"]}
    (return-cell "24H" (:day snapshot) "vault-performance-day")
    (return-cell "7D" (:week snapshot) "vault-performance-week")
    (return-cell "30D" (:month snapshot) "vault-performance-month")
    (return-cell "All-time" (:all-time snapshot) "vault-performance-all-time")
    (performance-cell {:label "APR"
                       :role "vault-performance-apr"
                       :value (vf/format-percent (:apr metrics) {:signed? false :decimals 1})})
    (performance-cell {:label "Max drawdown"
                       :role "vault-performance-drawdown"
                       :value (vf/format-percent vault-max-drawdown-pct)
                       :tone (signed-tone vault-max-drawdown-pct)
                       :detail (when (number? vault-max-drawdown-pct) "all-time")})
    (performance-cell {:label "Depositors"
                       :role "vault-performance-depositors"
                       :value (cond
                                ;; Hyperliquid lists at most 100 followers.
                                (and (number? followers) (>= followers 100)) "100+"
                                (number? followers) (str followers)
                                :else "—")})
    (performance-cell {:label "Leader profit share"
                       :role "vault-performance-commission"
                       :value (cond
                                (not (number? leader-commission)) "—"
                                (zero? leader-commission) "None"
                                :else (vf/format-percent leader-commission {:signed? false :decimals 0}))
                       :detail (when (and (number? leader-commission) (pos? leader-commission))
                                 "of depositor profits")})]
   [:div {:class ["mt-3.5" "border-t" "border-ho-border-accent-muted" "pt-2.5" "text-xs" "text-ho-text-muted"]}
    (str "TVL " (vf/format-currency (:tvl metrics))
         (when (number? leader-fraction)
           (str " · leader owns " (vf/format-percent leader-fraction {:signed? false :decimals 1})))
         " · returns from vault history")]])

(defn- render-your-performance-panel [{:keys [metrics position]}]
  (if (= :open (:status position))
    (render-open-performance-panel position)
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
