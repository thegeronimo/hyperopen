(ns hyperopen.views.trading-chart.utils.chart-interop.legend
  (:require [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.trading-chart.utils.chart-interop.candle-sync-policy :as candle-sync-policy]
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

(defn- vectorize-candles
  [candles]
  (if (vector? candles) candles (vec candles)))

(defn- legend-entry
  [candle prev-close]
  (when candle
    {:candle candle
     :prev-close prev-close}))

(defn- assoc-candle-entry
  [lookup candle entry]
  (if-let [key* (normalize-time-key (:time candle))]
    (assoc lookup key* entry)
    lookup))

(defn- build-candle-lookup-state
  [candles]
  (let [candles* (vectorize-candles candles)]
    (loop [remaining candles*
           prev-close nil
           acc {}
           latest-entry nil]
      (if-let [candle (first remaining)]
        (let [entry (legend-entry candle prev-close)]
          (recur (rest remaining)
                 (:close candle)
                 (assoc-candle-entry acc candle entry)
                 entry))
        {:candles candles*
         :candle-lookup acc
         :latest-entry latest-entry}))))

(defn- reconcile-candle-lookup-state
  [previous-state candles]
  (let [candles* (vectorize-candles candles)
        previous-state* (select-keys (or previous-state {}) [:candles :candle-lookup :latest-entry])
        previous-candles (:candles previous-state*)
        decision (if (contains? previous-state* :candles)
                   (candle-sync-policy/infer-decision previous-candles candles*)
                   {:mode :full-reset})]
    (case (:mode decision)
      :noop (or previous-state previous-state*)

      :append-last
      (let [next-last (peek candles*)
            previous-last (peek previous-candles)
            next-entry (legend-entry next-last (:close previous-last))]
        {:candles candles*
         :candle-lookup (assoc-candle-entry (:candle-lookup previous-state*) next-last next-entry)
         :latest-entry next-entry})

      :update-last
      (let [next-last (peek candles*)
            previous-last (peek previous-candles)
            previous-key (normalize-time-key (:time previous-last))
            next-prev-close (when (> (count candles*) 1)
                              (:close (nth candles* (- (count candles*) 2))))
            next-entry (legend-entry next-last next-prev-close)
            lookup* (cond-> (or (:candle-lookup previous-state*) {})
                      previous-key (dissoc previous-key))]
        {:candles candles*
         :candle-lookup (assoc-candle-entry lookup* next-last next-entry)
         :latest-entry next-entry})

      (build-candle-lookup-state candles*))))

(defn- build-legend-state
  ([legend-meta]
   (build-legend-state nil legend-meta))
  ([previous-state legend-meta]
   (let [symbol (or (:display-symbol legend-meta) (:symbol legend-meta) "—")
         timeframe-label (or (:timeframe-label legend-meta) "—")
         venue (or (:venue legend-meta) "—")
         header-text (str symbol " · " timeframe-label " · " venue)
         market-open? (not (false? (:market-open? legend-meta)))
         candle-state (reconcile-candle-lookup-state previous-state
                                                     (or (:candle-data legend-meta) []))]
     (assoc candle-state
            :header-text header-text
            :market-open? market-open?))))

(defn- create-value-node!
  [document row label]
  (let [pair-node (let [doc document]
                    (.createElement doc "span"))
        label-node (let [doc document]
                     (.createElement doc "span"))
        value-node (let [doc document]
                     (.createElement doc "span"))]
    (set! (.-cssText (.-style pair-node)) "display:inline-flex;align-items:center;gap:2px;color:#9ca3af;")
    (set! (.-textContent label-node) label)
    (set! (.-cssText (.-style label-node)) "opacity:0.78;")
    (set! (.-textContent value-node) "--")
    (.appendChild pair-node label-node)
    (.appendChild pair-node value-node)
    (.appendChild row pair-node)
    {:pair-node pair-node
     :value-node value-node}))

(defn- legend-document
  [document]
  (or document (aget js/globalThis "document")))

(defn- ensure-container-position!
  [container]
  (let [container-style (.-style container)]
    (when (or (not (.-position container-style))
              (= (.-position container-style) "static"))
      (set! (.-position container-style) "relative"))))

(defn- legend-formatters
  [{:keys [format-price format-delta format-pct]}]
  {:format-price (or format-price
                     (fn [price]
                       (when (number? price)
                         (fmt/format-trade-price-plain price))))
   :format-delta (or format-delta
                     (fn [delta]
                       (when (number? delta)
                         (let [formatted (fmt/format-trade-price-delta delta)]
                           (if (>= delta 0) (str "+" formatted) formatted)))))
   :format-pct (or format-pct
                   (fn [pct]
                     (when (number? pct)
                       (let [formatted (.toFixed pct 2)]
                         (if (>= pct 0) (str "+" formatted "%") (str formatted "%"))))))})

(defn- legend-market-status-state
  [market-open?]
  (if market-open?
    {:label "Market open"
     :color "#00c278"
     :box-shadow "0 0 0 4px rgba(0,194,120,0.16), 0 0 12px rgba(0,194,120,0.28)"}
    {:label "Market closed"
     :color "#6b7280"
     :box-shadow "0 0 0 4px rgba(107,114,128,0.12), 0 0 10px rgba(107,114,128,0.18)"}))

(defn- legend-set-metric-colors!
  [open-pair-node high-pair-node low-pair-node close-pair-node color]
  (doseq [pair-node [open-pair-node high-pair-node low-pair-node close-pair-node]]
    (set! (.-color (.-style pair-node)) color)))

(defn- legend-render-empty-values!
  [nodes]
  (let [{:keys [open-node high-node low-node close-node delta-node
                open-pair-node high-pair-node low-pair-node close-pair-node]} nodes]
    (set! (.-textContent open-node) "--")
    (set! (.-textContent high-node) "--")
    (set! (.-textContent low-node) "--")
    (set! (.-textContent close-node) "--")
    (legend-set-metric-colors! open-pair-node high-pair-node low-pair-node close-pair-node
                               "#9ca3af")
    (set! (.-textContent delta-node) "-- (--)")
    (set! (.-color (.-style delta-node)) "#9ca3af")))

(defn- legend-render-candle-values!
  [nodes entry formatters]
  (let [{:keys [open-node high-node low-node close-node delta-node
                open-pair-node high-pair-node low-pair-node close-pair-node]} nodes
        {:keys [format-price format-delta format-pct]} formatters
        c (:candle entry)
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
    (legend-set-metric-colors! open-pair-node high-pair-node low-pair-node close-pair-node
                               delta-color)
    (set! (.-textContent delta-node)
          (str (or (format-delta delta) "--")
               " ("
               (or (format-pct pct) "--")
               ")"))
    (set! (.-color (.-style delta-node)) delta-color)))

(defn- legend-render-state!
  [nodes state entry formatters]
  (let [{:keys [header-text market-open?]} @state
        {:keys [label color box-shadow]} (legend-market-status-state market-open?)]
    (set! (.-textContent (:header-text-node nodes)) header-text)
    (set! (.-backgroundColor (.-style (:market-status-node nodes))) color)
    (set! (.-boxShadow (.-style (:market-status-node nodes))) box-shadow)
    (.setAttribute (:market-status-node nodes) "aria-label" label)
    (.setAttribute (:market-status-node nodes) "title" label)
    (if (and entry (:candle entry))
      (legend-render-candle-values! nodes entry formatters)
      (legend-render-empty-values! nodes))))

(defn- legend-render-entry
  [state param]
  (let [{:keys [candle-lookup latest-entry]} @state
        lookup-key (when (and param (some? (.-time param)))
                     (normalize-time-key (.-time param)))
        entry (when lookup-key
                (get candle-lookup lookup-key))]
    (or entry latest-entry)))

(defn- create-legend-dom!
  [document container]
  (let [legend (let [doc document]
                 (.createElement doc "div"))
        legend-font-family (chart-options/resolve-chart-font-family)
        header-row (let [doc document]
                     (.createElement doc "div"))
        header-text-node (let [doc document]
                           (.createElement doc "span"))
        market-status-node (let [doc document]
                             (.createElement doc "span"))
        values-row (let [doc document]
                     (.createElement doc "div"))
        open-value (create-value-node! document values-row "O")
        high-value (create-value-node! document values-row "H")
        low-value (create-value-node! document values-row "L")
        close-value (create-value-node! document values-row "C")
        delta-node (let [doc document]
                     (.createElement doc "span"))]
    (set! (.-cssText (.-style legend))
          (str "position:absolute;left:12px;top:8px;z-index:100;"
               "font-size:12px;font-family:" legend-font-family ";"
               "font-variant-numeric:tabular-nums lining-nums;"
               "font-feature-settings:'tnum' 1,'lnum' 1;"
               "line-height:1.2;font-weight:500;color:#ffffff;"
               "padding:4px 0;display:flex;align-items:center;gap:10px;"
               "max-width:calc(100% - 24px);white-space:nowrap;pointer-events:none;overflow:hidden;"))
    (set! (.-cssText (.-style header-row))
          "display:flex;align-items:center;gap:8px;font-weight:600;min-width:0;flex:0 1 auto;")
    (set! (.-cssText (.-style header-text-node))
          "color:#d1d5db;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;")
    (set! (.-cssText (.-style market-status-node))
          "display:inline-block;width:8px;height:8px;border-radius:9999px;flex:0 0 auto;")
    (set! (.-backgroundColor (.-style market-status-node)) "#00c278")
    (set! (.-boxShadow (.-style market-status-node))
          "0 0 0 4px rgba(0,194,120,0.16), 0 0 12px rgba(0,194,120,0.28)")
    (.setAttribute market-status-node "data-role" "chart-market-status")
    (.setAttribute market-status-node "role" "img")
    (.setAttribute market-status-node "aria-label" "Market open")
    (.setAttribute market-status-node "title" "Market open")
    (.appendChild header-row header-text-node)
    (.appendChild header-row market-status-node)
    (set! (.-cssText (.-style values-row))
          "display:flex;align-items:center;gap:8px;flex:0 0 auto;")
    (set! (.-cssText (.-style delta-node)) "color:#9ca3af;font-weight:600;flex:0 0 auto;")
    (.appendChild values-row delta-node)
    (.appendChild legend header-row)
    (.appendChild legend values-row)
    (.appendChild container legend)
    {:legend legend
     :header-text-node header-text-node
     :market-status-node market-status-node
     :delta-node delta-node
     :open-node (:value-node open-value)
     :open-pair-node (:pair-node open-value)
     :high-node (:value-node high-value)
     :high-pair-node (:pair-node high-value)
     :low-node (:value-node low-value)
     :low-pair-node (:pair-node low-value)
     :close-node (:value-node close-value)
     :close-pair-node (:pair-node close-value)}))

(defn create-legend!
  "Create legend element that adapts to different chart types."
  ([container chart legend-meta]
   (create-legend! container chart legend-meta {}))
  ([container chart legend-meta {:keys [document
                                        format-price
                                        format-delta
                                        format-pct]}]
   (let [document* (legend-document document)]
     (when-not document*
       (throw (js/Error. "Legend rendering requires a DOM document.")))
     (ensure-container-position! container)
     (let [nodes (create-legend-dom! document* container)
           state (atom (build-legend-state nil legend-meta))
           formatters (legend-formatters {:format-price format-price
                                          :format-delta format-delta
                                          :format-pct format-pct})
           update-legend (fn [param]
                           (legend-render-state! nodes
                                                 state
                                                 (legend-render-entry state param)
                                                 formatters))
           update! (fn [new-meta]
                     (swap! state build-legend-state new-meta)
                     (update-legend nil))
           destroy! (fn []
                      (try
                        (.unsubscribeCrosshairMove ^js chart update-legend)
                        (catch :default _ nil))
                      (when (.-parentNode (:legend nodes))
                        (.removeChild (.-parentNode (:legend nodes))
                                      (:legend nodes))))]
       (.subscribeCrosshairMove ^js chart update-legend)
       (update-legend nil)
       #js {:update update! :destroy destroy!}))))
