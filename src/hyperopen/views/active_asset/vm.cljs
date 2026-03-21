(ns hyperopen.views.active-asset.vm
  (:require [clojure.string :as str]
            [hyperopen.active-asset.funding-policy :as funding-policy]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.utils.formatting :as fmt]))

(def default-dropdown-state
  {:visible-dropdown nil
   :search-term ""
   :sort-by :volume
   :sort-direction :desc
   :loading? false
   :phase :bootstrap
   :favorites #{}
   :missing-icons #{}
   :loaded-icons #{}
   :highlighted-market-key nil
   :scroll-top 0
   :render-limit 120
   :last-render-limit-increase-ms nil
   :favorites-only? false
   :strict? false
   :active-tab :all})

(def ^:private open-panel-dropdown-state-keys
  [:visible-dropdown
   :search-term
   :sort-by
   :sort-direction
   :loading?
   :phase
   :favorites
   :missing-icons
   :loaded-icons
   :highlighted-market-key
   :scroll-top
   :render-limit
   :favorites-only?
   :strict?
   :active-tab
   :markets])

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- normalize-coin-key [coin]
  (some-> coin non-blank-text str/upper-case))

(defn- select-state-keys
  [state ks]
  (select-keys (or state {}) ks))

(defn- related-coin-entries
  [by-coin coin]
  (let [normalized-coin (normalize-coin-key coin)]
    (cond-> {}
      (and (seq normalized-coin)
           (contains? by-coin normalized-coin))
      (assoc normalized-coin (get by-coin normalized-coin))

      (contains? by-coin coin)
      (assoc coin (get by-coin coin)))))

(defn- read-by-coin
  [by-coin coin]
  (let [normalized-coin (normalize-coin-key coin)]
    (or (and (seq normalized-coin)
             (get by-coin normalized-coin))
        (get by-coin coin))))

(defn available-assets [state]
  (get-in state [:asset-selector :markets] []))

(defn dropdown-state [state]
  (merge default-dropdown-state
         (get state :asset-selector)))

(defn- selector-dropdown-open?
  [state]
  (= (get-in state [:asset-selector :visible-dropdown]) :asset-selector))

(defn- projected-active-market?
  [state active-asset]
  (let [projected-market (:active-market state)]
    (and (map? projected-market)
         (= (:coin projected-market) active-asset))))

(defn- selector-market-lookup-needed?
  [state active-asset]
  (and (string? active-asset)
       (not (projected-active-market? state active-asset))))

