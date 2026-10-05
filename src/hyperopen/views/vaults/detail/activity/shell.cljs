(ns hyperopen.views.vaults.detail.activity.shell
  (:require [hyperopen.views.portfolio.montecarlo.panel :as montecarlo-panel]
            [hyperopen.views.vaults.detail.activity.performance-metrics :as metrics]
            [hyperopen.views.vaults.detail.activity.tables :as tables]))

(defn- format-activity-count [count]
  (cond
    (not (number? count)) nil
    (<= count 0) nil
    (>= count 100) "100+"
    :else (str count)))

(defn- activity-tab-button [{:keys [value label count]} selected-tab]
  [:button {:type "button"
            :class (into ["whitespace-nowrap"
                          "border-b"
                          "px-4"
                          "py-2.5"
                          "text-sm"
                          "font-normal"
                          "transition-colors"]
                         (if (= value selected-tab)
                           ["border-ho-border" "text-ho-text"]
                           ["border-ho-border" "text-ho-text-secondary" "hover:text-ho-text"]))
            :on {:click [[:actions/set-vault-detail-activity-tab value]]}}
   (if-let [count-label (format-activity-count count)]
     (str label " (" count-label ")")
     label)
   (when (= value :monte-carlo)
     [:span {:class ["account-info-tab-badge"]
             :data-role "vault-detail-activity-tab-badge-new"}
      "New"])])

(defn activity-panel [{:keys [selected-activity-tab
                              activity-tabs
                              activity-table-config
                              performance-metrics
                              monte-carlo
                              activity-direction-filter
                              activity-filter-open?
                              activity-filter-options
                              activity-sort-state-by-tab
                              activity-loading
                              activity-errors
                              activity-balances
                              activity-positions
                              activity-open-orders
                              activity-twaps
                              activity-fills
                              activity-funding-history
                              activity-order-history
                              activity-deposits-withdrawals
                              activity-depositors
                              activity-copy-feedback]}]
  (let [sort-state-by-tab (or activity-sort-state-by-tab {})
        selected-filter* (or activity-direction-filter :all)
        table-config-by-tab (or activity-table-config {})
        table-config (get table-config-by-tab selected-activity-tab)
        filter-enabled? (true? (:supports-direction-filter? table-config))
        table-columns (fn [tab]
                        (vec (or (get-in table-config-by-tab [tab :columns]) [])))]
    [:section {:class (into ["rounded-2xl"
                             "border"
                             "border-[#1b3237]"
                             "bg-[#071820]"
                             "overflow-hidden"
                             "w-full"
                             "flex"
                             "min-h-0"
                             "flex-col"]
                            (when (= selected-activity-tab :performance-metrics)
                              ["max-h-[75vh]" "xl:max-h-[46rem]"]))
               :data-role "vault-detail-activity-panel"}
     [:div {:class ["flex" "items-center" "justify-between" "border-b" "border-[#1b3237]" "bg-transparent" "gap-2" "pr-3"]}
      [:div {:class ["min-w-0" "overflow-x-auto"]}
       [:div {:class ["flex" "min-w-max" "items-center"]}
        (for [tab activity-tabs]
          ^{:key (str "activity-tab-" (name (:value tab)))}
          (activity-tab-button tab selected-activity-tab))]]
      [:div {:class ["relative" "hidden" "md:flex" "items-center"]}
       [:button {:type "button"
                 :disabled (not filter-enabled?)
                 :class (into ["inline-flex"
                               "items-center"
                               "gap-1"
                               "text-xs"
                               "text-ho-text-secondary"
                               "transition-colors"]
                              (if filter-enabled?
                                ["cursor-pointer" "hover:text-ho-text"]
                                ["cursor-not-allowed" "opacity-50"]))
                 :on {:click [[:actions/toggle-vault-detail-activity-filter-open]]}}
        "Filter"
        [:span "⌄"]]
       (when (and filter-enabled?
                  activity-filter-open?)
         [:div {:class ["absolute"
                        "right-0"
                        "top-full"
                        "z-30"
                        "mt-1.5"
                        "w-32"
                        "overflow-hidden"
                        "rounded-md"
                        "border"
                        "border-[#204046]"
                        "bg-[#081f29]"
                        "spectate-lg"]}
          (for [{:keys [value label]} activity-filter-options]
            ^{:key (str "vault-detail-activity-filter-" (name value))}
            [:button {:type "button"
                      :class (into ["flex"
                                    "w-full"
                                    "items-center"
                                    "justify-between"
                                    "px-3"
                                    "py-2"
                                    "text-left"
                                    "text-sm"
                                    "text-[#c7d5da]"
                                    "transition-colors"
                                    "hover:bg-[#0e2630]"
                                    "hover:text-ho-text"]
                                   (when (= value selected-filter*)
                                     ["bg-[#0e2630]" "text-ho-text"]))
                      :on {:click [[:actions/set-vault-detail-activity-direction-filter value]]}}
             [:span label]
             (when (= value selected-filter*)
               [:span {:class ["text-xs" "text-ho-accent-hi"]}
                "●"])])])]]
     (case selected-activity-tab
       :performance-metrics (metrics/performance-metrics-card performance-metrics)
       :monte-carlo (montecarlo-panel/monte-carlo-card monte-carlo)
       :balances (tables/balances-table activity-balances
                                        (get sort-state-by-tab :balances)
                                        (table-columns :balances))
       :positions (tables/positions-table activity-positions
                                          (get sort-state-by-tab :positions)
                                          (table-columns :positions))
       :open-orders (tables/open-orders-table activity-open-orders
                                              (get sort-state-by-tab :open-orders)
                                              (table-columns :open-orders))
       :twap (tables/twap-table activity-twaps
                                (get sort-state-by-tab :twap)
                                (table-columns :twap))
       :trade-history (tables/fills-table activity-fills
                                          (true? (:trade-history activity-loading))
                                          (:trade-history activity-errors)
                                          (get sort-state-by-tab :trade-history)
                                          (table-columns :trade-history))
       :funding-history (tables/funding-history-table activity-funding-history
                                                      (true? (:funding-history activity-loading))
                                                      (:funding-history activity-errors)
                                                      (get sort-state-by-tab :funding-history)
                                                      (table-columns :funding-history))
       :order-history (tables/order-history-table activity-order-history
                                                  (true? (:order-history activity-loading))
                                                  (:order-history activity-errors)
                                                  (get sort-state-by-tab :order-history)
                                                  (table-columns :order-history))
       :deposits-withdrawals (tables/ledger-table activity-deposits-withdrawals
                                                  (true? (:deposits-withdrawals activity-loading))
                                                  (:deposits-withdrawals activity-errors)
                                                  (get sort-state-by-tab :deposits-withdrawals)
                                                  (table-columns :deposits-withdrawals))
       :depositors (tables/depositors-table activity-depositors
                                            (get sort-state-by-tab :depositors)
                                            (table-columns :depositors)
                                            activity-copy-feedback)
       [:div {:class ["px-4" "py-6" "text-sm" "text-[#8ea2aa]"]}
        "This activity stream is not available yet for vaults."])]))
