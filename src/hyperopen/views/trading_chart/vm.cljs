(ns hyperopen.views.trading-chart.vm
  (:require [clojure.string :as str]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.trading-indicators-modules :as trading-indicators-modules]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.projections :as account-projections]
            [hyperopen.views.trading-chart.actions :as actions]
            [hyperopen.views.trading-chart.derived-cache :as derived-cache]
            [hyperopen.views.trading-chart.utils.position-overlay-model :as position-overlay-model]))

(defn- memoize-last
  [f]
  (let [cache (atom nil)]
    (fn [& args]
      (let [cached @cache]
        (if (and (map? cached)
                 (= args (:args cached)))
          (:result cached)
          (let [result (apply f args)]
            (reset! cache {:args args
                           :result result})
            result))))))

(defn- preferred-orders-value
  [state k]
  (if (contains? (or (:orders state) {}) k)
    (get-in state [:orders k])
    (get-in state [:webdata2 k])))

(def ^:private memoized-chart-open-orders
  (memoize-last
   (fn [open-orders-source
        open-orders-snapshot-source
        open-orders-snapshot-by-dex-source
        active-asset
        pending-cancel-oids]
     (account-projections/normalized-open-orders-for-active-asset
      open-orders-source
      open-orders-snapshot-source
      open-orders-snapshot-by-dex-source
      active-asset
      pending-cancel-oids))))

(def ^:private memoized-chart-fills
  (memoize-last
   (fn [fills-source]
     (cond
       (vector? fills-source) fills-source
       (sequential? fills-source) (vec fills-source)
       :else []))))

(def ^:private memoized-position-overlay-base
  (memoize-last
   (fn [active-asset active-position active-fills market-by-key selected-timeframe candle-data show-fill-markers?]
     (position-overlay-model/build-position-overlay
      {:active-asset active-asset
       :position active-position
       :fills active-fills
       :market-by-key market-by-key
       :selected-timeframe selected-timeframe
       :candle-data candle-data
       :show-fill-markers? show-fill-markers?}))))

(def ^:private memoized-fill-markers
  (memoize-last
   (fn [active-asset active-fills market-by-key selected-timeframe show-fill-markers?]
     (position-overlay-model/build-fill-markers
      {:active-asset active-asset
       :fills active-fills
       :market-by-key market-by-key
       :selected-timeframe selected-timeframe
       :show-fill-markers? show-fill-markers?}))))

(def ^:private memoized-position-overlay
  (memoize-last
   (fn [position-overlay-base preview]
     (cond-> position-overlay-base
       (and (map? position-overlay-base)
            (map? preview))
       (assoc :current-liquidation-price (:current-liquidation-price preview)
              :liquidation-price (:target-liquidation-price preview))))))

(def ^:private memoized-chart-runtime-options
  (memoize-last
   (fn [price-decimals
        volume-visible?
        indicator-runtime-ready?
        on-hide-volume-indicator
        active-asset
        candle-data
        on-liquidation-drag-preview
        on-liquidation-drag-confirm
        position-overlay
        fill-markers
        show-fill-markers?]
     {:series-options {:price-decimals price-decimals}
      :legend-deps {:format-price fmt/format-trade-price-plain
                    :format-delta fmt/format-trade-price-delta}
      :volume-visible? volume-visible?
      :indicator-runtime-ready? indicator-runtime-ready?
      :show-fill-markers? show-fill-markers?
      :on-hide-volume-indicator on-hide-volume-indicator
      :persistence-deps {:asset active-asset
                         :candles candle-data}
      :on-liquidation-drag-preview on-liquidation-drag-preview
      :on-liquidation-drag-confirm on-liquidation-drag-confirm
      :position-overlay position-overlay
      :fill-markers fill-markers})))

(def ^:private memoized-legend-meta
  (memoize-last
   (fn [symbol timeframe-label candle-data]
     {:symbol symbol
      :timeframe-label timeframe-label
      :venue "Hyperopen"
      :market-open? true
      :candle-data candle-data})))

(defn- parse-positive-number
  [value]
  (let [parsed (js/parseFloat (str (or value "")))]
    (when (and (number? parsed)
               (js/isFinite parsed)
               (pos? parsed))
      parsed)))

