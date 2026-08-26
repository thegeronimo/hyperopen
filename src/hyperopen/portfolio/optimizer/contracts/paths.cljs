(ns hyperopen.portfolio.optimizer.contracts.paths)

(def optimizer-path [:portfolio :optimizer])
(def draft-path (conj optimizer-path :draft))
(def draft-id-path (conj draft-path :id))
(def draft-name-path (conj draft-path :name))
(def draft-status-path (conj draft-path :status))
(def draft-universe-path (conj draft-path :universe))
(def draft-objective-path (conj draft-path :objective))
(def draft-return-model-path (conj draft-path :return-model))
(def draft-return-model-views-path (conj draft-return-model-path :views))
(def draft-risk-model-path (conj draft-path :risk-model))
(def draft-constraints-path (conj draft-path :constraints))
(def draft-execution-assumptions-path (conj draft-path :execution-assumptions))
(def draft-history-assumptions-path (conj draft-path :history-assumptions))
(def draft-proxy-reference-instruments-path
  (conj draft-path :proxy-reference-instruments))
(def draft-metadata-path (conj draft-path :metadata))
(def draft-dirty-path (conj draft-metadata-path :dirty?))
(def draft-universe-source-path (conj draft-metadata-path :universe-source))
(def draft-persist-path (conj optimizer-path :draft-persist))
(def active-scenario-path (conj optimizer-path :active-scenario))
(def active-scenario-loaded-id-path (conj active-scenario-path :loaded-id))
(def active-scenario-name-path (conj active-scenario-path :name))
(def active-scenario-status-path (conj active-scenario-path :status))
(def active-scenario-read-only-path (conj active-scenario-path :read-only?))
(def run-state-path (conj optimizer-path :run-state))
(def run-state-status-path (conj run-state-path :status))
(def runtime-path (conj optimizer-path :runtime))
(def runtime-as-of-ms-path (conj runtime-path :as-of-ms))
(def runtime-stale-after-ms-path (conj runtime-path :stale-after-ms))
(def runtime-funding-periods-per-year-path
  (conj runtime-path :funding-periods-per-year))
(def runtime-orderbook-stale-after-ms-path
  (conj runtime-path :orderbook-stale-after-ms))
(def history-data-path (conj optimizer-path :history-data))
(def history-discovery-path (conj optimizer-path :history-discovery))
(def market-cap-by-coin-path (conj optimizer-path :market-cap-by-coin))
(def history-load-state-path (conj optimizer-path :history-load-state))
(def history-load-state-status-path (conj history-load-state-path :status))
(def history-load-state-request-signature-path
  (conj history-load-state-path :request-signature))
(def history-prefetch-path (conj optimizer-path :history-prefetch))
(def history-prefetch-active-instrument-id-path
  (conj history-prefetch-path :active-instrument-id))
