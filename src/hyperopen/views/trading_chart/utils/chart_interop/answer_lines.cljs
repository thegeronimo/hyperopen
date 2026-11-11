(ns hyperopen.views.trading-chart.utils.chart-interop.answer-lines
  "The All answers chart: one Lightweight Charts line per answer on a fixed
   0–100% scale.

   Lines hold Yes prices in dollars (0..1). The scale is pinned to 0..1 by
   every series' autoscale provider, and `minMove` 0.25 makes the price
   scale's own tick builder step in quarters (its span never drops below
   `minMove`), so the axis reads 0 / 25 / 50 / 75 / 100%.

   Lifecycle: `on-render` mounts on the node, updates line data only when a
   coin's candle rows change identity, re-weights lines when the selected
   answer moves, asks for a sync when the event or timeframe changed since
   the stored plan, and refreshes the last candles on a slow timer while the
   tab is visible."
  (:require ["lightweight-charts" :refer [createChart LineSeries]]
            [hyperopen.outcomes.domain.answer-chart :as answer-chart]
            [hyperopen.views.trading-chart.runtime-state :as chart-runtime]
            [hyperopen.views.trading-chart.utils.chart-options :as chart-options]
            [hyperopen.views.trading-chart.utils.theme-colors :as theme-colors]))

(def ^:dynamic *refresh-interval-ms*
  "Background refresh of the drawn lines' last candles (tests may rebind)."
  answer-chart/refresh-interval-ms)

(def sync-debounce-ms
  "A plan request waits this long; a newer one (rapid timeframe flips)
   replaces it, so only the last asks for candles."
  250)

(def ^:dynamic *set-timeout* (fn [f ms] (js/setTimeout f ms)))

(def ^:dynamic *clear-timeout* (fn [id] (js/clearTimeout id)))

(def ^:private fixed-range
  #js {:priceRange #js {:minValue 0 :maxValue 1}
       :margins #js {:above 14 :below 14}})

(defn- autoscale-provider
  [_original]
  fixed-range)

(def price-format
  "Shared by every line, so the scale's formatter source reads percent."
  #js {:type "custom"
       :minMove 0.25
       :formatter (fn [value] (or (answer-chart/format-chance value) (str value)))
       :tickmarksFormatter (fn [values]
                             (clj->js (answer-chart/tick-labels (vec (array-seq values)))))})

(defn- chart-options
  []
  (clj->js (-> (chart-options/base-chart-options)
               (assoc-in [:rightPriceScale :autoScale] true)
               (assoc-in [:rightPriceScale :alignLabels] true)
               (assoc-in [:rightPriceScale :minimumWidth] 72)
               ;; The autoscale provider's pixel margins are the only padding:
               ;; the default fractional margins would push ticks past 100%.
               (assoc-in [:rightPriceScale :scaleMargins] {:top 0 :bottom 0})
               ;; The scale is fixed: no dragging it out of 0–100%.
               (assoc :handleScale {:axisPressedMouseMove {:time true :price false}
                                    :axisDoubleClickReset {:time true :price false}
                                    :mouseWheel true
                                    :pinch true}
                      :handleScroll {:mouseWheel true
                                     :pressedMouseMove true
                                     :horzTouchDrag true
                                     :vertTouchDrag false}))))

(defn- resolve-color
  "Lines carry a --ho-chart-answer-N token; the series needs the colour."
  [color]
  (if (and (string? color) (.startsWith color "--"))
    (theme-colors/token color)
    color))

(defn- line-options
  [{:keys [color width short-label]}]
  #js {:color (resolve-color color)
       :lineWidth width
       :title short-label
       :priceFormat price-format
       :autoscaleInfoProvider autoscale-provider
       :lastValueVisible true
       :priceLineVisible false
       :crosshairMarkerVisible true})

(defn- finite
  [value]
  (let [n (js/parseFloat value)]
    (when (js/isFinite n) n)))

