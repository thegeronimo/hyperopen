(ns hyperopen.views.portfolio.optimize.risk-contributions-card
  "Equal Risk results centerpiece: the RISK CONTRIBUTION BALANCE card — a
  horizontal diverging chart of each position's SIGNED Euler share of
  portfolio volatility against the equal 1/n target, built to the designer's
  2026-07-11 spec. One row per asset: recommended share as the sign-colored
  bar (colored by contribution SIGN, never by position side) capped with a
  green recommended dot, the current share as a gray outlined circle joined
  to the bar end by a dashed connector, and two mode-dependent right columns:
  purple Target / sign-colored Deviation while the fit is imperfect
  (:deviation display mode), or gray Current / neutral Shift once an :exact
  fit makes deviations dead zeros and the rebalance story is what's left
  (:shift mode — see balance-model). A per-row target tick draws only when a
  per-instrument target differs from the uniform equal target; otherwise the
  continuous dashed line IS the target. The axis always includes zero: a
  negative share means the position HEDGES the book, and positive shares can
  exceed 100% when another is negative — the signed shares still sum to 100%.
  No pies, donuts, stacked bars, or absolute contributions: those forms all
  lie once a contribution is negative.

  A five-cell KPI strip (risk-balance-kpi-strip, split for the namespace-size
  cap) sits between the header and the plot; the header's right side
  is a tab switcher (RISK CONTRIBUTION / BREAKDOWN / CORRELATION /
  RISK / RETURN) whose state lives entirely in the DOM as visually-hidden
  radio inputs toggled by scoped :has() CSS — no app state, same zero-state
  constraint the old <details> disclosure satisfied. The BREAKDOWN and
  CORRELATION tabs (designer specs 2026-07-11: correlation view + per-asset
  breakdown) render only when the result carries the :risk-structure payload
  section, so persisted pre-structure results keep the original pair; their
  panel bodies live in risk-asset-breakdown-panel (which defaults to one
  asset's decomposition and toggles to the all-assets chart) and
  risk-correlation-panel (the full-width P&L/underlying heatmap). Which asset
  the breakdown tab explains IS app state (Allocation-row clicks and the
  panel's Change-asset select both set it) — that selection routes data
  across cards, which DOM-state radios cannot.

  Rows come pre-ordered and capped from the equal-risk-results view-model
  (mode-dependent: worst deviations / signed-share order in :deviation mode,
  biggest shifts / current-share order in :shift mode, honest remainder line
  either way); the lanes are plain HTML so the zero/target lines
  stay 1px and markers stay round at any width. Replaces the
  efficient-frontier chart for :equal-risk — that objective is not selected
  from a frontier and must not be plotted as if it were."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
             :as equal-risk-results]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]
            [hyperopen.views.portfolio.optimize.risk-asset-breakdown-panel
             :as asset-breakdown-panel]
            [hyperopen.views.portfolio.optimize.risk-balance-kpi-strip
             :as kpi-strip]
            [hyperopen.views.portfolio.optimize.risk-correlation-panel
             :as correlation-panel]
            [hyperopen.views.portfolio.optimize.risk-diversification-summary
             :as diversification-summary]
            [hyperopen.views.portfolio.optimize.risk-return-context
             :as risk-return-context]))

(defn- format-pct
  ([value] (format-pct value 1))
  ([value decimals]
   (if (and (number? value) (js/isFinite value))
     (str (.toFixed (* 100 value) decimals) "%")
     "—")))

