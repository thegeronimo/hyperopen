(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.actions :as actions]
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
   (fn [indicator-markers fill-markers entry-marker]
     (let [base-markers (cond
                          (vector? indicator-markers) indicator-markers
                          (sequential? indicator-markers) (vec indicator-markers)
                          :else [])
           fill-markers* (cond
                           (vector? fill-markers) fill-markers
                           (sequential? fill-markers) (vec fill-markers)
                           :else [])]
       (cond-> (into base-markers fill-markers*)
         (map? entry-marker) (conj entry-marker))))))

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
         fill-markers (:fill-markers chart-runtime-options)
         on-liquidation-drag-preview (:on-liquidation-drag-preview chart-runtime-options)
         on-liquidation-drag-confirm (:on-liquidation-drag-confirm chart-runtime-options)
         series-options (:series-options chart-runtime-options)
         legend-deps (:legend-deps chart-runtime-options)
         persistence-deps (:persistence-deps chart-runtime-options)
         volume-visible? (boolean (get chart-runtime-options :volume-visible? true))
         on-hide-volume-indicator (:on-hide-volume-indicator chart-runtime-options)
         main-series-markers (memoized-main-series-markers indicator-markers
                                                           fill-markers
                                                           (:entry-marker position-overlay))
         overlay-deps {:on-cancel-order on-cancel-order
                       :format-price fmt/format-trade-price-plain
                       :format-size format-chart-overlay-size}
         position-overlay-deps {:format-price fmt/format-trade-price-plain
                                :format-size format-chart-overlay-size
                                :on-liquidation-drag-preview on-liquidation-drag-preview
                                :on-liquidation-drag-confirm on-liquidation-drag-confirm}
         volume-indicator-deps {:on-remove on-hide-volume-indicator}
         context-menu-deps {:format-price fmt/format-trade-price-plain
                            :price-decimals (:price-decimals series-options)
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
         chart-accessible-label (str (or (:symbol legend-meta) "Asset")
                                     " price chart, "
                                     (or (:timeframe-label legend-meta) "selected")
                                     " timeframe")
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
                     :volume-visible? volume-visible?
                     :main-series-markers main-series-markers
                     :position-overlay position-overlay
                     :position-overlay-deps position-overlay-deps
                     :open-order-overlays open-order-overlays
                     :overlay-deps overlay-deps
                     :volume-indicator-deps volume-indicator-deps
                     :context-menu-deps context-menu-deps
                     :schedule-decoration-frame! *schedule-chart-decoration-frame!*
                     :cancel-decoration-frame! *cancel-chart-decoration-frame!*})]
     [:div {:class ["w-full" "min-w-0" "relative" "flex-1" "min-h-[360px]" "overflow-hidden" "bg-base-100" "trading-chart-host"]
            :data-parity-id "chart-canvas"
            :data-role "trading-chart-canvas"
            :role "region"
            :aria-label chart-accessible-label
            :tabindex 0
            :replicant/key (str "chart-" (hash active-indicators) "-" legend-key "-" volume-visible?)
            :replicant/on-render on-render}])))

(defn trading-chart-view
  [state]
  (let [dispatch-fn (actions/current-dispatch-fn)
        {:keys [has-error?
                candle-data
                selected-chart-type
                selected-timeframe
                active-indicators
                legend-meta
                chart-runtime-options
                active-open-orders
                on-cancel-order]} (vm/chart-view-model state dispatch-fn)]
    [:div {:class ["w-full" "h-full" "min-h-0" "min-w-0" "overflow-hidden"]
           :data-parity-id "chart-panel"}
     [:div {:class ["w-full" "h-full" "flex" "flex-col" "min-h-0" "min-w-0" "overflow-hidden"]}
      (chart-top-menu state)
      (if has-error?
        [:div {:class ["text-red-500" "p-4" "flex-1"]} "Error fetching chart data."]
        (chart-canvas candle-data
                      selected-chart-type
                      active-indicators
                      legend-meta
                      selected-timeframe
                      chart-runtime-options
                      active-open-orders
                      on-cancel-order))]]))
