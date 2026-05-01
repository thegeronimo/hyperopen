(ns hyperopen.portfolio.optimizer.actions
  (:require [hyperopen.portfolio.optimizer.actions.draft :as draft]
            [hyperopen.portfolio.optimizer.actions.execution :as execution]
            [hyperopen.portfolio.optimizer.actions.run :as run]
            [hyperopen.portfolio.optimizer.actions.tracking :as tracking]
            [hyperopen.portfolio.optimizer.actions.universe :as universe]))

(def set-portfolio-optimizer-objective-kind
  draft/set-portfolio-optimizer-objective-kind)

(def set-portfolio-optimizer-return-model-kind
  draft/set-portfolio-optimizer-return-model-kind)

(def set-portfolio-optimizer-risk-model-kind
  draft/set-portfolio-optimizer-risk-model-kind)

(def apply-portfolio-optimizer-setup-preset
  draft/apply-portfolio-optimizer-setup-preset)

(def set-portfolio-optimizer-constraint
  draft/set-portfolio-optimizer-constraint)

(def set-portfolio-optimizer-objective-parameter
  draft/set-portfolio-optimizer-objective-parameter)

(def set-portfolio-optimizer-execution-assumption
  draft/set-portfolio-optimizer-execution-assumption)

(def set-portfolio-optimizer-instrument-filter
  draft/set-portfolio-optimizer-instrument-filter)

(def set-portfolio-optimizer-asset-override
  draft/set-portfolio-optimizer-asset-override)

(def set-portfolio-optimizer-universe-search-query
  universe/set-portfolio-optimizer-universe-search-query)

(def handle-portfolio-optimizer-universe-search-keydown
  universe/handle-portfolio-optimizer-universe-search-keydown)

(def set-portfolio-optimizer-results-tab
  run/set-portfolio-optimizer-results-tab)

(def add-portfolio-optimizer-universe-instrument
  universe/add-portfolio-optimizer-universe-instrument)

(def remove-portfolio-optimizer-universe-instrument
  universe/remove-portfolio-optimizer-universe-instrument)

(def set-portfolio-optimizer-universe-from-current
  universe/set-portfolio-optimizer-universe-from-current)

(def load-portfolio-optimizer-history-from-draft
  run/load-portfolio-optimizer-history-from-draft)

(def run-portfolio-optimizer-from-draft
  run/run-portfolio-optimizer-from-draft)

(def run-portfolio-optimizer-from-ready-draft
  run/run-portfolio-optimizer-from-ready-draft)

(def save-portfolio-optimizer-scenario-from-current
  run/save-portfolio-optimizer-scenario-from-current)

(def open-portfolio-optimizer-execution-modal
  execution/open-portfolio-optimizer-execution-modal)

(def close-portfolio-optimizer-execution-modal
  execution/close-portfolio-optimizer-execution-modal)

(def confirm-portfolio-optimizer-execution
  execution/confirm-portfolio-optimizer-execution)

(def refresh-portfolio-optimizer-tracking
  tracking/refresh-portfolio-optimizer-tracking)

(def enable-portfolio-optimizer-manual-tracking
  tracking/enable-portfolio-optimizer-manual-tracking)

(def load-portfolio-optimizer-route
  run/load-portfolio-optimizer-route)

(def archive-portfolio-optimizer-scenario
  run/archive-portfolio-optimizer-scenario)

(def duplicate-portfolio-optimizer-scenario
  run/duplicate-portfolio-optimizer-scenario)

(def run-portfolio-optimizer
  run/run-portfolio-optimizer)
