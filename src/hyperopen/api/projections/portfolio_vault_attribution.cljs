(ns hyperopen.api.projections.portfolio-vault-attribution
  "State writes for the Portfolio vault-attribution load. Every write is
   dropped when the effective account is no longer the one the load was for,
   so a slow load can never paint another account's vaults."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]
            [hyperopen.portfolio.vault-attribution-state :as attribution-state]
            [hyperopen.portfolio.vault-attribution.equity :as equity]))

(def ^:private path attribution-state/state-path)

(defn- current-address?
  [state address]
  (and address
       (= address (account-context/effective-account-address state))))

(defn- release-stale-loading
  "A load whose account went away must not leave `:loading` behind for that
   address: returning to the account would then never refetch."
  [state address]
  (let [slot (get-in state path)]
    (if (and (= :loading (:status slot))
             (= address (:for-address slot)))
      (assoc-in state path (attribution-state/default-state))
      state)))

(defn begin-load
  "Marks a load for `address` as in flight. Rows already held for the same
   address stay visible during a refresh; anything else is replaced."
  [state address]
  (if (current-address? state address)
    (let [slot (get-in state path)
          same? (= address (:for-address slot))
          base (if same? slot (attribution-state/default-state))]
      (assoc-in state path
                (assoc base
                       :status :loading
                       :for-address address
                       :stale? false
                       :error nil)))
    state))

(defn apply-success
  "`:stale?` set while this load was in flight (a vault transfer landed) is
   kept, so the next trigger refetches. `:live-vaults-at-load` records the
   vaults in the account's live rows now, so the drift check never counts
   them as new money (see `equity/drifted?`)."
  [state address {:keys [ledger-rows ledger-partial? details-by-vault errors-by-vault
                         loaded-at-ms]}]
  (if (current-address? state address)
    (let [slot (get-in state path)]
      (assoc-in state path
                {:status :ready
                 :for-address address
                 :ledger-rows (vec ledger-rows)
                 :ledger-partial? (boolean ledger-partial?)
                 :details-by-vault (or details-by-vault {})
                 :errors-by-vault (or errors-by-vault {})
                 :loaded-at-ms loaded-at-ms
                 :live-vaults-at-load (or (equity/live-vaults-at-load state address) #{})
                 :stale? (boolean (and (= address (:for-address slot)) (:stale? slot)))
                 :error nil}))
    (release-stale-loading state address)))

(defn apply-error
  "A failed first load is an error state. A failed REFRESH keeps the last
   good rows (and their `:loaded-at-ms`) with `:status :error` and the
   message, so the data stays on screen and the next trigger retries."
  [state address err]
  (if (current-address? state address)
    (let [{:keys [message]} (api-errors/normalize-error err)
          slot (get-in state path)
          held? (and (= address (:for-address slot))
                     (number? (:loaded-at-ms slot)))]
      (assoc-in state path
                (assoc (if held? slot (attribution-state/default-state))
                       :status :error
                       :for-address address
                       :error message)))
    (release-stale-loading state address)))
