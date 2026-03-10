(ns hyperopen.workbench.scenes.markets.asset-selector-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.asset-selector-view :as asset-selector-view]))

(portfolio/configure-scenes
  {:title "Asset Selector"
   :collection :markets})

(defn- selector-store
  [scene-id overrides]
  (ws/create-store
   scene-id
   (merge {:visible? true
           :desktop? true
           :markets (fixtures/markets)
           :selected-market-key "perp:BTC"
           :search-term ""
           :sort-by :volume
           :sort-direction :desc
           :favorites #{"perp:BTC" "perp:ETH"}
           :favorites-only? false
           :strict? false
           :active-tab :all
           :missing-icons #{}
           :loaded-icons #{"perp:BTC" "perp:ETH" "spot:@1"}
           :highlighted-market-key "perp:BTC"
           :render-limit 120
           :scroll-top 0
           :loading? false
           :phase :full}
          overrides)))

(defn- toggle-sort
  [state column]
  (let [active? (= column (:sort-by state))]
    (assoc state
           :sort-by column
           :sort-direction (if active?
                             (if (= :asc (:sort-direction state)) :desc :asc)
                             :desc))))

(defn- selector-reducers
  []
  {:actions/update-asset-search
   (fn [state _dispatch-data search-term]
     (assoc state :search-term search-term))

   :actions/toggle-asset-selector-strict
   (fn [state _dispatch-data]
     (update state :strict? not))

   :actions/set-asset-selector-favorites-only
   (fn [state _dispatch-data enabled?]
     (assoc state :favorites-only? enabled?))

   :actions/set-asset-selector-tab
   (fn [state _dispatch-data tab]
     (assoc state :active-tab tab))

   :actions/update-asset-selector-sort
   (fn [state _dispatch-data column]
     (toggle-sort state column))

   :actions/toggle-asset-favorite
   (fn [state _dispatch-data market-key]
     (update state :favorites
             (fn [favorites]
               (if (contains? favorites market-key)
                 (disj favorites market-key)
                 (conj favorites market-key)))))

   :actions/select-asset
   (fn [state _dispatch-data asset]
     (assoc state
            :selected-market-key (:key asset)
            :highlighted-market-key (:key asset)
            :visible? false))

   :actions/close-asset-dropdown
   (fn [state _dispatch-data]
     (assoc state :visible? false))})

(defonce desktop-selector-store
  (selector-store ::desktop-selector {}))

(defonce mobile-selector-store
  (selector-store ::mobile-selector {:desktop? false
                                     :highlighted-market-key "spot:@1"}))

(defonce loading-selector-store
  (selector-store ::loading-selector {:loading? true
                                      :strict? true
                                      :favorites-only? true
                                      :search-term "bt"
                                      :highlighted-market-key "perp:ETH"}))

(defonce long-list-selector-store
  (selector-store ::long-list-selector
                  {:markets (vec (mapcat (fn [idx]
                                           (map (fn [market]
                                                  (assoc market
                                                         :key (str (:key market) "-" idx)
                                                         :coin (str (:coin market) "-" idx)
                                                         :symbol (str (:symbol market) " " idx)))
                                                (fixtures/markets)))
                                         (range 7)))
                   :render-limit 240
                   :scroll-top 540}))

(defn- selector-scene
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (selector-reducers)
    (layout/desktop-shell
     (layout/panel-shell
      (asset-selector-view/asset-selector-wrapper @store))))))

(portfolio/defscene desktop-open
  :params desktop-selector-store
  [store]
  (selector-scene store))

(portfolio/defscene mobile-overlay
  :params mobile-selector-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (selector-reducers)
    (layout/mobile-shell
     (asset-selector-view/asset-selector-wrapper @store)))))

(portfolio/defscene loading-and-favorites
  :params loading-selector-store
  [store]
  (selector-scene store))

(portfolio/defscene long-list
  :params long-list-selector-store
  [store]
  (selector-scene store))
