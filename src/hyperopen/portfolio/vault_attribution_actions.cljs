(ns hyperopen.portfolio.vault-attribution-actions
  "Pure action that decides whether the Portfolio page needs to (re)load the
   data behind vault attribution."
  (:require [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.vault-attribution-state :as attribution-state]
            [hyperopen.portfolio.vault-attribution.equity :as equity]))

(def fresh-for-ms
  (* 5 60 1000))

(defn- skip-load?
  "Skip while a load for the address is in flight, or while its data is
   younger than `fresh-for-ms` AND not marked stale (a vault transfer) AND the
   live vault equity still agrees with the snapshot (money moved otherwise).
   A failed load (first or refresh) is never skipped."
  [state slot address now-ms]
  (and (= address (:for-address slot))
       (case (:status slot)
         :loading true
         :ready (let [loaded-at-ms (:loaded-at-ms slot)]
                  (and (number? loaded-at-ms)
                       (< (- now-ms loaded-at-ms) fresh-for-ms)
                       (not (:stale? slot))
                       (not (equity/drifted? state slot address now-ms))))
         false)))

(defn load-portfolio-vault-attribution
  ([state]
   (load-portfolio-vault-attribution state (platform/now-ms)))
  ([state now-ms]
   (let [address (account-context/effective-account-address state)]
     (if (and address
              (not (skip-load? state (get-in state attribution-state/state-path) address now-ms)))
       [[:effects/api-fetch-portfolio-vault-attribution address]]
       []))))
