(ns hyperopen.views.vault-detail-view
  (:require [hyperopen.views.vault-detail.activity :as activity]
            [hyperopen.views.vault-detail.chart :as chart]
            [hyperopen.views.vault-detail.hero :as hero]
            [hyperopen.views.vault-detail.panels :as panels]
            [hyperopen.views.vault-detail.transfer-modal :as transfer-modal]
            [hyperopen.views.vaults.detail-vm :as detail-vm]))

(defn vault-detail-view
  [state]
  (let [{:keys [kind
                invalid-address?
                loading?
                error
                tabs
                selected-tab
                vault-transfer
                chart] :as vm} (detail-vm/vault-detail-vm state)
        vault-transfer* (or vault-transfer {})]
    [:div
     {:class ["w-full" "app-shell-gutter" "py-4" "space-y-4"]
      :data-parity-id "vault-detail-root"}
     (cond
       invalid-address?
       [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4" "text-sm" "text-trading-text-secondary"]}
        "Invalid vault address."]

       (not= kind :detail)
       [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4" "text-sm" "text-trading-text-secondary"]}
        "Select a vault to view details."]

       :else
       [:div {:class ["space-y-4"]}
        (hero/hero-section vm vault-transfer*)

        (when loading?
          [:div {:class ["rounded-xl" "border" "border-[#1f3d3d]" "bg-[#081820]" "px-4" "py-2.5" "text-sm" "text-[#8fa6ad]"]}
           "Loading vault details..."])

        (when error
          [:div {:class ["rounded-lg" "border" "border-red-500/40" "bg-red-900/20" "px-3" "py-2" "text-sm" "text-red-200"]}
           error])

        [:div {:class ["grid" "gap-3" "lg:grid-cols-[minmax(280px,1fr)_minmax(0,3fr)]"]}
         [:section {:class ["rounded-2xl"
                            "border"
                            "border-[#1b393a]"
                            "bg-[#071820]"]}
          [:div {:class ["flex" "items-center" "border-b" "border-[#1f3b3c]"]}
           (for [tab tabs]
             ^{:key (str "vault-detail-tab-" (name (:value tab)))}
             (panels/detail-tab-button tab selected-tab))]
          (panels/render-tab-panel vm)]

         (chart/chart-section chart)]

        (activity/activity-panel vm)
        (transfer-modal/vault-transfer-modal-view vault-transfer*)])]))
