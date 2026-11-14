(ns hyperopen.views.portfolio.vaults-tab-fixtures
  (:require [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]))

(def address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def vault-c "0x2222222222222222222222222222222222222222")

(def ledger
  "vault-a: open, +1000 then +500 later. vault-b: closed (nil name).
   vault-c: not in the ledger at all, only in the account's live
   `userVaultEquities` rows (an orphan: shown, but incomplete)."
  [(fx/deposit (- fx/now (* 400 fx/day)) fx/vault-a "1000")
   (fx/deposit (- fx/now (* 200 fx/day)) fx/vault-a "500")
   (fx/deposit (- fx/now (* 300 fx/day)) fx/vault-b "2000")
   (fx/withdraw (- fx/now (* 100 fx/day)) fx/vault-b
                {:requested "2200" :commission "0" :basis "2000" :net "2200"})])

(defn ready-slot
  [& {:as overrides}]
  (merge {:status :ready
          :for-address address
          :ledger-rows ledger
          :details-by-vault {fx/vault-a {:name "HLP" :follower-state {:vault-equity 1800}}
                             fx/vault-b {:name nil}}
          :errors-by-vault {}
          :loaded-at-ms fx/now
          :error nil}
         overrides))

(def live-vaults
  "The account's live vault rows and the vault list naming the orphan."
  {:user-equities [{:vault-address fx/vault-a :equity 1800}
                   {:vault-address vault-c :equity 500}]
   :user-equities-for-address address
   :merged-index-rows [{:vault-address vault-c :name "Orphan"}]})

(defn state-with
  [slot]
  {:wallet {:address address}
   :portfolio {:vault-attribution slot}
   :vaults live-vaults})

(defn state-without-live
  "`state-with` minus the live vault rows (no orphan, no live fallback)."
  [slot]
  (dissoc (state-with slot) :vaults))
