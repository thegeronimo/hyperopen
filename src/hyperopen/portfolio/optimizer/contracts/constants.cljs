(ns hyperopen.portfolio.optimizer.contracts.constants)

(def maker-fee-bps
  "Canonical Hyperliquid maker fee in basis points — the lower fee a resting
  (limit/passive) execution order pays. It lives here, in the dependency-free
  contracts namespace, because BOTH rebalance-preview build sites must charge the
  same maker fee: the worker payload (application.engine.payload) and the frontend
  refresh (application.rebalance-preview). A preview built without it renders a
  resting order's all-in cost as a confident $0.

  Pinned to (* 100 (:maker hyperopen.domain.trading.core/default-fees)) by
  rebalance_preview_test — this namespace stays require-free so the engine payload
  can read it without pulling the trading core into the worker bundle."
  1.5)

(def draft-statuses
  #{:draft :saved :archived :tracking})

(def scenario-record-statuses
  #{:saved :archived :executed :partially-executed :tracking :failed})

(def tracking-snapshot-statuses
  #{:tracked :not-trackable})

(def result-payload-statuses
  #{:solved :infeasible :error :failed})

(def objective-kinds
  #{:minimum-variance :max-sharpe :target-return :target-volatility :equal-risk
    :inverse-volatility})

(def covariance-only-objective-kinds
  "Objectives that never consume expected returns: an invalid return model
  must not block their solve, dust removal must not drop their positions, and
  the setup flow never solicits return views for them."
  #{:equal-risk :inverse-volatility})

(def return-model-kinds
  #{:historical-mean :ew-mean :black-litterman})

(def risk-model-kinds
  #{:diagonal-shrink
    :ledoit-wolf
    :ledoit-wolf-dense
    :sample-covariance
    :mixed-frequency})

(def history-assumption-behaviors
  #{:conservative :proxy})
