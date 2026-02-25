(ns hyperopen.views.account-info.test-support.fixtures)

(def sample-balance-row
  {:key "spot-0"
   :coin "USDC (Spot)"
   :total-balance 150.12
   :available-balance 120.45
   :usdc-value 150.12
   :pnl-value -1.2
   :pnl-pct -0.8
   :amount-decimals 2})

(def default-sort-state {:column nil :direction :asc})

(def sample-position-data
  {:position {:coin "HYPE"
              :leverage {:value 5}
              :szi "12.34"
              :positionValue "85081.58"
              :entryPx "34.51"
              :unrealizedPnl "-8206.13"
              :returnOnEquity "-0.088"
              :liquidationPx "12.10"
              :marginUsed "2400"
              :cumFunding {:allTime "10.0"}}})

(def sample-order-history-row
  {:order {:coin "xyz:NVDA"
           :oid 307891000622
           :side "B"
           :origSz "0.500"
           :remainingSz "0.000"
           :limitPx "0.000"
           :orderType "Market"
           :reduceOnly false
           :isTrigger false
           :isPositionTpsl false
           :timestamp 1700000000000}
   :status "filled"
   :statusTimestamp 1700000005000})

(defn order-history-row
  [idx]
  {:order {:coin "PUMP"
           :oid idx
           :side (if (odd? idx) "B" "A")
           :origSz "1.0"
           :remainingSz "0.0"
           :limitPx "0.001"
           :orderType "Limit"
           :reduceOnly false
           :isTrigger false
           :isPositionTpsl false
           :timestamp (+ 1700000000000 idx)}
   :status (if (odd? idx) "filled" "canceled")
   :statusTimestamp (+ 1700000000000 idx)})

(defn funding-history-row
  [idx]
  {:id (str idx)
   :time-ms (+ 1700000000000 idx)
   :coin (if (odd? idx) "BTC" "ETH")
   :position-size-raw (if (odd? idx) 1.0 -1.0)
   :payment-usdc-raw (/ idx 1000)
   :funding-rate-raw (/ idx 1000000)})

(defn trade-history-row
  [idx]
  {:tid idx
   :coin (if (odd? idx) "BTC" "ETH")
   :side (if (odd? idx) "B" "A")
   :sz "1.25"
   :px "100.5"
   :fee "0.05"
   :time (+ 1700000000000 idx)})

(def sample-account-info-state
  {:account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-coin-search ""
                  :balances-sort default-sort-state
                  :positions-sort default-sort-state
                  :positions {:direction-filter :all
                              :coin-search ""
                              :filter-open? false}
                  :open-orders-sort {:column "Time" :direction :desc}
                  :order-history {:sort {:column "Time" :direction :desc}
                                  :status-filter :all
                                  :coin-search ""
                                  :filter-open? false
                                  :loading? false
                                  :error nil
                                  :request-id 0}}
   :webdata2 {}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :order-history []}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}})

(defn sample-position-row
  ([coin leverage size]
   (sample-position-row coin leverage size nil))
  ([coin leverage size dex]
   {:position {:coin coin
               :leverage {:value leverage}
               :szi size
               :positionValue "100.00"
               :entryPx "10.00"
               :unrealizedPnl "1.25"
               :returnOnEquity "0.10"
               :liquidationPx "4.20"
               :marginUsed "12.00"
               :cumFunding {:allTime "0.5"}}
    :dex dex}))
