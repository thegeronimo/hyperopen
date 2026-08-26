(ns hyperopen.portfolio.optimizer.defaults
  (:require [hyperopen.portfolio.optimizer.application.black-litterman-editor-model :as bl-editor-model]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.portfolio.optimizer.domain.history-assumptions :as history-assumptions]))

(defn default-draft
  []
  {:schema-version contracts/draft-schema-version
   :status :draft
   :name "Untitled Optimization"
   :universe []
   :objective {:kind :minimum-variance}
   :return-model {:kind :historical-mean}
   :risk-model {:kind :ledoit-wolf-dense}
   :constraints {:long-only? false
                 :include-spot? false
                 :gross-max 2.0
                 :net-min 1.0
                 :net-max 1.0
                 ;; Net tolerance as a decimal fraction of realized gross (5% = 0.05).
                 :net-band-pct 0.0
                 :max-asset-weight 0.5
                 :dust-usdc 0.0
                 :asset-overrides {}
                 :held-locks []
                 :perp-leverage {}
                 :allowlist []
                 :blocklist []
                 :max-turnover 1.0
                 :rebalance-tolerance 0.03}
   :execution-assumptions {:default-order-type :market
                           :fallback-slippage-bps 25
                           :fee-mode :taker
                           :manual-capital-usdc nil}
   :history-assumptions {}
   :proxy-reference-instruments []
   :metadata {:created-at-ms nil
              :updated-at-ms nil
              :dirty? false}})

(defn untouched-draft?
  "True when the in-memory draft carries no user (or machine) input yet: absent, or
  still structurally equal to a fresh default draft. The holdings preseed and the
  persisted-draft restore both gate on this so they can never clobber real input."
  [draft]
  (or (nil? draft)
      (= draft (default-draft))))

(def default-history-assumption
  "Default per-asset history-assumption draft for the given behavior. Delegates to
  the pure domain policy so engine, actions, and view models share one source."
  history-assumptions/default-assumption)

(defn default-run-state
  []
  {:status :idle
   :run-id nil
   :scenario-id nil
   :request-signature nil
   :started-at-ms nil
   :completed-at-ms nil
   :error nil
   :result nil})

(defn default-history-load-state
  []
  {:status :idle
   :request-signature nil
   :started-at-ms nil
   :completed-at-ms nil
   :error nil
   :warnings []})

(defn default-history-prefetch-state
  []
  {:queue []
   :active-instrument-id nil
   :by-instrument-id {}})

(defn default-history-discovery-state
  []
  {:status :idle
   :contract-version nil
   :request-id nil
   :dataset-version nil
   :loaded-at-ms nil
   :instruments-by-backend-id {}
   :backend-id-by-local-id {}
   :warnings []
   :error nil})

(defn default-optimization-progress-state
  []
  {:status :idle
   :run-id nil
   :scenario-id nil
   :started-at-ms nil
   :completed-at-ms nil
   :active-step nil
   :overall-percent 0
   :steps []
   :error nil})

(defn default-scenario-save-state
  []
  {:status :idle
   :scenario-id nil
   :started-at-ms nil
   :completed-at-ms nil
   :error nil})

(defn default-scenario-save-modal-state
  []
  {:open? false
   :name ""
   :error nil})

(defn default-scenario-index-load-state
  []
  {:status :idle
   :started-at-ms nil
   :completed-at-ms nil
   :error nil})

(defn default-scenario-load-state
  []
  {:status :idle
   :scenario-id nil
   :started-at-ms nil
   :completed-at-ms nil
   :error nil})

(defn default-scenario-archive-state
  []
  {:status :idle
   :scenario-id nil
   :started-at-ms nil
   :completed-at-ms nil
   :error nil})

(defn default-scenario-duplicate-state
  []
  {:status :idle
   :source-scenario-id nil
   :duplicated-scenario-id nil
   :started-at-ms nil
   :completed-at-ms nil
   :error nil})

(defn default-execution-modal-state
  []
  {:open? false
   :plan nil
   :submitting? false
   :error nil
   ;; v4 execution-tab staging interaction state (cosmetic + pre-submit phase).
   :phase :staged
   :default-order-type :recommended
   :overrides {}
   :params {}
   :open-row nil
   :order-filter :all
   ;; Per-oid cancel/keep choices for untagged open orders that overlap the rebalance
   ;; (the cross-session decision surface); {oid-string true} = user chose to cancel.
   :overlap-cancels {}
   ;; Held instruments the allocator excluded that the trader marked "sell to zero"
   ;; for THIS staging session (set of instrument-id strings). Deliberately reset on
   ;; every execution-tab entry: a staged sell of a held-out asset is an explicit,
   ;; per-staging decision, never a sticky default.
   :exit-instrument-ids #{}})

(defn default-execution-state
  []
  {:status :idle
   :attempt nil
   :history []
   :error nil})

(defn default-tracking-state
  []
  {:status :idle
   :scenario-id nil
   :updated-at-ms nil
   :snapshots []
   :error nil})

(defn default-refinement-state
  []
  {:active? false
   :depth nil
   :requested-points nil
   :baseline-result nil})

(defn default-optimizer-state
  []
  {:draft (default-draft)
   :refinement (default-refinement-state)
   :active-scenario {:loaded-id nil
                     :status :idle
                     :read-only? false}
   :scenario-index {:ordered-ids []
                    :by-id {}}
   :last-successful-run nil
   :constraint-profiles {}
   :view-library {}
   :history-data {:candle-history-by-coin {}
                  :funding-history-by-coin {}
                  :vault-details-by-address {}
                  :warnings []}
   :history-discovery (default-history-discovery-state)
   :history-prefetch (default-history-prefetch-state)
   :history-load-state (default-history-load-state)
   :optimization-progress (default-optimization-progress-state)
   :scenario-save-state (default-scenario-save-state)
   :scenario-save-modal (default-scenario-save-modal-state)
   :scenario-index-load-state (default-scenario-index-load-state)
   :scenario-load-state (default-scenario-load-state)
   :scenario-archive-state (default-scenario-archive-state)
	   :scenario-duplicate-state (default-scenario-duplicate-state)
	   :execution-modal (default-execution-modal-state)
	   :execution (default-execution-state)
	   :tracking (default-tracking-state)
	   :run-state (default-run-state)})

(defn default-optimizer-ui-state
  []
  {:list-filter :active
   :list-sort :updated-desc
   :universe-search-query ""
   :universe-search-active-index 0
   :universe-search-type-filter :all
   :universe-search-quote-filter :all
   :proxy-search-queries {}
   :objective-menu-open? false
   :scenario-menu-open? false
   :objective-menu-selection nil
   :objective-menu-view-order []
   :objective-menu-view-drafts {}
   :black-litterman-editor (bl-editor-model/default-editor-state)
   :workspace-panel :setup
   :results-tab :recommendation
   :diagnostics-tab :conditioning
   :frontier-overlay-mode :standalone
   :constrain-frontier? false
   :refinement-depth nil
   :exposure-zoom-level nil
   :return-views-filter :all
   :stale-auto-recompute {:request-signature nil
                          :input-signature nil
                          :scenario-id nil}})
