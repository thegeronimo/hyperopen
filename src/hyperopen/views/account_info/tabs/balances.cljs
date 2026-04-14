(ns hyperopen.views.account-info.tabs.balances
  (:require [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tabs.balances.desktop :as balances-desktop]
            [hyperopen.views.account-info.tabs.balances.mobile :as balances-mobile]
            [hyperopen.views.account-info.tabs.balances.shared :as balances-shared]))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

(defn build-balance-rows [webdata2 spot-data]
  (projections/build-balance-rows webdata2 spot-data nil))

(defn build-balance-rows-for-account [webdata2 spot-data account]
  (projections/build-balance-rows webdata2 spot-data account))

(defn sort-balances-by-column [rows column direction]
(balances-shared/sort-balances-by-column rows column direction))

(def sortable-balances-header
  balances-desktop/sortable-balances-header)

(def balance-row
  balances-desktop/balance-row)

(def balance-table-header
  balances-desktop/balance-table-header)

(defn balances-tab-content
  ([balance-rows hide-small? sort-state]
   (balances-tab-content balance-rows hide-small? sort-state "" {}))
  ([balance-rows hide-small? sort-state coin-search]
   (balances-tab-content balance-rows hide-small? sort-state coin-search {}))
  ([balance-rows hide-small? sort-state coin-search options]
   (let [{:keys [mobile-expanded-card read-only?]} (balances-shared/normalize-balances-options options)
         rows* (or balance-rows [])
         visible-rows (if hide-small?
                        (filter (fn [row]
                                  (>= (shared/parse-num (:usdc-value row)) 1))
                                rows*)
                        rows*)
         search-filtered-rows (balances-shared/filter-balances-by-coin-search visible-rows coin-search)
         sorted-rows (if (:column sort-state)
                       (balances-shared/sort-balances-by-column search-filtered-rows
                                                                (:column sort-state)
                                                                (:direction sort-state))
                       search-filtered-rows)
         expanded-row-id (:balances mobile-expanded-card)]
     (if (seq search-filtered-rows)
       [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
        (balances-desktop/balance-table-header sort-state read-only? ["hidden" "lg:grid"])
        (into [:div {:class ["hidden"
                             "lg:block"
                             "flex-1"
                             "min-h-0"
                             "min-w-0"
                             "overflow-auto"
                             "scrollbar-hide"]
                    :data-role "account-tab-rows-viewport"}]
              (map-indexed (fn [idx row]
                             (let [tooltip-position (if (zero? idx) :bottom :top)]
                               ^{:key (:key row)}
                               (balances-desktop/balance-row (assoc row :available-balance-tooltip-position tooltip-position)
                                                             {:read-only? read-only?})))
                           sorted-rows))
        (into [:div {:class ["lg:hidden"
                             "flex-1"
                             "min-h-0"
                             "overflow-y-auto"
                             "scrollbar-hide"
                             "space-y-2.5"
                             "px-2.5"
                             "pt-2"
                             "pb-[calc(6rem+env(safe-area-inset-bottom))]"]
                    :data-role "balances-mobile-cards-viewport"}]
              (map-indexed (fn [idx row]
                             (let [tooltip-position (if (zero? idx) :bottom :top)]
                               ^{:key (str "mobile-" (:key row))}
                               (balances-mobile/mobile-balance-card expanded-row-id
                                                                    (assoc row :available-balance-tooltip-position tooltip-position)
                                                                    {:read-only? read-only?})))
                           sorted-rows))]
       (empty-state "No balance data available")))))
