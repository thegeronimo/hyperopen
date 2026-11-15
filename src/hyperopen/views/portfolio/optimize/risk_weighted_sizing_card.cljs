(ns hyperopen.views.portfolio.optimize.risk-weighted-sizing-card
  "Risk-weighted sizing results centerpiece: the SIZING FIDELITY card — one
  row per asset showing |weight| × σ as a bar against the shared equal ideal,
  proving the objective's promise (every free asset carries the same
  standalone risk; a row a cap, lock, or turnover budget moved off its 1/σ
  seed wears a \"capped\" tag instead of silently breaking the pattern).
  Replaces the efficient-frontier chart for :inverse-volatility — the
  objective is deterministic (closed-form seed + one projection), not
  selected from a frontier, and must not be plotted as if it were. Reuses the
  risk-balance card's visual grammar (header, row grid, reading note) so the
  two covariance-only centerpieces read as siblings.

  The header's right side is the sibling card's DOM-state tab switcher
  (SIZING FIDELITY / CONTRIBUTIONS / DIVERSIFICATION / CORRELATION DRIVERS /
  RISK / RETURN): label-wrapped sr-only radios toggled by scoped :has() CSS
  in optimizer/results.css, no app state. The secondary tabs are the
  objective-AGNOSTIC analytics the engine now emits for covariance-only runs
  generally: CONTRIBUTIONS draws the signed Euler shares as a target-free
  diagnostic (no equal-target line, no target/deviation columns, no quality
  chrome — this objective does not target contributions), and the
  DIVERSIFICATION / CORRELATION / RISK / RETURN bodies are the equal-risk
  card's own panels reused verbatim (with the copy that named Equal Risk
  parameterized). Tabs degrade away on persisted results that predate the
  analytics sections, leaving the original single-view card."
  (:require [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]
            [hyperopen.portfolio.optimizer.application.view-model.inverse-volatility-results
             :as inverse-volatility-results]
            [hyperopen.views.portfolio.optimize.risk-asset-breakdown-panel
             :as asset-breakdown-panel]
            [hyperopen.views.portfolio.optimize.risk-contributions-card
             :as contributions-card]
            [hyperopen.views.portfolio.optimize.risk-correlation-panel
             :as correlation-panel]
            [hyperopen.views.portfolio.optimize.risk-diversification-summary
             :as diversification-summary]
            [hyperopen.views.portfolio.optimize.risk-return-context
             :as risk-return-context]))

(def ^:private objective-label "Risk-weighted sizing")

(defn- format-pct
  ([value] (format-pct value 1))
  ([value decimals]
   (if (and (number? value) (js/isFinite value))
     (str (.toFixed (* 100 value) decimals) "%")
     "—")))

(defn- sizing-lane
  "The row's plot area: the |w|·σ bar from zero, over a shared dashed ideal
  line drawn per-lane (the rows are plain HTML, so each lane carries its own
  1px marker at the same x — they read as one continuous line)."
  [x {:keys [label risk-weight moved-off-seed?]} ideal]
  [:div {:class ["relative" "h-3" "min-w-0" "bg-base-200/30"]}
   (when (number? risk-weight)
     [:div {:class ["absolute" "inset-y-0" "left-0"
                    (if moved-off-seed? "bg-warning/60" "bg-primary/50")]
            :data-role "portfolio-optimizer-sizing-bar"
            :data-moved (str (boolean moved-off-seed?))
            :title (str label " · |w|·σ " (format-pct risk-weight))
            :style {:width (str (max 0.4 (x risk-weight)) "%")}}])
   (when (number? ideal)
     [:div {:class ["absolute" "inset-y-0" "w-px" "bg-warning"]
            :data-role "portfolio-optimizer-sizing-ideal-line"
            :style {:left (str (x ideal) "%")}}])])