(def optimization-progress-path (conj optimizer-path :optimization-progress))
(def optimization-progress-status-path (conj optimization-progress-path :status))
(def scenario-index-path (conj optimizer-path :scenario-index))
(def scenario-save-state-path (conj optimizer-path :scenario-save-state))
(def scenario-load-state-path (conj optimizer-path :scenario-load-state))
(def scenario-index-load-state-path (conj optimizer-path :scenario-index-load-state))
(def scenario-archive-state-path (conj optimizer-path :scenario-archive-state))
(def scenario-duplicate-state-path (conj optimizer-path :scenario-duplicate-state))
(def scenario-save-modal-path (conj optimizer-path :scenario-save-modal))
(def scenario-save-modal-error-path (conj scenario-save-modal-path :error))
(def last-successful-run-path (conj optimizer-path :last-successful-run))
(def last-successful-run-result-path (conj last-successful-run-path :result))
(def constraint-profiles-path (conj optimizer-path :constraint-profiles))
(def view-library-path (conj optimizer-path :view-library))
;; Per-wallet remembered history assumptions (state mirror of the
;; assumption-library::<addr> IndexedDB record): {instrument-id library-entry}.
(def assumption-library-path (conj optimizer-path :assumption-library))
(def refinement-path (conj optimizer-path :refinement))
(def refinement-active-path (conj refinement-path :active?))
(def refinement-depth-path (conj refinement-path :depth))
(def refinement-requested-points-path (conj refinement-path :requested-points))
(def refinement-baseline-result-path (conj refinement-path :baseline-result))
(def execution-modal-path (conj optimizer-path :execution-modal))
(def execution-modal-error-path (conj execution-modal-path :error))
(def execution-modal-submitting-path (conj execution-modal-path :submitting?))
(def execution-path (conj optimizer-path :execution))
(def execution-history-path (conj execution-path :history))
(def execution-run-attempt-path (conj execution-path :run-attempt))
(def execution-abort-requested-path (conj execution-path :abort-requested?))
(def execution-persistence-error-path (conj execution-path :persistence-error))
;; Resting orders left on the book by PREVIOUS optimizer execution runs. Lives OUTSIDE
;; execution-path because that map is replaced wholesale on staging / discard / restage /
;; ledger-apply — exactly the moments the carryover must survive so a new run can cancel
;; the stale orders before submitting.
(def execution-resting-carryover-path (conj optimizer-path :execution-resting-carryover))
(def tracking-path (conj optimizer-path :tracking))
(def tracking-error-path (conj tracking-path :error))
(def optimizer-ui-path [:portfolio-ui :optimizer])
(def ui-list-filter-path (conj optimizer-ui-path :list-filter))
(def ui-list-sort-path (conj optimizer-ui-path :list-sort))
(def ui-workspace-panel-path (conj optimizer-ui-path :workspace-panel))
(def ui-results-tab-path (conj optimizer-ui-path :results-tab))
(def ui-diagnostics-tab-path (conj optimizer-ui-path :diagnostics-tab))
(def ui-universe-search-query-path (conj optimizer-ui-path :universe-search-query))
(def ui-universe-search-active-index-path
  (conj optimizer-ui-path :universe-search-active-index))
;; Universe search facets. :all, or a market-type keyword / quote token string.
;; Shared with the draft add-asset popover the same way the query is, and reset
;; alongside it so a filter can never sit invisibly on a surface with no chips.
(def ui-universe-search-type-filter-path
  (conj optimizer-ui-path :universe-search-type-filter))
(def ui-universe-search-quote-filter-path
  (conj optimizer-ui-path :universe-search-quote-filter))
;; Per-thin-asset proxy search query: {thin-instrument-id query-string}. Each
;; proxy card's catalog typeahead reads/writes its own entry.
(def ui-proxy-search-queries-path
  (conj optimizer-ui-path :proxy-search-queries))
;; Asset the History-assumptions queue is working on (the rail pills, Prev and
;; Skip set it). Never cleaned up: an id absent from the current card set falls
;; back to the queue view-model's default — the first asset still unsettled.
(def ui-history-assumption-active-path
  (conj optimizer-ui-path :history-assumption-active))
(def ui-draft-add-asset-open-path
  (conj optimizer-ui-path :draft-add-asset-open?))
;; Instrument whose contribution breakdown the Equal Risk correlation view
;; explains (set by Allocation-row clicks). Never cleaned up: an id absent
;; from the current result falls back to the view-model's default selection.
(def ui-selected-risk-instrument-path
  (conj optimizer-ui-path :selected-risk-instrument))
(def ui-objective-menu-open-path
  (conj optimizer-ui-path :objective-menu-open?))
;; Workspace-header "Scenarios" popover (the saved-scenario library menu).
(def ui-scenario-menu-open-path
  (conj optimizer-ui-path :scenario-menu-open?))
(def ui-objective-menu-selection-path
  (conj optimizer-ui-path :objective-menu-selection))
(def ui-objective-menu-view-order-path
  (conj optimizer-ui-path :objective-menu-view-order))
(def ui-objective-menu-view-drafts-path
  (conj optimizer-ui-path :objective-menu-view-drafts))
