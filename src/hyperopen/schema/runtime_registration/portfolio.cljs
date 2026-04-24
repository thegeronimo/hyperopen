(ns hyperopen.schema.runtime-registration.portfolio)

(def effect-binding-rows
  [[:effects/run-portfolio-optimizer :run-portfolio-optimizer]
   [:effects/load-portfolio-optimizer-history :load-portfolio-optimizer-history]
   [:effects/load-portfolio-optimizer-scenario-index
    :load-portfolio-optimizer-scenario-index]
   [:effects/load-portfolio-optimizer-scenario
    :load-portfolio-optimizer-scenario]
   [:effects/archive-portfolio-optimizer-scenario
    :archive-portfolio-optimizer-scenario]
   [:effects/duplicate-portfolio-optimizer-scenario
    :duplicate-portfolio-optimizer-scenario]
   [:effects/save-portfolio-optimizer-scenario :save-portfolio-optimizer-scenario]])

(def action-binding-rows
  [[:actions/toggle-portfolio-summary-scope-dropdown :toggle-portfolio-summary-scope-dropdown]
   [:actions/select-portfolio-summary-scope :select-portfolio-summary-scope]
   [:actions/toggle-portfolio-summary-time-range-dropdown :toggle-portfolio-summary-time-range-dropdown]
   [:actions/toggle-portfolio-performance-metrics-time-range-dropdown :toggle-portfolio-performance-metrics-time-range-dropdown]
   [:actions/open-portfolio-fee-schedule :open-portfolio-fee-schedule]
   [:actions/close-portfolio-fee-schedule :close-portfolio-fee-schedule]
   [:actions/toggle-portfolio-fee-schedule-referral-dropdown :toggle-portfolio-fee-schedule-referral-dropdown]
   [:actions/toggle-portfolio-fee-schedule-staking-dropdown :toggle-portfolio-fee-schedule-staking-dropdown]
   [:actions/toggle-portfolio-fee-schedule-maker-rebate-dropdown :toggle-portfolio-fee-schedule-maker-rebate-dropdown]
   [:actions/toggle-portfolio-fee-schedule-market-dropdown :toggle-portfolio-fee-schedule-market-dropdown]
   [:actions/select-portfolio-fee-schedule-referral-discount :select-portfolio-fee-schedule-referral-discount]
   [:actions/select-portfolio-fee-schedule-staking-tier :select-portfolio-fee-schedule-staking-tier]
   [:actions/select-portfolio-fee-schedule-maker-rebate-tier :select-portfolio-fee-schedule-maker-rebate-tier]
   [:actions/select-portfolio-fee-schedule-market-type :select-portfolio-fee-schedule-market-type]
   [:actions/handle-portfolio-fee-schedule-keydown :handle-portfolio-fee-schedule-keydown]
   [:actions/select-portfolio-summary-time-range :select-portfolio-summary-time-range]
   [:actions/select-portfolio-chart-tab :select-portfolio-chart-tab]
   [:actions/set-portfolio-account-info-tab :set-portfolio-account-info-tab]
   [:actions/set-portfolio-returns-benchmark-search :set-portfolio-returns-benchmark-search]
   [:actions/set-portfolio-returns-benchmark-suggestions-open :set-portfolio-returns-benchmark-suggestions-open]
   [:actions/select-portfolio-returns-benchmark :select-portfolio-returns-benchmark]
   [:actions/remove-portfolio-returns-benchmark :remove-portfolio-returns-benchmark]
   [:actions/handle-portfolio-returns-benchmark-search-keydown :handle-portfolio-returns-benchmark-search-keydown]
   [:actions/clear-portfolio-returns-benchmark :clear-portfolio-returns-benchmark]
   [:actions/open-portfolio-volume-history :open-portfolio-volume-history]
   [:actions/close-portfolio-volume-history :close-portfolio-volume-history]
   [:actions/handle-portfolio-volume-history-keydown :handle-portfolio-volume-history-keydown]
   [:actions/set-portfolio-optimizer-objective-kind :set-portfolio-optimizer-objective-kind]
   [:actions/set-portfolio-optimizer-return-model-kind :set-portfolio-optimizer-return-model-kind]
   [:actions/set-portfolio-optimizer-risk-model-kind :set-portfolio-optimizer-risk-model-kind]
   [:actions/set-portfolio-optimizer-constraint :set-portfolio-optimizer-constraint]
   [:actions/set-portfolio-optimizer-objective-parameter :set-portfolio-optimizer-objective-parameter]
   [:actions/set-portfolio-optimizer-execution-assumption :set-portfolio-optimizer-execution-assumption]
   [:actions/set-portfolio-optimizer-instrument-filter :set-portfolio-optimizer-instrument-filter]
   [:actions/set-portfolio-optimizer-asset-override :set-portfolio-optimizer-asset-override]
   [:actions/set-portfolio-optimizer-universe-from-current :set-portfolio-optimizer-universe-from-current]
   [:actions/load-portfolio-optimizer-history-from-draft :load-portfolio-optimizer-history-from-draft]
   [:actions/save-portfolio-optimizer-scenario-from-current :save-portfolio-optimizer-scenario-from-current]
   [:actions/load-portfolio-optimizer-route :load-portfolio-optimizer-route]
   [:actions/archive-portfolio-optimizer-scenario :archive-portfolio-optimizer-scenario]
   [:actions/duplicate-portfolio-optimizer-scenario :duplicate-portfolio-optimizer-scenario]
   [:actions/run-portfolio-optimizer-from-draft :run-portfolio-optimizer-from-draft]
   [:actions/run-portfolio-optimizer :run-portfolio-optimizer]])
