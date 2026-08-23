(ns hyperopen.views.portfolio.optimize.results-diagnostics-rail
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model.results :as results-model]
            [hyperopen.views.portfolio.optimize.format :as opt-format]
            [hyperopen.views.portfolio.optimize.results-summary :as summary]))

(defn- binding-constraint-row
  [labels-by-instrument binding]
  [:div {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]}
   [:span {:class ["font-semibold"]}
    (results-model/instrument-label labels-by-instrument (:instrument-id binding))]
   [:span {:class ["ml-2"]} (opt-format/keyword-label (:constraint binding))]])

(defn- sensitivity-row
  [labels-by-instrument [instrument-id row]]
  [:div {:class ["optimizer-row"
                 "rounded-md" "border" "border-base-300" "bg-base-200/40" "p-2" "text-xs"]
         :data-role (str "portfolio-optimizer-sensitivity-row-" instrument-id)}
   [:span {:class ["font-semibold"]} (results-model/instrument-label labels-by-instrument instrument-id)]
   [:span {:class ["ml-2" "text-trading-muted"]}
    (str "Base " (opt-format/format-pct (:base-expected-return row))
         " / Down " (opt-format/format-pct (:down-expected-return row))
         " / Up " (opt-format/format-pct (:up-expected-return row)))]])

(defn- replace-all-text
  [text needle replacement]
  (let [needle* (str needle)
        replacement* (str replacement)]
    (if (or (not (string? text))
            (empty? needle*)
            (= needle* replacement*))
      text
      (loop [remaining text
             out ""]
        (if-let [idx (str/index-of remaining needle*)]
          (recur (subs remaining (+ idx (count needle*)))
                 (str out (subs remaining 0 idx) replacement*))
          (str out remaining))))))

(defn- warning-instrument-ids
  [warning]
  (vec (distinct (concat (keep warning
                               [:instrument-id
                                :left-instrument-id
                                :right-instrument-id
                                :comparator-instrument-id
                                :long-instrument-id
                                :short-instrument-id])
                         (:instrument-ids warning)))))

(defn- warning-label
  [labels-by-instrument warning instrument-id]
  (or (when (= instrument-id (:instrument-id warning))
        (:instrument-label warning))
      (results-model/instrument-label labels-by-instrument instrument-id)))

(defn- human-warning-label
  [instrument-id label]
  (when (and (string? label)
             (seq label)
             (not= label (str instrument-id)))
    label))

(defn- warning-primary-label
  [labels-by-instrument warning]
  (when-let [instrument-id (:instrument-id warning)]
    (human-warning-label instrument-id
                         (warning-label labels-by-instrument warning instrument-id))))

(defn- prepend-warning-label
  [message label]
  (if (and (string? message)
           (seq label)
           (not (str/includes? message label)))
    (str label ": " message)
    message))

(defn- warning-message-body
  [labels-by-instrument warning]
  (reduce (fn [message instrument-id]
            (replace-all-text message
                              instrument-id
                              (warning-label labels-by-instrument
                                             warning
                                             instrument-id)))
          (:message warning)
          (warning-instrument-ids warning)))

(defn- warning-message
  [labels-by-instrument warning]
  (prepend-warning-label (warning-message-body labels-by-instrument warning)
                         (warning-primary-label labels-by-instrument warning)))

