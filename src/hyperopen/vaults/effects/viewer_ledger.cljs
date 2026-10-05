(ns hyperopen.vaults.effects.viewer-ledger
  "Follow-up fetch for the vault detail position band: the viewer's own
   non-funding ledger from their first deposit into the vault.")

(defn fetch-viewer-ledger!
  "Never rejects: the band degrades to followerState numbers without it.
   Skips unless the details payload carries the viewer's `vaultEntryTime`."
  [{:keys [store
           vault-address
           user-address
           request-user-non-funding-ledger-updates!
           begin-vault-viewer-ledger-load
           apply-vault-viewer-ledger-success
           apply-vault-viewer-ledger-error]}
   request-opts
   payload]
  (let [entry-time-ms (get-in payload [:follower-state :vault-entry-time-ms])]
    (if (and user-address
             (number? entry-time-ms)
             (fn? request-user-non-funding-ledger-updates!)
             (fn? begin-vault-viewer-ledger-load)
             (fn? apply-vault-viewer-ledger-success)
             (fn? apply-vault-viewer-ledger-error))
      (do
        (swap! store begin-vault-viewer-ledger-load vault-address user-address)
        (-> (request-user-non-funding-ledger-updates! user-address entry-time-ms nil request-opts)
            (.then (fn [rows]
                     (swap! store apply-vault-viewer-ledger-success vault-address user-address rows)
                     nil))
            (.catch (fn [err]
                      (swap! store apply-vault-viewer-ledger-error vault-address user-address err)
                      nil))))
      (js/Promise.resolve nil))))
