(ns hyperopen.state.app-defaults
  (:require [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.account.context :as account-context]
            [hyperopen.api-wallets.application.ui-state :as api-wallets-ui-state]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.i18n.locale :as i18n-locale]
            [hyperopen.order.cancel-visible-confirmation :as cancel-visible-confirmation]
            [hyperopen.order.submit-confirmation :as submit-confirmation]
            [hyperopen.leaderboard.actions :as leaderboard-actions]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.actions :as portfolio-actions]
            [hyperopen.surface-modules :as surface-modules]
            [hyperopen.trade-modules :as trade-modules]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.vaults.application.transfer-state :as vault-transfer-state]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]
            [hyperopen.vaults.infrastructure.persistence :as vault-persistence]))

(def ^:private portfolio-summary-time-range-storage-key
  "portfolio-summary-time-range")

(defn- default-portfolio-summary-time-range
  []
  (portfolio-actions/normalize-summary-time-range
   (platform/local-storage-get portfolio-summary-time-range-storage-key)))

(defn- default-vaults-snapshot-range
  []
  (vault-persistence/read-vaults-snapshot-range))

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
   :open-orders-hydrated? false
   :open-orders-snapshot []
   :open-orders-snapshot-by-dex {}
   :recently-canceled-oids #{}
   :recently-canceled-order-keys #{}
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

(defn default-ui-state
  []
  {:toast nil
   :toasts []
   :locale (i18n-locale/resolve-preferred-locale)})

(defn default-header-ui-state
  []
  {:mobile-menu-open? false
   :settings-open? false
   :settings-confirmation nil
   :settings-return-focus? false})

(defn default-trading-settings-state
  []
  trading-settings/default-state)

