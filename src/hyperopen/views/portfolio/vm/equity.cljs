(ns hyperopen.views.portfolio.vm.equity
  (:require [hyperopen.views.account-info.projections :as projections]))

(defn- optional-number
  [value]
  (projections/parse-optional-num value))

(defn- number-or-zero
  [value]
  (if-let [n (optional-number value)]
    n
    0))

(defn top-up-abstraction-enabled?
  [state]
  (= :unified (get-in state [:account :mode])))

(defn earn-balance
  [state]
  (number-or-zero (get-in state [:borrow-lend :total-supplied-usd])))

(defn vault-equity
  [state summary]
  (or (optional-number (get-in state [:webdata2 :totalVaultEquity]))
      (optional-number (:totalVaultEquity summary))
      0))

(defn perp-account-equity
  [state metrics]
  (or (optional-number (get-in state [:webdata2 :clearinghouseState :marginSummary :accountValue]))
      (optional-number (get-in state [:webdata2 :clearinghouseState :crossMarginSummary :accountValue]))
      (optional-number (:cross-account-value metrics))
      (optional-number (:perps-value metrics))
      0))

(defn spot-account-equity
  [metrics]
  (number-or-zero (:spot-equity metrics)))

(defn staking-account-hype
  [state]
  (or (optional-number (get-in state [:staking :total-hype]))
      (optional-number (get-in state [:staking :total]))
      0))

(defn staking-value-usd
  [_state _staking-hype]
  0)

(defn compute-total-equity
  [{:keys [top-up-enabled?
           vault-equity
           spot-equity
           staking-value-usd
           perp-equity
           earn-equity]}]
  (let [base-total (+ (number-or-zero vault-equity)
                      (number-or-zero spot-equity)
                      (number-or-zero staking-value-usd))]
    (if top-up-enabled?
      base-total
      (+ base-total
         (number-or-zero perp-equity)
         (number-or-zero earn-equity)))))
