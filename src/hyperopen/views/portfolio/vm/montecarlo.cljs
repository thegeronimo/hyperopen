(ns hyperopen.views.portfolio.vm.montecarlo
  "Read model for the portfolio Monte Carlo tab.

  Turns the cheap inputs assembled by `portfolio-vm` (the strategy's realized
  cumulative-return rows, a source version for cache-keying, the live total
  equity, and the selected scope/time-range) plus the user's controls into a
  render-ready model. The expensive `engine/run` call is cached by an input
  signature so it does not recompute on every live-data re-render; it only runs
  when the tab is actually rendered (the tab's `:render` closure is the only
  caller) and when an input that affects the numbers changes.

  The engine runs in unit-equity space (start-equity = 1), so the live account
  equity does not invalidate the cache — the view multiplies by `:live-equity`
  only where dollar amounts are shown."
  (:require [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.montecarlo.actions :as mc-actions]
            [hyperopen.portfolio.montecarlo.engine :as engine]))

(def min-sample
  "Fewest realized return intervals required before resampling is meaningful.
  Below this we show an explanatory state instead of a misleading fan."
  30)

(def ^:private chrome
  "Per-surface copy, data-role prefix, root class, and action ids consumed by the
  shared Monte Carlo views (`views/portfolio/montecarlo/*`)."
  {:root-class "portfolio-monte-carlo"
   :data-role-prefix "portfolio-monte-carlo"
   :set-control-action :actions/set-portfolio-monte-carlo-control
   :rerun-action :actions/rerun-portfolio-monte-carlo
   :subject "portfolio"
   :history-owner "your"
   :equity-label "Ending equity"
   :lede (str "Resamples your realized returns thousands of times to map the "
              "range of outcomes the same strategy could produce. Preserves your "
              "return distribution while reshuffling the path. Isolating luck from "
              "skill.")})

(def ^:private scope-labels
  {:all "Perps + Spot + Vaults"
   :perps "Perps"
   :vaults "Vaults"})

(def ^:private range-labels
  {:day "24H"
   :week "7D"
   :month "30D"
   :three-month "3M"
   :six-month "6M"
   :one-year "1Y"
   :two-year "2Y"
   :all-time "All-time"})

(defonce ^:private result-cache (atom nil))

(defn reset-cache!
  "Drop the memoized engine result. Called by `reset-portfolio-vm-cache!`."
  []
  (reset! result-cache nil))

(defn- realized-intervals
  "Irregular intervals (each carrying its real `:dt-years`, `:simple-return` and
  `:log-return`) derived from the strategy's cumulative-return rows — the exact
  series the tearsheet annualizes by elapsed time. Using these instead of one
  return per calendar day is what makes the Monte Carlo Sharpe/vol/CAGR agree
  with the Performance Metrics tab for sparse, irregularly-spaced vault history."
  [strategy-cumulative-rows]
  (history/cumulative-rows->irregular-intervals (or strategy-cumulative-rows [])))

(defn- total-years
  [intervals]
  (reduce (fn [acc {:keys [dt-years]}] (+ acc (or dt-years 0))) 0 intervals))

(defn- run-cached
  [engine-sig opts]
  (let [cache @result-cache]
    (if (and (map? cache) (= engine-sig (:sig cache)))
      (:result cache)
      (let [result (engine/run opts)]
        (reset! result-cache {:sig engine-sig :result result})
        result))))

(defn montecarlo-model
  "Build the Monte Carlo render model from `state` and the cheap `inputs` map
  produced by `portfolio-vm` (`:strategy-cumulative-rows`,
  `:strategy-source-version`, `:start-equity`, `:summary-scope`,
  `:summary-time-range`)."
  [state inputs]
  (let [{:keys [strategy-cumulative-rows strategy-source-version
                start-equity summary-scope summary-time-range]} inputs
        {:keys [method sims horizon bust goal seed run-nonce] :as controls}
        (mc-actions/controls state)
        intervals (realized-intervals strategy-cumulative-rows)
        sample-size (count intervals)
        years (total-years intervals)
        ppy-eff (if (pos? years) (/ sample-size years) 0)
        ;; :shuffle uses the whole realized history. :bootstrap's horizon is a
        ;; calendar span (months) clamped to the realized span, converted to a
        ;; resample step count via the realized points-per-year cadence.
        target-years (min (/ horizon 12) years)
        engine-horizon (if (= method :shuffle)
                         sample-size
                         (max 1 (js/Math.round (* target-years ppy-eff))))
        live-equity (if (and (number? start-equity) (pos? start-equity))
                      start-equity
                      0)
        method-tag (str (if (= method :shuffle) "Shuffle · " "Bootstrap · ")
                        (get scope-labels summary-scope "Portfolio")
                        " · "
                        (get range-labels summary-time-range "")
                        " window")
        base {:controls controls
              :chrome chrome
              :method method
              :method-options mc-actions/method-options
              :sims-options mc-actions/sims-options
              :horizon-options mc-actions/horizon-options
              :sample-size sample-size
              :total-years years
              :min-sample min-sample
              :method-tag method-tag
              :live-equity live-equity
              :bust-fraction (/ bust 100)
              :goal-fraction (/ goal 100)
              :run-key [strategy-source-version method sims horizon bust goal seed run-nonce]}]
    (cond
      (zero? sample-size)
      (assoc base :status :empty)

      (< sample-size min-sample)
      (assoc base :status :insufficient-history)

      :else
      (let [engine-sig [strategy-source-version method engine-horizon sims bust goal seed]
            result (run-cached engine-sig
                               {:intervals intervals
                                :method method
                                :sims sims
                                :horizon engine-horizon
                                :bust (/ bust 100)
                                :goal (/ goal 100)
                                :seed seed
                                :start-equity 1})]
        (assoc base :status :ready :result result)))))