(defn- asset-selector-state
  [state active-asset]
  (let [selector-state (:asset-selector state)
        base-state (if (selector-dropdown-open? state)
                     (-> (select-state-keys selector-state open-panel-dropdown-state-keys)
                         (update :missing-icons #(or % #{}))
                         (update :loaded-icons #(or % #{})))
                     (cond-> {:visible-dropdown (:visible-dropdown selector-state)}
                       active-asset
                       (assoc :missing-icons (or (:missing-icons selector-state) #{})
                              :loaded-icons (or (:loaded-icons selector-state) #{}))))]
    (cond-> base-state
      (selector-market-lookup-needed? state active-asset)
      (assoc :market-by-key (or (:market-by-key selector-state) {})))))

(defn- funding-tooltip-ui-state
  [state]
  {:tooltip (select-state-keys (get-in state [:funding-ui :tooltip]) [:visible-id :pinned-id])})

(defn- funding-tooltip-open?
  [tooltip-ui coin]
  (let [tooltip-id (funding-policy/funding-tooltip-pin-id coin)]
    (or (= tooltip-id (:visible-id tooltip-ui))
        (= tooltip-id (:pinned-id tooltip-ui)))))

(defn- active-assets-state
  [state active-asset tooltip-open?]
  (let [context (get-in state [:active-assets :contexts active-asset])
        predictability-state (get-in state [:active-assets :funding-predictability] {})]
    (cond-> {}
      context
      (assoc-in [:contexts active-asset] context)

      tooltip-open?
      (assoc :funding-predictability
             {:by-coin (related-coin-entries (get predictability-state :by-coin {}) active-asset)
              :loading-by-coin (related-coin-entries (get predictability-state :loading-by-coin {}) active-asset)
              :error-by-coin (related-coin-entries (get predictability-state :error-by-coin {}) active-asset)}))))

(defn- open-tooltip-funding-ui-state
  [state active-asset]
  (assoc (funding-tooltip-ui-state state)
         :hypothetical-position-by-coin
         (related-coin-entries
          (get-in state [:funding-ui :hypothetical-position-by-coin] {})
          active-asset)))

(defn panel-dependency-state
  [state]
  (let [active-asset (:active-asset state)
        tooltip-ui (get-in state [:funding-ui :tooltip] {})
        tooltip-open? (and active-asset
                           (funding-tooltip-open? tooltip-ui active-asset))]
    (cond-> {:active-asset active-asset
             :active-market (:active-market state)
             :active-assets (active-assets-state state active-asset tooltip-open?)
             :asset-selector (asset-selector-state state active-asset)
             :funding-ui (if tooltip-open?
                           (open-tooltip-funding-ui-state state active-asset)
                           (funding-tooltip-ui-state state))
             :trade-ui {:mobile-asset-details-open? (true? (get-in state [:trade-ui :mobile-asset-details-open?]))}}
      tooltip-open?
      (assoc :account (:account state)
             :perp-dex-clearinghouse (:perp-dex-clearinghouse state)
             :spot (:spot state)
             :ui {:locale (get-in state [:ui :locale])}))))

(defn active-asset-funding-tooltip-open?
  [state]
  (let [active-asset (:active-asset state)
        tooltip-ui (get-in state [:funding-ui :tooltip] {})]
    (and active-asset
         (funding-tooltip-open? tooltip-ui active-asset))))

(defn resolve-active-market [full-state active-asset]
  (let [projected-market (:active-market full-state)
        market-by-key (get-in full-state [:asset-selector :market-by-key] {})]
    (cond
      (and (map? projected-market)
           (= (:coin projected-market) active-asset))
      projected-market

      (string? active-asset)
      (markets/resolve-market-by-coin market-by-key active-asset)

      :else
      nil)))

(defn active-asset-row-vm [ctx-data market dropdown-state full-state]
  (let [coin (or (:coin market) (:coin ctx-data))
        icon-market (-> (or market {})
                        (assoc :coin (or (:coin market) coin))
                        (assoc :symbol (or (:symbol market) coin)))
        mark (or (:mark ctx-data) (:mark market))
        mark-raw (or (:markRaw ctx-data) (:markRaw market))
        oracle (:oracle ctx-data)
        oracle-raw (:oracleRaw ctx-data)
        change-24h (or (:change24h ctx-data) (:change24h market))
        change-24h-pct (or (:change24hPct ctx-data) (:change24hPct market))
        volume-24h (or (:volume24h ctx-data) (:volume24h market))
        open-interest-raw (:openInterest ctx-data)
        open-interest-usd (if (= :spot (:market-type market))
                            nil
                            (or (when (and open-interest-raw mark)
                                  (fmt/calculate-open-interest-usd open-interest-raw mark))
                                (:openInterest market)))
        funding-rate (funding-policy/parse-optional-number (:fundingRate ctx-data))
        countdown-text (fmt/format-funding-countdown)
        funding-tooltip-ui (get-in full-state [:funding-ui :tooltip] {})
        funding-tooltip-id (funding-policy/funding-tooltip-pin-id coin)
        funding-tooltip-open? (or (= funding-tooltip-id
                                     (:visible-id funding-tooltip-ui))
                                  (= funding-tooltip-id
                                     (:pinned-id funding-tooltip-ui)))
        funding-tooltip-pinned? (= funding-tooltip-id
                                   (:pinned-id funding-tooltip-ui))
        funding-tooltip-model (when funding-tooltip-open?
                                (let [active-position (trading-state/position-for-active-asset full-state)
                                      funding-predictability-state {:summary (read-by-coin
                                                                              (get-in full-state [:active-assets :funding-predictability :by-coin] {})
                                                                              coin)
                                                                    :loading? (true? (read-by-coin
                                                                                      (get-in full-state [:active-assets :funding-predictability :loading-by-coin] {})
                                                                                      coin))
                                                                    :error (read-by-coin
                                                                            (get-in full-state [:active-assets :funding-predictability :error-by-coin] {})
                                                                            coin)}
                                      funding-hypothetical-input (read-by-coin
                                                                  (get-in full-state [:funding-ui :hypothetical-position-by-coin] {})
                                                                  coin)
                                      locale (get-in full-state [:ui :locale])]
                                  (funding-policy/memoized-funding-tooltip-model
                                   (or active-position {})
                                   market
                                   coin
                                   mark
                                   funding-rate
                                   funding-predictability-state
                                   funding-hypothetical-input
                                   locale)))]
    {:coin coin
     :icon-market icon-market
     :dropdown-visible? (= (:visible-dropdown dropdown-state) :asset-selector)
     :details-open? (true? (get-in full-state [:trade-ui :mobile-asset-details-open?]))
     :mark mark
     :mark-raw mark-raw
     :oracle oracle
     :oracle-raw oracle-raw
     :change-24h change-24h
     :change-24h-pct change-24h-pct
     :volume-24h volume-24h
     :open-interest-usd open-interest-usd
     :funding-rate funding-rate
     :countdown-text countdown-text
     :funding-tooltip-open? funding-tooltip-open?
     :funding-tooltip-model funding-tooltip-model
     :funding-tooltip-id funding-tooltip-id
     :funding-tooltip-pinned? funding-tooltip-pinned?
     :is-spot (= :spot (:market-type market))
     :missing-icons (get-in full-state [:asset-selector :missing-icons] #{})
     :loaded-icons (get-in full-state [:asset-selector :loaded-icons] #{})}))

(defn active-asset-panel-vm [state]
  (let [contexts (get-in state [:active-assets :contexts] {})
        dropdown (dropdown-state state)
        active-asset (:active-asset state)
        active-market (resolve-active-market state active-asset)
        selected-key (or (:key active-market)
                         (when active-asset
                           (markets/coin->market-key active-asset)))]
    {:active-asset active-asset
     :row-vm (when active-asset
               (active-asset-row-vm (or (get contexts active-asset)
                                        {:coin active-asset})
                                    active-market
                                    dropdown
                                    state))
     :dropdown-state dropdown
     :asset-selector-props (when (:visible-dropdown dropdown)
                             {:visible? true
                              :markets (available-assets state)
                              :selected-market-key selected-key
                              :loading? (:loading? dropdown)
                              :phase (:phase dropdown)
                              :search-term (:search-term dropdown)
                              :sort-by (:sort-by dropdown)
                              :sort-direction (:sort-direction dropdown)
                              :favorites (:favorites dropdown)
                              :favorites-only? (:favorites-only? dropdown)
                              :missing-icons (:missing-icons dropdown)
                              :loaded-icons (:loaded-icons dropdown)
                              :highlighted-market-key (:highlighted-market-key dropdown)
                              :scroll-top (:scroll-top dropdown)
                              :render-limit (:render-limit dropdown)
                              :strict? (:strict? dropdown)
                              :active-tab (:active-tab dropdown)})}))