(defn- sizing-row
  [x ideal {:keys [instrument-id label weight sigma risk-weight short?
                   moved-off-seed? turnover-limited?] :as row}]
  [:div {:class ["optimizer-risk-balance-row"]
         :data-role "portfolio-optimizer-sizing-row"
         :data-instrument-id instrument-id
         :title (str label
                     " · " (if short? "short" "long") " " (format-pct weight)
                     " · σ " (format-pct sigma 0)
                     " · |w|·σ " (format-pct risk-weight))}
   [:span {:class ["optimizer-risk-balance-label" "truncate"]} label]
   (sizing-lane x row ideal)
   [:span {:class ["font-mono" "tabular-nums" "text-right" "text-xs"
                   "text-trading-text"]
           :data-role "portfolio-optimizer-sizing-risk-weight"}
    (format-pct risk-weight)]
   [:span {:class ["text-right"]}
    (when moved-off-seed?
      [:span {:class ["border" "border-warning/50" "px-1.5" "py-0.5"
                      "font-mono" "text-[0.5rem]" "font-semibold" "uppercase"
                      "tracking-[0.08em]" "text-warning"]
              :data-role "portfolio-optimizer-sizing-capped-tag"}
       "capped"])
    (when turnover-limited?
      [:span {:class ["border" "border-warning/50" "px-1.5" "py-0.5"
                      "font-mono" "text-[0.5rem]" "font-semibold" "uppercase"
                      "tracking-[0.08em]" "text-warning"]
              :data-role "portfolio-optimizer-sizing-turnover-limited-tag"}
       "turnover-limited"])]])

(defn- sizing-legend-row
  [ideal]
  [:div {:class ["optimizer-risk-balance-row" "optimizer-risk-balance-legend-row"]}
   [:span]
   [:div {:class ["optimizer-risk-balance-legend"]}
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-balance-legend-dash"]}]
     (str "Equal ideal (" (format-pct ideal) ")")]
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-balance-dot"]}]
     "|weight| × σ"]]
   [:span {:class ["optimizer-risk-balance-col-head"]} "|w|·σ"]
   [:span {:class ["optimizer-risk-balance-col-head"]} "Bound"]])

(defn- sizing-reading-note
  [{:keys [moved-count]}]
  [:p {:class ["optimizer-risk-balance-reading"]
       :data-role "portfolio-optimizer-risk-weighted-sizing-reading"}
   [:span {:class ["optimizer-risk-balance-reading-label"]} "Reading this"]
   [:span {:class ["optimizer-risk-balance-reading-sep"]} "·"]
   (str "Bars show each asset's standalone risk share (|weight| × its own "
        "volatility) against the equal ideal. Risk-weighted sizing sizes by "
        "standalone volatility only and ignores correlations — that is what "
        "keeps every selected asset in the book.")
   (when (pos? (or moved-count 0))
     (str " " moved-count
          (if (= 1 moved-count)
            " row was moved off its ideal size by a limit (tagged)."
            " rows were moved off their ideal sizes by limits (tagged).")))])

(defn- sizing-panel-body
  [{:keys [rows ideal-risk-weight max-risk-weight max-sizing-deviation]
    :as model}]
  (let [span (* 1.1 (max 1e-9 (or max-risk-weight 0)))
        x (fn [value] (-> (* 100 (/ value span)) (max 0) (min 100)))]
    [:div
     [:p {:class ["mb-2" "text-right" "font-mono" "text-[0.62rem]" "uppercase"
                  "tracking-[0.08em]" "text-trading-muted/70"]
          :data-role "portfolio-optimizer-risk-weighted-sizing-status"}
      "Deterministic · closed form + projection"]
     [:div {:class ["optimizer-risk-balance-plot-frame"]
            :data-role "portfolio-optimizer-risk-weighted-sizing-chart"}
      (sizing-legend-row ideal-risk-weight)
      (into [:div {:class ["relative" "space-y-1"]}]
            (map (partial sizing-row x ideal-risk-weight))
            rows)]
     [:p {:class ["mt-2" "font-mono" "text-xs" "text-trading-muted"]
          :data-role "portfolio-optimizer-risk-weighted-sizing-deviation"}
      (str "Max sizing deviation among free assets · "
           (format-pct max-sizing-deviation))]
     (sizing-reading-note model)]))

