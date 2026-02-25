(ns hyperopen.state.app-defaults
  (:require [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]))

(defn default-websocket-state
  [websocket-health]
  {:status :disconnected
   :attempt 0
   :next-retry-at-ms nil
   :last-close nil
   :last-activity-at-ms nil
   :queue-size 0
   :health websocket-health})

(defn default-websocket-ui-state
  []
  {:diagnostics-open? false
   :show-market-offline-banner? false
   :show-surface-freshness-cues? false
   :reveal-sensitive? false
   :copy-status nil
   :reconnect-cooldown-until-ms nil
   :reset-in-progress? false
   :reset-cooldown-until-ms nil
   :reset-counts {:market_data 0
                  :orders_oms 0
                  :all 0}
   :auto-recover-cooldown-until-ms nil
   :auto-recover-count 0
   :reconnect-count 0
   :diagnostics-timeline []})

(defn default-orders-state
  []
  {:open-orders []
   :open-orders-snapshot []
   :open-orders-snapshot-by-dex {}
   :fills []
   :fundings-raw []
   :fundings []
   :order-history []
   :ledger []})

(defn default-wallet-state
  [default-agent-state]
  {:connected? false
   :address nil
   :chain-id nil
   :connecting? false
   :error nil
   :agent default-agent-state})

(defn default-asset-selector-state
  []
  {:visible-dropdown nil
   :search-term ""
   :sort-by :volume
   :sort-direction :desc
   :markets []
   :market-by-key {}
   :scroll-top 0
   :render-limit 120
   :last-render-limit-increase-ms nil
   :highlighted-market-key nil
   :loading? false
   :phase :bootstrap
   :cache-hydrated? false
   :loaded-at-ms nil
   :favorites #{}
   :loaded-icons #{}
   :missing-icons #{}
   :favorites-only? false
   :strict? false
   :active-tab :all})

(defn default-chart-options-state
  []
  {:timeframes-dropdown-visible false
   :selected-timeframe :1d
   :chart-type-dropdown-visible false
   :selected-chart-type :candlestick
   :indicators-dropdown-visible false
   :volume-visible? true
   :active-indicators {}
   :indicators-search-term ""})

(defn default-orderbook-ui-state
  []
  {:size-unit :base
   :size-unit-dropdown-visible? false
   :price-aggregation-dropdown-visible? false
   :price-aggregation-by-coin {}
   :active-tab :orderbook})

(defn default-portfolio-ui-state
  []
  {:summary-scope :all
   :summary-time-range :month
   :chart-tab :account-value
   :summary-scope-dropdown-open? false
   :summary-time-range-dropdown-open? false})

(defn default-portfolio-state
  []
  {:summary-by-key {}
   :user-fees nil
   :loading? false
   :user-fees-loading? false
   :error nil
   :user-fees-error nil
   :loaded-at-ms nil
   :user-fees-loaded-at-ms nil})

(defn default-account-info-state
  [{:keys [default-trade-history
           default-funding-history
           default-order-history]}]
  {:selected-tab :balances
   :loading false
   :error nil
   :hide-small-balances? false
   :balances-coin-search ""
   :balances-sort {:column nil :direction :asc}
   :positions-sort {:column nil :direction :asc}
   :positions {:direction-filter :all
               :coin-search ""
               :filter-open? false}
   :open-orders-sort {:column "Time" :direction :desc}
   :open-orders {:direction-filter :all
                 :filter-open? false}
   :trade-history default-trade-history
   :funding-history default-funding-history
   :order-history default-order-history})

(defn default-app-state
  [{:keys [websocket-health
           default-agent-state
           default-order-form
           default-order-form-ui
           default-order-form-runtime
           default-trade-history
           default-funding-history
           default-order-history]}]
  {:websocket (default-websocket-state websocket-health)
   :websocket-ui (default-websocket-ui-state)
   :active-assets {:contexts {}
                   :loading false}
   :active-asset nil
   :active-market nil
   :orderbooks {}
   :webdata2 {}
   :perp-dexs []
   :perp-dex-fee-config-by-name {}
   :perp-dex-clearinghouse {}
   :spot {:meta nil
          :clearinghouse-state nil
          :loading-meta? false
          :loading-balances? false
          :error nil}
   :orders (default-orders-state)
   :wallet (default-wallet-state default-agent-state)
   :ui {:toast nil}
   :account {:mode :classic
             :abstraction-raw nil}
   :router {:path "/trade"}
   :order-form (or default-order-form {})
   :order-form-ui (or default-order-form-ui {})
   :order-form-runtime (or default-order-form-runtime {})
   :positions-ui {:tpsl-modal (position-tpsl/default-modal-state)
                  :reduce-popover (position-reduce/default-popover-state)}
   :funding-ui {:modal nil}
   :asset-selector (default-asset-selector-state)
   :chart-options (default-chart-options-state)
   :orderbook-ui (default-orderbook-ui-state)
   :portfolio-ui (default-portfolio-ui-state)
   :portfolio (default-portfolio-state)
   :account-info (default-account-info-state {:default-trade-history default-trade-history
                                              :default-funding-history default-funding-history
                                              :default-order-history default-order-history})})