(defn- display-asset-label
  "Instrument label for text-only trust/warning copy. Falls back to the last
  segment of a namespaced id (perp:HYPE -> HYPE) so raw internal ids never leak
  into user-facing sentences."
  [labels-by-instrument instrument-id]
  (let [label (results-model/instrument-label labels-by-instrument instrument-id)]
    (if (= label (str instrument-id))
      (or (last (str/split (str instrument-id) #":")) label)
      label)))

(defn warning-rows
  "One row per warning CODE, not per (code, asset) pair: 25 near-identical
  per-asset rows bury the signal, so the headline is the plain-language code +
  the affected assets, and the raw engine messages (which may carry provider
  internals) demote to deduplicated muted detail lines."
  [labels-by-instrument warnings]
  (map (fn [[code group]]
         (let [assets (->> group
                           (map :instrument-id)
                           (remove nil?)
                           distinct
                           (map (partial display-asset-label labels-by-instrument)))
               messages (->> group
                             (map (partial warning-message-body labels-by-instrument))
                             (filter #(and (string? %) (seq %)))
                             distinct)]
           [:div {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10"
                          "p-2" "text-xs" "text-warning"]
                  :data-role "portfolio-optimizer-result-warning"}
            [:p {}
             [:span {:class ["font-semibold"]} (opt-format/keyword-label code)]
             (when (seq assets)
               [:span {:class ["ml-2"]} (str/join " · " assets)])]
            (into [:div {}]
                  (map (fn [message]
                         [:p {:class ["mt-1" "text-[0.62rem]" "text-warning/80"]}
                          message])
                       messages))]))
       (sort-by (comp str key) (group-by :code warnings))))

(defn warning-row
  [labels-by-instrument warning]
  [:p {:class ["rounded-md" "border" "border-warning/40" "bg-warning/10" "p-2" "text-xs" "text-warning"]
       :data-role "portfolio-optimizer-result-warning"}
   [:span {:class ["font-semibold"]} (opt-format/keyword-label (:code warning))]
   (when-let [message (warning-message labels-by-instrument warning)]
     [:span {:class ["ml-2"]} (str " " message)])])

(defn warnings-panel
  [result]
  (when (seq (:warnings result))
    (summary/panel-shell
     "portfolio-optimizer-result-warnings"
     "Result Warnings"
     "Warnings explain assumptions or mathematically valid outcomes that may require a rerun with different controls."
     (warning-rows (:labels-by-instrument result) (:warnings result)))))

(defn- format-net-gross
  "Signed net-to-gross percentage, or — when gross is zero/near zero (never a
  division by zero or a misleading percentage)."
  [{:keys [net-exposure gross-exposure]}]
  (if (and (number? net-exposure) (number? gross-exposure)
           (> (js/Math.abs gross-exposure) 1e-9))
    (let [r (/ net-exposure gross-exposure)]
      (str (when-not (neg? r) "+") (.toFixed (* 100 r) 1) "%"))
    "—"))

(defn diagnostics-panel
  [result]
  (let [diagnostics (:diagnostics result)
        labels-by-instrument (or (:labels-by-instrument result) {})
        bindings (:binding-constraints diagnostics)
        conditioning (:covariance-conditioning diagnostics)
        sensitivity (:weight-sensitivity-by-instrument diagnostics)]
    (summary/panel-shell
     "portfolio-optimizer-diagnostics-panel"
     "Diagnostics"
     "Engine diagnostics are rendered from the run result, not recomputed in the view."
     [:div {:class ["grid" "grid-cols-2" "gap-2" "lg:grid-cols-4"]}
      (summary/summary-card "Gross" (opt-format/format-multiple (:gross-exposure diagnostics)))
      (summary/summary-card "Net" (opt-format/format-multiple (:net-exposure diagnostics)))
      (summary/summary-card "Net/Gross" (format-net-gross diagnostics))
      (summary/summary-card "Effective N" (opt-format/format-decimal (:effective-n diagnostics)))
      (summary/summary-card "Turnover" (opt-format/format-pct (:turnover diagnostics)))]
     [:div {:class ["grid" "grid-cols-1" "gap-2" "lg:grid-cols-3"]}
      (summary/summary-card "Condition" (opt-format/keyword-label (:status conditioning)))
      (summary/summary-card "Condition #"
                            (opt-format/format-decimal (:condition-number conditioning)))
      (summary/summary-card "Min Eigen"
                            (opt-format/format-decimal (:min-eigenvalue conditioning)))]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Binding Constraints"]
      (if (seq bindings)
        (into [:div {:class ["mt-2" "space-y-2"]}]
              (map (partial binding-constraint-row labels-by-instrument) bindings))
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         "No binding constraints reported."])]
     [:div {:class ["rounded-lg" "border" "border-base-300" "bg-base-200/40" "p-3"]
            :data-role "portfolio-optimizer-sensitivity-panel"}
      [:p {:class ["text-[0.65rem]" "font-semibold" "uppercase" "tracking-[0.18em]" "text-trading-muted"]}
       "Weight Sensitivity"]
      (if (seq sensitivity)
        (into [:div {:class ["mt-2" "space-y-2"]}]
              (map (partial sensitivity-row labels-by-instrument) sensitivity))
        [:p {:class ["mt-2" "text-xs" "text-trading-muted"]}
         "No sensitivity diagnostics reported."])])))

