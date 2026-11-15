(ns hyperopen.views.account-info.tabs.positions
  (:require [hyperopen.views.account-info.margin-recommendation-panel :as margin-rec-panel]
            [hyperopen.views.account-info.positions-vm :as positions-vm]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.tabs.positions.desktop :as positions-desktop]
            [hyperopen.views.account-info.tabs.positions.mobile :as positions-mobile]
            [hyperopen.views.account-info.tabs.positions.shared :as positions-shared]))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

(def positions-direction-filter-key
  positions-shared/positions-direction-filter-key)

(defn format-position-size [position-data]
  (positions-vm/format-position-size position-data))

(defn position-unique-key [position-data]
  (projections/position-unique-key position-data))

(defn collect-positions [webdata2 perp-dex-states]
  (projections/collect-positions webdata2 perp-dex-states))

(defn sort-positions-by-column [positions column direction]
  (positions-vm/sort-position-rows-by-column positions column direction))

(defn reset-positions-sort-cache! []
  nil)

(def sortable-header
  positions-desktop/sortable-header)

(def position-table-header
  positions-desktop/position-table-header)

(def position-row
  positions-desktop/position-row)

(defn positions-tab-content-from-rows
  [{:keys [positions
           sort-state
           tpsl-modal
           reduce-popover
           margin-modal
           positions-state]
    :or {positions-state {}} :as options}]
  (when-not (map? options)
    (throw (ex-info "positions-tab-content-from-rows expects an options map"
                    {:received options})))
  (let [positions* (vec (or positions []))
        sort-state* (or sort-state {:column nil :direction :asc})
        direction-filter (positions-direction-filter-key positions-state)
        coin-search (:coin-search positions-state "")
        read-only? (true? (:read-only? positions-state))
        expanded-row-id (get-in positions-state [:mobile-expanded-card :positions])
        row-vms (positions-vm/position-row-vms positions*)
        filtered-row-vms (positions-vm/filter-row-vms row-vms direction-filter coin-search)
        sorted-row-vms (vec (positions-vm/sort-row-vms-by-column filtered-row-vms
                                                                  (:column sort-state*)
                                                                  (:direction sort-state*)))
        visible-row-keys (into #{}
                               (keep :row-key)
                               sorted-row-vms)
        margin-rec (:margin-rec positions-state)
        margin-rec-panel-key (:panel margin-rec)
        margin-rec-panel-row-vm (when (and margin-rec-panel-key
                                           (contains? visible-row-keys
                                                      margin-rec-panel-key))
                                  (some #(when (= margin-rec-panel-key (:row-key %)) %)
                                        sorted-row-vms))]
    (if (seq sorted-row-vms)
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
       (positions-desktop/position-table-header sort-state* read-only? ["hidden" "lg:grid"])
       (into [:div {:class ["hidden"
                            "lg:block"
                            "flex-1"
                            "min-h-0"
                            "min-w-0"
                            "overflow-auto"
                            "scrollbar-hide"]
                   :data-role "account-tab-rows-viewport"}]
             (map (fn [row-vm]
                    ^{:key (or (:row-key row-vm)
                               (hash (:row-data row-vm)))}
                    (positions-desktop/position-row-from-vm row-vm
                                                            tpsl-modal
                                                            reduce-popover
                                                            margin-modal
                                                            read-only?
                                                            positions-state))
                  sorted-row-vms))
       (when margin-rec-panel-row-vm
         [:div {:class ["hidden" "lg:block"]}
          (margin-rec-panel/margin-recommendation-panel
           {:position-key margin-rec-panel-key
            :rec (get-in margin-rec [:recs margin-rec-panel-key])
            :row-vm margin-rec-panel-row-vm
            :read-only? read-only?
            :risk-mode (:risk-mode margin-rec)
            :anchor (:panel-anchor margin-rec)})])
       (into [:div {:class ["lg:hidden"
                            "flex-1"
                            "min-h-0"
                            "overflow-y-auto"
                            "scrollbar-hide"
                            "space-y-2.5"
                            "px-2.5"
                            "pt-2"
                            "pb-[calc(6rem+env(safe-area-inset-bottom))]"]
                   :data-role "positions-mobile-cards-viewport"}]
             (map (fn [row-vm]
                    ^{:key (str "mobile-" (or (:row-key row-vm)
                                              (hash (:row-data row-vm))))}
                    (positions-mobile/mobile-position-card-from-vm expanded-row-id
                                                                  row-vm
                                                                  tpsl-modal
                                                                  reduce-popover
                                                                  margin-modal
                                                                  read-only?))
                  sorted-row-vms))
       (positions-mobile/mobile-position-overlay-outlet visible-row-keys
                                                        tpsl-modal
                                                        reduce-popover
                                                        margin-modal
                                                        read-only?
                                                        margin-rec)]
      (empty-state (if (seq positions*)
                     "No matching positions"
                     "No active positions")))))

(defn positions-tab-content-from-webdata
  [{:keys [webdata2 perp-dex-states] :as options}]
  (when-not (map? options)
    (throw (ex-info "positions-tab-content-from-webdata expects an options map"
                    {:received options})))
  (positions-tab-content-from-rows
   (assoc options
          :positions (collect-positions webdata2 perp-dex-states))))

(defn positions-tab-content
  [options]
  (when-not (map? options)
    (throw (ex-info "positions-tab-content expects an options map"
                    {:received options})))
  (if (contains? options :positions)
    (positions-tab-content-from-rows options)
    (positions-tab-content-from-webdata options)))
