(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.actions :as actions]
            [hyperopen.views.trading-chart.answers-chart :as answers-chart]
            [hyperopen.views.trading-chart.answers-vm :as answers-vm]
            [hyperopen.views.trading-chart.derived-cache :as derived-cache]
            [hyperopen.views.trading-chart.runtime :as runtime]
            [hyperopen.views.trading-chart.toolbar :as toolbar]
            [hyperopen.views.trading-chart.vm :as vm]))

(def main-timeframes toolbar/main-timeframes)

(def ^:dynamic *schedule-chart-decoration-frame!*
  runtime/default-schedule-decoration-frame!)

(def ^:dynamic *cancel-chart-decoration-frame!*
  runtime/default-cancel-decoration-frame!)

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

(def ^:private memoized-main-series-markers
  (memoize-last
   ;; Only indicator markers ride on the series' built-in markers; the
   ;; trader's own fills are drawn by the trades layer (`:chart-trades`).
   (fn [indicator-markers]
     (cond
       (vector? indicator-markers) indicator-markers
       (sequential? indicator-markers) (vec indicator-markers)
       :else []))))

(defn- format-chart-overlay-size
  [value]
  (fmt/format-fixed-number value 2))

(defn chart-top-menu
  [state]
  (toolbar/chart-top-menu state))

(defn chart-canvas
  ([candle-data chart-type active-indicators legend-meta selected-timeframe chart-runtime-options]
   (chart-canvas candle-data chart-type active-indicators legend-meta selected-timeframe chart-runtime-options [] nil))
  ([candle-data chart-type active-indicators legend-meta selected-timeframe chart-runtime-options open-order-overlays on-cancel-order]
   (let [{:keys [indicators-data indicator-markers]
          indicator-series-data :indicator-series}
         (derived-cache/memoized-indicator-outputs candle-data
                                                   selected-timeframe
                                                   active-indicators
                                                   (boolean (:indicator-runtime-ready? chart-runtime-options)))
         position-overlay (:position-overlay chart-runtime-options)
         on-liquidation-drag-preview (:on-liquidation-drag-preview chart-runtime-options)
         on-liquidation-drag-confirm (:on-liquidation-drag-confirm chart-runtime-options)
         series-options (:series-options chart-runtime-options)
         legend-deps (:legend-deps chart-runtime-options)
         persistence-deps (:persistence-deps chart-runtime-options)
         volume-visible? (boolean (get chart-runtime-options :volume-visible? true))
         on-hide-volume-indicator (:on-hide-volume-indicator chart-runtime-options)
         on-history-backfill-request (:on-history-backfill-request chart-runtime-options)
         history-backfill-loading? (boolean (:history-backfill-loading? chart-runtime-options))
         main-series-markers (memoized-main-series-markers indicator-markers)
         {:keys [format-price copy-price]
          :or {format-price fmt/format-trade-price-plain}} (:price-formatters chart-runtime-options)
         overlay-deps {:on-cancel-order on-cancel-order
                       :format-price format-price
                       :format-size format-chart-overlay-size}
         position-overlay-deps {:format-price format-price
                                :format-size format-chart-overlay-size
                                :on-liquidation-drag-preview on-liquidation-drag-preview
                                :on-liquidation-drag-confirm on-liquidation-drag-confirm}
         volume-indicator-deps {:on-remove on-hide-volume-indicator}
         context-menu-deps {:format-price (or (:format-price copy-price) fmt/format-trade-price-plain)
                            :price-decimals (if copy-price
                                              (:price-decimals copy-price)
                                              (:price-decimals series-options))
                            :price-unit-suffix (:price-unit-suffix copy-price)
                            :context-key (str (or (:symbol legend-meta) "")
                                              "::"
                                              (name selected-timeframe))}
         legend-key (str (or (:symbol legend-meta) "")
                         "-"
                         (or (:timeframe-label legend-meta) "")
                         "-"
                         (or (:venue legend-meta) "")
                         "-"
                         (or (:market-open? legend-meta) true)
                         "-"
                         volume-visible?)
         chart-accessible-label (str (or (:display-symbol legend-meta) (:symbol legend-meta) "Asset")
                                     " price chart, "
                                     (or (:timeframe-label legend-meta) "selected")
                                     " timeframe"
                                     (when history-backfill-loading?
                                       ", loading older candles"))
         on-render (runtime/chart-canvas-on-render
                    {:candle-data candle-data
                     :chart-type chart-type
                     :indicators-data indicators-data
                     :indicator-series-data indicator-series-data
                     :legend-meta legend-meta
                     :legend-deps legend-deps
                     :series-options series-options
                     :selected-timeframe selected-timeframe
                     :persistence-deps persistence-deps
                     :on-history-backfill-request on-history-backfill-request
                     :volume-visible? volume-visible?
                     :main-series-markers main-series-markers
                     :chart-trades (:chart-trades chart-runtime-options)
                     :chart-trades-deps (:chart-trades-deps chart-runtime-options)
                     :position-overlay position-overlay
                     :position-overlay-deps position-overlay-deps
                     :open-order-overlays open-order-overlays
                     :overlay-deps overlay-deps
                     :vol-range-overlay (:vol-range-overlay chart-runtime-options)
                     :volume-indicator-deps volume-indicator-deps
                     :context-menu-deps context-menu-deps
                     :schedule-decoration-frame! *schedule-chart-decoration-frame!*
                     :cancel-decoration-frame! *cancel-chart-decoration-frame!*})]
     [:div {:class (cond-> ["w-full" "min-w-0" "relative" "flex-1" "min-h-[360px]"
                            "overflow-hidden" "bg-base-100" "trading-chart-host"]
                     history-backfill-loading?
                     (conj "trading-chart-host--history-loading"))
            :data-parity-id "chart-canvas"
            :data-role "trading-chart-canvas"
            :role "region"
            :aria-busy history-backfill-loading?
            :aria-label chart-accessible-label
            :tabindex 0
            ;; The outcome price unit is in the key: the legend's formatters
            ;; are fixed when it mounts, so a unit switch remounts the chart.
            :replicant/key (cond-> (str "chart-" (hash active-indicators) "-" legend-key "-" volume-visible?)
                             (:price-unit series-options)
                             (str "-" (name (:price-unit series-options))))
            :replicant/on-render on-render}])))

(defn- candle-chart
  [state dispatch-fn]
  (let [{:keys [has-error?
                candle-data
                selected-chart-type
                selected-timeframe
                active-indicators
                legend-meta
                chart-runtime-options
                active-open-orders
                on-cancel-order]} (vm/chart-view-model state dispatch-fn)]
    (if has-error?
      [:div {:class ["text-red-500" "p-4" "flex-1"]} "Error fetching chart data."]
      (chart-canvas candle-data
                    selected-chart-type
                    active-indicators
                    legend-meta
                    selected-timeframe
                    chart-runtime-options
                    active-open-orders
                    on-cancel-order))))

(defn trading-chart-view
  [state]
  (let [dispatch-fn (actions/current-dispatch-fn)]
    [:div {:class ["w-full" "h-full" "min-h-0" "min-w-0" "overflow-hidden"]
           :data-parity-id "chart-panel"}
     [:div {:class ["w-full" "h-full" "flex" "flex-col" "min-h-0" "min-w-0" "overflow-hidden"]}
      (chart-top-menu state)
      ;; All answers draws the event's lines whatever the selected coin's own
      ;; candle slot says (a failed load there must not blank every line).
      (if (answers-vm/all-answers? state)
        (answers-chart/answers-chart (answers-vm/answers-view-model state dispatch-fn))
        (candle-chart state dispatch-fn))]]))
