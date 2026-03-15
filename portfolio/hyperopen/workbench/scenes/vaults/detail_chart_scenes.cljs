(ns hyperopen.workbench.scenes.vaults.detail-chart-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.vaults.detail.chart-view :as chart-view]))

(portfolio/configure-scenes
  {:title "Vault Detail Chart"
   :collection :vaults})

(defn- clamp
  [value min-value max-value]
  (max min-value (min max-value value)))

(def returns-points
  [{:time-ms 1700000000000 :value 0.2 :x-ratio 0.0 :y-ratio 0.62}
   {:time-ms 1700086400000 :value 0.8 :x-ratio 0.2 :y-ratio 0.55}
   {:time-ms 1700172800000 :value 1.3 :x-ratio 0.4 :y-ratio 0.46}
   {:time-ms 1700259200000 :value 0.9 :x-ratio 0.6 :y-ratio 0.52}
   {:time-ms 1700345600000 :value 1.9 :x-ratio 0.8 :y-ratio 0.34}
   {:time-ms 1700432000000 :value 2.4 :x-ratio 1.0 :y-ratio 0.22}])

(def pnl-points
  [{:time-ms 1700000000000 :value 120 :x-ratio 0.0 :y-ratio 0.72}
   {:time-ms 1700086400000 :value 320 :x-ratio 0.2 :y-ratio 0.58}
   {:time-ms 1700172800000 :value 450 :x-ratio 0.4 :y-ratio 0.46}
   {:time-ms 1700259200000 :value 380 :x-ratio 0.6 :y-ratio 0.51}
   {:time-ms 1700345600000 :value 640 :x-ratio 0.8 :y-ratio 0.34}
   {:time-ms 1700432000000 :value 820 :x-ratio 1.0 :y-ratio 0.2}])

(def account-value-points
  [{:time-ms 1700000000000 :value 12400 :x-ratio 0.0 :y-ratio 0.71}
   {:time-ms 1700086400000 :value 12620 :x-ratio 0.2 :y-ratio 0.61}
   {:time-ms 1700172800000 :value 12880 :x-ratio 0.4 :y-ratio 0.48}
   {:time-ms 1700259200000 :value 12740 :x-ratio 0.6 :y-ratio 0.55}
   {:time-ms 1700345600000 :value 13150 :x-ratio 0.8 :y-ratio 0.36}
   {:time-ms 1700432000000 :value 13340 :x-ratio 1.0 :y-ratio 0.26}])

(def btc-returns-points
  [{:time-ms 1700000000000 :value 0.1 :x-ratio 0.0 :y-ratio 0.68}
   {:time-ms 1700086400000 :value 0.4 :x-ratio 0.2 :y-ratio 0.62}
   {:time-ms 1700172800000 :value 0.7 :x-ratio 0.4 :y-ratio 0.56}
   {:time-ms 1700259200000 :value 0.5 :x-ratio 0.6 :y-ratio 0.60}
   {:time-ms 1700345600000 :value 1.0 :x-ratio 0.8 :y-ratio 0.46}
   {:time-ms 1700432000000 :value 1.4 :x-ratio 1.0 :y-ratio 0.38}])

(def eth-returns-points
  [{:time-ms 1700000000000 :value -0.1 :x-ratio 0.0 :y-ratio 0.78}
   {:time-ms 1700086400000 :value 0.1 :x-ratio 0.2 :y-ratio 0.72}
   {:time-ms 1700172800000 :value 0.3 :x-ratio 0.4 :y-ratio 0.64}
   {:time-ms 1700259200000 :value 0.2 :x-ratio 0.6 :y-ratio 0.68}
   {:time-ms 1700345600000 :value 0.6 :x-ratio 0.8 :y-ratio 0.52}
   {:time-ms 1700432000000 :value 0.9 :x-ratio 1.0 :y-ratio 0.44}])

