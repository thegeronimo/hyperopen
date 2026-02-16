(ns hyperopen.views.trading-chart.utils.chart-interop.legend
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.utils.chart-options :as chart-options]))

(defn- normalize-time-key
  [value]
  (let [business-day (cond
                       (and (map? value)
                            (number? (:year value))
                            (number? (:month value))
                            (number? (:day value)))
                       value

                       (and value
                            (not (number? value))
                            (not (string? value))
                            (not (keyword? value)))
                       (let [converted (js->clj value :keywordize-keys true)]
                         (when (and (map? converted)
                                    (number? (:year converted))
                                    (number? (:month converted))
                                    (number? (:day converted)))
                           converted))

                       :else
                       nil)]
    (cond
      (number? value) (str "ts:" value)
      (string? value) (str "txt:" value)
      business-day (str "bd:" (:year business-day) "-" (:month business-day) "-" (:day business-day))
      :else nil)))

(defn- build-legend-state
  [legend-meta]
  (let [symbol (or (:symbol legend-meta) "—")
        timeframe-label (or (:timeframe-label legend-meta) "—")
        venue (or (:venue legend-meta) "—")
        header-text (str symbol " · " timeframe-label " · " venue)
        candle-data (or (:candle-data legend-meta) [])
        candle-lookup (when (seq candle-data)
                        (loop [remaining candle-data
                               prev-close nil
                               acc {}]
                          (if (empty? remaining)
                            acc
                            (let [c (first remaining)
                                  key* (normalize-time-key (:time c))
                                  acc* (if key*
                                         (assoc acc key* {:candle c :prev-close prev-close})
                                         acc)
                                  prev-close* (:close c)]
                              (recur (rest remaining) prev-close* acc*)))))
        latest-candle (last candle-data)
        latest-prev-close (when (> (count candle-data) 1)
                            (:close (nth candle-data (- (count candle-data) 2))))
        latest-entry (when latest-candle
                       {:candle latest-candle
                        :prev-close latest-prev-close})]
    {:header-text header-text
     :candle-lookup candle-lookup
     :latest-entry latest-entry}))

(defn- create-value-node!
  [row label]
  (let [label-node (js/document.createElement "span")
        value-node (js/document.createElement "span")]
    (set! (.-textContent label-node) label)
    (set! (.-cssText (.-style label-node)) "color:#9ca3af;")
    (set! (.-textContent value-node) "--")
    (.appendChild row label-node)
    (.appendChild row value-node)
    value-node))

(defn create-legend!
  "Create legend element that adapts to different chart types."
  [container chart legend-meta]
  (let [container-style (.-style container)]
    (when (or (not (.-position container-style))
              (= (.-position container-style) "static"))
      (set! (.-position container-style) "relative")))

  (let [legend (js/document.createElement "div")
        legend-font-family (chart-options/resolve-chart-font-family)
        header-row (js/document.createElement "div")
        header-text-node (js/document.createElement "span")
        values-row (js/document.createElement "div")
        open-node (create-value-node! values-row "O")
        high-node (create-value-node! values-row "H")
        low-node (create-value-node! values-row "L")
        close-node (create-value-node! values-row "C")
        delta-node (js/document.createElement "span")]
    (set! (.-cssText (.-style legend))
          (str "position:absolute;left:12px;top:8px;z-index:100;"
               "font-size:12px;font-family:" legend-font-family ";"
               "font-variant-numeric:tabular-nums lining-nums;"
               "font-feature-settings:'tnum' 1,'lnum' 1;"
               "line-height:1.4;font-weight:500;color:#ffffff;"
               "padding:6px 10px;border-radius:6px;box-shadow:none;pointer-events:none;"))
    (set! (.-cssText (.-style header-row))
          "display:flex;align-items:center;gap:6px;font-weight:600;")
    (set! (.-cssText (.-style header-text-node)) "color:#e5e7eb;")
    (.appendChild header-row header-text-node)
    (set! (.-cssText (.-style values-row))
          "display:flex;align-items:center;gap:8px;")
    (set! (.-cssText (.-style delta-node)) "color:#9ca3af;font-weight:600;")
    (.appendChild values-row delta-node)
    (.appendChild legend header-row)
    (.appendChild legend values-row)
    (.appendChild container legend)
    (let [state (atom (build-legend-state legend-meta))
          format-price (fn [price]
                         (when (number? price)
                           (fmt/format-trade-price-plain price)))
          format-delta (fn [delta]
                         (when (number? delta)
                           (let [formatted (fmt/format-trade-price-delta delta)]
                             (if (>= delta 0) (str "+" formatted) formatted))))
          format-pct (fn [pct]
                       (when (number? pct)
                         (let [formatted (.toFixed pct 2)]
                           (if (>= pct 0) (str "+" formatted "%") (str formatted "%")))))
          render-legend! (fn [entry]
                           (let [{:keys [header-text]} @state]
                             (set! (.-textContent header-text-node) header-text)
                             (if (and entry (:candle entry))
                               (let [c (:candle entry)
                                     baseline (or (:prev-close entry) (:open c))
                                     close (:close c)
                                     delta (when (and close baseline) (- close baseline))
                                     pct (when (and delta baseline (not= baseline 0)) (* 100 (/ delta baseline)))
                                     delta-color (cond
                                                   (nil? delta) "#9ca3af"
                                                   (>= delta 0) "#10b981"
                                                   :else "#ef4444")]
                                 (set! (.-textContent open-node) (or (format-price (:open c)) "--"))
                                 (set! (.-textContent high-node) (or (format-price (:high c)) "--"))
                                 (set! (.-textContent low-node) (or (format-price (:low c)) "--"))
                                 (set! (.-textContent close-node) (or (format-price (:close c)) "--"))
                                 (set! (.-textContent delta-node)
                                       (str (or (format-delta delta) "--")
                                            " ("
                                            (or (format-pct pct) "--")
                                            ")"))
                                 (set! (.-color (.-style delta-node)) delta-color))
                               (do
                                 (set! (.-textContent open-node) "--")
                                 (set! (.-textContent high-node) "--")
                                 (set! (.-textContent low-node) "--")
                                 (set! (.-textContent close-node) "--")
                                 (set! (.-textContent delta-node) "-- (--)")
                                 (set! (.-color (.-style delta-node)) "#9ca3af")))))
          update-legend (fn [param]
                          (let [{:keys [candle-lookup latest-entry]} @state
                                lookup-key (when (and param (some? (.-time param)))
                                             (normalize-time-key (.-time param)))
                                entry (when lookup-key
                                        (get candle-lookup lookup-key))]
                            (render-legend! (or entry latest-entry))))
          update! (fn [new-meta]
                    (reset! state (build-legend-state new-meta))
                    (update-legend nil))
          destroy! (fn []
                     (try
                       (.unsubscribeCrosshairMove ^js chart update-legend)
                       (catch :default _ nil))
                     (when (.-parentNode legend)
                       (.removeChild (.-parentNode legend) legend)))]
      (.subscribeCrosshairMove ^js chart update-legend)
      (update-legend nil)
      #js {:update update! :destroy destroy!})))
