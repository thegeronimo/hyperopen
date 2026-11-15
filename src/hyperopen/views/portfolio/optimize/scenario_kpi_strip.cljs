(ns hyperopen.views.portfolio.optimize.scenario-kpi-strip
  "The scenario/draft results KPI strip and the shared recommendation-deltas
  read model (extracted from scenario-detail-view during the Equal Risk
  results redesign, per that namespace's size-exception plan).

  Objective-aware: for the covariance-only objectives the Sharpe tile becomes
  the objective's own success metric (:equal-risk → the RISK BALANCE tile,
  current → recommended max contribution deviation in points;
  :inverse-volatility → the SIZING DEVIATION tile, max |w|·σ spread among
  free assets), and the volatility / expected-return deltas render NEUTRALLY
  because a rise or fall in either is not success or failure for them. Every
  other objective keeps the existing five tiles unchanged."
  (:require [hyperopen.portfolio.optimizer.application.view-model.equal-risk-results
             :as equal-risk-results]
            [hyperopen.portfolio.optimizer.application.view-model.inverse-volatility-results
             :as inverse-volatility-results]
            [hyperopen.portfolio.optimizer.contracts.constants :as contracts-constants]
            [hyperopen.views.portfolio.optimize.format :as opt-format]))

(defn- kpi-delta-class
  [delta {:keys [positive negative]}]
  (cond
    (not (opt-format/finite-number? delta)) "text-trading-muted"
    (pos? delta) positive
    (neg? delta) negative
    :else "text-trading-muted"))

(defn- kpi-card
  ([data-role label value delta]
   (kpi-card data-role label value delta "text-trading-green"))
  ([data-role label value delta delta-class]
   [:div {:class ["optimizer-kpi-card" "border-r" "border-base-300" "px-3" "py-2.5" "last:border-r-0"]
          :data-role data-role}
    [:p {:class ["font-mono"
                 "text-[0.6rem]"
                 "uppercase"
                 "tracking-[0.08em]"
                 "text-trading-muted/70"]}
     label]
    [:p {:class ["mt-1" "font-mono" "text-sm" "font-semibold" "tabular-nums" "text-trading-text"]}
     value]
    [:p {:class ["mt-0.5" "font-mono" "text-[0.65rem]" "tabular-nums" delta-class]}
     delta]]))

(defn- positive-number?
  [value]
  (and (opt-format/finite-number? value)
       (pos? value)))

(defn- sharpe-from
  [performance expected-return volatility]
  (or (when (opt-format/finite-number? (:in-sample-sharpe performance))
        (:in-sample-sharpe performance))
      (when (and (opt-format/finite-number? expected-return)
                 (positive-number? volatility))
        (/ expected-return volatility))))

(defn- format-decimal-delta
  [value]
  (if (opt-format/finite-number? value)
    (str (when (pos? value) "+")
         (opt-format/format-decimal value))
    "N/A"))

(defn recommendation-deltas
  "Single source of the current→target volatility/return deltas and the trade
  count, so the recommendation headline and the KPI strip never disagree.
  Volatility down is good and expected return up is good; deltas are nil when there
  is no current baseline, and `trade-count` is nil when no rebalance preview exists.
  The trade count is SENDABLE legs only (ready), matching the execution ledger's
  staged count and fill denominator — blocked and skipped legs never trade, so
  counting them would promise trades the plan cannot deliver."
  [result]
  (let [current-return (:current-expected-return result)
        current-vol (:current-volatility result)
        target-return (:expected-return result)
        target-vol (:volatility result)
        summary (:summary (:rebalance-preview result))]
    {:current-return current-return
     :current-vol current-vol
     :target-return target-return
     :target-vol target-vol
     :return-delta (when (opt-format/finite-number? current-return)
                     (- (or target-return 0) current-return))
     :vol-delta (when (opt-format/finite-number? current-vol)
                  (- (or target-vol 0) current-vol))
     :trade-count (when summary
                    (or (:ready-count summary) 0))
     :blocked-count (when summary
                      (or (:blocked-count summary) 0))
     :at-target? (when summary
                   (and (zero? (or (:ready-count summary) 0))
                        (zero? (or (:blocked-count summary) 0))))
     :rebalance-status (:status (:rebalance-preview result))}))

(defn- sharpe-kpi-card
  [result {:keys [current-return current-vol target-return target-vol]}]
  (let [current-sharpe (sharpe-from (:current-performance result)
                                    current-return
                                    current-vol)
        target-sharpe (sharpe-from (:performance result)
                                   target-return
                                   target-vol)
        sharpe-delta (when (and (opt-format/finite-number? current-sharpe)
                                (opt-format/finite-number? target-sharpe))
                       (- (or target-sharpe 0) current-sharpe))]
    (kpi-card "portfolio-optimizer-scenario-kpi-sharpe"
              "Sharpe · current → target"
              (if (opt-format/finite-number? current-sharpe)
                [:span [:span {:class ["text-trading-muted"]} (opt-format/format-decimal current-sharpe)]
                 " → "
                 (opt-format/format-decimal target-sharpe)]
                (opt-format/format-decimal target-sharpe))
              (if (opt-format/finite-number? current-sharpe)
                (str (format-decimal-delta sharpe-delta) " · raw Sharpe change")
                "raw Sharpe")
              (kpi-delta-class sharpe-delta
                               {:positive "text-trading-green"
                                :negative "text-warning"}))))

