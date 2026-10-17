(ns hyperopen.views.portfolio.optimize.results-summary
  (:require ["lucide/dist/esm/icons/crosshair.js" :default lucide-crosshair]
            ["lucide/dist/esm/icons/grid-3x3.js" :default lucide-grid]
            ["lucide/dist/esm/icons/list.js" :default lucide-list]
            ["lucide/dist/esm/icons/lock-open.js" :default lucide-lock-open]
            ["lucide/dist/esm/icons/lock.js" :default lucide-lock]
            ["lucide/dist/esm/icons/star.js" :default lucide-star]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
             :as equal-risk-results]
            [hyperopen.portfolio.optimizer.application.view-model.equal-risk-structure
             :as structure-model]
            [hyperopen.portfolio.optimizer.application.view-model.results :as results-model]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.setup-controls :as controls]))

(defn summary-card
  [label value]
  [:div {:class ["optimizer-summary-card" "rounded-lg" "border" "border-base-300" "bg-base-200/50" "p-3"]}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-2" "text-lg" "font-semibold" "tabular-nums"]}
    value]])

(defn compact-fact
  [label value]
  [:div {:class ["optimizer-summary-card" "rounded-lg" "border" "border-base-300" "bg-base-200/50" "px-3" "py-2"]}
   [:p {:class ["text-[0.6rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
    label]
   [:p {:class ["mt-1" "text-sm" "font-semibold" "tabular-nums"]}
    value]])

(defn panel-shell
  [data-role title subtitle & children]
  [:section {:class ["optimizer-results-panel"
                     "rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
             :data-role data-role}
   [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
    title]
   [:p {:class ["mt-2" "text-sm" "text-trading-muted"]} subtitle]
   (into [:div {:class ["mt-4" "space-y-2"]}]
         children)])

(defn stale-result-banner
  [stale?]
  (when stale?
    [:div {:class ["rounded-xl" "border" "border-warning/50" "bg-warning/10" "p-4"]
           :replicant/key "optimizer-stale-result-banner"
           :data-role "portfolio-optimizer-stale-result-banner"}
     [:div {:class ["flex" "flex-col" "gap-3" "md:flex-row" "md:items-center" "md:justify-between"]}
      [:div
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-warning"]}
        "Stale Output"]
       [:p {:class ["mt-2" "text-sm" "text-trading-muted"]}
        "These allocation weights come from the last successful run. The app is refreshing them automatically; save and execute unlock when the refreshed run completes."]]]]))

(defn- verdict-cta
  "The page's primary action, rendered inside the verdict bar so 'what happened' and
  'what to do next' read as one unit at the top of the tab (the previous end-of-grid
  placement buried it below the fold on real universes). Stages the rebalance straight
  into the Execution tab in-place (no navigation) so unsaved run state is preserved;
  the spectate/read-only gate lives downstream in the execution surface.

  This is the only control on the page that moves the flow FORWARD (everything else
  adjusts the scenario), so the actionable state carries a sweeping 'snake' border
  (`--live` modifier; static under prefers-reduced-motion) and a single large label.
  The explanatory sentence lives in a delayed hover/focus tooltip instead of standing
  subtext — it stays in the DOM for screen readers. Mutes, relabels, and drops the
  animation when the target already matches the current book so a no-op doesn't beg
  to be clicked.

  Labelled \"Review N trades\": clicking stages the plan for review — nothing is
  sent until the user arms and confirms on the Execution tab — so the label names
  what the next screen actually is instead of over-promising execution. Falls back
  to \"Review trades\" when the sendable count is zero (all-blocked plans still
  deserve a path in to see why) or unknown."
  [{:keys [at-target? trade-count vault-funding?]}]
  (let [no-trades? (and (true? at-target?) (not vault-funding?))
        label (cond
                ;; An all-vault run is funded with vault deposits and withdrawals,
                ;; never orders, so the forward action opens the funding sheet.
                vault-funding? "Fund this allocation"
                no-trades? "Already at target"
                (and (opt-format/finite-number? trade-count) (pos? trade-count))
                (str "Review " trade-count (if (= 1 trade-count) " trade" " trades"))
                :else "Review trades")]
    [:button {:type "button"
              :class (into ["optimizer-review-rebalance-cta" "optimizer-verdict-cta"
                            "relative" "flex" "items-center" "justify-between" "gap-3"
                            "rounded-lg" "border" "px-4" "py-2.5" "text-left"
                            "transition-colors"]
                           (if no-trades?
                             ["border-base-300" "bg-base-200/30" "text-trading-muted"]
                             ["optimizer-verdict-cta--live"
                              "border-primary/50" "bg-primary/10" "text-primary" "hover:bg-primary/30"]))
              :data-role (if vault-funding?
                           "portfolio-optimizer-vault-funding-cta"
                           "portfolio-optimizer-recommendation-rebalance-cta")
              :on {:click (if vault-funding?
                            [[:actions/portfolio-optimizer-vault-funding-command :open]]
                            [[:actions/open-portfolio-optimizer-execution]])}}
     [:span {:class ["optimizer-verdict-cta-label" "text-[0.8125rem]" "font-bold" "tracking-wide"]}
      label]
     [:span {:class ["optimizer-verdict-cta-tip"]}
      (cond
        vault-funding? "Plan deposits into (and withdrawals from) these vaults to match the target — nothing is sent until you confirm."
        no-trades? "Your current allocation already matches the target — no trades needed."
        :else "Opens the Execution tab to review the staged trades — nothing is sent until you arm and confirm there.")]
     [:span {:class ["text-sm" "font-semibold"] :aria-hidden "true"} "→"]]))

(defn verdict-headline
  "The verdict bar at the top of the recommendation tab: one plain-language sentence
  stating what the recommended portfolio does to the user's risk and return, with the
  trade count — so the user reads the answer instead of reconstructing it from five
  KPI cards and a frontier chart — paired with the primary Review-trades CTA.
  Targets are framed as estimates; deltas are signed (the rest of the UI uses the same
  convention: lower volatility and higher return are good). Falls back to non-delta
  phrasing when there is no current baseline.

  `deltas` is the map produced by scenario-kpi-strip's `recommendation-deltas`;
  `:objective-body`, when present, replaces the vol/return sentence with
  objective-specific copy (Equal Risk supplies its balance framing, including
  the constraint-determined case)."
  [{:keys [target-vol vol-delta target-return return-delta trade-count
           blocked-count at-target? objective-body vault-funding?]}]
  (let [vol-clause (if (opt-format/finite-number? vol-delta)
                     (str "estimated volatility " (opt-format/format-pct target-vol)
                          " (" (opt-format/format-pct-delta vol-delta) " vs current)")
                     (str "an estimated " (opt-format/format-pct target-vol) " volatility"))
        return-clause (if (opt-format/finite-number? return-delta)
                        (str "expected return " (opt-format/format-pct target-return)
                             " (" (opt-format/format-pct-delta return-delta) " vs current)")
                        (str "an estimated " (opt-format/format-pct target-return) " expected return"))
        body (or objective-body
                 (str "Targets " vol-clause " and " return-clause "."))
        trades-clause (cond
                        vault-funding? "Vaults are funded with deposits and withdrawals, not orders."
                        (not (opt-format/finite-number? trade-count)) nil
                        ;; Sendable trades only. An all-blocked plan is NOT "at
                        ;; target" — its trades exist but can't be sent yet.
                        at-target? "No trades needed — you're already at target."
                        (zero? trade-count)
                        (str "No trades can be sent — "
                             blocked-count
                             (if (= 1 blocked-count) " leg is" " legs are")
                             " blocked.")
                        (= 1 trade-count) "1 trade gets you there."
                        :else (str trade-count " trades get you there."))]
    [:section {:class ["optimizer-results-panel" "optimizer-recommendation-verdict"
                       "rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               ;; Keyed (with its recommendation-tab siblings) so the recompute
               ;; banner mounting/unmounting between them reconciles by key instead
               ;; of recreating subtrees — recreation would reset DOM state like
               ;; open <details> in the results grid mid-edit.
               :replicant/key "optimizer-recommendation-verdict"
               :data-role "portfolio-optimizer-recommendation-verdict"}
     [:div {:class ["flex" "flex-wrap" "items-center" "justify-between" "gap-3"]}
      [:div {:class ["min-w-0"]}
       [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
        "Recommendation"]
       [:p {:class ["mt-2" "text-sm" "font-medium" "text-trading-text"]} body]
       (when trades-clause
         [:p {:class ["mt-1" "text-[0.75rem]" "text-trading-muted"]
              :data-role "portfolio-optimizer-recommendation-verdict-trades"}
          trades-clause])]
      (verdict-cta {:at-target? at-target? :trade-count trade-count :vault-funding? vault-funding?})]]))

(defn- target-shape
  "Long/short counts and the largest |target weight| position, from the solved target
  weights. Weights within ±0.01% of zero count as flat."
  [weights-by-instrument]
  (let [epsilon 0.0001
        entries (filterv (fn [[_ weight]] (opt-format/finite-number? weight))
                         (vec weights-by-instrument))
        longs (count (filter (fn [[_ weight]] (> weight epsilon)) entries))
        shorts (count (filter (fn [[_ weight]] (< weight (- epsilon))) entries))
        largest (when (seq entries)
                  (apply max-key (fn [[_ weight]] (js/Math.abs weight)) entries))]
    {:asset-count (count entries)
     :longs longs
     :shorts shorts
     :largest largest}))

(defn- context-fact
  [data-role label value]
  [:div {:class ["optimizer-target-context-fact"]
         :data-role data-role}
   [:span {:class ["optimizer-target-context-label"]} label]
   [:span {:class ["optimizer-target-context-value" "font-mono" "tabular-nums"]} value]])

(defn- context-label
  ;; Prefer the enriched human label; instrument-label alone returns the raw id for
  ;; non-vault instruments.
  [labels-by-instrument instrument-id]
  (or (get labels-by-instrument instrument-id)
      (get labels-by-instrument (keyword instrument-id))
      (results-model/instrument-label labels-by-instrument instrument-id)))

(defn- binding-limit-row
  ;; Engine truth: a binding constraint is a per-instrument weight sitting on its
  ;; lower/upper bound (`domain/diagnostics.cljs binding-constraints`), so the copy
  ;; names the asset and the cap/floor it hit instead of leaking the raw keyword.
  [labels-by-instrument {:keys [instrument-id constraint bound weight]}]
  (let [level (or bound weight)
        level-text (when (opt-format/finite-number? level)
                     (str " " (opt-format/format-pct level)))]
    [:p {:class ["optimizer-target-context-binding" "border" "border-warning/40"
                 "bg-warning/10" "px-2" "py-1" "text-xs" "text-warning"]
         :data-role (str "portfolio-optimizer-target-context-binding-"
                         (or instrument-id "portfolio"))}
     (when instrument-id
       [:span {:class ["font-semibold"]}
        (str (context-label labels-by-instrument instrument-id) " ")])
     [:span (case constraint
              :upper-bound (str "at cap" level-text)
              :lower-bound (str "at floor" level-text)
              (str "at " (str/lower-case (opt-format/keyword-label constraint)) " limit"))]]))

(defn target-context-card
  "Why this target: engine-derived facts that explain the recommended book's shape —
  long/short counts, the largest target position, and which constraints bound the
  solve. Strictly facts the engine reported; no narrative it didn't. Fills the center
  column between the frontier and the (demoted) refinement card so the chart reads as
  a decision aid instead of a chart-first dead end."
  [result]
  (let [weights (:target-weights-by-instrument result)
        labels (:labels-by-instrument result)
        bindings (get-in result [:diagnostics :binding-constraints])]
    (when (seq weights)
      (let [{:keys [asset-count longs shorts largest]} (target-shape weights)
            [largest-id largest-weight] largest]
        [:section {:class ["optimizer-target-context" "rounded-xl" "border"
                           "border-base-300" "bg-base-100/95" "p-4"]
                   :data-role "portfolio-optimizer-target-context"}
         [:p {:class ["optimizer-target-context-title" "font-mono" "text-[0.62rem]"
                      "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
          "Why this target"]
         [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2" "sm:grid-cols-2"]}
          (context-fact "portfolio-optimizer-target-context-shape"
                        "Book shape"
                        (str longs " long · " shorts " short of " asset-count " assets"))
          (context-fact "portfolio-optimizer-target-context-largest"
                        "Largest target position"
                        (if largest-id
                          (str (context-label labels largest-id)
                               " · " (opt-format/format-pct largest-weight))
                          "—"))]
         [:div {:class ["mt-2"]
                :data-role "portfolio-optimizer-target-context-limits"}
          [:span {:class ["optimizer-target-context-label"]} "Limits hit"]
          (if (seq bindings)
            (into [:div {:class ["mt-1" "flex" "flex-wrap" "gap-1.5"]}]
                  (map (partial binding-limit-row labels) bindings))
            [:p {:class ["mt-1" "text-xs" "text-trading-muted"]}
             "None — the target sits inside your constraints."])]]))))

(defn equal-risk-fact-card
  "One icon card of the WHY THIS RISK ALLOCATION row (designer spec
  2026-07-11): tinted icon tile, tiny uppercase label, value line, one-line
  sub. `sub-class` opts the sub-line into a signal color. With :for-radio the
  card renders as a <label> targeting a tab radio of the risk card — clicking
  it activates that tab with zero app state — and the scoped :has() CSS
  highlights it while that tab is active. Public: the Equal Risk impact strip
  (equal-risk-impact-strip) renders its current → target chips with the same
  card so the two rows read as one visual language."
  ([data-role tone icon-node label value sub sub-class]
   (equal-risk-fact-card data-role tone icon-node label value sub sub-class nil))
  ([data-role tone icon-node label value sub sub-class {:keys [for-radio]}]
   [(if for-radio :label :div)
    (cond-> {:class (cond-> ["optimizer-equal-risk-fact"]
                      for-radio (conj "optimizer-equal-risk-fact--link"))
             :data-role data-role}
      for-radio (assoc :for for-radio))
    [:span {:class ["optimizer-equal-risk-fact-icon"]
            :data-tone tone}
     (controls/lucide-icon icon-node nil)]
    [:div {:class ["min-w-0"]}
     [:p {:class ["optimizer-equal-risk-fact-label"]} label]
     [:p {:class ["optimizer-equal-risk-fact-value" "font-mono" "tabular-nums"]}
      value]
     [:p {:class ["optimizer-equal-risk-fact-sub"
                  (or sub-class "text-trading-muted")]}
      sub]]]))

(defn equal-risk-context-card
  "Why this risk allocation: the Equal Risk replacement for the Why-this-target
  card — four icon cards for book shape, the equal per-asset target, the
  correlation view (a label that jumps to the risk card's Correlation tab —
  results with no :risk-structure section fall back to the largest risk
  CONTRIBUTOR card), and allocation freedom. Facts only, all from the run
  result; the binding-constraint enumeration lives in the trust-diagnostics
  rail."
  [result]
  (when-let [model (equal-risk-results/balance-model result)]
    (let [weights (:target-weights-by-instrument result)
          labels (:labels-by-instrument result)
          {:keys [asset-count longs shorts]} (target-shape weights)
          largest (:largest model)
          largest-dev (:deviation-pts largest)
          correlation? (some? (structure-model/correlation-model result))
          freedom (equal-risk-results/freedom-card-view
                   (equal-risk-results/allocation-freedom result))]
      [:section {:class ["optimizer-target-context" "rounded-xl" "border"
                         "border-base-300" "bg-base-100/95" "p-4"]
                 :data-role "portfolio-optimizer-equal-risk-context"}
       [:p {:class ["optimizer-target-context-title" "font-mono" "text-[0.62rem]"
                    "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
        "Why this risk allocation"]
       [:div {:class ["mt-3" "grid" "grid-cols-1" "gap-2" "sm:grid-cols-2"
                      "xl:grid-cols-4"]}
        (equal-risk-fact-card "portfolio-optimizer-equal-risk-context-shape"
                              "info" lucide-list
                              "Book shape"
                              (str longs " long · " shorts " short · "
                                   asset-count " assets")
                              "Long & short books preserved"
                              nil)
        (equal-risk-fact-card "portfolio-optimizer-equal-risk-context-target"
                              "long" lucide-crosshair
                              "Equal target"
                              (str (opt-format/format-pct (:target-share model))
                                   " per asset")
                              "Equal risk objective"
                              nil)
        (if correlation?
          (equal-risk-fact-card "portfolio-optimizer-equal-risk-context-correlation"
                                "accent" lucide-grid
                                "Correlation view"
                                "Position P&L"
                                "Shows how held trades interact"
                                nil
                                {:for-radio (structure-model/risk-view-radio-id
                                             result
                                             "correlation")})
          (equal-risk-fact-card "portfolio-optimizer-equal-risk-context-largest"
                                "accent" lucide-star
                                "Largest risk contributor"
                                (if largest
                                  (str (context-label labels (:instrument-id largest))
                                       " · " (opt-format/format-pct (:share largest)))
                                  "—")
                                (cond
                                  (not (opt-format/finite-number? largest-dev)) "—"
                                  (< (js/Math.abs largest-dev) 0.05) "On target"
                                  :else (str (equal-risk-results/format-signed-pts
                                              largest-dev)
                                             (if (neg? largest-dev)
                                               " below target"
                                               " above target")))
                                (when (and (opt-format/finite-number? largest-dev)
                                           (>= (js/Math.abs largest-dev) 0.05))
                                  (if (neg? largest-dev)
                                    "text-trading-green"
                                    "text-trading-red"))))
        (equal-risk-fact-card "portfolio-optimizer-equal-risk-context-freedom"
                              "warn"
                              (if (:locked? freedom) lucide-lock lucide-lock-open)
                              "Allocation freedom"
                              (:value freedom)
                              (:sub freedom)
                              nil)]])))

(defn- history-lookback-label
  [result]
  (let [summary (:history-summary result)
        observations (:return-observations summary)]
    (if (opt-format/finite-number? observations)
      (str observations " returns")
      "Loaded history")))

(defn- funding-assumption-label
  [result]
  (let [sources (->> (:return-decomposition-by-instrument result)
                     vals
                     (keep :funding-source)
                     set)]
    (cond
      (empty? sources) "No funding data"
      (= #{:not-applicable} sources) "Spot only"
      (contains? sources :market-funding-history) "Market funding"
      :else (opt-format/keyword-label (first sources)))))

(defn assumptions-strip
  [draft result]
  (let [objective-kind (or (get-in draft [:objective :kind])
                           (get-in result [:solver :objective-kind]))]
    [:section {:class ["optimizer-results-panel"
                       "rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4"]
               :data-role "portfolio-optimizer-assumptions-strip"}
     [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.24em]" "text-trading-muted"]}
      "Run Assumptions"]
     [:div {:class ["mt-3" "grid" "grid-cols-2" "gap-2" "xl:grid-cols-5"]}
      (compact-fact "Objective" (opt-format/keyword-label objective-kind))
      (compact-fact "Return Model"
                    ;; Covariance-only objectives never use returns to size
                    ;; positions — say so where the model is named, or users
                    ;; will assume the historical mean drove the weights.
                    (if (contains? contracts-constants/covariance-only-objective-kinds
                                   objective-kind)
                      (str (opt-format/keyword-label (:return-model result))
                           " · analytics only")
                      (opt-format/keyword-label (:return-model result))))
      (compact-fact "Risk Model" (opt-format/keyword-label (:risk-model result)))
      (compact-fact "Lookback" (history-lookback-label result))
      (compact-fact "Funding" (funding-assumption-label result))]]))
