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
                           :slippage-fallback-bps 25
                           :fee-mode :taker}
   :metadata {:created-at-ms nil
              :updated-at-ms nil}})

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

(defn default-optimizer-state
  []
  {:draft (default-draft)
   :active-scenario {:loaded-id nil
                     :status :idle
                     :read-only? false}
   :scenario-index {:ordered-ids []
                    :by-id {}}
   :last-successful-run nil
   :run-state (default-run-state)})

(defn default-optimizer-ui-state
  []
  {:list-filter :active
   :list-sort :updated-desc
   :workspace-panel :setup
   :results-tab :allocation
   :diagnostics-tab :conditioning})