(defn line-points
  "Close prices of candle rows as line points: [{:time <s> :value <dollars>}],
   time ascending, one point per time."
  [rows]
  (->> rows
       (keep (fn [row]
               (let [t (or (:t row) (:time row))
                     close (finite (or (:c row) (:close row)))]
                 (when (and (number? t) (some? close))
                   {:time (if (> t 100000000000) (js/Math.floor (/ t 1000)) t)
                    :value close}))))
       (reduce (fn [acc point] (assoc acc (:time point) point)) (sorted-map))
       vals
       vec))

(defn- dispatch!
  [dispatch-fn actions]
  (when (ifn? dispatch-fn)
    (dispatch-fn {:replicant/trigger :chart-outcome-answers} actions)))

(defn- tab-visible?
  []
  (or (not (exists? js/document))
      (= "visible" (.-visibilityState js/document))))

(defn request-sync!
  "Ask for a plan when the event, its answers or the timeframe moved away
   from the stored plan. Debounced: each new request key cancels the pending
   one, and the action reads the store when it runs, so the last wins."
  [node {:keys [request-key plan-key]}]
  (let [{:keys [scheduled-key sync-timer]} (chart-runtime/get-state node)]
    (cond
      (or (nil? request-key) (= request-key plan-key))
      (when sync-timer
        (*clear-timeout* sync-timer)
        (chart-runtime/assoc-state! node :sync-timer nil :scheduled-key nil))

      (not= request-key scheduled-key)
      (do (when sync-timer (*clear-timeout* sync-timer))
          (chart-runtime/assoc-state!
           node
           :scheduled-key request-key
           :sync-timer (*set-timeout*
                        (fn []
                          (chart-runtime/assoc-state! node :sync-timer nil)
                          (dispatch! (:dispatch-fn (chart-runtime/get-state node))
                                     [[:actions/sync-outcome-answer-chart]]))
                        sync-debounce-ms))))))