(defn- status-token
  [status]
  (case status
    :ok {:label "ok" :class "text-trading-green"}
    :healthy {:label "ok" :class "text-trading-green"}
    :warning {:label "caution" :class "text-warning"}
    :caution {:label "caution" :class "text-warning"}
    :ill-conditioned {:label "caution" :class "text-warning"}
    :singular {:label "bad" :class "text-trading-red"}
    :bad {:label "bad" :class "text-trading-red"}
    {:label (opt-format/keyword-label status) :class "text-trading-muted"}))

(defn diversification-status
  "Actually EVALUATE effective N instead of a hardcoded OK (the audit caught a
  one-position target — effective N 0.5 of 18 — badged green). Below ~1.5 the
  target is effectively a single position; below a quarter of the universe it is
  concentrated. Unknown values stay muted rather than falsely reassuring."
  [effective-n universe-size]
  (cond
    (not (and (number? effective-n) (js/isFinite effective-n))) :unknown
    (< effective-n 1.5) :bad
    (and (number? universe-size)
         (pos? universe-size)
         (< effective-n (* 0.25 universe-size))) :caution
    :else :ok))

(defn- diversification-subtext
  [status]
  (case status
    :bad "The target is effectively a single position."
    :caution "A few names dominate the target."
    :unknown "No diversification diagnostics reported."
    "Higher effective N means less concentration in one name."))

(defn- top-sensitivity
  [sensitivity]
  (when (seq sensitivity)
    (let [row-span (fn [row]
                     (js/Math.abs
                      (- (or (:up-weight row) (:up-expected-return row) 0)
                         (or (:down-weight row) (:down-expected-return row) 0))))
          [instrument-id row] (->> sensitivity
                                   (sort-by (fn [[_ row*]]
                                              (- (row-span row*))))
                                   first)]
      {:instrument-id instrument-id
       :span (row-span row)})))

(defn- pluralize
  [count singular plural]
  (if (= 1 count) singular plural))

(defn- rounded-count
  [value]
  (when (opt-format/finite-number? value)
    (js/Math.round value)))

(defn- history-window-value
  [history-summary]
  (let [observations (rounded-count (:return-observations history-summary))
        return-days (rounded-count (:return-days history-summary))]
    (cond
      (and observations return-days)
      (str (opt-format/format-decimal observations {:maximum-fraction-digits 0})
           " "
           (pluralize observations "return" "returns")
           " · "
           (opt-format/format-decimal return-days {:maximum-fraction-digits 0})
           " "
           (pluralize return-days "day" "days"))

      observations
      (str (opt-format/format-decimal observations {:maximum-fraction-digits 0})
           " "
           (pluralize observations "return" "returns"))

      :else
      "Loaded history")))

(defn history-window-status
  [history-summary]
  (let [observations (:return-observations history-summary)]
    (cond
      (:stale? history-summary)
      :caution

      (and (opt-format/finite-number? observations)
           (< observations 30))
      :caution

      :else
      :ok)))

(defn- limiter-reason-copy
  [reason]
  (case reason
    :starts-later "starts later than the rest"
    :ends-earlier "ends earlier than the rest"
    :fewest-return-observations "has the fewest usable return observations"
    "sets the shared return window"))

