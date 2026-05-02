(ns hyperopen.views.vaults.list-view.sections
  (:require [clojure.string :as str]
            [hyperopen.views.vaults.list-view.controls :as controls]
            [hyperopen.views.vaults.list-view.loading :as loading]
            [hyperopen.views.vaults.list-view.pagination :as pagination]
            [hyperopen.views.vaults.list-view.rows :as rows]))

(defn- section-loading-row-count
  [pagination]
  (let [page-size (:page-size pagination)]
    (if (and (number? page-size)
             (pos? page-size))
      (min loading/max-stable-loading-row-count
           page-size)
      loading/loading-skeleton-row-count)))

(defn section-table [state label rows* sort-state {:keys [loading? pagination desktop-layout?]}]
  (let [loading-row-count (section-loading-row-count pagination)]
    [:section {:class ["space-y-2"]}
     [:h3 {:class ["text-sm" "font-normal" "text-trading-text"]} label]
     (if desktop-layout?
       [:div {:class ["overflow-x-auto"]}
        [:table {:class ["w-full" "border-collapse"]
                 :data-role (str "vaults-" (str/lower-case (str/replace label #"\s+" "-")) "-table")}
         [:colgroup
          [:col {:style {:width "24%"}}]
          [:col {:style {:width "16%"}}]
          [:col {:style {:width "12%"}}]
          [:col {:style {:width "12%"}}]
          [:col {:style {:width "12%"}}]
          [:col {:style {:width "12%"}}]
          [:col {:style {:width "12%"}}]]
         [:thead
          [:tr {:class ["border-b" "border-base-300/70"]}
           [:th {:class ["px-3" "py-2" "text-left"]} (controls/sort-header "Vault" :vault sort-state)]
           [:th {:class ["px-3" "py-2" "text-left"]} (controls/sort-header "Leader" :leader sort-state)]
           [:th {:class ["px-3" "py-2" "text-left"]} (controls/sort-header "APR" :apr sort-state)]
           [:th {:class ["px-3" "py-2" "text-left"]} (controls/sort-header "TVL" :tvl sort-state)]
           [:th {:class ["px-3" "py-2" "text-left"]} (controls/sort-header "Your Deposit" :your-deposit sort-state)]
           [:th {:class ["px-3" "py-2" "text-left"]} (controls/sort-header "Age" :age sort-state)]
           [:th {:class ["px-3" "py-2" "text-right"]} (controls/sort-header "Snapshot" :snapshot sort-state)]]]
         [:tbody
          (cond
            loading?
            (for [idx (range loading-row-count)]
              ^{:key (str "vault-loading-row-" label "-" idx)}
              (loading/desktop-loading-row idx))

            (seq rows*)
            (for [row rows*]
              ^{:key (str "vault-row-" (:vault-address row))}
              (rows/vault-row state row))

            :else
            [:tr
             [:td {:col-span 7
                   :class ["px-3" "py-6" "text-center" "text-sm" "text-trading-text-secondary"]}
              "No vaults match current filters."]])]]]
       [:div {:class ["space-y-2"]}
        (cond
          loading?
          (for [idx (range loading-row-count)]
            ^{:key (str "mobile-vault-loading-row-" label "-" idx)}
            (loading/mobile-loading-card idx))

          (seq rows*)
          (for [row rows*]
            ^{:key (str "mobile-vault-row-" (:vault-address row))}
            (rows/mobile-vault-card state row))

          :else
          [:div {:class ["rounded-lg"
                         "border"
                         "border-base-300"
                         "bg-base-200/60"
                         "px-3"
                         "py-4"
                         "text-center"
                         "text-sm"
                         "text-trading-text-secondary"]}
           "No vaults match current filters."])])
     (pagination/user-vault-pagination-controls pagination)]))