(defn- returns-series
  []
  [{:id :strategy
    :label "Vault"
    :stroke "#16d6a1"
    :has-data? true
    :points returns-points}
   {:id :btc
    :coin "BTC"
    :label "Bitcoin"
    :stroke "#f7931a"
    :has-data? true
    :points btc-returns-points}
   {:id :eth
    :coin "ETH"
    :label "Ether"
    :stroke "#7dd3fc"
    :has-data? true
    :points eth-returns-points}])

(defn- pnl-series
  []
  [{:id :strategy
    :label "Vault"
    :stroke "#16d6a1"
    :has-data? true
    :area-positive-fill "rgba(22, 214, 161, 0.24)"
    :area-negative-fill "rgba(237, 112, 136, 0.24)"
    :zero-y-ratio 0.84
    :points pnl-points}])

(defn- account-value-series
  []
  [{:id :strategy
    :label "Vault"
    :stroke "#f7931a"
    :has-data? true
    :area-fill "rgba(247, 147, 26, 0.24)"
    :points account-value-points}])

(defn- chart-model
  ([]
   (chart-model {}))
  ([overrides]
   (merge {:axis-kind :returns
           :selected-series :returns
           :series-tabs [{:value :returns :label "Returns"}
                         {:value :pnl :label "PNL"}
                         {:value :account-value :label "Account Value"}]
           :timeframe-options [{:value :day :label "24H"}
                               {:value :week :label "7D"}
                               {:value :month :label "30D"}]
           :selected-timeframe :month
           :returns-benchmark {:coin-search ""
                               :suggestions-open? false
                               :candidates [{:value "BTC" :label "Bitcoin"}
                                            {:value "ETH" :label "Ether"}
                                            {:value "SOL" :label "Solana"}]
                               :top-coin "BTC"
                               :selected-options [{:value "BTC" :label "Bitcoin"}]
                               :empty-message "No symbols."}
           :points returns-points
           :series (returns-series)
           :y-ticks [{:value 0 :y-ratio 0.82}
                     {:value 1 :y-ratio 0.55}
                     {:value 2 :y-ratio 0.22}]
           :hover {:active? false}}
          overrides)))

(defn- set-series
  [state series-key]
  (case series-key
    :pnl (assoc state
                :selected-series :pnl
                :axis-kind :pnl
                :points pnl-points
                :series (pnl-series)
                :y-ticks [{:value 0 :y-ratio 0.84}
                          {:value 400 :y-ratio 0.48}
                          {:value 800 :y-ratio 0.18}])
    :account-value (assoc state
                          :selected-series :account-value
                          :axis-kind :account-value
                          :points account-value-points
                          :series (account-value-series)
                          :y-ticks [{:value 12000 :y-ratio 0.82}
                                    {:value 12750 :y-ratio 0.52}
                                    {:value 13500 :y-ratio 0.18}])
    (assoc state
           :selected-series :returns
           :axis-kind :returns
           :points returns-points
           :series (returns-series)
           :y-ticks [{:value 0 :y-ratio 0.82}
                     {:value 1 :y-ratio 0.55}
                     {:value 2 :y-ratio 0.22}])))

(defn- update-hover
  [state client-x bounds point-count]
  (let [left (:left bounds)
        width (max 1 (:width bounds))
        relative (/ (- client-x left) width)
        ratio (clamp relative 0 1)
        index (int (js/Math.round (* ratio (max 0 (dec point-count)))))
        point (get (:points state) index)]
    (assoc state :hover {:active? (some? point)
                         :index index
                         :point point})))

