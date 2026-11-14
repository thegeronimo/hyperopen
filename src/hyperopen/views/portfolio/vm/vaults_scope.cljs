(ns hyperopen.views.portfolio.vm.vaults-scope
  "Portfolio view-model pieces for the Vaults scope.

  The scope re-uses the whole summary pipeline: the vault sleeve is merged into
  `summary-by-key` as `:vault-day` ... `:vault-all-time` entries (the same shape
  Hyperliquid returns), so returns, drawdown, derived ranges, charts and the
  metrics tearsheet all run on it unchanged. This namespace owns the glue: the
  merge, the labels, the lifetime figures and the loading/empty/error states."
  (:require [hyperopen.portfolio.vault-attribution-selector :as selector]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.portfolio.vm.constants :as constants]
            [hyperopen.views.portfolio.vm.history :as vm-history]))

(defn vaults-scope?
  [scope]
  (= scope :vaults))

(defn attribution-model
  "Selector model, or nil outside the Vaults scope so other scopes never pay for
  (or depend on) vault data."
  [state scope]
  (when (vaults-scope? scope)
    (selector/model state)))

(defn summary-by-key
  "Raw `[:portfolio :summary-by-key]`, with the vault sleeve merged in for the
  Vaults scope. Vault keys only exist once the attribution is ready, so a
  loading/errored/empty account has NO vault entry and the scope resolves to no
  data rather than another scope's series."
  [state scope]
  (let [raw (get-in state [:portfolio :summary-by-key])
        entries (get-in (attribution-model state scope) [:attribution :summary-entries])]
    (if (seq entries)
      (merge raw entries)
      raw)))

(defn salt-source-version
  "Keeps vault series from sharing a metrics signature with any other scope."
  [scope version]
  (if (vaults-scope? scope)
    (hash [:vaults version])
    version))

(defn chart-tabs
  [scope]
  (if (vaults-scope? scope)
    (mapv (fn [{:keys [value] :as tab}]
            (if (= value :account-value)
              (assoc tab :label "Vault Value")
              tab))
          constants/chart-tab-options)
    constants/chart-tab-options))

(defn strategy-label
  [scope]
  (if (vaults-scope? scope) "Vaults" "Portfolio"))

(defn metric-label
  "Hover-tooltip metric name for `selected-tab`, scope-aware."
  [scope selected-tab]
  (when (and (vaults-scope? scope) (= selected-tab :account-value))
    "Vault Value"))

(defn performance-metrics
  "Labels the tearsheet for the scope and, until the vault series is ready,
  withholds numbers: the worker result still in state belongs to whatever scope
  was shown before, and must never sit under the Vaults header."
  [perf-metrics model scope]
  (let [labelled (assoc perf-metrics :portfolio-label (strategy-label scope))]
    (if (= :ready (:load-status model))
      labelled
      (assoc labelled
             :groups []
             :values {}
             :benchmark-values {}
             :benchmark-columns []
             :benchmark-selected? false
             :loading? false
             :stale? false))))

(def estimate-date-format-options
  {:year "numeric"
   :month "short"
   :day "2-digit"})

(defn- entry-first-time-ms
  [summary-entry]
  (some-> (vm-history/normalized-history-rows
           (vm-history/account-value-history-rows summary-entry))
          first
          :time-ms))

(defn estimate-note
  "Disclosure line for the part of the series built from coarse vault history,
  or nil when nothing visible is estimated."
  [model summary-entry]
  (let [daily-from-ms (get-in model [:attribution :daily-from-ms])
        first-ms (entry-first-time-ms summary-entry)]
    (when (and (number? daily-from-ms)
               (number? first-ms)
               (> daily-from-ms first-ms))
      (str "Estimated before "
           (or (fmt/format-intl-date-time daily-from-ms estimate-date-format-options)
               "--")
           " from vault history sampled about every 12 days"))))

(defn- status-of
  [model]
  (case (:load-status model)
    (:idle :loading) :loading
    :error :error
    :ready (if (= :empty (get-in model [:attribution :status])) :empty :ready)
    :none))

(defn summary-view
  "Summary-card model for the Vaults scope. `pnl` and `max-drawdown-pct` come
  from the SELECTED range's vault entry; everything under `:totals` is lifetime.
  Unknown is nil (rendered `--`), never zero."
  [model summary-entry pnl max-drawdown-pct]
  (let [status (status-of model)
        ready? (= :ready status)
        totals (when ready? (get-in model [:attribution :totals]))]
    {:status status
     :partial? (boolean (and ready? (:partial? model)))
     :ledger-partial? (boolean (and ready? (:ledger-partial? model)))
     :refresh-error? (boolean (and ready? (:refresh-error model)))
     :pnl (when ready? pnl)
     :max-drawdown-pct (when ready? max-drawdown-pct)
     :estimated? (boolean (and ready? (estimate-note model summary-entry)))
     :totals totals}))
