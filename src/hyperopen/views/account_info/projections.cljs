(ns hyperopen.views.account-info.projections
  (:require [hyperopen.views.account-info.projections.balances :as balances]
            [hyperopen.views.account-info.projections.coins :as coins]
            [hyperopen.views.account-info.projections.order-history :as order-history]
            [hyperopen.views.account-info.projections.orders :as orders]
            [hyperopen.views.account-info.projections.parse :as parse]
            [hyperopen.views.account-info.projections.positions :as positions]
            [hyperopen.views.account-info.projections.twaps :as twaps]
            [hyperopen.views.account-info.projections.trades :as trades]))

;; Parsing helpers
(def parse-num parse/parse-num)
(def parse-optional-num parse/parse-optional-num)
(def parse-optional-int parse/parse-optional-int)
(def parse-time-ms parse/parse-time-ms)
(def boolean-value parse/boolean-value)
(def non-blank-text parse/non-blank-text)
(def normalize-id parse/normalize-id)

;; Coin/display helpers
(def title-case-label coins/title-case-label)
(def parse-coin-namespace coins/parse-coin-namespace)
(def symbol-base-label coins/symbol-base-label)
(def resolve-coin-display coins/resolve-coin-display)

;; Open orders and order history
(def resolve-open-order-oid orders/resolve-open-order-oid)
(def order-history-status-key order-history/order-history-status-key)
(def order-history-status-label order-history/order-history-status-label)
(def normalize-open-order orders/normalize-open-order)
(def open-orders-seq orders/open-orders-seq)
(def open-orders-by-dex orders/open-orders-by-dex)
(def open-orders-source orders/open-orders-source)
(def pending-cancel-oid-set orders/pending-cancel-oid-set)
(def order-pending-cancel? orders/order-pending-cancel?)
(def normalized-open-orders orders/normalized-open-orders)
(def open-order-for-active-asset? orders/open-order-for-active-asset?)
(def normalized-open-orders-for-active-asset orders/normalized-open-orders-for-active-asset)
(def normalize-order-history-row order-history/normalize-order-history-row)
(def normalized-order-history order-history/normalized-order-history)

;; Balances and positions
(def normalize-balance-contract-id balances/normalize-balance-contract-id)
(def portfolio-usdc-value balances/portfolio-usdc-value)
(def build-balance-rows balances/build-balance-rows)
(def position-unique-key positions/position-unique-key)
(def collect-positions positions/collect-positions)

;; Trade history
(def trade-history-coin trades/trade-history-coin)
(def trade-history-time-ms trades/trade-history-time-ms)
(def trade-history-first-parseable-row-value trades/trade-history-first-parseable-row-value)
(def trade-history-value-number trades/trade-history-value-number)
(def trade-history-fee-number trades/trade-history-fee-number)
(def trade-history-closed-pnl-number trades/trade-history-closed-pnl-number)
(def trade-history-row-id trades/trade-history-row-id)

;; TWAP
(def normalize-active-twap-row twaps/normalize-active-twap-row)
(def normalized-active-twaps twaps/normalized-active-twaps)
(def normalize-twap-history-row twaps/normalize-twap-history-row)
(def normalized-twap-history twaps/normalized-twap-history)
(def normalize-twap-slice-fill twaps/normalize-twap-slice-fill)
(def normalized-twap-slice-fills twaps/normalized-twap-slice-fills)
