(ns hyperopen.views.portfolio.optimize.equal-risk-impact-strip
  "The WHAT CHANGES IF YOU EXECUTE strip: the Equal Risk results page's lead
  answer to 'what are the implications of this allocation?' — up to three
  current → target facts rendered with the why-card's icon fact cards,
  directly above the risk-contribution card:

    1. Risk imbalance (RMS gap to the equal target) — deep-links to the
       Risk Balance tab.
    2. Modeled total volatility — deep-links to the Diversification tab when
       the result carries the :risk-structure section that tab needs.
    3. Modeled one-year outcome (median ending wealth + touch-−50% odds) —
       no tab to point at; respects the leverage panel's gross/vol gate, so
       the strip never claims an outcome story the page below doesn't tell.

  Deep links are <label>s targeting the risk card's DOM-state tab radios
  (the zero-app-state idiom the why-card's correlation card established).
  Every number is read from models other cards already render — balance-model,
  the :risk-structure diversification summaries, and the leverage-risk model —
  so the strip can never disagree with the page. Renders nil for
  non-equal-risk results and when fewer than two chips have data: a one-chip
  strip is noise, not a summary."
  (:require ["lucide/dist/esm/icons/activity.js" :default lucide-activity]
            ["lucide/dist/esm/icons/banknote.js" :default lucide-banknote]
            ["lucide/dist/esm/icons/scale.js" :default lucide-scale]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
             :as equal-risk-results]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]
            [hyperopen.portfolio.optimizer.application.view-model.volatility-intuition
             :as volatility-vm]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.leverage-impact-panel
             :as leverage-impact-panel]
            [hyperopen.views.portfolio.optimize.results-summary :as summary]))

(def ^:private volatility-unchanged-threshold-pts
  "Below this many percentage points the volatility chip says 'roughly
  unchanged' instead of implying a real move — the same 0.5-pt materiality
  line the Diversification tab's tones use."
  0.5)

(defn- finite?*
  [value]
  (and (number? value) (js/isFinite value)))

(defn- arrow
  [from to]
  (str from " → " to))

(defn- pts-arrow
  [from-pts to-pts]
  (when (and (finite?* from-pts) (finite?* to-pts))
    (str (.toFixed from-pts 1) " → " (.toFixed to-pts 1) " pts")))

(defn- imbalance-chip
  "Current → target RMS gap to the equal risk target. Needs the current
  contributions section; a result without it has no before to compare."
  [result balance]
  (let [current-rms (get-in balance [:current :rms-pts])
        value (pts-arrow current-rms (:rms-pts balance))]
    (when value
      {:data-role "portfolio-optimizer-equal-risk-impact-balance"
       :tone "info"
       :icon lucide-scale
       :label "Risk imbalance (RMS)"
       :value value
       :sub "Each position's gap to the equal risk target"
       :for-radio (structure-model/risk-view-radio-id result "contribution")})))

(defn- volatility-direction-sub
  [delta-pts]
  (cond
    (< (js/Math.abs delta-pts) volatility-unchanged-threshold-pts)
    "Total volatility roughly unchanged"

    (pos? delta-pts)
    "Balancing risk here takes more total volatility"

    :else
    "Also lowers total volatility"))

(defn- volatility-chip
  "Current → target modeled portfolio volatility. Prefers the persisted
  :risk-structure benchmark scalars (the numbers the Diversification tab
  shows); falls back to the result's top-level volatilities for persisted
  pre-structure results — then the chip renders as a plain card because the
  tab it would link to does not exist."
  [result]
  (let [structure (:risk-structure result)
        target-vol (let [modeled (get-in structure
                                         [:target-diversification
                                          :modeled-volatility])]
                     (if (finite?* modeled) modeled (:volatility result)))
        current-vol (let [modeled (get-in structure
                                          [:current-diversification
                                           :modeled-volatility])]
                      (if (finite?* modeled)
                        modeled
                        (:current-volatility result)))]
    (when (and (finite?* target-vol) (finite?* current-vol))
      (let [delta-pts (* 100 (- target-vol current-vol))
            diversification-tab? (some? (structure-model/correlation-model
                                         result))
            fmt #(opt-format/format-pct % {:minimum-fraction-digits 1
                                           :maximum-fraction-digits 1})]
        {:data-role "portfolio-optimizer-equal-risk-impact-volatility"
         :tone "accent"
         :icon lucide-activity
         :label "Modeled volatility"
         :value (arrow (fmt current-vol) (fmt target-vol))
         :sub (volatility-direction-sub delta-pts)
         :for-radio (when diversification-tab?
                      (structure-model/risk-view-radio-id result
                                                          "breakdown"))}))))

(defn- outcome-value
  [capital-usd current-outcome target-outcome]
  (let [current-median (:median-ending-factor current-outcome)
        target-median (:median-ending-factor target-outcome)]
    (when (and (finite?* current-median) (finite?* target-median))
      (if capital-usd
        (arrow (leverage-impact-panel/compact-usd (* capital-usd current-median))
               (leverage-impact-panel/compact-usd (* capital-usd target-median)))
        (arrow (opt-format/format-multiple current-median)
               (opt-format/format-multiple target-median))))))

(defn- probability-pct
  [value]
  (opt-format/format-pct value {:minimum-fraction-digits 0
                                :maximum-fraction-digits 1}))

(defn- outcome-chip
  "Current → target modeled one-year outcome — median ending wealth on the
  value line, the touch-−50% ruin floor on the sub line. Gated exactly like
  the leverage-impact panel below (leverage-risk-model returns nil for an
  unlevered, low-volatility book), so the strip never introduces an outcome
  story the page cannot expand on."
  [result]
  (when-let [{:keys [target current capital-usd]}
             (volatility-vm/leverage-risk-model result)]
    (when-let [value (outcome-value capital-usd current target)]
      (let [current-touch (:prob-touch-half-drawdown current)
            target-touch (:prob-touch-half-drawdown target)]
        {:data-role "portfolio-optimizer-equal-risk-impact-outcome"
         :tone "warn"
         :icon lucide-banknote
         :label "1-yr outcome (modeled)"
         :value value
         :sub (if (and (finite?* current-touch) (finite?* target-touch))
                (str "Touch −50% odds " (probability-pct current-touch)
                     " → " (probability-pct target-touch))
                "Modeled median ending wealth")}))))

(defn equal-risk-impact-strip
  "Renders nil for non-equal-risk results (no :risk-contributions section)
  and when fewer than two chips have data."
  [result]
  (when-let [balance (equal-risk-results/balance-model result)]
    (let [chips (keep identity
                      [(imbalance-chip result balance)
                       (volatility-chip result)
                       (outcome-chip result)])]
      (when (<= 2 (count chips))
        [:section {:class ["optimizer-target-context" "rounded-xl" "border"
                           "border-base-300" "bg-base-100/95" "p-4"]
                   :replicant/key "optimizer-equal-risk-impact-strip"
                   :data-role "portfolio-optimizer-equal-risk-impact-strip"}
         [:p {:class ["optimizer-target-context-title" "font-mono"
                      "text-[0.62rem]" "uppercase" "tracking-[0.08em]"
                      "text-trading-muted/70"]}
          "What changes if you execute"]
         (into [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2"
                              "sm:grid-cols-2" "xl:grid-cols-3"]}]
               (map (fn [{:keys [data-role tone icon label value sub
                                 for-radio]}]
                      (summary/equal-risk-fact-card data-role tone icon label
                                                    value sub nil
                                                    {:for-radio for-radio})))
               chips)]))))
