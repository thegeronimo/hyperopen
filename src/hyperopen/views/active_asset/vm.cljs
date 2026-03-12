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

(defn- non-blank-text [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- normalize-coin-key [coin]
  (some-> coin non-blank-text str/upper-case))

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
        active-position (trading-state/position-for-active-asset full-state)
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
        locale (get-in full-state [:ui :locale])
        funding-tooltip-ui (get-in full-state [:funding-ui :tooltip] {})
        funding-tooltip-id (funding-policy/funding-tooltip-pin-id coin)
        funding-tooltip-open? (or (= funding-tooltip-id
                                     (:visible-id funding-tooltip-ui))
                                  (= funding-tooltip-id
                                     (:pinned-id funding-tooltip-ui)))
        funding-tooltip-pinned? (= funding-tooltip-id
                                   (:pinned-id funding-tooltip-ui))
        funding-tooltip-model (when funding-tooltip-open?
                                (funding-policy/memoized-funding-tooltip-model
                                 (or active-position {})
                                 market
                                 coin
                                 mark
                                 funding-rate
                                 funding-predictability-state
                                 funding-hypothetical-input
                                 locale))]
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
