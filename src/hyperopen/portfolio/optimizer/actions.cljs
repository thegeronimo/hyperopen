(ns hyperopen.portfolio.optimizer.actions
  (:require [hyperopen.portfolio.optimizer.actions.default-assumptions :as default-assumptions]
            [hyperopen.portfolio.optimizer.actions.draft :as draft]
            [hyperopen.portfolio.optimizer.actions.draft-persistence :as draft-persistence]
            [hyperopen.portfolio.optimizer.actions.execution :as execution]
            [hyperopen.portfolio.optimizer.actions.exposure :as exposure]
            [hyperopen.portfolio.optimizer.actions.history-assumptions-io :as history-assumptions-io]
            [hyperopen.portfolio.optimizer.actions.refinement :as refinement]
            [hyperopen.portfolio.optimizer.actions.return-views-io :as return-views-io]
            [hyperopen.portfolio.optimizer.actions.run :as run]
            [hyperopen.portfolio.optimizer.actions.scenario-library :as scenario-library]
            [hyperopen.portfolio.optimizer.actions.target-sigma :as target-sigma]
            [hyperopen.portfolio.optimizer.actions.tracking :as tracking]
            [hyperopen.portfolio.optimizer.actions.universe :as universe]
            [hyperopen.portfolio.optimizer.actions.view-library :as view-library-actions]))

(def set-portfolio-optimizer-objective-kind
  draft/set-portfolio-optimizer-objective-kind)

(def set-portfolio-optimizer-return-model-kind
  draft/set-portfolio-optimizer-return-model-kind)

(def set-portfolio-optimizer-risk-model-kind
  draft/set-portfolio-optimizer-risk-model-kind)

(def open-portfolio-optimizer-objective-menu
  draft/open-portfolio-optimizer-objective-menu)

(def close-portfolio-optimizer-objective-menu
  draft/close-portfolio-optimizer-objective-menu)

(def handle-portfolio-optimizer-objective-menu-keydown
  draft/handle-portfolio-optimizer-objective-menu-keydown)

(def select-portfolio-optimizer-objective-menu-option
  draft/select-portfolio-optimizer-objective-menu-option)

(def set-portfolio-optimizer-objective-menu-view-return
  draft/set-portfolio-optimizer-objective-menu-view-return)

(def step-portfolio-optimizer-objective-menu-view-return
  draft/step-portfolio-optimizer-objective-menu-view-return)

(def set-portfolio-optimizer-objective-menu-view-confidence
  draft/set-portfolio-optimizer-objective-menu-view-confidence)

(def remove-portfolio-optimizer-objective-menu-view
  draft/remove-portfolio-optimizer-objective-menu-view)

(def add-portfolio-optimizer-objective-menu-view
  draft/add-portfolio-optimizer-objective-menu-view)

(def apply-portfolio-optimizer-objective-menu-selection-and-run
  draft/apply-portfolio-optimizer-objective-menu-selection-and-run)

(def switch-portfolio-optimizer-objective-and-run
  draft/switch-portfolio-optimizer-objective-and-run)

(def set-portfolio-optimizer-return-views-filter
  draft/set-portfolio-optimizer-return-views-filter)

(def apply-portfolio-optimizer-setup-preset
  draft/apply-portfolio-optimizer-setup-preset)

(def set-portfolio-optimizer-constraint
  draft/set-portfolio-optimizer-constraint)

(def set-portfolio-optimizer-exposure-point
  exposure/set-portfolio-optimizer-exposure-point)

(def set-portfolio-optimizer-exposure-band
  exposure/set-portfolio-optimizer-exposure-band)

(def apply-portfolio-optimizer-exposure-preset
  exposure/apply-portfolio-optimizer-exposure-preset)

(def set-portfolio-optimizer-exposure-zoom-level
  exposure/set-portfolio-optimizer-exposure-zoom-level)

(def reset-portfolio-optimizer-constraints-to-system
  exposure/reset-portfolio-optimizer-constraints-to-system)

(def save-portfolio-optimizer-constraint-default
  exposure/save-portfolio-optimizer-constraint-default)

(def apply-portfolio-optimizer-constraint-default
  exposure/apply-portfolio-optimizer-constraint-default)

(def set-portfolio-optimizer-objective-parameter
  draft/set-portfolio-optimizer-objective-parameter)

(def set-portfolio-optimizer-objective-parameter-percent
  target-sigma/set-portfolio-optimizer-objective-parameter-percent)

(def set-portfolio-optimizer-target-sigma-draft
  target-sigma/set-portfolio-optimizer-target-sigma-draft)

(def set-portfolio-optimizer-objective-menu-target-sigma
  target-sigma/set-portfolio-optimizer-objective-menu-target-sigma)

(def reset-portfolio-optimizer-target-sigma-draft
  target-sigma/reset-portfolio-optimizer-target-sigma-draft)

