(ns hyperopen.views.trading-chart.core
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [replicant.core :as replicant-core]
            [hyperopen.system :as app-system]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.state.trading :as trading-state]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.views.account-info.projections :as account-projections]
            [hyperopen.views.trading-chart.runtime-state :as chart-runtime]
            [hyperopen.views.trading-chart.utils.chart-interop :as ci]
            [hyperopen.views.trading-chart.derived-cache :as derived-cache]
            [hyperopen.views.trading-chart.utils.position-overlay-model :as position-overlay-model]
            [hyperopen.views.trading-chart.timeframe-dropdown :refer [timeframe-dropdown]]
            [hyperopen.views.trading-chart.chart-type-dropdown :refer [chart-type-dropdown]]
            [hyperopen.views.trading-chart.indicators-dropdown :refer [indicators-dropdown]]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

;; Main timeframes for quick access buttons
(def main-timeframes [:5m :1h :1d])

(defn- preferred-orders-value
  [state k]
  (if (contains? (or (:orders state) {}) k)
    (get-in state [:orders k])
    (get-in state [:webdata2 k])))

(declare dispatch-chart-cancel-order!
         dispatch-hide-volume-indicator!
         dispatch-chart-liquidation-drag-margin-preview!
         dispatch-chart-liquidation-drag-margin-confirm!)

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

(def ^:private memoized-liquidation-drag-preview-callback
  (memoize-last
   (fn [dispatch-fn active-position-data]
     (fn [suggestion]
       (dispatch-chart-liquidation-drag-margin-preview!
        dispatch-fn
        active-position-data
        suggestion)))))

(def ^:private memoized-liquidation-drag-confirm-callback
  (memoize-last
   (fn [dispatch-fn active-position-data]
     (fn [suggestion]
       (dispatch-chart-liquidation-drag-margin-confirm!
        dispatch-fn
        active-position-data
        suggestion)))))

(def ^:private memoized-cancel-order-callback
  (memoize-last
   (fn [dispatch-fn]
     (fn [order]
       (dispatch-chart-cancel-order! dispatch-fn order)))))

(def ^:private memoized-hide-volume-indicator-callback
  (memoize-last
   (fn [dispatch-fn]
     (fn []
       (dispatch-hide-volume-indicator! dispatch-fn)))))