(defn default-asset-selector-state
  []
  {:visible-dropdown nil
   :search-term ""
   :sort-by :volume
   :sort-direction :desc
   :markets []
   :market-by-key {}
   :market-index-by-key {}
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

(defn default-trade-ui-state
  []
  {:mobile-surface :chart
   :mobile-asset-details-open? false})

(defn default-portfolio-ui-state
  []
  {:summary-scope :all
   :summary-time-range (default-portfolio-summary-time-range)
   :chart-tab :returns
   :account-info-tab :performance-metrics
   :returns-benchmark-coins ["BTC"]
   :returns-benchmark-coin "BTC"
   :returns-benchmark-search ""
   :returns-benchmark-suggestions-open? false
   :summary-scope-dropdown-open? false
   :summary-time-range-dropdown-open? false
   :performance-metrics-time-range-dropdown-open? false
   :volume-history-open? false
   :volume-history-anchor nil
   :fee-schedule-open? false
   :fee-schedule-anchor nil
   :fee-schedule-referral-discount nil
   :fee-schedule-staking-tier nil
   :fee-schedule-maker-rebate-tier nil
   :fee-schedule-referral-dropdown-open? false
   :fee-schedule-staking-dropdown-open? false
   :fee-schedule-maker-rebate-dropdown-open? false
   :fee-schedule-market-type :perps
   :fee-schedule-market-dropdown-open? false})

(defn default-portfolio-state
  []
  {:summary-by-key {}
   :user-fees nil
   :ledger-updates []
   :loading? false
   :user-fees-loading? false
   :user-fees-loading-for-address nil
   :error nil
   :user-fees-error nil
   :user-fees-error-for-address nil
   :ledger-error nil
   :loaded-at-ms nil
   :user-fees-loaded-at-ms nil
   :user-fees-loaded-for-address nil
   :ledger-loaded-at-ms nil})

(defn default-vaults-ui-state
  []
  {:search-query ""
   :filter-leading? true
   :filter-deposited? true
   :filter-others? true
   :filter-closed? false
   :snapshot-range (default-vaults-snapshot-range)
   :sort {:column vault-ui-state/default-vault-sort-column
          :direction vault-ui-state/default-vault-sort-direction}
   :user-vaults-page-size vault-ui-state/default-vault-user-page-size
   :user-vaults-page vault-ui-state/default-vault-user-page
   :user-vaults-page-size-dropdown-open? false
   :detail-tab vault-ui-state/default-vault-detail-tab
   :detail-activity-tab vault-ui-state/default-vault-detail-activity-tab
   :detail-activity-sort-by-tab {}
   :detail-activity-direction-filter vault-ui-state/default-vault-detail-activity-direction-filter
   :detail-activity-filter-open? false
   :detail-chart-timeframe-dropdown-open? false
   :detail-performance-metrics-timeframe-dropdown-open? false
   :detail-chart-series vault-ui-state/default-vault-detail-chart-series
   :detail-returns-benchmark-coins ["BTC"]
   :detail-returns-benchmark-coin "BTC"
   :detail-returns-benchmark-search ""
   :detail-returns-benchmark-suggestions-open? false
   :detail-performance-metrics-result nil
   :detail-performance-metrics-loading? false
   :vault-transfer-modal (vault-transfer-state/default-vault-transfer-modal-state)
   :list-loading? false
   :detail-loading? false})

(defn default-vaults-state
  []
  {:index-rows []
   :recent-summaries []
   :merged-index-rows []
   :index-cache {:hydrated? false
                 :saved-at-ms nil
                 :etag nil
                 :last-modified nil}
   :startup-preview nil
   :user-equities []
   :user-equity-by-address {}
   :details-by-address {}
   :benchmark-details-by-address {}
   :viewer-details-by-address {}
   :webdata-by-vault {}
   :fills-by-vault {}
   :funding-history-by-vault {}
   :order-history-by-vault {}
   :ledger-updates-by-vault {}
   :loading {:index? false
             :summaries? false
             :user-equities? false
             :details-by-address {}
             :benchmark-details-by-address {}
             :webdata-by-vault {}
             :fills-by-vault {}
             :funding-history-by-vault {}
             :order-history-by-vault {}
             :ledger-updates-by-vault {}}
   :errors {:index nil
            :summaries nil
            :user-equities nil
            :details-by-address {}
            :benchmark-details-by-address {}
            :webdata-by-vault {}
            :fills-by-vault {}
            :funding-history-by-vault {}
            :order-history-by-vault {}
            :ledger-updates-by-vault {}}
   :loaded-at-ms {:index nil
                  :summaries nil
                  :user-equities nil
                  :details-by-address {}
                  :benchmark-details-by-address {}
                  :webdata-by-vault {}
                  :fills-by-vault {}
                  :funding-history-by-vault {}
                  :order-history-by-vault {}
                  :ledger-updates-by-vault {}}})

(defn default-funding-comparison-ui-state
  []
  {:query ""
   :timeframe :8hour
   :sort {:column :open-interest
          :direction :desc}
   :loading? false})

(defn default-funding-comparison-state
  []
  {:predicted-fundings []
   :error nil
   :error-category nil
   :loaded-at-ms nil})

(defn default-leaderboard-ui-state
  []
  {:query ""
   :timeframe :month
   :sort {:column :pnl
          :direction :desc}
   :page 1
   :page-size leaderboard-actions/default-page-size
   :page-size-options leaderboard-actions/leaderboard-page-size-options
   :page-size-dropdown-open? false})

(defn default-leaderboard-state
  []
  {:rows []
   :excluded-addresses #{}
   :loading? false
   :error nil
   :error-category nil
   :loaded-at-ms nil})

(defn default-staking-ui-state
  []
  {:active-tab :validator-performance
   :validator-timeframe :week
   :validator-timeframe-dropdown-open? false
   :validator-page 0
   :validator-show-all? false
   :action-popover {:open? false
                    :kind nil
                    :anchor nil}
   :transfer-direction :spot->staking
   :validator-sort {:column :stake
                    :direction :desc}
   :selected-validator ""
   :validator-search-query ""
   :validator-dropdown-open? false
   :deposit-amount ""
   :withdraw-amount ""
   :delegate-amount ""
   :undelegate-amount ""
   :form-error nil
   :submitting {:deposit? false
                :withdraw? false
                :delegate? false
                :undelegate? false}})

(defn default-staking-state
  []
  {:validator-summaries []
   :delegator-summary nil
   :delegations []
   :rewards []
   :history []
   :loading {:validator-summaries false
             :delegator-summary false
             :delegations false
             :rewards false
             :history false}
   :errors {:validator-summaries nil
            :delegator-summary nil
            :delegations nil
            :rewards nil
            :history nil}
   :loaded-at-ms {:validator-summaries nil
                  :delegator-summary nil
                  :delegations nil
                  :rewards nil
                  :history nil}})

(defn default-api-wallets-ui-state
  []
  {:form (api-wallets-ui-state/default-form)
   :form-error nil
   :sort (api-wallets-ui-state/default-sort-state)
   :modal (api-wallets-ui-state/default-modal-state)
   :generated (api-wallets-ui-state/default-generated-state)})

(defn default-api-wallets-state
  []
  {:extra-agents []
   :default-agent-row nil
   :owner-webdata2 nil
   :server-time-ms nil
   :loading {:extra-agents? false
             :default-agent? false}
   :errors {:extra-agents nil
            :default-agent nil}
   :loaded-at-ms {:extra-agents nil
                  :default-agent nil}})

(defn default-account-info-state
  [{:keys [default-trade-history
           default-funding-history
           default-order-history]}]
  {:selected-tab :balances
   :loading false
   :error nil
   :mobile-expanded-card {:balances nil
                          :positions nil
                          :trade-history nil}
   :hide-small-balances? false
   :balances-coin-search ""
   :balances-sort {:column nil :direction :asc}
   :positions-sort {:column nil :direction :asc}
   :positions {:direction-filter :all
               :coin-search ""
               :filter-open? false}
   :open-orders-sort {:column "Time" :direction :desc}
   :open-orders {:direction-filter :all
                 :coin-search ""
                 :filter-open? false
                 :cancel-visible-confirmation (cancel-visible-confirmation/default-state)}
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
                   :loading false
                   :funding-predictability {:by-coin {}
                                            :loading-by-coin {}
                                            :error-by-coin {}
                                            :loaded-at-ms-by-coin {}}}
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
   :account-context (account-context/default-account-context-state)
   :ui (default-ui-state)
   :order-submit-confirmation (submit-confirmation/default-state)
   :header-ui (default-header-ui-state)
   :trading-settings (default-trading-settings-state)
   :account {:mode :classic
             :abstraction-raw nil}
   :router {:path "/trade"}
   :route-modules {:loaded #{}
                   :loading nil
                   :errors {}}
   :surface-modules (surface-modules/default-state)
   :trade-modules (trade-modules/default-state)
   :order-form (or default-order-form {})
   :order-form-ui (or default-order-form-ui {})
   :order-form-runtime (or default-order-form-runtime {})
   :positions-ui {:tpsl-modal (position-tpsl/default-modal-state)
                  :reduce-popover (position-reduce/default-popover-state)
                  :margin-modal (position-margin/default-modal-state)}
   :funding-ui {:modal (funding-actions/default-funding-modal-state)
                :tooltip {:visible-id nil
                          :pinned-id nil}
                :hypothetical-position-by-coin {}}
   :leaderboard-ui (default-leaderboard-ui-state)
   :leaderboard (default-leaderboard-state)
   :funding-comparison-ui (default-funding-comparison-ui-state)
   :funding-comparison (default-funding-comparison-state)
   :staking-ui (default-staking-ui-state)
   :staking (default-staking-state)
   :api-wallets-ui (default-api-wallets-ui-state)
   :api-wallets (default-api-wallets-state)
   :asset-selector (default-asset-selector-state)
   :chart-options (default-chart-options-state)
   :orderbook-ui (default-orderbook-ui-state)
   :trade-ui (default-trade-ui-state)
   :portfolio-ui (default-portfolio-ui-state)
   :portfolio (default-portfolio-state)
   :vaults-ui (default-vaults-ui-state)
   :vaults (default-vaults-state)
   :account-info (default-account-info-state {:default-trade-history default-trade-history
                                              :default-funding-history default-funding-history
                                              :default-order-history default-order-history})})