(defn- contributions-legend-row
  [current?]
  [:div {:class ["optimizer-risk-balance-row" "optimizer-risk-balance-legend-row"]}
   [:span]
   [:div {:class ["optimizer-risk-balance-legend"]}
    (when current?
      [:span {:class ["optimizer-risk-balance-legend-item"]}
       [:span {:class ["optimizer-risk-balance-current"]}]
       "Current"])
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-balance-dot"]}]
     "Recommended"]]
   [:span {:class ["optimizer-risk-balance-col-head"]} "Share"]
   [:span {:class ["optimizer-risk-balance-col-head"]} "Current"]])

(defn- contribution-row
  "One diagnostic chart row: signed-share lane plus plain Share / Current
  cells. Deliberately NO target or deviation columns and no per-row target
  tick — the rows carry no :target-share, so the reused lane draws none."
  [scale {:keys [instrument-id label share current-share] :as row}]
  [:div {:class ["optimizer-risk-balance-row"]
         :data-role "portfolio-optimizer-sizing-contribution-row"
         :data-instrument-id instrument-id
         :title (str label
                     " · contribution " (format-pct share)
                     (when (number? current-share)
                       (str " · current " (format-pct current-share))))}
   [:span {:class ["optimizer-risk-balance-label" "truncate"]} label]
   (contributions-card/contribution-lane scale row)
   [:span {:class ["optimizer-risk-balance-target-cell" "font-mono"
                   "tabular-nums"]
           :data-role "portfolio-optimizer-sizing-contribution-share"}
    (format-pct share)]
   [:span {:class ["optimizer-risk-balance-current-cell" "font-mono"
                   "tabular-nums"]
           :data-role "portfolio-optimizer-sizing-contribution-current"}
    (format-pct current-share)]])

(defn- contributions-reading-note
  [{:keys [negative-count]}]
  [:p {:class ["optimizer-risk-balance-reading"]
       :data-role "portfolio-optimizer-sizing-contributions-reading"}
   [:span {:class ["optimizer-risk-balance-reading-label"]} "Reading this"]
   [:span {:class ["optimizer-risk-balance-reading-sep"]} "·"]
   (str "Diagnostic only. Bars show the signed share of total portfolio "
        "volatility each position owns at the recommended weights. "
        "Risk-weighted sizing does not target these contributions — it sizes "
        "by each asset's own volatility and ignores correlations.")
   (when (and (number? negative-count) (pos? negative-count))
     (str " " negative-count
          (if (= 1 negative-count)
            " position hedges the book (negative risk contribution)."
            " positions hedge the book (negative risk contributions).")))])

(defn- contributions-overflow-line
  [{:keys [hidden-count hidden-max-pts]}]
  (when (pos? (or hidden-count 0))
    [:p {:class ["mt-2" "text-xs" "text-trading-muted"]
         :data-role "portfolio-optimizer-sizing-contributions-overflow"}
     (str "+ " hidden-count " more, each owning at most "
          (.toFixed (or hidden-max-pts 0) 1)
          " pts of risk (the largest shares are shown).")]))

(defn- contributions-body
  [{:keys [rows current?] :as model}]
  (let [scale (contributions-card/lane-scale {:rows rows :target-share nil})]
    [:div {:data-role "portfolio-optimizer-sizing-contributions"}
     [:div {:class ["optimizer-risk-balance-plot-frame"]
            :data-role "portfolio-optimizer-sizing-contributions-chart"}
      (contributions-legend-row current?)
      [:div {:class ["optimizer-risk-balance-rows" "relative"]}
       (contributions-card/plot-backdrop scale nil)
       (into [:div {:class ["relative" "space-y-1"]}]
             (map (partial contribution-row scale))
             rows)]
      (contributions-card/axis-rows scale)]
     (contributions-reading-note model)
     (contributions-overflow-line model)]))

(def ^:private diversification-explainer
  (str "Risk-weighted sizing sizes by each asset's own volatility and "
       "ignores correlations. These benchmarks show what the resulting "
       "book's correlations still do to total volatility, on one absolute "
       "annualized-volatility scale."))

