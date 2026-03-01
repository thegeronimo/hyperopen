(ns hyperopen.views.portfolio.vm.equity
  (:require [hyperopen.domain.trading :as trading]
            [hyperopen.portfolio.metrics.parsing :as parsing]))

(defn earn-balance
  [state]
  (let [info (get-in state [:market-data :account-info :agent-webdata])]
    (:earn-balance info)))

(defn top-up-abstraction-enabled?
  [state]
  (boolean (get-in state [:market-data :account-info :agent-webdata :top-up-abstraction-enabled?])))

(defn vault-equity
  [state summary]
  (when summary
    (let [vault-history (get-in state [:portfolio :summaries :all :vaultHistory])]
      (when-let [last-val (some-> vault-history last parsing/history-point-value)]
        (when (parsing/finite-number? last-val)
          last-val)))))

(defn perp-account-equity
  [state metrics]
  (if-let [account-value (:account-value metrics)]
    account-value
    (get-in state [:market-data :account-info :clearinghouse-state :margin-summary :account-value])))

(defn spot-account-equity
  [metrics]
  (:spot-equity metrics))

(defn staking-account-hype
  [state]
  (get-in state [:market-data :account-info :spot-state :balances "HYPE" :total]))

(defn compute-total-equity
  [state metrics summary]
  (let [perp (or (perp-account-equity state metrics) 0)
        spot (or (spot-account-equity metrics) 0)
        vault (or (vault-equity state summary) 0)
        earn (if (top-up-abstraction-enabled? state)
               (or (earn-balance state) 0)
               0)
        total-liquid (+ perp spot earn)
        staking-hype (staking-account-hype state)]
    {:total-liquid total-liquid
     :perp perp
     :spot spot
     :vault vault
     :earn earn
     :staking-hype staking-hype
     :total-equity (+ total-liquid vault)}))