(ns hyperopen.views.vaults.detail-vm.montecarlo
  "Read model for the vault detail Monte Carlo tab.

  Mirrors the portfolio Monte Carlo read model: it turns the vault's realized
  cumulative-return rows (the same series that powers the vault performance
  tearsheet, assembled by `chart-section` as the strategy benchmark-context)
  plus the user's controls into a render-ready model. The expensive
  `engine/run` is cached by an input signature so live TVL/equity ticks do not
  recompute it; the engine runs in unit-equity space (start-equity = 1) and the
  view scales to vault TVL only where dollar amounts are shown.

  Realized-return derivation and the engine are reused unchanged from the
  portfolio surface; only the surface chrome, the dollar base (vault TVL), and
  the method-tag window differ. Unlike the portfolio tab, the vault MC tab has no
  range control of its own (the chart range selector lives in the hidden
  performance-metrics card), so `chart-section` always feeds it the vault's
  all-time realized history to maximize bootstrap samples."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.montecarlo.actions :as mc-actions]
            [hyperopen.portfolio.montecarlo.engine :as engine]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(def min-sample
  "Fewest realized return intervals required before resampling is meaningful.
  Below this we show an explanatory state instead of a misleading fan."
  30)

(def ^:private chrome
  "Per-surface copy, data-role prefix, root class, and action ids consumed by the
  shared Monte Carlo views (`views/portfolio/montecarlo/*`)."
  {:root-class "vault-monte-carlo"
   :data-role-prefix "vault-monte-carlo"
   :set-control-action :actions/set-vault-monte-carlo-control
   :rerun-action :actions/rerun-vault-monte-carlo
   :subject "vault"
   :history-owner "this vault's"
   :equity-label "Projected TVL"
   :lede (str "Resamples this vault's realized daily returns thousands of times to "
              "map the range of outcomes the same strategy could produce. Preserves "
              "the return distribution while reshuffling the path — isolating luck "
              "from skill across drawdowns, Sharpe and terminal value.")})

(defonce ^:private result-cache (atom nil))

(defn reset-cache!
  "Drop the memoized engine result. Called by `reset-vault-detail-vm-cache!`."
  []
  (reset! result-cache nil))

(defn- realized-intervals
  "Irregular intervals (each carrying its real `:dt-years`, `:simple-return` and
  `:log-return`) derived from the vault's cumulative-return rows — the exact
  series the tearsheet annualizes by elapsed time. Using these instead of one
  return per calendar day is what makes the Monte Carlo Sharpe/vol/CAGR agree
  with the vault's Performance Metrics for sparse, irregularly-spaced history."
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
  "Build the vault Monte Carlo render model from `state` and the cheap `inputs`
  map produced by `chart-section` (`:strategy-cumulative-rows`,
  `:strategy-source-version`, `:start-equity` (vault TVL), `:window-label`,
  `:vault-label`)."
  [state inputs]
  (let [{:keys [strategy-cumulative-rows strategy-source-version
                start-equity window-label vault-label]} inputs
        {:keys [method sims horizon bust goal seed run-nonce] :as controls}
        (mc-actions/controls-at state vault-ui-state/vault-monte-carlo-state-path)
        intervals (realized-intervals strategy-cumulative-rows)
        sample-size (count intervals)
        years (total-years intervals)
        ppy-eff (if (pos? years) (/ sample-size years) 0)
        target-years (min (/ horizon 12) years)
        engine-horizon (if (= method :shuffle)
                         sample-size
                         (max 1 (js/Math.round (* target-years ppy-eff))))
        live-equity (if (and (number? start-equity) (pos? start-equity))
                      start-equity
                      0)
        method-tag (str (if (= method :shuffle) "Shuffle · " "Bootstrap · ")
                        (if (str/blank? vault-label) "Vault" vault-label)
                        " · "
                        (or window-label "All-time")
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
