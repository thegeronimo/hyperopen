(ns hyperopen.portfolio.optimizer.application.view-model
  (:require [hyperopen.portfolio.optimizer.application.view-model.black-litterman :as black-litterman]
            [hyperopen.portfolio.optimizer.application.view-model.execution :as execution]
            [hyperopen.portfolio.optimizer.application.view-model.scenario :as scenario]
            [hyperopen.portfolio.optimizer.application.view-model.scenario-library :as scenario-library]
            [hyperopen.portfolio.optimizer.application.view-model.setup :as setup]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-cards :as assumption-cards]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-queue :as assumption-queue]
            [hyperopen.portfolio.optimizer.application.view-model.setup-history-assumption-rail :as assumption-rail]
            [hyperopen.portfolio.optimizer.application.view-model.setup-summary :as setup-summary]
            [hyperopen.portfolio.optimizer.application.view-model.tracking :as tracking]
            [hyperopen.portfolio.optimizer.application.view-model.universe :as universe]
            [hyperopen.portfolio.optimizer.application.view-model.workspace :as workspace]))

(defn scenario-library-model
  [state]
  (scenario-library/library-model state))

(defn route-mismatched?
  [state scenario-id]
  (scenario/route-mismatched? state scenario-id))

(defn scenario-scoped-state
  [state scenario-id]
  (scenario/scenario-scoped-state state scenario-id))

(defn scenario-name
  [state scenario-id]
  (scenario/scenario-name state scenario-id))

(defn optimizer-draft
  [state]
  (workspace/optimizer-draft state))

(defn optimizer-running?
  [state]
  (workspace/optimizer-running? state))

(defn result
  [state]
  (workspace/result state))

(defn solved-result?
  [state]
  (workspace/solved-result? state))

(defn scenario-stale?
  [state readiness]
  (workspace/scenario-stale? state readiness))

(defn current-result?
  [state readiness]
  (workspace/current-result? state readiness))

(defn workspace-model
  [state route]
  (workspace/workspace-model state route))

(defn scenario-detail-model
  [state route]
  (scenario/scenario-detail-model state route))

(defn selected-history-status
  [state readiness history-load-state history-status-by-id instrument]
  (universe/selected-history-status state
                                    readiness
                                    history-load-state
                                    history-status-by-id
                                    instrument))

(defn selected-history-label
  [state readiness history-load-state history-status-by-id instrument]
  (universe/selected-history-label state
                                   readiness
                                   history-load-state
                                   history-status-by-id
                                   instrument))

(defn universe-section-model
  ([state draft]
   (universe/universe-section-model state draft))
  ([state draft opts]
   (universe/universe-section-model state draft opts)))

(defn universe-panel-model
  [state draft]
  (universe/universe-panel-model state draft))

(defn readiness-panel-model
  [readiness history-load-state]
  (setup/readiness-panel-model readiness history-load-state))

(defn run-verdict
  [readiness history-load-state]
  (setup/run-verdict readiness history-load-state))

(defn setup-summary-model
  ([draft]
   (setup-summary/setup-summary-model draft))
  ([draft formatters]
   (setup-summary/setup-summary-model draft formatters)))

(defn setup-summary-card-model
  ([draft]
   (setup-summary/setup-summary-card-model draft))
  ([draft formatters]
   (setup-summary/setup-summary-card-model draft formatters)))

(defn constraints-summary-line
  [constraints]
  (setup-summary/constraints-summary-line constraints))

(def return-model-display-names setup-summary/return-model-display-names)

(def risk-model-display-names setup-summary/risk-model-display-names)

(defn history-assumption-cards
  ([state draft readiness history-load-state]
   (assumption-cards/history-assumption-cards state draft readiness history-load-state))
  ([state draft readiness history-load-state formatters]
   (assumption-cards/history-assumption-cards state draft readiness history-load-state formatters)))

(defn history-assumption-queue-model
  ([state draft readiness history-load-state]
   (assumption-queue/history-assumption-queue-model state draft readiness history-load-state))
  ([state draft readiness history-load-state formatters]
   (assumption-queue/history-assumption-queue-model state draft readiness history-load-state formatters)))

(defn history-assumption-rail-model
  ([state draft readiness history-load-state]
   (assumption-rail/history-assumption-rail-model state draft readiness history-load-state))
  ([state draft readiness history-load-state formatters]
   (assumption-rail/history-assumption-rail-model state draft readiness history-load-state formatters)))

(defn black-litterman-preview-model
  [readiness]
  (black-litterman/build-preview readiness))

(defn black-litterman-cards-model
  [draft readiness preview formatters]
  (black-litterman/cards-model draft readiness preview formatters))

(defn tracking-model
  [state]
  (tracking/tracking-model state))

(defn execution-modal-model
  [state]
  (execution/execution-modal-model state))

(defn execution-tab-model
  [state]
  (execution/execution-tab-model state))

(defn inputs-audit-model
  [state]
  (scenario/inputs-audit-model state))
