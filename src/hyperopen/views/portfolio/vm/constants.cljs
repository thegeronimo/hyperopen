(ns hyperopen.views.portfolio.vm.constants)

(def fourteen-days-ms
  (* 14 24 60 60 1000))

(def summary-scope-options
  [{:value :all
    :label "Perps + Spot + Vaults"}
   {:value :perps
    :label "Perps"}
   {:value :vaults
    :label "Vaults"}])

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