(defn- risk-balance-kpi-card
  "Equal Risk's success metric: max |contribution − 1/n| in points, current →
  recommended (deviation DOWN is good). Degrades to target-only on persisted
  results without current contributions."
  [result]
  (let [{:keys [current-max-pts target-max-pts rms-pts delta-pts]}
        (equal-risk-results/kpi-risk-balance result)]
    (kpi-card "portfolio-optimizer-scenario-kpi-risk-balance"
              "Risk balance · current → target"
              (if (opt-format/finite-number? current-max-pts)
                [:span [:span {:class ["text-trading-muted"]}
                        (equal-risk-results/format-pts current-max-pts)]
                 " → "
                 (equal-risk-results/format-pts target-max-pts)]
                (equal-risk-results/format-pts target-max-pts))
              (str "max deviation · rms "
                   (equal-risk-results/format-pts rms-pts))
              (kpi-delta-class delta-pts
                               {:positive "text-warning"
                                :negative "text-trading-green"}))))

(defn- sizing-deviation-kpi-card
  "Risk-weighted sizing's success metric: the largest relative |w|·σ spread
  among the assets the projection left free (0% = perfect parity). No
  Exact/Approximate quality label — the solve is deterministic."
  [result]
  (let [deviation (inverse-volatility-results/max-sizing-deviation result)]
    (kpi-card "portfolio-optimizer-scenario-kpi-sizing-deviation"
              "Sizing deviation · free assets"
              (opt-format/format-pct deviation)
              "max |w|·σ spread vs equal ideal"
              "text-trading-muted")))

(defn kpi-strip
  [result*]
  (let [{:keys [current-return current-vol target-return target-vol
                return-delta vol-delta] :as deltas} (recommendation-deltas result*)
        preview (:rebalance-preview result*)
        diagnostics (:diagnostics result*)
        equal-risk? (equal-risk-results/equal-risk-result? result*)
        inverse-volatility? (inverse-volatility-results/inverse-volatility-result?
                             result*)
        covariance-only? (contains? contracts-constants/covariance-only-objective-kinds
                                    (get-in result* [:solver :objective-kind]))
        gross (:gross-exposure diagnostics)
        net (:net-exposure diagnostics)]
    [:section {:class ["optimizer-scenario-kpi-strip"
                       "grid" "grid-cols-2" "border-y" "border-base-300" "bg-base-100/95" "lg:grid-cols-5"]
               :data-role "portfolio-optimizer-scenario-kpi-strip"}
     (kpi-card "portfolio-optimizer-scenario-kpi-volatility"
               "Volatility · current → target"
               (if (opt-format/finite-number? current-vol)
                 [:span [:span {:class ["text-trading-muted"]} (opt-format/format-pct current-vol)]
                  " → "
                  (opt-format/format-pct target-vol)]
                 (opt-format/format-pct target-vol))
               (if (opt-format/finite-number? current-vol)
                 (str (opt-format/format-pct-delta vol-delta) " · annualized")
                 "annualized")
               (if covariance-only?
                 "text-trading-muted"
                 (kpi-delta-class vol-delta
                                  {:positive "text-warning"
                                   :negative "text-trading-green"})))
     (kpi-card "portfolio-optimizer-scenario-kpi-expected-return"
               "Expected Return · current → target"
               (if (opt-format/finite-number? current-return)
                 [:span [:span {:class ["text-trading-muted"]} (opt-format/format-pct current-return)]
                  " → "
                  (opt-format/format-pct target-return)]
                 (opt-format/format-pct target-return))
               (if (opt-format/finite-number? current-return)
                 (str (opt-format/format-pct-delta return-delta) " · annualized")
                 "annualized")
               (if covariance-only?
                 "text-trading-muted"
                 (kpi-delta-class return-delta
                                  {:positive "text-trading-green"
                                   :negative "text-warning"})))
     (cond
       equal-risk? (risk-balance-kpi-card result*)
       inverse-volatility? (sizing-deviation-kpi-card result*)
       :else (sharpe-kpi-card result* deltas))
     (kpi-card "portfolio-optimizer-scenario-kpi-turnover"
               "Turnover Required"
               (opt-format/format-pct (:turnover diagnostics))
               (str "rebalance " (opt-format/keyword-label (:status preview))))
     (kpi-card "portfolio-optimizer-scenario-kpi-rebalance"
               "Gross / Net"
               (str (opt-format/format-multiple gross) " / " (opt-format/format-multiple net))
               "constraint utilization")]))