(defn- chart-reducers
  []
  {:actions/set-vault-detail-chart-series
   (fn [state _dispatch-data value]
     (set-series state value))

   :actions/set-vaults-snapshot-range
   (fn [state _dispatch-data value]
     (assoc state :selected-timeframe (if (keyword? value) value (keyword value))))

   :actions/set-vault-detail-returns-benchmark-search
   (fn [state _dispatch-data value]
     (assoc-in state [:returns-benchmark :coin-search] value))

   :actions/set-vault-detail-returns-benchmark-suggestions-open
   (fn [state _dispatch-data open?]
     (assoc-in state [:returns-benchmark :suggestions-open?] open?))

   :actions/handle-vault-detail-returns-benchmark-search-keydown
   (fn [state _dispatch-data key top-coin]
     (if (= key "Enter")
       (update-in state [:returns-benchmark :selected-options]
                  (fn [options]
                    (let [value (or top-coin "BTC")
                          option {:value value :label value}]
                      (if (some #(= value (:value %)) options)
                        options
                        (conj (vec options) option)))))
       state))

   :actions/select-vault-detail-returns-benchmark
   (fn [state _dispatch-data value]
     (-> state
         (update-in [:returns-benchmark :selected-options]
                    (fn [options]
                      (if (some #(= value (:value %)) options)
                        (vec options)
                        (conj (vec options) {:value value :label value}))))
         (assoc-in [:returns-benchmark :suggestions-open?] false)
         (assoc-in [:returns-benchmark :coin-search] "")))

   :actions/remove-vault-detail-returns-benchmark
   (fn [state _dispatch-data value]
     (update-in state [:returns-benchmark :selected-options]
                (fn [options]
                  (->> (vec options)
                       (remove #(= value (:value %)))
                       vec))))

   :actions/set-vault-detail-chart-hover
   (fn [state _dispatch-data client-x bounds point-count]
     (update-hover state client-x bounds point-count))

   :actions/clear-vault-detail-chart-hover
   (fn [state _dispatch-data]
     (assoc state :hover {:active? false}))})

(defonce returns-store
  (ws/create-store ::vault-chart (chart-model {})))

(defonce single-series-store
  (ws/create-store ::single-series
                   (chart-model {:selected-series :account-value
                                 :axis-kind :account-value
                                 :points account-value-points
                                 :series (account-value-series)
                                 :y-ticks [{:value 12000 :y-ratio 0.82}
                                           {:value 12750 :y-ratio 0.52}
                                           {:value 13500 :y-ratio 0.18}]
                                 :returns-benchmark {:coin-search ""
                                                     :suggestions-open? false
                                                     :candidates []
                                                     :top-coin nil
                                                     :selected-options []}})))

(defonce pnl-store
  (ws/create-store ::pnl
                   (chart-model {:selected-series :pnl
                                 :axis-kind :pnl
                                 :points pnl-points
                                 :series (pnl-series)
                                 :y-ticks [{:value 0 :y-ratio 0.84}
                                           {:value 400 :y-ratio 0.48}
                                           {:value 800 :y-ratio 0.18}]
                                 :returns-benchmark {:selected-options []}})))

(defonce hover-store
  (ws/create-store ::hover
                   (chart-model {})))

(defonce benchmark-open-store
  (ws/create-store ::benchmark-open
                   (chart-model {:returns-benchmark {:coin-search "SO"
                                                     :suggestions-open? true
                                                     :candidates [{:value "SOL" :label "Solana"}]
                                                     :top-coin "SOL"
                                                     :selected-options [{:value "BTC" :label "Bitcoin"}]
                                                     :empty-message "No symbols."}})))

(defn- chart-scene
  [store]
  (layout/page-shell
   (layout/interactive-shell
    store
    (chart-reducers)
    (layout/desktop-shell
     (chart-view/chart-section @store)))))

(portfolio/defscene returns-with-benchmarks
  :params returns-store
  [store]
  (chart-scene store))

(portfolio/defscene single-series
  :params single-series-store
  [store]
  (chart-scene store))

(portfolio/defscene pnl-split-fill
  :params pnl-store
  [store]
  (chart-scene store))

(portfolio/defscene hover-state
  :params hover-store
  [store]
  (chart-scene store))

(portfolio/defscene benchmark-search-open
  :params benchmark-open-store
  [store]
  (chart-scene store))
