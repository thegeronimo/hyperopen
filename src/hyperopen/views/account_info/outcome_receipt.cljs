(ns hyperopen.views.account-info.outcome-receipt
  "Hiccup for `hyperopen.outcomes.receipt/receipt`: what a settled market
   leaves in place of silently switching to another market."
  (:require [hyperopen.outcomes.domain.quotes :as quotes]))

(defn- line
  [label value & [tone]]
  [:div {:class ["flex" "items-center" "justify-between" "gap-3" "text-sm"]}
   [:span {:class ["text-trading-text-secondary"]} label]
   [:span {:class ["num" "font-semibold" (or tone "text-trading-text")]} value]])

(defn receipt-view
  [{:keys [title result result-known? held cost payout fee fee-rate-text received profit
           fee-known? next]}]
  [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-200" "p-4" "space-y-2" "max-w-md"]
         :data-role "outcome-settlement-receipt"}
   [:div {:class ["text-sm" "text-trading-text-secondary"]} title]
   [:div {:class ["text-lg" "font-semibold" "text-trading-text"]} result]
   (line "You held" held)
   (line "Cost" (quotes/usd-text cost))
   (when result-known?
     (line "Payout" (quotes/usd-text payout)))
   (when (and result-known? fee-known?)
     (line (str "Settlement fee (" fee-rate-text ")")
           (str "−" (quotes/usd-text fee))))
   (when (and result-known? (not fee-known?))
     (line "Settlement fee" "unconfirmed on this venue"))
   (when (and result-known? fee-known?)
     (line "You received" (quotes/usd-text received)))
   (when (and result-known? fee-known?)
     (line "Profit" (quotes/usd-text profit {:signed? true})
           (if (neg? profit) "text-error" "text-success")))
   (when (and next (:coin next))
     [:button {:class ["mt-2" "bg-transparent" "p-0" "text-sm" "font-semibold" "text-trading-green"
                       "hover:text-ho-accent-bright"]
               :type "button"
               :data-role "outcome-receipt-next"
               :on {:click [[:actions/open-outcome-market nil (:coin next)]]}}
      (str (:title next) " →")])])