(defn- stale-window-copy
  [history-summary]
  (let [age-ms (:age-ms history-summary)
        age-days (when (opt-format/finite-number? age-ms)
                   (js/Math.round (/ age-ms 86400000)))]
    (if age-days
      (str "Shared window ends " age-days " "
           (if (= 1 age-days) "day" "days")
           " before the run date — refresh history.")
      "Shared window ends well before the run date — refresh history.")))

(defn history-window-subtext
  [result history-summary]
  (let [instrument-id (:limiting-instrument-id history-summary)
        reason (:limiting-reason history-summary)
        base (cond
               instrument-id
               (str (display-asset-label (:labels-by-instrument result)
                                         instrument-id)
                    " "
                    (limiter-reason-copy reason)
                    ".")

               (= :source-coverage-unavailable reason)
               "Limiter unavailable from aligned returns."

               :else
               "Shared return calendar from aligned optimizer history.")]
    (if (:stale? history-summary)
      (str (stale-window-copy history-summary) " " base)
      base)))

(defn- trust-row
  [{:keys [label status value subtext]}]
  (let [{status-label :label status-class :class} (status-token status)]
    [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
      [:span {:class ["text-[0.62rem]" "font-semibold" "uppercase" "tracking-[0.06em]" "text-trading-muted"]}
       label]
      [:span {:class [status-class "text-[0.62rem]" "font-semibold" "uppercase"]}
       (str "● " status-label)]]
     [:p {:class ["mt-1" "font-mono" "text-base" "font-semibold" "tabular-nums" "text-trading-text"]}
      value]
     [:p {:class ["mt-0.5" "text-[0.64rem]" "text-trading-muted/70"]}
      subtext]]))

