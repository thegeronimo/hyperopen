(ns hyperopen.schema.runtime-registration.portfolio)

(def effect-binding-rows
  [])

(def action-binding-rows
  [[:actions/toggle-portfolio-summary-scope-dropdown :toggle-portfolio-summary-scope-dropdown]
   [:actions/select-portfolio-summary-scope :select-portfolio-summary-scope]
   [:actions/toggle-portfolio-summary-time-range-dropdown :toggle-portfolio-summary-time-range-dropdown]
   [:actions/toggle-portfolio-performance-metrics-time-range-dropdown :toggle-portfolio-performance-metrics-time-range-dropdown]
   [:actions/select-portfolio-summary-time-range :select-portfolio-summary-time-range]
   [:actions/select-portfolio-chart-tab :select-portfolio-chart-tab]
   [:actions/set-portfolio-account-info-tab :set-portfolio-account-info-tab]
   [:actions/set-portfolio-chart-hover :set-portfolio-chart-hover]
   [:actions/clear-portfolio-chart-hover :clear-portfolio-chart-hover]
   [:actions/set-portfolio-returns-benchmark-search :set-portfolio-returns-benchmark-search]
   [:actions/set-portfolio-returns-benchmark-suggestions-open :set-portfolio-returns-benchmark-suggestions-open]
   [:actions/select-portfolio-returns-benchmark :select-portfolio-returns-benchmark]
   [:actions/remove-portfolio-returns-benchmark :remove-portfolio-returns-benchmark]
   [:actions/handle-portfolio-returns-benchmark-search-keydown :handle-portfolio-returns-benchmark-search-keydown]
   [:actions/clear-portfolio-returns-benchmark :clear-portfolio-returns-benchmark]])