(def ui-objective-menu-target-sigma-path
  (conj optimizer-ui-path :objective-menu-target-sigma))
(def ui-black-litterman-editor-path
  (conj optimizer-ui-path :black-litterman-editor))
(def ui-frontier-overlay-mode-path
  (conj optimizer-ui-path :frontier-overlay-mode))
(def ui-target-sigma-draft-path
  (conj optimizer-ui-path :target-sigma-draft))
(def ui-constrain-frontier-path (conj optimizer-ui-path :constrain-frontier?))
(def ui-stale-auto-recompute-path
  (conj optimizer-ui-path :stale-auto-recompute))
(def ui-refinement-depth-path (conj optimizer-ui-path :refinement-depth))
(def ui-exposure-zoom-level-path (conj optimizer-ui-path :exposure-zoom-level))
(def ui-return-views-filter-path (conj optimizer-ui-path :return-views-filter))
(def ui-return-views-io-note-path (conj optimizer-ui-path :return-views-io-note))
(def ui-history-assumptions-io-note-path
  (conj optimizer-ui-path :history-assumptions-io-note))

(def path-catalog
  {:optimizer/root optimizer-path
   :optimizer/draft draft-path
   :optimizer/draft-id draft-id-path
   :optimizer/draft-name draft-name-path
   :optimizer/draft-status draft-status-path
   :optimizer/draft-universe draft-universe-path
   :optimizer/draft-objective draft-objective-path
   :optimizer/draft-return-model draft-return-model-path
   :optimizer/draft-return-model-views draft-return-model-views-path
   :optimizer/draft-risk-model draft-risk-model-path
   :optimizer/draft-constraints draft-constraints-path
   :optimizer/draft-execution-assumptions draft-execution-assumptions-path
   :optimizer/draft-history-assumptions draft-history-assumptions-path
   :optimizer/draft-proxy-reference-instruments draft-proxy-reference-instruments-path
   :optimizer/draft-metadata draft-metadata-path
   :optimizer/draft-dirty draft-dirty-path
   :optimizer/draft-universe-source draft-universe-source-path
   :optimizer/draft-persist draft-persist-path
   :optimizer/active-scenario active-scenario-path
   :optimizer/active-scenario-loaded-id active-scenario-loaded-id-path
   :optimizer/active-scenario-name active-scenario-name-path
   :optimizer/active-scenario-status active-scenario-status-path
   :optimizer/active-scenario-read-only active-scenario-read-only-path
   :optimizer/run-state run-state-path
   :optimizer/run-state-status run-state-status-path
   :optimizer/runtime runtime-path
   :optimizer/runtime-as-of-ms runtime-as-of-ms-path
   :optimizer/runtime-stale-after-ms runtime-stale-after-ms-path
   :optimizer/runtime-funding-periods-per-year runtime-funding-periods-per-year-path
   :optimizer/runtime-orderbook-stale-after-ms runtime-orderbook-stale-after-ms-path
   :optimizer/history-data history-data-path
   :optimizer/history-discovery history-discovery-path
   :optimizer/market-cap-by-coin market-cap-by-coin-path
   :optimizer/history-load-state history-load-state-path
   :optimizer/history-load-state-status history-load-state-status-path
   :optimizer/history-load-state-request-signature
   history-load-state-request-signature-path
   :optimizer/history-prefetch history-prefetch-path
   :optimizer/history-prefetch-active-instrument-id
   history-prefetch-active-instrument-id-path
   :optimizer/optimization-progress optimization-progress-path
   :optimizer/optimization-progress-status optimization-progress-status-path
   :optimizer/scenario-index scenario-index-path
   :optimizer/scenario-save-state scenario-save-state-path
   :optimizer/scenario-load-state scenario-load-state-path
   :optimizer/scenario-index-load-state scenario-index-load-state-path
   :optimizer/scenario-archive-state scenario-archive-state-path
   :optimizer/scenario-duplicate-state scenario-duplicate-state-path
   :optimizer/scenario-save-modal scenario-save-modal-path
   :optimizer/scenario-save-modal-error scenario-save-modal-error-path
   :optimizer/last-successful-run last-successful-run-path
   :optimizer/last-successful-run-result last-successful-run-result-path
   :optimizer/constraint-profiles constraint-profiles-path
   :optimizer/view-library view-library-path
   :optimizer/assumption-library assumption-library-path
   :optimizer/refinement refinement-path
   :optimizer/refinement-active refinement-active-path
   :optimizer/refinement-depth refinement-depth-path
   :optimizer/refinement-requested-points refinement-requested-points-path
   :optimizer/refinement-baseline-result refinement-baseline-result-path
   :optimizer/execution-modal execution-modal-path
   :optimizer/execution-modal-error execution-modal-error-path
   :optimizer/execution-modal-submitting execution-modal-submitting-path
   :optimizer/execution execution-path
   :optimizer/execution-history execution-history-path
   :optimizer/execution-run-attempt execution-run-attempt-path
   :optimizer/execution-abort-requested execution-abort-requested-path
   :optimizer/execution-persistence-error execution-persistence-error-path
   :optimizer/execution-resting-carryover execution-resting-carryover-path
   :optimizer/tracking tracking-path
   :optimizer/tracking-error tracking-error-path
   :optimizer-ui/root optimizer-ui-path
   :optimizer-ui/list-filter ui-list-filter-path
   :optimizer-ui/list-sort ui-list-sort-path
   :optimizer-ui/workspace-panel ui-workspace-panel-path
   :optimizer-ui/results-tab ui-results-tab-path
   :optimizer-ui/diagnostics-tab ui-diagnostics-tab-path
   :optimizer-ui/universe-search-query ui-universe-search-query-path
   :optimizer-ui/universe-search-active-index ui-universe-search-active-index-path
   :optimizer-ui/universe-search-type-filter ui-universe-search-type-filter-path
   :optimizer-ui/universe-search-quote-filter ui-universe-search-quote-filter-path
   :optimizer-ui/draft-add-asset-open ui-draft-add-asset-open-path
   :optimizer-ui/selected-risk-instrument ui-selected-risk-instrument-path
   :optimizer-ui/objective-menu-open ui-objective-menu-open-path
   :optimizer-ui/scenario-menu-open ui-scenario-menu-open-path
   :optimizer-ui/objective-menu-selection ui-objective-menu-selection-path
   :optimizer-ui/objective-menu-view-order ui-objective-menu-view-order-path
   :optimizer-ui/objective-menu-view-drafts ui-objective-menu-view-drafts-path
   :optimizer-ui/objective-menu-target-sigma ui-objective-menu-target-sigma-path
   :optimizer-ui/black-litterman-editor ui-black-litterman-editor-path
   :optimizer-ui/frontier-overlay-mode ui-frontier-overlay-mode-path
   :optimizer-ui/target-sigma-draft ui-target-sigma-draft-path
   :optimizer-ui/constrain-frontier ui-constrain-frontier-path
   :optimizer-ui/stale-auto-recompute ui-stale-auto-recompute-path
   :optimizer-ui/refinement-depth ui-refinement-depth-path
   :optimizer-ui/exposure-zoom-level ui-exposure-zoom-level-path
   :optimizer-ui/return-views-filter ui-return-views-filter-path
   :optimizer-ui/return-views-io-note ui-return-views-io-note-path
   :optimizer-ui/history-assumptions-io-note ui-history-assumptions-io-note-path
   :optimizer-ui/history-assumption-active ui-history-assumption-active-path})

(defn optimizer-state-path
  [& segments]
  (into optimizer-path segments))

(defn optimizer-ui-state-path
  [& segments]
  (into optimizer-ui-path segments))

(defn contract-path
  [path-id & segments]
  (if-let [base-path (get path-catalog path-id)]
    (into base-path segments)
    (throw (ex-info "Unknown optimizer contract path id."
                    {:path-id path-id}))))
