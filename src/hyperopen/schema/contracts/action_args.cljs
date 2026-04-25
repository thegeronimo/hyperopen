(ns hyperopen.schema.contracts.action-args
  (:require [cljs.spec.alpha :as s]
            [hyperopen.schema.contracts.common :as common]
            [hyperopen.schema.contracts.state :as state]))

(s/def ::funding-history-filter-path (s/or :path ::common/state-path
                                          :key keyword?))
(s/def ::funding-history-filter-args (s/tuple ::funding-history-filter-path any?))
(s/def ::add-indicator-args (s/tuple keyword? map?))
(s/def ::update-indicator-period-args (s/tuple keyword? any?))
(s/def ::cancel-order-args (s/tuple map?))
(s/def ::funding-hypothetical-seed-args
  (s/tuple ::common/non-empty-string
           (s/nilable ::common/numberish)
           map?))
(s/def ::cancel-visible-open-orders-args (s/tuple ::common/non-empty-map-vector))
(s/def ::confirm-cancel-visible-open-orders-args
  (s/or :orders-only (s/tuple ::common/non-empty-map-vector)
        :orders-and-anchor (s/tuple ::common/non-empty-map-vector any?)))
(s/def ::funding-modal-args (s/tuple any?))
(s/def ::funding-modal-field-args (s/tuple ::common/state-path any?))
(s/def ::api-wallet-form-field #{:name :address :days-valid})
(s/def ::api-wallet-form-field-args (s/tuple ::api-wallet-form-field any?))
(s/def ::api-wallet-row-args (s/tuple map?))
(s/def ::staking-form-field #{:deposit-amount
                              :withdraw-amount
                              :delegate-amount
                              :undelegate-amount
                              :selected-validator
                              :validator-search-query
                              :validator-dropdown-open?})
(s/def ::staking-form-field-args (s/tuple ::staking-form-field any?))

(s/def ::set-hyperunit-lifecycle-args (s/tuple ::state/hyperunit-lifecycle-input))
(s/def ::set-hyperunit-lifecycle-error-args (s/tuple (s/nilable string?)))
(s/def ::position-tpsl-open-args
  (s/or :position-only (s/tuple map?)
        :position-and-anchor (s/tuple map? any?)))
(s/def ::position-tpsl-modal-field-args (s/tuple ::common/state-path any?))
(s/def ::position-reduce-open-args
  (s/or :position-only (s/tuple map?)
        :position-and-anchor (s/tuple map? any?)))
(s/def ::position-reduce-popover-field-args (s/tuple ::common/state-path any?))
(s/def ::position-margin-open-args
  (s/or :position-only (s/tuple map?)
        :position-and-anchor (s/tuple map? any?)))
(s/def ::position-margin-modal-field-args (s/tuple ::common/state-path any?))
(s/def ::funding-send-open-args
  (s/or :none ::common/no-args
        :context-only (s/tuple map?)
        :context-and-anchor (s/tuple map? any?)
        :context-anchor-and-data-role (s/tuple map? any? (s/nilable string?))))
(s/def ::funding-modal-open-args
  (s/or :none ::common/no-args
        :anchor-only (s/tuple any?)
        :anchor-and-data-role (s/tuple any? (s/nilable string?))))
(s/def ::fee-schedule-open-args
  (s/or :none ::common/no-args
        :anchor-only (s/tuple any?)))
(s/def ::spectate-mode-open-args
  (s/or :none ::common/no-args
        :anchor-only (s/tuple any?)))
(s/def ::portfolio-volume-history-open-args
  (s/or :none ::common/no-args
        :anchor-only (s/tuple any?)))
(s/def ::portfolio-optimizer-model-kind-args ::common/keyword-or-string-args)
(s/def ::portfolio-optimizer-constraint-args
  (s/tuple ::common/keyword-or-string any?))
(s/def ::portfolio-optimizer-key-value-args
  (s/tuple ::common/keyword-or-string any?))
