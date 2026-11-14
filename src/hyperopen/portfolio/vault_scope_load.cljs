(ns hyperopen.portfolio.vault-scope-load
  "Emits the vault-attribution load for the Portfolio views that need it: the
   Vaults summary scope and the Vaults account tab. Kept apart from
   `portfolio.actions` so every route/scope trigger shares one rule."
  (:require [hyperopen.portfolio.vault-attribution-actions :as vault-attribution-actions]))

(defn load-effects
  "Effects that load the vault data when the (already-normalized) summary
   `scope` is `:vaults` or the (already-normalized) account `account-tab` is
   `:vaults`; empty otherwise. Safe to emit repeatedly: the load is a no-op
   while fresh or already in flight."
  ([state scope]
   (load-effects state scope nil))
  ([state scope account-tab]
   (if (or (= :vaults scope) (= :vaults account-tab))
     (vault-attribution-actions/load-portfolio-vault-attribution state)
     [])))
