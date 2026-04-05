(ns hyperopen.account.test-support.lifecycle
  (:require [hyperopen.system :as system]))

(defn indexed-address
  [n]
  (str "0x" (.padStart (str n) 40 "0")))

(def owner-address
  (indexed-address 1))

(def spectate-address
  (indexed-address 2))

(def trader-route-address
  (indexed-address 3))

(defn state-for-kind
  [kind]
  (case kind
    :owner
    (-> (system/default-store-state)
        (assoc-in [:wallet :connected?] true)
        (assoc-in [:wallet :address] owner-address)
        (assoc-in [:router :path] "/trade"))

    :spectate
    (-> (state-for-kind :owner)
        (assoc-in [:account-context :spectate-mode]
                  {:active? true
                   :address spectate-address
                   :started-at-ms 1}))

    :trader-route
    (-> (state-for-kind :spectate)
        (assoc-in [:router :path] (str "/portfolio/trader/" trader-route-address)))

    :disconnected
    (-> (system/default-store-state)
        (assoc-in [:wallet :connected?] false)
        (assoc-in [:wallet :address] nil)
        (assoc-in [:account-context :spectate-mode]
                  {:active? false
                   :address nil
                   :started-at-ms nil})
        (assoc-in [:router :path] "/trade"))))

(defn seed-stale-account-surfaces
  [state]
  (-> state
      (assoc :webdata2 {:clearinghouseState {:assetPositions [{:position {:coin "BTC"
                                                                           :szi "1.0"}}]
                                             :marginSummary {:accountValue "100.0"}
                                             :crossMarginSummary {:accountValue "100.0"}}
                        :open-orders [{:coin "BTC" :oid 101}]
                        :fills [{:tid 77}]
                        :fundings [{:id "funding-1"}]
                        :fundings-raw [{:id "funding-1"}]})
      (assoc-in [:orders :open-orders-hydrated?] true)
      (assoc-in [:orders :open-orders] [{:coin "BTC" :oid 101}])
      (assoc-in [:orders :open-orders-snapshot] [{:coin "BTC" :oid 102}])
      (assoc-in [:orders :open-orders-snapshot-by-dex] {"dex-a" [{:coin "BTC" :oid 103}]})
      (assoc-in [:orders :open-error] "stale-open-error")
      (assoc-in [:orders :open-error-category] :stale)
      (assoc-in [:orders :fills] [{:tid 78}])
      (assoc-in [:orders :fills-error] "stale-fills-error")
      (assoc-in [:orders :fills-error-category] :stale)
      (assoc-in [:orders :fundings-raw] [{:id "funding-2"}])
      (assoc-in [:orders :fundings] [{:id "funding-2"}])
      (assoc-in [:orders :order-history] [{:oid 104}])
      (assoc-in [:orders :ledger] [{:delta 1}])
      (assoc-in [:orders :twap-states] [{:coin "BTC"}])
      (assoc-in [:orders :twap-history] [{:time 1000}])
      (assoc-in [:orders :twap-slice-fills] [{:tid 79}])
      (assoc-in [:orders :pending-cancel-oids] #{101})
      (assoc-in [:account-info :funding-history :loading?] true)
      (assoc-in [:account-info :funding-history :error] "stale-funding-history-error")
      (assoc-in [:account-info :order-history :loading?] true)
      (assoc-in [:account-info :order-history :error] "stale-order-history-error")
      (assoc-in [:account-info :order-history :loaded-at-ms] 1700000000000)
      (assoc-in [:account-info :order-history :loaded-for-address] spectate-address)
      (assoc-in [:spot :clearinghouse-state] {:balances [{:coin "USDC"
                                                          :total "5.0"}]})
      (assoc-in [:spot :loading-balances?] true)
      (assoc-in [:spot :error] "stale-spot-error")
      (assoc-in [:spot :error-category] :stale)
      (assoc :perp-dex-clearinghouse {"dex-a" {:assetPositions []}})
      (assoc :perp-dex-clearinghouse-error "stale-perp-error")
      (assoc :perp-dex-clearinghouse-error-category :stale)
      (assoc-in [:portfolio :summary-by-key] {:day {:vlm 10}})
      (assoc-in [:portfolio :user-fees] {:dailyUserVlm [[0 1]]})
      (assoc-in [:portfolio :ledger-updates] [{:delta 2}])
      (assoc-in [:portfolio :loading?] true)
      (assoc-in [:portfolio :user-fees-loading?] true)
      (assoc-in [:portfolio :error] "stale-portfolio-error")
      (assoc-in [:portfolio :user-fees-error] "stale-user-fees-error")
      (assoc-in [:portfolio :ledger-error] "stale-ledger-error")
      (assoc-in [:portfolio :loaded-at-ms] 1700000000001)
      (assoc-in [:portfolio :user-fees-loaded-at-ms] 1700000000002)
      (assoc-in [:portfolio :ledger-loaded-at-ms] 1700000000003)
      (assoc :account {:mode :unified
                       :abstraction-raw {:kind :agent}})))