(s/def ::portfolio-optimizer-instrument-key-value-args
  (s/tuple ::common/keyword-or-string ::common/non-empty-string any?))
(s/def ::portfolio-optimizer-run-args
  (s/tuple map? map?))
(s/def ::portfolio-optimizer-scenario-id-args
  (s/tuple ::common/non-empty-string))
(s/def ::portfolio-optimizer-instrument-id-args
  (s/tuple ::common/non-empty-string))
(s/def ::staking-action-popover-open-args
  (s/or :kind-only (s/tuple (s/or :keyword keyword?
                                   :string string?))
        :kind-and-anchor (s/tuple (s/or :keyword keyword?
                                        :string string?)
                                  any?)))
(s/def ::api-submit-request (s/keys :req-un [::common/action]))
(s/def ::submit-unlocked-order-request-args
  (s/or :request-only (s/tuple ::api-submit-request)
        :request-and-path-values (s/tuple ::api-submit-request ::common/path-values)))
(s/def ::submit-unlocked-cancel-request-args
  (s/or :request-only (s/tuple ::api-submit-request)
        :request-and-path-values (s/tuple ::api-submit-request ::common/path-values)))

(s/def ::action-id (s/and keyword?
                          #(= "actions" (namespace %))))

(def action-args-spec-by-id
  {:actions/init-websockets ::common/no-args
   :actions/subscribe-to-asset ::common/coin-args
   :actions/subscribe-to-webdata2 ::common/address-args
   :actions/connect-wallet ::common/no-args
   :actions/disconnect-wallet ::common/no-args
   :actions/open-mobile-header-menu ::common/no-args
   :actions/close-mobile-header-menu ::common/no-args
   :actions/open-header-settings ::common/no-args
   :actions/close-header-settings ::common/no-args
   :actions/request-agent-storage-mode-change ::common/storage-mode-request-args
   :actions/cancel-agent-storage-mode-change ::common/no-args
   :actions/confirm-agent-storage-mode-change ::common/no-args
   :actions/request-agent-local-protection-mode-change ::common/local-protection-mode-request-args
   :actions/cancel-agent-local-protection-mode-change ::common/no-args
   :actions/confirm-agent-local-protection-mode-change ::common/no-args
   :actions/set-fill-alerts-enabled ::common/boolean-args
   :actions/set-sound-on-fill-enabled ::common/boolean-args
   :actions/set-animate-orderbook-enabled ::common/boolean-args
   :actions/set-fill-markers-enabled ::common/boolean-args
   :actions/set-confirm-open-orders-enabled ::common/boolean-args
   :actions/set-confirm-close-position-enabled ::common/boolean-args
   :actions/set-confirm-market-orders-enabled ::common/boolean-args
   :actions/navigate-mobile-header-menu ::common/path-args
   :actions/open-spectate-mode-mobile-header-menu ::spectate-mode-open-args
   :actions/open-spectate-mode-modal ::spectate-mode-open-args
   :actions/close-spectate-mode-modal ::common/no-args
   :actions/set-spectate-mode-search ::common/single-input-args
   :actions/set-spectate-mode-label ::common/single-input-args
   :actions/start-spectate-mode ::common/optional-string-args
   :actions/stop-spectate-mode ::common/no-args
   :actions/add-spectate-mode-watchlist-address ::common/optional-string-args
   :actions/remove-spectate-mode-watchlist-address ::common/address-args
   :actions/edit-spectate-mode-watchlist-address ::common/address-args
   :actions/clear-spectate-mode-watchlist-edit ::common/no-args
   :actions/copy-spectate-mode-watchlist-address ::common/address-args
   :actions/copy-spectate-mode-watchlist-link ::common/address-args
   :actions/start-spectate-mode-watchlist-address ::common/address-args
   :actions/enable-agent-trading ::common/no-args
   :actions/unlock-agent-trading ::common/unlock-agent-trading-args
   :actions/close-agent-recovery-modal ::common/no-args
   :actions/set-agent-storage-mode ::common/set-agent-storage-mode-args
   :actions/set-agent-local-protection-mode ::common/set-agent-local-protection-mode-args
   :actions/copy-wallet-address ::common/no-args
   :actions/reconnect-websocket ::common/no-args
   :actions/toggle-ws-diagnostics ::common/no-args
   :actions/close-ws-diagnostics ::common/no-args
   :actions/handle-ws-diagnostics-keydown ::common/key-args
   :actions/toggle-ws-diagnostics-sensitive ::common/no-args
   :actions/ws-diagnostics-reconnect-now ::common/no-args
   :actions/ws-diagnostics-copy ::common/no-args
   :actions/set-show-surface-freshness-cues ::common/boolean-args
   :actions/toggle-show-surface-freshness-cues ::common/no-args
   :actions/ws-diagnostics-reset-market-subscriptions ::common/ws-reset-source-args
   :actions/ws-diagnostics-reset-orders-subscriptions ::common/ws-reset-source-args
   :actions/ws-diagnostics-reset-all-subscriptions ::common/ws-reset-source-args
   :actions/toggle-asset-dropdown ::common/dropdown-target-args
   :actions/close-asset-dropdown ::common/no-args
   :actions/select-asset ::common/market-or-coin-args
   :actions/update-asset-search ::common/single-input-args
   :actions/update-asset-selector-sort ::common/keyword-args
   :actions/toggle-asset-selector-strict ::common/no-args
   :actions/toggle-asset-favorite ::common/market-key-args
   :actions/set-asset-selector-favorites-only ::common/boolean-args
   :actions/set-asset-selector-tab ::common/tab-args
   :actions/handle-asset-selector-shortcut ::common/asset-selector-shortcut-args
   :actions/set-asset-selector-live-market-subscriptions-paused ::common/boolean-args
   :actions/set-asset-selector-scroll-top ::common/single-input-args
   :actions/increase-asset-selector-render-limit ::common/no-args
   :actions/show-all-asset-selector-markets ::common/no-args
   :actions/maybe-increase-asset-selector-render-limit ::common/single-or-double-input-args
   :actions/refresh-asset-markets ::common/no-args
   :actions/mark-loaded-asset-icon ::common/market-key-args
   :actions/mark-missing-asset-icon ::common/market-key-args
   :actions/set-funding-tooltip-visible ::common/tooltip-toggle-args
   :actions/set-funding-tooltip-pinned ::common/tooltip-toggle-args
   :actions/enter-funding-hypothetical-position ::funding-hypothetical-seed-args
   :actions/reset-funding-hypothetical-position ::common/coin-args
   :actions/set-funding-hypothetical-size ::common/coin-number-input-args
   :actions/set-funding-hypothetical-value ::common/coin-number-input-args
   :actions/toggle-timeframes-dropdown ::common/no-args
   :actions/select-chart-timeframe ::common/keyword-args
   :actions/toggle-chart-type-dropdown ::common/no-args
   :actions/select-chart-type ::common/keyword-args
   :actions/toggle-indicators-dropdown ::common/no-args
   :actions/update-indicators-search ::common/single-input-args
   :actions/toggle-portfolio-summary-scope-dropdown ::common/no-args
   :actions/select-portfolio-summary-scope ::common/keyword-or-string-args
   :actions/toggle-portfolio-summary-time-range-dropdown ::common/no-args
   :actions/toggle-portfolio-performance-metrics-time-range-dropdown ::common/no-args
   :actions/open-portfolio-fee-schedule ::fee-schedule-open-args
   :actions/close-portfolio-fee-schedule ::common/no-args
   :actions/toggle-portfolio-fee-schedule-referral-dropdown ::common/no-args
   :actions/toggle-portfolio-fee-schedule-staking-dropdown ::common/no-args
   :actions/toggle-portfolio-fee-schedule-maker-rebate-dropdown ::common/no-args
   :actions/toggle-portfolio-fee-schedule-market-dropdown ::common/no-args
   :actions/select-portfolio-fee-schedule-referral-discount ::common/keyword-or-string-args
   :actions/select-portfolio-fee-schedule-staking-tier ::common/keyword-or-string-args
   :actions/select-portfolio-fee-schedule-maker-rebate-tier ::common/keyword-or-string-args
   :actions/select-portfolio-fee-schedule-market-type ::common/keyword-or-string-args
   :actions/handle-portfolio-fee-schedule-keydown ::common/key-args
   :actions/select-portfolio-summary-time-range ::common/keyword-or-string-args
   :actions/select-portfolio-chart-tab ::common/keyword-or-string-args
   :actions/set-portfolio-account-info-tab ::common/tab-args
   :actions/set-portfolio-returns-benchmark-search ::common/single-input-args
   :actions/set-portfolio-returns-benchmark-suggestions-open ::common/boolean-args
   :actions/select-portfolio-returns-benchmark ::common/optional-string-args
   :actions/remove-portfolio-returns-benchmark ::common/coin-args
   :actions/handle-portfolio-returns-benchmark-search-keydown ::common/keydown-with-optional-coin-args
   :actions/clear-portfolio-returns-benchmark ::common/no-args
   :actions/open-portfolio-volume-history ::portfolio-volume-history-open-args
   :actions/close-portfolio-volume-history ::common/no-args
   :actions/handle-portfolio-volume-history-keydown ::common/single-input-args
   :actions/set-portfolio-optimizer-objective-kind ::portfolio-optimizer-model-kind-args
   :actions/set-portfolio-optimizer-return-model-kind ::portfolio-optimizer-model-kind-args
   :actions/set-portfolio-optimizer-risk-model-kind ::portfolio-optimizer-model-kind-args
   :actions/set-portfolio-optimizer-constraint ::portfolio-optimizer-constraint-args
   :actions/set-portfolio-optimizer-objective-parameter ::portfolio-optimizer-key-value-args
   :actions/set-portfolio-optimizer-execution-assumption ::portfolio-optimizer-key-value-args
   :actions/set-portfolio-optimizer-instrument-filter ::portfolio-optimizer-instrument-key-value-args
   :actions/set-portfolio-optimizer-asset-override ::portfolio-optimizer-instrument-key-value-args
   :actions/set-portfolio-optimizer-universe-search-query ::common/single-input-args
   :actions/add-portfolio-optimizer-universe-instrument ::portfolio-optimizer-instrument-id-args
   :actions/remove-portfolio-optimizer-universe-instrument ::portfolio-optimizer-instrument-id-args
   :actions/set-portfolio-optimizer-universe-from-current ::common/no-args
   :actions/load-portfolio-optimizer-history-from-draft ::common/no-args
   :actions/save-portfolio-optimizer-scenario-from-current ::common/no-args
   :actions/load-portfolio-optimizer-route ::common/path-args
   :actions/archive-portfolio-optimizer-scenario ::portfolio-optimizer-scenario-id-args
   :actions/duplicate-portfolio-optimizer-scenario ::portfolio-optimizer-scenario-id-args
   :actions/open-portfolio-optimizer-execution-modal ::common/no-args
	   :actions/close-portfolio-optimizer-execution-modal ::common/no-args
	   :actions/confirm-portfolio-optimizer-execution ::common/no-args
	   :actions/refresh-portfolio-optimizer-tracking ::common/no-args
	   :actions/run-portfolio-optimizer-from-draft ::common/no-args
   :actions/run-portfolio-optimizer ::portfolio-optimizer-run-args
   :actions/toggle-orderbook-size-unit-dropdown ::common/no-args
   :actions/select-orderbook-size-unit ::common/keyword-or-string-args
   :actions/toggle-orderbook-price-aggregation-dropdown ::common/no-args
   :actions/select-orderbook-price-aggregation ::common/keyword-or-string-args
   :actions/select-orderbook-tab ::common/tab-args
   :actions/select-trade-mobile-surface ::common/tab-args
   :actions/toggle-trade-mobile-asset-details ::common/no-args
   :actions/add-indicator ::add-indicator-args
   :actions/remove-indicator ::common/keyword-args
   :actions/update-indicator-period ::update-indicator-period-args
   :actions/show-volume-indicator ::common/no-args
   :actions/hide-volume-indicator ::common/no-args
   :actions/select-account-info-tab ::common/tab-args
   :actions/select-account-info-twap-subtab ::common/keyword-or-string-args
   :actions/set-funding-history-filters ::funding-history-filter-args
   :actions/toggle-funding-history-filter-open ::common/no-args
   :actions/toggle-funding-history-filter-coin ::common/coin-args
   :actions/add-funding-history-filter-coin ::common/coin-args
   :actions/handle-funding-history-coin-search-keydown ::common/keydown-with-optional-coin-args
   :actions/reset-funding-history-filter-draft ::common/no-args
   :actions/apply-funding-history-filters ::common/no-args
   :actions/view-all-funding-history ::common/no-args
   :actions/export-funding-history-csv ::common/no-args
   :actions/set-funding-history-page-size ::common/single-input-args
   :actions/set-funding-history-page ::common/page-and-max-page-args
   :actions/next-funding-history-page ::common/max-page-args
   :actions/prev-funding-history-page ::common/max-page-args
   :actions/set-funding-history-page-input ::common/single-input-args
   :actions/apply-funding-history-page-input ::common/max-page-args
   :actions/handle-funding-history-page-input-keydown ::common/keydown-with-max-page-args
   :actions/set-trade-history-page-size ::common/single-input-args
   :actions/set-trade-history-page ::common/page-and-max-page-args
   :actions/next-trade-history-page ::common/max-page-args
   :actions/prev-trade-history-page ::common/max-page-args
   :actions/set-trade-history-page-input ::common/single-input-args
   :actions/apply-trade-history-page-input ::common/max-page-args
   :actions/handle-trade-history-page-input-keydown ::common/keydown-with-max-page-args
   :actions/sort-trade-history ::common/sort-column-args
   :actions/toggle-trade-history-direction-filter-open ::common/no-args
   :actions/set-trade-history-direction-filter ::common/keyword-or-string-args
   :actions/sort-positions ::common/sort-column-args
   :actions/toggle-positions-direction-filter-open ::common/no-args
   :actions/set-positions-direction-filter ::common/keyword-or-string-args
   :actions/sort-balances ::common/sort-column-args
   :actions/sort-open-orders ::common/sort-column-args
   :actions/toggle-open-orders-direction-filter-open ::common/no-args
   :actions/set-open-orders-direction-filter ::common/keyword-or-string-args
   :actions/sort-funding-history ::common/sort-column-args
   :actions/sort-order-history ::common/sort-column-args
   :actions/toggle-order-history-filter-open ::common/no-args
   :actions/set-order-history-status-filter ::common/keyword-or-string-args
   :actions/set-account-info-coin-search ::common/tab-and-input-args
   :actions/toggle-account-info-mobile-card ::common/tab-and-input-args
   :actions/set-order-history-page-size ::common/single-input-args
   :actions/set-order-history-page ::common/page-and-max-page-args
   :actions/next-order-history-page ::common/max-page-args
   :actions/prev-order-history-page ::common/max-page-args
   :actions/set-order-history-page-input ::common/single-input-args
   :actions/apply-order-history-page-input ::common/max-page-args
   :actions/handle-order-history-page-input-keydown ::common/keydown-with-max-page-args
   :actions/refresh-order-history ::common/no-args
   :actions/set-hide-small-balances ::common/boolean-args
   :actions/open-position-tpsl-modal ::position-tpsl-open-args
   :actions/close-position-tpsl-modal ::common/no-args
   :actions/handle-position-tpsl-modal-keydown ::common/key-args
   :actions/set-position-tpsl-modal-field ::position-tpsl-modal-field-args
   :actions/set-position-tpsl-configure-amount ::common/boolean-args
   :actions/set-position-tpsl-limit-price ::common/boolean-args
   :actions/submit-position-tpsl ::common/no-args
   :actions/trigger-close-all-positions ::common/no-args
   :actions/open-position-reduce-popover ::position-reduce-open-args
   :actions/close-position-reduce-popover ::common/no-args
   :actions/handle-position-reduce-popover-keydown ::common/key-args
   :actions/set-position-reduce-popover-field ::position-reduce-popover-field-args
   :actions/set-position-reduce-size-percent ::common/single-input-args
   :actions/set-position-reduce-limit-price-to-mid ::common/no-args
   :actions/submit-position-reduce-close ::common/no-args
   :actions/open-position-margin-modal ::position-margin-open-args
   :actions/close-position-margin-modal ::common/no-args
   :actions/handle-position-margin-modal-keydown ::common/key-args
   :actions/set-position-margin-modal-field ::position-margin-modal-field-args
   :actions/set-position-margin-amount-percent ::common/single-input-args
   :actions/set-position-margin-amount-to-max ::common/no-args
   :actions/submit-position-margin-update ::common/no-args
   :actions/select-order-entry-mode ::common/keyword-or-string-args
   :actions/select-pro-order-type ::common/keyword-or-string-args
   :actions/toggle-pro-order-type-dropdown ::common/no-args
   :actions/close-pro-order-type-dropdown ::common/no-args
   :actions/handle-pro-order-type-dropdown-keydown ::common/key-args
   :actions/toggle-margin-mode-dropdown ::common/no-args
   :actions/close-margin-mode-dropdown ::common/no-args
   :actions/handle-margin-mode-dropdown-keydown ::common/key-args
   :actions/toggle-leverage-popover ::common/no-args
   :actions/close-leverage-popover ::common/no-args
   :actions/handle-leverage-popover-keydown ::common/key-args
   :actions/toggle-size-unit-dropdown ::common/no-args
   :actions/close-size-unit-dropdown ::common/no-args
   :actions/handle-size-unit-dropdown-keydown ::common/key-args
   :actions/toggle-tpsl-unit-dropdown ::common/no-args
   :actions/close-tpsl-unit-dropdown ::common/no-args
   :actions/handle-tpsl-unit-dropdown-keydown ::common/key-args
   :actions/toggle-tif-dropdown ::common/no-args
   :actions/close-tif-dropdown ::common/no-args
   :actions/handle-tif-dropdown-keydown ::common/key-args
   :actions/set-order-ui-leverage-draft ::common/single-input-args
   :actions/confirm-order-ui-leverage ::common/no-args
   :actions/set-order-ui-leverage ::common/single-input-args
   :actions/set-order-margin-mode ::common/keyword-or-string-args
   :actions/set-order-size-percent ::common/single-input-args
   :actions/set-order-size-display ::common/single-input-args
   :actions/set-order-size-input-mode ::common/keyword-or-string-args
   :actions/focus-order-price-input ::common/no-args
   :actions/blur-order-price-input ::common/no-args
   :actions/set-order-price-to-mid ::common/no-args
   :actions/toggle-order-tpsl-panel ::common/no-args
   :actions/update-order-form (s/tuple ::common/state-path any?)
   :actions/dismiss-order-feedback-toast ::common/optional-string-args
   :actions/expand-order-feedback-toast ::common/optional-string-args
   :actions/collapse-order-feedback-toast ::common/optional-string-args
   :actions/dismiss-order-submission-confirmation ::common/no-args
   :actions/handle-order-submission-confirmation-keydown ::common/key-args
   :actions/confirm-order-submission ::common/no-args
   :actions/submit-order ::common/no-args
   :actions/submit-unlocked-order-request ::submit-unlocked-order-request-args
   :actions/submit-unlocked-cancel-request ::submit-unlocked-cancel-request-args
   :actions/confirm-cancel-visible-open-orders ::confirm-cancel-visible-open-orders-args
   :actions/close-cancel-visible-open-orders-confirmation ::common/no-args
   :actions/handle-cancel-visible-open-orders-confirmation-keydown ::common/key-args
   :actions/submit-cancel-visible-open-orders-confirmation ::common/no-args
   :actions/cancel-visible-open-orders ::cancel-visible-open-orders-args
   :actions/cancel-order ::cancel-order-args
   :actions/cancel-twap ::cancel-order-args
   :actions/load-user-data ::common/address-args
   :actions/set-funding-modal ::funding-modal-args
   :actions/open-funding-send-modal ::funding-send-open-args
   :actions/open-funding-transfer-modal ::funding-modal-open-args
   :actions/open-funding-withdraw-modal ::funding-modal-open-args
   :actions/open-funding-deposit-modal ::funding-modal-open-args
   :actions/close-funding-modal ::common/no-args
   :actions/handle-funding-modal-keydown ::common/key-args
   :actions/set-funding-modal-field ::funding-modal-field-args
   :actions/search-funding-deposit-assets ::common/single-input-args
   :actions/search-funding-withdraw-assets ::common/single-input-args
   :actions/select-funding-deposit-asset ::common/keyword-or-string-args
   :actions/return-to-funding-deposit-asset-select ::common/no-args
   :actions/return-to-funding-withdraw-asset-select ::common/no-args
   :actions/enter-funding-deposit-amount ::common/single-input-args
   :actions/set-funding-deposit-amount-to-minimum ::common/no-args
   :actions/enter-funding-transfer-amount ::common/single-input-args
   :actions/select-funding-withdraw-asset ::common/keyword-or-string-args
   :actions/enter-funding-withdraw-destination ::common/single-input-args
   :actions/enter-funding-withdraw-amount ::common/single-input-args
   :actions/set-hyperunit-lifecycle ::set-hyperunit-lifecycle-args
   :actions/clear-hyperunit-lifecycle ::common/no-args
   :actions/set-hyperunit-lifecycle-error ::set-hyperunit-lifecycle-error-args
   :actions/set-funding-transfer-direction ::common/boolean-args
   :actions/set-funding-amount-to-max ::common/no-args
   :actions/submit-funding-send ::common/no-args
   :actions/submit-funding-transfer ::common/no-args
   :actions/submit-funding-withdraw ::common/no-args
   :actions/submit-funding-deposit ::common/no-args
   :actions/load-leaderboard-route ::common/path-args
   :actions/load-leaderboard ::common/no-args
   :actions/set-leaderboard-query ::common/single-input-args
   :actions/set-leaderboard-timeframe ::common/keyword-or-string-args
   :actions/set-leaderboard-sort ::common/keyword-or-string-args
   :actions/set-leaderboard-page-size ::common/single-input-args
   :actions/toggle-leaderboard-page-size-dropdown ::common/no-args
   :actions/close-leaderboard-page-size-dropdown ::common/no-args
   :actions/set-leaderboard-page ::common/page-and-max-page-args
   :actions/next-leaderboard-page ::common/max-page-args
   :actions/prev-leaderboard-page ::common/max-page-args
   :actions/load-api-wallet-route ::common/path-args
   :actions/set-api-wallet-form-field ::api-wallet-form-field-args
   :actions/set-api-wallet-sort ::common/keyword-or-string-args
   :actions/generate-api-wallet ::common/no-args
   :actions/open-api-wallet-authorize-modal ::common/no-args
   :actions/open-api-wallet-remove-modal ::api-wallet-row-args
   :actions/close-api-wallet-modal ::common/no-args
   :actions/confirm-api-wallet-modal ::common/no-args
   :actions/load-funding-comparison-route ::common/path-args
   :actions/load-funding-comparison ::common/no-args
   :actions/set-funding-comparison-query ::common/single-input-args
   :actions/set-funding-comparison-timeframe ::common/keyword-or-string-args
   :actions/set-funding-comparison-sort ::common/keyword-or-string-args
   :actions/load-staking-route ::common/path-args
   :actions/load-staking ::common/no-args
   :actions/set-staking-active-tab ::common/keyword-or-string-args
   :actions/toggle-staking-validator-timeframe-menu ::common/no-args
   :actions/close-staking-validator-timeframe-menu ::common/no-args
   :actions/set-staking-validator-timeframe ::common/keyword-or-string-args
   :actions/set-staking-validator-page ::common/single-input-args
   :actions/set-staking-validator-show-all ::common/boolean-args
   :actions/set-staking-validator-sort ::common/keyword-or-string-args
   :actions/open-staking-action-popover ::staking-action-popover-open-args
   :actions/close-staking-action-popover ::common/no-args
   :actions/handle-staking-action-popover-keydown ::common/key-args
   :actions/set-staking-transfer-direction ::common/keyword-or-string-args
   :actions/set-staking-form-field ::staking-form-field-args
   :actions/select-staking-validator ::common/optional-string-args
   :actions/set-staking-deposit-amount-to-max ::common/no-args
   :actions/set-staking-withdraw-amount-to-max ::common/no-args
   :actions/set-staking-delegate-amount-to-max ::common/no-args
   :actions/set-staking-undelegate-amount-to-max ::common/no-args
   :actions/submit-staking-deposit ::common/no-args
   :actions/submit-staking-withdraw ::common/no-args
   :actions/submit-staking-delegate ::common/no-args
   :actions/submit-staking-undelegate ::common/no-args
   :actions/load-vault-route ::common/path-args
   :actions/load-vaults ::common/no-args
   :actions/load-vault-detail ::common/address-args
   :actions/set-vaults-search-query ::common/single-input-args
   :actions/toggle-vaults-filter ::common/keyword-args
   :actions/toggle-vault-detail-chart-timeframe-dropdown ::common/no-args
   :actions/close-vault-detail-chart-timeframe-dropdown ::common/no-args
   :actions/toggle-vault-detail-performance-metrics-timeframe-dropdown ::common/no-args
   :actions/close-vault-detail-performance-metrics-timeframe-dropdown ::common/no-args
   :actions/set-vaults-snapshot-range ::common/keyword-or-string-args
   :actions/set-vaults-sort ::common/keyword-or-string-args
   :actions/set-vaults-user-page-size ::common/single-input-args
   :actions/toggle-vaults-user-page-size-dropdown ::common/no-args
   :actions/close-vaults-user-page-size-dropdown ::common/no-args
   :actions/set-vaults-user-page ::common/page-and-max-page-args
   :actions/next-vaults-user-page ::common/max-page-args
   :actions/prev-vaults-user-page ::common/max-page-args
   :actions/set-vault-detail-tab ::common/keyword-or-string-args
   :actions/set-vault-detail-activity-tab ::common/keyword-or-string-args
   :actions/sort-vault-detail-activity ::common/vault-detail-activity-sort-args
   :actions/toggle-vault-detail-activity-filter-open ::common/no-args
   :actions/close-vault-detail-activity-filter ::common/no-args
   :actions/set-vault-detail-activity-direction-filter ::common/keyword-or-string-args
   :actions/set-vault-detail-chart-series ::common/keyword-or-string-args
   :actions/set-vault-detail-returns-benchmark-search ::common/single-input-args
   :actions/set-vault-detail-returns-benchmark-suggestions-open ::common/boolean-args
   :actions/select-vault-detail-returns-benchmark ::common/optional-string-args
   :actions/remove-vault-detail-returns-benchmark ::common/coin-args
   :actions/handle-vault-detail-returns-benchmark-search-keydown ::common/keydown-with-optional-coin-args
   :actions/clear-vault-detail-returns-benchmark ::common/no-args
   :actions/open-vault-transfer-modal ::common/address-and-mode-args
   :actions/close-vault-transfer-modal ::common/no-args
   :actions/handle-vault-transfer-modal-keydown ::common/key-args
   :actions/set-vault-transfer-amount ::common/single-input-args
   :actions/set-vault-transfer-withdraw-all ::common/boolean-args
   :actions/submit-vault-transfer ::common/no-args
   :actions/navigate (s/or :path (s/tuple ::common/non-empty-string)
                           :path-and-opts (s/tuple ::common/non-empty-string map?))})
