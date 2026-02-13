(ns hyperopen.views.account-info.vm
  (:require [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.websocket-freshness :as ws-freshness]))

(defn account-info-vm [state]
  (let [selected-tab (get-in state [:account-info :selected-tab] :balances)
        webdata2 (merge (:webdata2 state) (get state :orders))
        loading? (get-in state [:account-info :loading] false)
        error (get-in state [:account-info :error])
        positions-sort (get-in state [:account-info :positions-sort] {:column nil :direction :asc})
        balances-sort (get-in state [:account-info :balances-sort] {:column nil :direction :asc})
        hide-small? (get-in state [:account-info :hide-small-balances?] false)
        perp-dex-states (:perp-dex-clearinghouse state)
        balance-rows (projections/build-balance-rows webdata2 (:spot state) (:account state))
        positions (projections/collect-positions webdata2 perp-dex-states)
        open-orders (projections/normalized-open-orders (get-in webdata2 [:open-orders])
                                                        (get-in webdata2 [:open-orders-snapshot])
                                                        (get-in webdata2 [:open-orders-snapshot-by-dex]))
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        trade-history-state (assoc (get-in state [:account-info :trade-history] {})
                                   :market-by-key market-by-key)
        funding-history-state (get-in state [:account-info :funding-history] {})
        order-history-state (assoc (get-in state [:account-info :order-history] {})
                                   :market-by-key market-by-key)
        tab-counts {:open-orders (count open-orders)
                    :positions (count positions)
                    :balances (count balance-rows)}
        open-orders-sort (get-in state [:account-info :open-orders-sort] {:column "Time" :direction :desc})
        websocket-health (or (:websocket-health state)
                             (get-in state [:websocket :health]))
        wallet-address (get-in state [:wallet :address])
        freshness-cues {:positions (ws-freshness/surface-cue websocket-health
                                                             {:topic "webData2"
                                                              :selector (when wallet-address
                                                                          {:user wallet-address})
                                                              :live-prefix "Updated"
                                                              :na-prefix "Last update"})
                        :open-orders (ws-freshness/surface-cue websocket-health
                                                               {:topic "openOrders"
                                                                :selector (when wallet-address
                                                                            {:user wallet-address})
                                                                :live-prefix "Updated"
                                                                :na-prefix "Last update"})}]
    {:selected-tab selected-tab
     :loading? loading?
     :error error
     :positions-sort positions-sort
     :balances-sort balances-sort
     :open-orders-sort open-orders-sort
     :hide-small? hide-small?
     :perp-dex-states perp-dex-states
     :webdata2 webdata2
     :balance-rows balance-rows
     :open-orders open-orders
     :trade-history-rows (get-in webdata2 [:fills])
     :trade-history-state trade-history-state
     :funding-history-rows (get-in webdata2 [:fundings])
     :funding-history-raw (get-in webdata2 [:fundings-raw])
     :funding-history-state funding-history-state
     :order-history-rows (get-in webdata2 [:order-history])
     :order-history-state order-history-state
     :tab-counts tab-counts
     :freshness-cues freshness-cues}))
