(ns hyperopen.workbench.scenes.markets.active-asset-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.fixtures :as fixtures]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.active-asset-view :as active-asset-view]))

(portfolio/configure-scenes
  {:title "Active Asset"
   :collection :markets})

(defn- market-by-key
  []
  (into {} (map (juxt :key identity) (fixtures/markets))))

(defn- active-asset-state
  [overrides]
  (ws/build-state
   {:active-asset "BTC"
    :active-market (fixtures/market)
    :active-assets {:contexts (fixtures/asset-contexts)
                    :loading false
                    :funding-predictability {:by-coin {"BTC" {:annualized 0.12}}
                                             :loading-by-coin {}
                                             :error-by-coin {}}}
    :asset-selector {:visible-dropdown nil
                     :search-term ""
                     :sort-by :volume
                     :sort-direction :desc
                     :markets (fixtures/markets)
                     :market-by-key (market-by-key)
                     :favorites #{"perp:BTC" "perp:ETH"}
                     :loaded-icons #{"perp:BTC" "perp:ETH" "spot:@1"}
                     :render-limit 120
                     :highlighted-market-key "perp:BTC"
                     :active-tab :all}
    :funding-ui {:tooltip {:visible-id nil
                           :pinned-id nil}
                 :hypothetical-position-by-coin {}}
    :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                  :szi "0.1500"
                                                                  :positionValue "15351.81"}}]}}}
   overrides))

(defn- update-sort
  [state column]
  (let [sort-by (get-in state [:asset-selector :sort-by])
        direction (get-in state [:asset-selector :sort-direction])
        active? (= column sort-by)]
    (-> state
        (assoc-in [:asset-selector :sort-by] column)
        (assoc-in [:asset-selector :sort-direction]
                  (if active?
                    (if (= :asc direction) :desc :asc)
                    :desc)))))

(defn- active-asset-reducers
  []
  {:actions/toggle-asset-dropdown
   (fn [state _dispatch-data]
     (update-in state [:asset-selector :visible-dropdown]
                #(if (= % :asset-selector) nil :asset-selector)))

   :actions/update-asset-search
   (fn [state _dispatch-data search-term]
     (assoc-in state [:asset-selector :search-term] search-term))

   :actions/toggle-asset-selector-strict
   (fn [state _dispatch-data]
     (update-in state [:asset-selector :strict?] not))

   :actions/set-asset-selector-favorites-only
   (fn [state _dispatch-data enabled?]
     (assoc-in state [:asset-selector :favorites-only?] enabled?))

   :actions/set-asset-selector-tab
   (fn [state _dispatch-data tab]
     (assoc-in state [:asset-selector :active-tab] tab))

   :actions/update-asset-selector-sort
   (fn [state _dispatch-data column]
     (update-sort state column))

   :actions/toggle-asset-favorite
   (fn [state _dispatch-data market-key]
     (update-in state [:asset-selector :favorites]
                #(if (contains? % market-key) (disj % market-key) (conj % market-key))))

   :actions/select-asset
   (fn [state _dispatch-data asset]
     (assoc state
            :active-asset (:coin asset)
            :active-market asset
            :asset-selector (-> (:asset-selector state)
                                (assoc :visible-dropdown nil)
                                (assoc :highlighted-market-key (:key asset)))))

   :actions/close-asset-dropdown
   (fn [state _dispatch-data]
     (assoc-in state [:asset-selector :visible-dropdown] nil))

   :actions/set-funding-tooltip-visible
   (fn [state _dispatch-data tooltip-id visible?]
     (assoc-in state [:funding-ui :tooltip :visible-id] (when visible? tooltip-id)))

   :actions/set-funding-tooltip-pinned
   (fn [state _dispatch-data tooltip-id pinned?]
     (assoc-in state [:funding-ui :tooltip :pinned-id] (when pinned? tooltip-id)))

   :actions/set-funding-hypothetical-size
   (fn [state _dispatch-data coin _mark value]
     (assoc-in state [:funding-ui :hypothetical-position-by-coin coin :size-input] value))

   :actions/set-funding-hypothetical-value
   (fn [state _dispatch-data coin _mark value]
     (assoc-in state [:funding-ui :hypothetical-position-by-coin coin :value-input] value))})

(defonce perp-store
  (ws/create-store ::perp (active-asset-state {})))

(defonce spot-store
  (ws/create-store ::spot
                   (active-asset-state {:active-asset "@1"
                                        :active-market (fixtures/market {:key "spot:@1"
                                                                         :coin "@1"
                                                                         :symbol "HYPE/USDC"
                                                                         :base "HYPE"
                                                                         :market-type :spot
                                                                         :mark 10.14
                                                                         :markRaw "10.14"
                                                                         :openInterest nil
                                                                         :fundingRate nil
                                                                         :maxLeverage nil})})))

(defonce dropdown-store
  (ws/create-store ::dropdown
                   (active-asset-state {:asset-selector {:visible-dropdown :asset-selector
                                                         :highlighted-market-key "perp:ETH"}})))

(defonce tooltip-store
  (ws/create-store ::tooltip
                   (active-asset-state {:funding-ui {:tooltip {:visible-id "funding-rate-tooltip-pin-btc"
                                                               :pinned-id "funding-rate-tooltip-pin-btc"}
                                                    :hypothetical-position-by-coin {"BTC" {:size-input "0.1500"
                                                                                           :value-input "15351.81"}}}})))

(portfolio/defscene perp-loaded
  :params perp-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (active-asset-reducers)
    (active-asset-view/active-asset-view @store))))

(portfolio/defscene spot-loaded
  :params spot-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (active-asset-reducers)
    (active-asset-view/active-asset-view @store))))

(portfolio/defscene selector-open
  :params dropdown-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (active-asset-reducers)
    (active-asset-view/active-asset-view @store))))

(portfolio/defscene funding-tooltip-open
  :params tooltip-store
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (active-asset-reducers)
    (active-asset-view/active-asset-view @store))))
