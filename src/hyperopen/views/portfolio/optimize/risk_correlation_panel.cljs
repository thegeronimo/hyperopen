(ns hyperopen.views.portfolio.optimize.risk-correlation-panel
  "CORRELATION tab of the Equal Risk risk-contribution card (designer spec
  2026-07-11): the correlation heatmap at the card's FULL width — the room
  matters as held-asset counts approach the payload's 12-asset matrix cap —
  with a POSITION P&L / UNDERLYING RETURNS toggle. Position-P&L correlations
  are the underlying correlations sign-flipped by position sides, the actual
  correlation between the trades as held. The per-asset contribution
  breakdown that used to share this tab lives on the BREAKDOWN tab
  (risk-asset-breakdown-panel) as of the 2026-07-11 per-asset redesign.

  The mode toggle is DOM state (label-wrapped sr-only radios + scoped :has()
  CSS, exactly like the card's tabs): BOTH heatmap grids and captions are
  pre-rendered and CSS swaps them, so toggling never re-renders. Every cell
  carries a native multi-line title with both correlations and the
  portfolio-risk verdict — hover answers 'economically correlated but held
  opposite?' without a chart change."
  (:require [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]))

(defn- mode-tab
  [radio-name mode text]
  [:label {:class ["optimizer-risk-corr-mode-tab"]
           :data-role (str "portfolio-optimizer-risk-corr-mode-" mode)}
   [:input {:type "radio"
            :name radio-name
            :class ["sr-only"]
            :data-corr-mode mode}]
   text])

(defn- corr-cell
  [row-entry col-entry {:keys [diagonal?] :as cell} mode]
  (let [value (get cell mode)
        strength (when (number? value)
                   (js/Math.round (* 72 (js/Math.abs value))))]
    [:span {:class ["optimizer-risk-corr-cell" "font-mono" "tabular-nums"]
            :data-role "portfolio-optimizer-risk-corr-cell"
            :data-row (:instrument-id row-entry)
            :data-col (:instrument-id col-entry)
            :data-sign (cond
                         (nil? value) "missing"
                         (neg? value) "negative"
                         :else "positive")
            :data-diagonal (when diagonal? "true")
            :style {:--corr-strength (str (or strength 0) "%")}
            :title (structure-model/cell-title row-entry col-entry cell)}
     (structure-model/format-correlation value)]))

(defn- matrix-grid
  "One heatmap for one mode. Column count is dynamic, so the tracks are an
  inline style (static-CSS track rules only cover fixed layouts; Tailwind
  arbitrary grid-cols are unreliable under JIT watch)."
  [{:keys [entries cells]} mode]
  (let [template (str "minmax(2.75rem, auto) repeat("
                      (count entries)
                      ", minmax(0, 1fr))")]
    [:div {:class ["optimizer-risk-corr-grid"]
           :data-role (str "portfolio-optimizer-risk-corr-grid-" (name mode))
           :data-corr-mode (name mode)
           :data-dense (when (> (count entries) 12) "true")
           :style {:grid-template-columns template}}
     (into [:div {:class ["contents"]}]
           (cons [:span {:class ["optimizer-risk-corr-corner"]}]
                 (map (fn [{:keys [label]}]
                        [:span {:class ["optimizer-risk-corr-col-label"
                                        "truncate" "font-mono"]}
                         label])
                      entries)))
     (into [:div {:class ["contents"]}]
           (mapcat (fn [row-entry row-cells]
                     (cons [:span {:class ["optimizer-risk-corr-row-label"
                                           "truncate" "font-mono"]}
                            (:label row-entry)]
                           (map (fn [col-entry cell]
                                  (corr-cell row-entry col-entry cell mode))
                                entries
                                row-cells)))
                   entries
                   cells))]))

(defn- legend
  [mode]
  [:div {:class ["optimizer-risk-corr-legend"]
         :data-role "portfolio-optimizer-risk-corr-legend"
         :data-corr-mode (name mode)}
   [:div {:class ["optimizer-risk-corr-legend-bar"]}]
   (into [:div {:class ["optimizer-risk-corr-legend-labels" "font-mono"]}]
         (map (fn [value] [:span value]))
         ["-1.0" "-0.5" "0.0" "0.5" "1.0"])
   [:p {:class ["optimizer-risk-corr-caption"]
        :title (if (= :position mode)
                 "Corr(position i P&L, position j P&L) = side i × side j × underlying correlation. A short flips the sign of its P&L correlations."
                 "The plain correlation of the assets' returns, ignoring which side you hold.")}
    (if (= :position mode)
      "Correlation between held position P&Ls"
      "Correlation between underlying asset returns")
    [:span {:class ["optimizer-risk-corr-info"]} "ⓘ"]]])

(defn- heatmap-block
  [{:keys [hidden-count] :as corr-model} radio-name]
  [:div {:class ["optimizer-risk-corr-block"]
         :data-role "portfolio-optimizer-risk-correlation-heatmap"}
   [:div {:class ["optimizer-risk-corr-head"]}
    [:div {:class ["min-w-0"]}
     [:p {:class ["optimizer-risk-corr-title"]
          :data-corr-only "position"}
      "Position P&L correlation"]
     [:p {:class ["optimizer-risk-corr-title"]
          :data-corr-only "underlying"}
      "Underlying return correlation"]]
    [:div {:class ["optimizer-risk-corr-mode-tabs"]
           :data-role "portfolio-optimizer-risk-corr-mode-tabs"}
     (mode-tab radio-name "position" "Position P&L")
     (mode-tab radio-name "underlying" "Underlying returns")]]
   [:div {:data-corr-only "position"}
    (matrix-grid corr-model :position)
    (legend :position)]
   [:div {:data-corr-only "underlying"}
    (matrix-grid corr-model :underlying)
    (legend :underlying)]
   (when (pos? (or hidden-count 0))
     [:p {:class ["mt-2" "text-xs" "text-trading-muted"]
          :data-role "portfolio-optimizer-risk-corr-overflow"}
      (str "+ " hidden-count " more held asset"
           (when (not= 1 hidden-count) "s")
           " not shown (the largest risk shares are kept).")])])

(defn correlation-panel
  "Panel body for the CORRELATION tab; nil when the result predates
  :risk-structure or holds no positions (the card then drops the tab). The
  heatmap is the layout grid's only child, so it takes the full card width."
  [{:keys [result kpi-strip]}]
  (when-let [corr-model (structure-model/correlation-model result)]
    (let [radio-name (str (structure-model/risk-view-radio-name result)
                          "-corr-mode")]
      [:div
       kpi-strip
       [:div {:class ["optimizer-risk-corr-layout"]}
        (heatmap-block corr-model radio-name)]])))
