(ns hyperopen.views.portfolio.vm.constants)

(def fourteen-days-ms
  (* 14 24 60 60 1000))

(def summary-scope-options
  [{:value :all
    :label "Perps + Spot + Vaults"}
   {:value :perps
    :label "Perps"}])

(def summary-time-range-options
  [{:value :day
    :label "24H"}
   {:value :week
    :label "7D"}
   {:value :month
    :label "30D"}
   {:value :three-month
    :label "3M"}
   {:value :six-month
    :label "6M"}
   {:value :one-year
    :label "1Y"}
   {:value :two-year
    :label "2Y"}
   {:value :all-time
    :label "All-time"}])

(def chart-tab-options
  [{:value :returns
    :label "Returns"}
   {:value :account-value
    :label "Account Value"}
   {:value :pnl
    :label "PNL"}])

(def chart-y-tick-count
  4)

(def performance-periods-per-year
  365)

(def chart-empty-y-ticks
  [{:y 1} {:y 0} {:y -1}])

(def strategy-series-stroke
  "rgba(148, 163, 184, 1)")

(def benchmark-series-strokes
  ["rgba(217, 119, 6, 1)"
   "rgba(16, 185, 129, 1)"
   "rgba(6, 182, 212, 1)"
   "rgba(139, 92, 246, 1)"])

(def vault-benchmark-prefix
  "vault:")

(def max-vault-benchmark-options
  10)

(def empty-benchmark-markets-signature
  "none")

(def hidden-portfolio-metric-keys
  #{:expected-monthly :expected-yearly})