(defn trust-diagnostics-rail
  [result]
  (let [diagnostics (:diagnostics result)
        conditioning (:covariance-conditioning diagnostics)
        sensitivity (:weight-sensitivity-by-instrument diagnostics)
        sensitivity-top (top-sensitivity sensitivity)
        effective-n (:effective-n diagnostics)
        universe-size (count (:instrument-ids result))
        conditioning-status (or (:status conditioning) :ok)
        plausibility (:covariance-plausibility diagnostics)
        plausibility-status (or (:status plausibility) :ok)
        implausible? (= :implausible plausibility-status)
        weight-stability-status (if sensitivity-top :caution :ok)]
    [:aside {:class ["optimizer-trust-caution-panel"
                     "min-h-0" "border-l" "border-base-300" "bg-base-100/95"]
             :data-role "portfolio-optimizer-trust-caution-panel"}
     [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
      [:p {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
       "How much to trust this"]]
     [:div {:class ["optimizer-diagnostics-list"]
            :data-role "portfolio-optimizer-diagnostics-panel"}
      ;; Conditioning must never read "Healthy" while magnitude is failing: it
      ;; is a RATIO of eigenvalues, so a scaled identity scores a perfect 1 at
      ;; any scale. That is why 8,697.7% shipped next to "Healthy" (2026-08-23).
      (trust-row {:label "Conditioning"
                  :status (if implausible? :caution conditioning-status)
                  :value (cond
                           implausible? "Not usable"
                           (= :ok conditioning-status) "Healthy"
                           :else (opt-format/keyword-label conditioning-status))
                  :subtext (if implausible?
                             "Well-conditioned, but not at a market scale — see Risk magnitude."
                             "Correlation matrix is checked before weights are accepted.")})
      (trust-row {:label "Risk magnitude"
                  :status plausibility-status
                  :value (if-let [peak (:max-volatility plausibility)]
                           (str "Peak σ · " (opt-format/format-pct peak))
                           "Unknown")
                  :subtext (if implausible?
                             (str "No asset trades at this volatility — "
                                  (str/join ", " (map (partial display-asset-label
                                                               (:labels-by-instrument result))
                                                      (take 3 (:implausible-instrument-ids plausibility))))
                                  " imply broken history, not risk.")
                             "Every asset's estimated volatility is a market number.")})
      (trust-row {:label "History Used"
                  :status (history-window-status (:history-summary result))
                  :value (history-window-value (:history-summary result))
                  :subtext (history-window-subtext result
                                                   (:history-summary result))})
      ;; Equal Risk replaces the weight-based diversification read with the
      ;; contribution-based one: how many positions HEDGE the book. A negative
      ;; contributor is not an error and not the same thing as a short.
      (if-let [contributions (:risk-contributions result)]
        (trust-row {:label "Negative contributors"
                    :status :ok
                    :value (str (or (:negative-contribution-count contributions) 0)
                                " of " universe-size)
                    :subtext "Negative contributors hedge total volatility — side and contribution sign are independent."})
        (let [status (diversification-status effective-n universe-size)]
          (trust-row {:label "Diversification"
                      :status status
                      :value (str "Effective N · " (opt-format/format-effective-n effective-n universe-size) " of " universe-size)
                      :subtext (diversification-subtext status)})))
      (trust-row {:label "Weight Stability"
                  :status weight-stability-status
                  :value (if sensitivity-top "Moderate" "Stable")
                  :subtext (if sensitivity-top
                            (str (display-asset-label (:labels-by-instrument result)
                                                      (:instrument-id sensitivity-top))
                                 " is most sensitive (±"
                                 (opt-format/format-pct (/ (:span sensitivity-top) 2))
                                 ").")
                            "No material sensitivity flags reported.")})
      (when-let [warnings (seq (:warnings result))]
        [:details {:class ["border-b" "border-base-300"]
                   :data-role "portfolio-optimizer-result-warnings"}
         [:summary {:class ["cursor-pointer" "px-4" "py-3"
                            "text-[0.62rem]" "font-semibold" "uppercase" "tracking-[0.06em]" "text-warning"]}
          (str "Warnings · " (count warnings))]
         (into [:div {:class ["space-y-2" "px-4" "pb-4"]}]
               (warning-rows (:labels-by-instrument result) warnings))])
      [:details {:class ["border-b" "border-base-300"]}
       [:summary {:class ["cursor-pointer" "px-4" "py-3" "font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
        "More Diagnostics"]
       [:div {:class ["space-y-2" "px-4" "pb-4"]}
        (summary/summary-card "Gross" (opt-format/format-multiple (:gross-exposure diagnostics)))
        (summary/summary-card "Net" (opt-format/format-multiple (:net-exposure diagnostics)))
        (summary/summary-card "Net/Gross" (format-net-gross diagnostics))
        (summary/summary-card "Turnover" (opt-format/format-pct (:turnover diagnostics)))
        (summary/summary-card "Condition #" (opt-format/format-decimal (:condition-number conditioning)))]]]]))

(defn- quality-status
  [quality]
  (case quality
    :high :ok
    :medium :ok
    :caution))

(defn- stability-status
  [stability]
  (case stability
    :provisional :caution
    :ok))

(defn confidence-row
  "Shared confidence-rail row (public: the equal-risk confidence rail reuses
  it)."
  [{:keys [label status value subtext value-class]}]
  (let [{status-label :label status-class :class} (when status (status-token status))]
    [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
      [:span {:class ["text-[0.62rem]" "font-semibold" "uppercase" "tracking-[0.06em]" "text-trading-muted"]}
       label]
      (when status
        [:span {:class [status-class "text-[0.62rem]" "font-semibold" "uppercase"]}
         (str "● " status-label)])]
     [:p {:class ["mt-1" "font-mono" "text-sm" "font-semibold" "tabular-nums"
                  (or value-class "text-trading-text")]}
      value]
     (when subtext
       [:p {:class ["mt-0.5" "text-[0.6rem]" "text-trading-muted/70"]} subtext])]))

(defn- next-step-row
  "Leads the confidence rail. A solved draft is usable as-is, so this frames the two
  honest paths forward rather than commanding a refinement: the always-available
  rebalance (the lone clickable token, which stages straight into the Execution tab)
  and the optional refine (descriptive only — its real trigger is the header
  action-bar button, and the rail refine control is depth-coupled, so duplicating a
  one-click refine here would hide that choice). Amber now signals \"clickable\", not
  \"required\"."
  [next-step]
  (let [tail (case next-step
               :refine-optimization " now, or refine for more density"
               :refine-further " now, or refine further"
               ;; :none (maximum density) — refining is genuinely impossible.
               " now")
        subtext (if (= :none next-step)
                  "Frontier is at maximum density — nothing left to refine."
                  "Draft is solved and usable. Refining only adds frontier density; selection rarely moves.")]
    [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]
           :data-role "portfolio-optimizer-result-confidence-next-step"}
     [:div {:class ["flex" "items-center" "justify-between" "gap-3"]}
      [:span {:class ["text-[0.62rem]" "font-semibold" "uppercase" "tracking-[0.06em]" "text-trading-muted"]}
       "From here"]]
     [:p {:class ["mt-1" "font-mono" "text-sm" "text-trading-muted"]}
      [:button {:type "button"
                :class ["optimizer-result-confidence-rebalance"]
                :data-role "portfolio-optimizer-result-confidence-rebalance"
                :on {:click [[:actions/open-portfolio-optimizer-execution]]}}
       "Rebalance"]
      tail]
     [:p {:class ["mt-0.5" "text-[0.6rem]" "text-trading-muted/70"]} subtext]]))

