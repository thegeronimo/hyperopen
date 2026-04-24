(ns hyperopen.portfolio.optimizer.defaults)

(defn default-draft
  []
  {:schema-version 1
   :status :draft
   :name "Untitled Optimization"
   :universe []
   :objective {:kind :minimum-variance}
   :return-model {:kind :historical-mean}
   :risk-model {:kind :ledoit-wolf}
   :constraints {:long-only? false
                 :gross-max 1.0
                 :net-min -1.0
                 :net-max 1.0
                 :max-asset-weight 0.35
                 :dust-usdc 50.0
                 :asset-overrides {}
                 :held-locks []
                 :perp-leverage {}
                 :allowlist []
                 :blocklist []
                 :max-turnover 0.35
                 :rebalance-tolerance 0.01}
   :execution-assumptions {:default-order-type :market
                           :fallback-slippage-bps 25
                           :fee-mode :taker}
   :metadata {:created-at-ms nil
              :updated-at-ms nil
              :dirty? false}})

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

(defn default-scenario-save-state
  []
  {:status :idle
   :scenario-id nil
   :started-at-ms nil
   :completed-at-ms nil
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
   :error nil})

(defn default-optimizer-state
  []
  {:draft (default-draft)
   :active-scenario {:loaded-id nil
                     :status :idle
                     :read-only? false}
   :scenario-index {:ordered-ids []
                    :by-id {}}
   :last-successful-run nil
   :history-data {:candle-history-by-coin {}
                  :funding-history-by-coin {}
                  :warnings []}
   :history-load-state (default-history-load-state)
   :scenario-save-state (default-scenario-save-state)
   :scenario-index-load-state (default-scenario-index-load-state)
   :scenario-load-state (default-scenario-load-state)
   :scenario-archive-state (default-scenario-archive-state)
   :scenario-duplicate-state (default-scenario-duplicate-state)
   :execution-modal (default-execution-modal-state)
   :run-state (default-run-state)})

(defn default-optimizer-ui-state
  []
  {:list-filter :active
   :list-sort :updated-desc
   :universe-search-query ""
   :workspace-panel :setup
   :results-tab :allocation
   :diagnostics-tab :conditioning})
