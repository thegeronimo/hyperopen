(ns hyperopen.views.vaults.detail.hero
  (:require [hyperopen.views.vaults.detail.format :as vf]
            [hyperopen.views.vaults.detail.panels :as panels]
            [hyperopen.views.vaults.detail.transfer-modal :as transfer-modal]
            [hyperopen.wallet.core :as wallet]))

(defn- metric-value-size-classes
  [value]
  (let [value-length (count (str (or value "")))]
    (cond
      (> value-length 16) ["text-[18px]" "sm:text-[22px]" "lg:text-[30px]"]
      (> value-length 12) ["text-[20px]" "sm:text-[24px]" "lg:text-[34px]"]
      :else ["text-[22px]" "sm:text-[28px]" "lg:text-[38px]"])))

(defn- metric-card
  [{:keys [label value accent]}]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-[#1a3a37]"
                 "bg-[#091a23]/88"
                 "min-w-0"
                 "px-3.5"
                 "py-3"
                 "shadow-[inset_0_0_0_1px_rgba(8,38,45,0.35)]"]}
   [:div {:class ["text-xs"
                  "uppercase"
                  "tracking-[0.08em]"
                  "text-[#8ba0a7]"]}
    label]
   [:div {:class (into ["mt-1.5"
                        "num"
                        "leading-[1.08]"
                        "font-semibold"]
                       (concat (metric-value-size-classes value)
                               (case accent
                                 :positive ["text-[#5de2c0]"]
                                 :negative ["text-[#e59ca8]"]
                                 ["text-trading-text"])))}
    value]])

(defn hero-section
  [vm vault-transfer]
  (let [{:keys [loading?
                name
                vault-address
                relationship
                metrics]} vm
        resolved-name (vf/resolved-vault-name name vault-address)
        show-name-skeleton? (and loading?
                                 (nil? resolved-name))
        vault-name (or resolved-name
                       (wallet/short-addr vault-address)
                       "Vault")
        can-open-withdraw? (true? (:can-open-withdraw? vault-transfer))
        can-open-deposit? (true? (:can-open-deposit? vault-transfer))
        month-return (:past-month-return metrics)
        month-return-accent (cond
                              (and (number? month-return) (pos? month-return)) :positive
                              (and (number? month-return) (neg? month-return)) :negative
                              :else nil)]
    [:section {:class ["rounded-2xl"
                       "border"
                       "border-[#19423e]"
                       "px-4"
                       "py-4"
                       "lg:px-6"
                       "bg-[radial-gradient(circle_at_82%_18%,rgba(41,186,147,0.20),transparent_42%),linear-gradient(180deg,#06382f_0%,#082029_56%,#051721_100%)]"]}
     [:div {:class ["flex" "flex-col" "gap-3" "lg:flex-row" "lg:items-start" "lg:justify-between"]}
      [:div {:class ["min-w-0"]}
       [:div {:class ["mb-2" "flex" "items-center" "gap-2" "text-xs" "text-[#8da5aa]"]}
        [:button {:type "button"
                  :class ["hover:text-trading-text"]
                  :on {:click [[:actions/navigate "/vaults"]]}}
         "Vaults"]
        [:span ">"]
        [:span {:class ["truncate"]}
         (if show-name-skeleton?
           [:span {:class ["inline-flex" "items-center"]
                   :data-role "vault-detail-breadcrumb-skeleton"}
            (vf/loading-skeleton-block ["h-3"
                                        "w-24"
                                        "sm:w-28"])]
           vault-name)]]
       [:h1 {:class ["text-[34px]"
                     "leading-[1.02]"
                     "font-semibold"
                     "tracking-tight"
                     "text-trading-text"
                     "sm:text-[44px]"
                     "xl:text-[56px]"
                     "break-words"]}
        (if show-name-skeleton?
          [:span {:class ["inline-block" "align-baseline" "max-w-[18ch]"]
                  :data-role "vault-detail-title-skeleton"}
           (vf/loading-skeleton-block ["h-[0.96em]"
                                       "w-[10ch]"
                                       "sm:w-[11ch]"])
           [:span {:class ["sr-only"]}
            "Loading vault name"]]
          vault-name)]
       [:div {:class ["mt-1.5" "num" "text-sm" "text-[#89a1a8]"]}
        (or (wallet/short-addr vault-address) vault-address)]
       (panels/relationship-links {:relationship relationship})]
      [:div {:class ["grid" "w-full" "grid-cols-2" "gap-2" "lg:w-auto" "lg:flex"]}
       (transfer-modal/hero-transfer-button {:label "Withdraw"
                                             :enabled? can-open-withdraw?
                                             :action [:actions/open-vault-transfer-modal
                                                      vault-address
                                                      :withdraw]})
       (transfer-modal/hero-transfer-button {:label "Deposit"
                                             :enabled? can-open-deposit?
                                             :action [:actions/open-vault-transfer-modal
                                                      vault-address
                                                      :deposit]})]]
     [:div {:class ["mt-4" "grid" "grid-cols-2" "gap-2.5" "lg:mt-5" "lg:gap-3" "xl:grid-cols-4"]}
      (metric-card {:label "TVL"
                    :value (vf/format-currency (:tvl metrics) {:missing "$0.00"})})
      (metric-card {:label "Past Month Return"
                    :value (vf/format-percent month-return {:signed? false
                                                            :decimals 0})
                    :accent month-return-accent})
      (metric-card {:label "Your Deposits"
                    :value (vf/format-currency (:your-deposit metrics))})
      (metric-card {:label "All-time Earned"
                    :value (vf/format-currency (:all-time-earned metrics))})]]))