(defn result-confidence-rail
  "Leads the right rail: just the next-step row (Rebalance now / refine).
  The frontier-quality/selection-stability/stop-reason detail rows that used
  to follow it here now render separately, below the volatility-intuition and
  leverage-risk cards — see `result-confidence-quality-rail`."
  [refinement]
  (when-let [assessment (:assessment refinement)]
    [:aside {:class ["optimizer-result-confidence-panel"
                     "min-h-0" "border-l" "border-base-300" "bg-base-100/95"]
             :data-role "portfolio-optimizer-result-confidence-panel"}
     [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
      [:p {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
       "Result confidence"]]
     (next-step-row (:next-step assessment))]))

(defn result-confidence-quality-rail
  "Frontier quality / selection stability / stop reason (2026-07-12: split out
  of result-confidence-rail so it can sit below the volatility-intuition and
  leverage-risk cards instead of leading the rail). Titled 'Solve quality'
  rather than reusing 'Result confidence' — the two cards are no longer
  adjacent, so a repeated header would read as a duplicate rather than a
  continuation."
  [refinement]
  (when-let [assessment (:assessment refinement)]
    [:aside {:class ["optimizer-result-confidence-panel"
                     "min-h-0" "border-l" "border-base-300" "bg-base-100/95"]
             :data-role "portfolio-optimizer-result-confidence-quality-panel"}
     [:div {:class ["border-b" "border-base-300" "px-4" "py-3"]}
      [:p {:class ["font-mono" "text-[0.62rem]" "uppercase" "tracking-[0.08em]" "text-trading-muted/70"]}
       "Solve quality"]]
     (confidence-row {:label "Frontier quality"
                      :status (quality-status (:frontier-quality assessment))
                      :value (opt-format/refinement-quality-label (:frontier-quality assessment))
                      :subtext "Density of the solved efficient-frontier sample."})
     (confidence-row {:label "Selection stability"
                      :status (stability-status (:selection-stability assessment))
                      :value (opt-format/refinement-stability-label (:selection-stability assessment))
                      :subtext (if (:exact-selection? assessment)
                                 "Selected portfolio is solved exactly for this objective."
                                 "Selected portfolio is sampled from the frontier grid.")})
     (confidence-row {:label "Stop reason"
                      :value (opt-format/refinement-stop-reason-label (:stop-reason assessment))
                      :subtext "Why the current result is where it is."})]))