(defn- pending-liquidation-preview
  [state active-position-data]
  (let [margin-modal (get-in state [:positions-ui :margin-modal])
        position-key (when (map? active-position-data)
                       (account-projections/position-unique-key active-position-data))
        current-liquidation-price (parse-positive-number
                                   (:prefill-liquidation-current-price margin-modal))
        target-liquidation-price (parse-positive-number
                                  (:prefill-liquidation-target-price margin-modal))]
    (when (and (map? margin-modal)
               (true? (:open? margin-modal))
               (= :chart-liquidation-drag (:prefill-source margin-modal))
               (string? position-key)
               (= position-key (:position-key margin-modal))
               (number? current-liquidation-price)
               (number? target-liquidation-price))
      {:current-liquidation-price current-liquidation-price
       :target-liquidation-price target-liquidation-price})))

(defn- chart-open-orders
  [state]
  (let [active-asset (:active-asset state)
        open-orders-source (preferred-orders-value state :open-orders)
        open-orders-snapshot-source (preferred-orders-value state :open-orders-snapshot)
        open-orders-snapshot-by-dex-source (preferred-orders-value state :open-orders-snapshot-by-dex)
        pending-cancel-oids (get-in state [:orders :pending-cancel-oids])]
    (memoized-chart-open-orders
     open-orders-source
     open-orders-snapshot-source
     open-orders-snapshot-by-dex-source
     active-asset
     pending-cancel-oids)))

(defn- chart-fills
  [state]
  (let [fills-source (preferred-orders-value state :fills)]
    (memoized-chart-fills fills-source)))

(defn chart-view-model
  [state dispatch-fn]
  (let [active-asset (:active-asset state)
        active-open-orders (chart-open-orders state)
        active-fills (chart-fills state)
        candles-map (:candles state)
        active-market (or (:active-market state) {})
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        active-position (trading-state/position-for-active-asset state)
        active-position-data (when (map? active-position)
                               {:position active-position
                                :dex (:dex active-market)})
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        selected-chart-type (get-in state [:chart-options :selected-chart-type] :candlestick)
        api-response (get-in candles-map [active-asset selected-timeframe] {})
        has-error? (contains? api-response :error)
        raw-candles (if (vector? api-response)
                      api-response
                      (get api-response :data []))
        candle-data (derived-cache/memoized-candle-data raw-candles selected-timeframe)
        show-fill-markers? (trading-settings/show-fill-markers? state)
        preview (pending-liquidation-preview state active-position-data)
        position-overlay-base (memoized-position-overlay-base
                               active-asset
                               active-position
                               active-fills
                               market-by-key
                               selected-timeframe
                               candle-data
                               show-fill-markers?)
        position-overlay (memoized-position-overlay position-overlay-base preview)
        fill-markers (or (:fill-markers position-overlay)
                         (memoized-fill-markers active-asset
                                                active-fills
                                                market-by-key
                                                selected-timeframe
                                                show-fill-markers?))
        on-liquidation-drag-preview (actions/liquidation-drag-preview-callback
                                     dispatch-fn
                                     active-position-data)
        on-liquidation-drag-confirm (actions/liquidation-drag-confirm-callback
                                     dispatch-fn
                                     active-position-data)
        on-cancel-order (actions/cancel-order-callback dispatch-fn)
        on-hide-volume-indicator (actions/hide-volume-indicator-callback dispatch-fn)
        symbol (or active-asset "—")
        timeframe-label (str/upper-case (name selected-timeframe))
        price-decimals (or (:price-decimals active-market)
                           (:priceDecimals active-market)
                           (:pxDecimals active-market)
                           (fmt/price-decimals-from-raw (:markRaw active-market))
                           (fmt/price-decimals-from-raw (:prevDayRaw active-market)))
        volume-visible? (boolean (get-in state [:chart-options :volume-visible?] true))
        indicator-runtime-ready? (trading-indicators-modules/trading-indicators-ready? state)
        chart-runtime-options (memoized-chart-runtime-options
                               price-decimals
                               volume-visible?
                               indicator-runtime-ready?
                               on-hide-volume-indicator
                               active-asset
                               candle-data
                               on-liquidation-drag-preview
                               on-liquidation-drag-confirm
                               position-overlay
                               fill-markers
                               show-fill-markers?)
        legend-meta (memoized-legend-meta symbol timeframe-label candle-data)]
    {:has-error? has-error?
     :candle-data candle-data
     :selected-chart-type selected-chart-type
     :selected-timeframe selected-timeframe
     :active-indicators (get-in state [:chart-options :active-indicators] {})
     :legend-meta legend-meta
     :chart-runtime-options chart-runtime-options
     :active-open-orders active-open-orders
     :on-cancel-order on-cancel-order}))