(defn- tail-update?
  "True when `rows` only changed or added the last candle of `previous`, so
   one `update` call redraws it (the selected answer's live candle)."
  [previous rows]
  (let [n (count previous)
        before-last (- n 2)]
    (and (pos? n)
         (seq rows)
         (= (:t (first previous)) (:t (first rows)))
         (<= n (count rows) (inc n))
         ;; a refetch replaces earlier rows too: those need a full redraw
         (or (neg? before-last)
             (identical? (nth previous before-last) (nth rows before-last))))))

(defn- set-line-data!
  [^js series previous rows]
  (if (tail-update? previous rows)
    (when-let [point (peek (line-points [(peek rows)]))]
      (try (.update series (clj->js point))
           (catch :default _
             (.setData series (clj->js (line-points rows))))))
    (.setData series (clj->js (line-points rows)))))

(defn- window-key
  "Which data window the lines show: coin and first candle time of each
   drawn line. A new key means a new window to fit."
  [series-by-coin]
  (vec (sort (keep (fn [[coin {:keys [rows]}]]
                     (when (seq rows) [coin (:t (first rows))]))
                   series-by-coin))))

(defn- next-line-entry
  "Draw `rows` when the slot has them; while it is loading keep what this
   timeframe already drew; a failed or empty slot draws nothing (the legend
   says why)."
  [^js series entry {:keys [rows status]}]
  (cond
    (seq rows)
    (do (when-not (identical? rows (:rows entry))
          (set-line-data! series (:rows entry) rows))
        rows)

    (= :loading status)
    (:rows entry)

    :else
    (do (when (seq (:rows entry)) (.setData series #js []))
        nil)))

(defn sync-lines!
  [node {:keys [lines timeframe]}]
  (let [{:keys [chart series-by-coin fitted-key] :as state} (chart-runtime/get-state node)
        ;; A new timeframe never shows the old one's rows on its time axis.
        new-timeframe? (and (contains? state :timeframe) (not= timeframe (:timeframe state)))
        series-by-coin (if new-timeframe?
                         (reduce-kv (fn [acc coin entry]
                                      (when (seq (:rows entry)) (.setData ^js (:series entry) #js []))
                                      (assoc acc coin (assoc entry :rows nil)))
                                    {}
                                    (or series-by-coin {}))
                         series-by-coin)
        wanted (set (map :coin lines))
        kept (reduce-kv (fn [acc coin entry]
                          (if (contains? wanted coin)
                            (assoc acc coin entry)
                            (do (try (.removeSeries ^js chart (:series entry))
                                     (catch :default _ nil))
                                acc)))
                        {}
                        (or series-by-coin {}))
        next-series
        (reduce (fn [acc {:keys [coin color width short-label] :as line}]
                  (let [{:keys [series] :as entry} (get acc coin)
                        series (or series (.addSeries ^js chart LineSeries (line-options line)))
                        style [color width short-label]]
                    (when (and entry (not= style (:style entry)))
                      (.applyOptions ^js series #js {:color (resolve-color color)
                                                     :lineWidth width
                                                     :title short-label}))
                    (assoc acc coin {:series series
                                     :style style
                                     :color color
                                     :rows (next-line-entry series entry line)})))
                kept
                lines)
        window (window-key next-series)]
    (chart-runtime/assoc-state! node :series-by-coin next-series :timeframe timeframe)
    (when (and (seq window) (not= window fitted-key))
      (.fitContent ^js (.timeScale ^js chart))
      (chart-runtime/assoc-state! node :fitted-key window))))

(defn- restyle!
  "Theme switch: chart chrome and line colours re-read the tokens."
  [node]
  (let [{:keys [chart series-by-coin]} (chart-runtime/get-state node)]
    (when chart
      (.applyOptions ^js chart (chart-options))
      (doseq [[_ {:keys [series color]}] series-by-coin]
        (.applyOptions ^js series #js {:color (resolve-color color)})))))

(defn- observe-theme!
  [node]
  (when (and (exists? js/MutationObserver) (exists? js/document))
    (let [observer (js/MutationObserver. (fn [_] (restyle! node)))]
      (.observe observer (.-documentElement js/document)
                #js {:attributes true :attributeFilter #js ["data-theme"]})
      observer)))

(defn- mount!
  [node {:keys [dispatch-fn timeframe] :as context}]
  (let [chart (createChart node (chart-options))
        timer (js/setInterval (fn []
                                (when (tab-visible?)
                                  (dispatch! (:dispatch-fn (chart-runtime/get-state node))
                                             [[:actions/sync-outcome-answer-chart {:refresh? true}]])))
                              *refresh-interval-ms*)]
    (chart-runtime/set-state! node {:answers-chart? true
                                    :chart chart
                                    :timer timer
                                    :theme-observer (observe-theme! node)
                                    :dispatch-fn dispatch-fn
                                    :timeframe timeframe
                                    :series-by-coin {}})
    (sync-lines! node context)
    (request-sync! node context)))

(defn- update!
  [node {:keys [dispatch-fn] :as context}]
  (chart-runtime/assoc-state! node :dispatch-fn dispatch-fn)
  (sync-lines! node context)
  (request-sync! node context))

(defn- unmount!
  [node]
  (let [{:keys [chart timer sync-timer theme-observer]} (chart-runtime/get-state node)]
    (when timer (js/clearInterval timer))
    (when sync-timer (*clear-timeout* sync-timer))
    (when theme-observer (.disconnect ^js theme-observer))
    (when chart
      (try (.remove ^js chart) (catch :default _ nil)))
    (chart-runtime/clear-state! node)))

(defn on-render
  [context]
  (fn [{:keys [:replicant/life-cycle :replicant/node]}]
    (case life-cycle
      :replicant.life-cycle/mount
      (try (mount! node context)
           (catch :default e (js/console.error "Error in answers chart:" e)))

      :replicant.life-cycle/update
      (try (update! node context)
           (catch :default e (js/console.error "Error updating answers chart:" e)))

      :replicant.life-cycle/unmount
      (unmount! node)

      nil)))