(defn lane-scale
  "One scale for every lane, fitted to the PRIMARY data — zero, every target,
  and every recommended share — padded and rounded to 5% so the domain reads
  as clean ticks. Public: the Risk-weighted sizing card's CONTRIBUTIONS
  diagnostic tab reuses the lane primitives (scale, backdrop, lane, axis)
  with no targets, so the two covariance-only cards draw contributions in
  one visual language. Current shares only extend the domain when they sit within
  35% of the primary span beyond it: a wildly unbalanced current book (one
  asset carrying ~100% of volatility is exactly why Equal Risk gets run)
  must not squash the recommended bars into a corner. Currents beyond that
  render as off-scale edge chevrons (see contribution-lane). Returns
  {:lo :hi :x (fraction-of-total -> 0..100 percent)}."
  [{:keys [rows target-share]}]
  (let [primary (concat [0 (or target-share 0)]
                        (keep :share rows)
                        (keep :target-share rows))
        lo-p (reduce min 0 primary)
        hi-p (reduce max 0 primary)
        reach (* 0.35 (max 0.05 (- hi-p lo-p)))
        values (concat primary
                       (filter #(<= (- lo-p reach) % (+ hi-p reach))
                               (keep :current-share rows)))
        lo0 (reduce min 0 values)
        hi0 (reduce max 0 values)
        pad (max 0.02 (* 0.08 (- hi0 lo0)))
        lo1 (- lo0 pad)
        hi1 (+ hi0 pad)
        mixed? (and (some #(< % -1e-9) values)
                    (some #(> % 1e-9) values))
        lo2 (if mixed? (min lo1 (* -0.45 hi1)) lo1)
        hi2 (if mixed? (max hi1 (* 0.45 (js/Math.abs lo2))) hi1)
        lo (* 0.05 (js/Math.floor (/ lo2 0.05)))
        hi (* 0.05 (js/Math.ceil (/ hi2 0.05)))
        span (max 1e-9 (- hi lo))]
    {:lo lo
     :hi hi
     :uniform-target target-share
     :x (fn [value]
          (-> (* 100 (/ (- value lo) span))
              (max 0)
              (min 100)))}))

(defn- tick-values
  [{:keys [lo hi]}]
  (let [span (- hi lo)
        step (or (first (filter #(<= (/ span %) 8)
                                [0.05 0.1 0.2 0.25 0.5 1 2 5]))
                 5)]
    (->> (range (js/Math.ceil (/ lo step)) (inc (js/Math.floor (/ hi step))))
         (map #(* step %)))))

(defn- legend-row
  "Legend + the two right column heads. The per-row tick legend entry appears
  only when some row actually draws a tick (a per-instrument target differing
  from the uniform equal target — see contribution-lane); for plain Equal
  Risk the continuous dashed line IS the target and a second 'Target (equal)'
  entry described the same pixel twice. Column heads follow the display mode:
  Target/Deviation grade an imperfect fit, Current/Shift tell the exact-fit
  rebalance story."
  [{:keys [target-share current? per-row-ticks? mode]}]
  [:div {:class ["optimizer-risk-balance-row" "optimizer-risk-balance-legend-row"]}
   [:span]
   [:div {:class ["optimizer-risk-balance-legend"]}
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-balance-legend-dash"]}]
     (str "Equal target (" (format-pct target-share) ")")]
    (when current?
      [:span {:class ["optimizer-risk-balance-legend-item"]}
       [:span {:class ["optimizer-risk-balance-current"]}]
       "Current"])
    (when per-row-ticks?
      [:span {:class ["optimizer-risk-balance-legend-item"]}
       [:span {:class ["optimizer-risk-balance-legend-tick"]}]
       "Per-asset target"])
    [:span {:class ["optimizer-risk-balance-legend-item"]}
     [:span {:class ["optimizer-risk-balance-dot"]}]
     "Recommended"]]
   [:span {:class ["optimizer-risk-balance-col-head"]}
    (if (= :shift mode) "Current" "Target")]
   [:span {:class ["optimizer-risk-balance-col-head"]}
    (if (= :shift mode) "Shift" "Deviation")]])

(defn plot-backdrop
  "Gridlines, the zero axis, and the continuous dashed target line (only when
  `target-share` is numeric), drawn once behind the whole row stack in a
  same-grid overlay so they read as one plot."
  [{:keys [x] :as scale} target-share]
  [:div {:class ["optimizer-risk-balance-row" "optimizer-risk-balance-backdrop"
                 "pointer-events-none"]}
   [:span]
   (into [:div {:class ["relative" "h-full"]}]
         (concat
          (map (fn [tick]
                 [:div {:class ["optimizer-risk-balance-gridline"]
                        :style {:left (str (x tick) "%")}}])
               (remove zero? (tick-values scale)))
          [[:div {:class ["optimizer-risk-balance-zeroline"]
                  :style {:left (str (x 0) "%")}}]]
          (when (number? target-share)
            [[:div {:class ["optimizer-risk-balance-targetline"]
                    :style {:left (str (x target-share) "%")}}]])))
   [:span]
   [:span]])

(defn contribution-lane
  "The row's plot area: dashed current↔recommended connector, the
  recommended-share bar from zero (sign-colored), the purple per-row target
  tick (omitted when the row carries no :target-share), the gray current
  circle, and the green recommended dot. A current
  share beyond the fitted domain (see lane-scale) draws as a gray chevron
  pinned to the plot edge — honest 'off the chart' instead of a circle that
  pretends the value sits at the edge — with the true value in the tooltip."
  [{:keys [x lo hi uniform-target]}
   {:keys [label share current-share target-share negative?]}]
  (let [x0 (x 0)
        xs (when (number? share) (x share))
        off-direction (when (number? current-share)
                        (cond
                          (> current-share hi) "right"
                          (< current-share lo) "left"
                          :else nil))
        xc (when (and (number? current-share) (nil? off-direction))
             (x current-share))
        connector-to (or xc (case off-direction "right" 100 "left" 0 nil))]
    [:div {:class ["optimizer-risk-balance-lane" "relative" "min-w-0"]}
     (when (and xs connector-to)
       [:div {:class ["optimizer-risk-balance-connector"]
              :style {:left (str (min xs connector-to) "%")
                      :width (str (js/Math.abs (- xs connector-to)) "%")}}])
     (when xs
       [:div {:class ["optimizer-risk-balance-bar"]
              :data-role "portfolio-optimizer-risk-contribution-bar"
              :data-sign (if negative? "negative" "positive")
              :style {:left (str (min x0 xs) "%")
                      :width (str (max 0.4 (js/Math.abs (- xs x0))) "%")}}])
     (when (and (number? target-share)
                (or (not (number? uniform-target))
                    (> (js/Math.abs (- target-share uniform-target)) 1e-9)))
       [:div {:class ["optimizer-risk-balance-tick"]
              :data-role "portfolio-optimizer-risk-contribution-target-tick"
              :style {:left (str (x target-share) "%")}}])
     (when xc
       [:div {:class ["optimizer-risk-balance-current" "optimizer-risk-balance-marker"]
              :data-role "portfolio-optimizer-risk-contribution-current"
              :style {:left (str xc "%")}}])
     (when off-direction
       [:div {:class ["optimizer-risk-balance-current-offscale"]
              :data-role "portfolio-optimizer-risk-contribution-current"
              :data-offscale off-direction
              :title (str label " · current " (format-pct current-share)
                          " — beyond the chart scale")}])
     (when xs
       [:div {:class ["optimizer-risk-balance-dot" "optimizer-risk-balance-marker"]
              :data-role "portfolio-optimizer-risk-contribution-recommended"
              :style {:left (str xs "%")}}])]))

(defn- deviation-sign
  [deviation-pts]
  (cond
    (not (number? deviation-pts)) nil
    (< (js/Math.abs deviation-pts) 0.05) "zero"
    (pos? deviation-pts) "positive"
    :else "negative"))

(defn- contribution-row
  "One chart row. The two right cells follow the display mode: Target /
  Deviation grade the fit in :deviation mode; Current / Shift tell the
  rebalance story in :shift mode (an exact fit's deviations are all zero).
  The Shift cell is deliberately NOT sign-colored — a position gaining or
  shedding risk share is neither good nor bad, and the deviation column's
  severity colors must not leak judgment onto it."
  [scale mode {:keys [instrument-id label share current-share target-share
                      deviation-pts shift-pts] :as row}]
  (let [shift? (= :shift mode)]
    [:div {:class ["optimizer-risk-balance-row"]
           :data-role "portfolio-optimizer-risk-contribution-row"
           :data-instrument-id instrument-id
           :title (if shift?
                    (str label
                         " · recommended " (format-pct share)
                         " (on the equal target)"
                         (when (number? current-share)
                           (str " · current " (format-pct current-share)))
                         " · shift "
                         (equal-risk-results/format-signed-pts shift-pts))
                    (str label
                         " · recommended " (format-pct share)
                         (when (number? current-share)
                           (str " · current " (format-pct current-share)))
                         " · target " (format-pct target-share)
                         " · "
                         (equal-risk-results/format-signed-pts deviation-pts)
                         " vs target"))}
     [:span {:class ["optimizer-risk-balance-label" "truncate"]} label]
     (contribution-lane scale row)
     (if shift?
       [:span {:class ["optimizer-risk-balance-current-cell" "font-mono"
                       "tabular-nums"]
               :data-role "portfolio-optimizer-risk-contribution-current-cell"}
        (format-pct current-share)]
       [:span {:class ["optimizer-risk-balance-target-cell" "font-mono"
                       "tabular-nums"]
               :data-role "portfolio-optimizer-risk-contribution-target"}
        (format-pct target-share)])
     (if shift?
       [:span {:class ["optimizer-risk-balance-shift-cell" "font-mono"
                       "tabular-nums"]
               :data-role "portfolio-optimizer-risk-contribution-shift"}
        (equal-risk-results/format-signed-pts shift-pts)]
       [:span {:class ["optimizer-risk-balance-deviation-cell" "font-mono"
                       "tabular-nums"]
               :data-role "portfolio-optimizer-risk-contribution-deviation"
               :data-sign (deviation-sign deviation-pts)}
        (equal-risk-results/format-signed-pts deviation-pts)])]))

(defn axis-rows
  [{:keys [x] :as scale}]
  [:div
   [:div {:class ["optimizer-risk-balance-row"]}
    [:span]
    (into [:div {:class ["optimizer-risk-balance-axis" "relative"]}]
          (map (fn [tick]
                 [:span {:class ["optimizer-risk-balance-axis-label" "absolute"
                                 "-translate-x-1/2" "font-mono"]
                         :style {:left (str (x tick) "%")}}
                  (format-pct tick 0)])
               (tick-values scale)))
    [:span] [:span]]
   [:div {:class ["optimizer-risk-balance-row"]}
    [:span]
    [:p {:class ["optimizer-risk-balance-axis-title"]}
     "Contribution to Total Volatility (%)"]
    [:span] [:span]]])

(defn- reading-note
  [{:keys [target-share negative-count] :as model}]
  [:p {:class ["optimizer-risk-balance-reading"]
       :data-role "portfolio-optimizer-risk-contributions-reading"}
   [:span {:class ["optimizer-risk-balance-reading-label"]} "Reading this"]
   [:span {:class ["optimizer-risk-balance-reading-sep"]} "·"]
   (if (kpi-strip/shift-mode? model)
     (str "Every bar ends on the equal target (" (format-pct target-share)
          " per asset) — the fit is exact. The gray circle is where each "
          "position's risk sits today; Shift is the risk share the rebalance "
          "adds (+) or removes (−). Equal Risk balances risk ownership; it "
          "does not minimize total volatility.")
     (str "Bars show recommended contribution. Gray circle is current. Purple "
          "line marks equal target (" (format-pct target-share) " per asset). "
          "Equal Risk balances risk ownership; it does not minimize total volatility."))
   (when (and (number? negative-count) (pos? negative-count))
     (str " " negative-count
          (if (= 1 negative-count)
            " position hedges the book (negative risk contribution)."
            " positions hedge the book (negative risk contributions).")))])

(defn- switch-objective-suggestion
  "The floored-state escape hatch: when Equal Risk held one or more
  side-locked assets at 0% (a zero binding bound + a zero published target),
  offer the one-click switch to Risk-weighted sizing — the objective built to
  keep every selected asset — and rerun. Renders nil when nothing is floored."
  [result]
  (let [floored (equal-risk-results/floored-instrument-ids result)
        labels (:labels-by-instrument result)
        named (->> floored
                   (map #(or (get labels %) %))
                   (take 3))]
    (when (seq floored)
      [:p {:class ["mt-2" "text-xs" "text-trading-muted"]
           :data-role "portfolio-optimizer-risk-contributions-floored-note"}
       (str (count floored)
            (if (= 1 (count floored))
              " side-locked position ("
              " side-locked positions (")
            (str/join ", " named)
            (when (> (count floored) (count named)) ", …")
            ") held at 0% — a fixed side Equal Risk cannot balance. ")
       [:button {:type "button"
                 :class ["border" "border-warning/50" "bg-warning/10" "px-1.5"
                         "py-0.5" "text-xs" "font-semibold" "text-warning"
                         "hover:bg-warning/20"]
                 :data-role "portfolio-optimizer-switch-to-risk-weighted-sizing"
                 :on {:click [[:actions/switch-portfolio-optimizer-objective-and-run
                               :inverse-volatility]]}}
        "Try Risk-weighted sizing"]
       " to keep every asset with a volatility-based size."])))

(defn- overflow-line
  [{:keys [hidden-count hidden-max-pts] :as model}]
  (when (pos? (or hidden-count 0))
    [:p {:class ["mt-2" "text-xs" "text-trading-muted"]
         :data-role "portfolio-optimizer-risk-contributions-overflow"}
     (if (kpi-strip/shift-mode? model)
       (str "+ " hidden-count " more with risk shifts within ±"
            (.toFixed (or hidden-max-pts 0) 1)
            " pts (the largest shifts are shown).")
       (str "+ " hidden-count " more within ±"
            (.toFixed (or hidden-max-pts 0) 1)
            " pts of target (the largest deviations are shown)."))]))

(defn- exposure-line
  [{:keys [gross-exposure net-exposure long-exposure short-exposure]}]
  (when (number? gross-exposure)
    [:p {:class ["mt-2" "font-mono" "text-xs" "text-trading-muted"]
         :data-role "portfolio-optimizer-risk-contributions-exposure"}
     (str "Realized · gross " (format-pct gross-exposure 0)
          " · net " (format-pct net-exposure 0)
          " · long " (format-pct long-exposure 0)
          " · short " (format-pct short-exposure 0))]))

(defn- solver-footer
  [result]
  (let [solver (:equal-risk-solver result)]
    (when (some? (:converged? solver))
      [:p {:class ["mt-1" "font-mono" "text-[0.6875rem]" "text-trading-muted/70"]
           :data-role "portfolio-optimizer-risk-contributions-solver"}
       (str (if (:converged? solver) "Converged" "Not converged")
            " · " (:iterations solver) " iterations"
            (when-let [start (:selected-initialization solver)]
              (str " · " (name start) " start")))])))

(defn- tab-label
  "A tab is a label wrapping its own visually-hidden radio: checked state
  lives in the DOM (never controlled by the render), and the scoped :has()
  CSS in optimizer/results.css does the active styling + panel switching.
  The radio gets a deterministic id so out-of-card labels (the why-card's
  Correlation view card) can activate a tab with plain label/for."
  [result view text]
  [:label {:class ["optimizer-risk-balance-tab"]
           :data-role (str "portfolio-optimizer-risk-view-tab-" view)}
   [:input {:type "radio"
            :name (structure-model/risk-view-radio-name result)
            :id (structure-model/risk-view-radio-id result view)
            :class ["sr-only"]
            :data-risk-view view}]
   text])

(defn risk-contributions-card
  "Renders nil unless the result carries the :risk-contributions section
  (present only on :equal-risk runs). Degrades gracefully on persisted
  pre-redesign results: no current markers/connectors, no solver footer, the
  RISK / RETURN tab disappears when no portfolio points exist, and the
  BREAKDOWN / CORRELATION tabs disappear when the result predates the
  :risk-structure section. `selected-risk-instrument` (app state, set by
  Allocation-row clicks and the breakdown tab's Change-asset select) picks
  which asset the breakdown tab's per-asset panel explains; the view-model
  falls back when it is nil or stale."
  ([result] (risk-contributions-card result nil))
  ([result {:keys [selected-risk-instrument]}]
   (when-let [model (equal-risk-results/balance-model result)]
     (let [{:keys [rows target-share current display-mode]} model
           scale (lane-scale model)
           per-row-ticks? (boolean
                           (some (fn [{:keys [target-share]}]
                                   (and (number? target-share)
                                        (or (not (number? (:uniform-target scale)))
                                            (> (js/Math.abs
                                                (- target-share
                                                   (:uniform-target scale)))
                                               1e-9))))
                                 rows))
           risk-return-panel (risk-return-context/risk-return-panel result)
           correlation-body (correlation-panel/correlation-panel
                             {:result result
                              :kpi-strip (kpi-strip/kpi-strip model
                                                    "Correlation matrix")})
           breakdown-body (asset-breakdown-panel/breakdown-tab-panel
                           {:result result
                            :rows (structure-model/breakdown-rows result rows)
                            :target-share target-share
                            :kpi-strip (kpi-strip/kpi-strip model "Diversification details")
                            :overflow-note (overflow-line model)
                            :selected-instrument-id selected-risk-instrument})]
       [:section {:class ["optimizer-risk-balance" "rounded-xl" "border"
                          "border-base-300"]
                  :data-role "portfolio-optimizer-risk-contributions"}
        [:div {:class ["optimizer-risk-balance-header"]}
         [:div {:class ["min-w-0"]}
          [:p {:class ["optimizer-risk-balance-title"]}
           "Risk contribution balance"]
          [:p {:class ["optimizer-risk-balance-subtitle"]
               :title "Signed Euler contribution to total portfolio volatility — contributions sum to exactly 100%; a negative contribution hedges the book."}
           "How much of the portfolio's risk each position owns"]]
         (when (or risk-return-panel breakdown-body correlation-body)
           [:div {:class ["optimizer-risk-balance-tabs"]
                  :data-role "portfolio-optimizer-risk-view-tabs"}
            (tab-label result "contribution" "Risk Balance")
            (when breakdown-body
              (tab-label result "breakdown" "Diversification"))
            (when correlation-body
              (tab-label result "correlation" "Correlation Drivers"))
            (when risk-return-panel
              (tab-label result "risk-return" "Risk / Return"))])]
        [:div {:class ["optimizer-risk-balance-panel"
                       "optimizer-risk-balance-panel--contribution"]}
         (kpi-strip/kpi-strip model)
         [:div {:class ["optimizer-risk-balance-plot-frame"]
                :data-role "portfolio-optimizer-risk-contribution-chart"}
          (legend-row {:target-share target-share
                       :current? (some? current)
                       :per-row-ticks? per-row-ticks?
                       :mode display-mode})
          [:div {:class ["optimizer-risk-balance-rows" "relative"]}
           (plot-backdrop scale target-share)
           (into [:div {:class ["relative" "space-y-1"]}]
                 (map (partial contribution-row scale display-mode))
                 rows)]
          (axis-rows scale)]
         (reading-note model)
         (switch-objective-suggestion result)
         (overflow-line model)
         (exposure-line (:diagnostics result))
         (solver-footer result)]
        (when breakdown-body
          [:div {:class ["optimizer-risk-balance-panel"
                         "optimizer-risk-balance-panel--breakdown"]}
           (diversification-summary/diversification-summary
            result
            {:help-id-prefix
             (str (structure-model/risk-view-radio-name result)
                  "-diversification-help")})
           breakdown-body])
        (when correlation-body
          [:div {:class ["optimizer-risk-balance-panel"
                         "optimizer-risk-balance-panel--correlation"]}
           correlation-body])
        (when risk-return-panel
          [:div {:class ["optimizer-risk-balance-panel"
                         "optimizer-risk-balance-panel--risk-return"]}
           risk-return-panel])]))))
