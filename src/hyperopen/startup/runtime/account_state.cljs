(ns hyperopen.startup.runtime.account-state)

(defn reset-account-surface-state
  [state]
  ;; Clear every account-derived surface when the effective account changes so
  ;; disconnected, spectate, and connected transitions cannot drift apart.
  (-> state
      (assoc :webdata2 nil)
      (assoc-in [:orders :open-orders] [])
      (assoc-in [:orders :open-orders-hydrated?] false)
      (assoc-in [:orders :open-orders-snapshot] [])
      (assoc-in [:orders :open-orders-snapshot-by-dex] {})
      (assoc-in [:orders :open-error] nil)
      (assoc-in [:orders :open-error-category] nil)
      (assoc-in [:orders :fills] [])
      (assoc-in [:orders :fills-error] nil)
      (assoc-in [:orders :fills-error-category] nil)
      (assoc-in [:orders :fundings-raw] [])
      (assoc-in [:orders :fundings] [])
      (assoc-in [:orders :order-history] [])
      (assoc-in [:orders :ledger] [])
      (assoc-in [:orders :twap-states] [])
      (assoc-in [:orders :twap-history] [])
      (assoc-in [:orders :twap-slice-fills] [])
      (assoc-in [:orders :pending-cancel-oids] nil)
      (assoc-in [:orders :recently-canceled-oids] #{})
      (assoc-in [:orders :recently-canceled-order-keys] #{})
      (update-in [:account-info :funding-history]
                 (fn [funding-history]
                   (-> (or funding-history {})
                       (assoc :loading? false)
                       (assoc :error nil))))
      (update-in [:account-info :order-history]
                 (fn [order-history]
                   (-> (or order-history {})
                       (assoc :loading? false)
                       (assoc :error nil)
                       (assoc :loaded-at-ms nil)
                       (assoc :loaded-for-address nil))))
      (assoc-in [:spot :clearinghouse-state] nil)
      (assoc-in [:spot :loading-balances?] false)
      (assoc-in [:spot :error] nil)
      (assoc-in [:spot :error-category] nil)
      (assoc-in [:perp-dex-clearinghouse] {})
      (assoc-in [:perp-dex-clearinghouse-error] nil)
      (assoc-in [:perp-dex-clearinghouse-error-category] nil)
      (assoc-in [:vaults :user-equities] [])
      (assoc-in [:vaults :user-equity-by-address] {})
      (assoc-in [:vaults :user-equities-for-address] nil)
      (assoc-in [:vaults :user-equities-error-for-address] nil)
      (assoc-in [:vaults :user-equities-request-id] nil)
      (assoc-in [:vaults :loading :user-equities?] false)
      (assoc-in [:vaults :loading :user-equities-for-address] nil)
      (assoc-in [:vaults :errors :user-equities] nil)
      (assoc-in [:vaults :loaded-at-ms :user-equities] nil)
      ;; HyperEVM balances are per address; in-flight transactions are not
      ;; and must survive account switches.
      (assoc-in [:hyperevm :balances :by-address] {})
      (update :portfolio assoc
              :summary-by-key {} :user-fees nil :ledger-updates []
              :loading? false :user-fees-loading? false :user-fees-loading-for-address nil :ledger-loading? false
              :error nil :user-fees-error nil :user-fees-error-for-address nil :ledger-error nil
              :loaded-at-ms nil :user-fees-loaded-at-ms nil :user-fees-loaded-for-address nil
              :ledger-loaded-at-ms nil)
      (assoc :account {:mode :classic :abstraction-raw nil})))