(def ^:private memoized-chart-runtime-options
  (memoize-last
   (fn [price-decimals
        volume-visible?
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

(defn- runtime-dispatch-fn
  []
  (when app-system/store
    (fn [event actions]
      (nxr/dispatch app-system/store event actions))))

(defn- current-dispatch-fn
  []
  (let [dispatch-fn replicant-core/*dispatch*]
    (or (when (ifn? dispatch-fn)
          dispatch-fn)
        (runtime-dispatch-fn))))

(defn- dispatch-chart-actions!
  [dispatch-fn trigger actions]
  (when (and (ifn? dispatch-fn)
             (seq actions))
    (dispatch-fn {:replicant/trigger trigger}
                 actions)))

(defn- dispatch-chart-cancel-order!
  ([order]
   (dispatch-chart-cancel-order! (current-dispatch-fn) order))
  ([dispatch-fn order]
   (when (map? order)
     (dispatch-chart-actions! dispatch-fn
                              :chart-order-overlay-cancel
                              [[:actions/cancel-order order]]))))

(defn- dispatch-hide-volume-indicator!
  ([]
   (dispatch-hide-volume-indicator! (current-dispatch-fn)))
  ([dispatch-fn]
   (dispatch-chart-actions! dispatch-fn
                            :chart-volume-indicator-remove
                            [[:actions/hide-volume-indicator]])))

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

(defn- chart-liquidation-drag-prefill-actions
  [position-data suggestion]
  (when (and (map? position-data)
             (map? suggestion))
    [[:actions/select-account-info-tab :positions]
     [:actions/open-position-margin-modal
      (merge position-data
             {:prefill-source :chart-liquidation-drag
              :prefill-margin-mode (:mode suggestion)
              :prefill-margin-amount (:amount suggestion)
              :prefill-liquidation-target-price (:target-liquidation-price suggestion)
              :prefill-liquidation-current-price (:current-liquidation-price suggestion)})
      (:anchor suggestion)]]))

(defn- dispatch-chart-liquidation-drag-margin-prefill!
  ([trigger position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-prefill!
    (current-dispatch-fn)
    trigger
    position-data
    suggestion))
  ([dispatch-fn trigger position-data suggestion]
   (let [actions (chart-liquidation-drag-prefill-actions position-data suggestion)]
     (dispatch-chart-actions! dispatch-fn trigger actions))))

(defn- dispatch-chart-liquidation-drag-margin-preview!
  ([position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-preview!
    (current-dispatch-fn)
    position-data
    suggestion))
  ([dispatch-fn position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-prefill!
    dispatch-fn
    :chart-liquidation-drag-margin-preview
    position-data
    suggestion)))

(defn- dispatch-chart-liquidation-drag-margin-confirm!
  ([position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-confirm!
    (current-dispatch-fn)
    position-data
    suggestion))
  ([dispatch-fn position-data suggestion]
   (dispatch-chart-liquidation-drag-margin-prefill!
    dispatch-fn
    :chart-liquidation-drag-margin-confirm
    position-data
    suggestion)))

(defn- format-chart-overlay-size
  [value]
  (fmt/format-fixed-number value 2))

(defn- merge-main-series-markers
  [indicator-markers fill-markers position-overlay]
  (let [base-markers (cond
                       (vector? indicator-markers) indicator-markers
                       (sequential? indicator-markers) (vec indicator-markers)
                       :else [])
        fill-markers* (cond
                        (vector? fill-markers) fill-markers
                        (sequential? fill-markers) (vec fill-markers)
                        :else [])
        entry-marker (:entry-marker position-overlay)]
    (cond-> (into base-markers fill-markers*)
      (map? entry-marker) (conj entry-marker))))

;; Top menu component with timeframe selection and bars indicator
(defn chart-top-menu [state]
  (let [timeframes-dropdown-visible (get-in state [:chart-options :timeframes-dropdown-visible])
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        chart-type-dropdown-visible (get-in state [:chart-options :chart-type-dropdown-visible])
        selected-chart-type (get-in state [:chart-options :selected-chart-type] :candlestick)
        indicators-dropdown-visible (get-in state [:chart-options :indicators-dropdown-visible])
        volume-visible? (boolean (get-in state [:chart-options :volume-visible?] true))
        active-indicators (get-in state [:chart-options :active-indicators] {})
        indicators-search-term (get-in state [:chart-options :indicators-search-term] "")
        show-surface-freshness-cues?
        (boolean (get-in state [:websocket-ui :show-surface-freshness-cues?] false))
        websocket-health (get-in state [:websocket :health])
        freshness-cue (when show-surface-freshness-cues?
                        (ws-freshness/surface-cue websocket-health
                                                  {:topic "trades"
                                                   :selector {:coin (:active-asset state)}
                                                   :live-prefix "Last tick"}))]
    [:div.flex.items-center.border-b.border-gray-700.px-4.pt-2.pb-1.w-full.space-x-4.bg-base-100
     {:data-parity-id "chart-toolbar"}
     ;; Left side - Favorite timeframes + dropdown
     [:div.flex.items-center.space-x-1
      ;; Main timeframe buttons
      (for [key main-timeframes]
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:key key
          :class (if (= selected-timeframe key)
                   ["text-trading-green"]
                   ["text-gray-300" "hover:text-white" "hover:bg-gray-700"])
          :on {:click [[:actions/select-chart-timeframe key]]}}
         (name key)])
      ;; Additional timeframe button visible only when selected timeframe is not one of the main 3
      (when-not (contains? (set main-timeframes) selected-timeframe)
        [:button.relative.px-3.py-1.text-sm.font-medium.rounded.transition-colors
         {:class ["text-trading-green"]
          :on {:click [[:actions/toggle-timeframes-dropdown]]}}
         (name selected-timeframe)])

      ;; Dropdown for additional timeframes
      (timeframe-dropdown {:selected-timeframe selected-timeframe
                          :timeframes-dropdown-visible timeframes-dropdown-visible})]
     
     ;; Vertical divider
     [:div.w-px.h-6.bg-gray-700]
   
     ;; Chart type and indicators section
     [:div.flex.items-center.gap-1
     ;; Chart type dropdown
      (chart-type-dropdown {:selected-chart-type selected-chart-type
                           :chart-type-dropdown-visible chart-type-dropdown-visible})
      ;; Vertical divider between chart type and indicators
      [:div.w-px.h-6.bg-gray-700]
      ;; Indicators dropdown
      [:div.relative
       (let [active-count (count active-indicators)
             has-active-indicators? (pos? active-count)]
         [:button
          {:class (cond-> ["flex" "items-center" "gap-1.5" "h-8" "px-3" "text-base" "font-medium"
                           "rounded-none" "transition-colors"
                           "text-gray-300" "bg-gray-900/40"
                           "hover:text-white" "hover:bg-gray-800/70"
                           "focus:outline-none" "focus-visible:ring-2" "focus-visible:ring-slate-500/70"
                           "focus-visible:ring-offset-1" "focus-visible:ring-offset-base-100"]
                    indicators-dropdown-visible (into ["text-white" "bg-gray-800"])
                    has-active-indicators? (conj "text-gray-100"))
           :on {:click [[:actions/toggle-indicators-dropdown]]}
           :aria-label (if has-active-indicators?
                         (str "Indicators (" active-count " active)")
                         "Indicators")}
          [:span "Indicators"]
          (when has-active-indicators?
            [:span {:class ["text-xs" "text-gray-400"]} (str "(" active-count ")")])])
       (indicators-dropdown {:indicators-dropdown-visible indicators-dropdown-visible
                            :volume-visible? volume-visible?
                            :active-indicators active-indicators
                            :search-term indicators-search-term})]]

     (when freshness-cue
       ^{:replicant/key "chart-freshness-cue"}
       [:div {:class ["ml-auto" "flex" "items-center"]
              :data-role "chart-freshness-cue"}
        [:span {:class (case (:tone freshness-cue)
                         :success ["text-xs" "font-medium" "text-success" "tracking-wide"]
                         :warning ["text-xs" "font-medium" "text-warning" "tracking-wide"]
                         ["text-xs" "font-medium" "text-base-content/70" "tracking-wide"])}
         (:text freshness-cue)]])]))

;; Generic chart component that supports all chart types with volume
(defn chart-canvas
  ([candle-data chart-type active-indicators legend-meta selected-timeframe chart-runtime-options]
   (chart-canvas candle-data chart-type active-indicators legend-meta selected-timeframe chart-runtime-options [] nil))
  ([candle-data chart-type active-indicators legend-meta selected-timeframe chart-runtime-options open-order-overlays on-cancel-order]
   (let [{:keys [indicators-data indicator-markers]
         indicator-series-data :indicator-series}
         (derived-cache/memoized-indicator-outputs candle-data selected-timeframe active-indicators)
         position-overlay (:position-overlay chart-runtime-options)
         fill-markers (:fill-markers chart-runtime-options)
         on-liquidation-drag-preview (:on-liquidation-drag-preview chart-runtime-options)
         on-liquidation-drag-confirm (:on-liquidation-drag-confirm chart-runtime-options)
         series-options (:series-options chart-runtime-options)
         legend-deps (:legend-deps chart-runtime-options)
         persistence-deps (:persistence-deps chart-runtime-options)
         volume-visible? (boolean (get chart-runtime-options :volume-visible? true))
         on-hide-volume-indicator (:on-hide-volume-indicator chart-runtime-options)
         main-series-markers (merge-main-series-markers indicator-markers fill-markers position-overlay)
         overlay-deps {:on-cancel-order on-cancel-order
                       :format-price fmt/format-trade-price-plain
                       :format-size format-chart-overlay-size}
         position-overlay-deps {:format-price fmt/format-trade-price-plain
                                :format-size format-chart-overlay-size
                                :on-liquidation-drag-preview on-liquidation-drag-preview
                                :on-liquidation-drag-confirm on-liquidation-drag-confirm}
         volume-indicator-deps {:on-remove on-hide-volume-indicator}
         legend-key (str (or (:symbol legend-meta) "")
                         "-"
                         (or (:timeframe-label legend-meta) "")
                         "-"
                         (or (:venue legend-meta) "")
                         "-"
                         (or (:market-open? legend-meta) true)
                         "-"
                         volume-visible?)
         mark-visible-range-interaction!
         (fn [node]
           (chart-runtime/update-state! node
                                        update
                                        :visible-range-interaction-epoch
                                        (fnil inc 0)))
         reset-visible-range!
         (fn [node chart candles]
           (ci/apply-default-visible-range! chart candles)
           (mark-visible-range-interaction! node))
         sync-navigation-overlay!
         (fn [node chart-obj candles]
           (ci/sync-chart-navigation-overlay!
            chart-obj
            node
            candles
            {:on-interaction #(mark-visible-range-interaction! node)
             :on-reset (fn [chart* candles*]
                         (reset-visible-range! node chart* candles*))}))
         start-visible-range-persistence!
         (fn [node chart]
           (ci/subscribe-visible-range-persistence!
            chart
            selected-timeframe
            (assoc persistence-deps
                   :on-visible-range-change! #(mark-visible-range-interaction! node))))
         start-visible-range-restore!
         (fn [node chart candles]
           (let [runtime-state (chart-runtime/get-state node)
                 restore-token (inc (or (:visible-range-restore-token runtime-state) 0))
                 interaction-epoch (or (:visible-range-interaction-epoch runtime-state) 0)]
             (ci/apply-default-visible-range! chart candles)
             (chart-runtime/assoc-state! node
                                         :visible-range-restore-tried? true
                                         :visible-range-restore-token restore-token)
             (-> (ci/apply-persisted-visible-range!
                  chart
                  selected-timeframe
                  (assoc persistence-deps
                         :candles candles
                         :fallback-to-default? false
                         :allow-apply-fn
                         (fn []
                           (let [runtime-state* (chart-runtime/get-state node)]
                             (and (= restore-token
                                     (:visible-range-restore-token runtime-state*))
                                  (= interaction-epoch
                                     (or (:visible-range-interaction-epoch runtime-state*) 0)))))))
                 (.catch (fn [error]
                           (js/console.warn "Failed to restore persisted visible range:" error))))))
         mount! (fn [{:keys [:replicant/life-cycle :replicant/node]}]
                  (case life-cycle
                    :replicant.life-cycle/mount
                    (try
                      ;; Create chart with indicators support
                      (let [chart-obj (if (seq indicators-data)
                                        (ci/create-chart-with-indicators! node chart-type candle-data indicators-data
                                                                          {:series-options series-options
                                                                           :volume-visible? volume-visible?})
                                        (ci/create-chart-with-volume-and-series! node chart-type candle-data
                                                                                 {:series-options series-options
                                                                                  :volume-visible? volume-visible?}))
                            chart (.-chart chart-obj)
                            legend-control (ci/create-legend! node chart legend-meta legend-deps)
                            data-ready? (boolean (seq candle-data))]
                        (ci/set-main-series-markers! chart-obj main-series-markers)
                        (ci/sync-baseline-base-value-subscription! chart-obj chart-type)
                        (ci/sync-position-overlays! chart-obj node position-overlay position-overlay-deps)
                        (ci/sync-open-order-overlays! chart-obj node open-order-overlays overlay-deps)
                        (ci/sync-volume-indicator-overlay! chart-obj node candle-data volume-indicator-deps)
                        (sync-navigation-overlay! node chart-obj candle-data)
                        (chart-runtime/set-state! node {:chart-obj chart-obj
                                                        :legend-control legend-control
                                                        :chart-type chart-type
                                                        :visible-range-restore-tried? false
                                                        :visible-range-restore-token 0
                                                        :visible-range-interaction-epoch 0
                                                        :visible-range-persistence-subscribed? false
                                                        :visible-range-cleanup nil})
                        (when data-ready?
                          (start-visible-range-restore! node chart candle-data)
                          (chart-runtime/assoc-state! node
                                                     :visible-range-cleanup
                                                     (start-visible-range-persistence! node chart)
                                                     :visible-range-persistence-subscribed? true)))
                      (catch :default e
                        (js/console.error "Error in chart:" e)))
                    :replicant.life-cycle/update
                    (try
                      (let [runtime-state (chart-runtime/get-state node)
                            chart-obj (:chart-obj runtime-state)
                            legend-control (:legend-control runtime-state)
                            previous-chart-type (:chart-type runtime-state)
                            visible-range-restore-tried? (boolean (:visible-range-restore-tried? runtime-state))
                            visible-range-persistence-subscribed? (boolean (:visible-range-persistence-subscribed? runtime-state))
                            main-series (when chart-obj (.-mainSeries ^js chart-obj))
                            volume-series (when chart-obj (.-volumeSeries ^js chart-obj))
                            indicator-series (when chart-obj (.-indicatorSeries ^js chart-obj))
                            chart (when chart-obj (.-chart ^js chart-obj))]
                        (when (and chart previous-chart-type (not= previous-chart-type chart-type))
                          (let [time-scale (.timeScale ^js chart)
                                visible-range (.getVisibleLogicalRange ^js time-scale)
                                new-series (ci/add-series! chart chart-type)]
                            (when main-series
                              (try
                                (.removeSeries ^js chart main-series)
                                (catch :default _ nil)))
                            (set! (.-mainSeries ^js chart-obj) new-series)
                            (ci/set-series-data! new-series candle-data chart-type series-options)
                            (ci/sync-baseline-base-value-subscription! chart-obj chart-type)
                            (when visible-range
                              (try
                                (.setVisibleLogicalRange ^js time-scale visible-range)
                                (catch :default _ nil)))))
                        (when (and main-series (or (nil? previous-chart-type) (= previous-chart-type chart-type)))
                          (ci/set-series-data! main-series candle-data chart-type series-options))
                        (when chart-obj
                          (ci/sync-baseline-base-value-subscription! chart-obj chart-type))
                        (when volume-series
                          (ci/set-volume-data! volume-series candle-data))
                        (when (and chart-obj chart (seq candle-data) (not visible-range-restore-tried?))
                          (start-visible-range-restore! node chart candle-data))
                        (when (and chart-obj chart (seq candle-data) (not visible-range-persistence-subscribed?))
                          (chart-runtime/assoc-state! node
                                                     :visible-range-cleanup
                                                     (start-visible-range-persistence! node chart)
                                                     :visible-range-persistence-subscribed? true))
                        (when chart-obj
                          (ci/set-main-series-markers! chart-obj main-series-markers)
                          (ci/sync-position-overlays! chart-obj node position-overlay position-overlay-deps)
                          (ci/sync-open-order-overlays! chart-obj node open-order-overlays overlay-deps))
                        (ci/sync-volume-indicator-overlay! chart-obj node candle-data volume-indicator-deps)
                        (sync-navigation-overlay! node chart-obj candle-data)
                        (when (and indicator-series (seq indicator-series-data))
                          (doseq [[idx series-entry] (map-indexed vector indicator-series-data)]
                            (when-let [^js indicator-series-entry (aget ^js indicator-series idx)]
                              (when-let [series (.-series indicator-series-entry)]
                                (ci/set-indicator-data! series (:data series-entry))))))
                        (when legend-control
                          (.update ^js legend-control legend-meta))
                        (when (and chart-obj (not= previous-chart-type chart-type))
                          (chart-runtime/assoc-state! node :chart-type chart-type)))
                      (catch :default e
                        (js/console.error "Error updating chart:" e)))
                    :replicant.life-cycle/unmount
                    (let [{:keys [chart-obj legend-control visible-range-cleanup]} (chart-runtime/get-state node)
                          chart (when chart-obj (.-chart ^js chart-obj))]
                      (when legend-control
                        (.destroy ^js legend-control))
                      (ci/clear-open-order-overlays! chart-obj)
                      (ci/clear-position-overlays! chart-obj)
                      (ci/clear-volume-indicator-overlay! chart-obj)
                      (ci/clear-chart-navigation-overlay! chart-obj)
                      (ci/clear-baseline-base-value-subscription! chart-obj)
                      (when visible-range-cleanup
                        (try
                          (visible-range-cleanup)
                          (catch :default _
                            nil)))
                      (when chart
                        (try
                          (.remove ^js chart)
                          (catch :default _ nil)))
                      (chart-runtime/clear-state! node))
                    nil))]
     [:div {:class ["w-full" "relative" "flex-1" "h-full" "min-h-[360px]" "bg-base-100" "trading-chart-host"]
            :data-parity-id "chart-canvas"
            :replicant/key (str "chart-" (hash active-indicators) "-" legend-key "-" volume-visible?)
            :replicant/on-render mount!}])))

(defn trading-chart-view [state]
  (let [active-asset (:active-asset state)
        dispatch-fn (current-dispatch-fn)
        active-open-orders (chart-open-orders state)
        active-fills (chart-fills state)
        candles-map (:candles state)
        active-market (or (:active-market state) {})
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        active-position (trading-state/position-for-active-asset state)
        active-position-data (when (map? active-position)
                               {:position active-position
                                :dex (:dex active-market)})
        ;; Use selected timeframe from state
        selected-timeframe (get-in state [:chart-options :selected-timeframe] :1d)
        selected-chart-type (get-in state [:chart-options :selected-chart-type] :candlestick)
        api-response (get-in candles-map [active-asset selected-timeframe] {})
        ;; Check for error state
        has-error? (contains? api-response :error)
        ;; Handle both possible data structures: direct array or wrapped in :data
        raw-candles (if (vector? api-response)
                      api-response  ; Direct array
                      (get api-response :data []))  ; Wrapped in :data
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
        on-liquidation-drag-preview (memoized-liquidation-drag-preview-callback
                                     dispatch-fn
                                     active-position-data)
        on-liquidation-drag-confirm (memoized-liquidation-drag-confirm-callback
                                     dispatch-fn
                                     active-position-data)
        on-cancel-order (memoized-cancel-order-callback dispatch-fn)
        on-hide-volume-indicator (memoized-hide-volume-indicator-callback dispatch-fn)
        symbol (or active-asset "—")
        timeframe-label (str/upper-case (name selected-timeframe))
        price-decimals (or (:price-decimals active-market)
                           (:priceDecimals active-market)
                           (:pxDecimals active-market)
                           (fmt/price-decimals-from-raw (:markRaw active-market))
                           (fmt/price-decimals-from-raw (:prevDayRaw active-market)))
        volume-visible? (boolean (get-in state [:chart-options :volume-visible?] true))
        chart-runtime-options (memoized-chart-runtime-options
                               price-decimals
                               volume-visible?
                               on-hide-volume-indicator
                               active-asset
                               candle-data
                               on-liquidation-drag-preview
                               on-liquidation-drag-confirm
                               position-overlay
                               fill-markers
                               show-fill-markers?)
        legend-meta (memoized-legend-meta symbol timeframe-label candle-data)]
    [:div {:class ["w-full" "h-full"]
           :data-parity-id "chart-panel"}
     ;; Chart container with consistent width for both menu and chart
     [:div {:class ["w-full" "h-full" "flex" "flex-col"]}
      ;; Add the top menu above the chart
      (chart-top-menu state)
      (if has-error?
        [:div {:class ["text-red-500" "p-4" "flex-1"]} "Error fetching chart data."]
        (chart-canvas candle-data
                      selected-chart-type
                      (get-in state [:chart-options :active-indicators] {})
                      legend-meta
                      selected-timeframe
                      chart-runtime-options
                      active-open-orders
                      on-cancel-order))]]))
