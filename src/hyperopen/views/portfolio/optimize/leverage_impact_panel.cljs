(ns hyperopen.views.portfolio.optimize.leverage-impact-panel
  "The ONE-YEAR MODELED LEVERAGE IMPACT panel (designer mockup, user-trimmed
  2026-07-12): a full-width center-column card directly under the objective's
  primary result visual — the efficient frontier for frontier objectives, or
  Risk Contribution Balance for Equal Risk — translating the target's
  leverage into modeled one-year dollar outcomes — median ending wealth
  current vs target, the median shortfall
  headline, mean / 5th-percentile / loss-odds tiles, and the modeled
  ending-wealth distribution with 5th-percentile, median, and mean markers.
  The funding/execution cost tiles from the mockup are deliberately omitted
  (execution costs live on the Execution tab).

  Surfaces only when the target is meaningfully levered (gross ≥ 2x) or
  extremely volatile (≥100% annualized) — the gate lives in the view-model.

  Honesty contract: closed-form lognormal scaled from the run's own
  return/volatility estimates (see domain.leverage-risk), labeled 'modeled',
  assumptions in fine print, and never a 'probability of liquidation' —
  maintenance margins are not in the result, so the 50%-drawdown odds are
  explained as a floor on ruin-type risk. The distribution's dollar axis is
  log-scaled (a linear axis would crush the skew this panel exists to show);
  the axis carries no invented ticks, only the three model markers.

  Every field carries an accessible info-tip (leverage-impact-tips, split for
  the namespace-size cap — the codebase's group / :has() hover-card idiom).
  The copy is written for THIS model — a closed-form lognormal, not a
  Monte-Carlo simulation, with no funding / execution / liquidation modeling
  — so it must never claim simulated paths, cash costs, or a liquidation
  probability the model does not compute."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.volatility-intuition
             :as vm]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.leverage-impact-tips :as tips]))

(def ^:private info-tip tips/info-tip)

(def ^:private tip-copy tips/copy)

(defn- whole-usd
  [value]
  (opt-format/format-usdc value {:maximum-fraction-digits 0}))

