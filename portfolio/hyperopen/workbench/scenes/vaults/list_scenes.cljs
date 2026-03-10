(ns hyperopen.workbench.scenes.vaults.list-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.vaults.list-view :as vaults-view]))

(portfolio/configure-scenes
  {:title "Vaults List"
   :collection :vaults})

(defn- vaults-store
  [scene-id overrides]
  (ws/create-store scene-id (fixtures/vaults-list-state overrides)))

(defn- toggle-filter
  [state filter-key]
  (update-in state
             [:vaults-ui
              (case filter-key
                :leading :filter-leading?
                :deposited :filter-deposited?
                :others :filter-others?
                :closed :filter-closed?
                :filter-leading?)]
             not))

(defn- list-reducers
  []
  {:actions/toggle-vaults-filter
   (fn [state _dispatch-data filter-key]
     (toggle-filter state filter-key))

   :actions/set-vaults-snapshot-range
   (fn [state _dispatch-data range-key]
     (assoc-in state [:vaults-ui :snapshot-range]
               (if (keyword? range-key) range-key (keyword range-key))))

   :actions/set-vaults-sort
   (fn [state _dispatch-data column]
     (update-in state [:vaults-ui :sort]
                #(ws/update-sort-state (or % {:column :tvl :direction :desc}) column)))

   :actions/toggle-vaults-user-page-size-dropdown
   (fn [state _dispatch-data]
     (update-in state [:vaults-ui :user-vaults-page-size-dropdown-open?] not))

   :actions/close-vaults-user-page-size-dropdown
   (fn [state _dispatch-data]
     (assoc-in state [:vaults-ui :user-vaults-page-size-dropdown-open?] false))

   :actions/set-vaults-user-page-size
   (fn [state _dispatch-data size]
     (-> state
         (assoc-in [:vaults-ui :user-vaults-page-size] size)
         (assoc-in [:vaults-ui :user-vaults-page] 1)
         (assoc-in [:vaults-ui :user-vaults-page-size-dropdown-open?] false)))

   :actions/prev-vaults-user-page
   (fn [state _dispatch-data _page-count]
     (update-in state [:vaults-ui :user-vaults-page] #(max 1 (dec (or % 1)))))

   :actions/next-vaults-user-page
   (fn [state _dispatch-data page-count]
     (update-in state [:vaults-ui :user-vaults-page] #(min page-count (inc (or % 1)))))

   :actions/connect-wallet
   (fn [state _dispatch-data]
     (assoc-in state [:wallet :address] "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"))

   :actions/set-vaults-search-query
   (fn [state _dispatch-data value]
     (assoc-in state [:vaults-ui :search-query] value))})

(defonce connected-store
  (vaults-store ::connected {}))

(defonce disconnected-store
  (vaults-store ::disconnected {:wallet {:address nil}}))

(defonce loading-store
  (vaults-store ::loading {:vaults {:loading {:index? true
                                              :summaries? true}
                                    :merged-index-rows []}}))

(defonce filtered-empty-store
  (vaults-store ::filtered-empty {:vaults-ui {:search-query "zzz-not-found"}}))

(defonce page-two-store
  (vaults-store ::page-two {:vaults-ui {:user-vaults-page-size 1
                                        :user-vaults-page 2}}))

(defn- vaults-scene
  [store shell]
  (layout/page-shell
   (layout/interactive-shell
    store
    (list-reducers)
    (shell
     (vaults-view/vaults-view @store)))))

(portfolio/defscene connected
  :params connected-store
  [store]
  (vaults-scene store layout/desktop-shell))

(portfolio/defscene disconnected-cta
  :params disconnected-store
  [store]
  (vaults-scene store layout/desktop-shell))

(portfolio/defscene loading
  :params loading-store
  [store]
  (vaults-scene store layout/desktop-shell))

(portfolio/defscene filtered-empty
  :params filtered-empty-store
  [store]
  (vaults-scene store layout/desktop-shell))

(portfolio/defscene mobile-cards
  :params connected-store
  [store]
  (vaults-scene store layout/mobile-shell))

(portfolio/defscene page-2
  :params page-two-store
  [store]
  (vaults-scene store layout/desktop-shell))
