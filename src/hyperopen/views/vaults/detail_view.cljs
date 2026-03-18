(ns hyperopen.views.vaults.detail-view
  (:require [hyperopen.views.vaults.detail.activity :as activity]
            [hyperopen.views.vaults.detail.chart-view :as chart]
            [hyperopen.views.vaults.detail.hero :as hero]
            [hyperopen.views.vaults.detail.panels :as panels]
            [hyperopen.views.vaults.detail.transfer-modal :as transfer-modal]
            [hyperopen.views.vaults.detail-vm :as detail-vm]))

(defn- background-status-banner [{:keys [visible? title detail items]}]
  (when visible?
    [:div {:class ["rounded-xl"
                   "border"
                   "px-4"
                   "py-3"
                   "backdrop-blur-sm"]
           :style {:border-color "rgba(39, 82, 86, 0.92)"
                   :background "linear-gradient(135deg, rgba(7, 24, 32, 0.97) 0%, rgba(8, 31, 37, 0.97) 54%, rgba(12, 41, 39, 0.93) 100%)"}
           :data-role "vault-detail-background-status"
           :role "status"
           :aria-live "polite"}
     [:div {:class ["flex" "flex-col" "gap-3" "xl:flex-row" "xl:items-center" "xl:justify-between"]}
      [:div {:class ["flex" "items-start" "gap-3"]}
       [:span {:class ["mt-0.5" "loading" "loading-spinner" "loading-sm" "text-[#66e3c5]"]
               :aria-hidden true}]
       [:div {:class ["space-y-1"]}
        [:div {:class ["text-sm" "font-medium" "text-trading-text"]}
         title]
        [:div {:class ["text-sm" "leading-5" "text-[#9fb4bb]"]}
         detail]]]
      [:div {:class ["flex" "flex-wrap" "gap-2"]}
       (for [{:keys [id label]} items]
         ^{:key (str "vault-detail-background-status-item-" (name id))}
         [:span {:class ["rounded-full"
                         "border"
                         "px-2.5"
                         "py-1"
                         "text-xs"
                         "font-medium"
                         "uppercase"
                         "tracking-[0.18em]"]
                 :style {:border-color "rgba(72, 113, 119, 0.88)"
                         :background-color "rgba(12, 29, 35, 0.92)"
                         :color "#9fb6bc"}
                 :data-role (str "vault-detail-background-status-item-" (name id))}
          label])]]]))

(defn vault-detail-view
  [state]
  (let [{:keys [kind
                invalid-address?
                loading?
                background-status
                error
                tabs
                selected-tab
                vault-transfer
                chart] :as vm} (detail-vm/vault-detail-vm state)
        vault-transfer* (or vault-transfer {})]
    [:div
     {:class ["flex-1"
              "min-h-0"
              "overflow-y-auto"
              "scrollbar-hide"
              "w-full"
              "app-shell-gutter"
              "py-4"
              "space-y-4"
              "md:py-5"]
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
        (background-status-banner background-status)

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

(defn ^:export route-view
  [state]
  (vault-detail-view state))

(goog/exportSymbol "hyperopen.views.vaults.detail_view.route_view" route-view)
