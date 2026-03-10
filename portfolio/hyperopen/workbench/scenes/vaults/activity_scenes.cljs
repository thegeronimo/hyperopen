(ns hyperopen.workbench.scenes.vaults.activity-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.vaults.detail.activity :as activity]))

(portfolio/configure-scenes
  {:title "Vault Activity"
   :collection :vaults})

(defn- activity-store
  [scene-id overrides]
  (ws/create-store scene-id (fixtures/vault-activity overrides)))

(defn- activity-reducers
  []
  {:actions/set-vault-detail-activity-tab
   (fn [state _dispatch-data value]
     (assoc state :selected-activity-tab value))

   :actions/sort-vault-detail-activity
   (fn [state _dispatch-data tab column]
     (update-in state [:activity-sort-state-by-tab tab]
                #(ws/update-sort-state (or % {:direction :desc}) column)))

   :actions/toggle-vault-detail-activity-filter-open
   (fn [state _dispatch-data]
     (update state :activity-filter-open? not))

   :actions/set-vault-detail-activity-direction-filter
   (fn [state _dispatch-data value]
     (-> state
         (assoc :activity-direction-filter value)
         (assoc :activity-filter-open? false)))

   :actions/set-vaults-snapshot-range
   (fn [state _dispatch-data value]
     (assoc-in state [:performance-metrics :selected-timeframe]
               (if (keyword? value) value (keyword value))))})

(defonce fills-store
  (activity-store ::fills {}))

(defonce funding-store
  (activity-store ::funding {:selected-activity-tab :funding-history}))

(defonce orders-store
  (activity-store ::orders {:selected-activity-tab :order-history}))

(defonce empty-store
  (activity-store ::empty {:selected-activity-tab :trade-history
                           :activity-fills []
                           :activity-funding-history []
                           :activity-order-history []
                           :activity-errors {}
                           :activity-loading {}}))

(defn- activity-scene
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (activity-reducers)
    (layout/desktop-shell
     (activity/activity-panel @store)))))

(portfolio/defscene fills
  :params fills-store
  [store]
  (activity-scene store))

(portfolio/defscene funding
  :params funding-store
  [store]
  (activity-scene store))

(portfolio/defscene order-history
  :params orders-store
  [store]
  (activity-scene store))

(portfolio/defscene empty-state
  :params empty-store
  [store]
  (activity-scene store))