(def rerun-portfolio-optimizer-at-target-sigma
  target-sigma/rerun-portfolio-optimizer-at-target-sigma)

(def set-portfolio-optimizer-execution-assumption
  draft/set-portfolio-optimizer-execution-assumption)

(def set-portfolio-optimizer-instrument-filter
  draft/set-portfolio-optimizer-instrument-filter)

(def set-portfolio-optimizer-asset-override
  draft/set-portfolio-optimizer-asset-override)

(def set-portfolio-optimizer-history-assumption-mode
  draft/set-portfolio-optimizer-history-assumption-mode)

(def set-portfolio-optimizer-history-assumption-expected-return
  draft/set-portfolio-optimizer-history-assumption-expected-return)

(def set-portfolio-optimizer-history-assumption-expected-volatility
  draft/set-portfolio-optimizer-history-assumption-expected-volatility)

(def set-portfolio-optimizer-history-assumption-max-weight-cap
  draft/set-portfolio-optimizer-history-assumption-max-weight-cap)

(def clear-portfolio-optimizer-history-assumption
  draft/clear-portfolio-optimizer-history-assumption)

(def set-portfolio-optimizer-history-assumption-proxy-asset
  draft/set-portfolio-optimizer-history-assumption-proxy-asset)

(def set-portfolio-optimizer-history-assumption-proxy-search
  draft/set-portfolio-optimizer-history-assumption-proxy-search)

(def set-portfolio-optimizer-history-assumption-relationship-strength
  draft/set-portfolio-optimizer-history-assumption-relationship-strength)

(def apply-portfolio-optimizer-history-assumption
  draft/apply-portfolio-optimizer-history-assumption)

(def reset-portfolio-optimizer-history-assumption
  draft/reset-portfolio-optimizer-history-assumption)

(def set-portfolio-optimizer-history-assumption-active
  draft/set-portfolio-optimizer-history-assumption-active)

(def hydrate-portfolio-optimizer-history-assumption-library
  draft/hydrate-portfolio-optimizer-history-assumption-library)

(def hydrate-portfolio-optimizer-view-library
  view-library-actions/hydrate-portfolio-optimizer-view-library)

(def set-portfolio-optimizer-universe-search-query
  universe/set-portfolio-optimizer-universe-search-query)

(def set-portfolio-optimizer-draft-add-asset-open
  universe/set-portfolio-optimizer-draft-add-asset-open)

(def handle-portfolio-optimizer-universe-search-keydown
  universe/handle-portfolio-optimizer-universe-search-keydown)

(def handle-portfolio-optimizer-draft-add-asset-keydown
  universe/handle-portfolio-optimizer-draft-add-asset-keydown)

(def set-portfolio-optimizer-results-tab
  run/set-portfolio-optimizer-results-tab)

(def set-portfolio-optimizer-selected-risk-instrument
  run/set-portfolio-optimizer-selected-risk-instrument)

(def add-portfolio-optimizer-universe-instrument
  universe/add-portfolio-optimizer-universe-instrument)

(def add-portfolio-optimizer-universe-instrument-and-run
  universe/add-portfolio-optimizer-universe-instrument-and-run)

(def toggle-portfolio-optimizer-universe-instrument-exclusion-and-run
  universe/toggle-portfolio-optimizer-universe-instrument-exclusion-and-run)

(def set-portfolio-optimizer-universe-instrument-side
  universe/set-portfolio-optimizer-universe-instrument-side)

(def set-portfolio-optimizer-universe-instrument-side-and-run
  universe/set-portfolio-optimizer-universe-instrument-side-and-run)

(def remove-portfolio-optimizer-universe-instrument
  universe/remove-portfolio-optimizer-universe-instrument)

(def set-portfolio-optimizer-universe-from-current
  universe/set-portfolio-optimizer-universe-from-current)

(def clear-portfolio-optimizer-universe
  universe/clear-portfolio-optimizer-universe)

(def auto-preseed-portfolio-optimizer-universe-from-current
  universe/auto-preseed-portfolio-optimizer-universe-from-current)

(def restore-or-preseed-portfolio-optimizer-draft
  draft-persistence/restore-or-preseed-portfolio-optimizer-draft)

(def hydrate-portfolio-optimizer-draft
  draft-persistence/hydrate-portfolio-optimizer-draft)

(def load-portfolio-optimizer-history-from-draft
  run/load-portfolio-optimizer-history-from-draft)

(def run-portfolio-optimizer-from-draft
  run/run-portfolio-optimizer-from-draft)

(def run-portfolio-optimizer-from-ready-draft
  run/run-portfolio-optimizer-from-ready-draft)

(def auto-recompute-stale-portfolio-optimizer-scenario
  run/auto-recompute-stale-portfolio-optimizer-scenario)

