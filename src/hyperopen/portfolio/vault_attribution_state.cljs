(ns hyperopen.portfolio.vault-attribution-state
  "Shape of the `[:portfolio :vault-attribution]` slot: the raw ledger and the
   per-vault details the vault-attribution model is computed from.

   `:ledger-partial?` is true when the ledger hit the page cap (the newest
   rows may be missing). `:live-vaults-at-load` is the set of vaults in the
   account's live equity rows when the data landed. `:stale?` asks the next load trigger to refetch even
   if the data is young (a vault transfer happened since). `:error` with
   `:loaded-at-ms` set is a failed REFRESH: the last good rows are kept.")

(def state-path [:portfolio :vault-attribution])

(defn default-state
  []
  {:status :idle
   :for-address nil
   :ledger-rows []
   :ledger-partial? false
   :details-by-vault {}
   :errors-by-vault {}
   :loaded-at-ms nil
   :live-vaults-at-load #{}
   :stale? false
   :error nil})

(defn mark-stale
  "State with the held vault attribution (if any) marked for refetch."
  [state]
  (if (map? (get-in state state-path))
    (assoc-in state (conj state-path :stale?) true)
    state))