(defn compact-usd
  "Marker-label dollars in the mockup's compact form: $409, $43k, $2M.
  Public so tests can pin the rendered marker labels without re-deriving the
  rounding."
  [value]
  (when (opt-format/finite-number? value)
    (let [abs (js/Math.abs value)
          sign (if (neg? value) "−$" "$")
          fmt (fn [v digits]
                (.toLocaleString v "en-US" #js {:maximumFractionDigits digits}))]
      (cond
        (>= abs 1e6) (str sign (fmt (/ abs 1e6) 1) "M")
        (>= abs 1e4) (str sign (fmt (/ abs 1e3) 0) "k")
        (>= abs 1e3) (str sign (fmt (/ abs 1e3) 1) "k")
        :else (str sign (fmt abs 0))))))

(defn- probability-pct
  [value]
  (opt-format/format-pct value {:minimum-fraction-digits 0
                                :maximum-fraction-digits 1}))

(defn- factor-text
  "Dollar value when account equity is known, else a multiple of starting
  equity (0.43x = ending at 43% of start)."
  [capital-usd factor]
  (if capital-usd
    (whole-usd (* capital-usd factor))
    (str (opt-format/format-multiple factor) " start")))

(defn- compact-factor-text
  [capital-usd factor]
  (if capital-usd
    (compact-usd (* capital-usd factor))
    (str (opt-format/format-multiple factor))))

;; --- Median ending wealth bars --------------------------------------------------

(defn- median-bar-width
  [factor max-factor]
  (if (and (number? factor) (number? max-factor) (pos? max-factor))
    (max 2 (min 100 (* 100 (/ factor max-factor))))
    0))

(defn- median-row
  [kind label outcome capital-usd max-factor]
  [:div {:class ["optimizer-leverage-impact-median-row"]
         :data-role (str "portfolio-optimizer-leverage-impact-median-" kind)}
   [:span {:class ["optimizer-leverage-impact-median-label"]}
    [:span {:class ["optimizer-leverage-impact-legend"
                    (str "optimizer-leverage-impact-legend--" kind)]
            :aria-hidden "true"}]
    label]
   [:div {:class ["optimizer-leverage-impact-track"]
          :aria-hidden "true"}
    ;; Hairline at the starting-equity position (factor 1.0): everything left
    ;; of it is modeled loss, right of it modeled growth. Inset 1px so the
    ;; tick survives the track's overflow clip when start IS the scale max.
    [:div {:class ["optimizer-leverage-impact-start-tick"]
           :style {:left (str "calc(" (median-bar-width 1.0 max-factor) "% - 1px)")}}]
    [:div {:class ["optimizer-leverage-impact-fill"
                   (str "optimizer-leverage-impact-fill--" kind)]
           :style {:width (str (median-bar-width (:median-ending-factor outcome)
                                                 max-factor)
                               "%")}}]]
   [:span {:class ["optimizer-leverage-impact-median-value" "font-mono" "tabular-nums"]}
    (factor-text capital-usd (:median-ending-factor outcome))]])

(defn- median-shortfall-headline
  "The mockup's emphasized 'MEDIAN WEALTH SHORTFALL VS CURRENT' line — the
  single number that makes volatility drag real. Symmetric: a target whose
  median beats the current book gets the same row in green."
  [current target]
  (let [current-usd (get-in current [:dollar :median-usd])
        target-usd (get-in target [:dollar :median-usd])]
    (when (and (number? current-usd) (number? target-usd))
      (let [diff (- target-usd current-usd)
            shortfall? (neg? diff)
            tip (str "The gap between the two medians: the typical modeled "
                     "target outcome ends " (whole-usd (js/Math.abs diff)) " "
                     (if shortfall? "below" "above")
                     " the typical current outcome. Not a guaranteed loss, a "
                     "fee, or the difference of the two averages. Both books "
                     "are modeled analytically, so this gap carries no "
                     "simulation noise.")]
        [:div {:class ["mt-3" "flex" "flex-wrap" "items-baseline" "justify-between" "gap-2"]
               :data-role "portfolio-optimizer-leverage-impact-median-shortfall"}
         [:span {:class ["inline-flex" "items-center" "gap-1.5" "text-[0.62rem]"
                         "font-semibold" "uppercase" "tracking-[0.06em]"
                         (if shortfall? "text-warning" "text-trading-green")]}
          (if shortfall?
            "Median wealth shortfall vs current"
            "Median wealth gain vs current")
          (info-tip "portfolio-optimizer-leverage-impact-shortfall-tip" tip)]
         [:span {:class ["font-mono" "text-xl" "font-semibold" "tabular-nums"
                         (if shortfall? "text-warning" "text-trading-green")]}
          (str (if shortfall? "−" "+") (whole-usd (js/Math.abs diff)))]]))))

;; --- Stat tiles -----------------------------------------------------------------

(defn- stat-tile
  "`:compare` is the current book's same statistic (\"Now 12.1%\") — the frame
  that turns an absolute target number into the decision-relevant delta.
  Rendered only when the caller has a real current value; the warning color
  threshold reads the TARGET value alone."
  [{:keys [data-role label value compare subtext value-class tip tip-id
           tip-position]}]
  [:div {:class ["optimizer-leverage-impact-tile" "border" "border-base-300"
                 "bg-base-200/40" "px-3" "py-2.5"]
         :data-role data-role}
   [:p {:class ["flex" "items-center" "gap-1.5" "text-[0.6rem]" "font-semibold"
                "uppercase" "tracking-[0.06em]" "text-trading-muted"]}
    label
    (when tip
      (info-tip tip-id tip (or tip-position :start)))]
   [:p {:class ["mt-1" "font-mono" "text-base" "font-semibold" "tabular-nums"
                (or value-class "text-trading-text")]}
    value]
   (when compare
     [:p {:class ["mt-0.5" "font-mono" "text-[0.62rem]" "tabular-nums"
                  "text-trading-muted"]
          :data-role (str data-role "-current")}
      compare])
   (when subtext
     [:p {:class ["mt-0.5" "text-[0.6rem]" "text-trading-muted/70"]} subtext])])

;; --- Ending-wealth distribution --------------------------------------------------

(def ^:private dist-width 720)
(def ^:private dist-height 150)
(def ^:private dist-pad-x 12)
(def ^:private dist-baseline-y 96)
(def ^:private dist-peak-y 16)
(def ^:private dist-domain-sigmas 3.2)
(def ^:private dist-label-min-gap 84)

(defn- spread-label-xs
  "Push overlapping label centers apart (ascending input) to at least
  `min-gap`, keeping everything inside [lo, hi]. Marker dots stay at their
  true positions; only the text slides."
  [xs min-gap lo hi]
  (let [pushed (reduce (fn [acc x]
                         (conj acc (max (max x lo)
                                        (if (seq acc)
                                          (+ (peek acc) min-gap)
                                          lo))))
                       []
                       xs)
        overflow (- (peek pushed) hi)
        shifted (if (pos? overflow)
                  (mapv #(- % overflow) pushed)
                  pushed)]
    (reduce (fn [acc x]
              (conj acc (max (max x lo)
                             (if (seq acc)
                               (+ (peek acc) min-gap)
                               lo))))
            []
            shifted)))

(defn- distribution-marker
  [{:keys [key x label-x name value emphasis?]}]
  [:g {:data-role (str "portfolio-optimizer-leverage-impact-dist-" key)}
   [:line {:x1 x
           :x2 x
           :y1 dist-baseline-y
           :y2 (+ dist-peak-y 6)
           :stroke (if emphasis?
                     "var(--optimizer-target-border)"
                     "rgba(255, 255, 255, 0.14)")
           :stroke-width 1
           :stroke-dasharray "3 3"}]
   [:circle {:cx x
             :cy dist-baseline-y
             :r (if emphasis? 3.5 2.5)
             :fill (if emphasis? "var(--optimizer-target)" "var(--optimizer-text-2)")}]
   [:text {:x label-x
           :y 118
           :fill "var(--optimizer-text-3)"
           :font-size 9.5
           :text-anchor "middle"}
    name]
   [:text {:x label-x
           :y 133
           :fill "var(--optimizer-text)"
           :font-size 11.5
           :font-weight 700
           :text-anchor "middle"
           :class ["font-mono"]}
    value]])

(defn- distribution-section
  "The modeled target ending-wealth distribution: the lognormal density drawn
  on a LOG dollar axis (linear dollars would crush median and 5th percentile
  into the left edge at high σ — hiding exactly the skew this panel exists to
  show). The bell shape IS the model: log-wealth ~ Normal(ν, σ). No axis
  ticks are invented; the three markers carry the numbers, mean sitting right
  of median by exactly the σ²/2 drag."
  [{:keys [log-drift log-sigma p5-ending-factor median-ending-factor
           mean-ending-factor]}
   capital-usd]
  (when (and (number? log-sigma) (pos? log-sigma))
    (let [nu log-drift
          sigma log-sigma
          x-min (- nu (* dist-domain-sigmas sigma))
          span (* 2 dist-domain-sigmas sigma)
          plot-w (- dist-width (* 2 dist-pad-x))
          ;; An ending factor that underflows to 0 makes (js/Math.log 0) return
          ;; -Infinity, which used to land verbatim in the SVG :x1/:cx
          ;; attributes. Clamp to the plotted domain so a marker is always a
          ;; finite coordinate; the value it labels is formatted separately.
          x-px (fn [x]
                 (if-not (js/isFinite x)
                   (if (and (number? x) (pos? x)) (+ dist-pad-x plot-w) dist-pad-x)
                   (+ dist-pad-x
                      (max 0 (min plot-w (* plot-w (/ (- x x-min) span)))))))
          curve-y (fn [x]
                    (let [z (/ (- x nu) sigma)]
                      (- dist-baseline-y
                         (* (- dist-baseline-y dist-peak-y)
                            (js/Math.exp (* -0.5 z z))))))
          samples 64
          curve-points (map (fn [i]
                              (let [x (+ x-min (* span (/ i samples)))]
                                (str (.toFixed (x-px x) 1) " "
                                     (.toFixed (curve-y x) 1))))
                            (range (inc samples)))
          path (str "M " dist-pad-x " " dist-baseline-y
                    " L " (str/join " L " curve-points)
                    " L " (+ dist-pad-x plot-w) " " dist-baseline-y " Z")
          marker-xs [(x-px (js/Math.log p5-ending-factor))
                     (x-px (js/Math.log median-ending-factor))
                     (min (+ dist-pad-x plot-w)
                          (x-px (js/Math.log mean-ending-factor)))]
          label-xs (spread-label-xs marker-xs
                                    dist-label-min-gap
                                    48
                                    (- dist-width 48))
          values (mapv (partial compact-factor-text capital-usd)
                       [p5-ending-factor median-ending-factor mean-ending-factor])]
      [:div {:class ["mt-4"]
             :data-role "portfolio-optimizer-leverage-impact-distribution"}
       [:p {:class ["flex" "items-center" "gap-1.5" "text-[0.62rem]" "font-semibold"
                    "uppercase" "tracking-[0.06em]" "text-trading-muted"]}
        "Target ending wealth distribution"
        (info-tip "portfolio-optimizer-leverage-impact-distribution-tip"
                  (:distribution tip-copy))]
       [:svg {:viewBox (str "0 0 " dist-width " " dist-height)
              :class ["mt-1" "w-full" "h-auto"]
              :role "img"
              :aria-label (str "Modeled target ending wealth distribution over one year: "
                               "5th percentile " (nth values 0)
                               ", median " (nth values 1)
                               ", mean " (nth values 2)
                               ". Log-scaled axis.")}
        [:path {:d path
                :fill "var(--optimizer-target-soft)"
                :stroke "var(--optimizer-target-border)"
                :stroke-width 1
                :data-role "portfolio-optimizer-leverage-impact-dist-curve"}]
        [:line {:x1 dist-pad-x
                :x2 (- dist-width dist-pad-x)
                :y1 dist-baseline-y
                :y2 dist-baseline-y
                :stroke "var(--optimizer-border-strong)"
                :stroke-width 1}]
        (distribution-marker {:key "p5"
                              :x (nth marker-xs 0)
                              :label-x (nth label-xs 0)
                              :name "5th pct."
                              :value (nth values 0)})
        (distribution-marker {:key "median"
                              :x (nth marker-xs 1)
                              :label-x (nth label-xs 1)
                              :name "Median"
                              :value (nth values 1)
                              :emphasis? true})
        (distribution-marker {:key "mean"
                              :x (nth marker-xs 2)
                              :label-x (nth label-xs 2)
                              :name "Mean"
                              :value (nth values 2)})
        [:text {:x dist-pad-x
                :y 147
                :fill "var(--optimizer-text-3)"
                :font-size 9}
         "Lower"]
        [:text {:x (- dist-width dist-pad-x)
                :y 147
                :fill "var(--optimizer-text-3)"
                :font-size 9
                :text-anchor "end"}
         "Higher"]]])))

;; --- Panel ------------------------------------------------------------------------

(defn leverage-impact-panel
  [result]
  (when-let [{:keys [target current capital-usd]} (vm/leverage-risk-model result)]
    (let [max-factor (apply max
                            1.0
                            (keep :median-ending-factor [current target]))
          touch-odds (:prob-touch-half-drawdown target)
          terminal-odds (:prob-terminal-loss-half target)
          now-factor (fn [key]
                       (let [value (get current key)]
                         (when (opt-format/finite-number? value)
                           (str "Now " (compact-factor-text capital-usd value)))))
          now-odds (fn [key]
                     (let [value (get current key)]
                       (when (opt-format/finite-number? value)
                         (str "Now " (probability-pct value)))))]
      [:section {:class ["optimizer-leverage-impact" "rounded-xl" "border"
                         "border-base-300" "bg-base-100/95" "p-4"]
                 :replicant/key "optimizer-leverage-impact-panel"
                 :data-role "portfolio-optimizer-leverage-impact"}
       [:div {:class ["flex" "flex-wrap" "items-baseline" "justify-between" "gap-2"]}
        [:div {:class ["min-w-0"]}
         [:p {:class ["flex" "items-center" "gap-1.5" "font-mono" "text-[0.62rem]"
                      "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
          "One-year modeled leverage impact"
          (info-tip "portfolio-optimizer-leverage-impact-title-tip"
                    (:panel tip-copy))]
         [:p {:class ["mt-0.5" "text-[0.68rem]" "text-trading-muted"]}
          (if capital-usd
            (str "Modeled dollar outcomes on account equity "
                 (whole-usd capital-usd) " · 1-year horizon")
            "Modeled outcomes as multiples of starting equity · 1-year horizon")]]
        [:span {:class ["optimizer-leverage-impact-chip-group" "group" "relative"
                        "inline-flex" "items-center"]}
         [:span {:class ["optimizer-leverage-impact-chip" "cursor-help"]
                 :tabindex 0
                 :aria-describedby "portfolio-optimizer-leverage-impact-modeled-tip"}
          "Modeled"]
         [:span {:class ["optimizer-leverage-impact-tip-card" "pointer-events-none"
                         "absolute" "right-0" "top-[calc(100%+6px)]" "z-40"
                         "w-[min(22rem,calc(100vw-2rem))]" "border" "border-base-300"
                         "bg-base-100" "px-2.5" "py-2" "font-sans" "text-[0.7rem]"
                         "font-normal" "normal-case" "leading-[1.5]" "tracking-normal"
                         "text-trading-muted" "opacity-0"
                         "shadow-[0_12px_32px_rgba(0,0,0,0.5)]" "transition-opacity"
                         "duration-150" "group-hover:opacity-100"
                         "group-focus-within:opacity-100"]
                 :id "portfolio-optimizer-leverage-impact-modeled-tip"
                 :role "tooltip"
                 :data-role "portfolio-optimizer-leverage-impact-modeled-tip"}
          (:modeled tip-copy)]]]
       [:div {:class ["mt-3"]}
        [:p {:class ["flex" "items-center" "gap-1.5" "text-[0.62rem]" "font-semibold"
                     "uppercase" "tracking-[0.06em]" "text-trading-muted"]}
         "Median ending wealth"
         (info-tip "portfolio-optimizer-leverage-impact-median-tip"
                   (str "The midpoint of the modeled distribution of one-year "
                        "ending equity: half the modeled outcomes finish above "
                        "it, half below — one row per book. This is the typical "
                        "outcome, not the average; for a levered book the mean "
                        "sits well above the median because a few large winners "
                        "pull it up."
                        (when capital-usd
                          (str " Both rows start from " (whole-usd capital-usd)
                               "."))))]
        [:div {:class ["mt-2" "space-y-1.5"]}
         (when current
           (median-row "current" "Current" current capital-usd max-factor))
         (median-row "target" "Target" target capital-usd max-factor)]
        (median-shortfall-headline current target)]
       [:div {:class ["optimizer-leverage-impact-tiles" "mt-3" "grid" "grid-cols-2"
                      "gap-2" "xl:grid-cols-4"]}
        (stat-tile {:data-role "portfolio-optimizer-leverage-impact-mean"
                    :label "Mean ending wealth"
                    :value (factor-text capital-usd (:mean-ending-factor target))
                    :compare (now-factor :mean-ending-factor)
                    :subtext "Pulled up by rare extreme outcomes."
                    :tip (:mean tip-copy)
                    :tip-id "portfolio-optimizer-leverage-impact-mean-tip"})
        (stat-tile {:data-role "portfolio-optimizer-leverage-impact-p5"
                    :label "5th percentile"
                    :value (factor-text capital-usd (:p5-ending-factor target))
                    :compare (now-factor :p5-ending-factor)
                    :subtext "One year in twenty ends at or below this."
                    :tip (:p5 tip-copy)
                    :tip-id "portfolio-optimizer-leverage-impact-p5-tip"})
        (stat-tile {:data-role "portfolio-optimizer-leverage-impact-terminal"
                    :label "Odds of ending down 50%+"
                    :value (probability-pct terminal-odds)
                    :value-class (when (and (number? terminal-odds)
                                            (>= terminal-odds 0.5))
                                   "text-warning")
                    :compare (now-odds :prob-terminal-loss-half)
                    :subtext "Chance the year ends at half of start or less."
                    :tip (:terminal tip-copy)
                    :tip-id "portfolio-optimizer-leverage-impact-terminal-tip"
                    :tip-position :end})
        (stat-tile {:data-role "portfolio-optimizer-leverage-impact-touch"
                    :label "Odds of touching −50%"
                    :value (probability-pct touch-odds)
                    :value-class (when (and (number? touch-odds)
                                            (>= touch-odds 0.5))
                                   "text-warning")
                    :compare (now-odds :prob-touch-half-drawdown)
                    :subtext "Usually forced-liquidation territory — a floor on ruin risk."
                    :tip (:touch tip-copy)
                    :tip-id "portfolio-optimizer-leverage-impact-touch-tip"
                    :tip-position :end})]
       (distribution-section target capital-usd)
       [:p {:class ["mt-3" "text-[0.6rem]" "text-trading-muted/70"]}
        "Lognormal model scaled from this run's return and volatility estimates; assumes continuous rebalancing to target weights. Distribution axis is log-scaled. Ignores fat tails, funding, execution costs, and margin mechanics. Modeled, not a guarantee."]])))