(def save-portfolio-optimizer-scenario-from-current
  run/save-portfolio-optimizer-scenario-from-current)

(def open-portfolio-optimizer-scenario-save-modal
  run/open-portfolio-optimizer-scenario-save-modal)

(def close-portfolio-optimizer-scenario-save-modal
  run/close-portfolio-optimizer-scenario-save-modal)

(def set-portfolio-optimizer-scenario-save-name
  run/set-portfolio-optimizer-scenario-save-name)

(def confirm-portfolio-optimizer-scenario-save
  run/confirm-portfolio-optimizer-scenario-save)

(def toggle-portfolio-optimizer-scenario-menu
  scenario-library/toggle-portfolio-optimizer-scenario-menu)

(def close-portfolio-optimizer-scenario-menu
  scenario-library/close-portfolio-optimizer-scenario-menu)

(def handle-portfolio-optimizer-scenario-menu-keydown
  scenario-library/handle-portfolio-optimizer-scenario-menu-keydown)

(def new-portfolio-optimizer-scenario
  scenario-library/new-portfolio-optimizer-scenario)

(def open-portfolio-optimizer-execution
  execution/open-portfolio-optimizer-execution)

(def set-portfolio-optimizer-execution-phase
  execution/set-portfolio-optimizer-execution-phase)

(def set-portfolio-optimizer-execution-default-order-type
  execution/set-portfolio-optimizer-execution-default-order-type)

(def set-portfolio-optimizer-execution-row-order-type
  execution/set-portfolio-optimizer-execution-row-order-type)

(def toggle-portfolio-optimizer-execution-row
  execution/toggle-portfolio-optimizer-execution-row)

(def set-portfolio-optimizer-execution-row-param
  execution/set-portfolio-optimizer-execution-row-param)

(def confirm-portfolio-optimizer-execution
  execution/confirm-portfolio-optimizer-execution)

(def amend-portfolio-optimizer-execution-order
  execution/amend-portfolio-optimizer-execution-order)

(def resume-portfolio-optimizer-execution
  execution/resume-portfolio-optimizer-execution)

(def revert-portfolio-optimizer-execution-filled
  execution/revert-portfolio-optimizer-execution-filled)

(def restage-portfolio-optimizer-execution-smaller
  execution/restage-portfolio-optimizer-execution-smaller)

(def pause-portfolio-optimizer-execution
  execution/pause-portfolio-optimizer-execution)

(def discard-portfolio-optimizer-execution
  execution/discard-portfolio-optimizer-execution)

(def open-portfolio-optimizer-execution-in-ticket
  execution/open-portfolio-optimizer-execution-in-ticket)

(def set-portfolio-optimizer-execution-order-filter
  execution/set-portfolio-optimizer-execution-order-filter)

(def set-portfolio-optimizer-execution-overlap-cancel
  execution/set-portfolio-optimizer-execution-overlap-cancel)

(def set-portfolio-optimizer-execution-exit
  execution/set-portfolio-optimizer-execution-exit)

(def restage-portfolio-optimizer-execution-plan
  execution/restage-portfolio-optimizer-execution-plan)

(def set-portfolio-optimizer-execution-auto-exit
  execution/set-portfolio-optimizer-execution-auto-exit)

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

(def set-portfolio-optimizer-refinement-depth
  refinement/set-portfolio-optimizer-refinement-depth)

(def refine-portfolio-optimizer
  refinement/refine-portfolio-optimizer)

(def stop-portfolio-optimizer-refinement
  refinement/stop-portfolio-optimizer-refinement)

(def export-portfolio-optimizer-return-views
  return-views-io/export-portfolio-optimizer-return-views)

(def import-portfolio-optimizer-return-views
  return-views-io/import-portfolio-optimizer-return-views)

(def apply-imported-portfolio-optimizer-return-views
  return-views-io/apply-imported-portfolio-optimizer-return-views)

(def dismiss-portfolio-optimizer-return-views-io-note
  return-views-io/dismiss-portfolio-optimizer-return-views-io-note)

(def export-portfolio-optimizer-history-assumptions
  history-assumptions-io/export-portfolio-optimizer-history-assumptions)

(def import-portfolio-optimizer-history-assumptions
  history-assumptions-io/import-portfolio-optimizer-history-assumptions)

(def apply-imported-portfolio-optimizer-history-assumptions
  history-assumptions-io/apply-imported-portfolio-optimizer-history-assumptions)

(def dismiss-portfolio-optimizer-history-assumptions-io-note
  history-assumptions-io/dismiss-portfolio-optimizer-history-assumptions-io-note)

(def apply-portfolio-optimizer-recommended-history-assumption
  default-assumptions/apply-portfolio-optimizer-recommended-history-assumption)

(def apply-portfolio-optimizer-recommended-history-assumptions
  default-assumptions/apply-portfolio-optimizer-recommended-history-assumptions)