(defn- tab-label
  "A tab is a label wrapping its own visually-hidden radio (the sibling
  card's idiom): checked state lives in the DOM and the scoped :has() CSS in
  optimizer/results.css does the active styling + panel switching. The
  radio group/ids reuse the structure model's result-scoped naming, so the
  two covariance-only cards share one deep-link grammar."
  [result view text]
  [:label {:class ["optimizer-risk-balance-tab"]
           :data-role (str "portfolio-optimizer-sizing-view-tab-" view)}
   [:input {:type "radio"
            :name (structure-model/risk-view-radio-name result)
            :id (structure-model/risk-view-radio-id result view)
            :class ["sr-only"]
            :data-risk-view view}]
   text])

(defn risk-weighted-sizing-card
  "Renders nil unless the result carries the :inverse-volatility section
  (present only on Risk-weighted sizing runs). Secondary tabs degrade away
  per body: CONTRIBUTIONS needs the diagnostic :risk-contributions section,
  DIVERSIFICATION / CORRELATION DRIVERS need :risk-structure, and RISK /
  RETURN disappears when no portfolio point has return metrics (covariance-
  only runs can carry an invalid return model — same degradation as the
  equal-risk card). `selected-risk-instrument` (app state, set by
  Allocation-row clicks and the breakdown tab's Change-asset select) picks
  which asset the DIVERSIFICATION tab's per-asset panel explains."
  ([result] (risk-weighted-sizing-card result nil))
  ([result {:keys [selected-risk-instrument]}]
   (when-let [model (inverse-volatility-results/sizing-model result)]
     (let [contributions (inverse-volatility-results/contributions-model result)
           risk-return-panel (risk-return-context/risk-return-panel
                              result
                              {:objective-label objective-label})
           correlation-body (correlation-panel/correlation-panel
                             {:result result})
           breakdown-body (asset-breakdown-panel/breakdown-tab-panel
                           {:result result
                            :rows (structure-model/breakdown-rows
                                   result
                                   (:rows contributions))
                            :overflow-note (contributions-overflow-line
                                            contributions)
                            :selected-instrument-id selected-risk-instrument
                            :summary-tiles? false})
           tabs? (or contributions breakdown-body correlation-body
                     risk-return-panel)]
       [:section {:class ["optimizer-risk-balance"
                          "optimizer-risk-balance--sizing"
                          "rounded-xl" "border" "border-base-300"]
                  :data-role "portfolio-optimizer-risk-weighted-sizing-card"}
        [:div {:class ["optimizer-risk-balance-header"]}
         [:div {:class ["min-w-0"]}
          [:p {:class ["optimizer-risk-balance-title"]}
           "Sizing fidelity"]
          [:p {:class ["optimizer-risk-balance-subtitle"]}
           "|weight| × volatility per asset vs the equal ideal"]]
         (when tabs?
           [:div {:class ["optimizer-risk-balance-tabs"]
                  :data-role "portfolio-optimizer-sizing-view-tabs"}
            (tab-label result "sizing" "Sizing Fidelity")
            (when contributions
              (tab-label result "contributions" "Contributions"))
            (when breakdown-body
              (tab-label result "breakdown" "Diversification"))
            (when correlation-body
              (tab-label result "correlation" "Correlation Drivers"))
            (when risk-return-panel
              (tab-label result "risk-return" "Risk / Return"))])]
        [:div {:class ["optimizer-risk-balance-panel"
                       "optimizer-risk-balance-panel--sizing"]}
         (sizing-panel-body model)]
        (when contributions
          [:div {:class ["optimizer-risk-balance-panel"
                         "optimizer-risk-balance-panel--contributions"]}
           (contributions-body contributions)])
        (when breakdown-body
          [:div {:class ["optimizer-risk-balance-panel"
                         "optimizer-risk-balance-panel--breakdown"]}
           (diversification-summary/diversification-summary
            result
            {:help-id-prefix
             (str (structure-model/risk-view-radio-name result)
                  "-diversification-help")
             :explainer diversification-explainer})
           breakdown-body])
        (when correlation-body
          [:div {:class ["optimizer-risk-balance-panel"
                         "optimizer-risk-balance-panel--correlation"]}
           correlation-body])
        (when risk-return-panel
          [:div {:class ["optimizer-risk-balance-panel"
                         "optimizer-risk-balance-panel--risk-return"]}
           risk-return-panel])]))))
